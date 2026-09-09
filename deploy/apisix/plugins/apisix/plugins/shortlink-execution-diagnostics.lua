-- Optional bounded worker-local diagnostics. Durations are wall seconds,
-- including off-CPU time; never interpreted as CPU or broker-only time. Phase
-- clocks are monotonic; dequeue_age uses the existing cached ngx.now queue clock.
-- No timers, logs, shared dictionaries, request identifiers or network calls.
local M = {}
local PHASES = {sender_lag=true,linger_lag=true,dequeue_age=true,event_encode=true,ledger_publish=true,
  adapter_total=true,prepare=true,encode=true,connection=true,socket_send=true,
  ack_receive=true,response_decode=true}
local OUTCOMES = {ok=true,error=true,premature=true,exiting=true}
local REASONS = {immediate=true,count=true,bytes=true,age=true,configuration=true,
  timer_fallback=true}
local LIMITS = {
  seconds={0,0.0001,0.001,0.005,0.01,0.025,0.05,0.1,0.25,1,10,60},
  batch_events={1,4,8,16,32,64,128},
  batch_bytes={512,1024,4096,16384,32768,65536,131072,1048576}}
local MAX_INTEGER=9007199254740991
local clock_ready, native_clock, clock_buffer
local function finite(v) return type(v)=="number" and v>=0 and v<math.huge end
local function integer(v) return finite(v) and v%1==0 and v<=MAX_INTEGER end
local function fault(record) record.invalid=record.invalid+1 end
local function init_clock()
  if clock_ready~=nil then return clock_ready end
  clock_ready=false
  local ok=pcall(function()
    local ffi=require("ffi")
    if ffi.os~="Linux" or ffi.arch~="x64" then return end
    if not pcall(function() return ffi.C.clock_gettime end) then
      ffi.cdef("int clock_gettime(int clock_id, void *tp);")
    end
    native_clock=ffi.cast("int (*)(int, void *)",ffi.C.clock_gettime)
    clock_buffer=ffi.new("struct { long tv_sec; long tv_nsec; }[1]")
    clock_ready=true
  end)
  if not ok then clock_ready=false end
  return clock_ready
end
function M.new()
  return {version=1,clock="monotonic",invalid=0,seconds={},batch_events={},
    batch_bytes={},flush_reasons={}}
end
function M.clock(record)
  if not init_clock() then fault(record);return nil end
  local ok,value=pcall(function()
    if native_clock(1,clock_buffer)~=0 then return nil end -- CLOCK_MONOTONIC
    return tonumber(clock_buffer[0].tv_sec)+tonumber(clock_buffer[0].tv_nsec)/1e9
  end)
  if not ok or not finite(value) then fault(record);return nil end
  return value
end
local function histogram(kind)
  -- Dense arrays survive cjson round-trips without null holes or sparse-array
  -- encoding failures when the first observation falls in a late bucket.
  local buckets={}
  for i=1,#LIMITS[kind] do buckets[i]=0 end
  return {count=0,sum=0,max=0,buckets=buckets}
end
local function add(record,kind,key,value)
  if not finite(value) then fault(record);return end
  local h=record[kind][key]
  if not h then h=histogram(kind);record[kind][key]=h end
  h.count=h.count+1;h.sum=h.sum+value;h.max=math.max(h.max,value)
  for i,limit in ipairs(LIMITS[kind]) do
    if value<=limit then h.buckets[i]=(h.buckets[i] or 0)+1 end
  end
end
function M.seconds(record,phase,value,outcome)
  outcome=outcome or "ok"
  if not PHASES[phase] or not OUTCOMES[outcome] then fault(record);return end
  add(record,"seconds",phase..":"..outcome,value)
end
function M.finish(record,phase,started,outcome)
  if started==nil then return end -- clock() already marked missing coverage.
  local ended=M.clock(record)
  if ended then
    if ended<started then fault(record)
    else M.seconds(record,phase,ended-started,outcome) end
  end
end
function M.lag(record,phase,due,outcome)
  if due==nil then return end
  local entered=M.clock(record)
  if entered then M.seconds(record,phase,math.max(0,entered-due),outcome) end
end
function M.batch(record,count,bytes,reason)
  if not integer(count) or count<1 or count>128 or not integer(bytes)
      or bytes>1048576 or not REASONS[reason] then fault(record);return end
  add(record,"batch_events","all",count);add(record,"batch_bytes","all",bytes)
  record.flush_reasons[reason]=(record.flush_reasons[reason] or 0)+1
end
function M.aggregate() return {record=M.new(),observed=0,valid=true} end
local function valid_histogram(h,limits)
  if type(h)~="table" or not integer(h.count) or not finite(h.sum) or not finite(h.max)
      or type(h.buckets)~="table" or h.sum<h.max or (h.count==0 and (h.sum~=0 or h.max~=0)) then return false end
  local previous=0
  for i,limit in ipairs(limits) do
    local n=h.buckets[i] or 0
    if not integer(n) or n<previous or n>h.count or (h.max<=limit and n~=h.count) then return false end
    previous=n
  end
  for i in pairs(h.buckets) do if not integer(i) or i<1 or i>#limits then return false end end
  return true
end
function M.include(total,record)
  if type(record)~="table" or record.version~=1 or record.clock~="monotonic"
      or not integer(record.invalid) then total.valid=false;return end
  local allowed={}
  for phase in pairs(PHASES) do for outcome in pairs(OUTCOMES) do allowed[phase..":"..outcome]=true end end
  for kind,limits in pairs(LIMITS) do
    if type(record[kind])~="table" then total.valid=false;return end
    for key,h in pairs(record[kind]) do
      if not (kind=="seconds" and allowed[key] or kind~="seconds" and key=="all")
          or not valid_histogram(h,limits) then total.valid=false;return end
    end
  end
  if type(record.flush_reasons)~="table" then total.valid=false;return end
  local flushed=0
  for reason,count in pairs(record.flush_reasons) do
    if not REASONS[reason] or not integer(count) then total.valid=false;return end
    flushed=flushed+count
  end
  local events,bytes=record.batch_events.all,record.batch_bytes.all
  if flushed~=(events and events.count or 0) or flushed~=(bytes and bytes.count or 0) then total.valid=false;return end
  total.observed=total.observed+1;total.record.invalid=total.record.invalid+record.invalid
  for kind,limits in pairs(LIMITS) do
    for key,h in pairs(record[kind]) do
      local target=total.record[kind][key]
      if not target then target=histogram(kind);total.record[kind][key]=target end
      target.count=target.count+h.count;target.sum=target.sum+h.sum;target.max=math.max(target.max,h.max)
      for i=1,#limits do target.buckets[i]=(target.buckets[i] or 0)+(h.buckets[i] or 0) end
    end
  end
  for reason,count in pairs(record.flush_reasons) do
    total.record.flush_reasons[reason]=(total.record.flush_reasons[reason] or 0)+count
  end
end
function M.emit(total,retained,complete)
  local record=total.record
  for kind,limits in pairs(LIMITS) do
    for key,h in pairs(record[kind]) do
      local labels=""
      if kind=="seconds" then
        local phase,outcome=key:match("^([^:]+):([^:]+)$")
        labels='phase="'..phase..'",outcome="'..outcome..'"'
      end
      local suffix=labels~="" and "{"..labels.."}" or ""
      local prefix="shortlink_edge_exec_"..kind
      for _,stat in ipairs({"count","sum","max"}) do ngx.say(prefix,"_",stat,suffix," ",h[stat]) end
      for i,limit in ipairs(limits) do
        ngx.say(prefix,'_bucket{',labels,labels~="" and "," or "",'le="',limit,'"} ',h.buckets[i] or 0)
      end
      ngx.say(prefix,'_bucket{',labels,labels~="" and "," or "",'le="+Inf"} ',h.count)
    end
  end
  for reason,count in pairs(record.flush_reasons) do
    ngx.say('shortlink_edge_exec_flush_total{reason="',reason,'"} ',count)
  end
  ngx.say("shortlink_edge_exec_invalid ",record.invalid)
  ngx.say("shortlink_edge_exec_observed_worker_generations ",total.observed)
  ngx.say("shortlink_edge_exec_observation_complete ",
    complete and total.valid and record.invalid==0 and retained>0 and total.observed==retained and 1 or 0)
end
return M
