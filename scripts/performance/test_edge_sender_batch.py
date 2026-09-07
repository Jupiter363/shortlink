"""Offline batch-sender regression: reuse all existing 34 cases unchanged.

The real logger runs against a yielding in-memory batch adapter mock. This tests
ownership, immutable retries and accounting; it does not exercise Kafka's wire
protocol or claim broker delivery. No runtime/service/load is started here.
Use --emit-lua for an existing interpreter, --lua-command 'luajit -', or
--static-only (explicitly no Lua execution).
"""
import argparse
import json
import shlex
import subprocess
import sys

from test_edge_metrics import HARNESS, LOGGER, static_checks
from test_edge_sender_concurrency import EXTRA_CASES


BATCH_CASES = r'''
-- Extend only this suite. Existing EM/SC case bodies and their encoder behavior
-- remain unchanged unless a batch case explicitly requests large event bodies.
local original_encode=require("apisix.core").json.encode
require("apisix.core").json.encode=function(value)
  local text=original_encode(value)
  if text and state and state.batch_event_padding and value.decisionId then
    local padded=text..string.rep("p",state.batch_event_padding)
    encoded[padded]=copy(encoded[text]);return padded
  end
  return text
end
package.preload["apisix.plugins.shortlink-kafka-batch"]=function()
  return {new=function(_,brokers,options)
    state.batch_constructor_calls=state.batch_constructor_calls+1
    local producer_id=state.batch_constructor_calls
    state.batch_constructor_options[producer_id]=copy(options)
    if state.batch_constructor_failure=="throw" then error("batch constructor unavailable") end
    if state.batch_constructor_failure=="nil" then return nil,"batch constructor unavailable" end
    return {stats={produce_requests=0,produce_records=0,metadata_requests=0},
      send_batch=function(self,topic,items)
        assert(not state.batch_busy[producer_id],"two slots concurrently reused one batch producer")
        state.batch_busy[producer_id]=true
        local eligible,snapshots={},{}
        for _,item in ipairs(items) do
          if not item.acked and not item.terminal and state.now-item.created<30 then
            eligible[#eligible+1]=item
            snapshots[#snapshots+1]={key=item.key,body=item.body,size=item.size,created=item.created}
          end
        end
        assert(#eligible>0,"logger must not invoke adapter for an entirely ineligible batch")
        local call={topic=topic,producer_id=producer_id,received_items=items,
          eligible_refs=eligible,attempted=snapshots}
        state.batch_calls[#state.batch_calls+1]=call
        -- Synthetic adapter-side statistics only: no test treats these values as
        -- actual Kafka Produce/Metadata requests or encoded record byte sizes.
        self.stats.produce_requests=self.stats.produce_requests+1
        self.stats.produce_records=self.stats.produce_records+#eligible
        local action=coroutine.yield({kind="send",batch=call})
        state.batch_busy[producer_id]=nil
        action=action or {}
        if action==true then action={ack_all=true} end
        assert(type(action)=="table","invalid test ACK action")
        if action.ack_all then for _,item in ipairs(eligible) do item.acked=true end end
        for _,index in ipairs(action.ack or {}) do assert(eligible[index]).acked=true end
        for _,index in ipairs(action.terminal or {}) do assert(eligible[index]).terminal=true end
        if action.throw then error("adapter exception after optional partial ACK") end
        if action.throw_stats_after then
          local values,armed=self.stats,true
          self.stats=setmetatable({}, {__index=function(_,name)
            if armed then armed=false;error("one post-return adapter stats read failed") end
            return values[name]
          end})
        end
        return action.return_success or action.ack_all or false
      end}
  end}
end
local function batch_begin(concurrency,size,padding)
  local plugin,c=begin(concurrency or 1)
  state.batch_constructor_calls=0;state.batch_constructor_options={};state.batch_calls={};state.batch_busy={}
  state.batch_event_padding=padding
  c.send_batch_size=size or 32;c.send_batch_bytes=65536;c.queue_count=1000
  c.queue_bytes=8*1024*1024;c.max_event_bytes=16384
  return plugin,c
end
local function add_events(plugin,c,count,start)
  for i=start or 1,(start or 1)+count-1 do plugin.log(c,{shortlink_request_id="batch-"..i}) end
end
local function batch_point(task,count)
  suspended(task);local call=task.point.batch
  assert(call and call.topic=="shortlink.gateway.request.v1","expected a batch adapter call")
  if count then eq(#call.attempted,count,"eligible records in adapter attempt") end
  return call
end
local function snapshot_equal(actual,expected)
  for _,field in ipairs({"key","body","size","created"}) do eq(actual[field],expected[field],"immutable "..field) end
end
local function empty_batch_queue(plugin,delivered,failed)
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,delivered);eq(m.shortlink_edge_events_failed,failed or 0)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_pending_bytes,0)
  eq(m.shortlink_edge_queued_count,0);eq(m.shortlink_edge_inflight_count,0)
  eq(m.shortlink_edge_active_senders,0);eq(#state.timers,0)
  eq(m.shortlink_edge_batch_observation_complete,1)
  return m
end

case("BC01","batch schema is bounded and a lone event sends immediately without linger",function()
  local plugin,c=batch_begin(1,32)
  local size=plugin.schema.properties.send_batch_size;local bytes=plugin.schema.properties.send_batch_bytes
  eq(size.type,"integer");eq(size.minimum,1);eq(size.maximum,128);eq(size.default,1)
  eq(bytes.type,"integer");eq(bytes.minimum,16384);eq(bytes.maximum,1048576);eq(bytes.default,65536)
  c.ssl=true;add_events(plugin,c,1);eq(#state.timers,1)
  local task=start_timer();batch_point(task,1)
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,0);eq(m.shortlink_edge_pending_count,1)
  eq(m.shortlink_edge_inflight_count,1);eq(m.shortlink_edge_active_senders,1)
  local options=state.batch_constructor_options[1]
  eq(options.request_timeout,1000);eq(options.socket_timeout,1500);eq(options.ssl,true);eq(options.ssl_verify,true)
  resume_task(task,true);dead(task);empty_batch_queue(plugin,1)
end)
case("BC02","batch item limit splits backlog without delaying a short final batch",function()
  local plugin,c=batch_begin(1,3);add_events(plugin,c,7)
  local task=start_timer()
  for index,count in ipairs({3,3,1}) do
    local call=batch_point(task,count);eq(#call.received_items,count)
    local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,(index-1)*3)
    eq(m.shortlink_edge_inflight_count,count);resume_task(task,true)
  end
  dead(task);eq(#state.batch_calls,3);empty_batch_queue(plugin,7)
end)
case("BC03","batch byte limit uses complete item sizes including keys",function()
  local plugin,c=batch_begin(1,128,6000);c.send_batch_bytes=16384;add_events(plugin,c,5)
  local task=start_timer()
  for _,count in ipairs({2,2,1}) do
    local call=batch_point(task,count);local total=0
    for _,item in ipairs(call.attempted) do
      eq(item.size,#item.key+#item.body);total=total+item.size
    end
    assert(total<=c.send_batch_bytes,"serialized item bytes exceed batch limit")
    if count==2 then assert(total+call.attempted[1].size>c.send_batch_bytes,"third item must not fit") end
    resume_task(task,true)
  end
  dead(task);empty_batch_queue(plugin,5)
end)
case("BC04","equal-valued but distinct configuration objects cannot mix in one batch",function()
  local plugin,first=batch_begin(1,32);local second=copy(first)
  add_events(plugin,first,2,1);add_events(plugin,second,2,3);add_events(plugin,first,1,5)
  local task=start_timer()
  for index,expected in ipairs({first,second,first}) do
    local call=batch_point(task,index==3 and 1 or 2)
    for _,item in ipairs(call.received_items) do eq(item.conf,expected,"batch owns one exact conf object") end
    resume_task(task,true)
  end
  dead(task);eq(state.batch_constructor_calls,3);empty_batch_queue(plugin,5)
end)
case("BC05","partial ACK keeps immutable flags and retries only still eligible items",function()
  local plugin,c=batch_begin(1,32);add_events(plugin,c,3)
  local task=start_timer();local original=batch_point(task,3)
  resume_task(task,{ack={1}});local retry=batch_point(task,2)
  eq(retry.received_items,original.received_items,"immutable batch survives retries")
  eq(retry.received_items[1].acked,true)
  snapshot_equal(retry.attempted[1],original.attempted[2]);snapshot_equal(retry.attempted[2],original.attempted[3])
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,0)
  eq(m.shortlink_edge_pending_count,3);eq(m.shortlink_edge_inflight_count,3)
  resume_task(task,true);dead(task);empty_batch_queue(plugin,3)
end)
case("BC06","permanent item failure is excluded from later attempts",function()
  local plugin,c=batch_begin(1,32);add_events(plugin,c,2)
  local task=start_timer();local original=batch_point(task,2)
  resume_task(task,{terminal={1}});local retry=batch_point(task,1)
  eq(retry.received_items[1].terminal,true);snapshot_equal(retry.attempted[1],original.attempted[2])
  resume_task(task,true);dead(task);eq(#state.batch_calls,2);empty_batch_queue(plugin,1,1)
end)
case("BC07","exception after a partial ACK rebuilds producer without replaying confirmed items",function()
  local plugin,c=batch_begin(1,32);add_events(plugin,c,3)
  local task=start_timer();local original=batch_point(task,3)
  resume_task(task,{ack={1},throw=true});local retry=batch_point(task,2)
  assert(retry.producer_id~=original.producer_id,"throw must retire the producer")
  eq(retry.received_items,original.received_items);eq(retry.received_items[1].acked,true)
  snapshot_equal(retry.attempted[1],original.attempted[2]);snapshot_equal(retry.attempted[2],original.attempted[3])
  resume_task(task,true);dead(task);eq(state.batch_constructor_calls,2);empty_batch_queue(plugin,3)
end)
case("BC08","three attempts without item ACK exhaust once even if adapter returns truthy",function()
  local plugin,c=batch_begin(1,32);add_events(plugin,c,3)
  local task=start_timer();local original=batch_point(task,3)
  for attempt=1,3 do
    local call=batch_point(task,3)
    for index,item in ipairs(call.attempted) do snapshot_equal(item,original.attempted[index]) end
    eq(healthy(plugin).shortlink_edge_events_failed,0)
    resume_task(task,{return_success=true})
  end
  dead(task);eq(#state.batch_calls,3);local m=empty_batch_queue(plugin,0,3)
  eq(m.shortlink_edge_retry_attempts,2)
end)
case("BC09","expired entries never send and expiry after partial ACK preserves confirmed entries",function()
  local plugin,c=batch_begin(1,32);add_events(plugin,c,2,1)
  state.now=state.now+20;add_events(plugin,c,1,3);state.now=state.now+10
  local task=start_timer();local call=batch_point(task,1)
  assert(call.attempted[1].key:find("batch-3",1,true));eq(#call.received_items,3)
  resume_task(task,true);dead(task);empty_batch_queue(plugin,1,2)
  plugin,c=batch_begin(1,32);add_events(plugin,c,2)
  task=start_timer();batch_point(task,2);state.now=state.now+30
  resume_task(task,{ack={1}});dead(task);eq(#state.batch_calls,1);empty_batch_queue(plugin,1,1)
end)
case("BC10","worker exit stops batch retries and preserves unclaimed queue residue",function()
  local plugin,c=batch_begin(1,2);add_events(plugin,c,3)
  local task=start_timer();batch_point(task,2);state.exiting=true
  plugin.log(c,{});resume_task(task,{ack={1}});dead(task)
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,1);eq(m.shortlink_edge_events_failed,1)
  eq(reason(m,"worker_exiting"),1);eq(#state.batch_calls,1)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_queued_count,1)
  eq(m.shortlink_edge_inflight_count,0);eq(m.shortlink_edge_active_senders,0);eq(#state.timers,0)
end)
case("BC11","timer failure cannot reject events owned by an in-flight batch slot",function()
  local plugin,c=batch_begin(2,2);add_events(plugin,c,2)
  local task=start_timer();batch_point(task,2);dead(start_timer())
  state.fail_timer_at=3;add_events(plugin,c,1,3)
  local m=healthy(plugin);eq(m.shortlink_edge_timer_failures,1);eq(m.shortlink_edge_events_rejected,0)
  eq(m.shortlink_edge_pending_count,3);eq(m.shortlink_edge_inflight_count,2);eq(m.shortlink_edge_queued_count,1)
  resume_task(task,true);batch_point(task,1);resume_task(task,true);dead(task);empty_batch_queue(plugin,3)
end)
case("BC12","one batch of thirty-two in flight cannot generate an empty timer loop",function()
  local plugin,c=batch_begin(4,32);add_events(plugin,c,32)
  eq(#state.timers,4);local task=start_timer();batch_point(task,32)
  for i=1,3 do dead(start_timer()) end
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,0);eq(m.shortlink_edge_queued_count,0)
  eq(m.shortlink_edge_inflight_count,32);eq(m.shortlink_edge_pending_count,32)
  eq(m.shortlink_edge_active_senders,1);eq(#state.timers,0);eq(state.timer_calls,4)
  resume_task(task,true);dead(task);eq(state.timer_calls,4);empty_batch_queue(plugin,32)
end)
case("BC13","the next batch send publishes the complete previous batch terminal totals",function()
  local plugin,c=batch_begin(1,2);add_events(plugin,c,4)
  local task=start_timer();local first=batch_point(task,2);local before=shared_worker_record()
  local released=first.attempted[1].size+first.attempted[2].size
  resume_task(task,true);batch_point(task,2);local record=shared_worker_record()
  eq(record.delivered,2);eq(record.failed,0);eq(record.pending_count,2)
  eq(record.pending_bytes,before.pending_bytes-released);eq(record.inflight_count,2)
  eq(record.queued_count,0);eq(record.active_senders,1);healthy(plugin)
  resume_task(task,true);dead(task);empty_batch_queue(plugin,4)
end)
case("BC14","constructor nil and exception fail each owned item once without adapter sends",function()
  for _,failure in ipairs({"nil","throw"}) do
    local plugin,c=batch_begin(1,32);state.batch_constructor_failure=failure;add_events(plugin,c,3)
    dead(start_timer());eq(#state.batch_calls,0);eq(state.batch_constructor_calls,1)
    local m=empty_batch_queue(plugin,0,3);eq(m.shortlink_edge_producer_failures,1)
  end
end)
case("BC15","retained old worker without batch fields keeps original ledger but marks extension incomplete",function()
  local plugin,c=batch_begin(1,32);add_events(plugin,c,2);dead(start_timer(1,true))
  local dictionary=ngx.shared.shortlink_edge_metrics
  for _,key in ipairs(dictionary:get_keys(0)) do
    if key:sub(1,7)=="worker:" then
      local record=require("apisix.core").json.decode(dictionary:get(key))
      record.batch_diagnostics=nil;record.record_version=nil
      dictionary:safe_set(key,require("apisix.core").json.encode(record))
    end
  end
  local m=healthy(plugin);eq(m.shortlink_edge_pending_count,2)
  eq(m.shortlink_edge_batch_observation_complete,0)
  local fresh=worker(0,101);add_events(fresh,c,2,3)
  local task=start_timer();batch_point(task,2);resume_task(task,true);dead(task)
  m=healthy(fresh);eq(m.shortlink_edge_events_delivered,2);eq(m.shortlink_edge_pending_count,2)
  eq(m.shortlink_edge_retained_worker_generations,2);eq(m.shortlink_edge_batch_observation_complete,0)
end)
case("BC16","two concurrent batches and queued tail share count and bytes without multiplying budgets",function()
  for _,limit in ipairs({"count","bytes"}) do
    local plugin,c=batch_begin(2,2);if limit=="count" then c.queue_count=5 end
    add_events(plugin,c,5);local first=start_timer();local second=start_timer()
    local a=batch_point(first,2);local b=batch_point(second,2)
    assert(a.producer_id~=b.producer_id,"active batch slots need independent producers")
    local occupied=healthy(plugin);eq(occupied.shortlink_edge_pending_count,5)
    eq(occupied.shortlink_edge_inflight_count,4);eq(occupied.shortlink_edge_queued_count,1)
    eq(occupied.shortlink_edge_active_senders,2);eq(occupied.shortlink_edge_events_delivered,0)
    if limit=="bytes" then c.queue_bytes=occupied.shortlink_edge_pending_bytes end
    add_events(plugin,c,1,6);local m=healthy(plugin)
    eq(reason(m,"queue_"..limit),1);eq(m.shortlink_edge_events_rejected,1)
    eq(m.shortlink_edge_pending_count,5);eq(m.shortlink_edge_pending_bytes,occupied.shortlink_edge_pending_bytes)
    resume_task(first,true);batch_point(first,1);m=healthy(plugin)
    eq(m.shortlink_edge_events_delivered,2);eq(m.shortlink_edge_pending_count,3);eq(m.shortlink_edge_inflight_count,3)
    resume_task(second,true);dead(second);eq(healthy(plugin).shortlink_edge_events_delivered,4)
    resume_task(first,true);dead(first);empty_batch_queue(plugin,5)
  end
end)
case("BC17","post-return stats exception preserves partial ACK and resumes the queued next batch",function()
  local plugin,c=batch_begin(1,2);add_events(plugin,c,4)
  local first=start_timer();batch_point(first,2)
  resume_task(first,{ack={1},throw_stats_after=true});dead(first)
  local record=shared_worker_record()
  eq(record.delivered,1,"confirmed item survives an exception outside send's pcall")
  eq(record.failed,1,"only the unconfirmed item in the owned batch fails")
  eq(record.pending_count,2);eq(record.queued_count,2);eq(record.inflight_count,0)
  eq(record.active_senders,1);eq(#state.batch_calls,1,"outer drain error must not replay the first batch")
  local m=healthy(plugin);eq(m.shortlink_edge_drain_errors,1)
  eq(#state.timers,1,"next batch is rescheduled without external admission")
  local next_task=start_timer();local next_call=batch_point(next_task,2)
  assert(next_call.attempted[1].key:find("batch-3",1,true))
  assert(next_call.attempted[2].key:find("batch-4",1,true))
  resume_task(next_task,true);dead(next_task);eq(state.batch_constructor_calls,2)
  empty_batch_queue(plugin,3,1)
end)
case("BC18","eight multi-item slots shrink to one and the last high slot drains all remaining batches",function()
  local plugin,c=batch_begin(8,2);add_events(plugin,c,18)
  eq(#state.timers,8);local tasks={}
  for i=1,8 do tasks[i]=start_timer();batch_point(tasks[i],2) end
  local m=healthy(plugin);eq(m.shortlink_edge_inflight_count,16)
  eq(m.shortlink_edge_queued_count,2);eq(m.shortlink_edge_active_senders,8)
  local lower=copy(c);lower.send_concurrency=1;add_events(plugin,lower,2,19)
  for i=1,7 do resume_task(tasks[i],true);dead(tasks[i]) end
  m=healthy(plugin);eq(m.shortlink_edge_events_delivered,14)
  eq(m.shortlink_edge_inflight_count,2);eq(m.shortlink_edge_queued_count,4)
  eq(m.shortlink_edge_active_senders,1);eq(m.shortlink_edge_pending_count,6)
  eq(#state.timers,0);eq(state.timer_calls,8)
  for _,expected_first in ipairs({"batch-17","batch-19"}) do
    resume_task(tasks[8],true);local call=batch_point(tasks[8],2)
    assert(call.attempted[1].key:find(expected_first,1,true))
    m=healthy(plugin);eq(m.shortlink_edge_active_senders,1)
    eq(m.shortlink_edge_inflight_count,2);eq(state.timer_calls,8)
  end
  resume_task(tasks[8],true);dead(tasks[8]);empty_batch_queue(plugin,20)
  eq(state.timer_calls,8,"no empty timer or new admission is needed after shrink")
  eq(#state.batch_calls,10)
end)
'''


def build_program(source):
    marker = "local failures=0\n"
    assert HARNESS.count(marker) == 1
    assert HARNESS.count('case("EM') == 14
    assert EXTRA_CASES.count('case("SC') == 20, "Preserve all existing concurrency cases"
    assert BATCH_CASES.count('case("BC') == 18
    delimiter = "===="
    assert "]" + delimiter + "]" not in source
    harness = HARNESS.replace(marker, EXTRA_CASES + "\n" + BATCH_CASES + "\n" + marker)
    return harness.replace("__LOGGER_SOURCE__", "[" + delimiter + "[" + source + "]" + delimiter + "]")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--emit-lua", action="store_true")
    mode.add_argument("--static-only", action="store_true")
    mode.add_argument("--lua-command", help="Existing interpreter command reading stdin; no shell")
    args = parser.parse_args()
    source = LOGGER.read_text(encoding="utf-8")
    checks = static_checks(source)
    program = build_program(source)
    if args.emit_lua:
        sys.stdout.write(program)
        return 0
    print(json.dumps(checks, ensure_ascii=False))
    if args.static_only:
        print(json.dumps({"suite": "edge_sender_batch", "lua_executed": False,
                          "generated_cases": 52, "existing_cases": 34, "new_cases": 18,
                          "reason": "Explicit static-only mode"}))
        return 0
    try:
        result = subprocess.run(shlex.split(args.lua_command or "luajit -"), input=program,
                                text=True, capture_output=True, encoding="utf-8", timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"suite": "edge_sender_batch", "passed": False, "lua_executed": False,
                          "error": str(error)}, ensure_ascii=False))
        return 2
    lines = result.stdout.splitlines()
    passed = result.returncode == 0 and "CASES 52 FAILURES 0" in lines
    print(json.dumps({"suite": "edge_sender_batch", "passed": passed, "lua_executed": True,
                      "case_count": 52, "existing_cases": 34, "new_cases": 18,
                      "output": lines, "stderr": result.stderr}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
