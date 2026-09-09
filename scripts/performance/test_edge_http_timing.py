"""Execute real worker-local timing code in Lua; no services or network."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

SOURCE = Path(__file__).resolve().parents[2] / 'deploy/apisix/plugins/apisix/plugins/shortlink-http-timing.lua'
TEST = r'''
local make = assert((loadstring or load)(__SOURCE__))
local output = {}
ngx = {status=302, var={}, req={get_method=function() return "GET" end},
    say=function(...) local pieces={...}; for i,v in ipairs(pieces) do pieces[i]=tostring(v) end
        output[#output+1]=table.concat(pieces) end}
local ctx={matched_route={value={id="shortlink-redirect"}}}
local module=make()
assert(module.snapshot()==nil)
ngx.var={request_time="0.018",upstream_response_time="0.002"}
module.observe(ctx)
local data=module.snapshot()
assert(data.eligible==1 and data.request_count==1 and data.request_sum==0.018)
assert(data.upstream_count==1 and data.upstream_sum==0.002)
-- Zero is a measured duration; absent, malformed and multi-attempt are distinct.
for _, raw in ipairs({"0.000", "-", "0.001, 0.002", "0.001 : 0.002", "nan", "-1", "secret-marker"}) do
    ngx.var={request_time="0.000",upstream_response_time=raw}; module.observe(ctx)
end
ngx.var={request_time="invalid"};module.observe(ctx)
assert(data.eligible==9 and data.request_count==8 and data.request_missing==1)
assert(data.upstream_count==2 and data.upstream_missing==2 and data.upstream_multiple==2 and data.upstream_invalid==3)
local before=data.eligible
ngx.status=429;module.observe(ctx);ngx.status=302
ngx.req.get_method=function() return "HEAD" end;module.observe(ctx)
ngx.req.get_method=function() return "GET" end
module.observe({matched_route={value={id="shortlink-management"}}});module.observe({})
assert(data.eligible==before)
local total=module.aggregate();module.include(total,data);module.include(total,nil)
module.emit(total,2)
assert(output[#output]=="shortlink_edge_http_observation_complete 0")
assert(not table.concat(output):find("secret-marker",1,true))
output={};module.emit(total,1)
assert(output[#output]=="shortlink_edge_http_observation_complete 1")
-- Multiple workers sum totals but take max(duration), preserving missing coverage.
local second=make();ngx.var={request_time="0.030",upstream_response_time="0.010"};second.observe(ctx)
module.include(total,second.snapshot())
assert(total.observed==2 and total.values.eligible==10 and total.values.request_max==0.030)
assert(math.abs(total.values.request_sum-0.048)<1e-12)
local function copy(x) local r={};for k,v in pairs(x) do r[k]=v end;return r end
for _, change in ipairs({{eligible=100},{request_count=-1},{request_sum=0/0},
                        {upstream_sum=math.huge},{upstream_missing=0.5},{request_max=99}}) do
    local invalid=copy(data);for k,v in pairs(change) do invalid[k]=v end
    local aggregate=module.aggregate();module.include(aggregate,invalid)
    assert(not aggregate.valid and aggregate.observed==0)
end
print('PASS: real Lua timing, units, no-upstream, retries, bounded route, coverage and corruption')
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emit-lua', action='store_true')
    parser.add_argument('--lua-command', default='luajit -')
    args = parser.parse_args()
    source = TEST.replace('__SOURCE__', json.dumps(SOURCE.read_text(encoding='utf-8')))
    if args.emit_lua:
        print(source)
        return
    result = subprocess.run(shlex.split(args.lua_command), input=source, text=True,
                            encoding='utf-8', errors='replace', capture_output=True, timeout=30)
    print(result.stdout, end='')
    if result.returncode:
        raise RuntimeError(result.stderr[-1000:])

if __name__ == '__main__':
    main()
