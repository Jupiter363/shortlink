"""Boundary evidence and fixture refresh for the isolated performance run.

Read-only SQL; only private fixture versions and this run's evidence are written.
No service configuration changes, load requests, data resets, or Kafka consumption.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

import observe


def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sql(state, query):
    if not query.lstrip().upper().startswith("SELECT") or ";" in query:
        raise ValueError("ONE_READ_ONLY_SELECT_REQUIRED")
    secret = observe._secret(state, Path(state["folder"]))
    env = dict(os.environ, MYSQL_PWD=secret["mysqlPassword"])
    result = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", state["mysqlContainer"],
        "mysql", "-u" + secret.get("mysqlUser", "root"), "--batch", "--raw", "--skip-column-names",
        "--connect-timeout=2", state["database"]],
        input="SET SESSION TRANSACTION READ ONLY; START TRANSACTION;\n" + query + ";\nCOMMIT;\n",
        text=True, capture_output=True, env=env, timeout=30)
    if result.returncode:
        raise RuntimeError("READ_ONLY_SQL_FAILED")
    return [row.split("\t") for row in result.stdout.splitlines()]


def refresh_mutations(state_path):
    state, folder = observe._load(state_path)
    target = Path(state["fixturePath"])
    fixture = json.loads(target.read_text())
    records = fixture["mutationLinks"]
    lookup = {str(x["linkId"]): x for x in records}
    if any(not re.fullmatch(r"[0-9]+", key) for key in lookup):
        raise ValueError("INVALID_ID")
    found = 0
    for start in range(0, len(records), 500):
        ids = ",".join(str(x["linkId"]) for x in records[start:start + 500])
        rows = sql(state, "SELECT link_id,route_version,route_status,origin_url FROM t_link_route WHERE link_id IN (" + ids + ")")
        for identity, version, status, origin in rows:
            row = lookup[identity]
            row.update(routeVersion=int(version), state=status, originUrl=origin)
            found += 1
    if found != len(records):
        raise ValueError("MUTATION_FIXTURE_MISSING_AUTHORITY")
    fixture["mutationRefreshAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
    temporary = target.with_suffix(".refresh")
    with temporary.open("x", encoding="utf-8") as stream:
        os.chmod(temporary, 0o600)
        json.dump(fixture, stream, ensure_ascii=False)
    temporary.replace(target)
    summary = {"refreshed": found, "states": {s: sum(r["state"] == s for r in records)
        for s in sorted({r["state"] for r in records})}, "at": fixture["mutationRefreshAt"]}
    write(folder / "mutation-refresh.json", summary)
    return summary


def integrity(state):
    union = " UNION ALL ".join("SELECT id,tenant_id,gid,domain,short_uri,origin_url FROM t_link_" + str(n) for n in range(16))
    query = "SELECT JSON_OBJECT('routeRows',COUNT(*),'uniqueIds',COUNT(DISTINCT link_id)," \
        "'uniqueAddresses',COUNT(DISTINCT domain_norm,short_uri),'nonNineCodes',COALESCE(SUM(CHAR_LENGTH(short_uri)<>9),0)," \
        "'states',JSON_OBJECT('active',COALESCE(SUM(route_status='ACTIVE'),0),'disabled',COALESCE(SUM(route_status='DISABLED'),0))) FROM t_link_route"
    routes = json.loads(sql(state, query)[0][0])
    query = "SELECT JSON_OBJECT('linkRows',COUNT(*),'uniqueLinkIds',COUNT(DISTINCT l.id)," \
        "'missingOrMismatchedRoutes',COALESCE(SUM(r.link_id IS NULL OR r.tenant_id<>l.tenant_id OR " \
        "r.current_gid<>l.gid OR r.domain_norm<>l.domain OR r.short_uri<>l.short_uri OR r.origin_url<>l.origin_url),0)) " \
        "FROM (" + union + ") l LEFT JOIN t_link_route r ON r.link_id=l.id"
    links = json.loads(sql(state, query)[0][0])
    quota = json.loads(sql(state, "SELECT JSON_OBJECT('used',COALESCE(SUM(used_rows),0),"
        "'reserved',COALESCE(SUM(reserved_rows),0)) FROM t_tenant_quota")[0][0])
    tenant_counts = "SELECT tenant_id,COUNT(*) n FROM (" + union + ") l GROUP BY tenant_id"
    tenant_mismatches = int(sql(state, "SELECT COUNT(*) FROM t_tenant_quota q LEFT JOIN (" + tenant_counts +
        ") c ON q.tenant_id=c.tenant_id WHERE q.used_rows<>COALESCE(c.n,0) OR q.reserved_rows<>0")[0][0])
    ok = routes["routeRows"] == routes["uniqueIds"] == routes["uniqueAddresses"] == links["linkRows"] \
        == links["uniqueLinkIds"] == quota["used"] and routes["nonNineCodes"] == 0 \
        and links["missingOrMismatchedRoutes"] == 0 and quota["reserved"] == 0 and tenant_mismatches == 0
    return {"status": "AVAILABLE", "passed": ok, "routes": routes, "links": links, "quota": quota,
        "tenantQuotaMismatchCount": tenant_mismatches,
        "scope": "Current isolated schema, no permanent deletes; read-only full scan after writers stopped and queues drained"}


def command(args, timeout=30):
    try:
        value = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        if value.returncode:
            return {"status": "NOT_AVAILABLE", "reason": "COMMAND_FAILED"}
        return {"status": "AVAILABLE", "output": value.stdout.strip()}
    except (OSError, subprocess.TimeoutExpired):
        return {"status": "NOT_AVAILABLE", "reason": "COMMAND_TIMEOUT_OR_OS_ERROR"}


def kafka_boundary(state):
    base = ["docker", "exec", state["kafkaContainer"]]
    operations = {
        "endOffsets": base + ["/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server", "localhost:9092", "--time", "-1", "--topic", "shortlink.*"],
        "topics": base + ["/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092", "--describe"],
        "logDirectories": base + ["/opt/kafka/bin/kafka-log-dirs.sh", "--bootstrap-server", "localhost:9092", "--describe"],
        "logDiskKiB": base + ["du", "-sk", "/tmp/kafka-logs"],
    }
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {key: pool.submit(command, args) for key, args in operations.items()}
        result = {key: future.result() for key, future in futures.items()}
    result.update(observedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
        scope="Broker totals include retained prior runs; compare deltas. No analytics consumer is running.")
    return result


def boundary(state_path, label):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", label):
        raise ValueError("INVALID_LABEL")
    state, folder = observe._load(state_path)
    pre = observe.sample(state_path)
    if not pre["drain"]["drained"]:
        raise RuntimeError("BOUNDARY_REQUIRES_QUIESCENCE_AND_NO_ACTIVE_WRITER")
    result = {"label": label, "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "integrity": integrity(state), "kafka": kafka_boundary(state), "metrics": observe.sample(state_path)}
    write(folder / (label + "-boundary.json"), result)
    return {"label": label, "integrityPassed": result["integrity"]["passed"],
        "routeRows": result["integrity"]["routes"]["routeRows"], "drain": result["metrics"]["drain"]}


def freeze(state_path):
    state, folder = observe._load(state_path)
    if (folder / "run-manifest.json").exists() or (folder / "stages").exists():
        raise ValueError("MANIFEST_ALREADY_FROZEN_OR_LOAD_STARTED")
    tracked = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=observe.ROOT)
    entries = []
    for name in sorted(set(tracked.decode().split("\0")) - {""}):
        source = observe.ROOT / name
        if source.is_file():
            entries.append({"path": name, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()})
    write(folder / "source-checksums.json", entries)
    # Fixed before the first workload, sufficient for 216k A1 confirmation rows,
    # 117120 upper-bound batch exploration rows, 45840 A1 exploration + setup/refinement.
    state["maximumCreatedRows"] = 500000
    state["budgets"]["maximumPersistentPending"] = 2000
    write(state_path, state)
    inspected = []
    for container in (state["mysqlContainer"], state["redisContainer"], state["kafkaContainer"],
                      "shortlink-refactor-it-minio-1", state["apisix"]):
        raw = subprocess.check_output(["docker", "inspect", container], text=True)
        info = json.loads(raw)[0]
        inspected.append({"name": container, "image": info["Config"]["Image"], "imageId": info["Image"],
            "cpuset": info["HostConfig"]["CpusetCpus"], "memoryLimitBytes": info["HostConfig"]["Memory"],
            "cpuQuota": info["HostConfig"]["CpuQuota"], "cpuPeriod": info["HostConfig"]["CpuPeriod"]})
    runtime_commands = {
        "jdk": ["java", "--version"], "kernel": ["uname", "-a"],
        "k6": ["/opt/shortlink-perf/tools/k6-v2.2.0", "version"],
        "mysql": ["docker", "exec", state["mysqlContainer"], "mysql", "--version"],
        "redis": ["docker", "exec", state["redisContainer"], "redis-server", "--version"],
        "apisix": ["docker", "exec", state["apisix"], "apisix", "version"],
    }
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = {key: pool.submit(command, args) for key, args in runtime_commands.items()}
        versions = {key: future.result() for key, future in futures.items()}
    manifest = {"frozenAt": dt.datetime.now(dt.timezone.utc).isoformat(), "state": state,
        "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=observe.ROOT, text=True).strip(),
        "gitBranch": subprocess.check_output(["git", "branch", "--show-current"], cwd=observe.ROOT, text=True).strip(),
        "sourceFileCount": len(entries), "dirtySourceSnapshot": "source-checksums.json",
        "sourceSnapshotSha256": hashlib.sha256((folder / "source-checksums.json").read_bytes()).hexdigest(),
        "containers": inspected, "versions": versions,
        "k6": json.loads(Path("/opt/shortlink-perf/tools/k6-install.json").read_text()),
        "budgets": {"createP99Ms": 500, "directP99Ms": 50, "edgeP99Ms": 80,
            "batchP99Ms": {"10": 1000, "100": 3000, "500": 5000}, "correctRateMinimum": 0.999,
            "offeredRateMinimumRatio": 0.99, "droppedIterationsMaximum": 0, "failedOrRejectedEventsMaximum": 0,
            "maximumCreatedRows": 500000, "maximumCreatedRowsPreviousDefault": 200000,
            "rowBudgetReason": "Frozen before workload: setup 11300 + A1 upper-bound exploration 45840 + "
                "batch exploration 117120 + A1 twenty-account-only confirmation 216000 + bounded refinements/checks <=500000",
            "confirmationScope": "Only A1 twenty-account writes and representative A5/B1 hot10 GET candidates. "
                "Batch and other distributions remain exploration evidence; do not claim confirmed capacity.",
            "rowBudgetDiskEstimateGiB": 10, "maximumMetadataDrainSeconds": 300},
        "comparison": {"entry": "HTTP/1.1 keep-alive; no TLS; standalone APISIX; local diagnostics",
            "cpu": "Load 12-15; Java 0-7 (each ActiveProcessorCount=2); APISIX 4-7 overlaps Java; dependencies 8-11",
            "cacheAuthorityTtlMillis": 1000, "sharedOriginBudgetPerSecond": 200,
            "metadata": "Known .local policy rejection, poll" + str(state.get("metadataPollMillis", 50))
                + "ms (default1000), " + str(state["resourceProfile"]["metadataWorkers"])
                + " workers; no normal fetch claim",
            "statistics": "Kafka producer evidence only; Agent, LLM, Flink, ClickHouse, analytics queries excluded"}}
    manifest["budgets"].update(maximumPersistentPending=2000,
        persistentPendingReason="Preparation drained 500 jobs in approximately 40-53 seconds. "
            "Stop input at 2000 pending to retain recovery headroom within 300 seconds; finite diagnostic admission guard.",
        smokeP99MaxMs=10000, smokeMeaning="30-second contract calibration, excluded from capacity acceptance")
    if state.get("optimizationProfile"):
        manifest["budgets"].update(
            rowBudgetReason="Retain the prior 500000-row hard ceiling; optimization specs offer at most 33634 new rows plus 11300 fixture rows.",
            confirmationScope="Bounded optimization regression only: four contract checks, five write stages, seven hot10 redirect stages; no sustained capacity confirmation.",
            persistentPendingReason="Retain the prior 2000-pending stop threshold and 300-second drain limit for comparable bounded regression; not a measured broker or consumer capacity.")
    if state.get("metadataIntentObservationRequired"):
        manifest["budgets"].update(
            rowBudgetReason="Bounded metadata execution regression: <=67000 new rows including one optional 100/s five-minute measurement, plus 11300 fixture rows; retain 500000 hard ceiling.",
            confirmationScope="Same 25/50/100 create and two batch levels, contract checks and representative gateway redirect. Only after all stages pass, one bounded five-minute 100/s measurement checks backlog growth; no three-repeat or production-capacity acceptance.",
            persistentPendingReason="Stop at 2000 Outbox pending OR 2000 metadata unfinished intents (not-yet-enqueued plus table pending, never added to Outbox); require complete recovery within 300 seconds.")
    if state.get("campaignProfile") == "peak":
        plan_path = folder / "peak-plan.json"
        plan = json.loads(plan_path.read_text(encoding="utf-8-sig"))
        if plan.get("runId") != state["runId"] or plan.get("maximumNewRows") != 400000:
            raise ValueError("PEAK_PLAN_IDENTITY_OR_BUDGET_INVALID")
        manifest["peakPlan"] = plan
        manifest["peakPlanSha256"] = hashlib.sha256(plan_path.read_bytes()).hexdigest()
        manifest["budgets"].update(
            rowBudgetReason="Peak search: <=400000 new rows including finite ladders, up to three refinements, one 300-second candidate confirmation and short bursts; plus 11300 fixture rows, retain hard 500000.",
            confirmationScope="Find measured pass/fail brackets for current artifacts. Create uses 256 VUs; direct/edge use 512 VUs. Short bursts are recovery-tested peaks, not sustainable throughput or production acceptance. Edge multi-source load keeps original per-IP limits.")
    write(folder / "run-manifest.json", manifest)
    return {"frozen": True, "sourceFileCount": len(entries), "maximumCreatedRows": 500000}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state")
    parser.add_argument("action", choices=("freeze", "boundary", "refresh"))
    parser.add_argument("--label", default="baseline")
    args = parser.parse_args()
    result = freeze(args.state) if args.action == "freeze" else refresh_mutations(args.state) if args.action == "refresh" else boundary(args.state, args.label)
    print(json.dumps(result, ensure_ascii=False))
