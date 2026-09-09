-- Own the FFI buffers for the whole call. resty.core.worker.pids borrows shared
-- scratch storage; identity checks must never accept its empty/stale PID view.
local ffi = require("ffi")
local _M = {}
local declared = pcall(ffi.cdef, [[
  int ngx_http_lua_ffi_worker_pids(int *pids, size_t *pids_len);
]])
local available, native = pcall(function() return ffi.C.ngx_http_lua_ffi_worker_pids end)
local function integer(value)
  return type(value)=="number" and value>=0 and value<math.huge and value%1==0
end
function _M.pids()
  if not declared or not available or not ngx or not ngx.config or ngx.config.subsystem~="http"
    or not ngx.worker or type(ngx.worker.count)~="function" or type(ngx.get_phase)~="function" then return nil end
  local phase_ok,phase=pcall(ngx.get_phase)
  if not phase_ok or phase=="init" or phase=="init_worker" then return nil end
  local workers_ok,workers=pcall(ngx.worker.count)
  if not workers_ok or not integer(workers) or workers<1 or workers>1024 then return nil end
  local capacity=math.min(workers*4,1024)
  local ok,result=pcall(function()
    -- Extra guard cell protects against a native implementation appending the
    -- current PID twice at the boundary. A full/truncated result fails closed.
    local buffer=ffi.new("int[?]",capacity+1)
    local length=ffi.new("size_t[1]",capacity)
    local rc=native(buffer,length)
    local used=tonumber(length[0])
    if rc~=0 or not integer(used) or used<1 or used>=capacity then return nil end
    local pids={}
    for index=0,used-1 do
      local pid=tonumber(buffer[index])
      if not integer(pid) or pid<1 or pid>2147483647 then return nil end
      pids[pid]=true
    end
    return pids
  end)
  if not ok then return nil end
  return result
end
return _M
