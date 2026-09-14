"""Real-LuaJIT regression tests for the canonical logger's ready_head.

Generation is offline. Production Lua is never modified or copied as a runnable
plugin. --emit-bundle writes one self-contained test program and a manifest;
--lua-command executes that same program with an existing LuaJIT, without a shell.
The historical ready_head oracle is embedded; no work-directory files are needed.
An explicitly supplied --candidate-patch is applied only in memory for exploration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/performance"))
from test_edge_sender_scheduling import program as scheduling_program
from test_edge_snapshot_consistency import SNAPSHOT_CASES
from test_edge_sender_linger import decode_output

LOGGER = ROOT / "deploy/apisix/plugins/apisix/plugins/shortlink-request-logger.lua"
RELATIVE_LOGGER = "deploy/apisix/plugins/apisix/plugins/shortlink-request-logger.lua"
HISTORICAL_ORACLE_REVISION = "main14273ab"
# Only this small function is historical (main commit 14273ab). All other code
# in both the oracle source and tested source comes from the current logger.
HISTORICAL_READY_HEAD = '''local function ready_head(now)
  local item=queue[head]
  if not item then return false end
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
  local batch_bytes,items=0,0
  local byte_limit=conf.send_batch_bytes or 65536
  for index=head,math.min(tail,head+batch_size-1) do
    local candidate=queue[index]
    -- A closed prefix cannot be enlarged by waiting for later admissions.
    if candidate.conf~=conf then return true,nil,"configuration" end
    if batch_bytes+candidate.size>byte_limit then return true,nil,"bytes" end
    batch_bytes=batch_bytes+candidate.size;items=items+1
    if items>=batch_size then return true,nil,"count" end
    if batch_bytes>=byte_limit then return true,nil,"bytes" end
  end
  return false,deadline
end
'''


def normalize(text):
    return text.replace("\r\n", "\n")


def historical_oracle_source(canonical):
    """Replace only ready_head and its worker-local cache with the old oracle."""
    marker = "local function ready_head(now)\n"
    previous = "local function send_item(slot, item)\n"
    following = "\nlocal function arm_linger(deadline)"
    if any(canonical.count(token) != 1 for token in (previous, marker, following)):
        raise ValueError("canonical ready_head section boundary ambiguous")
    ready = canonical.index(marker)
    previous_end = canonical.rfind("\nend\n", canonical.index(previous), ready)
    end = canonical.index(following)
    if previous_end < 0 or end <= ready:
        raise ValueError("canonical ready_head section boundary changed")
    begin = previous_end + len("\nend\n")
    return canonical[:begin] + HISTORICAL_READY_HEAD + canonical[end:]


def apply_in_memory(source, patch):
    """Strict unified-diff application, only to this logger's in-memory string."""
    lines = normalize(patch).splitlines(keepends=True)
    expected = ["--- a/" + RELATIVE_LOGGER + "\n", "+++ b/" + RELATIVE_LOGGER + "\n"]
    if lines[:2] != expected:
        raise ValueError("candidate patch must target only the canonical logger")
    original = normalize(source).splitlines(keepends=True)
    result, consumed, index, hunks = [], 0, 2, 0
    while index < len(lines):
        match = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@[^\n]*\n?", lines[index])
        if not match:
            raise ValueError("invalid or extra patch hunk/header")
        old_start = int(match.group(1)) - 1
        old_count = int(match.group(2) or 1)
        new_start = int(match.group(3)) - 1
        new_count = int(match.group(4) or 1)
        if old_start < consumed or old_start > len(original):
            raise ValueError("overlapping or out-of-range hunk")
        result.extend(original[consumed:old_start])
        consumed = old_start
        if new_start != len(result):
            raise ValueError("new hunk position mismatch")
        seen_old = seen_new = 0
        index += 1
        while index < len(lines) and not lines[index].startswith("@@ "):
            line = lines[index]
            if not line or line[0] not in " +-":
                raise ValueError("unsupported patch metadata")
            mode, content = line[0], line[1:]
            if mode in " -":
                if consumed >= len(original) or original[consumed] != content:
                    raise ValueError("candidate context differs from canonical source")
                consumed += 1
                seen_old += 1
            if mode in " +":
                result.append(content)
                seen_new += 1
            index += 1
        if (seen_old, seen_new) != (old_count, new_count):
            raise ValueError("hunk line counts differ")
        hunks += 1
    if not hunks:
        raise ValueError("empty candidate patch")
    result.extend(original[consumed:])
    return "".join(result)


def readiness_blocks(original, candidate):
    marker = "local function ready_head(now)\n"
    following = "\nlocal function arm_linger(deadline)"
    if original.count(marker) != 1 or original.count(following) != 1:
        raise ValueError("canonical ready_head boundary ambiguous")
    begin, end = original.index(marker), original.index(following)
    prefix, suffix = original[:begin], original[end:]
    if not candidate.startswith(prefix) or not candidate.endswith(suffix):
        raise ValueError("candidate modifies code outside ready_head/cache declarations")
    new = candidate[len(prefix):len(candidate) - len(suffix)]
    if new.count(marker) != 1:
        raise ValueError("candidate ready_head boundary ambiguous")
    return original[begin:end], new


def lua_quote(text):
    delimiter = "=========="
    if "]" + delimiter + "]" in text:
        raise ValueError("source contains Lua test delimiter")
    return "[" + delimiter + "[" + text + "]" + delimiter + "]"


DIFFERENTIAL_CASES = r'''
do
  assert(type(jit)=="table" and jit.version:find("LuaJIT",1,true),
    "this suite must execute real LuaJIT; generation is not execution")
  local old_block=__OLD_READY_BLOCK__
  local new_block=__NEW_READY_BLOCK__
  local checks=0
  local function pack(...) return {n=select("#",...),...} end
  local function probe(block)
    local needle="    local candidate=queue[index]"
    local changed,n=block:gsub(needle:gsub("(%W)","%%%1"),
      "    scan_count=scan_count+1\n"..needle)
    assert(n==1,"exactly one real prefix scan site must be instrumented")
    local code="local queue,head,tail,linger_wakeup\nlocal scan_count=0\n"..changed..[[
return {
  check=function(f)
    queue=f.queue;head=f.head;tail=f.tail;linger_wakeup=f.wakeup
    return ready_head(f.now)
  end,
  scans=function() return scan_count end
}
]]
    return assert(load_lua(code))()
  end
  local function fixture(batch,byte_limit)
    return {queue={},head=1,tail=0,now=100,wakeup=nil,serial=0,
      current={send_batch_size=batch or 32,send_batch_bytes=byte_limit or 65536,send_linger_ms=5}}
  end
  local function pair() return {old=probe(old_block),new=probe(new_block)} end
  local function append(f,size,conf)
    f.serial=f.serial+1;f.tail=f.tail+1
    f.queue[f.tail]={size=size or 1000,created=f.now,conf=conf or f.current,identity=f.serial}
  end
  local function pop_front(f,n)
    for _=1,n do
      if f.head>f.tail then break end
      f.queue[f.head]=nil;f.head=f.head+1
    end
    if f.head>f.tail then f.queue={};f.head=1;f.tail=0 end
  end
  local function pop_tail(f)
    if f.tail>=f.head then f.queue[f.tail]=nil;f.tail=f.tail-1 end
    if f.head>f.tail then f.queue={};f.head=1;f.tail=0 end
  end
  local function compare(p,f,context)
    local before={};local old_queue,old_head,old_tail=f.queue,f.head,f.tail
    for i=f.head,f.tail do
      local item=assert(f.queue[i],"fixture queue has a hole")
      before[i]={item=item,size=item.size,created=item.created,conf=item.conf,
        batch=item.conf.send_batch_size,bytes=item.conf.send_batch_bytes,linger=item.conf.send_linger_ms}
    end
    local a,b=pack(p.old.check(f)),pack(p.new.check(f));checks=checks+1
    eq(a.n,b.n,context.." return arity")
    for i=1,3 do
      if i==2 and type(a[i])=="number" and type(b[i])=="number" then
        assert(math.abs(a[i]-b[i])<1e-9,context.." deadline mismatch")
      else eq(a[i],b[i],context.." result field"..i) end
    end
    eq(f.queue,old_queue,"readiness must not replace queue");eq(f.head,old_head);eq(f.tail,old_tail)
    for i,saved in pairs(before) do
      local item=f.queue[i];eq(item,saved.item,"readiness mutated item identity")
      eq(item.size,saved.size);eq(item.created,saved.created);eq(item.conf,saved.conf)
      eq(item.conf.send_batch_size,saved.batch);eq(item.conf.send_batch_bytes,saved.bytes)
      eq(item.conf.send_linger_ms,saved.linger)
    end
    return a
  end
  local function expect(p,f,ready,reason,label)
    local value=compare(p,f,label);eq(value[1],ready,label.." expected ready")
    eq(value[3],reason,label.." expected reason");return value
  end
  case("RP01","ten growing arrivals inspect55 versus10 immutable prefix items",function()
    local p,f=pair(),fixture()
    for i=1,10 do append(f);expect(p,f,false,nil,"growing10/"..i);f.now=f.now+0.0001 end
    eq(p.old.scans(),55);eq(p.new.scans(),10)
    print("READY_PREFIX_SCAN N10 ORIGINAL55 CANDIDATE10")
  end)
  case("RP02","count32 and repeated readiness preserve count while avoiding rescans",function()
    local p,f=pair(),fixture()
    for i=1,32 do append(f,100);expect(p,f,i==32,i==32 and "count" or nil,"growing32/"..i);f.now=f.now+0.0001 end
    eq(p.old.scans(),528);eq(p.new.scans(),32)
    for i=1,8 do expect(p,f,true,"count","repeat-ready/"..i) end
    eq(p.old.scans(),784);eq(p.new.scans(),32)
    print("READY_PREFIX_SCAN N32 ORIGINAL528 CANDIDATE32 RECHECK_ORIGINAL784 CANDIDATE32")
  end)
  case("RP03","empty queue and numeric head reset cannot reuse the old item prefix",function()
    local p,f=pair(),fixture(4,16384)
    append(f,10000);expect(p,f,false,nil,"oldhead")
    pop_front(f,1);compare(p,f,"empty-all-inflight")
    append(f,8000);append(f,8384);expect(p,f,true,"bytes","fresh-head-index1")
    pop_front(f,2);append(f,1);expect(p,f,false,nil,"second-index1-reuse")
  end)
  case("RP04","advancing head excludes all inflight items and uses new creation deadline",function()
    local p,f=pair(),fixture(4,16384)
    append(f,3000);append(f,3000);compare(p,f,"oldprefix")
    pop_front(f,1);f.now=100.002;append(f,3000);compare(p,f,"advance-head")
    pop_front(f,2);compare(p,f,"only-inflight-empty")
    f.now=100.004;append(f,3000);local value=expect(p,f,false,nil,"new-tail")
    assert(math.abs(value[2]-100.009)<1e-9)
  end)
  case("RP05","tail rollback and a different item at the same index invalidate cached bytes",function()
    local p,f=pair(),fixture(8,16384)
    append(f,4096);append(f,4096);expect(p,f,false,nil,"cached-tail")
    pop_tail(f);compare(p,f,"tail-missing")
    append(f,12288);expect(p,f,true,"bytes","new-tail-exact")
    pop_tail(f);append(f,1);expect(p,f,false,nil,"tail-replaced-without-intermediate-check")
  end)
  case("RP06","excluded byte-boundary candidate is rechecked after rollback",function()
    local p,f=pair(),fixture(8,16384)
    append(f,10000);append(f,4000);expect(p,f,false,nil,"fitting-prefix")
    append(f,4000);expect(p,f,true,"bytes","excluded-tail")
    pop_tail(f);expect(p,f,false,nil,"excluded-tail-removed")
    append(f,2384);expect(p,f,true,"bytes","exact-cap")
    expect(p,f,true,"bytes","exact-cap-repeat")
  end)
  case("RP07","conf identities and rolled-back config boundaries never mix prefixes",function()
    local p,f=pair(),fixture(8,16384);local other=copy(f.current)
    append(f,1000);compare(p,f,"one")
    append(f,1000,other);expect(p,f,true,"configuration","equal-new-conf")
    pop_tail(f);append(f,1000);expect(p,f,false,nil,"boundary-rollback")
    f.queue[f.head].conf=other;expect(p,f,true,"configuration","head-conf-ref-change")
    pop_front(f,1);expect(p,f,false,nil,"new-head-original-conf")
  end)
  case("RP08","in-place batch and byte limits invalidate cached facts with original reason order",function()
    local p,f=pair(),fixture(4,32768)
    for i=1,3 do append(f,8000) end
    expect(p,f,false,nil,"three-of-four")
    f.current.send_batch_size=2;expect(p,f,true,"count","lower-count")
    f.current.send_batch_size=4;expect(p,f,false,nil,"restore-count")
    f.current.send_batch_bytes=16384;expect(p,f,true,"bytes","lower-bytes")
    f.current.send_batch_bytes=32768;expect(p,f,false,nil,"restore-bytes")
    f.current.send_batch_size=3;f.current.send_batch_bytes=24000
    expect(p,f,true,"count","count-before-exact-bytes")
    f.current.send_batch_size=4;expect(p,f,true,"bytes","bytes-with-larger-count")
  end)
  case("RP09","clock rollback and hot linger changes retain live deadline precedence",function()
    local p,f=pair(),fixture();append(f)
    f.now=100.004;expect(p,f,false,nil,"young")
    f.now=100.005;expect(p,f,true,"age","deadline")
    f.now=99.999;expect(p,f,false,nil,"clock-retreat")
    f.now=100.001;f.wakeup={deadline=100.005};f.current.send_linger_ms=3
    expect(p,f,true,"configuration","shorter-than-old-wakeup")
    f.current.send_linger_ms=10;local value=expect(p,f,false,nil,"longer-than-wakeup")
    assert(math.abs(value[2]-100.010)<1e-9)
    f.now=100.010;expect(p,f,true,"age","new-deadline")
  end)
  case("RP10","high queue indices and immediate modes cannot confuse index1 reuse",function()
    local p,f=pair(),fixture();f.head=1000;f.tail=999
    append(f);compare(p,f,"high-index")
    f.current.send_linger_ms=0;expect(p,f,true,"immediate","zero-linger")
    f.current.send_linger_ms=5;f.current.send_batch_size=1
    expect(p,f,true,"immediate","single-item-mode")
    f.current.send_batch_size=32;expect(p,f,false,nil,"restore-batch-mode")
    pop_front(f,1);append(f,7);expect(p,f,false,nil,"reset-after-high-index")
  end)
  case("RP11","rollback of a previously full counted prefix does not retain ready state",function()
    local p,f=pair(),fixture(3,65536)
    for i=1,3 do append(f,1000) end
    expect(p,f,true,"count","full")
    pop_tail(f);expect(p,f,false,nil,"rolled-back-count")
    append(f,1200);expect(p,f,true,"count","same-index-new-object")
    pop_front(f,3);compare(p,f,"all-inflight")
  end)
  case("RP12","seeded mixed operations match original results and never mutate event facts",function()
    local total_before=checks
    -- Bias toward admissions so queues also grow between mutation/drain steps.
    local actions={1,1,1,1,1,1,1,1,2,3,4,5,6,7,8,9,10,11,12,1}
    for seed=1,32 do
      local rng=seed
      local function random(n) rng=(rng*48271)%2147483647;return (rng%n)+1 end
      local p,f=pair(),fixture();local configs={f.current}
      for step=1,256 do
        local operation=actions[random(#actions)];local target=configs[random(#configs)]
        if operation==1 then
          if f.tail-f.head+1<96 then append(f,random(4096)) end
        elseif operation==2 then pop_front(f,random(4))
        elseif operation==3 then pop_tail(f)
        elseif operation==4 then target.send_batch_size=({1,2,4,8,16,32})[random(6)]
        elseif operation==5 then target.send_batch_bytes=({16384,32768,49152,65536})[random(4)]
        elseif operation==6 then target.send_linger_ms=({0,1,2,3,4,5,10})[random(7)]
        elseif operation==7 then f.now=f.now+(random(21)-7)/10000
        elseif operation==8 then
          local item=f.queue[f.head]
          f.wakeup=(item and random(2)==1) and {deadline=item.created+random(12)/1000} or nil
        elseif operation==9 then
          f.current=copy(configs[random(#configs)]);configs[#configs+1]=f.current
        elseif operation==10 then
          for extra=1,3 do compare(p,f,"seed"..seed.." step"..step.." extra"..extra) end
        elseif operation==11 then pop_front(f,96)
        elseif operation==12 and f.queue[f.head] then
          -- Head ref mutation is explicit; queued interior item refs remain immutable.
          f.queue[f.head].conf=configs[random(#configs)]
        end
        compare(p,f,"seed"..seed.." step"..step.." operation"..operation)
      end
    end
    assert(checks-total_before>=8192,"random differential coverage missing")
    print("READY_PREFIX_RANDOM SEEDS32 OPERATIONS8192 CHECKS"..(checks-total_before))
  end)
  case("RP13","negative controls prove readiness deadline and reason differences are detected",function()
    local f=fixture();append(f)
    local function rejected(check,label)
      local p={old=probe(old_block),new={check=check}}
      local ok=pcall(compare,p,f,"negative-control-"..label)
      assert(not ok,"differential oracle silently accepted "..label)
    end
    rejected(function() return true,nil,"age" end,"readiness")
    rejected(function() return false,100.500 end,"deadline")
    f.now=100.005
    rejected(function() return true,nil,"bytes" end,"reason")
    print("READY_PREFIX_NEGATIVE_CONTROLS3 REJECTED3")
  end)
end
'''


def build_program(original, candidate):
    old_block, new_block = readiness_blocks(original, candidate)
    base = scheduling_program(candidate)
    marker = "local failures=0\n"
    if base.count(marker) != 1:
        raise ValueError("canonical harness marker ambiguous")
    differential = DIFFERENTIAL_CASES.replace("__OLD_READY_BLOCK__", lua_quote(old_block))
    differential = differential.replace("__NEW_READY_BLOCK__", lua_quote(new_block))
    rendered = base.replace(marker, SNAPSHOT_CASES + "\n" + differential + "\n" + marker)
    expected = {"EM": 14, "SC": 20, "BC": 18, "LG": 28, "GS": 10, "SS": 32, "RP": 13}
    actual = {family: len(re.findall(r'case\("' + family + r'\d+"', rendered)) for family in expected}
    if actual != expected:
        raise ValueError("case-family coverage changed: " + repr(actual))
    if "__LOGGER_SOURCE__" in rendered or "__OLD_READY_BLOCK__" in rendered or "__NEW_READY_BLOCK__" in rendered:
        raise ValueError("unexpanded test placeholder")
    return rendered, expected


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-patch", type=Path,
                        help="Optional explicit ready_head patch against the current logger; applied only in memory")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--emit-bundle", type=Path, help="Create a NEW directory with suite.lua and manifest.json; do not execute Lua")
    modes.add_argument("--emit-lua", action="store_true", help="Print self-contained test program; do not execute Lua")
    modes.add_argument("--lua-command", help="Existing LuaJIT command reading stdin, e.g. 'taskset -c 12-15 luajit -'; shell=False")
    args = parser.parse_args()
    try:
        canonical_raw = LOGGER.read_bytes()
        canonical = normalize(canonical_raw.decode("utf-8-sig"))
        original = historical_oracle_source(canonical)
        patch_raw = args.candidate_patch.read_bytes() if args.candidate_patch else None
        candidate = (apply_in_memory(canonical, patch_raw.decode("utf-8-sig"))
                     if patch_raw is not None else canonical)
        rendered, families = build_program(original, candidate)
        info = {"suite": "edge_ready_prefix", "case_families": families, "case_count": sum(families.values()),
                "mode": "candidate_patch" if args.candidate_patch else "canonical",
                "canonical_source_sha256": sha(canonical_raw),
                "candidate_patch_sha256": sha(patch_raw) if patch_raw is not None else None,
                "historical_oracle_revision": HISTORICAL_ORACLE_REVISION,
                "historical_ready_head_sha256": sha(HISTORICAL_READY_HEAD.encode("utf-8")),
                "candidate_normalized_source_sha256": sha(candidate.encode("utf-8")),
                "test_program_sha256": sha(rendered.encode("utf-8")), "production_written": False,
                "random_seeds": 32, "random_operations": 8192,
                "scan_count_expectations": {"ten": [55, 10], "thirty_two": [528, 32]},
                "tests_have_not_run_until_luajit_executes": True}
        if args.emit_lua:
            sys.stdout.write(rendered)
            return 0
        if args.emit_bundle:
            args.emit_bundle.mkdir(parents=True, exist_ok=False)
            with (args.emit_bundle / "suite.lua").open("w", encoding="utf-8", newline="\n") as stream:
                stream.write(rendered)
            info.update(lua_executed=False, generated=True,
                        execute="taskset -c 12-15 luajit " + (args.emit_bundle / "suite.lua").as_posix())
            with (args.emit_bundle / "manifest.json").open("w", encoding="utf-8", newline="\n") as stream:
                json.dump(info, stream, indent=2)
                stream.write("\n")
            print(json.dumps(info))
            return 0
        process = subprocess.run(shlex.split(args.lua_command), input=rendered.encode("utf-8"),
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45, check=False)
        output, errors = decode_output(process.stdout), decode_output(process.stderr)
        expected_line = "CASES %d FAILURES 0" % info["case_count"]
        evidence = ("READY_PREFIX_SCAN N10 ORIGINAL55 CANDIDATE10",
                    "READY_PREFIX_SCAN N32 ORIGINAL528 CANDIDATE32 RECHECK_ORIGINAL784 CANDIDATE32",
                    "READY_PREFIX_NEGATIVE_CONTROLS3 REJECTED3")
        passed = process.returncode == 0 and expected_line in output.splitlines()
        passed = passed and all(item in output.splitlines() for item in evidence)
        passed = passed and any(line.startswith("READY_PREFIX_RANDOM SEEDS32 OPERATIONS8192 CHECKS") for line in output.splitlines())
        info.update(lua_executed=True, tests_have_not_run_until_luajit_executes=False,
                    passed=passed, process_exit=process.returncode,
                    output=output.splitlines(), stderr=errors.splitlines())
        print(json.dumps(info))
        return 0 if passed else 1
    except (OSError, ValueError, AssertionError, subprocess.TimeoutExpired) as exc:
        print(json.dumps({"suite": "edge_ready_prefix", "passed": False, "error": type(exc).__name__,
                          "detail": str(exc)[:500], "production_written": False}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
