"""Exercise the real APISIX logger with a pure Lua, in-memory OpenResty harness.

No HTTP, Kafka connection, Docker operation, service start or load is performed.
Examples:
  python -B scripts/performance/test_edge_metrics.py --lua-command 'luajit -'
  python -B scripts/performance/test_edge_metrics.py --emit-lua | <existing-luajit> -
  python -B scripts/performance/test_edge_metrics.py --static-only
The last form validates wiring only and explicitly does not claim Lua execution.
"""
import argparse
import json
import pathlib
import shlex
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOGGER = ROOT / "deploy/apisix/plugins/apisix/plugins/shortlink-request-logger.lua"
HTTP_TIMING = ROOT / "deploy/apisix/plugins/apisix/plugins/shortlink-http-timing.lua"

HARNESS = r'''
local source = __LOGGER_SOURCE__
local load_lua = loadstring or load
local timing_source = __HTTP_TIMING_SOURCE__
package.preload["apisix.plugins.shortlink-http-timing"]=function() return assert(load_lua(timing_source))() end
package.preload["apisix.plugins.shortlink-worker-identity"]=function()
  return {pids=function()
    local values=ngx.worker.pids();if type(values)~="table" then return nil end
    local result={}
    for _,pid in ipairs(values) do
      if type(pid)~="number" or pid<=0 or pid>=math.huge or pid%1~=0 then return nil end
      result[pid]=true
    end
    if next(result)==nil then return nil end
    return result
  end}
end
local function copy(value)
  if type(value)~="table" then return value end
  local result={};for key,item in pairs(value) do result[key]=copy(item) end;return result
end
local state, encoded, encoding_id, uuid_id
local function dictionary()
  local values={}
  return {
    get=function(_,key)
      local value=values[key]
      if state.get_hook then state.get_hook(key,value) end
      return value
    end,
    delete=function(_,key) values[key]=nil end,
    safe_add=function(_,key,value)
      if state and state.fail_boot and key=="boot_id" then return nil,"no memory" end
      if values[key]~=nil then return nil,"exists" end
      values[key]=value;return true
    end,
    safe_set=function(_,key,value)
      if state.throw_ledger_once and key:sub(1,7)=="worker:" then
        state.throw_ledger_once=false;error("injected ledger write exception")
      end
      if state.fail_ledger and key:sub(1,7)=="worker:" then return nil,"no memory" end
      if key:sub(1,7)=="worker:" then state.ledger_writes=state.ledger_writes+1 end
      values[key]=value;return true
    end,
    incr=function(_,key,amount,initial)
      if values[key]==nil then
        if initial==nil then return nil,"not found" end
        values[key]=initial
      end
      values[key]=values[key]+amount;return values[key]
    end,
    get_keys=function()
      local keys={};for key in pairs(values) do keys[#keys+1]=key end;table.sort(keys)
      if state.keys_hook then state.keys_hook() end
      return keys
    end,
  }
end
package.preload["apisix.core"]=function()
  return {schema={check=function() return true end},
    id={gen_uuid_v4=function() uuid_id=uuid_id+1;return "uuid-"..uuid_id end},
    json={encode=function(value)
      if state.fail_event_json and value.decisionId then return nil end
      encoding_id=encoding_id+1
      local text="json-"..encoding_id..string.rep("x",128)
      encoded[text]=copy(value);return text
    end,decode=function(text) return copy(encoded[text]) end}}
end
package.preload["resty.kafka.producer"]=function()
  return {new=function(...)
    state.producer_calls=state.producer_calls+1
    local producer_id=state.producer_calls
    state.producer_arguments[producer_id]={...}
    if state.fail_producer or state.fail_producer_at==producer_id then error("producer unavailable") end
    if state.nil_producer_at==producer_id then return nil,"producer unavailable" end
    return {send=function(_,topic,key,body)
      assert(not state.busy_producers[producer_id],"concurrent send reused one mutable producer")
      state.busy_producers[producer_id]=true
      local send={topic=topic,key=key,body=body,producer_id=producer_id}
      state.sends[#state.sends+1]=send
      if state.send_hook then state.send_hook() end
      if state.pause_sends then
        local outcome,err=coroutine.yield({kind="send",index=#state.sends,send=send})
        state.busy_producers[producer_id]=nil
        if outcome=="throw" then error(err or "send exception") end
        return outcome,err
      end
      state.busy_producers[producer_id]=nil
      if state.send_failures>0 then state.send_failures=state.send_failures-1;return nil,"retryable" end
      return true
    end}
  end}
end
local function reset()
  state={now=1700000000,worker_id=0,pid=100,timers={},sends={},send_failures=0,output={},
    producer_calls=0,producer_arguments={},busy_producers={},timer_calls=0,exiting=false,ledger_writes=0,
    worker_count=1,live_pids={[0]=100}}
  encoded={};encoding_id=0;uuid_id=0
  ngx={shared={shortlink_edge_metrics=dictionary()},
    var={hostname="node-a",uri="/000000001",host="s.example",http_host="s.example",scheme="http",upstream_status="302"},
    status=302,header={},now=function() return state.now end,
    worker={id=function() return state.worker_id end,pid=function() return state.pid end,
      count=function() return state.worker_count end,
      pids=function()
        if state.fail_pids then return nil end
        local result={};for _,pid in pairs(state.live_pids) do result[#result+1]=pid end
        return result
      end,exiting=function() return state.exiting end},
    req={get_method=function() return "GET" end},
    timer={at=function(_,callback,...)
      state.timer_calls=state.timer_calls+1
      if state.fail_timer or state.fail_timer_at==state.timer_calls then return nil,"too many pending timers" end
      local args={...};args.n=select("#",...)
      state.timers[#state.timers+1]={callback=callback,args=args,id=state.worker_id,pid=state.pid};return true
    end},
    say=function(...)
      local parts={};for index=1,select("#",...) do parts[index]=tostring(select(index,...)) end
      state.output[#state.output+1]=table.concat(parts)
    end}
end
local function worker(id,pid)
  state.worker_id=id;state.pid=pid
  if id>=0 then
    state.worker_count=math.max(state.worker_count,id+1);state.live_pids[id]=pid
  end
  package.loaded["apisix.plugins.shortlink-http-timing"]=nil
  local plugin=assert(load_lua(source))()
  plugin.init()
  return plugin
end
local function conf()
  return {brokers={{host="test.invalid",port=9092}},instance_id="deployment",queue_count=8,queue_bytes=4096,max_event_bytes=4096}
end
local function scrape(plugin)
  state.output={};plugin.metrics()
  local result={}
  for _,line in ipairs(state.output) do
    local name,value=line:match("^(shortlink_edge_[a-z_]+) ([%d%.]+)$")
    if name then result[name]=tonumber(value) end
  end
  result.text=table.concat(state.output,"\n");return result
end
local function eq(actual,expected,description)
  assert(actual==expected,(description or "mismatch")..": "..tostring(actual).." ~= "..tostring(expected))
end
local function run_timer(premature)
  local timer=table.remove(state.timers,1);assert(timer,"missing timer")
  state.worker_id=timer.id;state.pid=timer.pid
  timer.callback(premature or false,unpack(timer.args,1,timer.args.n))
end
local function balanced(metrics)
  eq(metrics.shortlink_edge_events_attempted,
    metrics.shortlink_edge_events_delivered+metrics.shortlink_edge_events_failed+
    metrics.shortlink_edge_events_rejected+metrics.shortlink_edge_pending_count,"event conservation")
end
local cases={}
local function case(id,name,callback) cases[#cases+1]={id=id,name=name,callback=callback} end
case("EM01","zero traffic exposes complete boot identity",function()
  reset();local plugin=worker(0,100);local m=scrape(plugin)
  eq(m.shortlink_edge_events_attempted,0);eq(m.shortlink_edge_pending_count,0)
  eq(m.shortlink_edge_pending_bytes,0);eq(m.shortlink_edge_observation_complete,1)
  eq(m.shortlink_edge_started_at_seconds,state.now)
  assert(m.text:find('shortlink_edge_instance_info{instance="node%-a",boot_id="uuid%-1"} 1'))
end)
case("EM02","pending includes Kafka send in flight",function()
  reset();local plugin=worker(0,100);plugin.log(conf(),{})
  local before=scrape(plugin);eq(before.shortlink_edge_pending_count,1);assert(before.shortlink_edge_pending_bytes>0)
  state.send_hook=function()
    local m=scrape(plugin);eq(m.shortlink_edge_pending_count,1)
    eq(m.shortlink_edge_pending_bytes,before.shortlink_edge_pending_bytes)
  end
  run_timer();local m=scrape(plugin);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_pending_bytes,0);balanced(m)
end)
case("EM03","queue count remains occupied during send",function()
  reset();local plugin=worker(0,100);local c=conf();c.queue_count=1;plugin.log(c,{})
  state.send_hook=function() plugin.log(c,{}) end
  run_timer();local m=scrape(plugin);eq(m.shortlink_edge_events_attempted,2)
  eq(m.shortlink_edge_events_rejected,1);eq(m.shortlink_edge_events_delivered,1);balanced(m)
end)
case("EM04","oversize and byte budget reject without phantom pending",function()
  reset();local plugin=worker(0,100);local c=conf();c.max_event_bytes=1;plugin.log(c,{})
  c=conf();plugin.log(c,{});local size=scrape(plugin).shortlink_edge_pending_bytes;c.queue_bytes=size
  plugin.log(c,{});local m=scrape(plugin);eq(m.shortlink_edge_events_rejected,2)
  eq(m.shortlink_edge_pending_count,1);run_timer();balanced(scrape(plugin))
end)
case("EM05","timer rejection undoes admission exactly once",function()
  reset();local plugin=worker(0,100);state.fail_timer=true;plugin.log(conf(),{})
  local m=scrape(plugin);eq(m.shortlink_edge_events_attempted,1);eq(m.shortlink_edge_events_rejected,1)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_pending_bytes,0);balanced(m)
end)
case("EM06","retry exhaustion has one terminal failure and identical payload",function()
  reset();local plugin=worker(0,100);state.send_failures=3;plugin.log(conf(),{});run_timer()
  eq(#state.sends,3);for _,send in ipairs(state.sends) do
    eq(send.key,state.sends[1].key);eq(send.body,state.sends[1].body)
  end
  local m=scrape(plugin);eq(m.shortlink_edge_events_attempted,1);eq(m.shortlink_edge_events_failed,1);balanced(m)
end)
case("EM07","retry success does not count failed attempts as terminal events",function()
  reset();local plugin=worker(0,100);state.send_failures=1;plugin.log(conf(),{});run_timer()
  local m=scrape(plugin);eq(#state.sends,2);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_events_failed,0);balanced(m)
end)
case("EM08","worker restart cannot erase old uncompleted generation",function()
  reset();local old=worker(0,100);old.log(conf(),{});run_timer(true)
  local old_bytes=scrape(old).shortlink_edge_pending_bytes
  local fresh=worker(0,101);fresh.log(conf(),{});run_timer()
  local m=scrape(fresh);eq(m.shortlink_edge_events_attempted,2);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_pending_bytes,old_bytes)
  eq(m.shortlink_edge_retained_worker_generations,2);balanced(m)
  assert(m.text:find('generation="100:'));assert(m.text:find('generation="101:'))
end)
case("EM09","two active workers aggregate without replacing one another",function()
  reset();local first=worker(0,100);first.log(conf(),{})
  local second=worker(1,101);second.log(conf(),{});local m=scrape(second)
  eq(m.shortlink_edge_pending_count,2);eq(m.shortlink_edge_retained_worker_generations,2)
  run_timer();eq(scrape(first).shortlink_edge_pending_count,1);run_timer()
  m=scrape(second);eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_events_delivered,2);balanced(m)
end)
case("EM10","ledger exhaustion marks evidence incomplete without evicting residue",function()
  reset();local first=worker(0,100);first.log(conf(),{});run_timer(true)
  local previous=scrape(first).shortlink_edge_pending_bytes
  state.fail_ledger=true;local second=worker(0,101);second.log(conf(),{});run_timer()
  local m=scrape(second);eq(m.shortlink_edge_observation_complete,0)
  assert(m.shortlink_edge_observation_faults>0);eq(m.shortlink_edge_pending_bytes,previous)
  eq(m.shortlink_edge_raw_events_delivered,1)
  eq(m.shortlink_edge_events_delivered,0,"missing generation cannot manufacture coherent terminal evidence")
  state.fail_ledger=false;eq(scrape(second).shortlink_edge_observation_complete,0)
end)
case("EM11","missing boot fails observation explicitly",function()
  reset();state.fail_boot=true;local plugin=worker(0,100);plugin.log(conf(),{})
  local m=scrape(plugin);eq(m.shortlink_edge_observation_complete,0)
  eq(m.shortlink_edge_raw_events_rejected,1);eq(#state.timers,0)
end)
case("EM12","JSON rejection and producer creation failure are terminal once",function()
  reset();local plugin=worker(0,100);state.fail_event_json=true;plugin.log(conf(),{})
  state.fail_event_json=false;state.fail_producer=true;plugin.log(conf(),{});run_timer()
  local m=scrape(plugin);eq(m.shortlink_edge_events_rejected,1);eq(m.shortlink_edge_events_failed,1)
  eq(m.shortlink_edge_pending_count,0);balanced(m)
end)
case("EM13","scrape across admission cannot report a complete empty queue",function()
  reset();local plugin=worker(0,100);scrape(plugin)
  ngx.shared.shortlink_edge_metrics:incr("attempted",1)
  local m=scrape(plugin);eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_observation_complete,0)
end)
case("EM14","full shared dictionary restart changes boot identity",function()
  reset();local old=worker(0,100);old.log(conf(),{});local previous=scrape(old).text
  ngx.shared.shortlink_edge_metrics=dictionary();state.now=state.now+10
  local fresh=worker(0,200);local m=scrape(fresh)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_started_at_seconds,state.now)
  local old_boot=previous:match('boot_id="([^"]+)"');local new_boot=m.text:match('boot_id="([^"]+)"')
  assert(old_boot~=new_boot,"new observation interval must not reuse old boot")
end)
local failures=0
for _,item in ipairs(cases) do
  local ok,err=pcall(item.callback)
  if ok then print("PASS "..item.id.." "..item.name)
  else failures=failures+1;print("FAIL "..item.id.." "..item.name..": "..tostring(err)) end
end
print("CASES "..#cases.." FAILURES "..failures)
if failures>0 then os.exit(1) end
'''

# Use the real optional module in every suite importing this shared harness.
# Each simulated worker receives its own module state, as in OpenResty.
_timing_source = HTTP_TIMING.read_text(encoding="utf-8")
assert "]=====]" not in _timing_source
HARNESS = HARNESS.replace("__HTTP_TIMING_SOURCE__", "[=====[" + _timing_source + "]=====]")


def static_checks(source):
    config = (ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8")
    template = (ROOT / "deploy/apisix/config-etcd.template.yaml").read_text(encoding="utf-8")
    renderer = (ROOT / "deploy/apisix/render-etcd-config.py").read_text(encoding="utf-8")
    assert 'listen 127.0.0.1:9099;' in config
    assert 'require("apisix.plugins.shortlink-request-logger").metrics()' in config
    assert 'lua_shared_dict shortlink_edge_metrics 1m;' in config
    assert '"config.yaml"' in renderer and 'merge(base,' in renderer
    assert 'nginx_config:' not in template, "etcd overlay must retain the shared base endpoint"
    assert 'dictionary:add(' not in source and 'dictionary:set(' not in source
    assert 'safe_set(worker_key,record)' in source
    assert 'shortlink_edge_observation_complete' in source
    assert 'shortlink_edge_pending_count' in source and 'shortlink_edge_pending_bytes' in source
    assert source.index('metric("attempted")') < source.index('local request_id=')
    return {"suite": "edge_metrics_wiring", "passed": True, "case_count": 3,
            "cases": ["loopback shared exporter", "etcd inherits exporter", "non-evicting ledger and attempted entry"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--lua-command", help="Existing Lua/LuaJIT command reading a program from stdin; no shell")
    mode.add_argument("--emit-lua", action="store_true", help="Print self-contained Lua suite for an existing interpreter")
    mode.add_argument("--static-only", action="store_true", help="Check wiring only; does not execute Lua cases")
    args = parser.parse_args()
    source = LOGGER.read_text(encoding="utf-8")
    checks = static_checks(source)
    delimiter = "===="
    assert "]" + delimiter + "]" not in source
    program = HARNESS.replace("__LOGGER_SOURCE__", "[" + delimiter + "[" + source + "]" + delimiter + "]")
    if args.emit_lua:
        sys.stdout.write(program)
        return 0
    print(json.dumps(checks, ensure_ascii=False))
    if args.static_only:
        print(json.dumps({"lua_executed": False, "reason": "Explicit static-only mode"}))
        return 0
    command = shlex.split(args.lua_command or "luajit -")
    try:
        result = subprocess.run(command, input=program, text=True, capture_output=True,
                                encoding="utf-8", timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"suite": "edge_metrics_lua", "passed": False, "lua_executed": False,
                          "error": str(error)}, ensure_ascii=False))
        return 2
    lines = result.stdout.splitlines()
    passed = result.returncode == 0 and "CASES 14 FAILURES 0" in lines
    print(json.dumps({"suite": "edge_metrics_lua", "passed": passed, "lua_executed": True,
                      "case_count": 14, "output": lines, "stderr": result.stderr}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
