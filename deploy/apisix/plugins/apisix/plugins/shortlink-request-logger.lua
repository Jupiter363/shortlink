local core = require("apisix.core")
local kafka = require("resty.kafka.producer")
local http_timing = require("apisix.plugins.shortlink-http-timing")
local worker_identity = require("apisix.plugins.shortlink-worker-identity")
local _M = { version = 0.4, priority = 399, name = "shortlink-request-logger",
 schema = { type="object", properties={
  brokers={type="array", minItems=1, items={type="object",properties={host={type="string"},port={type="integer",minimum=1,maximum=65535},sasl_config={type="object",properties={mechanism={type="string",enum={"PLAIN"}},user={type="string"},password={type="string"}},required={"mechanism","user","password"}}},required={"host","port"}}},
  ssl={type="boolean",default=false},
  instance_id={type="string",minLength=1,maxLength=80,pattern="^[!-~]+$"},queue_count={type="integer",minimum=1,maximum=100000},
  queue_bytes={type="integer",minimum=4096},max_event_bytes={type="integer",minimum=512,maximum=16384},
  send_concurrency={type="integer",minimum=1,maximum=8,default=2},
  send_batch_size={type="integer",minimum=1,maximum=128,default=1},
  send_batch_bytes={type="integer",minimum=16384,maximum=1048576,default=65536},
  send_linger_ms={type="integer",minimum=0,maximum=10,enum={0,1,2,3,4,5,10},default=0},
  http_timing_enabled={type="boolean",default=false},
  execution_diagnostics={type="boolean",default=false}
 },required={"brokers","instance_id","queue_count","queue_bytes","max_event_bytes"} } }
local queue, head, tail, count, bytes = {}, 1, 0, 0, 0
local slots, active_senders, inflight_count, desired_concurrency = {}, 0, 0, 2
local reserved_senders = 0
local diagnostics = {send_attempts=0,send_seconds_sum=0,send_seconds_max=0,retry_attempts=0,
  producer_creations=0,producer_failures=0,timer_failures=0,drain_errors=0}
-- Separate extension: a retained registered v3 ledger can lack batch diagnostics.
-- Its event ledger remains valid; missing extension coverage is explicit.
local batch_diagnostics = {calls=0,event_attempts=0,produce_requests=0,produce_records=0,metadata_requests=0}
local rejection_reasons = {identity_unavailable=0,worker_exiting=0,encoding=0,event_size=0,
  queue_count=0,queue_bytes=0,timer_unavailable=0}
function _M.check_schema(conf) return core.schema.check(_M.schema,conf) end
local metric_names = {"attempted", "delivered", "failed", "rejected"}
local worker_generation, worker_key, observation_boot, worker_id, worker_pid
local worker_registered = false
local worker_totals = {attempted=0, delivered=0, failed=0, rejected=0}
-- Lazily initialized only by an enabled request or an enabled retained record.
local execution, execution_record, execution_sequence
local function observation_fault()
  -- This reserved key is never evicted: all writes below use safe_add/safe_set.
  ngx.shared.shortlink_edge_metrics:incr("observation_faults",1)
end
local function observation_identity()
  if observation_boot then return observation_boot end
  -- A shared etcd rule contains a deployment label, not one node's identity.
  -- The shared dictionary survives worker reloads with its counters, but a full
  -- instance restart gets a new boot id. Atomic add makes it common to workers.
  local dictionary=ngx.shared.shortlink_edge_metrics
  dictionary:safe_add("observation_faults",0)
  local boot=dictionary:get("boot_id")
  if not boot then
    dictionary:safe_add("boot_id",core.id.gen_uuid_v4())
    boot=dictionary:get("boot_id")
  end
  dictionary:safe_add("started_at",ngx.now())
  dictionary:safe_add("registration_pending",0)
  dictionary:safe_add("registration_revision",0)
  for _, name in ipairs(metric_names) do dictionary:safe_add(name,0) end
  observation_boot=boot
  return boot
end
local function metric(name, amount)
  amount=amount or 1
  if amount==0 then return end
  local ok=ngx.shared.shortlink_edge_metrics:incr(name,amount)
  if not ok then observation_fault() end
  worker_totals[name]=worker_totals[name]+amount
end
local function reject(reason)
  rejection_reasons[reason]=rejection_reasons[reason]+1
  metric("rejected")
end
local function worker_record()
  -- One atomic value keeps each worker's count/bytes and terminal totals coherent.
  -- A dequeued item remains in count/bytes while producer.send is in flight.
  -- Never reuse a prior worker's key: crash/reload residue must remain observable.
  -- The 1 MiB dictionary bounds retained generations; exhaustion marks evidence
  -- incomplete instead of evicting an old pending record or altering event sends.
  local oldest=queue[head] and queue[head].created
  for _, slot in ipairs(slots) do
    if slot.item and (not oldest or slot.item.created<oldest) then oldest=slot.item.created end
  end
  return core.json.encode({worker_id=worker_id,worker_pid=worker_pid,generation=worker_generation,
    boot_id=observation_boot,
    pending_count=count,pending_bytes=bytes,attempted=worker_totals.attempted,
    delivered=worker_totals.delivered,failed=worker_totals.failed,rejected=worker_totals.rejected,
    queued_count=count-inflight_count,inflight_count=inflight_count,active_senders=active_senders,
    oldest_created=oldest,diagnostics=diagnostics,rejection_reasons=rejection_reasons,
    record_version=3,batch_diagnostics=batch_diagnostics,http_timing=http_timing.snapshot()})
end
local function register_worker()
  if worker_registered then return true end
  local id,pid,workers=ngx.worker.id(),ngx.worker.pid(),ngx.worker.count()
  -- APISIX calls plugin.init() in ordinary workers and its privileged agent.
  -- Only the configured ordinary workers participate in the event ledger.
  if type(id)~="number" or id<0 or id>=workers then return false end
  local dictionary=ngx.shared.shortlink_edge_metrics
  if not observation_identity() then observation_fault();return false end
  if not worker_key then
    worker_id=id;worker_pid=pid
    worker_generation=tostring(pid)..":"..core.id.gen_uuid_v4()
    worker_key="worker:"..tostring(id)..":"..worker_generation
  end
  -- Register before serving traffic. Bookended revision/pending reads let the
  -- collector reject a scrape crossing this multi-key transaction, including
  -- simultaneous registrations; an odd/even revision alone would not suffice.
  if not dictionary:incr("registration_pending",1) then observation_fault();return false end
  local ok,result=pcall(function()
    assert(dictionary:incr("registration_revision",1))
    local registration=core.json.encode({worker_id=worker_id,worker_pid=worker_pid,
      generation=worker_generation,boot_id=observation_boot,record_version=3})
    assert(registration and dictionary:safe_set("registered:"..worker_key,registration))
    local record=worker_record()
    assert(record and dictionary:safe_set(worker_key,record))
    assert(dictionary:safe_set("current:"..tostring(worker_id),worker_key))
    assert(dictionary:incr("registration_revision",1))
    return true
  end)
  if not dictionary:incr("registration_pending",-1) then observation_fault() end
  if not ok or not result then observation_fault();return false end
  worker_registered=true
  return true
end
local fd_preparation_attempted = false
function _M.init()
  local failure
  if not fd_preparation_attempted then
    fd_preparation_attempted = true
    local ok, result = pcall(function()
      return require("apisix.plugins.shortlink-fd-preallocate").init(1023)
    end)
    if not ok then
      failure = {status="INITIALIZATION_FAILED"}
    elseif type(result) ~= "table" or rawget(result,"ok") ~= true then
      failure = type(result) == "table" and result or {status="INVALID_RESULT"}
    end
  end
  -- Optional startup preparation must never prevent the existing v3 registration.
  register_worker()
  if failure then
    -- Emit at most one bounded failure record per worker; logging is optional too.
    pcall(function()
      local status = rawget(failure,"status")
      if type(status) ~= "string" or #status > 64 or not status:match("^[A-Z0-9_]+$") then
        status = "INVALID_RESULT"
      end
      local function number(value)
        if type(value) == "number" and value >= 0 and value <= 9007199254740991
            and value == math.floor(value) then return value end
        return "unknown"
      end
      core.log.warn("shortlink fd preparation failed: status=",status,
        " worker_id=",number(ngx.worker.id())," worker_pid=",number(ngx.worker.pid()),
        " errno=",number(rawget(failure,"errno")),
        " close_errno=",number(rawget(failure,"closeErrno")),
        " soft_limit=",number(rawget(failure,"softLimit")),
        " hard_limit=",number(rawget(failure,"hardLimit")))
    end)
  end
end
local function publish_pending(sample_execution)
  if not register_worker() then return end
  if sample_execution then
    local started=execution.clock(execution_record)
    local success,problem=pcall(function()
      local record=worker_record()
      local ok=record and ngx.shared.shortlink_edge_metrics:safe_set(worker_key,record)
      if not ok then observation_fault() end
      return ok
    end)
    execution.finish(execution_record,"ledger_publish",started,success and problem and "ok" or "error")
    if not success then error(problem,0) end -- Preserve the pre-existing exceptional behavior.
    return
  end
  local record=worker_record()
  local ok=record and ngx.shared.shortlink_edge_metrics:safe_set(worker_key,record)
  if not ok then observation_fault() end
end
local function publish_execution()
  if not execution_record or not worker_registered then return end
  -- Separate record: never serialized by the per-request v3 publication. A
  -- diagnostic failure cannot alter event ACKs, admission or drain ownership.
  local success,written=pcall(function()
    local value=core.json.encode({record_version=3,worker_id=worker_id,worker_pid=worker_pid,
      generation=worker_generation,boot_id=observation_boot,observed_at=ngx.now(),
      diagnostics=execution_record})
    if not value or #value>32768 then return false end
    return ngx.shared.shortlink_edge_metrics:safe_set("exec:"..worker_key,value)
  end)
  if not success or not written then
    execution_record.invalid=execution_record.invalid+1
    -- A previous successful snapshot must not claim coverage after this failure.
    -- Delete only our optional diagnostic key; v3 accounting remains untouched.
    ngx.shared.shortlink_edge_metrics:delete("exec:"..worker_key)
  end
end
local function instance_id(conf)
  local boot=observation_identity()
  if not boot then observation_fault();return nil end
  return conf.instance_id..":"..ngx.var.hostname..":"..boot
end
local function label(value)
  return tostring(value):gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n")
end
local function finite(value)
  return type(value)=="number" and value>=0 and value<math.huge
end
local function integer(value)
  return finite(value) and value<=9007199254740991 and value%1==0
end
local function live_pids()
  return worker_identity.pids()
end
local function same_pids(first,last)
  if not first or not last then return false end
  for pid in pairs(first) do if not last[pid] then return false end end
  for pid in pairs(last) do if not first[pid] then return false end end
  return true
end
local function valid_identity(record,key,boot)
  return type(record)=="table" and record.record_version==3
    and record.boot_id==boot and integer(record.worker_id) and integer(record.worker_pid)
    and record.worker_pid>0 and type(record.generation)=="string"
    and record.generation:sub(1,#tostring(record.worker_pid)+1)==tostring(record.worker_pid)..":"
    and key=="worker:"..tostring(record.worker_id)..":"..record.generation
end
local function valid_record(record)
  for _,name in ipairs(metric_names) do if not integer(record[name]) then return false end end
  if not integer(record.pending_count) or not integer(record.pending_bytes)
    or not integer(record.queued_count) or not integer(record.inflight_count)
    or not integer(record.active_senders) or record.active_senders>8 then return false end
  if record.attempted~=record.delivered+record.failed+record.rejected+record.pending_count
    or record.queued_count+record.inflight_count~=record.pending_count
    or (record.pending_count==0)~=(record.pending_bytes==0) then return false end
  if record.pending_count>0 and (not finite(record.oldest_created) or record.oldest_created==0) then return false end
  return true
end
function _M.metrics()
  local dictionary=ngx.shared.shortlink_edge_metrics
  register_worker()
  local boot=observation_identity()
  local started=dictionary:get("started_at")
  local revision=dictionary:get("registration_revision")
  local registering=dictionary:get("registration_pending")
  local workers=ngx.worker.count()
  local pids_before=live_pids()
  local total_count,total_bytes,retained,registered=0,0,0,0
  local queued,inflight,senders,oldest_age=0,0,0,0
  local diagnostic_totals,rejection_totals,totals={},{},{}
  local batch_totals,batch_complete={},true
  local execution_total
  local execution_records={}
  local http_totals=http_timing.aggregate()
  for _,name in ipairs(metric_names) do totals[name]=0 end
  for name in pairs(batch_diagnostics) do batch_totals[name]=0 end
  for name in pairs(diagnostics) do diagnostic_totals[name]=0 end
  for name in pairs(rejection_reasons) do rejection_totals[name]=0 end
  local complete=type(boot)=="string" and #boot>0 and finite(started) and started>0
    and dictionary:get("observation_faults")==0
    and integer(revision) and registering==0 and integer(workers) and workers>0 and pids_before~=nil
  -- Coverage is shared by all record-derived sections, unlike event-specific
  -- reconciliation. A valid subset must not claim complete HTTP observations.
  local structural_coverage=complete
  ngx.header.content_type="text/plain; version=0.0.4; charset=utf-8"
  ngx.header["Cache-Control"]="no-store"
  -- Each JSON value is atomic; different workers need not be sampled at the
  -- same instant. Quality counts and pending always come from those same rows.
  -- Registration rows persist just like worker rows, so losing either one is
  -- visible even after that generation has no pending events or has exited.
  local records,registrations={},{}
  for _,key in ipairs(dictionary:get_keys(0)) do
    if key:sub(1,7)=="worker:" then
      local record=core.json.decode(dictionary:get(key))
      records[key]=record or false
      if valid_identity(record,key,boot) and valid_record(record) then
        total_count=total_count+record.pending_count;total_bytes=total_bytes+record.pending_bytes
        retained=retained+1
        for _,name in ipairs(metric_names) do totals[name]=totals[name]+record[name] end
        queued=queued+record.queued_count;inflight=inflight+record.inflight_count
        senders=senders+record.active_senders
        local labels='{worker_id="'..label(record.worker_id)..'",generation="'..label(record.generation)..'"}'
        ngx.say("shortlink_edge_worker_pending_count",labels," ",record.pending_count)
        ngx.say("shortlink_edge_worker_pending_bytes",labels," ",record.pending_bytes)
        ngx.say("shortlink_edge_worker_inflight_count",labels," ",record.inflight_count)
        ngx.say("shortlink_edge_worker_active_senders",labels," ",record.active_senders)
        if record.pending_count>0 then oldest_age=math.max(oldest_age,math.max(0,ngx.now()-record.oldest_created)) end
        for name in pairs(diagnostics) do
          local value=type(record.diagnostics)=="table" and record.diagnostics[name]
          local valid=(name=="send_seconds_sum" or name=="send_seconds_max") and finite(value) or integer(value)
          if valid then
            if name=="send_seconds_max" then diagnostic_totals[name]=math.max(diagnostic_totals[name],value)
            else diagnostic_totals[name]=diagnostic_totals[name]+value end
          else complete=false end
        end
        local rejected=0
        for name in pairs(rejection_reasons) do
          local value=type(record.rejection_reasons)=="table" and record.rejection_reasons[name]
          if integer(value) then rejection_totals[name]=rejection_totals[name]+value;rejected=rejected+value
          else complete=false end
        end
        if rejected~=record.rejected then complete=false end
        for name in pairs(batch_diagnostics) do
          local value=type(record.batch_diagnostics)=="table" and record.batch_diagnostics[name]
          if integer(value) then batch_totals[name]=batch_totals[name]+value else batch_complete=false end
        end
        http_timing.include(http_totals,record.http_timing)
      else complete=false;structural_coverage=false end
    elseif key:sub(1,5)=="exec:" then
      execution_records[key:sub(6)]=core.json.decode(dictionary:get(key)) or false
    elseif key:sub(1,11)=="registered:" then
      local target=key:sub(12)
      local registration=core.json.decode(dictionary:get(key))
      registrations[target]=registration or false;registered=registered+1
      if not valid_identity(registration,target,boot) then complete=false;structural_coverage=false end
    end
  end
  for key,record in pairs(records) do
    local registration=registrations[key]
    if not valid_identity(registration,key,boot) or not valid_identity(record,key,boot)
      or registration.worker_pid~=record.worker_pid then complete=false;structural_coverage=false end
  end
  for key in pairs(registrations) do
    if not records[key] then complete=false;structural_coverage=false end
  end
  if integer(workers) and workers>0 then
    for id=0,workers-1 do
      local key=dictionary:get("current:"..tostring(id))
      local registration=key and registrations[key]
      if not valid_identity(registration,key,boot) or registration.worker_id~=id
        or not records[key] or not pids_before or not pids_before[registration.worker_pid] then
        complete=false;structural_coverage=false
      end
    end
  end
  -- Raw counters intentionally remain independent diagnostics. During active
  -- sends their read time can differ from a worker row; that is not a loss.
  -- A quiet snapshot must still reconcile ALL raw counters exactly, so a lost
  -- final row/update or a crash gap cannot masquerade as successful draining.
  local reconciled=true
  for _,name in ipairs(metric_names) do
    local value=dictionary:get(name)
    if not integer(value) then complete=false;reconciled=false
    elseif value~=totals[name] then reconciled=false end
    if not integer(totals[name]) then complete=false end
    ngx.say("shortlink_edge_raw_events_",name," ",integer(value) and value or "NaN")
    ngx.say("shortlink_edge_events_",name," ",totals[name])
  end
  if not integer(total_count) or not integer(total_bytes) then complete=false end
  if total_count==0 and (not reconciled or total_bytes~=0 or senders~=0) then complete=false end
  local registration_after=dictionary:get("registration_pending")
  if registration_after~=0 or dictionary:get("registration_revision")~=revision
    or dictionary:get("boot_id")~=boot or dictionary:get("started_at")~=started
    or not same_pids(pids_before,live_pids()) then complete=false;structural_coverage=false end
  local faults=dictionary:get("observation_faults")
  if not integer(faults) or faults~=0 then complete=false;structural_coverage=false end
  ngx.say('shortlink_edge_instance_info{instance="',label(ngx.var.hostname),'",boot_id="',label(boot or "UNAVAILABLE"),'"} 1')
  ngx.say("shortlink_edge_started_at_seconds ",finite(started) and started>0 and started or "NaN")
  ngx.say("shortlink_edge_snapshot_version 3")
  ngx.say("shortlink_edge_raw_global_reconciled ",reconciled and 1 or 0)
  ngx.say("shortlink_edge_registered_worker_generations ",registered)
  ngx.say("shortlink_edge_registration_pending ",integer(registration_after) and registration_after or "NaN")
  ngx.say("shortlink_edge_pending_count ",total_count)
  ngx.say("shortlink_edge_pending_bytes ",total_bytes)
  ngx.say("shortlink_edge_queued_count ",queued)
  ngx.say("shortlink_edge_inflight_count ",inflight)
  ngx.say("shortlink_edge_active_senders ",senders)
  ngx.say("shortlink_edge_oldest_pending_seconds ",oldest_age)
  for name,value in pairs(diagnostic_totals) do ngx.say("shortlink_edge_",name," ",value) end
  for name,value in pairs(batch_totals) do ngx.say("shortlink_edge_batch_",name," ",value) end
  ngx.say("shortlink_edge_batch_observation_complete ",batch_complete and complete and 1 or 0)
  for name,value in pairs(rejection_totals) do ngx.say('shortlink_edge_rejections_by_reason{reason="',name,'"} ',value) end
  ngx.say("shortlink_edge_retained_worker_generations ",retained)
  ngx.say("shortlink_edge_observation_faults ",integer(faults) and faults or "NaN")
  ngx.say("shortlink_edge_observation_complete ",complete and 1 or 0)
  if not structural_coverage then http_totals.valid=false end
  http_timing.emit(http_totals,retained)
  -- All optional snapshots have been copied. Refresh the scraper's cached
  -- clock once so another worker's newer cache is not mistaken for the future.
  -- Keep the original v3/HTTP calculations above unchanged; a refresh failure
  -- invalidates only diagnostic coverage and never the business ledger.
  local execution_now
  if next(execution_records) then
    local ok,value=pcall(function() ngx.update_time();return ngx.now() end)
    if ok and finite(value) then execution_now=value end
  end
  local maximum_age,fresh=0,0
  for key,snapshot in pairs(execution_records) do
    execution=execution or require("apisix.plugins.shortlink-execution-diagnostics")
    execution_total=execution_total or execution.aggregate()
    if records[key] and valid_identity(snapshot,key,boot) and finite(snapshot.observed_at)
        and execution_now and snapshot.observed_at<=execution_now then
      local age=execution_now-snapshot.observed_at
      maximum_age=math.max(maximum_age,age)
      if age<=15 then fresh=fresh+1 end
      execution.include(execution_total,snapshot.diagnostics)
    else execution_total.valid=false end
  end
  ngx.say("shortlink_edge_exec_snapshot_max_age_seconds ",maximum_age)
  ngx.say("shortlink_edge_exec_snapshot_fresh_worker_generations ",fresh)
  if execution_total then execution.emit(execution_total,retained,complete and fresh==retained)
  else
    ngx.say("shortlink_edge_exec_observed_worker_generations 0")
    ngx.say("shortlink_edge_exec_observation_complete 0")
  end
end
local schedule, drain, linger_callback
-- This timer owns no event and reserves no sender. Retained worker ledgers
-- continue to account for every queued byte even while no sender is active.
local linger_wakeup
local function finish(slot, ok)
  local item=slot.item
  -- Only this reserved slot owns the item; release once, after final confirmation.
  slot.item=nil;inflight_count=inflight_count-1
  count=count-1;bytes=bytes-item.size
  metric(ok and "delivered" or "failed")
  -- No yield follows this transition before the next pre-send publication or
  -- drain's unconditional final publication. Avoid serializing the same worker
  -- record twice on the busy path; the next snapshot includes this terminal.
end
local function finish_batch(slot)
  local delivered,failed,released_bytes=0,0,0
  for _,item in ipairs(slot.items) do
    released_bytes=released_bytes+item.size
    if item.acked then delivered=delivered+1 else failed=failed+1 end
  end
  local released=delivered+failed
  slot.items=nil;slot.item=nil
  inflight_count=inflight_count-released;count=count-released;bytes=bytes-released_bytes
  metric("delivered",delivered);metric("failed",failed)
end
local function send_batch(slot)
  local conf=slot.item.conf
  if slot.conf~=conf then slot.producer=nil;slot.conf=conf end
  for attempt=1,3 do
    local eligible=0
    for _,item in ipairs(slot.items) do
      if not item.acked and not item.terminal and ngx.now()-item.created<30 then eligible=eligible+1 end
    end
    if eligible==0 or ngx.worker.exiting() then break end
    if not slot.producer then
      local batch=require("apisix.plugins.shortlink-kafka-batch")
      local created,candidate=pcall(batch.new,batch,conf.brokers,{
        request_timeout=1000,socket_timeout=1500,ssl=conf.ssl,ssl_verify=conf.ssl})
      if created and candidate then
        slot.producer=candidate;diagnostics.producer_creations=diagnostics.producer_creations+1
      else diagnostics.producer_failures=diagnostics.producer_failures+1;break end
    end
    if attempt>1 then diagnostics.retry_attempts=diagnostics.retry_attempts+1 end
    diagnostics.send_attempts=diagnostics.send_attempts+1
    batch_diagnostics.calls=batch_diagnostics.calls+1
    batch_diagnostics.event_attempts=batch_diagnostics.event_attempts+eligible
    local producer=slot.producer
    local before={}
    for _,name in ipairs({"produce_requests","produce_records","metadata_requests"}) do
      before[name]=producer.stats and producer.stats[name] or 0
    end
    local started=ngx.now()
    local success=pcall(producer.send_batch,producer,"shortlink.gateway.request.v1",slot.items,slot.item.execution)
    local elapsed=math.max(0,ngx.now()-started)
    diagnostics.send_seconds_sum=diagnostics.send_seconds_sum+elapsed
    diagnostics.send_seconds_max=math.max(diagnostics.send_seconds_max,elapsed)
    for name,value in pairs(before) do
      batch_diagnostics[name]=batch_diagnostics[name]+math.max(0,(producer.stats and producer.stats[name] or 0)-value)
    end
    -- ACK flags belong to the immutable batch, not the producer instance. An
    -- exception after one broker succeeds cannot replay those confirmed items.
    if not success then slot.producer=nil end
  end
end
local function send_item(slot, item)
  local conf=item.conf
  if slot.conf~=conf then slot.producer=nil;slot.conf=conf end
  for attempt=1,3 do
    if ngx.now()-item.created>=30 then break end
    if not slot.producer then
      -- In the pinned lua-resty-kafka 0.20 client only async instances are cached.
      -- Each sync instance has mutable sendbuffer/correlation state, owned by one slot.
      local created, candidate=pcall(kafka.new,kafka,conf.brokers,{producer_type="sync",
        required_acks=-1,max_retry=3,request_timeout=1000,socket_timeout=1500,
        batch_num=1,max_buffering=1,
        ssl=conf.ssl,ssl_verify=conf.ssl})
      if created and candidate then
        slot.producer=candidate;diagnostics.producer_creations=diagnostics.producer_creations+1
      else diagnostics.producer_failures=diagnostics.producer_failures+1;break end
    end
    if attempt>1 then diagnostics.retry_attempts=diagnostics.retry_attempts+1 end
    diagnostics.send_attempts=diagnostics.send_attempts+1
    local started=ngx.now()
    local success, sent=pcall(slot.producer.send,slot.producer,
      "shortlink.gateway.request.v1",item.key,item.body)
    local elapsed=math.max(0,ngx.now()-started)
    diagnostics.send_seconds_sum=diagnostics.send_seconds_sum+elapsed
    diagnostics.send_seconds_max=math.max(diagnostics.send_seconds_max,elapsed)
    if success and sent then return true end
    -- An exception can leave mutable client buffers partially populated. Rebuild
    -- before retrying the same immutable event; never mix it into a later event.
    if not success then slot.producer=nil end
    if ngx.worker.exiting() then break end
  end
  return false
end
-- Cache only immutable prefix facts. The clock/deadline checks stay live.
-- Keep one worker-local cache, with no allocation per readiness check.
local ready_cache_head, ready_cache_conf, ready_cache_batch_size, ready_cache_byte_limit
local ready_cache_next, ready_cache_last, ready_cache_items, ready_cache_bytes
local function ready_head(now)
  local item=queue[head]
  if not item then
    ready_cache_head=nil;ready_cache_conf=nil;ready_cache_last=nil
    return false
  end
  local conf=item.conf
  local linger=conf.send_linger_ms or 0
  local batch_size=conf.send_batch_size or 1
  if linger==0 or batch_size==1 then return true,nil,"immediate" end
  local deadline=item.created+linger/1000
  if now>=deadline then return true,nil,"age" end
  -- Hot configuration can shorten the new head's deadline below an old armed
  -- wakeup. Timers cannot be cancelled; send this prefix early rather than
  -- creating a second future timer or extending the new configuration's wait.
  if linger_wakeup and linger_wakeup.deadline>deadline then return true,nil,"configuration" end
  local byte_limit=conf.send_batch_bytes or 65536
  if ready_cache_head~=item or ready_cache_conf~=conf
     or ready_cache_batch_size~=batch_size or ready_cache_byte_limit~=byte_limit
     or (ready_cache_items>0 and
         (ready_cache_next-1>tail or queue[ready_cache_next-1]~=ready_cache_last)) then
    ready_cache_head=item;ready_cache_conf=conf
    ready_cache_batch_size=batch_size;ready_cache_byte_limit=byte_limit
    ready_cache_next=head;ready_cache_last=nil;ready_cache_items=0;ready_cache_bytes=0
  end
  -- Readiness can be checked twice before a reserved callback claims this head.
  if ready_cache_items>=batch_size then return true,nil,"count" end
  if ready_cache_bytes>=byte_limit then return true,nil,"bytes" end
  for index=ready_cache_next,math.min(tail,head+batch_size-1) do
    local candidate=queue[index]
    -- Do not cache a closing boundary: admission can roll back that new tail
    -- when timer creation fails. Re-check the excluded candidate next time.
    if candidate.conf~=conf then return true,nil,"configuration" end
    if ready_cache_bytes+candidate.size>byte_limit then return true,nil,"bytes" end
    ready_cache_bytes=ready_cache_bytes+candidate.size;ready_cache_items=ready_cache_items+1
    ready_cache_next=index+1;ready_cache_last=candidate
    if ready_cache_items>=batch_size then return true,nil,"count" end
    if ready_cache_bytes>=byte_limit then return true,nil,"bytes" end
  end
  return false,deadline
end

local function arm_linger(deadline)
  if linger_wakeup then return true end
  local token={deadline=deadline}
  -- OpenResty converts seconds to integer milliseconds. A fractional remainder
  -- can truncate to zero while cached ngx.now() stays unchanged, causing an
  -- unbounded immediate-rearm loop. Only this optional future wakeup rounds up;
  -- ready batches still take the existing immediate sender path.
  local delay_ms=math.max(1,math.ceil((deadline-ngx.now())*1000))
  if queue[head] and queue[head].execution then
    token.execution=queue[head].execution
    local now=execution.clock(token.execution)
    if now then token.execution_due=now+delay_ms/1000 end
  end
  local ok=ngx.timer.at(delay_ms/1000,linger_callback,token)
  if not ok then
    diagnostics.timer_failures=diagnostics.timer_failures+1
    return false
  end
  -- timer.at does not yield or invoke its callback inline.
  linger_wakeup=token
  return true
end

local function drain_items(slot, bypass_once)
  while head<=tail and not ngx.worker.exiting() and active_senders<=desired_concurrency do
    local ready,deadline,reason=ready_head(ngx.now())
    if not ready and not bypass_once then
      if not deadline or arm_linger(deadline) then break end
      -- A timer-creation failure must not strand the existing queue after its
      -- last sender releases its slot. This timer context may shorten only this
      -- batch's optimization wait; every budget, ownership and ACK rule remains.
    end
    if not ready then reason="timer_fallback" end
    bypass_once=false
    local item=queue[head]
    local batch_size=item.conf.send_batch_size or 1
    if batch_size>1 then
      local items,batch_bytes={},0
      local byte_limit=item.conf.send_batch_bytes or 65536
      while head<=tail and #items<batch_size do
        local candidate=queue[head]
        if candidate.conf~=item.conf or batch_bytes+candidate.size>byte_limit then break end
        items[#items+1]=candidate;batch_bytes=batch_bytes+candidate.size
        queue[head]=nil;head=head+1
      end
      assert(#items>0,"event exceeds validated batch byte limit")
      slot.items=items;inflight_count=inflight_count+#items
    else
      queue[head]=nil;head=head+1;inflight_count=inflight_count+1
    end
    if head>tail then queue={};head=1;tail=0 end
    slot.item=item
    if item.execution then
      local selected=slot.items or {item}
      local selected_bytes=0
      for _,candidate in ipairs(selected) do
        selected_bytes=selected_bytes+candidate.size
        execution.seconds(item.execution,"dequeue_age",math.max(0,ngx.now()-candidate.created),"ok")
      end
      execution.batch(item.execution,#selected,selected_bytes,reason)
    end
    -- A batch timer claims its real prefix before reserving the next callback.
    -- This is non-yielding: independent slots can still overlap ACK waits, while
    -- admissions cannot pre-reserve several timers for this same batch.
    if slot.items then schedule() end
    publish_pending()
    if slot.items then send_batch(slot);finish_batch(slot)
    else finish(slot,send_item(slot,item)) end
    if execution_record then publish_execution() end
  end
end
drain = function(premature, slot, bypass_once)
  if slot.execution and slot.execution_due then
    execution.lag(slot.execution,"sender_lag",slot.execution_due,
      premature and "premature" or (ngx.worker.exiting() and "exiting" or "ok"))
  end
  slot.execution=nil;slot.execution_due=nil
  reserved_senders=reserved_senders-1
  if not premature and not ngx.worker.exiting() then
    local ok=pcall(drain_items,slot,bypass_once)
    if not ok then
      diagnostics.drain_errors=diagnostics.drain_errors+1
      slot.producer=nil
      if slot.items then finish_batch(slot)
      elseif slot.item then finish(slot,false) end
    end
  end
  slot.busy=false;active_senders=active_senders-1
  -- Premature/exit leaves every unconfirmed queued item in its original generation.
  if not premature and not ngx.worker.exiting() then schedule() end
  publish_pending()
  if execution_record then publish_execution() end
end
schedule = function(inline_timer)
  -- Reserved timers count against the same concurrency budget as active sends.
  -- All dequeue/reservation transitions are non-yielding within this worker.
  -- In-flight batches may contain many items. Only queued items can justify a
  -- new timer; subtract timers already reserved but not yet entered as well.
  while active_senders<desired_concurrency and count-inflight_count>reserved_senders do
    -- Batch cardinality is known only when its callback claims a prefix. Keep
    -- at most one unentered callback for a batch head instead of reserving one
    -- per queued event. Single-item mode keeps its existing eager reservations.
    local first=queue[head]
    if first and (first.conf.send_batch_size or 1)>1 and reserved_senders>0 then break end
    local ready,deadline=ready_head(ngx.now())
    local bypass_once=false
    if not ready then
      if not deadline or arm_linger(deadline) then break end
      if not inline_timer then break end
      -- Rearming failed inside a wakeup callback. Reuse that existing timer
      -- instead of waiting for a new request or recursively creating timers.
      bypass_once=true
    end
    local slot
    for index=1,desired_concurrency do
      if not slots[index] then slots[index]={} end
      if not slots[index].busy then slot=slots[index];break end
    end
    if not slot then break end
    slot.busy=true;active_senders=active_senders+1;reserved_senders=reserved_senders+1
    if first and first.execution then
      slot.execution=first.execution;slot.execution_due=execution.clock(first.execution)
    end
    if inline_timer then
      drain(false,slot,bypass_once)
      return active_senders>0 or linger_wakeup~=nil,true -- drain already published its final state
    end
    local ok=ngx.timer.at(0,drain,slot)
    if not ok then
      slot.busy=false;active_senders=active_senders-1;reserved_senders=reserved_senders-1
      if slot.execution and slot.execution_due then execution.lag(slot.execution,"sender_lag",slot.execution_due,"error") end
      slot.execution=nil;slot.execution_due=nil
      diagnostics.timer_failures=diagnostics.timer_failures+1
      break
    end
  end
  return active_senders>0 or linger_wakeup~=nil
end

linger_callback = function(premature,token)
  if token~=linger_wakeup then return end
  if token.execution and token.execution_due then
    execution.lag(token.execution,"linger_lag",token.execution_due,
      premature and "premature" or (ngx.worker.exiting() and "exiting" or "ok"))
  end
  linger_wakeup=nil
  if not premature and not ngx.worker.exiting() then
    local _,published=schedule(true)
    if published then return end
  end
  -- A wakeup can be obsolete, premature, or find all slots occupied. It never
  -- owns items; publish the retained queue and any actual sender transitions.
  publish_pending()
  if execution_record then publish_execution() end
end
function _M.log(conf,ctx)
  if conf.http_timing_enabled then http_timing.observe(ctx) end
  local sample_execution=false
  if conf.execution_diagnostics then
    execution=execution or require("apisix.plugins.shortlink-execution-diagnostics")
    execution_record=execution_record or execution.new()
    execution_sequence=(execution_sequence or 0)+1
    sample_execution=execution_sequence%64==0
  end
  register_worker()
  local producer_instance=instance_id(conf)
  metric("attempted")
  desired_concurrency=conf.send_concurrency or 2
  if not producer_instance then reject("identity_unavailable");publish_pending(sample_execution);return end
  if ngx.worker.exiting() then reject("worker_exiting");publish_pending(sample_execution);return end
  -- The boundary replaces the caller's header and freezes this trusted value.
  -- Unmatched edge requests have no boundary context; generate an independent id.
  local request_id=ctx.shortlink_request_id or core.id.gen_uuid_v4()
  local occurred_at=math.floor(ngx.now()*1000)
  local decision_id="v1:"..string.format("%.0f",occurred_at)..":"..producer_instance..":"..request_id
  local path=ngx.var.uri or ""
  local short_uri=path:match("^/([A-Za-z0-9]+)$")
  local authority=(ngx.var.http_host or ngx.var.host or ""):lower()
  authority=authority:gsub(ngx.var.scheme=="https" and ":443$" or ":80$",""):gsub("%.$",""):gsub("%.(:%d+)$","%1")
  local upstream=ngx.var.upstream_status
  local event={
    decisionId=decision_id,schemaVersion=1,occurredAt=occurred_at,
    producerInstanceId=producer_instance,source="APISIX",stage="EDGE",method=ngx.req.get_method(),
    status=ngx.status,reason=(upstream and upstream~="") and "UPSTREAM_RESPONSE" or "EDGE_RESPONSE",
    domainNorm=#authority<=253 and authority or nil,shortUri=short_uri,requestId=request_id,traceId=request_id
  }
  local body
  if sample_execution then
    local started=execution.clock(execution_record)
    local ok,value=pcall(core.json.encode,event)
    execution.finish(execution_record,"event_encode",started,ok and value and "ok" or "error")
    if not ok then error(value,0) end
    body=value
  else body=core.json.encode(event) end
  local key=decision_id
  local size=body and #body+#key or 0
  local reason
  if not body then reason="encoding"
  elseif size>conf.max_event_bytes then reason="event_size"
  elseif count>=conf.queue_count then reason="queue_count"
  elseif bytes+size>conf.queue_bytes then reason="queue_bytes" end
  if reason then reject(reason);publish_pending(sample_execution);return end
  tail=tail+1;queue[tail]={key=key,body=body,size=size,created=ngx.now(),conf=conf,
    execution=conf.execution_diagnostics and execution_record or nil}
  count=count+1;bytes=bytes+size
  if not schedule() then
    -- No other reserved sender can own this new item. A partial scheduling
    -- failure with an existing sender instead leaves it queued for that sender.
    queue[tail]=nil;tail=tail-1;count=count-1;bytes=bytes-size
    reject("timer_unavailable")
  end
  publish_pending(sample_execution)
end
return _M
