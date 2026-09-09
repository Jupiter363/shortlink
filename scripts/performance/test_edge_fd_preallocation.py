"""Test FD startup preparation and real logger registration without services.

The mock and logger suites perform no native FD calls. The real suite uses an
isolated LuaJIT child process, only that child's own descriptor table, and no
network. --emit-lua prepares fixtures for an existing interpreter; --static-only
does not run Lua. This script never starts Docker, Nginx, HTTP, or Kafka.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import sys

MODULE_REL = Path('deploy/apisix/plugins/apisix/plugins/shortlink-fd-preallocate.lua')
LOGGER_REL = MODULE_REL.with_name('shortlink-request-logger.lua')
MODULE_MOCK = r'''-- Pure mocked FFI/ngx tests. No actual fcntl/getrlimit/close or runtime/network access.
-- Self-contained fixture emitted by test_edge_fd_preallocation.py.
local source = __FD_SOURCE__
local tests = 0
local original_ffi, original_process, original_ngx = package.loaded.ffi, package.loaded["ngx.process"], ngx
local function fresh(options)
    options = options or {}
    local state = {calls = {}, closes = {}, errno = 0, phase = "init_worker", kind = "worker",
        id = 0, pid = 41, workers = 1, exiting = false, soft = 4096, hard = 4096}
    for k, v in pairs(options) do state[k] = v end
    ngx = {get_phase = function() return state.phase end,
        worker = {id = function() return state.id end, pid = function() return state.pid end,
            count = function() return state.workers end, exiting = function() return state.exiting end}}
    package.loaded["ngx.process"] = {type = function() return state.kind end}
    local ffi = {os = state.platform or "Linux", arch = state.arch or "x64", C = {}}
    ffi.abi = function(name) assert(name == "64bit"); return true end
    ffi.cdef = function() error("mock declarations already present") end
    ffi.cast = function(_, value) return value end
    ffi.new = function(name, value)
        if name == "int" then return {cInt = value} end
        assert(name == "struct { unsigned long current; unsigned long maximum; }[1]")
        return {[0] = {}}
    end
    ffi.errno = function() return state.errno end
    ffi.C.getrlimit = function(resource, value)
        state.calls[#state.calls + 1] = "getrlimit"; assert(resource == 7)
        if state.limitError then state.errno = 22; return -1 end
        value[0].current, value[0].maximum = state.soft, state.hard; return 0
    end
    ffi.C.fcntl = function(fd, command, value)
        state.calls[#state.calls + 1] = "fcntl:" .. fd .. ":" .. command
        if command == 1030 then
            assert(fd == 2 and value.cInt == 1023, "typed vararg/source invariant")
            if state.dupError then state.errno = 24; return -1 end
            state.duplicated = true; return state.newFd or 1023
        end
        assert(command == 1 and value == nil)
        if fd == 2 then
            if state.sourceError then state.errno = 9; return -1 end
            return 0
        end
        if state.duplicated and fd == (state.newFd or 1023) then
            if state.flagError then state.errno = 9; return -1 end
            if state.noCloexec then return 0 end
            return 1
        end
        assert(fd == 1023)
        if state.occupied then return 1 end
        state.errno = state.targetError and 22 or 9; return -1
    end
    ffi.C.close = function(fd)
        assert(state.duplicated and fd == (state.newFd or 1023) and fd >= 1023 and fd ~= 2,
            "only created high fd may close")
        state.closes[#state.closes + 1] = fd
        if state.closeThrows then error("mock close error") end
        if state.closeError then state.errno = 4; return -1 end
        return 0
    end
    package.loaded.ffi = ffi
    return assert((loadstring or load)(source))(), state
end
local function test(name, fn)
    fn(); tests = tests + 1; print("PASS " .. name)
end
test("valid_exact_high_fd_closed_once_and_repeat_is_inert", function()
    local m, s = fresh(); local r = m.init(1023)
    assert(r.ok and r.status == "PREALLOCATED" and r.closeSucceeded and r.capacityAtLeast == 1024)
    assert(#s.closes == 1 and s.closes[1] == 1023)
    local calls = #s.calls; r.workerPid = 999
    assert(m.snapshot().workerPid == 41)
    assert(m.init().alreadyAttempted and #s.calls == calls and #s.closes == 1)
end)
test("existing_high_fd_is_never_closed_or_replaced", function()
    local m, s = fresh({occupied = true}); local r = m.init()
    assert(r.ok and r.status == "ALREADY_CAPACITY_PRESENT" and not s.duplicated and #s.closes == 0)
end)
test("racing_allocation_closes_only_actual_new_fd", function()
    local m, s = fresh({newFd = 1024}); local r = m.init()
    assert(r.ok and r.allocatedFd == 1024 and s.closes[1] == 1024 and #s.closes == 1)
end)
test("privileged_master_and_single_processes_never_call_ffi", function()
    for _, kind in ipairs({"privileged agent", "master", "single", "helper"}) do
        local m, s = fresh({kind = kind}); local r = m.init()
        assert(r.ok and r.skipped and not r.attempted and #s.calls == 0 and #s.closes == 0)
    end
end)
test("phase_identity_and_parameter_boundaries", function()
    for _, value in ipairs({0, 1022, 1024, -1, 1023.5, "1023", false, math.huge, 0/0}) do
        local m, s = fresh(); assert(not m.init(value).ok and #s.calls == 0)
    end
    for _, options in ipairs({{phase="content"}, {id=-1}, {id=1}, {workers=0}, {pid=0}, {exiting=true}}) do
        local m, s = fresh(options); assert(not m.init().ok and #s.calls == 0)
    end
end)
test("unsupported_platform_calls_no_system_functions", function()
    local m, s = fresh({platform="Windows"}); assert(m.init().status == "LINUX_X64_REQUIRED" and #s.calls == 0)
    m, s = fresh({arch="arm64"}); assert(m.init().status == "LINUX_X64_REQUIRED" and #s.calls == 0)
end)
test("nofile_limit_is_checked_without_modification", function()
    local m, s = fresh({soft=1023}); local r = m.init()
    assert(r.status == "NOFILE_LIMIT_TOO_SMALL_OR_INVALID" and not s.duplicated and #s.closes == 0)
    m, s = fresh({limitError=true}); assert(m.init().status == "GETRLIMIT_FAILED" and #s.closes == 0)
end)
test("invalid_stderr_and_probe_error_create_nothing", function()
    local m, s = fresh({sourceError=true}); assert(m.init().status == "SOURCE_FD_INVALID" and not s.duplicated and #s.closes == 0)
    m, s = fresh({targetError=true}); assert(m.init().status == "TARGET_FD_PROBE_FAILED" and not s.duplicated and #s.closes == 0)
end)
test("dup_failure_is_not_closed_and_not_retried", function()
    local m, s = fresh({dupError=true}); assert(m.init().status == "DUPLICATE_FAILED" and #s.closes == 0)
    local calls=#s.calls; assert(m.init().alreadyAttempted and #s.calls == calls)
end)
test("flag_failure_still_closes_owned_fd", function()
    for _, options in ipairs({{flagError=true}, {noCloexec=true}}) do
        local m, s = fresh(options); local r=m.init()
        assert(not r.ok and r.closeSucceeded and #s.closes == 1)
    end
end)
test("close_error_or_exception_fail_clearly_never_retry", function()
    for _, options in ipairs({{closeError=true}, {closeThrows=true}}) do
        local m, s=fresh(options); local r=m.init()
        assert(not r.ok and r.status == "CLOSE_FAILED" and r.closeNotRetried and #s.closes == 1)
        local calls=#s.calls; assert(m.init().alreadyAttempted and #s.calls==calls and #s.closes==1)
    end
end)
test("impossible_low_dup_result_never_closes_stderr", function()
    local m, s=fresh({newFd=2}); local r=m.init()
    assert(not r.ok and r.status=="DUPLICATE_RETURN_UNSAFE_TO_CLOSE" and #s.closes==0)
end)
test("changed_process_cannot_reinitialize_module", function()
    local m, s=fresh(); assert(m.init().ok); local calls=#s.calls; s.pid=42
    assert(m.init().status=="INITIALIZED_IN_ANOTHER_PROCESS" and #s.calls==calls)
end)
package.loaded.ffi, package.loaded["ngx.process"], ngx = original_ffi, original_process, original_ngx
print("FD_PREALLOCATE_MOCK_CHECK_PASSED cases=" .. tests .. " actualSyscalls=0 businessRequests=0")
'''

REAL = r'''
local ffi=require('ffi')
assert(ffi.os=='Linux' and ffi.arch=='x64' and ffi.abi('64bit'),'Linux x64 required')
ffi.cdef[[int getpid(void); int fcntl(int fd,int cmd,...); int close(int fd);
int getrlimit(int resource, void *limits);]]
local source=__FD_SOURCE__
local load_lua=loadstring or load
local function capacity()
  local f=assert(io.open('/proc/self/status','r'));local text=f:read('*a');f:close()
  return assert(tonumber(text:match('FDSize:%s*(%d+)')))
end
local function limits()
  local out=ffi.new('struct { unsigned long current; unsigned long maximum; }[1]')
  assert(ffi.C.getrlimit(7,out)==0);return tostring(out[0].current),tostring(out[0].maximum)
end
local pid=tonumber(ffi.C.getpid());assert(pid>1)
ngx={get_phase=function() return 'init_worker' end,worker={id=function() return 0 end,
  pid=function() return pid end,count=function() return 1 end,exiting=function() return false end}}
package.loaded['ngx.process']={type=function() return 'worker' end}
local before=capacity();local soft,hard=limits()
local source_flags=ffi.C.fcntl(2,1);assert(source_flags>=0,'isolated process requires valid stderr')
assert(ffi.C.fcntl(1023,1)==-1 and ffi.errno()==9,'isolated process requires initially free fd1023')
local module=assert(load_lua(source))();local first=module.init(1023)
assert(first.ok and first.createdFd and first.closeSucceeded and first.allocatedFd==1023)
local after=capacity();assert(after>=1024 and after>=before)
assert(ffi.C.fcntl(1023,1)==-1 and ffi.errno()==9)
assert(ffi.C.fcntl(2,1)==source_flags)
local second=module.init();assert(second.ok and second.alreadyAttempted)
assert(ffi.C.fcntl(1023,1)==-1 and ffi.errno()==9)
-- A separate module observes an existing descriptor without replacing/closing it.
local owned=tonumber(ffi.C.fcntl(2,1030,ffi.new('int',1023)));assert(owned==1023)
local owned_flags=ffi.C.fcntl(owned,1);assert(owned_flags>=0)
local occupied=assert(load_lua(source))().init()
assert(occupied.ok and occupied.status=='ALREADY_CAPACITY_PRESENT' and not occupied.createdFd)
assert(ffi.C.fcntl(owned,1)==owned_flags and ffi.C.fcntl(2,1)==source_flags)
assert(ffi.C.close(owned)==0) -- Test owns only its own explicit duplication.
local soft_after,hard_after=limits();assert(soft==soft_after and hard==hard_after)
print('FD_REAL_PASS cases=2 pid='..pid..' capacityBefore='..before..' capacityAfter='..after..
  ' sourceFdPreserved=true noLimitChange=true businessRequests=0')
'''

LOGGER_CASES = r'''
local fd_source=__FD_SOURCE__
local fd_name='apisix.plugins.shortlink-fd-preallocate'
local fd_state
local function setup_fd(options)
  options=options or {};reset()
  fd_state={native_calls=0,dup=0,closes=0,logs={},errno=0}
  ngx.get_phase=function() return 'init_worker' end
  package.loaded['ngx.process']={type=function()
    return state.worker_id<0 and 'privileged agent' or 'worker'
  end}
  local mock={os='Linux',arch=options.unsupported and 'arm64' or 'x64',C={}}
  mock.abi=function() return true end
  mock.cast=function(_,v) return v end
  mock.new=function(kind,v) if kind=='int' then return {cInt=v} end;return {[0]={}} end
  mock.errno=function() return fd_state.errno end
  mock.C.getrlimit=function(resource,value)
    eq(resource,7);fd_state.native_calls=fd_state.native_calls+1
    value[0].current=options.low_limit and 1023 or 4096;value[0].maximum=4096;return 0
  end
  mock.C.fcntl=function(fd,cmd,arg)
    fd_state.native_calls=fd_state.native_calls+1
    if cmd==1030 then
      eq(fd,2);eq(arg.cInt,1023);fd_state.dup=fd_state.dup+1;return 1023
    end
    eq(cmd,1)
    if fd==2 then
      if options.invalid_stderr then fd_state.errno=9;return -1 end
      return 0
    end
    eq(fd,1023)
    if fd_state.dup>0 then return 1 end
    fd_state.errno=9;return -1
  end
  mock.C.close=function(fd)
    eq(fd,1023);eq(fd_state.dup,1);fd_state.closes=fd_state.closes+1
    if options.close_fail then fd_state.errno=4;return -1 end
    return 0
  end
  package.loaded.ffi=mock
  package.loaded[fd_name]=nil
  package.preload[fd_name]=function()
    if options.require_fail then error('CREDENTIAL_MARKER module missing') end
    if options.init_throw then return {init=function() error({secret='CREDENTIAL_MARKER'}) end} end
    if options.bad_result then return {init=function() return options.bad_result end} end
    return assert(load_lua(fd_source))()
  end
  require('apisix.core').log={warn=function(...)
    assert(ngx.shared.shortlink_edge_metrics:get('current:0'),'registration must precede logging')
    local values={...};for i,v in ipairs(values) do values[i]=tostring(v) end
    fd_state.logs[#fd_state.logs+1]=table.concat(values)
    if options.log_throw then error('log unavailable') end
  end}
end
local function ledger_snapshot()
  local d=ngx.shared.shortlink_edge_metrics;local out={}
  for _,key in ipairs(d:get_keys()) do
    local value=d:get(key)
    out[key]=type(value)=='string' and require('apisix.core').json.decode(value) or value
    if out[key]==nil then out[key]=value end
  end
  return out
end
local function same(a,b)
  if type(a)~=type(b) then return false end
  if type(a)~='table' then return a==b end
  for k,v in pairs(a) do if not same(v,b[k]) then return false end end
  for k in pairs(b) do if a[k]==nil then return false end end
  return true
end
local function assert_registered(plugin)
  local d=ngx.shared.shortlink_edge_metrics
  eq(d:get('observation_faults'),0);eq(d:get('registration_pending'),0)
  eq(d:get('registration_revision'),2);eq(state.ledger_writes,1)
  local key=assert(d:get('current:0'));local row=require('apisix.core').json.decode(d:get(key))
  eq(row.record_version,3);eq(row.attempted,0);eq(row.pending_count,0)
  eq(scrape(plugin).shortlink_edge_observation_complete,1)
end
local function baseline_snapshot()
  setup_fd();local plugin=worker(0,100);assert_registered(plugin);return ledger_snapshot()
end
case('FD01','successful native preparation preserves the existing zero ledger',function()
  setup_fd();local plugin=worker(0,100);assert_registered(plugin)
  eq(fd_state.dup,1);eq(fd_state.closes,1);eq(#fd_state.logs,0)
  local before=ledger_snapshot();local calls=fd_state.native_calls
  plugin.init();plugin.init();eq(fd_state.native_calls,calls);eq(fd_state.closes,1)
  assert(same(before,ledger_snapshot()));eq(state.ledger_writes,1)
end)
case('FD02','unsupported ABI preserves the same v3 ledger and logs once',function()
  local baseline=baseline_snapshot();setup_fd({unsupported=true})
  local plugin=worker(0,100);assert_registered(plugin);eq(fd_state.native_calls,0)
  assert(same(baseline,ledger_snapshot()));eq(#fd_state.logs,1)
  assert(fd_state.logs[1]:find('LINUX_X64_REQUIRED',1,true))
  plugin.init();plugin.init();eq(#fd_state.logs,1);eq(state.ledger_writes,1)
end)
case('FD03','low nofile preserves registration without opening a descriptor',function()
  local baseline=baseline_snapshot();setup_fd({low_limit=true})
  local plugin=worker(0,100);assert_registered(plugin);assert(same(baseline,ledger_snapshot()))
  eq(fd_state.dup,0);eq(fd_state.closes,0);eq(#fd_state.logs,1)
  assert(fd_state.logs[1]:find('NOFILE_LIMIT_TOO_SMALL_OR_INVALID',1,true))
end)
case('FD04','missing optional module neither blocks registration nor leaks errors',function()
  local baseline=baseline_snapshot();setup_fd({require_fail=true})
  local plugin=worker(0,100);assert_registered(plugin);assert(same(baseline,ledger_snapshot()))
  eq(#fd_state.logs,1);assert(not fd_state.logs[1]:find('CREDENTIAL_MARKER',1,true))
  plugin.init();eq(#fd_state.logs,1);eq(state.ledger_writes,1)
end)
case('FD05','close failure is visible after registration and close is never retried',function()
  local baseline=baseline_snapshot();setup_fd({close_fail=true})
  local plugin=worker(0,100);assert_registered(plugin);assert(same(baseline,ledger_snapshot()))
  eq(fd_state.closes,1);eq(#fd_state.logs,1)
  assert(fd_state.logs[1]:find('CLOSE_FAILED',1,true));assert(fd_state.logs[1]:find('close_errno=4',1,true))
  plugin.init();eq(fd_state.closes,1);eq(#fd_state.logs,1)
end)
case('FD06','logging exception does not escape or alter already registered ledger',function()
  local baseline=baseline_snapshot();setup_fd({low_limit=true,log_throw=true})
  local plugin=worker(0,100);assert_registered(plugin);assert(same(baseline,ledger_snapshot()))
  plugin.init();eq(#fd_state.logs,1);eq(state.ledger_writes,1)
end)
case('FD07','privileged init skips FFI and does not create an ordinary generation',function()
  setup_fd();local plugin=worker(-1,999)
  eq(fd_state.native_calls,0);eq(fd_state.dup,0);eq(fd_state.closes,0);eq(#fd_state.logs,0)
  eq(state.ledger_writes,0);eq(#ngx.shared.shortlink_edge_metrics:get_keys(),0)
  plugin.init();eq(fd_state.native_calls,0)
end)
case('FD08','arbitrary init exception is normalized without escaping or serializing it',function()
  setup_fd({init_throw=true});local plugin=worker(0,100);assert_registered(plugin)
  eq(#fd_state.logs,1);assert(fd_state.logs[1]:find('INITIALIZATION_FAILED',1,true))
  assert(not fd_state.logs[1]:find('CREDENTIAL_MARKER',1,true))
end)
case('FD09','failure log is bounded for malformed status and numeric fields',function()
  setup_fd({bad_result={ok=false,status=string.rep('CREDENTIAL_MARKER',1000),
    errno={},closeErrno=math.huge,softLimit=-1,hardLimit=0/0}})
  local plugin=worker(0,100);assert_registered(plugin)
  eq(#fd_state.logs,1);assert(#fd_state.logs[1]<300)
  assert(fd_state.logs[1]:find('INVALID_RESULT',1,true))
  assert(not fd_state.logs[1]:find('CREDENTIAL_MARKER',1,true))
end)
case('FD10','invalid stderr creates nothing and registration is still complete',function()
  setup_fd({invalid_stderr=true});local plugin=worker(0,100);assert_registered(plugin)
  eq(fd_state.dup,0);eq(fd_state.closes,0);eq(#fd_state.logs,1)
  assert(fd_state.logs[1]:find('SOURCE_FD_INVALID',1,true))
end)
case('FD11','new worker retains previous terminal generation through preparation failure',function()
  setup_fd();local old=worker(0,100);old.log(conf(),{});run_timer()
  local d=ngx.shared.shortlink_edge_metrics;local old_key=d:get('current:0')
  local old_record=d:get(old_key);local prior_revision=d:get('registration_revision')
  package.loaded[fd_name]=nil
  package.preload[fd_name]=function() error('module unavailable in replacement worker') end
  local fresh=worker(0,101);local m=scrape(fresh)
  eq(d:get(old_key),old_record);eq(d:get('registration_revision'),prior_revision+2)
  eq(d:get('observation_faults'),0);eq(m.shortlink_edge_observation_complete,1)
  eq(m.shortlink_edge_registered_worker_generations,2);eq(m.shortlink_edge_retained_worker_generations,2)
  eq(m.shortlink_edge_events_attempted,1);eq(m.shortlink_edge_events_delivered,1)
  eq(#fd_state.logs,1);fresh.init();eq(#fd_state.logs,1)
end)
case('FD12','preparation success never hides a genuine ledger registration failure',function()
  setup_fd();state.fail_ledger=true;local plugin=worker(0,100)
  eq(fd_state.dup,1);eq(fd_state.closes,1);eq(#fd_state.logs,0)
  local m=scrape(plugin);eq(m.shortlink_edge_observation_complete,0)
  assert(m.shortlink_edge_observation_faults>0)
end)
'''


def lua_string(value):
    delimiter = '======='
    assert ']' + delimiter + ']' not in value
    return '[' + delimiter + '[' + value + ']' + delimiter + ']'


def programs(source_root, harness_root):
    module = (source_root / MODULE_REL).read_text(encoding='utf-8')
    logger = (source_root / LOGGER_REL).read_text(encoding='utf-8')
    harness_path = harness_root / 'scripts/performance/test_edge_metrics.py'
    spec = importlib.util.spec_from_file_location('fd_metrics_harness', harness_path)
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    anchor = 'local failures=0'
    assert harness.HARNESS.count(anchor) == 1
    logger_program = harness.HARNESS.replace('__LOGGER_SOURCE__', lua_string(logger))
    logger_program = logger_program.replace(anchor, LOGGER_CASES + '\n' + anchor, 1)
    return {name: text.replace('__FD_SOURCE__', lua_string(module)) for name, text in {
        'mock': MODULE_MOCK, 'real': REAL, 'logger': logger_program}.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--harness-root', type=Path)
    parser.add_argument('--suite', choices=('all', 'mock', 'real', 'logger'), default='all')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--emit-lua', action='store_true')
    mode.add_argument('--static-only', action='store_true')
    mode.add_argument('--lua-command', default=None)
    args = parser.parse_args()
    generated = programs(args.source_root, args.harness_root or args.source_root)
    if args.emit_lua:
        if args.suite == 'all': parser.error('--emit-lua requires one explicit --suite')
        sys.stdout.write(generated[args.suite]);return 0
    selected = list(generated) if args.suite == 'all' else [args.suite]
    if args.static_only:
        for name in selected:
            assert '__FD_SOURCE__' not in generated[name] and '__MOCK_BODY__' not in generated[name]
            assert len(generated[name]) > 1000
        print(json.dumps({'prepared': selected, 'luaExecuted': False, 'businessRequests': 0}))
        return 0
    expected = {'mock': 'FD_PREALLOCATE_MOCK_CHECK_PASSED cases=13',
                'real': 'FD_REAL_PASS cases=2', 'logger': 'CASES 26 FAILURES 0'}
    reports = []
    for name in selected:
        result = subprocess.run(shlex.split(args.lua_command or 'luajit -'),
                                input=generated[name], text=True, encoding='utf-8',
                                capture_output=True, timeout=30, check=False)
        passed = result.returncode == 0 and expected[name] in result.stdout
        report = {'suite': name, 'passed': passed, 'exitCode': result.returncode,
                  'stdout': result.stdout, 'stderr': result.stderr}
        reports.append(report);print(json.dumps(report, ensure_ascii=False))
    return 0 if all(row['passed'] for row in reports) else 1


if __name__ == '__main__':
    raise SystemExit(main())
