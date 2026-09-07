"""Bounded, read-only observations for the isolated performance supervisor.

sample(state_path) only reads; CLI writes metrics.jsonl and atomically replaces
latest-metrics.json in that run's folder. No business requests, service lifecycle,
Kafka consumers, or schema changes. drained() proves quiescence, not success:
the caller must compare failure counters with its baseline and check latency.
Run on the dedicated Linux host. Python standard library only.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import http.client
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
MAX_BYTES = 2 * 1024 * 1024
SERVICES = {"gateway": 8100, "shortlink-command": 8101, "admin": 8102,
            "shortlink-redirect": 8103}
SAFE_NAME = re.compile(r"[A-Za-z0-9_.-]{1,128}\Z")
PROM_LINE = re.compile(r'^([A-Za-z_:][A-Za-z0-9_:]*)(\{[^\n]*\})?\s+([^\s]+)(?:\s+\d+)?$')
LABEL = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)="((?:[^"\\]|\\.)*)"')
ALLOWED_LABELS = {"area", "id", "pool", "action", "cause", "method", "status",
                  "outcome", "exception", "error", "uri", "lane", "le", "quantile",
                  "state", "name", "worker", "worker_id", "generation", "instance", "boot_id", "producer_instance_id"}
METADATA_INTAKE_OUTCOMES = {
    "shortlink_metadata_intake_batches_total": {"committed", "failed"},
    "shortlink_metadata_intake_duration_seconds_count": {"committed", "failed"},
    "shortlink_metadata_intake_duration_seconds_sum": {"committed", "failed"},
    "shortlink_metadata_intake_duration_seconds_max": {"committed", "failed"},
    "shortlink_metadata_intake_records_total": {"accepted", "rejected"},
    "shortlink_metadata_intake_offset_commits_total": {"committed", "failed"},
    "shortlink_metadata_intake_reconnects_total": set(),
}
PIPELINE_FAILURE_SERIES = {
    "outbox.claim_failures": ("shortlink_outbox_claim_failures_total", {}),
    "metadata.intake_batches_failed": ("shortlink_metadata_intake_batches_total", {"outcome": "failed"}),
    "metadata.intake_offset_commits_failed": ("shortlink_metadata_intake_offset_commits_total", {"outcome": "failed"}),
    "metadata.intake_records_rejected": ("shortlink_metadata_intake_records_total", {"outcome": "rejected"}),
}
PIPELINE_FAILURE_NAMES = {name for name, labels in PIPELINE_FAILURE_SERIES.values()}
METADATA_EXECUTION_OPERATIONS = ("candidates", "claim", "apply", "retry")
METADATA_EXECUTION_OUTCOMES = ("success", "empty", "obsolete", "stale", "failed")
METADATA_EXECUTION_METRICS = {
    "shortlink_metadata_execution_duration_seconds_count": "count",
    "shortlink_metadata_execution_duration_seconds_sum": "totalSeconds",
    "shortlink_metadata_execution_duration_seconds_max": "maxSeconds",
}
METADATA_EXECUTION_LABELS = re.compile(
    r"\s*" + LABEL.pattern + r"(?:\s*,\s*" + LABEL.pattern + r")*\s*,?\s*")


def unavailable(reason):
    return {"status": "NOT_AVAILABLE", "reason": reason}


def guarded(action):
    started = time.monotonic()
    try:
        value = action()
        if "status" not in value:
            value = {"status": "AVAILABLE", "data": value}
    except subprocess.TimeoutExpired:
        value = unavailable("COMMAND_TIMEOUT")
    except (TimeoutError, OSError, http.client.HTTPException):
        value = unavailable("TRANSPORT_OR_OS_ERROR")
    except Exception:
        # Never serialize exception text: CLI/HTTP errors can contain credentials or URLs.
        value = unavailable("INVALID_OR_UNAVAILABLE_OBSERVATION")
    value["durationMs"] = round((time.monotonic() - started) * 1000, 1)
    return value


def _run(args, *, data=None, timeout=3, env=None, allow_failure=False):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, timeout=timeout, env=env)
    if len(result.stdout) > MAX_BYTES or len(result.stderr) > MAX_BYTES:
        raise ValueError("OUTPUT_BUDGET")
    if result.returncode and not allow_failure:
        raise ValueError("COMMAND_FAILED")
    return result


def _http(port, path, token=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=1.2)
    try:
        headers = {"Accept": "application/json" if token else "text/plain",
                   "Connection": "close"}
        if token:
            headers["X-Internal-Token"] = token
        conn.request("GET", path, headers=headers)
        response = conn.getresponse()
        data = response.read(MAX_BYTES + 1)
        if response.status != 200 or len(data) > MAX_BYTES:
            raise ValueError("HTTP_OBSERVATION_UNAVAILABLE")
        return data.decode("utf-8")
    finally:
        conn.close()


def _load(state_path):
    path = Path(state_path).resolve()
    path.relative_to((ROOT / ".work/performance").resolve())
    if path.name != "state.json":
        raise ValueError("Expected an isolated performance state.json")
    state = json.loads(path.read_text(encoding="utf-8-sig"))
    if type(state.get("metadataIntentObservationRequired", False)) is not bool:
        raise ValueError("INVALID_METADATA_INTENT_OBSERVATION_FLAG")
    if type(state.get("pipelineFailureObservationRequired", False)) is not bool:
        raise ValueError("INVALID_PIPELINE_FAILURE_OBSERVATION_FLAG")
    if not isinstance(state.get("runId"), str) or not SAFE_NAME.fullmatch(state["runId"]):
        raise ValueError("Invalid run identity")
    folder = Path(state["folder"]).resolve()
    if folder != path.parent or not re.fullmatch(r"shortlink_perf_[a-f0-9_]{6,48}", state["database"]):
        raise ValueError("Refusing non-performance folder/schema")
    if type(state.get("redisDatabase")) is not int or not 1 <= state["redisDatabase"] <= 15:
        raise ValueError("A dedicated non-default Redis logical database is required")
    for field, default in (("mysqlContainer", "shortlink-refactor-it-mysql-1"),
                           ("redisContainer", "shortlink-refactor-it-redis-1")):
        state.setdefault(field, default)
        if not isinstance(state[field], str) or not SAFE_NAME.fullmatch(state[field]):
            raise ValueError("Invalid container identity")
    redis_container = state["redisContainer"]
    redis_port = state.setdefault("redisPort", 16379)
    if type(redis_port) is not int or not (
            (redis_container == "shortlink-refactor-it-redis-1" and redis_port == 16379)
            or (re.fullmatch(r"shortlink-perf-redis-[a-z0-9-]+", redis_container)
                and redis_port == 16380)):
        raise ValueError("Invalid isolated Redis container/port combination")
    container = state.get("containerName", state.get("apisix"))
    if not isinstance(container, str) or not SAFE_NAME.fullmatch(container):
        raise ValueError("Invalid APISIX container identity")
    state["apisix"] = container
    return state, folder


def _prometheus(text, edge=False):
    values = []
    omitted = 0
    pipeline_series_invalid = False
    execution_series_invalid = False
    execution_seen = set()
    for line in text.splitlines():
        match = PROM_LINE.fullmatch(line)
        if not match:
            if not edge and line.startswith("shortlink_metadata_execution_"):
                execution_series_invalid = True
            continue
        name, encoded, raw = match.groups()
        execution_metric = name.startswith("shortlink_metadata_execution_")
        accepted = name.startswith("shortlink_edge_") if edge else name.startswith(
            ("jvm_", "process_cpu_", "process_uptime_", "process_start_time_",
             "system_cpu_", "http_server_requests_", "hikaricp_", "shortlink_id_",
             "shortlink_events_", "shortlink_outbox_", "shortlink_admin_feign_",
              "shortlink_metadata_intake_", "shortlink_metadata_execution_"))
        if not accepted:
            continue
        label_pairs = LABEL.findall(encoded or "")
        labels = dict(label_pairs)
        if execution_metric:
            if (name not in METADATA_EXECUTION_METRICS or not encoded
                    or not METADATA_EXECUTION_LABELS.fullmatch(encoded[1:-1])
                    or len(label_pairs) != len(labels) or set(labels) != {"operation", "outcome"}
                    or labels["operation"] not in METADATA_EXECUTION_OPERATIONS
                    or labels["outcome"] not in METADATA_EXECUTION_OUTCOMES):
                execution_series_invalid = True
                omitted += 1
                continue
            identity = (name, labels["operation"], labels["outcome"])
            if identity in execution_seen:
                execution_series_invalid = True
                omitted += 1
                continue
            execution_seen.add(identity)
        if name in PIPELINE_FAILURE_NAMES and len(label_pairs) != len(labels):
            pipeline_series_invalid = True
            omitted += 1
            continue
        if name == "shortlink_outbox_claim_failures_total" and labels:
            pipeline_series_invalid = True
            omitted += 1
            continue
        if name.startswith("shortlink_metadata_intake_"):
            outcomes = METADATA_INTAKE_OUTCOMES.get(name)
            if outcomes is None or (outcomes and (set(labels) != {"outcome"}
                    or labels["outcome"] not in outcomes)) or (not outcomes and labels):
                pipeline_series_invalid |= name in PIPELINE_FAILURE_NAMES
                omitted += 1
                continue
        allowed_labels = ALLOWED_LABELS | {"operation"} if execution_metric else ALLOWED_LABELS
        if encoded and (any(k not in allowed_labels for k in labels)
                        or any(len(v) > 160 for v in labels.values())):
            pipeline_series_invalid |= name in PIPELINE_FAILURE_NAMES
            omitted += 1
            continue
        # Only templated/fixed management paths. Never emit a raw alias or request URL.
        uri = labels.get("uri")
        if uri is not None and not (uri in {"UNKNOWN", "NOT_FOUND", "root", "/", "/error"}
                or ("{" in uri and "?" not in uri)
                or re.fullmatch(r"/(?:api|internal|actuator)/[A-Za-z0-9_/{}/.-]{1,140}", uri)):
            omitted += 1
            continue
        try:
            number = float(raw)
        except ValueError:
            if not execution_metric:
                raise
            execution_series_invalid = True
            omitted += 1
            continue
        if execution_metric and (not math.isfinite(number) or number < 0
                or (METADATA_EXECUTION_METRICS[name] == "count" and not number.is_integer())):
            execution_series_invalid = True
            omitted += 1
            continue
        if not math.isfinite(number):
            pipeline_series_invalid |= name in PIPELINE_FAILURE_NAMES
            omitted += 1
            continue
        values.append({"name": name, "labels": labels, "value": number})
        if len(values) > 2000:
            raise ValueError("SERIES_BUDGET")
    if not values:
        return unavailable("NO_ALLOWED_METRICS")
    return {"status": "AVAILABLE", "metrics": values, "omittedSeries": omitted,
            "pipelineFailureSeriesInvalid": pipeline_series_invalid,
            "metadataExecutionSeriesInvalid": execution_series_invalid}


def _secret(state, folder):
    path = Path(state.get("observerSecret", folder / "observer-secret.json")).resolve()
    if path != folder / "observer-secret.json":
        expected = (Path("/var/lib/shortlink-perf") / state["runId"] / "observer-secret.json").resolve()
        if path != expected:
            raise ValueError("INVALID_PRIVATE_SECRET_PATH")
    return json.loads(path.read_text(encoding="utf-8"))


def _quality(state, folder):
    secret = _secret(state, folder)
    token = secret.get("internalToken")
    if not isinstance(token, str) or not token:
        raise ValueError("INTERNAL_TOKEN_REQUIRED")
    raw = json.loads(_http(8003, "/internal/v1/events/quality", token))
    result = {k: raw[k] for k in ("producerInstanceId", "startedAt", "observedAt")}
    if not isinstance(result["producerInstanceId"], str) or not SAFE_NAME.fullmatch(result["producerInstanceId"]):
        raise ValueError("INVALID_INSTANCE")
    if any(type(result[key]) is not int or result[key] < 1 for key in ("startedAt", "observedAt")):
        raise ValueError("INVALID_OBSERVATION_TIME")
    result["lanes"] = {}
    for lane in ("click", "result"):
        counts = {key: raw["lanes"][lane][key]
                  for key in ("attempted", "delivered", "failed", "rejected", "pending")}
        if any(type(value) is not int or value < 0 for value in counts.values()):
            raise ValueError("INVALID_COUNTERS")
        result["lanes"][lane] = counts
    return result


def _edge(state):
    pid = int(_run(["docker", "inspect", "-f", "{{.State.Pid}}", state["apisix"]]).stdout.strip())
    if pid < 1:
        raise ValueError("APISIX_NOT_RUNNING")
    script = ("import http.client,sys; c=http.client.HTTPConnection('127.0.0.1',9099,timeout=1);"
              "c.request('GET','/shortlink/metrics'); r=c.getresponse(); b=r.read(2097153);"
              "assert r.status==200 and len(b)<=2097152;sys.stdout.buffer.write(b);c.close()")
    report = _prometheus(_run(["nsenter", "-t", str(pid), "-n", "/usr/bin/python3", "-B", "-c", script],
                              timeout=2).stdout, edge=True)
    report["containerPid"] = pid
    report["pidChangedSinceStart"] = state.get("apisixPid") not in (None, pid)
    return report


def _mysql(state, folder):
    now = "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)"
    statements = ["START TRANSACTION READ ONLY;"]
    if state.get("metadataIntentObservationRequired") is True:
        # Both tables must describe one committed view, even if the session default is RC.
        statements = ["SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;",
                      "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;"]
    for name, table, terminal in (("outbox", "t_outbox", "'PUBLISHED','FAILED'"),
                                   ("metadata", "t_metadata_job", "'COMPLETED','OBSOLETE','FAILED'")):
        pending = "state NOT IN (" + terminal + ")"
        extra = (",'terminalFailedFirstAttempt',COALESCE(SUM(state='FAILED' AND attempts=1),0),"
                 "'terminalFailedRetryExhausted',COALESCE(SUM(state='FAILED' AND attempts>=8),0)"
                 if name == "metadata" else "")
        statements.append("SELECT /*+ MAX_EXECUTION_TIME(1200) */ '" + name + "', JSON_OBJECT("
            "'pending',COALESCE(SUM(" + pending + "),0),"
            "'oldestPendingAgeMs',COALESCE(MAX(CASE WHEN " + pending + " THEN GREATEST(0," + now + "-created_at) END),0),"
            "'terminalFailed',COALESCE(SUM(state='FAILED'),0),'total',COUNT(*)" + extra + ") FROM " + table + ";")
    statements.append("SELECT /*+ MAX_EXECUTION_TIME(1200) */ 'routeCount', COUNT(*) FROM t_link_route;")
    counts = " UNION ALL ".join("SELECT COUNT(*) n FROM t_link_" + str(i) for i in range(16))
    statements.append("SELECT /*+ MAX_EXECUTION_TIME(1200) */ 'linkCount', SUM(n) FROM (" + counts + ") x;")
    statements.append("SELECT /*+ MAX_EXECUTION_TIME(1200) */ 'quota', JSON_OBJECT('tenantId',tenant_id,"
        "'usedRows',used_rows,'reservedRows',reserved_rows,'activeJobs',active_jobs,"
        "'validationJobs',validation_jobs,'validationBytes',validation_bytes) FROM t_tenant_quota ORDER BY tenant_id LIMIT 1001;")
    statements.append("SELECT /*+ MAX_EXECUTION_TIME(1200) */ 'quotaCount',COUNT(*) FROM t_tenant_quota;")
    statements.append("SELECT 'idAllocator',JSON_OBJECT('namespace',biz_tag,'endExclusive',max_id,"
        "'configuredStep',step) FROM t_id_alloc WHERE biz_tag='shortlink_global';")
    statements.append("COMMIT;")
    variables = ("Questions", "Queries", "Com_select", "Com_insert", "Com_update", "Com_commit",
                 "Com_rollback", "Threads_connected", "Threads_running", "Connections", "Aborted_connects",
                 "Innodb_row_lock_current_waits", "Innodb_row_lock_time", "Innodb_row_lock_waits",
                 "Innodb_buffer_pool_reads", "Innodb_buffer_pool_read_requests")
    statements.append("SELECT 'globalStatus',JSON_OBJECTAGG(VARIABLE_NAME,VARIABLE_VALUE) FROM "
        "performance_schema.global_status WHERE VARIABLE_NAME IN (" + ",".join("'" + x + "'" for x in variables) + ");")
    statements.append("SELECT /*+ MAX_EXECUTION_TIME(1200) */ 'idStatementDigest',JSON_OBJECT("
        "'performanceSchema',@@performance_schema,'digestConsumer',"
        "(SELECT ENABLED FROM performance_schema.setup_consumers WHERE NAME='statements_digest'),"
        "'matchingDigests',COUNT(*),'countStar',COALESCE(SUM(COUNT_STAR),0),"
        "'sumTimerWaitPs',COALESCE(SUM(SUM_TIMER_WAIT),0)) FROM "
        "performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='" + state["database"] + "' "
        "AND DIGEST_TEXT LIKE 'UPDATE%t_id_alloc%';")
    env = dict(os.environ)
    # Inherited by docker exec via name, never put the value into its argument list or report.
    secret = _secret(state, folder)
    username = secret.get("mysqlUser", "root")
    password = secret.get("mysqlPassword")
    if not isinstance(password, str) or not password or not SAFE_NAME.fullmatch(username):
        raise ValueError("MYSQL_OBSERVER_CREDENTIALS_REQUIRED")
    env["MYSQL_PWD"] = password
    result = _run(["docker", "exec", "-i", "-e", "MYSQL_PWD", state["mysqlContainer"],
                   "mysql", "-u" + username, "--batch", "--raw", "--skip-column-names", "--force",
                   "--connect-timeout=1", state["database"]], data="\n".join(statements),
                  timeout=4, env=env, allow_failure=True)
    if state.get("metadataIntentObservationRequired") is True and (result.returncode or result.stderr.strip()):
        # --force can otherwise leave a partial result after a failed snapshot statement.
        return unavailable("MYSQL_METADATA_INTENT_SNAPSHOT_QUERY_FAILED")
    data = {"quota": []}
    for line in result.stdout.splitlines():
        key, separator, value = line.partition("\t")
        if separator and key in {"outbox", "metadata", "routeCount", "linkCount", "quota", "quotaCount", "idAllocator",
                                "globalStatus", "idStatementDigest"}:
            decoded = json.loads(value)
            if key == "quota":
                data[key].append(decoded)
            else:
                data[key] = decoded
    required = {"outbox", "metadata", "routeCount", "linkCount", "idAllocator", "quotaCount"}
    if not required.issubset(data) or len(data["quota"]) > 1000 or len(data["quota"]) != data["quotaCount"]:
        return unavailable("MYSQL_REQUIRED_QUERY_FAILED_OR_QUOTA_BUDGET")
    for key in ("globalStatus", "idStatementDigest"):
        if key not in data or data[key] is None:
            data[key] = unavailable("MYSQL_OPTIONAL_METRICS_UNAVAILABLE")
    digest = data["idStatementDigest"]
    if "status" not in digest:
        digest["status"] = "AVAILABLE" if (digest.get("performanceSchema") == 1
            and digest.get("digestConsumer") == "YES" and digest.get("matchingDigests", 0) > 0) else "NOT_AVAILABLE"
        if digest["status"] != "AVAILABLE":
            digest["reason"] = "DIGEST_DISABLED_OR_NO_CAPTURED_MATCH"
            digest.pop("countStar", None)
            digest.pop("sumTimerWaitPs", None)
    data["globalScope"] = "GLOBAL_STATUS_INCLUDES_OBSERVER_AND_OTHER_DATABASES"
    available_names = {str(key).lower() for key in data["globalStatus"]}
    data["globalStatusMissing"] = [name for name in variables if name.lower() not in available_names]
    data["rowCountCost"] = "EXACT_COUNTS_SCAN_CURRENT_TEST_ROWS"
    data["metadataMode"] = state.get("metadataMode", "UNSPECIFIED")
    return data


def _redis(state):
    raw = _run(["docker", "exec", state["redisContainer"], "redis-cli", "--raw", "-n",
                str(state["redisDatabase"]), "INFO", "all"]).stdout
    allowed = {"used_memory", "used_memory_rss", "used_memory_peak", "maxmemory", "maxmemory_policy",
               "evicted_keys", "expired_keys", "total_commands_processed", "total_net_input_bytes",
               "total_net_output_bytes", "instantaneous_ops_per_sec", "connected_clients", "blocked_clients",
               "keyspace_hits", "keyspace_misses", "uptime_in_seconds", "total_error_replies"}
    data = {"globalScope": "MEMORY_AND_COMMAND_COUNTERS_ARE_INSTANCE_WIDE", "database": state["redisDatabase"]}
    db_key = "db" + str(state["redisDatabase"])
    for line in raw.splitlines():
        key, separator, value = line.partition(":")
        if not separator:
            continue
        if key in allowed:
            data[key] = int(value) if re.fullmatch(r"\d+", value) else value
        elif key == db_key:
            data["keyspace"] = {k: int(v) for k, v in (x.split("=") for x in value.split(","))
                                if k in {"keys", "expires", "avg_ttl"}}
        elif key in {"cmdstat_get", "cmdstat_set", "cmdstat_eval", "cmdstat_evalsha", "cmdstat_hget",
                     "cmdstat_hset", "cmdstat_info"}:
            data.setdefault("commandStats", {})[key] = {k: float(v) for k, v in (x.split("=") for x in value.split(","))}
    if "used_memory" not in data:
        raise ValueError("REDIS_INFO_UNAVAILABLE")
    data.setdefault("keyspace", {"keys": 0, "expires": 0, "avg_ttl": 0})
    return data


def _process(pid):
    pid = int(pid)
    if pid < 1:
        raise ValueError("INVALID_PID")
    base = Path("/proc") / str(pid)
    fields = (base / "stat").read_text().rsplit(") ", 1)[1].split()
    return {"pid": pid, "state": fields[0], "userTicks": int(fields[11]),
            "systemTicks": int(fields[12]), "startTicks": int(fields[19]),
            "threads": int(fields[17]), "rssBytes": int(fields[21]) * os.sysconf("SC_PAGE_SIZE"),
            "openFds": len(list((base / "fd").iterdir())), "clockTicksPerSecond": os.sysconf("SC_CLK_TCK")}


def _children(pid):
    child_ids = (Path("/proc") / str(pid) / "task" / str(pid) / "children").read_text().split()
    if len(child_ids) > 64:
        raise ValueError("PROCESS_CHILD_BUDGET")
    return {"parentPid": pid, "children": [guarded(lambda value=value: _process(value)) for value in child_ids]}


def _host(folder):
    memory = {}
    for line in Path("/proc/meminfo").read_text().splitlines():
        key, value = line.split(":", 1)
        if key in {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree", "Dirty", "Writeback"}:
            memory[key + "Bytes"] = int(value.split()[0]) * 1024
    cpu = [int(x) for x in Path("/proc/stat").read_text().splitlines()[0].split()[1:]]
    disk = shutil.disk_usage(folder)
    return {"memory": memory, "cpuTicks": cpu, "cpuCount": os.cpu_count(), "loadAverage": list(os.getloadavg()),
            "disk": {"totalBytes": disk.total, "usedBytes": disk.used, "freeBytes": disk.free},
            "diskScope": "PERFORMANCE_EVIDENCE_FILESYSTEM_NOT_KAFKA_VOLUME"}


def sample(state_path) -> dict:
    """Read one bounded observation. This function itself writes no files."""
    started = time.monotonic()
    state, folder = _load(state_path)
    report = {"schemaVersion": 1, "runId": state["runId"],
              "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "services": {}, "processes": {},
              "outboxObservationRequired": state.get("outboxObservationRequired", False),
              "adminTransportObservationRequired": state.get("adminTransportObservationRequired", False),
              "metadataIntentObservationRequired": state.get("metadataIntentObservationRequired", False),
              "pipelineFailureObservationRequired": state.get("pipelineFailureObservationRequired", False)}
    actions = {name: (lambda port=port: _prometheus(_http(port, "/actuator/prometheus")))
               for name, port in SERVICES.items()}
    actions.update(redirectQuality=lambda: _quality(state, folder), apisix=lambda: _edge(state),
                   mysql=lambda: _mysql(state, folder), redis=lambda: _redis(state), host=lambda: _host(folder))
    with ThreadPoolExecutor(max_workers=9, thread_name_prefix="perf-observer") as pool:
        futures = {key: pool.submit(guarded, action) for key, action in actions.items()}
        for key, future in futures.items():
            value = future.result()
            if key in SERVICES:
                report["services"][key] = value
            else:
                report[key] = value
    for name, artifact in state.get("jvmArtifacts", {}).items():
        if name in SERVICES:
            report["processes"][name] = guarded(lambda artifact=artifact: _process(artifact["pid"]))
    if report["apisix"].get("containerPid"):
        report["processes"]["apisixMaster"] = guarded(lambda: _process(report["apisix"]["containerPid"]))
        report["processes"]["apisixChildren"] = guarded(lambda: _children(report["apisix"]["containerPid"]))
    report["sampleDurationMs"] = round((time.monotonic() - started) * 1000, 1)
    report["observerOverrun"] = report["sampleDurationMs"] > 5000
    if report["metadataIntentObservationRequired"]:
        report["metadataIntent"] = metadata_intent(report)
    report["drain"] = drained(report)
    return report


def _metric_values(report, names):
    return [m["value"] for m in report.get("metrics", []) if m["name"] in names]


def metadata_execution_metrics(snapshot) -> dict:
    """Optional execution diagnostics; no outcome is added to the existing failure gates.

    All 20 timers are pre-registered. Absent, duplicate or invalid series are not
    zero samples. Timer sums include concurrent operations and are not wall time.
    """
    command = snapshot.get("services", {}).get("shortlink-command", {})
    if command.get("status") != "AVAILABLE":
        return unavailable("METADATA_EXECUTION_SERVICE_UNAVAILABLE")
    if command.get("metadataExecutionSeriesInvalid"):
        return unavailable("METADATA_EXECUTION_SERIES_INVALID")
    metrics = command.get("metrics")
    if not isinstance(metrics, list):
        return unavailable("METADATA_EXECUTION_SERIES_UNAVAILABLE")
    values = {}
    for metric in metrics:
        if not isinstance(metric, dict):
            return unavailable("METADATA_EXECUTION_SERIES_INVALID")
        name = metric.get("name")
        if not isinstance(name, str) or not name.startswith("shortlink_metadata_execution_"):
            continue
        labels, value = metric.get("labels"), metric.get("value")
        if (name not in METADATA_EXECUTION_METRICS or not isinstance(labels, dict)
                or set(labels) != {"operation", "outcome"}
                or labels["operation"] not in METADATA_EXECUTION_OPERATIONS
                or labels["outcome"] not in METADATA_EXECUTION_OUTCOMES
                or type(value) not in (int, float) or not math.isfinite(value) or value < 0
                or (METADATA_EXECUTION_METRICS[name] == "count" and value != int(value))):
            return unavailable("METADATA_EXECUTION_SERIES_INVALID")
        identity = (name, labels["operation"], labels["outcome"])
        if identity in values:
            return unavailable("METADATA_EXECUTION_SERIES_DUPLICATED")
        values[identity] = value
    expected = len(METADATA_EXECUTION_METRICS) * len(METADATA_EXECUTION_OPERATIONS) * len(METADATA_EXECUTION_OUTCOMES)
    if len(values) != expected:
        return unavailable("METADATA_EXECUTION_SERIES_INCOMPLETE")
    operations = {}
    for operation in METADATA_EXECUTION_OPERATIONS:
        operations[operation] = {}
        for outcome in METADATA_EXECUTION_OUTCOMES:
            fields = {field: values[(name, operation, outcome)]
                      for name, field in METADATA_EXECUTION_METRICS.items()}
            fields["meanSeconds"] = fields["totalSeconds"] / fields["count"] if fields["count"] else None
            operations[operation][outcome] = fields
    return {"status": "AVAILABLE", "seriesCount": expected, "operations": operations,
            "meaning": "OPTIONAL_DIAGNOSTIC; COUNT_IS_OPERATIONS; DURATIONS_ARE_SECONDS; "
                       "SUM_INCLUDES_PARALLEL_OPERATIONS; STALE_IS_RECOVERABLE; "
                       "NO_EXECUTION_OUTCOME_CHANGES_PIPELINE_FAILURE_GATES"}


def pipeline_failure_counters(snapshot):
    """Four separate failure observations; reconnects and existing ACK failures are not added."""
    command = snapshot.get("services", {}).get("shortlink-command", {})
    if command.get("status") != "AVAILABLE" or command.get("pipelineFailureSeriesInvalid"):
        return None
    result = {}
    for key, (name, expected_labels) in PIPELINE_FAILURE_SERIES.items():
        matches = [m for m in command.get("metrics", []) if m.get("name") == name
                   and (not expected_labels or (isinstance(m.get("labels"), dict)
                        and m["labels"].get("outcome") == expected_labels["outcome"]))]
        if len(matches) != 1 or matches[0].get("labels") != expected_labels:
            return None
        value = matches[0].get("value")
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            return None
        result[key] = value
    return result


def metadata_intent(snapshot) -> dict:
    """One creation intent per route; only valid for the restricted create/read profile.

    notEnqueued includes intents still in Outbox. It is not Kafka consumer lag and
    must never be added to Outbox pending. Recompute from raw counts at each gate.
    """
    database = snapshot.get("mysql")
    if not isinstance(database, dict) or database.get("status") != "AVAILABLE":
        return unavailable("METADATA_INTENT_MYSQL_UNAVAILABLE")
    data = database.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("metadata"), dict):
        return unavailable("METADATA_INTENT_COUNTS_UNAVAILABLE")
    route_count = data.get("routeCount")
    task_total = data["metadata"].get("total")
    pending = data["metadata"].get("pending")
    if any(type(value) is not int or value < 0 for value in (route_count, task_total, pending)):
        return unavailable("METADATA_INTENT_COUNTS_INVALID_OR_UNAVAILABLE")
    not_enqueued = route_count - task_total
    if not_enqueued < 0:
        return unavailable("METADATA_INTENT_NEGATIVE_DIFFERENCE")
    if pending > task_total:
        return unavailable("METADATA_INTENT_PENDING_EXCEEDS_TOTAL")
    return {"status": "AVAILABLE", "routeCount": route_count, "taskTotal": task_total,
            "notEnqueued": not_enqueued, "tablePending": pending,
            "totalUnfinished": not_enqueued + pending,
            "meaning": "ONE_CREATION_INTENT_PER_ROUTE; NOT_ENQUEUED_INCLUDES_OUTBOX; "
                       "NOT_KAFKA_LAG; DO_NOT_ADD_OUTBOX_PENDING"}


def drained(snapshot) -> dict:
    """Conservative drain evidence; no error-rate/recovery SLA or baseline inference."""
    reasons = []
    pipeline_required = snapshot.get("pipelineFailureObservationRequired", False)
    if type(pipeline_required) is not bool:
        reasons.append("INVALID_PIPELINE_FAILURE_OBSERVATION_FLAG")
    elif pipeline_required and pipeline_failure_counters(snapshot) is None:
        reasons.append("PIPELINE_FAILURE_COUNTERS_UNAVAILABLE")
    try:
        observed = dt.datetime.fromisoformat(snapshot["observedAt"])
        age = (dt.datetime.now(dt.timezone.utc) - observed).total_seconds()
        if age < -2 or age > 15:
            reasons.append("SNAPSHOT_NOT_FRESH")
    except (KeyError, TypeError, ValueError):
        reasons.append("SNAPSHOT_TIME_UNAVAILABLE")
    quality = snapshot.get("redirectQuality", {})
    if quality.get("status") != "AVAILABLE":
        reasons.append("REDIRECT_QUALITY_UNAVAILABLE")
    else:
        quality_time = quality.get("data", {}).get("observedAt")
        if type(quality_time) is not int or not -2000 <= time.time() * 1000 - quality_time <= 15000:
            reasons.append("REDIRECT_QUALITY_NOT_FRESH")
        for lane in ("click", "result"):
            counts = quality.get("data", {}).get("lanes", {}).get(lane, {})
            if counts.get("pending") != 0:
                reasons.append("REDIRECT_" + lane.upper() + "_NOT_DRAINED")
            if not counts or counts.get("attempted") != sum(counts.get(k, -1) for k in ("delivered", "failed", "rejected", "pending")):
                reasons.append("REDIRECT_" + lane.upper() + "_COUNTER_MISMATCH")
    edge = snapshot.get("apisix", {})
    for key, names in (("COUNT", {"shortlink_edge_pending_count"}),
                       ("BYTES", {"shortlink_edge_pending_bytes"})):
        values = _metric_values(edge, names)
        if edge.get("status") != "AVAILABLE" or not values:
            reasons.append("EDGE_PENDING_" + key + "_UNAVAILABLE")
        elif any(v != 0 for v in values):
            reasons.append("EDGE_PENDING_" + key + "_NOT_DRAINED")
    if _metric_values(edge, {"shortlink_edge_observation_complete"}) != [1.0]:
        reasons.append("EDGE_OBSERVATION_INCOMPLETE")
    if len(_metric_values(edge, {"shortlink_edge_instance_info"})) != 1:
        reasons.append("EDGE_INSTANCE_IDENTITY_UNAVAILABLE")
    if _metric_values(edge, {"shortlink_edge_observation_faults"}) != [0.0]:
        reasons.append("EDGE_OBSERVATION_FAULTS_UNKNOWN_OR_NONZERO")
    if len(_metric_values(edge, {"shortlink_edge_started_at_seconds"})) != 1:
        reasons.append("EDGE_START_TIME_UNAVAILABLE")
    if edge.get("pidChangedSinceStart") is True:
        reasons.append("EDGE_PROCESS_CHANGED_SINCE_START")
    counters = [_metric_values(edge, {"shortlink_edge_events_" + k})
                for k in ("attempted", "delivered", "failed", "rejected")]
    pending = _metric_values(edge, {"shortlink_edge_pending_count"})
    if any(len(values) != 1 for values in counters) or len(pending) != 1:
        reasons.append("EDGE_TERMINAL_COUNTERS_UNAVAILABLE")
    elif counters[0][0] != sum(values[0] for values in counters[1:]) + pending[0]:
        reasons.append("EDGE_COUNTER_MISMATCH")
    database = snapshot.get("mysql", {})
    if database.get("status") != "AVAILABLE":
        reasons.append("MYSQL_QUEUES_UNAVAILABLE")
    else:
        for name in ("outbox", "metadata"):
            if database.get("data", {}).get(name, {}).get("pending") != 0:
                reasons.append(name.upper() + "_NOT_DRAINED")
    intent_required = snapshot.get("metadataIntentObservationRequired", False)
    if type(intent_required) is not bool:
        reasons.append("INVALID_METADATA_INTENT_OBSERVATION_FLAG")
    elif intent_required:
        intent = metadata_intent(snapshot)
        if intent["status"] != "AVAILABLE":
            reasons.append(intent["reason"])
        elif intent["totalUnfinished"] != 0:
            reasons.append("METADATA_INTENT_NOT_DRAINED")
    for name in SERVICES:
        service = snapshot.get("services", {}).get(name, {})
        if service.get("status") != "AVAILABLE":
            reasons.append(name.upper() + "_METRICS_UNAVAILABLE")
        values = _metric_values(service, {"hikaricp_connections_pending"})
        if any(value != 0 for value in values):
            reasons.append(name.upper() + "_HIKARI_WAITERS")
        if name == "shortlink-redirect" and not values:
            reasons.append("REDIRECT_HIKARI_PENDING_UNAVAILABLE")
    if snapshot.get("adminTransportObservationRequired"):
        admin = snapshot.get("services", {}).get("admin", {})
        for metric_name in ("shortlink_admin_feign_pool_leased", "shortlink_admin_feign_pool_pending"):
            values = _metric_values(admin, {metric_name})
            if len(values) != 1:
                reasons.append(metric_name.upper() + "_UNAVAILABLE")
            elif values[0] != 0:
                reasons.append(metric_name.upper() + "_NOT_DRAINED")
    if snapshot.get("outboxObservationRequired"):
        command = snapshot.get("services", {}).get("shortlink-command", {})
        for metric_name in ("shortlink_outbox_inflight", "shortlink_outbox_send_pending", "shortlink_outbox_ack_pending"):
            values = _metric_values(command, {metric_name})
            if len(values) != 1:
                reasons.append(metric_name.upper() + "_UNAVAILABLE")
            elif values[0] != 0:
                reasons.append(metric_name.upper() + "_NOT_DRAINED")
    return {"drained": not reasons, "reasons": reasons,
            "meaning": "QUIESCENCE_ONLY_COMPARE_FAILURE_DELTAS_AND_LATENCY_SEPARATELY"}


def _write(folder, value):
    encoded = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    with (folder / "metrics.jsonl").open("a", encoding="utf-8") as target:
        target.write(encoded + "\n")
    temporary = folder / (".latest-metrics-" + str(os.getpid()) + ".tmp")
    temporary.write_text(encoded + "\n", encoding="utf-8")
    os.replace(temporary, folder / "latest-metrics.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", type=Path)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--once", action="store_true")
    modes.add_argument("--watch", action="store_true")
    parser.add_argument("--interval", type=float, default=5)
    parser.add_argument("--max-seconds", type=float, default=8 * 3600)
    args = parser.parse_args()
    if not sys.platform.startswith("linux"):
        parser.error("Use the dedicated Linux performance host")
    if not 5 <= args.interval <= 60 or not 1 <= args.max_seconds <= 8 * 3600:
        parser.error("interval must be 5..60 seconds; duration must be 1..28800 seconds")
    _, folder = _load(args.state)
    deadline = time.monotonic() + args.max_seconds
    while time.monotonic() < deadline and not (folder / "STOP_OBSERVER").exists():
        begin = time.monotonic()
        value = sample(args.state)
        _write(folder, value)
        if args.once:
            print(json.dumps({"runId": value["runId"], "sampleDurationMs": value["sampleDurationMs"],
                              "drain": value["drain"]}, ensure_ascii=False))
            return 0
        remaining = max(0, args.interval - (time.monotonic() - begin))
        until = min(deadline, time.monotonic() + remaining)
        while time.monotonic() < until and not (folder / "STOP_OBSERVER").exists():
            time.sleep(min(0.5, until - time.monotonic()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
