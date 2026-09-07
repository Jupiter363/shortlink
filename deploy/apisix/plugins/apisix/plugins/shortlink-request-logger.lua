local core = require("apisix.core")
local kafka = require("resty.kafka.producer")
local _M = { version = 0.3, priority = 399, name = "shortlink-request-logger",
 schema = { type="object", properties={
  brokers={type="array", minItems=1, items={type="object",properties={host={type="string"},port={type="integer",minimum=1,maximum=65535},sasl_config={type="object",properties={mechanism={type="string",enum={"PLAIN"}},user={type="string"},password={type="string"}},required={"mechanism","user","password"}}},required={"host","port"}}},
  ssl={type="boolean",default=false},
  instance_id={type="string",minLength=1,maxLength=80,pattern="^[!-~]+$"},queue_count={type="integer",minimum=1,maximum=100000},
  queue_bytes={type="integer",minimum=4096},max_event_bytes={type="integer",minimum=512,maximum=16384},
  send_concurrency={type="integer",minimum=1,maximum=8,default=2},
  send_batch_size={type="integer",minimum=1,maximum=128,default=1},
  send_batch_bytes={type="integer",minimum=16384,maximum=1048576,default=65536}
 },required={"brokers","instance_id","queue_count","queue_bytes","max_event_bytes"} } }
local queue, head, tail, count, bytes = {}, 1, 0, 0, 0
local slots, active_senders, inflight_count, desired_concurrency = {}, 0, 0, 2
local reserved_senders = 0
local diagnostics = {send_attempts=0,send_seconds_sum=0,send_seconds_max=0,retry_attempts=0,
  producer_creations=0,producer_failures=0,timer_failures=0,drain_errors=0}
-- Separate extension: a retained older worker has no batch diagnostics. Its
-- original event ledger remains valid; missing extension coverage is explicit.
local batch_diagnostics = {calls=0,event_attempts=0,produce_requests=0,produce_records=0,metadata_requests=0}
local rejection_reasons = {identity_unavailable=0,worker_exiting=0,encoding=0,event_size=0,
  queue_count=0,queue_bytes=0,timer_unavailable=0}
function _M.check_schema(conf) return core.schema.check(_M.schema,conf) end
local metric_names = {"attempted", "delivered", "failed", "rejected"}
local worker_generation, worker_key, observation_boot
local worker_totals = {attempted=0, delivered=0, failed=0, rejected=0}
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
local function publish_pending()
  if not worker_key then
    worker_generation=tostring(ngx.worker.pid())..":"..core.id.gen_uuid_v4()
    worker_key="worker:"..tostring(ngx.worker.id())..":"..worker_generation
  end
  -- One atomic value keeps each worker's count/bytes and terminal totals coherent.
  -- A dequeued item remains in count/bytes while producer.send is in flight.
  -- Never reuse a prior worker's key: crash/reload residue must remain observable.
  -- The 1 MiB dictionary bounds retained generations; exhaustion marks evidence
  -- incomplete instead of evicting an old pending record or altering event sends.
  local oldest=queue[head] and queue[head].created
  for _, slot in ipairs(slots) do
    if slot.item and (not oldest or slot.item.created<oldest) then oldest=slot.item.created end
  end
  local record=core.json.encode({worker_id=ngx.worker.id(),generation=worker_generation,
    pending_count=count,pending_bytes=bytes,attempted=worker_totals.attempted,
    delivered=worker_totals.delivered,failed=worker_totals.failed,rejected=worker_totals.rejected,
    queued_count=count-inflight_count,inflight_count=inflight_count,active_senders=active_senders,
    oldest_created=oldest,diagnostics=diagnostics,rejection_reasons=rejection_reasons,
    record_version=2,batch_diagnostics=batch_diagnostics})
  local ok=record and ngx.shared.shortlink_edge_metrics:safe_set(worker_key,record)
  if not ok then observation_fault() end
end
local function instance_id(conf)
  local boot=observation_identity()
  if not boot then observation_fault();return nil end
  return conf.instance_id..":"..ngx.var.hostname..":"..boot
end
local function label(value)
  return tostring(value):gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n")
end
function _M.metrics()
  local dictionary=ngx.shared.shortlink_edge_metrics
  local boot=observation_identity()
  local total_count,total_bytes,retained=0,0,0
  local queued,inflight,senders,oldest_age=0,0,0,0
  local diagnostic_totals,rejection_totals={},{}
  local batch_totals,batch_complete={},true
  for name in pairs(batch_diagnostics) do batch_totals[name]=0 end
  for name in pairs(diagnostics) do diagnostic_totals[name]=0 end
  for name in pairs(rejection_reasons) do rejection_totals[name]=0 end
  local complete=boot~=nil and (dictionary:get("observation_faults") or 1)==0
  ngx.header.content_type="text/plain; version=0.0.4; charset=utf-8"
  ngx.header["Cache-Control"]="no-store"
  -- get_keys is bounded by this dedicated fixed-size dictionary; it never scans
  -- URLs, IPs or other user-provided keys. Old worker generations are retained.
  for _, key in ipairs(dictionary:get_keys(0)) do
    if key:sub(1,7)=="worker:" then
      local record=core.json.decode(dictionary:get(key))
      if type(record)=="table" and type(record.pending_count)=="number"
          and type(record.pending_bytes)=="number" and record.pending_count>=0 and record.pending_bytes>=0 then
        total_count=total_count+record.pending_count
        total_bytes=total_bytes+record.pending_bytes
        retained=retained+1
        local labels='{worker_id="'..label(record.worker_id)..'",generation="'..label(record.generation)..'"}'
        ngx.say("shortlink_edge_worker_pending_count",labels," ",record.pending_count)
        ngx.say("shortlink_edge_worker_pending_bytes",labels," ",record.pending_bytes)
        if type(record.queued_count)=="number" and type(record.inflight_count)=="number"
            and record.queued_count+record.inflight_count==record.pending_count
            and record.queued_count>=0 and record.inflight_count>=0 then
          queued=queued+record.queued_count;inflight=inflight+record.inflight_count
          senders=senders+(record.active_senders or 0)
          ngx.say("shortlink_edge_worker_inflight_count",labels," ",record.inflight_count)
          ngx.say("shortlink_edge_worker_active_senders",labels," ",record.active_senders or 0)
        else complete=false end
        if record.pending_count>0 then
          if type(record.oldest_created)=="number" then
            oldest_age=math.max(oldest_age,math.max(0,ngx.now()-record.oldest_created))
          else complete=false end
        end
        for name in pairs(diagnostics) do
          local value=record.diagnostics and record.diagnostics[name]
          if type(value)=="number" and value>=0 then
            if name=="send_seconds_max" then diagnostic_totals[name]=math.max(diagnostic_totals[name],value)
            else diagnostic_totals[name]=diagnostic_totals[name]+value end
          else complete=false end
        end
        for name in pairs(rejection_reasons) do
          local value=record.rejection_reasons and record.rejection_reasons[name]
          if type(value)=="number" and value>=0 then rejection_totals[name]=rejection_totals[name]+value
          else complete=false end
        end
        for name in pairs(batch_diagnostics) do
          local value=record.batch_diagnostics and record.batch_diagnostics[name]
          if type(value)=="number" and value>=0 then batch_totals[name]=batch_totals[name]+value
          else batch_complete=false end
        end
      else complete=false end
    end
  end
  local totals={}
  for _, name in ipairs(metric_names) do
    local value=dictionary:get(name)
    if type(value)~="number" then complete=false end
    totals[name]=value or 0
    ngx.say("shortlink_edge_events_",name," ",value or 0)
  end
  -- A scrape may straddle an admission/terminal update in another worker. Such
  -- a sample is incomplete, never a false zero-pending acknowledgement.
  if totals.attempted~=totals.delivered+totals.failed+totals.rejected+total_count then complete=false end
  if total_count==0 and total_bytes~=0 then complete=false end
  ngx.say('shortlink_edge_instance_info{instance="',label(ngx.var.hostname),'",boot_id="',label(boot or "UNAVAILABLE"),'"} 1')
  ngx.say("shortlink_edge_started_at_seconds ",dictionary:get("started_at") or 0)
  ngx.say("shortlink_edge_pending_count ",total_count)
  ngx.say("shortlink_edge_pending_bytes ",total_bytes)
  ngx.say("shortlink_edge_queued_count ",queued)
  ngx.say("shortlink_edge_inflight_count ",inflight)
  ngx.say("shortlink_edge_active_senders ",senders)
  ngx.say("shortlink_edge_oldest_pending_seconds ",oldest_age)
  for name,value in pairs(diagnostic_totals) do ngx.say("shortlink_edge_",name," ",value) end
  for name,value in pairs(batch_totals) do ngx.say("shortlink_edge_batch_",name," ",value) end
  ngx.say("shortlink_edge_batch_observation_complete ",batch_complete and complete and 1 or 0)
  for name,value in pairs(rejection_totals) do
    ngx.say('shortlink_edge_rejections_by_reason{reason="',name,'"} ',value)
  end
  ngx.say("shortlink_edge_retained_worker_generations ",retained)
  ngx.say("shortlink_edge_observation_faults ",dictionary:get("observation_faults") or 1)
  ngx.say("shortlink_edge_observation_complete ",complete and 1 or 0)
end
local schedule
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
    local success=pcall(producer.send_batch,producer,"shortlink.gateway.request.v1",slot.items)
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
local function drain_items(slot)
  while head<=tail and not ngx.worker.exiting() and active_senders<=desired_concurrency do
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
    publish_pending()
    if slot.items then send_batch(slot);finish_batch(slot)
    else finish(slot,send_item(slot,item)) end
  end
end
local function drain(premature, slot)
  reserved_senders=reserved_senders-1
  if not premature and not ngx.worker.exiting() then
    local ok=pcall(drain_items,slot)
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
end
schedule = function()
  -- Reserved timers count against the same concurrency budget as active sends.
  -- All dequeue/reservation transitions are non-yielding within this worker.
  -- In-flight batches may contain many items. Only queued items can justify a
  -- new timer; subtract timers already reserved but not yet entered as well.
  while active_senders<desired_concurrency and count-inflight_count>reserved_senders do
    local slot
    for index=1,desired_concurrency do
      if not slots[index] then slots[index]={} end
      if not slots[index].busy then slot=slots[index];break end
    end
    if not slot then break end
    slot.busy=true;active_senders=active_senders+1;reserved_senders=reserved_senders+1
    local ok=ngx.timer.at(0,drain,slot)
    if not ok then
      slot.busy=false;active_senders=active_senders-1;reserved_senders=reserved_senders-1
      diagnostics.timer_failures=diagnostics.timer_failures+1
      break
    end
  end
  return active_senders>0
end
function _M.log(conf,ctx)
  local producer_instance=instance_id(conf)
  metric("attempted")
  desired_concurrency=conf.send_concurrency or 2
  if not producer_instance then reject("identity_unavailable");publish_pending();return end
  if ngx.worker.exiting() then reject("worker_exiting");publish_pending();return end
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
  local body=core.json.encode({
    decisionId=decision_id,schemaVersion=1,occurredAt=occurred_at,
    producerInstanceId=producer_instance,source="APISIX",stage="EDGE",method=ngx.req.get_method(),
    status=ngx.status,reason=(upstream and upstream~="") and "UPSTREAM_RESPONSE" or "EDGE_RESPONSE",
    domainNorm=#authority<=253 and authority or nil,shortUri=short_uri,requestId=request_id,traceId=request_id
  })
  local key=decision_id
  local size=body and #body+#key or 0
  local reason
  if not body then reason="encoding"
  elseif size>conf.max_event_bytes then reason="event_size"
  elseif count>=conf.queue_count then reason="queue_count"
  elseif bytes+size>conf.queue_bytes then reason="queue_bytes" end
  if reason then reject(reason);publish_pending();return end
  tail=tail+1;queue[tail]={key=key,body=body,size=size,created=ngx.now(),conf=conf}
  count=count+1;bytes=bytes+size
  if not schedule() then
    -- No other reserved sender can own this new item. A partial scheduling
    -- failure with an existing sender instead leaves it queued for that sender.
    queue[tail]=nil;tail=tail-1;count=count-1;bytes=bytes-size
    reject("timer_unavailable")
  end
  publish_pending()
end
return _M
