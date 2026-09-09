"""Pure Lua interleaving tests for worker-coherent edge observation.

Reuses the 52 sender/metrics cases and real logger/HTTP timing modules. Reads
only local source. --static-only does not execute Lua; root supplies an existing
LuaJIT with --lua-command or consumes --emit-lua. No runtime is started here.
"""
import argparse
import json
import shlex
import subprocess
import sys

from test_edge_metrics import HARNESS, LOGGER, static_checks
from test_edge_sender_concurrency import EXTRA_CASES
from test_edge_sender_batch import BATCH_CASES


SNAPSHOT_CASES = r'''
local function ledger_key(id)
  return assert(ngx.shared.shortlink_edge_metrics:get("current:"..tostring(id or 0)))
end
local function edit_row(key,change)
  local d=ngx.shared.shortlink_edge_metrics
  local value=assert(require("apisix.core").json.decode(d:get(key)))
  change(value);assert(d:safe_set(key,require("apisix.core").json.encode(value)))
end
local function after_read(key,callback)
  state.get_hook=function(read_key)
    if read_key==key then state.get_hook=nil;callback() end
  end
end
local function incomplete(plugin)
  local m=scrape(plugin);eq(m.shortlink_edge_observation_complete,0);return m
end
case("SS01","ACK after a worker row read cannot corrupt the coherent active snapshot",function()
  local plugin,c=begin(1);plugin.log(c,{});local task=start_timer()
  after_read(ledger_key(),function() resume_task(task,true);dead(task) end)
  local m=healthy(plugin)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_events_delivered,0)
  eq(m.shortlink_edge_raw_events_delivered,1);eq(m.shortlink_edge_raw_global_reconciled,0)
  m=healthy(plugin);eq(m.shortlink_edge_events_delivered,1)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_raw_global_reconciled,1)
end)
case("SS02","admission after an active worker row read stays in the next snapshot",function()
  local plugin,c=begin(1);plugin.log(c,{});local task=start_timer()
  after_read(ledger_key(),function() plugin.log(c,{}) end)
  local m=healthy(plugin);eq(m.shortlink_edge_events_attempted,1)
  eq(m.shortlink_edge_raw_events_attempted,2);eq(m.shortlink_edge_pending_count,1)
  eq(healthy(plugin).shortlink_edge_pending_count,2)
  resume_task(task,true);resume_task(task,true);dead(task)
end)
case("SS03","admission crossing an empty row is never a successful drain",function()
  local plugin,c=begin(1)
  after_read(ledger_key(),function() plugin.log(c,{}) end)
  local m=incomplete(plugin);eq(m.shortlink_edge_pending_count,0)
  eq(m.shortlink_edge_raw_events_attempted,1);eq(m.shortlink_edge_raw_global_reconciled,0)
  eq(healthy(plugin).shortlink_edge_pending_count,1)
end)
case("SS04","missing final row cannot be hidden by a locally balanced zero row",function()
  reset();local plugin=worker(0,100);local d=ngx.shared.shortlink_edge_metrics
  local key=ledger_key();local initial=d:get(key)
  plugin.log(conf(),{});run_timer();d:safe_set(key,initial)
  local m=incomplete(plugin);eq(m.shortlink_edge_pending_count,0)
  eq(m.shortlink_edge_raw_events_attempted,1);eq(m.shortlink_edge_raw_events_delivered,1)
  eq(m.shortlink_edge_events_delivered,0)
end)
case("SS05","losing a current zero-traffic record is incomplete",function()
  reset();local plugin=worker(0,100)
  ngx.shared.shortlink_edge_metrics:delete(ledger_key());incomplete(plugin)
end)
case("SS06","losing an old terminal generation remains detectable after replacement",function()
  reset();local old=worker(0,100);old.log(conf(),{});run_timer();local old_key=ledger_key()
  local fresh=worker(0,101);eq(healthy(fresh).shortlink_edge_events_delivered,1)
  ngx.shared.shortlink_edge_metrics:delete(old_key)
  local m=incomplete(fresh);eq(m.shortlink_edge_registered_worker_generations,2)
end)
case("SS07","every configured worker must register even before any traffic",function()
  reset();state.worker_count=2;local first=worker(0,100);incomplete(first)
  local second=worker(1,101);local m=healthy(second)
  eq(m.shortlink_edge_registered_worker_generations,2)
  eq(m.shortlink_edge_retained_worker_generations,2)
  eq(m.shortlink_edge_snapshot_version,3)
end)
case("SS08","registration between key enumeration and final boundary invalidates scrape",function()
  reset();local first=worker(0,100)
  state.keys_hook=function() state.keys_hook=nil;worker(1,101) end
  incomplete(first);eq(healthy(first).shortlink_edge_registered_worker_generations,2)
end)
case("SS09","in-progress registration cannot produce false quiescence",function()
  reset();local plugin=worker(0,100);local d=ngx.shared.shortlink_edge_metrics
  d:incr("registration_pending",1);incomplete(plugin)
  d:incr("registration_pending",-1);healthy(plugin)
end)
case("SS10","completed interleaved registration revision is detected even with zero pending",function()
  reset();local plugin=worker(0,100)
  after_read(ledger_key(),function() ngx.shared.shortlink_edge_metrics:incr("registration_revision",2) end)
  incomplete(plugin);healthy(plugin)
end)
case("SS11","an orphan worker generation is not adopted silently",function()
  reset();local plugin=worker(0,100)
  ngx.shared.shortlink_edge_metrics:delete("registered:"..ledger_key());incomplete(plugin)
end)
case("SS12","unknown record versions and boot generations are rejected",function()
  for _,change in ipairs({function(r) r.record_version=2 end,
      function(r) r.boot_id="another-boot" end,function(r) r.generation="100:unknown" end}) do
    reset();local plugin=worker(0,100);edit_row(ledger_key(),change);incomplete(plugin)
  end
end)
case("SS13","a dead current PID cannot prove zero pending after worker replacement",function()
  reset();local plugin=worker(0,100);state.live_pids[0]=101;incomplete(plugin)
end)
case("SS14","unavailable process identities fail closed without changing counters",function()
  reset();local plugin=worker(0,100);state.fail_pids=true;incomplete(plugin)
  state.fail_pids=false;healthy(plugin)
end)
case("SS15","PID membership changes invalidate scrape but a stable helper is harmless",function()
  reset();local plugin=worker(0,100)
  after_read(ledger_key(),function() state.live_pids.helper=999 end)
  incomplete(plugin);healthy(plugin)
end)
case("SS16","worker-local conservation is mandatory independently of raw globals",function()
  reset();local plugin=worker(0,100)
  edit_row(ledger_key(),function(r) r.attempted=1 end);incomplete(plugin)
end)
case("SS17","negative fractional nonfinite string and unsafe integer ledger values fail closed",function()
  for _,bad in ipairs({-1,0.5,math.huge,0/0,"0",9007199254740992}) do
    reset();local plugin=worker(0,100)
    edit_row(ledger_key(),function(r) r.pending_bytes=bad end);incomplete(plugin)
  end
end)
case("SS18","byte count and oldest invariants remain strict",function()
  for _,change in ipairs({function(r) r.pending_bytes=1 end,
      function(r) r.queued_count=1 end,function(r) r.active_senders=9 end}) do
    reset();local plugin=worker(0,100);edit_row(ledger_key(),change);incomplete(plugin)
  end
  reset();local plugin=worker(0,100);plugin.log(conf(),{})
  edit_row(ledger_key(),function(r) r.oldest_created=nil end);incomplete(plugin)
end)
case("SS19","sticky observation faults remain a gate even after counters reconcile",function()
  reset();local plugin=worker(0,100)
  ngx.shared.shortlink_edge_metrics:incr("observation_faults",1)
  local m=incomplete(plugin);eq(m.shortlink_edge_raw_global_reconciled,1)
end)
case("SS20","expired retained pending generations never become terminal or disappear",function()
  reset();local old=worker(0,100);old.log(conf(),{});run_timer(true)
  state.now=state.now+31;local fresh=worker(0,101);local m=healthy(fresh)
  eq(m.shortlink_edge_pending_count,1);eq(m.shortlink_edge_oldest_pending_seconds,31)
  eq(m.shortlink_edge_events_delivered,0);eq(m.shortlink_edge_events_failed,0)
  eq(m.shortlink_edge_retained_worker_generations,2)
end)
case("SS21","diagnostic and rejection-reason corruption is never masked",function()
  for _,change in ipairs({function(r) r.diagnostics=nil end,
      function(r) r.rejection_reasons.encoding=1 end}) do
    reset();local plugin=worker(0,100);edit_row(ledger_key(),change);incomplete(plugin)
  end
end)
case("SS22","privileged agents do not register fictitious ordinary worker rows",function()
  reset();worker(0,100);local helper=worker(-1,999);state.live_pids.helper=999
  local m=healthy(helper);eq(m.shortlink_edge_registered_worker_generations,1)
end)
case("SS23","HTTP timing coverage is independent of event observation",function()
  reset();local plugin=worker(0,100);local c=conf();c.http_timing_enabled=true
  plugin.log(c,{matched_route={value={id="shortlink-redirect"}}});run_timer()
  edit_row(ledger_key(),function(r) r.http_timing.request_count=-1 end)
  local m=healthy(plugin);eq(m.shortlink_edge_events_delivered,1)
  assert(m.text:find("shortlink_edge_http_observation_complete 0",1,true))
end)
case("SS24","missing or corrupt raw counters fail closed even during active sends",function()
  for _,bad in ipairs({-1,0.5,math.huge,"0"}) do
    reset();local plugin=worker(0,100);plugin.log(conf(),{})
    ngx.shared.shortlink_edge_metrics:safe_set("attempted",bad);incomplete(plugin)
  end
  reset();local plugin=worker(0,100);plugin.log(conf(),{})
  ngx.shared.shortlink_edge_metrics:delete("delivered");incomplete(plugin)
end)
case("SS25","current worker pointers cannot reference another worker's generation",function()
  reset();worker(0,100);local second=worker(1,101)
  ngx.shared.shortlink_edge_metrics:safe_set("current:0",ledger_key(1));incomplete(second)
end)
case("SS26","registered identity corruption remains visible for terminal generations",function()
  reset();local plugin=worker(0,100)
  edit_row("registered:"..ledger_key(),function(r) r.worker_pid=101 end);incomplete(plugin)
end)
case("SS27","empty but unreleased sender state cannot claim a drain",function()
  reset();local plugin=worker(0,100)
  edit_row(ledger_key(),function(r) r.active_senders=1 end);incomplete(plugin)
end)
case("SS28","retained acknowledged generations aggregate without resetting totals",function()
  reset();local first=worker(0,100);first.log(conf(),{});run_timer()
  local second=worker(0,101);second.log(conf(),{});run_timer()
  local m=healthy(second);eq(m.shortlink_edge_events_attempted,2)
  eq(m.shortlink_edge_events_delivered,2);eq(m.shortlink_edge_raw_global_reconciled,1)
  eq(m.shortlink_edge_pending_count,0);eq(m.shortlink_edge_retained_worker_generations,2)
end)
case("SS29","invalid or changing start identity cannot identify a complete observation interval",function()
  for _,bad in ipairs({0,-1,math.huge,"0"}) do
    reset();local plugin=worker(0,100)
    ngx.shared.shortlink_edge_metrics:safe_set("started_at",bad);incomplete(plugin)
  end
  reset();local plugin=worker(0,100)
  after_read(ledger_key(),function() ngx.shared.shortlink_edge_metrics:incr("started_at",1) end)
  incomplete(plugin)
end)
local function timed_workers()
  reset();local first=worker(0,100);local c=conf();c.http_timing_enabled=true
  ngx.var.request_time="0.002";ngx.var.upstream_response_time="0.001"
  local ctx={matched_route={value={id="shortlink-redirect"}}}
  first.log(c,ctx);run_timer();local first_key=ledger_key()
  local second=worker(1,101);second.log(c,ctx);run_timer()
  local m=healthy(second);eq(m.shortlink_edge_http_observation_complete,1)
  eq(m.shortlink_edge_http_observed_worker_generations,2)
  return second,first_key
end
case("SS30","missing one of two timed worker records retains partial values but never complete timing",function()
  local plugin,key=timed_workers();ngx.shared.shortlink_edge_metrics:delete(key)
  local m=incomplete(plugin);eq(m.shortlink_edge_http_observation_complete,0)
  eq(m.shortlink_edge_http_observed_worker_generations,1)
  assert(m.text:find('shortlink_edge_http_samples_total{kind="eligible"} 1',1,true))
  assert(m.text:find('shortlink_edge_http_seconds_count{phase="request"} 1',1,true))
end)
case("SS31","timing coverage also requires valid registry current PID and interval identity",function()
  for _,change in ipairs({
      function(key) ngx.shared.shortlink_edge_metrics:delete("registered:"..key) end,
      function() ngx.shared.shortlink_edge_metrics:delete("current:0") end,
      function() state.live_pids[0]=999 end,
      function() state.fail_pids=true end,
      function() ngx.shared.shortlink_edge_metrics:safe_set("started_at",-1) end,
      function(key) edit_row(key,function(r) r.record_version=2 end) end,
      function() ngx.shared.shortlink_edge_metrics:incr("registration_pending",1) end,
      function(key) after_read(key,function() ngx.shared.shortlink_edge_metrics:incr("registration_revision",2) end) end,
      function(key) after_read(key,function() ngx.shared.shortlink_edge_metrics:incr("started_at",1) end) end}) do
    local plugin,key=timed_workers();change(key)
    local m=incomplete(plugin);eq(m.shortlink_edge_http_observation_complete,0)
    assert(m.shortlink_edge_http_observed_worker_generations>=1,"retain available timing diagnostics")
  end
end)
case("SS32","normal active raw-counter time skew does not invalidate complete timing coverage",function()
  local plugin,c=begin(1);c.http_timing_enabled=true
  ngx.var.request_time="0.002";ngx.var.upstream_response_time="0.001"
  plugin.log(c,{matched_route={value={id="shortlink-redirect"}}});local task=start_timer()
  after_read(ledger_key(),function() resume_task(task,true);dead(task) end)
  local m=healthy(plugin);eq(m.shortlink_edge_pending_count,1)
  eq(m.shortlink_edge_raw_global_reconciled,0);eq(m.shortlink_edge_http_observation_complete,1)
  eq(m.shortlink_edge_http_observed_worker_generations,1)
end)
'''


def build_program(source):
    marker = "local failures=0\n"
    assert HARNESS.count(marker) == 1
    assert HARNESS.count('case("EM') == 14
    assert EXTRA_CASES.count('case("SC') == 20
    assert BATCH_CASES.count('case("BC') == 18
    assert SNAPSHOT_CASES.count('case("SS') == 32
    assert "]====]" not in source
    harness = HARNESS.replace(marker, EXTRA_CASES + BATCH_CASES + SNAPSHOT_CASES + marker)
    return harness.replace("__LOGGER_SOURCE__", "[====[" + source + "]====]")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--emit-lua", action="store_true")
    mode.add_argument("--static-only", action="store_true")
    mode.add_argument("--lua-command", help="Existing LuaJIT reading stdin; never starts a service")
    args = parser.parse_args()
    source = LOGGER.read_text(encoding="utf-8")
    static_checks(source)
    program = build_program(source)
    if args.emit_lua:
        sys.stdout.write(program)
        return 0
    if args.static_only:
        print(json.dumps({"suite": "edge_snapshot_consistency", "lua_executed": False,
                          "existing_cases": 52, "new_cases": 32, "generated_cases": 84}))
        return 0
    try:
        result = subprocess.run(shlex.split(args.lua_command or "luajit -"), input=program,
                                text=True, capture_output=True, encoding="utf-8", timeout=45)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"lua_executed": False, "error": str(error)}))
        return 2
    lines = result.stdout.splitlines()
    passed = result.returncode == 0 and "CASES 84 FAILURES 0" in lines
    print(json.dumps({"suite": "edge_snapshot_consistency", "lua_executed": True,
                      "passed": passed, "cases": 84, "output": lines, "stderr": result.stderr}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
