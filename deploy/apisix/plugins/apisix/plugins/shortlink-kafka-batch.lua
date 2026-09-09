-- Private synchronous batch adapter; no global resty.kafka module is replaced.
-- Protocol: https://kafka.apache.org/39/design/protocol/
-- Uses lua-resty-kafka v0.20 client configuration and request/MessageSet encoder.
-- Encoding/metadata layout informed by that project's producer/client/broker.
-- Upstream notice: https://github.com/doujiang24/lua-resty-kafka/tree/v0.20
-- Copyright (C) 2014-2020, by Dejiang Zhu (doujiang24) <doujiang24@gmail.com>.
-- All rights reserved.
-- Redistribution and use in source and binary forms, with or without
-- modification, are permitted provided that the following conditions are met:
-- * Redistributions of source code must retain the above copyright notice,
--   this list of conditions and the following disclaimer.
-- * Redistributions in binary form must reproduce the above copyright notice,
--   this list of conditions and the following disclaimer in the documentation
--   and/or other materials provided with the distribution.
-- THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
-- AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
-- IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
-- ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
-- LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
-- CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
-- SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
-- INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
-- CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
-- ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
-- POSSIBILITY OF SUCH DAMAGE.

local client = require("resty.kafka.client")
local request = require("resty.kafka.request")
local errors = require("resty.kafka.errors")
local sha256 = require("resty.sha256")
local hex = require("resty.string").to_hex
local ffi = require("ffi")

local _M = { _VERSION = "1.0" }
local mt = { __index = _M }
local MAX_ITEMS, MAX_BYTES = 128, 1048576
local MAX_REQUEST, MAX_RESPONSE = MAX_BYTES + 16384, 1048576
local MAX_BROKERS, MAX_PARTITIONS = 64, 4096
local AGE_SECONDS = 30
local execution
-- One adapter is owned by one sender slot. Optional phase state never crosses
-- a send_batch invocation; phase completion also runs on exceptional paths.
local function phase_done(self, outcome)
    if self.exec_record and self.exec_phase then
        execution.finish(self.exec_record,self.exec_phase,self.exec_started,outcome or "ok")
        self.exec_phase,self.exec_started=nil,nil
    end
end
local function phase(self, name)
    if self.exec_record then
        phase_done(self,"ok")
        self.exec_phase=name;self.exec_started=execution.clock(self.exec_record)
    end
end

local function fail(kind, terminal)
    error({kind=kind, terminal=terminal == true}, 0)
end

local function integer(value, low, high)
    return type(value) == "number" and value == math.floor(value)
        and value >= low and value <= high
end

-- All string/array bounds are checked before slicing, allocation or iteration.
local reader_mt = {}
reader_mt.__index = reader_mt
local function reader(data)
    return setmetatable({data=data, pos=1}, reader_mt)
end
function reader_mt:take(length)
    if not integer(length, 0, #self.data - self.pos + 1) then fail("invalid_response") end
    local pos = self.pos
    self.pos = pos + length
    return self.data:sub(pos, self.pos - 1)
end
function reader_mt:u8() return self:take(1):byte() end
function reader_mt:i16()
    local a,b = self:take(2):byte(1,2)
    local n = a * 256 + b
    return n >= 32768 and n - 65536 or n
end
function reader_mt:i32()
    local a,b,c,d = self:take(4):byte(1,4)
    local n = ((a * 256 + b) * 256 + c) * 256 + d
    return n >= 2147483648 and n - 4294967296 or n
end
function reader_mt:i64()
    local data = self:take(8)
    local value = ffi.new("int64_t", data:byte(1) >= 128 and -1 or 0)
    for i=1,8 do value = value * 256 + data:byte(i) end
    return value
end
function reader_mt:string(nullable, maximum)
    local length = self:i16()
    if length == -1 and nullable then return nil end
    if not integer(length, 0, maximum or 32767) then fail("invalid_response") end
    return self:take(length)
end
function reader_mt:bytes(maximum)
    local length = self:i32()
    if not integer(length, 0, maximum) then fail("invalid_response") end
    return self:take(length)
end
function reader_mt:count(maximum, minimum_bytes)
    local count = self:i32()
    if not integer(count, 0, maximum) or count * minimum_bytes > #self.data - self.pos + 1 then
        fail("invalid_response")
    end
    return count
end
function reader_mt:done()
    if self.pos ~= #self.data + 1 then fail("invalid_response") end
end

local function close(sock)
    if sock then pcall(sock.close, sock) end
end
local function remaining(sock, deadline, timeout)
    local millis = math.floor((deadline - ngx.now()) * 1000)
    if millis < 1 then fail("expired") end
    sock:settimeout(math.min(timeout, millis))
end
local function next_request(self, api)
    self.correlation = (self.correlation + 1) % 1073741824
    return request:new(api, self.correlation, self.client.client_id, 1), self.correlation
end

local function pool_identity(self, conf)
    if conf._pool_id then return conf._pool_id end
    local auth = conf.sasl_config or {}
    local hash = sha256:new()
    if not hash then fail("configuration") end
    for _,value in ipairs({conf.host, tostring(conf.port), tostring(self.ssl), tostring(self.ssl_verify),
                           auth.mechanism or "", auth.user or "", auth.password or ""}) do
        hash:update(tostring(#value) .. ":" .. value)
    end
    -- Never emit this digest or its input as a label/log. Different credentials
    -- and TLS verification modes cannot reuse an authenticated old connection.
    conf._pool_id = "shortlink-kafka-batch:" .. hex(hash:final())
    return conf._pool_id
end

local function roundtrip(self, sock, req, correlation, deadline, decoder, counter, records)
    if self.exec_record then phase(self,"encode") end
    if req.len + 4 > MAX_REQUEST then fail("request_too_large", true) end
    local payload = req:package()
    if self.exec_record then phase(self,"socket_send") end
    remaining(sock, deadline, self.socket_timeout)
    if counter then self.stats[counter] = self.stats[counter] + 1 end
    if records then self.stats.produce_records = self.stats.produce_records + records end
    if not sock:send(payload) then fail("transport") end
    if self.exec_record then phase(self,"ack_receive") end
    remaining(sock, deadline, self.socket_timeout)
    local header = sock:receive(4)
    if not header or #header ~= 4 then fail("transport") end
    local length = reader(header):i32()
    if not integer(length, 4, MAX_RESPONSE) then fail("invalid_response") end
    remaining(sock, deadline, self.socket_timeout)
    local body = sock:receive(length)
    if not body or #body ~= length then fail("transport") end
    if self.exec_record then phase(self,"response_decode") end
    local input = reader(body)
    if input:i32() ~= correlation then fail("invalid_response") end
    local result = decoder(input)
    input:done()
    if self.exec_record then phase_done(self,"ok") end
    return result
end

local function authenticate(self, sock, auth, deadline)
    local req, correlation = next_request(self, request.SaslHandshakeRequest)
    req:string("PLAIN")
    roundtrip(self, sock, req, correlation, deadline, function(input)
        local code, found = input:i16(), false
        local count = input:count(64, 2)
        for _=1,count do if input:string(false,256) == "PLAIN" then found = true end end
        if code ~= 0 or not found then fail("authentication", true) end
        return true
    end)
    req, correlation = next_request(self, request.SaslAuthenticateRequest)
    req:bytes("\0" .. auth.user .. "\0" .. auth.password)
    return roundtrip(self, sock, req, correlation, deadline, function(input)
        local code = input:i16()
        input:string(true,32767) -- Never log a broker-provided error message.
        input:bytes(65536)
        local lifetime = input:i64()
        if code ~= 0 then fail("authentication", true) end
        if lifetime < 0 then fail("invalid_response") end
        -- Finite SASL sessions are not pooled: a reused socket otherwise needs
        -- an absolute session expiry/re-authentication protocol of its own.
        return lifetime == 0
    end)
end

-- A valid decoded result survives a keepalive failure. Every exceptional path
-- closes its socket, including malformed ACKs, TLS/SASL errors and partial IO.
local function exchange(self, conf, req, correlation, deadline, decoder, counter, records)
    local sock, reusable
    if self.exec_record then phase(self,"connection") end
    local ok, result = pcall(function()
        sock = ngx.socket.tcp()
        if not sock then fail("transport") end
        remaining(sock, deadline, self.socket_timeout)
        if not sock:connect(conf.host, conf.port, {pool=pool_identity(self,conf), pool_size=8, backlog=8}) then
            fail("transport")
        end
        local reused = sock:getreusedtimes()
        if reused == nil then fail("transport") end
        reusable = true
        if reused == 0 then
            if self.ssl then
                remaining(sock, deadline, self.socket_timeout)
                if not sock:sslhandshake(false, conf.host, self.ssl_verify) then fail("tls") end
            end
            if conf.sasl_config then reusable = authenticate(self, sock, conf.sasl_config, deadline) end
        end
        return roundtrip(self, sock, req, correlation, deadline, decoder, counter, records)
    end)
    if not ok then
        if self.exec_record then phase_done(self,"error") end
        close(sock)
        if type(result) ~= "table" or not result.kind then result = {kind="transport",terminal=false} end
        return nil, result
    end
    if reusable then
        local kept, success = pcall(sock.setkeepalive, sock, 30000, 8)
        if not kept or not success then close(sock) end
    else close(sock) end
    return result
end

local function decode_metadata(input, topic, auth)
    local brokers = {}
    local broker_count = input:count(MAX_BROKERS, 12)
    if broker_count == 0 then fail("invalid_response") end
    for _=1,broker_count do
        local id, host, port = input:i32(), input:string(false,253), input:i32()
        input:string(true,256) -- rack
        if id < 0 or brokers[id] or #host == 0 or host:find("[%z%s]")
            or not integer(port,1,65535) then fail("invalid_response") end
        brokers[id] = {host=host,port=port,sasl_config=auth}
    end
    input:i32() -- controller id is -1 while no controller is known.
    if input:count(1,9) ~= 1 then fail("invalid_response") end
    local topic_error, name, internal = input:i16(), input:string(false,249), input:u8()
    if name ~= topic or (internal ~= 0 and internal ~= 1) then fail("invalid_response") end
    local count = input:count(MAX_PARTITIONS,18)
    local partitions = {}
    for _=1,count do
        local code, id, leader = input:i16(), input:i32(), input:i32()
        if not integer(id,0,MAX_PARTITIONS-1) or partitions[id] then fail("invalid_response") end
        for _=1,2 do
            local nodes = input:count(MAX_BROKERS,4)
            for _=1,nodes do input:i32() end
        end
        partitions[id] = {errcode=code,leader=leader}
    end
    if topic_error == 0 then
        if count == 0 then fail("invalid_response") end
        for id=0,count-1 do if not partitions[id] then fail("invalid_response") end end
    end
    return {brokers=brokers,partitions=partitions,num=count,errcode=topic_error}
end

local function refresh(self, topic, deadline)
    local seeds = self.brokers
    -- One bounded metadata attempt per outer send_batch call. The next call
    -- rotates bootstrap broker; it never hides a second internal retry loop.
    self.seed = self.seed % #seeds + 1
    local conf = seeds[self.seed]
    if self.exec_record then phase(self,"encode") end
    local req, correlation = next_request(self, request.MetadataRequest)
    req:int32(1); req:string(topic)
    local result, err = exchange(self, conf, req, correlation, deadline,
        function(input) return decode_metadata(input,topic,conf.sasl_config) end, "metadata_requests")
    if result then self.metadata = result; self.topic = topic; self.refresh_needed = false end
    return result, err
end

local function eligible(item, now)
    return not item.acked and not item.terminal and now - item.created < AGE_SECONDS
end
local function nonretryable(code)
    -- Unknown codes remain unconfirmed; no optimistic success or guessed retry
    -- semantics. The pinned table classifies known Kafka protocol errors.
    local known = errors[code]
    return code ~= 0 and known ~= nil and known.retriable == false
end
local function mark_error(items, err)
    for _,item in ipairs(items) do
        if not item.acked and not item.terminal then
            item.error = err.kind
            if err.terminal then item.terminal = true end
        end
    end
end

local function decode_produce(input, topic, expected, expected_count)
    if input:count(1,6) ~= 1 or input:string(false,249) ~= topic then fail("invalid_response") end
    if input:count(MAX_ITEMS,14) ~= expected_count then fail("invalid_response") end
    local result = {}
    for _=1,expected_count do
        local id, code, offset = input:i32(), input:i16(), input:i64()
        if not expected[id] or result[id] or (code == 0 and offset < 0) then fail("invalid_response") end
        result[id] = {code=code,offset=offset}
    end
    if input:i32() < 0 then fail("invalid_response") end -- Produce v1 throttle_time_ms
    return result
end

local function send(self, topic, items)
    if type(topic) ~= "string" or #topic < 1 or #topic > 249
        or not topic:match("^[%w%._%-]+$") or type(items) ~= "table" or #items > MAX_ITEMS then
        fail("invalid_batch",true)
    end
    local total, pending, deadline = 0, {}, math.huge
    local now = ngx.now()
    for _,item in ipairs(items) do
        if type(item) ~= "table" or type(item.key) ~= "string" or type(item.body) ~= "string"
            or type(item.created) ~= "number" or item.created ~= item.created or item.created > now
            or item.created == -math.huge then fail("invalid_batch",true) end
        total = total + #item.key + #item.body
        if total > MAX_BYTES then fail("invalid_batch",true) end
        if eligible(item,now) then
            pending[#pending+1] = item
            deadline = math.min(deadline,item.created+AGE_SECONDS)
        end
    end
    if #pending == 0 or ngx.worker.exiting() then return end
    local metadata, err = self.metadata
    if not metadata or self.topic ~= topic or self.refresh_needed then metadata,err = refresh(self,topic,deadline) end
    if not metadata then mark_error(pending,err); return end
    if metadata.errcode ~= 0 then
        self.refresh_needed = true
        mark_error(pending,{kind="metadata",terminal=nonretryable(metadata.errcode)})
        return
    end
    if self.exec_record then phase(self,"prepare") end
    local groups, order = {}, {}
    for _,item in ipairs(pending) do
        if item._kafka_topic and item._kafka_topic ~= topic then fail("invalid_batch",true) end
        local partition = item._kafka_partition
        if partition == nil then
            partition = ngx.crc32_short(item.key) % metadata.num
            item._kafka_partition, item._kafka_topic = partition, topic
        end
        local info = metadata.partitions[partition]
        local broker = info and metadata.brokers[info.leader]
        if not info or info.errcode ~= 0 or not broker then
            self.refresh_needed = true
            item.error = "metadata"
            if info and nonretryable(info.errcode) then item.terminal = true end
        else
            if not groups[info.leader] then
                groups[info.leader] = {broker=broker,items={}}
                order[#order+1] = info.leader
            end
            local group = groups[info.leader]
            group.items[#group.items+1] = item
        end
    end
    for _,id in ipairs(order) do
        if self.exec_record then phase(self,"prepare") end
        if ngx.worker.exiting() then return end
        local group = groups[id]
        local parts, partition_count, record_count, selected = {}, 0, 0, {}
        local current, limit = ngx.now(), math.huge
        for _,item in ipairs(group.items) do
            if eligible(item,current) then
                local partition = item._kafka_partition
                if not parts[partition] then parts[partition] = {}; partition_count = partition_count + 1 end
                local messages = parts[partition]
                messages[#messages+1] = item.key; messages[#messages+1] = item.body
                selected[#selected+1] = item; record_count = record_count + 1
                limit = math.min(limit,item.created+AGE_SECONDS)
            end
        end
        if record_count > 0 then
            if self.exec_record then phase(self,"encode") end
            local req, correlation = next_request(self, request.ProduceRequest)
            req:int16(-1); req:int32(self.request_timeout); req:int32(1); req:string(topic)
            req:int32(partition_count)
            for partition,messages in pairs(parts) do req:int32(partition); req:message_set(messages,#messages) end
            local result, problem = exchange(self,group.broker,req,correlation,limit,
                function(input) return decode_produce(input,topic,parts,partition_count) end,
                "produce_requests",record_count)
            if result then
                -- Complete response validation precedes ALL mutations below.
                -- No yield before the known ACKs are on the original items.
                for _,item in ipairs(selected) do
                    local status = result[item._kafka_partition]
                    if status.code == 0 then item.acked = true; item.error = nil
                    elseif nonretryable(status.code) then item.terminal = true; item.error = "kafka_nonretryable"
                    else item.error = "kafka_retryable"; self.refresh_needed = true end
                end
            else
                self.refresh_needed = true
                mark_error(selected,problem)
            end
        end
    end
end

function _M.new(_, brokers, opts)
    opts = opts or {}
    if type(opts) ~= "table" or type(brokers) ~= "table" or #brokers < 1 or #brokers > MAX_BROKERS then
        return nil,"configuration"
    end
    local copied = {}
    for _,conf in ipairs(brokers) do
        if type(conf) ~= "table" or type(conf.host) ~= "string" or #conf.host < 1 or #conf.host > 253
            or conf.host:find("[%z%s]") or not integer(conf.port,1,65535) then return nil,"configuration" end
        local auth = conf.sasl_config
        if auth then
            if type(auth) ~= "table" or auth.mechanism ~= "PLAIN" or type(auth.user) ~= "string"
                or type(auth.password) ~= "string" or #auth.user > 4096 or #auth.password > 4096
                or auth.user:find("%z") or auth.password:find("%z") then return nil,"configuration" end
            auth = {mechanism="PLAIN",user=auth.user,password=auth.password}
        end
        copied[#copied+1] = {host=conf.host,port=conf.port,sasl_config=auth}
    end
    local request_timeout, socket_timeout = opts.request_timeout or 1000, opts.socket_timeout or 1500
    if not integer(request_timeout,1,5000) or not integer(socket_timeout,1,5000)
        or (opts.ssl ~= nil and type(opts.ssl) ~= "boolean")
        or (opts.ssl_verify ~= nil and type(opts.ssl_verify) ~= "boolean") then return nil,"configuration" end
    local cli = client:new(copied,{ssl=opts.ssl==true,ssl_verify=opts.ssl_verify==true,
        socket_timeout=socket_timeout,keepalive_timeout=30000,keepalive_size=8})
    if not cli then return nil,"configuration" end
    return setmetatable({client=cli,brokers=copied,ssl=opts.ssl==true,ssl_verify=opts.ssl_verify==true,
        request_timeout=request_timeout,socket_timeout=socket_timeout,correlation=0,seed=0,
        stats={produce_requests=0,produce_records=0,metadata_requests=0}},mt)
end

function _M.send_batch(self, topic, items, diagnostics)
    if self.busy then return false,"concurrent_use" end
    self.busy = true
    local started
    if diagnostics then
        execution=execution or require("apisix.plugins.shortlink-execution-diagnostics")
        self.exec_record=diagnostics;started=execution.clock(diagnostics)
        phase(self,"prepare")
    end
    local ok, problem = pcall(send,self,topic,items)
    if self.exec_record then phase_done(self,ok and "ok" or "error") end
    self.busy = false
    local all_acked=ok
    if ok then for _,item in ipairs(items) do if not item.acked then all_acked=false;break end end end
    if diagnostics then execution.finish(diagnostics,"adapter_total",started,all_acked and "ok" or "error") end
    self.exec_record=nil
    if not ok then
        self.refresh_needed = true
        local kind = type(problem) == "table" and problem.kind or "transport"
        -- Existing ACKs on original items survive any later exception. Invalid
        -- input is not allowed to escape with a body/URL/credential in an error.
        return false,kind
    end
    return all_acked
end

return _M
