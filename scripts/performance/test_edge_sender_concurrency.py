"""Offline coroutine tests for the real APISIX edge event sender.

The existing fourteen metrics cases also execute, unchanged. A mocked Kafka
send yields until the test supplies an ACK, retryable error, or exception. No
HTTP, Kafka, Docker, WSL, or service operation is performed by this script.

Examples:
  python -B scripts/performance/test_edge_sender_concurrency.py --emit-lua
  python -B scripts/performance/test_edge_sender_concurrency.py --lua-command 'luajit -'
  python -B scripts/performance/test_edge_sender_concurrency.py --static-only

An existing LuaJIT interpreter is required to execute the Lua cases. Static-only
mode validates generation and wiring; it does not claim coroutine execution.
"""
import argparse
import json
import shlex
import subprocess
import sys

from test_edge_metrics import HARNESS, LOGGER, static_checks


EXTRA_CASES = r'''
-- A timer starts a coroutine and a send yields before any final confirmation.
-- A task retains its worker identity across an explicitly selected resume.
local function resume_task(task,...)
  state.worker_id=task.id;state.pid=task.pid
  local ok,point=coroutine.resume(task.co,...)
  assert(ok,"timer coroutine failed: "..tostring(point))
  task.point=point
  if coroutine.status(task.co)~="dead" then
    assert(type(point)=="table" and point.kind=="send","unexpected coroutine yield")
  end
  return task
end
local function start_timer(index,premature)
  local timer=table.remove(state.timers,index or 1);assert(timer,"missing reserved timer")
  local task={id=timer.id,pid=timer.pid}
  task.co=coroutine.create(function()
    timer.callback(premature or false,unpack(timer.args,1,timer.args.n))
  end)
  return resume_task(task)
end
local function suspended(task)
  eq(coroutine.status(task.co),"suspended","send must await an explicit ACK")
end
local function dead(task) eq(coroutine.status(task.co),"dead","sender must finish") end
local function healthy(plugin)
  local m=scrape(plugin);balanced(m)
  eq(m.shortlink_edge_queued_count+m.shortlink_edge_inflight_count,m.shortlink_edge_pending_count,
    "shared count includes queue plus in flight exactly once")
  eq(m.shortlink_edge_observation_complete,1,"complete observation")
  return m
end
local function reason(m,name)
  return tonumber(m.text:match('shortlink_edge_rejections_by_reason{reason="'..name..'"} ([%d%.]+)'))
end
local function begin(concurrency)
  reset();state.pause_sends=true
  local plugin=worker(0,100);local c=conf();c.send_concurrency=concurrency
  return plugin,c
end
local function shared_worker_record()
  local dictionary=ngx.shared.shortlink_edge_metrics
  local found
  for _,key in ipairs(dictionary:get_keys(0)) do
    if key:sub(1,7)=="worker:" then
      assert(not found,"test requires exactly one published worker generation")
      found=require("apisix.core").json.decode(dictionary:get(key))
    end
  end
  assert(type(found)=="table","worker record must be published")
  return found
end

case("SC01","concurrency declares the bounded default and sync producer ACK settings",function()
  local plugin,c=begin(nil)
  local field=plugin.schema.properties.send_concurrency
  eq(field.type,"integer");eq(field.minimum,1);eq(field.maximum,8);eq(field.default,2)
  plugin.log(c,{});plugin.log(c,{});eq(#state.timers,2,"default reserves two senders")
  local a=start_timer();local b=start_timer();suspended(a);suspended(b)
  for _,args in pairs(state.producer_arguments) do
    -- Observe the actual public constructor options, not an invented cache key argument.
    eq(args[3].producer_type,"sync");eq(args[3].required_acks,-1);eq(args[3].max_retry,3)
    eq(args[3].request_timeout,1000);eq(args[3].socket_timeout,1500)
    eq(args[3].batch_num,1);eq(args[3].max_buffering,1)
  end
  assert(state.sends[1].producer_id~=state.sends[2].producer_id,"each slot owns an independent producer")
  resume_task(a,true);resume_task(b,true);eq(healthy(plugin).shortlink_edge_events_delivered,2)
end)
case("SC02","two slots ACK out of order while a third event stays queued",function()
  local plugin,c=begin(2)
  for i=1,3 do plugin.log(c,{shortlink_request_id="request-"..i}) end
  local before=healthy(plugin);eq(before.shortlink_edge_active_senders,2)
  local a=start_timer();local b=start_timer();suspended(a);suspended(b)
  local m=healthy(plugin);eq(#state.sends,2);eq(m.shortlink_edge_queued_count,1)
  eq(m.shortlink_edge_inflight_count,2);eq(m.shortlink_edge_events_delivered,0)
  eq(m.shortlink_edge_pending_bytes,before.shortlink_edge_pending_bytes)
  state.now=state.now+2;m=healthy(plugin);eq(m.shortlink_edge_oldest_pending_seconds,2)
  resume_task(b,true);suspended(b);m=healthy(plugin)
  eq(#state.sends,3);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_pending_count,2);eq(m.shortlink_edge_queued_count,0)
  eq(state.sends[2].producer_id,state.sends[3].producer_id,"slot reuses its own confirmed producer")
  resume_task(a,true);dead(a);eq(healthy(plugin).shortlink_edge_events_delivered,2)
  state.now=state.now+1;resume_task(b,true);dead(b);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,3);eq(m.shortlink_edge_pending_bytes,0)
  eq(m.shortlink_edge_active_senders,0);eq(m.shortlink_edge_send_attempts,3)
  eq(m.shortlink_edge_send_seconds_sum,5);eq(m.shortlink_edge_send_seconds_max,2)
  eq(m.shortlink_edge_oldest_pending_seconds,0)
end)
case("SC03","each payload retries at most three times while the other slot progresses",function()
  local plugin,c=begin(2);plugin.log(c,{});plugin.log(c,{})
  local a=start_timer();local b=start_timer();local original=state.sends[1]
  resume_task(a,nil,"retryable");suspended(a)
  eq(healthy(plugin).shortlink_edge_events_failed,0)
  resume_task(b,true);dead(b);eq(healthy(plugin).shortlink_edge_events_delivered,1)
  resume_task(a,nil,"retryable");suspended(a)
  eq(healthy(plugin).shortlink_edge_events_failed,0)
  resume_task(a,nil,"retryable");dead(a)
  local attempts=0
  for _,send in ipairs(state.sends) do
    if send.key==original.key then
      attempts=attempts+1;eq(send.body,original.body);eq(send.producer_id,original.producer_id)
    end
  end
  eq(attempts,3);local m=healthy(plugin);eq(m.shortlink_edge_events_failed,1)
  eq(m.shortlink_edge_events_delivered,1);eq(m.shortlink_edge_pending_count,0)
  eq(m.shortlink_edge_retry_attempts,2);eq(m.shortlink_edge_send_attempts,4)
end)
case("SC04","send exception rebuilds only its slot before retrying immutable payload",function()
  local plugin,c=begin(2);plugin.log(c,{});plugin.log(c,{})
  local a=start_timer();local b=start_timer();local original=state.sends[1]
  resume_task(a,"throw","socket failure");suspended(a)
  eq(state.producer_calls,3);eq(state.sends[3].key,original.key);eq(state.sends[3].body,original.body)
  assert(state.sends[3].producer_id~=original.producer_id,"exception retires mutable producer")
  eq(healthy(plugin).shortlink_edge_events_failed,0)
  resume_task(b,true);resume_task(a,true);local m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,2);eq(m.shortlink_edge_events_failed,0)
  eq(m.shortlink_edge_send_attempts,3);eq(m.shortlink_edge_retry_attempts,1)
end)
case("SC05","queue count limit is shared by two in-flight events and one queued event",function()
  local plugin,c=begin(2);c.queue_count=3
  plugin.log(c,{});plugin.log(c,{});local a=start_timer();local b=start_timer()
  plugin.log(c,{});local occupied=healthy(plugin);plugin.log(c,{})
  local m=healthy(plugin);eq(m.shortlink_edge_pending_count,3)
  eq(m.shortlink_edge_pending_bytes,occupied.shortlink_edge_pending_bytes)
  eq(m.shortlink_edge_events_rejected,1);eq(reason(m,"queue_count"),1)
  resume_task(a,true);suspended(a);resume_task(b,true);resume_task(a,true)
  m=healthy(plugin);eq(m.shortlink_edge_events_delivered,3);eq(m.shortlink_edge_pending_bytes,0)
end)
case("SC06","queue byte limit includes both active slots without doubling the budget",function()
  local plugin,c=begin(2);plugin.log(c,{});plugin.log(c,{})
  local a=start_timer();local b=start_timer();local occupied=healthy(plugin)
  c.queue_bytes=occupied.shortlink_edge_pending_bytes
  plugin.log(c,{});local m=healthy(plugin)
  eq(m.shortlink_edge_pending_count,2);eq(m.shortlink_edge_pending_bytes,c.queue_bytes)
  eq(m.shortlink_edge_events_rejected,1);eq(reason(m,"queue_bytes"),1)
  resume_task(a,true);resume_task(b,true);eq(healthy(plugin).shortlink_edge_pending_bytes,0)
end)
case("SC07","second timer failure leaves new work for the existing in-flight sender",function()
  local plugin,c=begin(2);plugin.log(c,{});local a=start_timer()
  state.fail_timer_at=2;plugin.log(c,{})
  local m=healthy(plugin);eq(m.shortlink_edge_timer_failures,1)
  eq(m.shortlink_edge_events_rejected,0);eq(m.shortlink_edge_inflight_count,1)
  eq(m.shortlink_edge_queued_count,1);eq(m.shortlink_edge_active_senders,1)
  resume_task(a,true);suspended(a);eq(healthy(plugin).shortlink_edge_events_delivered,1)
  resume_task(a,true);dead(a);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,2);eq(m.shortlink_edge_pending_count,0)
  eq(reason(m,"timer_unavailable"),0)
end)
case("SC08","timer failure with no active sender rejects only the new admission",function()
  local plugin,c=begin(2);plugin.log(c,{});start_timer(1,true)
  local old=healthy(plugin);eq(old.shortlink_edge_pending_count,1)
  state.fail_timer=true;plugin.log(c,{})
  local m=healthy(plugin);eq(m.shortlink_edge_events_rejected,1)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_pending_bytes,old.shortlink_edge_pending_bytes)
  eq(m.shortlink_edge_active_senders,0);eq(m.shortlink_edge_timer_failures,1)
  eq(reason(m,"timer_unavailable"),1);eq(#state.sends,0)
end)
case("SC09","constructor exception terminates its event without rejecting another slot",function()
  local plugin,c=begin(2);plugin.log(c,{});local a=start_timer()
  plugin.log(c,{});state.fail_producer_at=2;local b=start_timer();dead(b)
  local m=healthy(plugin);eq(m.shortlink_edge_events_failed,1);eq(m.shortlink_edge_events_rejected,0)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_inflight_count,1)
  eq(m.shortlink_edge_producer_failures,1);eq(#state.sends,1)
  resume_task(a,true);m=healthy(plugin);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_send_attempts,1)
end)
case("SC10","nil constructor result has one terminal failure and no send attempts",function()
  local plugin,c=begin(2);state.nil_producer_at=1;plugin.log(c,{})
  dead(start_timer());local m=healthy(plugin)
  eq(m.shortlink_edge_events_failed,1);eq(m.shortlink_edge_producer_failures,1)
  eq(m.shortlink_edge_send_attempts,0);eq(m.shortlink_edge_pending_bytes,0)
end)
case("SC11","worker exit preserves queued residue and rejects new admission separately",function()
  local plugin,c=begin(2);for i=1,3 do plugin.log(c,{}) end
  local a=start_timer();local b=start_timer();state.exiting=true
  plugin.log(c,{});local m=healthy(plugin)
  eq(reason(m,"worker_exiting"),1);eq(m.shortlink_edge_pending_count,3)
  resume_task(a,true);resume_task(b,true);dead(a);dead(b);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,2);eq(m.shortlink_edge_pending_count,1)
  eq(m.shortlink_edge_queued_count,1);eq(m.shortlink_edge_inflight_count,0)
  eq(m.shortlink_edge_active_senders,0);local residue=m.shortlink_edge_pending_bytes
  state.exiting=false;local fresh=worker(0,101);fresh.log(c,{})
  local next_task=start_timer();resume_task(next_task,true);m=healthy(fresh)
  eq(m.shortlink_edge_events_delivered,3);eq(m.shortlink_edge_pending_count,1)
  eq(m.shortlink_edge_pending_bytes,residue);eq(m.shortlink_edge_retained_worker_generations,2)
end)
case("SC12","maximum eight senders reserve eight slots with a ninth event queued",function()
  local plugin,c=begin(8);c.queue_count=9
  for i=1,9 do plugin.log(c,{}) end
  eq(#state.timers,8);local tasks={}
  for i=1,8 do tasks[i]=start_timer();suspended(tasks[i]) end
  local m=healthy(plugin);eq(m.shortlink_edge_inflight_count,8);eq(m.shortlink_edge_queued_count,1)
  eq(m.shortlink_edge_active_senders,8);eq(state.producer_calls,8)
  resume_task(tasks[8],true);suspended(tasks[8]);eq(#state.sends,9)
  for i=1,7 do resume_task(tasks[i],true);dead(tasks[i]) end
  resume_task(tasks[8],true);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,9);eq(m.shortlink_edge_active_senders,0)
end)
case("SC13","explicit concurrency one remains serial while later events wait",function()
  local plugin,c=begin(1);for i=1,3 do plugin.log(c,{}) end
  eq(#state.timers,1);local a=start_timer();local m=healthy(plugin)
  eq(m.shortlink_edge_inflight_count,1);eq(m.shortlink_edge_queued_count,2)
  for i=1,2 do resume_task(a,true);suspended(a) end
  resume_task(a,true);dead(a);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,3);eq(state.producer_calls,1)
end)
case("SC14","lowering concurrency lets owned sends complete before retiring excess slots",function()
  local plugin,c=begin(2);plugin.log(c,{});plugin.log(c,{})
  local a=start_timer();local b=start_timer();local lower=conf();lower.send_concurrency=1
  plugin.log(lower,{});local m=healthy(plugin)
  eq(m.shortlink_edge_inflight_count,2);eq(m.shortlink_edge_events_failed,0)
  resume_task(b,true);dead(b);m=healthy(plugin)
  eq(m.shortlink_edge_active_senders,1);eq(m.shortlink_edge_queued_count,1)
  resume_task(a,true);suspended(a);eq(healthy(plugin).shortlink_edge_inflight_count,1)
  resume_task(a,true);dead(a);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,3);eq(m.shortlink_edge_events_failed,0)
end)
case("SC15","premature timers retain both old worker events after a generation change",function()
  local plugin,c=begin(2);plugin.log(c,{});plugin.log(c,{})
  dead(start_timer(1,true));dead(start_timer(1,true));local old=healthy(plugin)
  eq(old.shortlink_edge_pending_count,2);eq(old.shortlink_edge_active_senders,0)
  state.now=state.now+5;local fresh=worker(0,101);fresh.log(c,{})
  local a=start_timer();resume_task(a,true);local m=healthy(fresh)
  eq(m.shortlink_edge_events_attempted,3);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_pending_count,2);eq(m.shortlink_edge_pending_bytes,old.shortlink_edge_pending_bytes)
  eq(m.shortlink_edge_oldest_pending_seconds,5);eq(m.shortlink_edge_retained_worker_generations,2)
end)
case("SC16","the last high-numbered slot drains existing work after lowering eight to one",function()
  local plugin,c=begin(8);c.queue_count=10
  for i=1,9 do plugin.log(c,{}) end
  local tasks={};for i=1,8 do tasks[i]=start_timer() end
  local lower=conf();lower.send_concurrency=1;lower.queue_count=10
  plugin.log(lower,{})
  for i=1,7 do resume_task(tasks[i],true);dead(tasks[i]) end
  local m=healthy(plugin);eq(m.shortlink_edge_active_senders,1)
  eq(m.shortlink_edge_queued_count,2);eq(m.shortlink_edge_inflight_count,1)
  eq(#state.timers,0,"no new request or timer is needed for the last occupied slot")
  resume_task(tasks[8],true);suspended(tasks[8])
  resume_task(tasks[8],true);suspended(tasks[8])
  resume_task(tasks[8],true);dead(tasks[8]);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,10);eq(m.shortlink_edge_events_failed,0)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_active_senders,0)
end)
case("SC17","outer drain exception fails its owned item once and reschedules remaining work",function()
  local plugin,c=begin(1);plugin.log(c,{});plugin.log(c,{})
  state.throw_ledger_once=true;dead(start_timer())
  local m=healthy(plugin);eq(m.shortlink_edge_drain_errors,1)
  eq(m.shortlink_edge_events_failed,1);eq(m.shortlink_edge_events_delivered,0)
  eq(m.shortlink_edge_queued_count,1);eq(m.shortlink_edge_inflight_count,0)
  eq(m.shortlink_edge_active_senders,1);eq(#state.timers,1,"remaining queue gets a replacement timer")
  eq(#state.sends,0,"exception happened before calling the producer")
  local a=start_timer();suspended(a);resume_task(a,true);dead(a);m=healthy(plugin)
  eq(m.shortlink_edge_events_delivered,1);eq(m.shortlink_edge_events_failed,1)
  eq(m.shortlink_edge_pending_bytes,0);eq(m.shortlink_edge_active_senders,0)
end)
case("SC18","every subsequent send yield exposes prior ACK and final drain publishes empty state",function()
  local plugin,c=begin(1);for i=1,3 do plugin.log(c,{shortlink_request_id="chain-"..i}) end
  local task=start_timer();suspended(task)
  local first=shared_worker_record();eq(first.attempted,3);eq(first.delivered,0)
  eq(first.pending_count,3);eq(first.queued_count,2);eq(first.inflight_count,1)
  local pending_bytes=first.pending_bytes
  for completed=1,2 do
    local writes=state.ledger_writes
    local previous=task.point.send
    pending_bytes=pending_bytes-#previous.key-#previous.body
    resume_task(task,true);suspended(task)
    -- Assert the observable publication budget, independently of function layout.
    eq(state.ledger_writes-writes,1,"one coherent shared write between ACK and next send yield")
    local record=shared_worker_record()
    eq(record.attempted,3);eq(record.delivered,completed);eq(record.failed,0)
    eq(record.pending_count,3-completed);eq(record.pending_bytes,pending_bytes)
    eq(record.queued_count,2-completed);eq(record.inflight_count,1);eq(record.active_senders,1)
    eq(healthy(plugin).shortlink_edge_events_delivered,completed)
  end
  local writes=state.ledger_writes;resume_task(task,true);dead(task)
  eq(state.ledger_writes-writes,1,"final ACK publishes the empty queue before returning")
  local final=shared_worker_record();eq(final.delivered,3);eq(final.failed,0)
  eq(final.pending_count,0);eq(final.pending_bytes,0);eq(final.queued_count,0)
  eq(final.inflight_count,0);eq(final.active_senders,0)
  local m=healthy(plugin);eq(m.shortlink_edge_oldest_pending_seconds,0);eq(#state.timers,0)
end)
case("SC19","next claim publication exception cannot revoke a previous successful ACK",function()
  local plugin,c=begin(1);for i=1,3 do plugin.log(c,{shortlink_request_id="chain-"..i}) end
  local first=start_timer();suspended(first);state.throw_ledger_once=true
  resume_task(first,true);dead(first)
  local record=shared_worker_record()
  eq(record.delivered,1,"first event keeps its confirmed terminal state")
  eq(record.failed,1,"only the second owned item fails before its send")
  eq(record.rejected,0);eq(record.pending_count,1);eq(record.queued_count,1)
  eq(record.inflight_count,0);eq(record.active_senders,1)
  local m=healthy(plugin);eq(m.shortlink_edge_drain_errors,1);eq(#state.sends,1)
  eq(#state.timers,1,"third queued event gets a recovery timer without a new request")
  local third=start_timer();suspended(third);assert(third.point.send.key:find("chain-3",1,true))
  resume_task(third,true);dead(third);m=healthy(plugin)
  eq(#state.sends,2);eq(m.shortlink_edge_events_delivered,2);eq(m.shortlink_edge_events_failed,1)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_pending_bytes,0)
  eq(m.shortlink_edge_inflight_count,0);eq(m.shortlink_edge_active_senders,0);eq(#state.timers,0)
end)
case("SC20","four explicit slots share count and byte limits while a fifth event waits",function()
  for _,limit in ipairs({"count","bytes"}) do
    local plugin,c=begin(4);c.queue_count=limit=="count" and 5 or 1000;c.queue_bytes=8*1024*1024
    for i=1,5 do plugin.log(c,{}) end
    eq(#state.timers,4);local tasks,producers={},{}
    for i=1,4 do
      tasks[i]=start_timer();suspended(tasks[i])
      local producer_id=tasks[i].point.send.producer_id
      assert(not producers[producer_id],"four in-flight slots must own four producer instances")
      producers[producer_id]=true
    end
    local occupied=healthy(plugin);eq(occupied.shortlink_edge_pending_count,5)
    eq(occupied.shortlink_edge_inflight_count,4);eq(occupied.shortlink_edge_queued_count,1)
    eq(occupied.shortlink_edge_active_senders,4);eq(#state.sends,4)
    if limit=="bytes" then c.queue_bytes=occupied.shortlink_edge_pending_bytes end
    plugin.log(c,{});local m=healthy(plugin)
    eq(m.shortlink_edge_events_rejected,1);eq(reason(m,"queue_"..limit),1)
    eq(m.shortlink_edge_pending_count,5);eq(m.shortlink_edge_pending_bytes,occupied.shortlink_edge_pending_bytes)
    eq(#state.timers,0,"full shared budget cannot admit a fifth sender")
    resume_task(tasks[3],true);suspended(tasks[3]);eq(#state.sends,5)
    for _,index in ipairs({1,2,4}) do resume_task(tasks[index],true);dead(tasks[index]) end
    resume_task(tasks[3],true);dead(tasks[3]);m=healthy(plugin)
    eq(m.shortlink_edge_events_delivered,5);eq(m.shortlink_edge_events_failed,0)
    eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_pending_bytes,0)
    eq(m.shortlink_edge_active_senders,0);eq(#state.timers,0)
  end
end)
'''


def build_program(source):
    marker = "local failures=0\n"
    assert HARNESS.count(marker) == 1, "Existing metrics suite runner changed"
    assert HARNESS.count('case("EM') == 14, "Preserve all fourteen existing cases"
    assert EXTRA_CASES.count('case("SC') == 20
    delimiter = "===="
    assert "]" + delimiter + "]" not in source
    harness = HARNESS.replace(marker, EXTRA_CASES + "\n" + marker)
    return harness.replace("__LOGGER_SOURCE__", "[" + delimiter + "[" + source + "]" + delimiter + "]")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--lua-command", help="Existing interpreter command reading stdin; no shell")
    mode.add_argument("--emit-lua", action="store_true", help="Print self-contained real-source Lua suite")
    mode.add_argument("--static-only", action="store_true", help="Validate wiring and generation only")
    args = parser.parse_args()
    source = LOGGER.read_text(encoding="utf-8")
    checks = static_checks(source)
    program = build_program(source)
    if args.emit_lua:
        sys.stdout.write(program)
        return 0
    print(json.dumps(checks, ensure_ascii=False))
    if args.static_only:
        print(json.dumps({"suite": "edge_sender_concurrency", "lua_executed": False,
                          "generated_cases": 34, "existing_cases": 14, "new_cases": 20,
                          "reason": "Explicit static-only mode"}))
        return 0
    try:
        result = subprocess.run(shlex.split(args.lua_command or "luajit -"), input=program,
                                text=True, capture_output=True, encoding="utf-8", timeout=20,
                                check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"suite": "edge_sender_concurrency", "passed": False,
                          "lua_executed": False, "error": str(error)}, ensure_ascii=False))
        return 2
    lines = result.stdout.splitlines()
    passed = result.returncode == 0 and "CASES 34 FAILURES 0" in lines
    print(json.dumps({"suite": "edge_sender_concurrency", "passed": passed,
                      "lua_executed": True, "case_count": 34, "existing_cases": 14,
                      "new_cases": 20, "output": lines, "stderr": result.stderr}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
