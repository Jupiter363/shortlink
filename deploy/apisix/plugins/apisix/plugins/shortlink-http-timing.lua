-- Optional worker-local diagnostics for the public GET/302 route. No request
-- identifiers or additional shared-dictionary operations on the request path.
local _M = {}
local values
local kinds = {"eligible", "request_missing", "upstream_missing", "upstream_multiple", "upstream_invalid"}
local phases = {"request", "upstream"}
local function finite(value)
    return type(value) == "number" and value >= 0 and value < math.huge
end
local function empty()
    return {eligible=0, request_missing=0, upstream_missing=0, upstream_multiple=0,
            upstream_invalid=0, request_count=0, request_sum=0, request_max=0,
            upstream_count=0, upstream_sum=0, upstream_max=0}
end
local function seconds(raw)
    if type(raw) ~= "string" or #raw > 24 then return nil end
    if not raw:match("^%d+%.?%d*$") then return nil end
    local value = tonumber(raw)
    if finite(value) then return value end
end
local function add(phase, value)
    values[phase.."_count"] = values[phase.."_count"] + 1
    values[phase.."_sum"] = values[phase.."_sum"] + value
    values[phase.."_max"] = math.max(values[phase.."_max"], value)
end
function _M.observe(ctx)
    values = values or empty()
    local route = ctx and ctx.matched_route and ctx.matched_route.value
    if not route or route.id ~= "shortlink-redirect" or ngx.status ~= 302
            or ngx.req.get_method() ~= "GET" then return end
    values.eligible = values.eligible + 1
    local request = seconds(ngx.var.request_time)
    if request then add("request", request)
    else values.request_missing = values.request_missing + 1 end
    -- Nginx encodes retries/internal redirect groups using ',' and ':'. They
    -- are counted explicitly, never mistaken for a single upstream duration.
    local raw = ngx.var.upstream_response_time
    if raw == nil or raw == "" or raw == "-" then
        values.upstream_missing = values.upstream_missing + 1
    elseif type(raw) == "string" and (raw:find(",", 1, true) or raw:find(":", 1, true)) then
        values.upstream_multiple = values.upstream_multiple + 1
    else
        local upstream = seconds(raw)
        if upstream then add("upstream", upstream)
        else values.upstream_invalid = values.upstream_invalid + 1 end
    end
end
function _M.snapshot()
    return values -- Encoded synchronously with the worker's existing ledger.
end
function _M.aggregate()
    return {values=empty(), observed=0, valid=true}
end
function _M.include(total, record)
    if record == nil then return end -- Disabled/older worker: coverage remains explicit.
    if type(record) ~= "table" then total.valid=false; return end
    for key in pairs(total.values) do
        if not finite(record[key]) then total.valid=false; return end
        if (key:sub(-6) == "_count" or not key:find("_"))
                and (record[key] % 1 ~= 0 or record[key] > 9007199254740991) then
            total.valid=false; return
        end
    end
    for _, kind in ipairs(kinds) do
        if record[kind] % 1 ~= 0 or record[kind] > 9007199254740991 then total.valid=false; return end
    end
    for _, phase in ipairs(phases) do
        local count, sum, maximum = record[phase.."_count"], record[phase.."_sum"], record[phase.."_max"]
        if sum < maximum or (count == 0 and (sum ~= 0 or maximum ~= 0)) then total.valid=false; return end
    end
    if record.eligible ~= record.request_count + record.request_missing
            or record.eligible ~= record.upstream_count + record.upstream_missing
                + record.upstream_multiple + record.upstream_invalid then total.valid=false; return end
    total.observed = total.observed + 1
    for key, value in pairs(record) do
        if total.values[key] ~= nil then
            if key:sub(-4) == "_max" then total.values[key] = math.max(total.values[key], value)
            else total.values[key] = total.values[key] + value end
        end
    end
end
function _M.emit(total, retained)
    for _, phase in ipairs(phases) do
        for _, statistic in ipairs({"count", "sum", "max"}) do
            ngx.say('shortlink_edge_http_seconds_', statistic, '{phase="', phase, '"} ', total.values[phase.."_"..statistic])
        end
    end
    for _, kind in ipairs(kinds) do
        ngx.say('shortlink_edge_http_samples_total{kind="', kind, '"} ', total.values[kind])
    end
    ngx.say("shortlink_edge_http_observed_worker_generations ", total.observed)
    ngx.say("shortlink_edge_http_observation_complete ",
            total.valid and retained > 0 and total.observed == retained and 1 or 0)
end
return _M
