"""Run the isolated, explicitly bounded local performance configuration.

Requires isolated MySQL/Redis/Kafka/MinIO containers that are already running.
Redis selection never starts, removes, or clears a Redis container or database.
Never starts Agent or Analytics. Each serve invocation creates a new test schema.
"""
import argparse
import datetime as dt
import hashlib
import http.client
import json
import os
import pathlib
import re
import socket
import subprocess
import sys
import time
import uuid

from kafka_probe_profile import inspect_actual_profile

ROOT = pathlib.Path(__file__).resolve().parents[2]
MYSQL = "shortlink-refactor-it-mysql-1"
REDIS = "shortlink-refactor-it-redis-1"
REDIS_PORT = 16379
KAFKA = "shortlink-refactor-it-kafka-1"
NETWORK = "shortlink-refactor-it_default"
MANAGEMENT_HOST = "admin.perf.test"
REDIRECT_HOST = "s.perf.test"
JARS = {
    "shortlink-command": ("shortlink-command-1.0-SNAPSHOT.jar", 8001, 8101),
    "admin": ("shortlink-admin.jar", 8002, 8102),
    "gateway": ("shortlink-gateway-1.0-SNAPSHOT.jar", 8000, 8100),
    "shortlink-redirect": ("shortlink-redirect-1.0-SNAPSHOT.jar", 8003, 8103),
}


def command(args, *, data=None, timeout=60, check=True):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, text=True)
    if check and result.returncode:
        raise RuntimeError("Command failed: " + " ".join(args[:4]) + "\n" + result.stderr[-1600:])
    return result


def sql(statement, database=None):
    args = ["docker", "exec", "-i", "-e", "MYSQL_PWD=shortlink-it-only", MYSQL,
            "mysql", "-uroot", "--batch", "--skip-column-names", "--default-character-set=utf8mb4"]
    if database:
        args.append(database)
    return command(args, data=statement, timeout=60).stdout


def request_http(port, path, headers=None, method="GET", body=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=8)
    try:
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        payload = response.read(1024 * 1024 + 1)
        if len(payload) > 1024 * 1024:
            raise RuntimeError("HTTP evidence exceeds budget")
        return response.status, dict(response.getheaders()), payload
    finally:
        conn.close()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def validate_redis_selection(container, port, database=None):
    if not isinstance(container, str) or len(container) > 128 or not (
            container == REDIS or re.fullmatch(r"shortlink-perf-redis-[a-z0-9-]+", container)):
        raise ValueError("Redis container must be the default or shortlink-perf-redis-[a-z0-9-]+, at most 128 characters")
    expected_port = (REDIS_PORT if container == REDIS else
                     16381 if container == "shortlink-perf-redis-20260908-breakthrough" else 16380)
    if type(port) is not int or port != expected_port:
        raise ValueError("Default Redis requires 16379; breakthrough Redis requires 16381; other alternate Redis requires 16380")
    if database is not None:
        if type(database) is not int or database not in range(1, 16):
            raise ValueError("Explicit Redis database must be a non-default integer from 1 to 15")
        if container == REDIS:
            raise ValueError("Explicit Redis database requires a dedicated shortlink-perf-redis-* instance")
    return container, port


def select_empty_redis_database(container, port, requested_database=None):
    """Read only: an explicit choice never falls back, and existing data is never cleared."""
    validate_redis_selection(container, port, requested_database)
    candidates = range(8, 16) if requested_database is None else (requested_database,)
    for candidate in candidates:
        value = command(["docker", "exec", container, "redis-cli", "-n", str(candidate), "DBSIZE"]).stdout.strip()
        if value == "0":
            return candidate
    if requested_database is not None:
        raise RuntimeError("Explicit Redis database is not confirmed empty; refusing to select another database or clear data")
    raise RuntimeError("No empty isolated Redis database; refusing to flush existing data")


def edge_worker_profile(workers, send_concurrency=1):
    """Keep node queues fixed; eight workers also expand CPU affinity shared with Java."""
    if type(workers) is not int or workers not in (2, 4, 8):
        raise ValueError("EDGE_WORKERS_MUST_BE_2_4_OR_8")
    if type(send_concurrency) is not int or send_concurrency not in (1, 2, 4):
        raise ValueError("EDGE_SEND_CONCURRENCY_MUST_BE_1_2_OR_4")
    return {"edgeWorkers": workers, "edgeCpuSet": "0-7" if workers == 8 else "4-7",
            "edgeQueueCountPerWorker": 2000 // workers,
            "edgeQueueBytesPerWorker": (16 * 1024 * 1024) // workers,
            "edgeQueueCountNode": 2000, "edgeQueueBytesNode": 16 * 1024 * 1024,
            "edgeSendConcurrencyPerWorker": send_concurrency, "edgeSenderSlotsNode": send_concurrency * workers,
            "edgeSendBatchSize": 32, "edgeSendBatchBytes": 65536}


def validate_application_cpu_selection(workers, cpu_set):
    if type(workers) is not int or workers not in (2, 4, 8):
        raise ValueError("EDGE_WORKERS_MUST_BE_2_4_OR_8")
    if type(cpu_set) is not str or cpu_set not in ("0-3", "0-7", "0-11"):
        raise ValueError("APPLICATION_CPU_SET_MUST_BE_0_3_OR_0_7_OR_0_11")
    if cpu_set != "0-7" and workers != 8:
        raise ValueError("APPLICATION_CPU_SET_" + cpu_set.replace("-", "_") + "_REQUIRES_8_EDGE_WORKERS")
    return cpu_set


def application_cpu_profile(edge_profile, cpu_set="0-7"):
    """Pure affinity selection; keep all queue, sender and worker budgets intact."""
    cpu_set = validate_application_cpu_selection(edge_profile["edgeWorkers"], cpu_set)
    result = dict(edge_profile, applicationCpuSet=cpu_set, javaCpuSet=cpu_set,
                   applicationDependencyCpuOverlap="8-11" if cpu_set == "0-11" else None)
    if cpu_set == "0-11":
        result["edgeCpuSet"] = cpu_set
    elif cpu_set == "0-3":
        result["edgeCpuSet"] = "4-7"
    result["applicationCpuUnion"] = "0-11" if cpu_set == "0-11" else "0-7"
    result["javaEdgeCpuOverlap"] = None if cpu_set == "0-3" else result["edgeCpuSet"]
    return result


def validate_edge_cpu_selection(workers, application_cpu_set, cpu_set):
    if cpu_set is None:
        return None
    if type(cpu_set) is not str or cpu_set not in ("4-7", "0-7"):
        raise ValueError("EDGE_CPU_SET_MUST_BE_4_7_OR_0_7")
    if type(workers) is not int or workers != 4 or application_cpu_set != "0-7":
        raise ValueError("EDGE_CPU_SET_REQUIRES_4_WORKERS_AND_APPLICATION_CPU_SET_0_7")
    return cpu_set


def edge_cpu_profile(profile, cpu_set=None):
    """Explicit four-worker edge affinity within the unchanged application union."""
    selected = validate_edge_cpu_selection(profile["edgeWorkers"], profile["javaCpuSet"], cpu_set)
    result = dict(profile)
    if selected is not None:
        result.update(edgeCpuSet=selected, javaEdgeCpuOverlap=selected)
    return result


def render_http_timing_configuration(manifest, enabled):
    if type(enabled) is not bool:
        raise ValueError("HTTP_TIMING_OPTION_MUST_BE_BOOLEAN")
    if not enabled:
        return manifest
    needle = "      shortlink-request-logger:\n"
    if manifest.count(needle) != 1 or re.search(r"\bhttp_timing_enabled\s*:", manifest):
        raise ValueError("HTTP_TIMING_CONFIGURATION_AMBIGUOUS")
    return manifest.replace(needle, needle + "        http_timing_enabled: true\n", 1)


def render_execution_diagnostics(manifest, config, enabled):
    """Opt-in request correlation and sender timings; preserve the default log prefix.

    One existing access record is extended, so source/accounting evidence keeps
    the same status/Host/User-Agent fields and no second per-request log is added.
    Java timings are sampled wall time, never CPU time or full upstream time.
    """
    if type(enabled) is not bool:
        raise ValueError("EXECUTION_DIAGNOSTICS_OPTION_MUST_BE_BOOLEAN")
    if not enabled:
        return manifest, config
    lines, logger, stop = _edge_global_logger_lines(manifest)
    if re.search(r"\bexecution_diagnostics\s*:", manifest):
        raise ValueError("EXECUTION_DIAGNOSTICS_ALREADY_CONFIGURED")
    lines.insert(logger + 1, "        execution_diagnostics: true\n")
    if config.count("  http:\n") != 1 or re.search(r"\baccess_log_format\s*:", config):
        raise ValueError("EXECUTION_ACCESS_LOG_CONFIGURATION_AMBIGUOUS")
    # Verified against apache/apisix:3.11.0-debian apisix/cli/config.lua.
    prefix = ('$remote_addr - $remote_user [$time_local] $http_host "$request" '
              '$status $body_bytes_sent $request_time "$http_referer" "$http_user_agent" '
              '$upstream_addr $upstream_status $upstream_response_time '
              '"$upstream_scheme://$upstream_host$upstream_uri"')
    extra = (' SLTRACE msec=$msec pid=$pid peerport=$remote_port conn=$connection '
             'seq=$connection_requests id=$sent_http_x_request_id '
             'upstream_id=$upstream_http_x_request_id '
             'java_ns=$upstream_http_x_shortlink_handler_nanos '
             'uct=$upstream_connect_time uht=$upstream_header_time')
    return "".join(lines), config.replace("  http:\n", "  http:\n    access_log_format: '" + prefix + extra + "'\n", 1)


def validate_edge_linger_millis(millis):
    if type(millis) is not int or millis not in (0, 1, 2, 3, 4, 5, 10):
        raise ValueError("EDGE_SEND_LINGER_MILLIS_MUST_BE_INTEGER_0_TO_5_OR_10")
    return millis


def _edge_global_logger_lines(manifest):
    """Locate the one literal global logger; aliases/merges cannot hide keys."""
    if not isinstance(manifest, str):
        raise ValueError("EDGE_CONFIGURATION_MUST_BE_TEXT")
    lines = manifest.splitlines(keepends=True)
    active = "\n".join(line.split("#", 1)[0] for line in lines)
    if re.search(r"(?<![\w-])<<[\"']?[ \t]*:|:[ \t]*[&*]", active):
        raise ValueError("EDGE_CONFIGURATION_ALIASES_UNSUPPORTED")
    global_indices = [index for index, line in enumerate(lines)
                      if re.match(r"^global_rules[ \t]*:", line)]
    logger_indices = [index for index, line in enumerate(lines)
                      if re.fullmatch(r"[ \t]*shortlink-request-logger:[ \t]*(?:\r?\n)?", line)]
    logger_occurrences = re.findall(r"(?<![\w-])shortlink-request-logger[\"']?[ \t]*:", active)
    global_occurrences = re.findall(r"(?<![\w-])global_rules[\"']?[ \t]*:", active)
    if len(global_indices) != 1 or len(global_occurrences) != 1 \
            or len(logger_indices) != 1 or len(logger_occurrences) != 1:
        raise ValueError("EDGE_GLOBAL_LOGGER_NOT_UNIQUE")
    start, logger = global_indices[0], logger_indices[0]
    if not re.fullmatch(r"global_rules:[ \t]*(?:\r?\n)?", lines[start]):
        raise ValueError("EDGE_GLOBAL_RULES_LAYOUT_CHANGED")
    stop = len(lines)
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line.strip() and not line.lstrip().startswith("#") and not line[0].isspace():
            stop = index
            break
    if not start < logger < stop or not re.fullmatch(r" {6}shortlink-request-logger:[ \t]*(?:\r?\n)?", lines[logger]):
        raise ValueError("EDGE_LOGGER_OUTSIDE_EXPECTED_GLOBAL_BLOCK")
    logger_stop = stop
    for index in range(logger + 1, stop):
        line = lines[index]
        if line.strip() and not line.lstrip().startswith("#"):
            if "\t" in line[:len(line) - len(line.lstrip())]:
                raise ValueError("EDGE_LOGGER_INDENTATION_CHANGED")
            if len(line) - len(line.lstrip(" ")) <= 6:
                logger_stop = index
                break
    return lines, logger, logger_stop


def render_edge_linger_configuration(manifest, millis):
    """Override one literal 0..5 or 10 field; absent legacy zero stays absent.

    CLI and legacy callers of serve default to the deployed 5ms setting. An
    explicit zero replaces a deployed nonzero value; it never inherits that wait.
    """
    validate_edge_linger_millis(millis)
    lines, logger, logger_stop = _edge_global_logger_lines(manifest)
    positions = []
    for index, line in enumerate(lines):
        active = line.split("#", 1)[0]
        if re.search(r"(?<![\w-])send_batch_linger_ms(?![\w-])", active):
            raise ValueError("EDGE_SEND_LINGER_ALIAS_UNSUPPORTED")
        if re.search(r"(?<![\w-])send_linger_ms(?![\w-])", active):
            positions.append(index)
    if len(positions) > 1:
        raise ValueError("EDGE_SEND_LINGER_KEY_NOT_UNIQUE")
    if positions:
        index = positions[0]
        match = re.fullmatch(r"( {8}send_linger_ms:[ \t]+)(10|[0-5])([ \t]*(?:#[^\r\n]*)?)(\r?\n)?", lines[index])
        if not logger < index < logger_stop or match is None:
            raise ValueError("EDGE_SEND_LINGER_SOURCE_VALUE_CHANGED")
        lines[index] = match[1] + str(millis) + match[3] + (match[4] or "")
    elif millis:
        ending = "\r\n" if lines[logger].endswith("\r\n") else "\n"
        lines.insert(logger + 1, "        send_linger_ms: " + str(millis) + ending)
    return "".join(lines)


def render_edge_worker_configuration(manifest, config, workers, send_concurrency=1):
    """Pure strict edits; preserve all unselected source bytes and comments."""
    profile = edge_worker_profile(workers, send_concurrency)
    if not isinstance(config, str):
        raise ValueError("EDGE_CONFIGURATION_MUST_BE_TEXT")
    lines, logger, logger_stop = _edge_global_logger_lines(manifest)
    expected = {"queue_count": 1000, "queue_bytes": 8388608, "max_event_bytes": 4096,
                "send_concurrency": 1, "send_batch_size": 32, "send_batch_bytes": 65536}
    replacements = {"queue_count": profile["edgeQueueCountPerWorker"],
                    "queue_bytes": profile["edgeQueueBytesPerWorker"],
                    "send_concurrency": profile["edgeSendConcurrencyPerWorker"]}
    for key, source_value in expected.items():
        positions = [index for index in range(logger + 1, logger_stop)
                     if re.match(r"^[ \t]*" + re.escape(key) + r"[ \t]*:", lines[index])]
        if len(positions) != 1:
            raise ValueError("EDGE_LOGGER_KEY_NOT_UNIQUE:" + key)
        index = positions[0]
        match = re.fullmatch(r"( {8}" + re.escape(key) + r":[ \t]+)([0-9]+)([ \t]*(?:#[^\r\n]*)?)(\r?\n)?", lines[index])
        if match is None or match[2] != str(source_value):
            raise ValueError("EDGE_LOGGER_SOURCE_VALUE_CHANGED:" + key)
        if key in replacements:
            lines[index] = match[1] + str(replacements[key]) + match[3] + (match[4] or "")
    config_lines = config.splitlines(keepends=True)
    active_lines = [line.split("#", 1)[0] for line in config_lines]
    active = "\n".join(active_lines)
    if re.search(r"(?<![\w-])<<[\"']?[ \t]*:|(?:^|[ \t:{,\[])[&*][\w-]+", active, re.MULTILINE):
        raise ValueError("EDGE_NGINX_ALIASES_UNSUPPORTED")
    nginx = [index for index, line in enumerate(config_lines)
             if re.fullmatch(r"nginx_config:[ \t]*(?:\r?\n)?", line)]
    occurrences = re.findall(r"(?<![\w-])nginx_config[\"']?[ \t]*:", active)
    worker_positions = [index for index, line in enumerate(active_lines)
                        if re.search(r"(?<![\w-])worker_processes(?![\w-])", line)]
    if len(nginx) != 1 or len(occurrences) != 1 or len(worker_positions) != 1:
        raise ValueError("EDGE_NGINX_WORKER_DIRECTIVE_AMBIGUOUS")
    start = nginx[0]
    stop = len(config_lines)
    for index in range(start + 1, len(config_lines)):
        line = config_lines[index]
        if line.strip() and not line.lstrip().startswith("#") and not line[0].isspace():
            stop = index
            break
    index = worker_positions[0]
    match = re.fullmatch(r"( {2}worker_processes:[ \t]+)(2)([ \t]*(?:#[^\r\n]*)?)(\r?\n)?", config_lines[index])
    if not start < index < stop or match is None:
        raise ValueError("EDGE_NGINX_WORKER_SOURCE_VALUE_CHANGED")
    config_lines[index] = match[1] + str(workers) + match[3] + (match[4] or "")
    return "".join(lines), "".join(config_lines), profile


def serve(args):
    # Reject invalid optional affinity before even reading deployment files.
    linger_millis = validate_edge_linger_millis(getattr(args, "edge_send_linger_millis", 5))
    application_cpu_set = validate_application_cpu_selection(
        getattr(args, "edge_workers", 2), getattr(args, "application_cpu_set", "0-7"))
    selected_edge_cpu_set = validate_edge_cpu_selection(
        getattr(args, "edge_workers", 2), application_cpu_set, getattr(args, "edge_cpu_set", None))
    requested_redis_database = getattr(args, "redis_database", None)
    redis_container, redis_port = validate_redis_selection(
        args.redis_container, args.redis_port, requested_redis_database)
    if not args.allow_test_database:
        raise RuntimeError("Use --allow-test-database for the dedicated disposable MySQL fixture")
    if not sys.platform.startswith("linux"):
        raise RuntimeError("Run this supervisor inside the dedicated Linux test host")
    # Validate/render before creating a run directory, schema or any process.
    manifest, edge_config, edge_profile = render_edge_worker_configuration(
        (ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig"),
        (ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8"),
        getattr(args, "edge_workers", 2), getattr(args, "edge_send_concurrency", 1))
    edge_profile = application_cpu_profile(edge_profile, application_cpu_set)
    edge_profile = edge_cpu_profile(edge_profile, selected_edge_cpu_set)
    manifest = render_edge_linger_configuration(manifest, linger_millis)
    edge_profile["edgeSendLingerMillis"] = linger_millis
    manifest = render_http_timing_configuration(manifest, getattr(args, "http_timings", False))
    manifest, edge_config = render_execution_diagnostics(
        manifest, edge_config, getattr(args, "execution_diagnostics", False))
    run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    folder = ROOT / ".work/performance" / run_id
    folder.mkdir(parents=True)
    private_dir = pathlib.Path("/var/lib/shortlink-perf") / run_id
    private_dir.mkdir(parents=True, mode=0o700)
    os.chmod(private_dir, 0o700)
    database = "shortlink_perf_" + uuid.uuid4().hex[:10]
    assert re.fullmatch(r"shortlink_perf_[a-f0-9]{10}", database)
    for _, port, management in JARS.values():
        for candidate in (port, management):
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", candidate)) == 0:
                    raise RuntimeError("Owned test port already occupied: " + str(candidate))
    deadline = time.monotonic() + 60
    while True:
        try:
            sql("SELECT 1;")
            break
        except Exception:
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)
    redis_database = select_empty_redis_database(redis_container, redis_port, requested_redis_database)
    sql("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;")
    sql((ROOT / "deploy/mysql/001-business-schema.sql").read_text(encoding="utf-8-sig"), database)
    suffix = uuid.uuid4().hex[:8]
    app_user, redirect_user = "sl_e2e_app_" + suffix, "sl_e2e_read_" + suffix
    password = uuid.uuid4().hex
    sql(f"CREATE USER '{app_user}'@'%' IDENTIFIED BY '{password}';\n"
        f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{database}`.* TO '{app_user}'@'%';\n"
        f"CREATE USER '{redirect_user}'@'%' IDENTIFIED BY '{password}';\n"
        f"GRANT SELECT ON `{database}`.* TO '{redirect_user}'@'%';\n"
        f"GRANT UPDATE ON `{database}`.t_cache_generation TO '{redirect_user}'@'%';")
    token = "e2e-internal-" + uuid.uuid4().hex
    jdbc = f"jdbc:mysql://127.0.0.1:13306/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    env = dict(os.environ, BUSINESS_DB_URL=jdbc, BUSINESS_DB_CATALOG=database,
               BUSINESS_DB_USERNAME=app_user, BUSINESS_DB_PASSWORD=password,
               REDIRECT_DB_USERNAME=redirect_user, REDIRECT_DB_PASSWORD=password,
               INTERNAL_TOKEN=token, AGENT_INTERNAL_TOKEN="unused-agent-" + uuid.uuid4().hex,
               AGENT_SYSTEM_USERNAME="unused-e2e-agent", AGENT_URL="http://127.0.0.1:1",
               ANALYTICS_URL="http://127.0.0.1:1", ACCOUNT_PII_KEY="a1" * 16,
               SHORTCODE_FIXED_KEY_HEX="23" * 32, SHORTLINK_DEFAULT_DOMAIN=REDIRECT_HOST,
               SHORTLINK_ALLOWED_DOMAINS=REDIRECT_HOST, ADMIN_ALLOWED_HOSTS=MANAGEMENT_HOST,
               REDIS_HOST="127.0.0.1", REDIS_PORT=str(redis_port), REDIS_PASSWORD="",
               COMMAND_URL="http://127.0.0.1:8001", ADMIN_URL="http://127.0.0.1:8002",
               KAFKA_BOOTSTRAP_SERVERS="localhost:19092", ANALYTICS_HASH_KEY="4b" * 32,
               REDIRECT_INSTANCE_ID="perf-" + run_id, OBJECT_ENDPOINT="http://127.0.0.1:19000",
               IMPORT_ACCESS_KEY="shortlink-it", IMPORT_SECRET_KEY="shortlink-it-only",
               IMPORT_BUCKET="shortlink-create-redirect-e2e")
    env["SHORTLINK_REDIRECT_FUNCTIONAL_ROUTING_ENABLED"] = str(getattr(args, "redirect_functional_routing", False)).lower()
    env["SHORTLINK_REDIRECT_REQUEST_TIMING_ENABLED"] = str(getattr(args, "execution_diagnostics", False)).lower()
    # Test fixture is plaintext; transport correctness is unchanged in application code.
    env.pop("KAFKA_SECURITY_PROPERTIES", None)
    manifest_path = folder / "apisix.yaml"
    manifest_path.write_text(manifest, encoding="utf-8")
    edge_config_path = folder / "config.yaml"
    edge_config_path.write_text(edge_config, encoding="utf-8")
    container = "shortlink-perf-apisix-" + suffix
    processes, logs = [], []
    state = dict(runId=run_id, folder=str(folder), database=database, redisDatabase=redis_database,
                 redisDatabaseRequested=requested_redis_database,
                 redisDatabaseSelection="explicit-empty" if requested_redis_database is not None else "automatic-empty-8-15",
                 apisix=container, managementHost=MANAGEMENT_HOST, redirectHost=REDIRECT_HOST,
                 baseUrl="http://127.0.0.1:19080", producerInstanceId=env["REDIRECT_INSTANCE_ID"],
                 phase="STARTING", topology="APISIX standalone + four real production JARs",
                 agentsStarted=False, analyticsStarted=False, jvmArtifacts={},
                 mysqlContainer=MYSQL, redisContainer=redis_container, redisPort=redis_port,
                 kafkaContainer=KAFKA,
                 privateDir=str(private_dir), observerSecret=str(private_dir / "observer-secret.json"),
                 fixturePath=str(private_dir / "fixture.json"),
                 metadataMode="local-host-policy-rejection", metadataPollMillis=args.metadata_poll_millis,
                 outboxObservationRequired=True,
                 adminTransportObservationRequired=True,
                 metadataIntentObservationRequired=True,
                 pipelineFailureObservationRequired=True,
                 publisherDiagnosticObservationRequired=getattr(args, "publisher_diagnostics_required", False),
                 edgeHttpTimingEnabled=getattr(args, "http_timings", False),
                 executionDiagnosticsEnabled=getattr(args, "execution_diagnostics", False),
                 redirectFunctionalRoutingEnabled=getattr(args, "redirect_functional_routing", False),
                 edgeSnapshotVersionRequired=3,
                 edgeCpuSetRequested=selected_edge_cpu_set,
                 edgeObservationContract="worker-record-v3-with-raw-global-reconciliation",
                 campaignProfile=args.campaign_profile,
                 optimizationProfile=("metadata-peak-v7" if args.campaign_profile == "peak" else
                                      "metadata-fenced-sql-workers" + str(args.metadata_workers) + "-v6"),
                 outboxProfile={"pollMillis":25, "batchSize":64, "maxInFlight":64,
                                "sendWorkers":4, "ackWorkers":4, "ackBatchSize":16, "ackBatchLingerMillis":2,
                                "ackTimeoutMillis":12000, "leaseMillis":30000,
                                "claimIsolation":"READ_COMMITTED", "claimPropagation":"REQUIRES_NEW"},
                 metadataPipelineProfile={"intakeMaxRecords":16, "intakeMaxEventBytes":65536,
                                          "intakeCommit":"REQUIRES_NEW before Kafka offset commit",
                                          "intakeTransactionTimeoutSeconds":5, "workers":args.metadata_workers,
                                          "workerQuantumMaxJobs":64, "workerQuantumBudgetMillis":1000,
                                          "quantumBudgetBoundary":"between tasks; not a hard timeout for an in-progress fetch",
                                          "workerTransactions":"REQUIRES_NEW READ_COMMITTED; commit included in execution timers",
                                          "claimSql":"locked row then database-clock conditional update",
                                          "terminalSql":"locked fence and database-clock conditional final update",
                                          "executionMetricOperations":["candidates", "claim", "apply", "retry"],
                                          "executionMetricOutcomes":["success", "empty", "obsolete", "stale", "failed"],
                                          "idlePollMillis":args.metadata_poll_millis},
                 adminTransportProfile={"client":"Feign ApacheHttp5Client", "maxTotal":32,
                                        "maxPerRoute":32, "leaseTimeoutMillis":250,
                                        "connectTimeoutMillis":1000, "readTimeoutMillis":5000,
                                        "keepAliveMillis":1000, "idleEvictMillis":1000,
                                        "validateAfterMillis":200, "ttlMillis":30000,
                                        "automaticRetries":False, "followsRedirects":False},
                 adminIngressProfile={"keepAliveTimeoutMillis":15000, "maxKeepAliveRequests":100,
                                      "connectionTimeoutMillis":2000, "uploadTimeoutMillis":5000,
                                      "maxConnections":256, "maxThreads":64, "acceptCount":32},
                 edgeWorkerComparison={"variesTogether":["worker count", "edge CPU affinity and overlap with Java",
                                                         "per-worker queue count/bytes",
                                                         "node total sender slots (worker count times selected per-worker concurrency)"],
                                       "isSingleVariableCpuExperiment":False,
                                       "nodeQueueBudgetHeldConstant":True,
                                        "cpuAffinityMeaning":"Java uses " + edge_profile["javaCpuSet"] +
                                            "; APISIX uses " + edge_profile["edgeCpuSet"] +
                                            "; Java/APISIX overlap: " + (edge_profile["javaEdgeCpuOverlap"] or "none") +
                                            "; application CPU union: " + edge_profile["applicationCpuUnion"] +
                                            "; dependencies remain 8-11; application/dependency overlap: " +
                                           (edge_profile["applicationDependencyCpuOverlap"] or "none")},
                 configurationId="local-diag-poll" + str(args.metadata_poll_millis) + "-workers" + str(args.metadata_workers),
                 resourceProfile={"javaHeapMaxMiB":384, "javaActiveProcessors":2,
                                  "javaCpuSet":edge_profile.get("javaCpuSet", "0-7"), **edge_profile,"dependencyCpuSet":"8-11",
                                  "loadGeneratorCpuSet":"12-15", "wslLogicalProcessors":os.cpu_count(),
                                  "metadataWorkers":args.metadata_workers, "metadataPollDefaultMillis":1000,
                                  "commandPool":16,"idPool":2,"redirectPool":8,"adminPool":12,
                                  "kafkaReplicationFactor":1,"kafkaPartitions":2},
                 maximumCreatedRows=200000,
                 budgets={"minimumFreeDiskBytes":10*1024**3, "maxRuntimeSeconds":args.max_runtime_seconds})
    if selected_edge_cpu_set is not None:
        state["edgeWorkerComparison"].update(
            variesTogether=["APISIX CPU affinity and overlap with Java"],
            explicitEdgeCpuSet=selected_edge_cpu_set,
            comparisonMeaning="Explicit edge affinity keeps four workers and application CPU union 0-7; causal comparison requires other independently selected settings to match")
    state_path = folder / "state.json"
    try:
        for dependency in (MYSQL, redis_container, KAFKA, "shortlink-refactor-it-minio-1"):
            command(["docker", "update", "--cpuset-cpus", "8-11", dependency])
        # Inspect the running dependency before opening any business listener.
        # Failure is fixed-code and follows this run's existing cleanup path.
        state["kafkaHealthProbeProfile"] = inspect_actual_profile(KAFKA)
        command(["docker", "run", "-d", "--name", container, "--network", NETWORK,
                 "--cpuset-cpus", edge_profile["edgeCpuSet"], "--memory", "1g",
                 "--add-host", "host.docker.internal:host-gateway", "-p", "127.0.0.1:19080:9080",
                 "-e", "KAFKA_HOST=kafka", "-e", "APISIX_INSTANCE_ID=e2e-edge-" + suffix,
                 "-e", "MANAGEMENT_HOST=" + MANAGEMENT_HOST, "-e", "SHORTLINK_HOST=" + REDIRECT_HOST,
                 "-e", "GATEWAY_UPSTREAM_HOST=host.docker.internal", "-e", "REDIRECT_UPSTREAM_HOST=host.docker.internal",
                 "-v", str(edge_config_path) + ":/usr/local/apisix/conf/config.yaml:ro",
                 "-v", str(manifest_path) + ":/usr/local/apisix/conf/apisix.yaml:ro",
                 "-v", str(ROOT / "deploy/apisix/plugins") + ":/opt/shortlink:ro", "apache/apisix:3.11.0-debian"])
        info = json.loads(command(["docker", "inspect", container]).stdout)[0]
        node = info["NetworkSettings"]["Networks"][NETWORK]
        env["APISIX_CIDRS"] = node["IPAddress"] + "/32"
        state.update(apisixIp=node["IPAddress"], networkGateway=node["Gateway"],
                     apisixPid=info["State"]["Pid"], manifest=str(manifest_path))
        write_json(private_dir / "observer-secret.json", {"internalToken": token,
                   "mysqlUser":"root", "mysqlPassword":"shortlink-it-only"})
        os.chmod(private_dir / "observer-secret.json", 0o600)
        group = "shortlink-metadata-perf-" + suffix
        cut = command(["docker", "exec", KAFKA, "/opt/kafka/bin/kafka-consumer-groups.sh",
                       "--bootstrap-server", "localhost:9092", "--group", group,
                       "--reset-offsets", "--to-latest", "--topic", "shortlink.metadata.fetch.v1", "--execute"], timeout=30)
        (folder / "metadata-start-offsets.log").write_text(cut.stdout + cut.stderr, encoding="utf-8")
        state["metadataConsumerGroup"] = group
        for module, (filename, port, management) in JARS.items():
            artifact = ROOT / "services" / module / "target" / filename
            stream = (folder / (module + ".log")).open("wb")
            logs.append(stream)
            proc = subprocess.Popen(["taskset", "-c", edge_profile["javaCpuSet"], "java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2",
                                     "-Dfile.encoding=UTF-8", "-jar", str(artifact),
                                     "--spring.profiles.active=production",
                                     "--spring.config.location=classpath:application-production.properties",
                                     "--spring.data.redis.password=",
                                     "--spring.data.redis.database=" + str(redis_database),
                                     "--shortlink.metadata.poll-millis=" + str(args.metadata_poll_millis),
                                     "--shortlink.metadata.workers=" + str(args.metadata_workers),
                                     "--shortlink.metadata.consumer-group=shortlink-metadata-perf-" + suffix,
                                     "--shortlink.redirect.functional-routing-enabled=" + env["SHORTLINK_REDIRECT_FUNCTIONAL_ROUTING_ENABLED"]],
                                    env=env, stdout=stream, stderr=subprocess.STDOUT)
            processes.append(proc)
            deadline = time.monotonic() + 100
            while True:
                if proc.poll() is not None:
                    raise RuntimeError(module + " stopped during startup; see application log")
                try:
                    status, _, body = request_http(management, "/actuator/health")
                    if status == 200 and json.loads(body).get("status") == "UP":
                        break
                except (OSError, ValueError, http.client.HTTPException):
                    pass
                if time.monotonic() > deadline:
                    raise RuntimeError(module + " did not become fully healthy")
                time.sleep(1)
            state["jvmArtifacts"][module] = dict(pid=proc.pid, businessPort=port, healthPort=management,
                                               sha256=hashlib.sha256(artifact.read_bytes()).hexdigest())
            write_json(state_path, state)
            print(module + " health=UP", flush=True)
        state["phase"] = "READY"
        write_json(state_path, state)
        (ROOT / ".work/performance/latest-state.txt").write_text(str(state_path), encoding="utf-8")
        print("READY " + str(state_path), flush=True)
        deadline = time.monotonic() + args.max_runtime_seconds
        while time.monotonic() < deadline and not (folder / "STOP").exists():
            if any(proc.poll() is not None for proc in processes):
                raise RuntimeError("An owned Java service exited")
            time.sleep(1)
    except BaseException as error:
        state["error"] = str(error)
        state["phase"] = "FAILED"
        write_json(state_path, state)
        raise
    finally:
        for proc in reversed(processes):
            if proc.poll() is None:
                proc.terminate()
        for proc in reversed(processes):
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        edge_log = command(["docker", "logs", container], check=False)
        (folder / "apisix.log").write_text(edge_log.stdout + edge_log.stderr, encoding="utf-8")
        command(["docker", "rm", "-f", container], check=False)
        for stream in logs:
            stream.close()
        state["servicesStopped"] = True
        if state["phase"] != "FAILED":
            state["phase"] = "STOPPED"
        # freeze() may persist stricter run budgets after this process reached READY.
        # Preserve those effective values without accepting an external lifecycle state.
        try:
            recorded = json.loads(state_path.read_text(encoding="utf-8-sig"))
            if recorded.get("runId") == run_id:
                for key in ("maximumCreatedRows", "budgets"):
                    if key in recorded:
                        state[key] = recorded[key]
        except (OSError, ValueError):
            pass
        write_json(state_path, state)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-test-database", action="store_true")
    parser.add_argument("--max-runtime-seconds", type=int, default=28800)
    parser.add_argument("--metadata-poll-millis", type=int, choices=(50,1000), default=50)
    parser.add_argument("--metadata-workers", type=int, choices=(4,8), default=4,
                        help="Explicit bounded concurrency comparison; production defaults remain unchanged")
    parser.add_argument("--edge-workers", type=int, choices=(2,4,8), default=2,
                        help="Temporary APISIX workers; node queue 2000/16MiB; 8 workers expand CPU affinity from 4-7 to 0-7 shared with Java")
    parser.add_argument("--application-cpu-set", choices=("0-3", "0-7", "0-11"), default="0-7",
                        help="0-3 requires 8 edge workers: Java 0-3 and APISIX 4-7 with unchanged union 0-7; 0-11 requires 8 workers and overlaps dependencies on 8-11; default preserves legacy affinity")
    parser.add_argument("--edge-cpu-set", choices=("4-7", "0-7"), default=None,
                        help="Explicit APISIX affinity only for 4 workers with Java/application 0-7; application CPU union stays 0-7; omitted preserves legacy worker-dependent affinity")
    parser.add_argument("--edge-send-concurrency", type=int, choices=(1,2,4), default=1,
                        help="Independent temporary sender slots per worker; total slots = workers times concurrency; CPU, queues and batch limits unchanged")
    parser.add_argument("--edge-send-linger-millis", type=int, choices=(0,1,2,3,4,5,10), default=5,
                        help="0..5ms or 10ms small-batch timer target; default 5ms matches deployment, explicit 0 disables waiting; CPU, sender and queue budgets unchanged")
    parser.add_argument("--http-timings", action="store_true",
                        help="Optional worker-local GET/302 request/upstream timings; retain fixed bounded labels")
    parser.add_argument("--execution-diagnostics", action="store_true",
                        help="Optional sender phase timings, correlated access log, and 1/64 Java handler wall-time sample")
    parser.add_argument("--publisher-diagnostics-required", action="store_true",
                        help="Require the fixed Publisher diagnostic schema for this newly frozen run")
    parser.add_argument("--redirect-functional-routing", action="store_true",
                        help="Use the optional public Redirect functional route; keep all business and filter contracts")
    parser.add_argument("--redis-container", default=REDIS,
                        help="Already running default Redis, or shortlink-perf-redis-[a-z0-9-]+; never created or cleared")
    parser.add_argument("--redis-port", type=int, choices=(16379,16380,16381), default=REDIS_PORT,
                        help="16379 for default Redis; 16380 for alternate Redis; 16381 for the isolated breakthrough Redis")
    parser.add_argument("--redis-database", type=int, choices=range(1, 16), default=None,
                        help="Optional empty non-default DB 1..15 on a dedicated shortlink-perf-redis-* instance only; omitted selects first empty DB 8..15; never clears data")
    parser.add_argument("--campaign-profile", choices=("regression","peak"), default="regression",
                        help="Evidence label only; peak records metadata-peak-v7 without changing resources or protections")
    args = parser.parse_args(argv)
    try:
        validate_redis_selection(args.redis_container, args.redis_port, args.redis_database)
        validate_application_cpu_selection(args.edge_workers, args.application_cpu_set)
        validate_edge_cpu_selection(args.edge_workers, args.application_cpu_set, args.edge_cpu_set)
    except ValueError as error:
        parser.error(str(error))
    return args


if __name__ == "__main__":
    serve(parse_args())
