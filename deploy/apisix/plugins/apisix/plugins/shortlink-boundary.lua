local core = require("apisix.core")
local _M = { version = 0.1, priority = 10000, name = "shortlink-boundary",
  schema = { type = "object", properties = { mode = { type = "string", enum = {"redirect", "management"} } }, required = {"mode"} } }
function _M.check_schema(conf) return core.schema.check(_M.schema, conf) end
local identity = { ["userid"]=true, ["realname"]=true, ["tenantid"]=true, ["accountid"]=true,
  ["authversion"]=true, ["authorization"]=true, ["x-internal-token"]=true,
  ["forwarded"]=true, ["x-real-ip"]=true, ["x-forwarded-host"]=true }
function _M.rewrite(conf, ctx)
  -- Freeze once: a later nginx phase may evaluate request_id again.
  ctx.shortlink_request_id=ctx.shortlink_request_id or ngx.var.request_id
  local raw = ngx.var.request_uri:match("^[^?]*")
  local path = ngx.var.uri
  if raw ~= path or path:find("..",1,true) or path:find("//",1,true) or path:find(";",1,true) or path:find("\\",1,true) then
    return 400, {code="INVALID_PATH"}
  end
  if conf.mode == "redirect" then
    if not path:match("^/[A-Za-z0-9]+$") or #path > 33 then return 404, {code="NOT_FOUND"} end
    local method = ngx.req.get_method()
    if method ~= "GET" and method ~= "HEAD" then return 405, {code="METHOD_NOT_ALLOWED"} end
  elseif not (path:sub(1,25)=="/api/short-link/admin/v1/" or path=="/api/short-link/v1/user" or path:sub(1,24)=="/api/short-link/v1/user/") then
    return 404, {code="NOT_FOUND"}
  end
  local headers, header_error=ngx.req.get_headers(100)
  if header_error=="truncated" then return 400, {code="TOO_MANY_HEADERS"} end
  for name, _ in pairs(headers) do
    local lower = name:lower()
    if identity[lower] or lower:sub(1,12)=="x-shortlink-" or lower:sub(1,8)=="x-agent-"
       or (conf.mode=="redirect" and (lower=="username" or lower=="token")) then ngx.req.clear_header(name) end
  end
  -- APISIX is the internet-facing trust boundary. Inbound forwarding claims never survive.
  ngx.req.set_header("X-Forwarded-For", ngx.var.remote_addr)
  ngx.req.set_header("X-Forwarded-Proto", ngx.var.scheme)
  ngx.req.set_header("X-Request-ID", ctx.shortlink_request_id)
end
function _M.header_filter(conf, ctx)
  -- The value was frozen before rewrite could reject the request. Never echo
  -- the caller's or upstream's supplied correlation header. Upstream variables
  -- remain available to the internal access log after hiding this response field.
  ngx.header["X-Request-ID"] = ctx.shortlink_request_id
  ngx.header["X-Shortlink-Handler-Nanos"] = nil
end
return _M
