"""Fault-test the real batch adapter with actual Kafka request encoding and mock sockets.

No HTTP, Kafka, Docker, service or performance load is started by this script.
--lua-command executes ONLY the explicitly supplied interpreter command, without a shell.
By default LuaJIT requires the actual codecs installed in the APISIX runtime.
--codec-root can instead embed explicitly supplied request.lua/errors.lua with SHA256.
Static checks are explicitly not evidence that the Lua behavior cases have passed.
"""
import argparse
import hashlib
import json
import pathlib
import re
import shlex
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "deploy/apisix/plugins/apisix/plugins/shortlink-kafka-batch.lua"

HARNESS = r'''
local adapter_source = __ADAPTER_SOURCE__
local request_source = __REQUEST_SOURCE__
local errors_source = __ERRORS_SOURCE__
-- The pinned APISIX image installs its Lua modules here. Existing interpreter
-- paths remain available; no local .work export or network fetch is required.
package.path="/usr/local/apisix/deps/share/lua/5.1/?.lua;/usr/local/apisix/deps/share/lua/5.1/?/init.lua;"..package.path
local load_lua = loadstring or load
local S, assertions, cases = nil, 0, {}
local TOPIC = "shortlink.gateway.request.v1"
local function eq(a,b,label)
  assertions=assertions+1
  assert(a==b,(label or "mismatch")..": "..tostring(a).." ~= "..tostring(b))
end
local function ok(value,label) assertions=assertions+1;assert(value,label or "assertion failed") end
local function case(id,name,fn) cases[#cases+1]={id=id,name=name,fn=fn} end
local function i8(n) return string.char(n%256) end
local function i16(n) return string.char(math.floor(n/256)%256,n%256) end
local function i32(n)
  return string.char(math.floor(n/16777216)%256,math.floor(n/65536)%256,math.floor(n/256)%256,n%256)
end
local function i64(n) return n<0 and string.rep(string.char(255),8) or i32(0)..i32(n) end
local function str(s) return s==nil and i16(-1) or i16(#s)..s end
local function blob(s) return i32(#s)..s end
local function reader(bytes)
  local r={s=bytes,p=1}
  function r:take(n)
    assert(n>=0 and self.p+n-1<=#self.s,"test request frame is truncated")
    local v=self.s:sub(self.p,self.p+n-1);self.p=self.p+n;return v
  end
  function r:n16() local a,b=self:take(2):byte(1,2);return a*256+b end
  function r:n32() local a,b,c,d=self:take(4):byte(1,4);return ((a*256+b)*256+c)*256+d end
  function r:string() local n=self:n16();if n==65535 then return nil end;return self:take(n) end
  return r
end
local function parse_request(wire)
  if type(wire)=="table" then wire=table.concat(wire) end
  local r=reader(wire);eq(r:n32(),#wire-4,"request length")
  local q={wire=wire,api=r:n16(),version=r:n16(),correlation=r:n32()}
  q.client=r:string();q.payload=wire:sub(r.p)
  if q.api==0 then
    q.acks=r:n16();q.timeout=r:n32();q.topics={};q.partitions={};q.bodies={}
    local nt=r:n32()
    for _=1,nt do
      local name=r:string();q.topics[#q.topics+1]=name
      local np=r:n32()
      for _=1,np do
        local pid=r:n32();q.partitions[#q.partitions+1]=pid
        local ms=reader(r:take(r:n32()))
        while ms.p<=#ms.s do
          ms:take(8);local message=reader(ms:take(ms:n32()))
          message:take(4);local magic=message:take(1):byte();message:take(1)
          if magic==1 then message:take(8) end
          local key=message:take(message:n32());local body=message:take(message:n32())
          q.bodies[#q.bodies+1]={partition=pid,key=key,body=body}
          eq(message.p,#message.s+1,"message body fully encoded")
        end
      end
    end
    eq(r.p,#wire+1,"produce frame fully consumed")
  end
  return q
end
local function produce_reply(q,overrides)
  local o=overrides or {};local rows={}
  for _,pid in ipairs(q.partitions) do rows[#rows+1]={pid=pid,error=(o.errors or {})[pid] or 0} end
  if o.missing then table.remove(rows) end
  if o.duplicate then rows[#rows+1]=rows[1] end
  if o.foreign_partition then rows[1]={pid=999,error=0} end
  local a={i32(q.correlation+(o.bad_correlation and 1 or 0)),i32(o.negative_topics and -1 or 1),str(o.foreign_topic and "other.topic" or TOPIC),i32(o.negative_partitions and -1 or #rows)}
  for _,row in ipairs(rows) do
    a[#a+1]=i32(row.pid)..i16(row.error)..i64(o.invalid_offset and -1 or (row.error==0 and 42 or -1))
  end
  a[#a+1]=i32(0) -- Produce v1 throttle_time_ms is mandatory.
  local result=table.concat(a)
  if o.truncate then result=result:sub(1,-3) end
  if o.trailing then result=result.."unexpected" end
  return result
end
local function metadata_reply(q)
  local out={i32(q.correlation),i32(#S.nodes)}
  for _,node in ipairs(S.nodes) do out[#out+1]=i32(node.id)..str(node.host)..i32(9092)..str(nil) end
  out[#out+1]=i32(S.nodes[1].id)..i32(1)..i16(0)..str(TOPIC)..i8(0)..i32(#S.leaders)
  for index,leader in ipairs(S.leaders) do
    out[#out+1]=i16(0)..i32(index-1)..i32(leader)..i32(1)..i32(leader)..i32(1)..i32(leader)
  end
  return table.concat(out)
end
local function sasl_reply(q)
  if q.api==17 then return i32(q.correlation)..i16(0)..i32(1)..str("PLAIN") end
  return i32(q.correlation)..i16(S.auth_failure and 58 or 0)..str(nil)..blob("")..i64(0)
end
local function socket()
  local sock={closed=false,receive_calls=0}
  S.sockets[#S.sockets+1]=sock
  function sock:settimeout(value) self.timeout=value;return true end
  function sock:settimeouts(a,b,c) self.timeouts={a,b,c};return true end
  function sock:connect(host,port,options)
    self.host=host;self.port=port;self.pool=options and options.pool
    eq(options.pool_size,8,"socket pool size bound");eq(options.backlog,8,"socket backlog bound")
    S.connections[#S.connections+1]={host=host,port=port,pool=self.pool,socket=self}
    if S.connect_failure then return nil,"injected connect failure" end
    self.reused=self.pool and S.pools[self.pool] and 1 or 0
    return true
  end
  function sock:getreusedtimes() return self.reused end
  function sock:sslhandshake(_,host,verify)
    S.tls[#S.tls+1]={host=host,verify=verify,pool=self.pool}
    if S.tls_failure then return nil,"injected TLS failure" end
    return true
  end
  function sock:send(wire)
    local q=parse_request(wire);q.host=self.host;q.pool=self.pool;S.requests[#S.requests+1]=q
    if q.api==0 then S.produces[#S.produces+1]=q end
    if q.api==3 then S.metadata=S.metadata+1 end
    if q.api==17 or q.api==36 then S.auth[#S.auth+1]=q end
    local action=S.reply_hook and S.reply_hook(q,self)
    if action and action.throw then error("injected socket exception") end
    if action and action.send_failure then return nil,"injected send failure" end
    if action and action.timeout then self.receive_failure=true end
    local payload=action and action.body
    if not payload then
      if q.api==0 then payload=produce_reply(q)
      elseif q.api==3 then payload=metadata_reply(q)
      elseif q.api==17 or q.api==36 then payload=sasl_reply(q)
      else error("unexpected Kafka request API") end
    end
    self.buffer=i32(action and action.frame_length or #payload)..payload
    return #q.wire
  end
  function sock:receive(n)
    self.receive_calls=self.receive_calls+1
    if self.receive_failure then return nil,"timeout" end
    assert(n<=1048576,"adapter attempted oversized socket receive")
    if not self.buffer or #self.buffer<n then return nil,"closed" end
    local result=self.buffer:sub(1,n);self.buffer=self.buffer:sub(n+1);return result
  end
  function sock:setkeepalive(timeout,size)
    ok(not self.closed,"closed socket returned to keepalive")
    S.keepalives=S.keepalives+1;self.kept=true
    if self.pool then S.pools[self.pool]=true end
    return true
  end
  function sock:close() self.closed=true;S.closes=S.closes+1;return true end
  return sock
end
local function reset()
  S={now=1700000000,nodes={{id=1,host="broker-a"}},leaders={1,1},requests={},produces={},metadata=0,sockets={},connections={},pools={},tls={},auth={},keepalives=0,closes=0,timer_calls=0}
  local crc=function(value)
      local p=value:match("^partition%-(%d+)$");return p and tonumber(p) or #value
    end
  ngx={now=function() return S.now end,crc32_long=crc,crc32_short=crc,
    socket={tcp=socket},worker={pid=function() return 101 end,exiting=function() return false end},
    timer={at=function() S.timer_calls=S.timer_calls+1;error("batch adapter must not schedule asynchronous callbacks") end},
    log=function() end,ERR=3,WARN=4,DEBUG=8}
  package.loaded["resty.kafka.request"]=nil;package.loaded["resty.kafka.errors"]=nil
  package.preload["resty.kafka.request"]=request_source and function() return assert(load_lua(request_source))() end or nil
  package.preload["resty.kafka.errors"]=errors_source and function() return assert(load_lua(errors_source))() end or nil
  package.preload["resty.kafka.client"]=function()
    return {new=function(_,brokers,options) return {client_id="unit-client",broker_list=brokers,config=options,brokers={},topics={}} end}
  end
  package.preload["resty.string"]=function() return {to_hex=function(value)
    return (value:gsub(".",function(c)return string.format("%02x",c:byte())end)) end} end
  package.preload["resty.sha256"]=function()
    return {new=function() local text="";return {
      update=function(_,value) text=text..value;return true end,
      final=function() -- Deterministic identity-only mock, not a SHA implementation.
        local h=17;for i=1,#text do h=(h*131+text:byte(i))%2147483647 end
        return i32(h)..i32(#text)..string.rep("H",24)
      end} end}
  end
  return assert(load_lua(adapter_source,"@shortlink-kafka-batch.lua"))()
end
local function producer(module,options,user,password)
  local brokers={{host="bootstrap",port=9092}}
  if user then brokers[1].sasl_config={mechanism="PLAIN",user=user,password=password} end
  return assert(module:new(brokers,options or {ssl=false,ssl_verify=true,request_timeout=1000,socket_timeout=1500}))
end
local function item(partition,body)
  local key="partition-"..partition;body=body or ("event-"..partition)
  return {key=key,body=body,size=#key+#body,created=S.now}
end
local function send(p,items) local result=p:send_batch(TOPIC,items);eq(S.timer_calls,0,"no asynchronous callback");return result end
local function all_unacked(items) for _,x in ipairs(items) do ok(not x.acked,"malformed/unknown response cannot acknowledge item") end end
case("KB01","real request codec emits one synchronous Produce with two partitions",function()
  local p=producer(reset());local items={item(0),item(1)}
  eq(send(p,items),true);eq(#S.produces,1);eq(S.produces[1].acks,65535,"acks=-1")
  eq(S.produces[1].version,1);eq(S.produces[1].timeout,1000);eq(#S.produces[1].bodies,2)
  ok(items[1].acked and items[2].acked);eq(p.stats.produce_requests,1);eq(p.stats.produce_records,2)
end)
case("KB02","partial partition ACK retains success and retries only the other partition",function()
  local p=producer(reset());local items={item(0),item(1)}
  S.reply_hook=function(q) if q.api==0 and #S.produces==1 then return {body=produce_reply(q,{errors={[1]=6}})} end end
  eq(send(p,items),false);ok(items[1].acked);ok(not items[2].acked and not items[2].terminal)
  eq(send(p,items),true);eq(#S.produces,2);eq(#S.produces[2].partitions,1);eq(S.produces[2].partitions[1],1)
end)
case("KB03","later broker transport failure cannot resend earlier broker ACK",function()
  local module=reset();S.nodes[2]={id=2,host="broker-b"};S.leaders={1,2}
  local p=producer(module);local items={item(0),item(1)};local failed=false
  S.reply_hook=function(q) if q.api==0 and q.host=="broker-b" and not failed then failed=true;return {timeout=true} end end
  eq(send(p,items),false);ok(items[1].acked);ok(not items[2].acked)
  eq(send(p,items),true);local first=0;for _,q in ipairs(S.produces) do if q.host=="broker-a" then first=first+1 end end
  eq(first,1);ok(S.closes>0,"unknown transport closes socket")
end)
local malformed={
  {"KB04","correlation mismatch",{bad_correlation=true}},
  {"KB05","missing partition",{missing=true}},
  {"KB06","duplicate partition",{duplicate=true}},
  {"KB07","foreign topic",{foreign_topic=true}},
  {"KB08","truncated throttle field",{truncate=true}},
  {"KB09","negative topic count",{negative_topics=true}},
  {"KB10","negative partition count",{negative_partitions=true}},
  {"KB11","success with negative offset",{invalid_offset=true}},
  {"KB12","foreign partition",{foreign_partition=true}},
  {"KB13","unexpected trailing response bytes",{trailing=true}},
}
for _,spec in ipairs(malformed) do
  local id,name,mutation=spec[1],spec[2],spec[3]
  case(id,name.." does not apply partial ACK and closes",function()
    local p=producer(reset());local items={item(0),item(1)};local bad_socket
    S.reply_hook=function(q,sock) if q.api==0 then bad_socket=sock;return {body=produce_reply(q,mutation)} end end
    eq(send(p,items),false);all_unacked(items);ok(bad_socket.closed,"invalid response socket closed");ok(not bad_socket.kept,"invalid response not pooled")
  end)
end
case("KB14","response frame above one MiB is rejected before body read",function()
  local p=producer(reset());local items={item(0)};local sock
  S.reply_hook=function(q,s) if q.api==0 then sock=s;return {frame_length=1048577,body=""} end end
  eq(send(p,items),false);all_unacked(items);ok(sock.closed);eq(sock.receive_calls,1)
end)
case("KB15","negative response frame length cannot request an unbounded body",function()
  local p=producer(reset());local items={item(0)};local sock
  S.reply_hook=function(q,s) if q.api==0 then sock=s;return {frame_length=-1,body=""} end end
  eq(send(p,items),false);all_unacked(items);ok(sock.closed);eq(sock.receive_calls,1)
end)
case("KB16","nonretryable Kafka error terminates only its partition",function()
  local p=producer(reset());local items={item(0),item(1)}
  S.reply_hook=function(q) if q.api==0 then return {body=produce_reply(q,{errors={[1]=10}})} end end
  eq(send(p,items),false);ok(items[1].acked);ok(items[2].terminal and not items[2].acked)
  eq(items[2].error,"kafka_nonretryable");local before=#S.produces
  eq(send(p,items),false);eq(#S.produces,before,"terminal partition not retried")
end)
case("KB17","retryable leader error refreshes metadata without losing partition identity",function()
  local module=reset();S.nodes[2]={id=2,host="broker-b"};local p=producer(module);local items={item(1)}
  S.reply_hook=function(q) if q.api==0 and #S.produces==1 then S.leaders={1,2};return {body=produce_reply(q,{errors={[1]=6}})} end end
  eq(send(p,items),false);ok(not items[1].terminal);local n=S.metadata
  eq(send(p,items),true);ok(S.metadata>n);eq(S.produces[2].host,"broker-b");eq(S.produces[2].partitions[1],1)
end)
case("KB18","ambiguous send exception is retryable and preserves original body",function()
  local p=producer(reset());local items={item(0,"immutable-original-event")}
  S.reply_hook=function(q) if q.api==0 and #S.produces==1 then return {throw=true} end end
  eq(send(p,items),false);all_unacked(items);ok(not items[1].terminal);ok(S.closes>0)
  eq(send(p,items),true);eq(S.produces[1].bodies[1].body,S.produces[2].bodies[1].body)
end)
case("KB19","TLS and SASL run once per pooled connection and never mix credentials",function()
  local module=reset();local options={ssl=true,ssl_verify=true,request_timeout=1000,socket_timeout=1500}
  local p=producer(module,options,"test-user","test-password-A")
  eq(send(p,{item(0)}),true);local auth=#S.auth;local tls=#S.tls;local pools={}
  for _,c in ipairs(S.connections) do ok(c.pool and not c.pool:find("test-password",1,true),"pool identity excludes plaintext credentials");pools[c.pool]=true end
  eq(send(p,{item(1)}),true);eq(#S.auth,auth,"pooled socket must not repeat SASL");eq(#S.tls,tls,"pooled socket must not repeat TLS")
  local p2=producer(module,options,"test-user","test-password-B");local at=#S.connections
  eq(send(p2,{item(0)}),true);ok(#S.auth>auth)
  for i=at+1,#S.connections do ok(not pools[S.connections[i].pool],"different credentials must not share pool") end
end)
case("KB20","TLS verification failure closes without Produce or ACK",function()
  local p=producer(reset(),{ssl=true,ssl_verify=true,request_timeout=1000,socket_timeout=1500});S.tls_failure=true
  local items={item(0)};eq(send(p,items),false);all_unacked(items);eq(#S.produces,0);ok(S.closes>0)
  eq(S.tls[1].verify,true)
end)
case("KB21","SASL authentication failure cannot send Produce or reuse failed socket",function()
  local p=producer(reset(),nil,"test-user","test-password");S.auth_failure=true
  local items={item(0)};eq(send(p,items),false);all_unacked(items);eq(#S.produces,0);ok(S.closes>0)
  for _,sock in ipairs(S.sockets) do ok(not sock.kept,"unauthenticated connection not pooled") end
end)
case("KB22","TLS and plaintext producer configurations have isolated pools",function()
  local module=reset();local p=producer(module);eq(send(p,{item(0)}),true);local pools={}
  for _,c in ipairs(S.connections) do pools[c.pool]=true end
  local at=#S.connections;local secure=producer(module,{ssl=true,ssl_verify=true,request_timeout=1000,socket_timeout=1500})
  eq(send(secure,{item(0)}),true)
  for i=at+1,#S.connections do ok(not pools[S.connections[i].pool],"TLS must not reuse plaintext socket") end
end)
case("KB23","expired item is never sent",function()
  local p=producer(reset());local items={item(0)};items[1].created=S.now-30
  eq(send(p,items),false);all_unacked(items);eq(#S.produces,0)
end)
case("KB24","metadata delay crossing maximum age prevents Produce",function()
  local p=producer(reset());local items={item(0)}
  S.reply_hook=function(q) if q.api==3 then S.now=S.now+31 end end
  eq(send(p,items),false);all_unacked(items);eq(#S.produces,0)
end)
case("KB25","count above 128 is rejected without partial Produce",function()
  local p=producer(reset());local items={};for i=1,129 do items[i]=item(i%2) end
  eq(send(p,items),false);all_unacked(items);eq(#S.produces,0)
end)
case("KB26","actual key plus body bytes above one MiB cannot hide in small size field",function()
  local p=producer(reset());local items={item(0,string.rep("X",1048576))};items[1].size=1
  eq(send(p,items),false);all_unacked(items);eq(#S.produces,0)
end)
case("KB27","count limit 128 is usable and acknowledgements are synchronous",function()
  local p=producer(reset());local items={};for i=1,128 do items[i]=item(i%2,"event-"..i) end
  eq(send(p,items),true);eq(#S.produces,1);eq(#S.produces[1].bodies,128)
  for _,x in ipairs(items) do ok(x.acked) end
end)
case("KB28","already ACKed items are never encoded or counted twice",function()
  local p=producer(reset());local items={item(0)};eq(send(p,items),true);local n=#S.produces;local records=p.stats.produce_records
  eq(send(p,items),true);eq(#S.produces,n);eq(p.stats.produce_records,records)
end)
case("KB29","metadata malformed correlation cannot publish or pool socket",function()
  local p=producer(reset());local items={item(0)};local bad
  S.reply_hook=function(q,sock) if q.api==3 then bad=sock;local bytes=metadata_reply(q);return {body=i32(q.correlation+1)..bytes:sub(5)} end end
  eq(send(p,items),false);all_unacked(items);eq(#S.produces,0);ok(bad.closed);ok(not bad.kept)
end)
case("KB30","one invocation has bounded bootstrap attempt and rotates on the next invocation",function()
  local module=reset();local p=assert(module:new({{host="bootstrap-a",port=9092},{host="bootstrap-b",port=9092}},{ssl=false,request_timeout=1000,socket_timeout=1500}))
  local items={item(0)};S.connect_failure=true;eq(send(p,items),false);eq(#S.connections,1)
  local first=S.connections[1].host;S.connect_failure=false;eq(send(p,items),true)
  ok(S.connections[2].host~=first,"next invocation rotates bootstrap")
end)
local failures=0
for _,c in ipairs(cases) do
  local success,err=pcall(c.fn)
  if success then print("PASS "..c.id.." "..c.name)
  else failures=failures+1;print("FAIL "..c.id.." "..c.name..": "..tostring(err)) end
end
print("CASES "..#cases.." FAILURES "..failures.." ASSERTIONS "..assertions)
if failures>0 then os.exit(1) end
'''


def lua_literal(text):
    for width in range(4, 20):
        marks = "=" * width
        if "]" + marks + "]" not in text:
            return "[" + marks + "[" + text + "]" + marks + "]"
    raise ValueError("Cannot quote input source")


def load_program(request_codec=None, errors_codec=None):
    if (request_codec is None) != (errors_codec is None):
        raise ValueError("Both explicit codec sources must be supplied together")
    paths = {"adapter": ADAPTER}
    if request_codec is not None:
        paths.update(requestCodec=request_codec, errorCodes=errors_codec)
    sources = {key: path.read_bytes() for key, path in paths.items()}
    hashes = {key: hashlib.sha256(value).hexdigest() for key, value in sources.items()}
    text = {key: value.decode("utf-8") for key, value in sources.items()}
    source = text["adapter"]
    assert re.search(r"function\s+\w+[.:]send_batch\s*\(", source), "Missing real adapter send_batch API"
    assert "ngx.socket.tcp" in source or "ngx.socket" in source, "Missing real socket transport"
    assert "message_set" in source, "Adapter must call actual request message_set encoder"
    if request_codec is not None:
        assert "function _M.message_set" in text["requestCodec"], "Not the installed request codec"
    program = HARNESS
    for token, key in [("__ADAPTER_SOURCE__", "adapter"), ("__REQUEST_SOURCE__", "requestCodec"), ("__ERRORS_SOURCE__", "errorCodes")]:
        program = program.replace(token, lua_literal(text[key]) if key in text else "nil")
    header = "-- Tested input SHA256: " + json.dumps(hashes, sort_keys=True) + "\n"
    return header + program, hashes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--static-only", action="store_true")
    mode.add_argument("--emit-lua", action="store_true")
    mode.add_argument("--lua-command", help="Existing LuaJIT stdin interpreter command, no shell")
    parser.add_argument("--codec-root", type=pathlib.Path, help="Explicit installed codec source directory to embed and hash; default requires runtime-installed codecs")
    parser.add_argument("--request-codec", type=pathlib.Path, help="Explicit request.lua; requires --errors-codec")
    parser.add_argument("--errors-codec", type=pathlib.Path, help="Explicit errors.lua; requires --request-codec")
    args = parser.parse_args(argv)
    if args.codec_root is not None:
        if args.request_codec is not None or args.errors_codec is not None:
            parser.error("--codec-root cannot be combined with individual codec options")
        args.request_codec = args.codec_root / "request.lua"
        args.errors_codec = args.codec_root / "errors.lua"
    codec_mode = "embedded-explicit-source" if args.request_codec is not None else "runtime-installed"
    try:
        program, hashes = load_program(args.request_codec, args.errors_codec)
    except (OSError, ValueError, AssertionError) as error:
        print(json.dumps({"suite": "edge_kafka_batch", "passed": False, "lua_executed": False, "error": str(error)}))
        return 2
    if args.emit_lua:
        sys.stdout.write(program)
        return 0
    print(json.dumps({"suite": "edge_kafka_batch_static", "passed": True, "input_sha256": hashes, "codec_mode": codec_mode, "runtime_codecs_checked": False, "behavior_cases": 30}))
    if args.static_only:
        print(json.dumps({"lua_executed": False, "reason": "Static-only checks do not execute fault cases"}))
        return 0
    command = shlex.split(args.lua_command or "luajit -")
    if not command:
        parser.error("--lua-command must not be empty")
    try:
        result = subprocess.run(command, input=program, text=True, encoding="utf-8", capture_output=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"suite": "edge_kafka_batch_lua", "passed": False, "lua_executed": False, "input_sha256": hashes, "error": str(error)}))
        return 2
    lines = result.stdout.splitlines()
    passed = result.returncode == 0 and any(re.fullmatch(r"CASES 30 FAILURES 0 ASSERTIONS \d+", line) for line in lines)
    print(json.dumps({"suite": "edge_kafka_batch_lua", "passed": passed, "lua_executed": True, "input_sha256": hashes, "codec_mode": codec_mode, "runtime_codec_hashes": "Bind pinned image ID and installed codec hashes in the outer execution evidence" if codec_mode == "runtime-installed" else None, "behavior_cases": 30, "output": lines, "stderr": result.stderr}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
