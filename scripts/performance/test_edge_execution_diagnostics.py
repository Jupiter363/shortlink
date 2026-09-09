"""Offline P0 diagnostics/correlation tests plus unchanged sender/wire regressions.

No service or network is started here. --lua-command must name an existing
isolated stdin interpreter; the caller owns its lifecycle. Kafka sockets and
timers in the suites are mocks. The default codec is the runtime's pinned codec.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/performance"))
from test_edge_sender_linger import program as logger_program, decode_output
from test_edge_kafka_batch import load_program as kafka_program, lua_literal

PLUGINS = ROOT / "deploy/apisix/plugins/apisix/plugins"

LOGGER_CASES = r'''
local execution=require("apisix.plugins.shortlink-execution-diagnostics")
local native_clock=execution.clock
local function diagnostic_rows()
  local rows={}
  for _,key in ipairs(ngx.shared.shortlink_edge_metrics:get_keys(0)) do
    if key:sub(1,5)=="exec:" then rows[key]=require("apisix.core").json.decode(ngx.shared.shortlink_edge_metrics:get(key)) end
  end
  return rows
end
local function diagnostic()
  local _,row=next(diagnostic_rows());assert(row,"missing diagnostic snapshot");return row.diagnostics
end
local function enabled_begin(batch,linger)
  local plugin,c=linger_begin(1,batch or 32,linger or 0)
  c.execution_diagnostics=true
  state.exec_refresh_calls=0
  ngx.update_time=function() state.exec_refresh_calls=state.exec_refresh_calls+1 end
  execution.clock=function(record)
    state.exec_clock_calls=(state.exec_clock_calls or 0)+1
    if state.exec_clock_failure then record.invalid=record.invalid+1;return nil end
    return state.now
  end
  local dict=ngx.shared.shortlink_edge_metrics;local write=dict.safe_set
  state.exec_writes=0
  dict.safe_set=function(self,key,value)
    if key:sub(1,5)=="exec:" then
      state.exec_writes=state.exec_writes+1
      if state.exec_write_failure then return nil,"diagnostic capacity" end
    end
    return write(self,key,value)
  end
  return plugin,c
end
case("EX01","real monotonic clock and bounded histogram validation",function()
  reset();local r=execution.new();local a=native_clock(r);local z=native_clock(r)
  assert(a and z and z>=a);eq(r.invalid,0)
  execution.seconds(r,"ack_receive",0.025,"ok");execution.seconds(r,"ack_receive",0.1,"error")
  execution.batch(r,32,19808,"count")
  local total=execution.aggregate();execution.include(total,r)
  assert(total.valid);eq(total.observed,1)
  execution.seconds(r,"attacker-value",0.1);execution.seconds(r,"ack_receive",-1)
  eq(r.invalid,2);eq(r.seconds["attacker-value:ok"],nil)
  local corrupt=copy(r);corrupt.seconds["ack_receive:ok"].buckets[1]=2
  total=execution.aggregate();execution.include(total,corrupt);eq(total.valid,false)
  total=execution.aggregate();corrupt=copy(r);corrupt.flush_reasons.injected=1
  execution.include(total,corrupt);eq(total.valid,false)
end)
case("EX02","default off performs no clock calls or diagnostic publications",function()
  local plugin,c=linger_begin(1,32,0)
  execution.clock=function() error("disabled diagnostics touched clock") end
  ngx.update_time=function() error("disabled diagnostics refreshed clock") end
  add_events(plugin,c,8);local task=start_timer();batch_point(task,8)
  resume_task(task,true);dead(task);empty_batch_queue(plugin,8)
  eq(next(diagnostic_rows()),nil)
  eq(plugin.schema.properties.execution_diagnostics.default,false)
end)
case("EX03","sender lag age and batch dimensions do not change ACK accounting",function()
  local plugin,c=enabled_begin(2,0);add_events(plugin,c,2);advance_ms(20)
  local task=fire_due();batch_point(task,2);acknowledge(task,true);dead(task)
  local r=diagnostic();assert(math.abs(r.seconds["sender_lag:ok"].sum-0.02)<1e-8)
  eq(r.seconds["dequeue_age:ok"].count,2);eq(r.batch_events.all.sum,2)
  eq(r.flush_reasons.immediate,1);empty_batch_queue(plugin,2)
  eq(scrape(plugin).shortlink_edge_exec_observation_complete,1)
end)
case("EX04","linger lateness and overdue head send once without another wait",function()
  local plugin,c=enabled_begin(32,5);add_events(plugin,c,1);advance_ms(30)
  local task=fire_due();batch_point(task,1);acknowledge(task,true);dead(task)
  local r=diagnostic();assert(r.seconds["linger_lag:ok"].sum>0.023)
  eq(r.flush_reasons.age,1);empty_batch_queue(plugin,1)
end)
case("EX05","premature and exiting callbacks preserve original pending events",function()
  for _,kind in ipairs({"premature","exiting"}) do
    local plugin,c=enabled_begin(32,0);add_events(plugin,c,1)
    if kind=="exiting" then state.exiting=true end
    run_timer(kind=="premature")
    local m=scrape(plugin);eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_events_failed,0)
    eq(diagnostic().seconds["sender_lag:"..kind].count,1);balanced(m)
  end
end)
case("EX06","one in sixty-four sampling and separate snapshot publication",function()
  local plugin,c=enabled_begin(32,0);add_events(plugin,c,128)
  eq(state.exec_writes,0,"no per-GET diagnostic snapshot")
  eq(state.exec_refresh_calls,0,"no per-GET clock refresh")
  for _,key in ipairs(ngx.shared.shortlink_edge_metrics:get_keys(0)) do
    if key:sub(1,7)=="worker:" then
      eq(require("apisix.core").json.decode(ngx.shared.shortlink_edge_metrics:get(key)).execution_diagnostics,nil)
    end
  end
  local task=fire_due()
  while coroutine.status(task.co)~="dead" do acknowledge(task,true) end
  local r=diagnostic();eq(r.seconds["event_encode:ok"].count,2)
  eq(r.seconds["ledger_publish:ok"].count,2);eq(r.batch_events.all.count,4)
  eq(state.exec_writes,5,"four settled batches plus drain, not 128 requests")
  empty_batch_queue(plugin,128)
end)
case("EX07","diagnostic write and clock failures cannot revoke delivered events",function()
  local plugin,c=enabled_begin(32,0);state.exec_write_failure=true;state.exec_clock_failure=true
  add_events(plugin,c,1);local task=fire_due();acknowledge(task,true);dead(task)
  empty_batch_queue(plugin,1);eq(scrape(plugin).shortlink_edge_exec_observation_complete,0)
  state.exec_write_failure=false;state.exec_clock_failure=false
  add_events(plugin,c,1,2);task=fire_due();acknowledge(task,true);dead(task)
  empty_batch_queue(plugin,2);assert(diagnostic().invalid>0)
  eq(scrape(plugin).shortlink_edge_exec_observation_complete,0)
end)
case("EX08","stale snapshots and missing worker coverage cannot appear complete",function()
  local plugin,c=enabled_begin(32,0);add_events(plugin,c,1)
  local task=fire_due();acknowledge(task,true);dead(task)
  eq(scrape(plugin).shortlink_edge_exec_observation_complete,1)
  advance_ms(15001);eq(scrape(plugin).shortlink_edge_exec_observation_complete,0)
  local other=worker(1,101);eq(scrape(other).shortlink_edge_exec_observation_complete,0)
  eq(scrape(other).shortlink_edge_observation_complete,1)
end)
case("EX09","partial ACK retries immutable remainder with diagnostics enabled",function()
  local plugin,c=enabled_begin(2,0);add_events(plugin,c,2);local task=fire_due()
  local first=batch_point(task,2);acknowledge(task,{ack={1}})
  local second=batch_point(task,1);eq(second.attempted[1].key,first.attempted[2].key)
  acknowledge(task,true);dead(task);empty_batch_queue(plugin,2)
  eq(diagnostic().batch_events.all.count,1)
end)
case("EX10","boundary returns frozen gateway ID even after early rejection",function()
  reset();local boundary=assert(load_lua(__BOUNDARY_SOURCE__))()
  local frozen=string.rep("a",32);ngx.var.request_id=frozen
  ngx.var.request_uri="/bad%2Fpath";ngx.var.uri="/bad/path"
  local ctx={};eq(boundary.rewrite({mode="redirect"},ctx),400)
  ngx.var.request_id=string.rep("b",32);ngx.var.upstream_http_x_shortlink_handler_nanos="987"
  ngx.header["X-Request-ID"]="upstream-untrusted";ngx.header["X-Shortlink-Handler-Nanos"]="987"
  boundary.header_filter({},ctx);eq(ngx.header["X-Request-ID"],frozen)
  eq(ngx.header["X-Shortlink-Handler-Nanos"],nil)
  eq(ngx.var.upstream_http_x_shortlink_handler_nanos,"987")
end)
case("EX11","failed refresh removes stale successful diagnostic coverage",function()
  local plugin,c=enabled_begin(32,0);add_events(plugin,c,1)
  local task=fire_due();acknowledge(task,true);dead(task)
  eq(scrape(plugin).shortlink_edge_exec_observation_complete,1)
  state.exec_write_failure=true;add_events(plugin,c,1,2)
  task=fire_due();acknowledge(task,true);dead(task)
  empty_batch_queue(plugin,2);eq(next(diagnostic_rows()),nil)
  eq(scrape(plugin).shortlink_edge_exec_observation_complete,0)
end)
case("EX12","finite full histogram fits the bounded per-generation snapshot",function()
  local r=execution.new()
  for _,name in ipairs({"sender_lag","linger_lag","dequeue_age","event_encode","ledger_publish",
      "adapter_total","prepare","encode","connection","socket_send","ack_receive","response_decode"}) do
    for _,outcome in ipairs({"ok","error","premature","exiting"}) do
      for _,value in ipairs({0,0.0001,0.001,0.005,0.01,0.025,0.05,0.1,0.25,1,10,60,100}) do
        execution.seconds(r,name,value,outcome)
      end
    end
  end
  for _,reason in ipairs({"immediate","count","bytes","age","configuration","timer_fallback"}) do
    execution.batch(r,128,1048576,reason)
  end
  package.cpath=package.cpath..";/usr/local/openresty/lualib/?.so"
  local wire=require("cjson").encode({record_version=3,worker_id=1,worker_pid=2147483647,
    generation=string.rep("x",64),boot_id=string.rep("b",64),observed_at=1700000000,diagnostics=r})
  assert(#wire<=32768,"bounded finite histograms exceed snapshot limit")
  print("DIAGNOSTIC_FULL_SNAPSHOT_BYTES "..#wire)
end)
case("EX13","sampled ledger safe-set failure is timed as error with original fault",function()
  local plugin,c=enabled_begin(32,0);add_events(plugin,c,63)
  local dict=ngx.shared.shortlink_edge_metrics;local write=dict.safe_set
  dict.safe_set=function(self,key,value)
    if key:sub(1,7)=="worker:" then return nil,"ledger capacity" end
    return write(self,key,value)
  end
  add_events(plugin,c,1,64);dict.safe_set=write
  local task=fire_due();while coroutine.status(task.co)~="dead" do acknowledge(task,true) end
  eq(diagnostic().seconds["ledger_publish:error"].count,1)
  eq(scrape(plugin).shortlink_edge_observation_complete,0)
end)
case("EX14","real cjson preserves late-first buckets without null or sparse holes",function()
  package.cpath=package.cpath..";/usr/local/openresty/lualib/?.so"
  local codec=require("cjson");local r=execution.new()
  execution.seconds(r,"ack_receive",30,"error");execution.batch(r,32,19808,"count")
  local recovered=codec.decode(codec.encode(r));local total=execution.aggregate()
  execution.include(total,recovered);eq(total.valid,true);eq(total.observed,1)
  eq(total.record.seconds["ack_receive:error"].buckets[1],0)
  eq(total.record.seconds["ack_receive:error"].buckets[12],1)
end)
case("EX15","copy all generations then refresh once without rereading snapshots",function()
  local plugin,c=enabled_begin(32,0)
  for i=0,3 do
    if i>0 then plugin=worker(i,100+i) end
    add_events(plugin,c,1,i+1);local task=fire_due();acknowledge(task,true);dead(task)
  end
  local dict=ngx.shared.shortlink_edge_metrics;local codec=require("apisix.core").json
  local rows=diagnostic_rows();local count=0;local replacement_key,replacement
  for key,row in pairs(rows) do
    count=count+1;row.observed_at=state.now+count/1000
    assert(dict:safe_set(key,codec.encode(row)))
    replacement_key=key;replacement=copy(row)
  end
  eq(count,4);local reads,refreshes=0,0
  state.get_hook=function(key) if key:sub(1,5)=="exec:" then reads=reads+1 end end
  ngx.update_time=function()
    refreshes=refreshes+1;eq(reads,4,"all shared records copied before refresh")
    state.now=state.now+0.01
    replacement.diagnostics.invalid=100
    assert(dict:safe_set(replacement_key,codec.encode(replacement)))
  end
  local m=scrape(plugin);state.get_hook=nil
  eq(refreshes,1);eq(reads,4,"no snapshot reread")
  eq(m.shortlink_edge_exec_observed_worker_generations,4)
  eq(m.shortlink_edge_exec_snapshot_fresh_worker_generations,4)
  eq(m.shortlink_edge_exec_observation_complete,1)
  eq(m.shortlink_edge_observation_complete,1);eq(m.shortlink_edge_events_delivered,4)
end)
case("EX16","genuine future timestamps remain incomplete after refreshing",function()
  local plugin,c=enabled_begin(32,0);add_events(plugin,c,1)
  local task=fire_due();acknowledge(task,true);dead(task)
  local key,row=next(diagnostic_rows());row.observed_at=state.now+0.1
  ngx.shared.shortlink_edge_metrics:safe_set(key,require("apisix.core").json.encode(row))
  ngx.update_time=function() state.now=state.now+0.01 end
  local m=scrape(plugin);eq(m.shortlink_edge_exec_observation_complete,0)
  eq(m.shortlink_edge_exec_observed_worker_generations,0)
  eq(m.shortlink_edge_observation_complete,1);eq(m.shortlink_edge_events_delivered,1)
end)
case("EX17","refresh unavailable throwing or invalid fails only optional coverage",function()
  for _,mode in ipairs({"missing","throw","nan","infinite"}) do
    local plugin,c=enabled_begin(32,0);add_events(plugin,c,1)
    local task=fire_due();acknowledge(task,true);dead(task)
    if mode=="missing" then ngx.update_time=nil
    elseif mode=="throw" then ngx.update_time=function() error("refresh unavailable") end
    else ngx.update_time=function() ngx.now=function() return mode=="nan" and 0/0 or math.huge end end end
    local m=scrape(plugin);eq(m.shortlink_edge_exec_observation_complete,0)
    eq(m.shortlink_edge_observation_complete,1);eq(m.shortlink_edge_observation_faults,0)
    eq(m.shortlink_edge_events_delivered,1);balanced(m)
  end
end)
case("EX18","freshness boundary remains fifteen seconds and stale counters remain visible",function()
  local plugin,c=enabled_begin(32,0);add_events(plugin,c,1)
  local task=fire_due();acknowledge(task,true);dead(task)
  advance_ms(15000);local fresh=scrape(plugin)
  eq(fresh.shortlink_edge_exec_observation_complete,1)
  eq(fresh.shortlink_edge_exec_snapshot_max_age_seconds,15)
  advance_ms(1);local stale=scrape(plugin)
  eq(stale.shortlink_edge_exec_observation_complete,0)
  eq(stale.shortlink_edge_exec_observed_worker_generations,1)
  eq(stale.shortlink_edge_observation_complete,1)
end)
case("EX19","refresh after original ledger calculation preserves original pending age",function()
  local plugin,c=enabled_begin(32,0);add_events(plugin,c,1)
  local task=fire_due();acknowledge(task,true);dead(task)
  add_events(plugin,c,1,2);advance_ms(2)
  local calls=0;ngx.update_time=function() calls=calls+1;state.now=state.now+2 end
  local m=scrape(plugin);eq(calls,1)
  assert(math.abs(m.shortlink_edge_oldest_pending_seconds-0.002)<1e-8)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_observation_complete,1);balanced(m)
end)
'''

KAFKA_CASES = r'''
local execution=require("apisix.plugins.shortlink-execution-diagnostics")
execution.clock=function(record) return S.now end
case("EK01","all transport phases close and item ACKs remain authoritative",function()
  local p=producer(reset());local r=execution.new();local items={item(0),item(1)}
  eq(p:send_batch(TOPIC,items,r),true);eq(r.invalid,0)
  for _,name in ipairs({"prepare","encode","connection","socket_send","ack_receive","response_decode","adapter_total"}) do
    ok(r.seconds[name..":ok"] and r.seconds[name..":ok"].count>0,name)
  end
  eq(p.exec_record,nil);eq(p.exec_phase,nil);ok(items[1].acked and items[2].acked)
end)
case("EK02","connection failure closes diagnostics without optimistic ACK",function()
  local p=producer(reset());local r=execution.new();S.connect_failure=true;local items={item(0)}
  eq(p:send_batch(TOPIC,items,r),false);all_unacked(items)
  eq(r.seconds["connection:error"].count,1);eq(r.seconds["adapter_total:error"].count,1)
  eq(p.exec_record,nil);eq(p.exec_phase,nil)
end)
case("EK03","malformed ACK is decode error and retains synchronous failure",function()
  local p=producer(reset());local r=execution.new();local items={item(0)}
  S.reply_hook=function(q) if q.api==0 then return {body=produce_reply(q,{bad_correlation=true})} end end
  eq(p:send_batch(TOPIC,items,r),false);all_unacked(items)
  eq(r.seconds["response_decode:error"].count,1);eq(r.seconds["adapter_total:error"].count,1)
end)
case("EK04","produce socket timeout is ACK receive error and never changes age budget",function()
  local p=producer(reset());local r=execution.new();local items={item(0)}
  S.reply_hook=function(q) if q.api==0 then S.now=S.now+1.5;return {timeout=true} end end
  local result=p:send_batch(TOPIC,items,r)
  eq(result,false);all_unacked(items)
  ok(r.seconds["ack_receive:error"]);eq(r.seconds["adapter_total:error"].count,1)
end)
'''


def programs():
    helper = (PLUGINS / "shortlink-execution-diagnostics.lua").read_text(encoding="utf-8")
    preload = 'package.preload["apisix.plugins.shortlink-execution-diagnostics"]=function() return assert((loadstring or load)(' + lua_literal(helper) + '))() end\n'
    log = logger_program((PLUGINS / "shortlink-request-logger.lua").read_text(encoding="utf-8"))
    extra = LOGGER_CASES.replace("__BOUNDARY_SOURCE__", lua_literal((PLUGINS / "shortlink-boundary.lua").read_text(encoding="utf-8")))
    log = preload + log.replace("local failures=0\n", extra + "\nlocal failures=0\n")
    kafka, _ = kafka_program()
    kafka = preload + kafka.replace("local failures=0\n", KAFKA_CASES + "\nlocal failures=0\n")
    return {"logger": (log, 99), "kafka": (kafka, 34)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("logger", "kafka", "all"), default="all")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--emit-lua", action="store_true")
    mode.add_argument("--lua-command")
    args = parser.parse_args()
    selected = programs()
    if args.suite != "all":
        selected = {args.suite: selected[args.suite]}
    if args.emit_lua:
        if len(selected) != 1:
            parser.error("--emit-lua requires one --suite")
        sys.stdout.write(next(iter(selected.values()))[0])
        return 0
    passed = True
    for name, (source, count) in selected.items():
        result = subprocess.run(shlex.split(args.lua_command), input=source.encode("utf-8"), capture_output=True, timeout=60)
        lines = decode_output(result.stdout).splitlines()
        ok = result.returncode == 0 and any(line.startswith("CASES %d FAILURES 0" % count) for line in lines)
        passed = passed and ok
        print(json.dumps({"suite": "edge_execution_" + name, "passed": ok, "cases": count,
                          "output": lines, "stderr": decode_output(result.stderr), "exit": result.returncode,
                          "inputLuaSha256": hashlib.sha256(source.encode("utf-8")).hexdigest()}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
