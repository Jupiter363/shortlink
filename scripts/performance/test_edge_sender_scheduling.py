"""Offline batch scheduling regression; no HTTP or Kafka operations.

Runs the existing 80 EM/SC/BC/LG cases plus ten scheduling cases with the real
logger and coroutine/clock mocks. --source can replay the sealed old logger;
--only-new isolates the regression oracle. Static mode is not Lua execution.
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
from test_edge_sender_linger import LOGGER, decode_output, program as linger_program


SCHEDULING_CASES = r'''
case("GS01","two through thirty-two queued events reserve one batch callback and one final snapshot",function()
  for size=2,32 do
    local plugin,c=batch_begin(4,32);add_events(plugin,c,size)
    eq(#state.timers,1,"one unentered batch callback, not one per event")
    eq(state.timer_calls,1)
    local writes=state.ledger_writes
    local task=start_timer();batch_point(task,size)
    local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,0)
    eq(m.shortlink_edge_pending_count,size);eq(m.shortlink_edge_inflight_count,size)
    eq(m.shortlink_edge_active_senders,1);eq(#state.timers,0)
    eq(state.ledger_writes,writes+1,"claim publishes its owned batch before send yields")
    resume_task(task,true);dead(task);empty_batch_queue(plugin,size)
    eq(state.ledger_writes,writes+2,"one final publication; no empty sibling callbacks")
    eq(state.timer_calls,1);eq(#state.batch_calls,1)
  end
end)
case("GS02","four full batches reach four independent senders before any ACK",function()
  local plugin,c=batch_begin(4,32);add_events(plugin,c,128)
  eq(#state.timers,1)
  local tasks,producers={},{}
  for i=1,4 do
    tasks[i]=start_timer();local call=batch_point(tasks[i],32)
    assert(not producers[call.producer_id],"each held batch owns an independent producer")
    producers[call.producer_id]=true
    local m=healthy(plugin)
    eq(m.shortlink_edge_pending_count,128);eq(m.shortlink_edge_events_delivered,0)
    eq(m.shortlink_edge_inflight_count,i*32);eq(m.shortlink_edge_queued_count,(4-i)*32)
    eq(m.shortlink_edge_active_senders,math.min(i+1,4))
    eq(#state.timers,i<4 and 1 or 0,"claim, then reserve at most one successor")
  end
  eq(state.timer_calls,4)
  for i=4,1,-1 do resume_task(tasks[i],true);dead(tasks[i]) end
  empty_batch_queue(plugin,128)
end)
case("GS03","byte-limited successor batches share the original node budget",function()
  local plugin,c=batch_begin(4,128,6000);c.send_batch_bytes=16384;c.queue_count=8
  add_events(plugin,c,8);eq(#state.timers,1)
  local original=healthy(plugin).shortlink_edge_pending_bytes
  local tasks={}
  for i=1,4 do
    tasks[i]=start_timer();local call=batch_point(tasks[i],2)
    local size=call.attempted[1].size+call.attempted[2].size
    assert(size<=c.send_batch_bytes and size+call.attempted[1].size>c.send_batch_bytes)
  end
  local m=healthy(plugin);eq(m.shortlink_edge_pending_bytes,original)
  eq(m.shortlink_edge_pending_count,8);eq(m.shortlink_edge_inflight_count,8)
  add_events(plugin,c,1,9);m=healthy(plugin);eq(reason(m,"queue_count"),1)
  eq(m.shortlink_edge_pending_bytes,original);eq(m.shortlink_edge_events_delivered,0)
  for i=4,1,-1 do resume_task(tasks[i],true);dead(tasks[i]) end
  m=empty_batch_queue(plugin,8);eq(m.shortlink_edge_events_rejected,1)
end)
case("GS04","distinct configuration prefixes refill concurrency without mixing batches",function()
  local plugin,c=batch_begin(4,32);local configs={c,copy(c),copy(c),copy(c)}
  for i=1,4 do add_events(plugin,configs[i],2,(i-1)*2+1) end
  eq(#state.timers,1)
  local tasks={}
  for i=1,4 do
    tasks[i]=start_timer();local call=batch_point(tasks[i],2)
    for _,item in ipairs(call.received_items) do eq(item.conf,configs[i]) end
  end
  eq(healthy(plugin).shortlink_edge_events_delivered,0)
  for i=4,1,-1 do resume_task(tasks[i],true);dead(tasks[i]) end
  empty_batch_queue(plugin,8)
end)
case("GS05","successor timer failure leaves its queued batch for the current owner",function()
  local plugin,c=batch_begin(2,2);add_events(plugin,c,4);state.fail_timer_at=2
  local task=start_timer();batch_point(task,2)
  local m=healthy(plugin);eq(m.shortlink_edge_timer_failures,1)
  eq(m.shortlink_edge_events_rejected,0);eq(m.shortlink_edge_events_failed,0)
  eq(m.shortlink_edge_pending_count,4);eq(m.shortlink_edge_inflight_count,2)
  eq(m.shortlink_edge_queued_count,2);eq(m.shortlink_edge_active_senders,1);eq(#state.timers,0)
  resume_task(task,true);batch_point(task,2)
  eq(healthy(plugin).shortlink_edge_events_delivered,2)
  resume_task(task,true);dead(task);empty_batch_queue(plugin,4)
end)
case("GS06","premature successor and worker exit retain unclaimed generation residue",function()
  local plugin,c=batch_begin(2,2);add_events(plugin,c,4)
  local first=start_timer();batch_point(first,2);eq(#state.timers,1)
  state.exiting=true;dead(start_timer(1,true));resume_task(first,true);dead(first)
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,2)
  eq(m.shortlink_edge_events_failed,0);eq(m.shortlink_edge_pending_count,2)
  eq(m.shortlink_edge_queued_count,2);eq(m.shortlink_edge_inflight_count,0)
  eq(m.shortlink_edge_active_senders,0);assert(m.shortlink_edge_pending_bytes>0)
  eq(#state.batch_calls,1);eq(#state.timers,0)
end)
case("GS07","lower concurrency retires a reserved successor before it can claim",function()
  local plugin,c=batch_begin(4,2);add_events(plugin,c,8)
  local first=start_timer();batch_point(first,2);eq(#state.timers,1)
  local lower=copy(c);lower.send_concurrency=1;add_events(plugin,lower,1,9)
  dead(start_timer());eq(#state.batch_calls,1)
  local m=healthy(plugin);eq(m.shortlink_edge_active_senders,1);eq(m.shortlink_edge_pending_count,9)
  for _,size in ipairs({2,2,2,1}) do resume_task(first,true);batch_point(first,size) end
  resume_task(first,true);dead(first);empty_batch_queue(plugin,9)
  eq(state.timer_calls,2,"the already owned high slot finishes without new reservations")
end)
case("GS08","successor scheduling respects a young head and the fixed linger deadline",function()
  local plugin,c=linger_begin(4,2,5);add_events(plugin,c,2)
  local first=fire_due();batch_point(first,2)
  advance_ms(1);add_events(plugin,c,1,3);local created=state.now
  acknowledge(first,true);dead(first);no_due()
  advance_ms(4);dead(fire_due());eq(#state.batch_calls,1)
  local timer=rounded_wakeup(created+0.005)
  advance_ms((timer.due-state.timer_clock)*1000)
  local next_task=fire_due();batch_point(next_task,1)
  assert(state.now>=created+0.005-1e-9,"replenishment cannot bypass a young head")
  acknowledge(next_task,true);dead(next_task);finish_idle_timers();empty_batch_queue(plugin,3)
end)
case("GS09","partially acknowledged batch retries alongside its reserved successor",function()
  local plugin,c=batch_begin(2,2);add_events(plugin,c,4)
  local first=start_timer();local original=batch_point(first,2)
  local second=start_timer();batch_point(second,2)
  resume_task(first,{ack={1},throw=true});local retry=batch_point(first,1)
  snapshot_equal(retry.attempted[1],original.attempted[2])
  eq(healthy(plugin).shortlink_edge_events_delivered,0,"partial ACK stays owned until terminal settlement")
  resume_task(second,true);dead(second);eq(healthy(plugin).shortlink_edge_events_delivered,2)
  resume_task(first,true);dead(first);empty_batch_queue(plugin,4)
  eq(state.batch_constructor_calls,3,"only the throwing slot rebuilds")
end)
case("GS10","non-yielding constructor failures leave at most one harmless reserved callback",function()
  local plugin,c=batch_begin(4,2);state.batch_constructor_failure="nil";add_events(plugin,c,8)
  dead(start_timer())
  -- A synchronous return may let this owner consume the queue before its already
  -- reserved successor enters. This is bounded, not a claim of zero empty timers.
  eq(#state.timers,1);eq(state.timer_calls,2)
  local record=shared_worker_record();eq(record.failed,8);eq(record.delivered,0)
  eq(record.pending_count,0);eq(record.pending_bytes,0);eq(record.active_senders,1)
  dead(start_timer());local m=empty_batch_queue(plugin,0,8)
  eq(m.shortlink_edge_events_rejected,0);eq(m.shortlink_edge_producer_failures,4)
  eq(#state.batch_calls,0);eq(state.timer_calls,2)
end)
'''


def program(source, only_new=False):
    base = linger_program(source)
    marker = "local failures=0\n"
    assert base.count(marker) == 1
    selected = ""
    if only_new:
        selected = ('local selected={}\nfor _,c in ipairs(cases) do '
                    'if c.id:sub(1,2)=="GS" then selected[#selected+1]=c end end\ncases=selected\n')
    return base.replace(marker, SCHEDULING_CASES + "\n" + selected + marker)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=LOGGER)
    parser.add_argument("--only-new", action="store_true")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--emit-lua", action="store_true")
    mode.add_argument("--static-only", action="store_true")
    mode.add_argument("--lua-command", help="Existing interpreter command, no shell or service startup")
    args = parser.parse_args()
    raw = args.source.read_bytes()
    source = raw.decode("utf-8-sig")
    count = SCHEDULING_CASES.count('case("GS')
    assert count == 10
    count = count if args.only_new else 80 + count
    rendered = program(source, args.only_new)
    info = {"suite": "edge_sender_scheduling", "case_count": count,
            "only_new": args.only_new, "source_sha256": hashlib.sha256(raw).hexdigest()}
    if args.emit_lua:
        sys.stdout.write(rendered)
        return 0
    if args.static_only:
        print(json.dumps(dict(info, generated=True, lua_executed=False)))
        return 0
    try:
        result = subprocess.run(shlex.split(args.lua_command), input=rendered.encode("utf-8"),
                                capture_output=True, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps(dict(info, passed=False, lua_executed=False, error_type=type(error).__name__)))
        return 2
    lines = decode_output(result.stdout).splitlines()
    passed = result.returncode == 0 and "CASES %d FAILURES 0" % count in lines
    print(json.dumps(dict(info, passed=passed, lua_executed=True, process_exit=result.returncode,
                         output=lines, stderr=decode_output(result.stderr))))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
