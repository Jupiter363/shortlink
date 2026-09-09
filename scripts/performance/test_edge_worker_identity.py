"""Exercise real LuaJIT FFI allocations with a controlled native-call boundary.

No service, Docker, network, or Nginx process is started. The root additionally
validates the actual exported Nginx C API in the existing isolated runtime.
"""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

SOURCE = Path(__file__).resolve().parents[2] / 'deploy/apisix/plugins/apisix/plugins/shortlink-worker-identity.lua'
TEST = r'''
local realffi=require("ffi")
local source=__SOURCE__
local load_lua=loadstring or load
local state
local function make()
  state={workers=8,phase="content",calls=0,values={49,50,51,52,53,54,55,56,58},refs={}}
  ngx={config={subsystem="http"},worker={count=function()
    if state.count_throws then error("count unavailable") end;return state.workers
  end},get_phase=function() if state.phase_throws then error("phase unavailable") end;return state.phase end}
  local proxy={cdef=function(...) if state.declaration_fails then error("unavailable") end;return realffi.cdef(...) end,
    new=function(...) return realffi.new(...) end,C=setmetatable({}, {__index=function(_,name)
      assert(name=="ngx_http_lua_ffi_worker_pids")
      if state.symbol_missing then error("missing native symbol") end
      return function(buffer,length)
        state.calls=state.calls+1
        assert(type(buffer)=="cdata" and type(length)=="cdata","use actual FFI arrays")
        local capacity=tonumber(length[0]);state.capacity=capacity
        for _,pair in ipairs(state.refs) do
          assert(realffi.cast("void*",buffer)~=realffi.cast("void*",pair[1]),"PID storage must be exclusive per call")
          assert(realffi.cast("void*",length)~=realffi.cast("void*",pair[2]),"length storage must be exclusive per call")
        end
        state.refs[#state.refs+1]={buffer,length}
        if state.native_throws then error("native boundary unavailable") end
        for index,pid in ipairs(state.values) do buffer[index-1]=pid end
        length[0]=state.used or #state.values
        return state.rc or 0
      end
    end})}
  package.loaded.ffi=proxy
  local function instantiate() return assert(load_lua(source))() end
  return instantiate,proxy
end
local cases=0
local function case(name,fn)
  local ok,err=pcall(fn);if not ok then error(name..": "..tostring(err)) end
  cases=cases+1;print("PASS "..name)
end
case("real FFI arrays deliver all nine PID identities",function()
  local factory=make();local module=factory();local pids=assert(module.pids())
  for _,pid in ipairs(state.values) do assert(pids[pid]==true) end
  assert(state.calls==1 and state.capacity==32)
end)
case("sixteen calls retain neither borrowed storage nor old PID results",function()
  local factory=make();local module=factory()
  for n=1,16 do
    state.values={100+n};local pids=assert(module.pids())
    assert(pids[100+n] and (n==1 or not pids[99+n]))
  end
  assert(state.calls==16 and #state.refs==16)
end)
case("empty and failed native views fail closed",function()
  for _,setup in ipairs({function() state.used=0 end,function() state.rc=-1 end,
      function() state.native_throws=true end}) do
    local factory=make();local module=factory();setup();assert(module.pids()==nil)
  end
end)
case("full or oversized native result is not accepted as complete",function()
  for _,used in ipairs({32,33,1025}) do
    local factory=make();local module=factory();state.used=used;assert(module.pids()==nil)
  end
end)
case("zero and negative PID identities fail closed",function()
  for _,pid in ipairs({0,-1,-2147483648}) do
    local factory=make();local module=factory();state.values={49,pid};assert(module.pids()==nil)
  end
end)
case("duplicate native PIDs become a set",function()
  local factory=make();local module=factory();state.values={49,49,50}
  local pids=assert(module.pids());local n=0;for _ in pairs(pids) do n=n+1 end;assert(n==2)
end)
case("worker count is bounded and integral",function()
  for _,count in ipairs({0,-1,0.5,math.huge,0/0,1025,"8"}) do
    local factory=make();local module=factory();state.workers=count
    assert(module.pids()==nil and state.calls==0)
  end
end)
case("allocation is capped at Nginx process limit",function()
  for _,count in ipairs({256,512,1024}) do
    local factory=make();local module=factory();state.workers=count
    assert(module.pids() and state.capacity==1024)
  end
end)
case("one worker still uses bounded four-PID capacity",function()
  local factory=make();local module=factory();state.workers=1;state.values={49,58}
  assert(module.pids() and state.capacity==4)
end)
case("unavailable phase or count does not escape as an exception",function()
  for _,field in ipairs({"phase_throws","count_throws"}) do
    local factory=make();local module=factory();state[field]=true
    assert(module.pids()==nil and state.calls==0)
  end
end)
case("startup contexts and other subsystems do not call the native query",function()
  for _,phase in ipairs({"init","init_worker"}) do
    local factory=make();local module=factory();state.phase=phase
    assert(module.pids()==nil and state.calls==0)
  end
  local factory=make();local module=factory();ngx.config.subsystem="stream";assert(module.pids()==nil)
end)
case("missing native symbol fails closed",function()
  local factory=make();state.symbol_missing=true;local module=factory();assert(module.pids()==nil)
end)
case("allocation failure does not reuse an earlier result",function()
  local factory,proxy=make();local module=factory();assert(module.pids())
  proxy.new=function() error("out of memory") end;assert(module.pids()==nil and state.calls==1)
end)
package.loaded.ffi=realffi
print("CASES "..cases.." FAILURES 0")
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emit-lua', action='store_true')
    parser.add_argument('--lua-command', default='luajit -')
    args = parser.parse_args()
    program = TEST.replace('__SOURCE__', json.dumps(SOURCE.read_text(encoding='utf-8')))
    if args.emit_lua:
        print(program)
        return
    result = subprocess.run(shlex.split(args.lua_command), input=program, text=True,
                            encoding='utf-8', capture_output=True, timeout=30)
    print(result.stdout, end='')
    if result.returncode or 'CASES 13 FAILURES 0' not in result.stdout:
        raise RuntimeError(result.stderr[-2000:])


if __name__ == '__main__':
    main()
