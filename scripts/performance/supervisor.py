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


def validate_redis_selection(container, port):
    if not isinstance(container, str) or len(container) > 128 or not (
            container == REDIS or re.fullmatch(r"shortlink-perf-redis-[a-z0-9-]+", container)):
        raise ValueError("Redis container must be the default or shortlink-perf-redis-[a-z0-9-]+, at most 128 characters")
    expected_port = REDIS_PORT if container == REDIS else 16380
    if type(port) is not int or port != expected_port:
        raise ValueError("Default Redis requires port 16379; alternate Redis requires port 16380")
    return container, port


def edge_worker_profile(workers):
    """Keep node queues fixed; eight workers also expand CPU affinity shared with Java."""
    if type(workers) is not int or workers not in (2, 4, 8):
        raise ValueError("EDGE_WORKERS_MUST_BE_2_4_OR_8")
    return {"edgeWorkers": workers, "edgeCpuSet": "0-7" if workers == 8 else "4-7",
            "edgeQueueCountPerWorker": 2000 // workers,
            "edgeQueueBytesPerWorker": (16 * 1024 * 1024) // workers,
            "edgeQueueCountNode": 2000, "edgeQueueBytesNode": 16 * 1024 * 1024,
            "edgeSendConcurrencyPerWorker": 4, "edgeSenderSlotsNode": 4 * workers,
            "edgeSendBatchSize": 32, "edgeSendBatchBytes": 65536}


def render_edge_worker_configuration(manifest, config, workers):
    """Pure, strict edits of the pinned source layout; no YAML dependency or I/O.

    Reject ambiguous blocks, duplicate keys and changed source values instead of
    silently patching a different rule. All unselected text, including APISIX
    environment placeholders, is preserved verbatim.
    """
    profile = edge_worker_profile(workers)
    if not isinstance(manifest, str) or not isinstance(config, str):
        raise ValueError("EDGE_CONFIGURATION_MUST_BE_TEXT")
    lines = manifest.splitlines(keepends=True)
    global_indices = [index for index, line in enumerate(lines)
                      if re.match(r"^global_rules[ \t]*:", line)]
    logger_indices = [index for index, line in enumerate(lines)
                      if re.fullmatch(r"[ \t]*shortlink-request-logger:[ \t]*(?:\r?\n)?", line)]
    logger_occurrences = re.findall(r"(?<![\w-])shortlink-request-logger[ \t]*:", manifest)
    if len(global_indices) != 1 or len(logger_indices) != 1 or len(logger_occurrences) != 1:
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
    expected = {"queue_count": 1000, "queue_bytes": 8388608, "max_event_bytes": 4096,
                "send_concurrency": 4, "send_batch_size": 32, "send_batch_bytes": 65536}
    replacements = {"queue_count": profile["edgeQueueCountPerWorker"],
                    "queue_bytes": profile["edgeQueueBytesPerWorker"]}
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
    nginx = [index for index, line in enumerate(config_lines)
             if re.match(r"^nginx_config[ \t]*:", line)]
    if len(nginx) != 1 or not re.fullmatch(r"nginx_config:[ \t]*\r?\n", config_lines[nginx[0]]) \
            or any(re.match(r"^[ \t]*worker_processes[ \t]*:", line) for line in config_lines):
        raise ValueError("EDGE_NGINX_WORKER_DIRECTIVE_AMBIGUOUS")
    index = nginx[0]
    ending = "\r\n" if config_lines[index].endswith("\r\n") else "\n"
    config_lines.insert(index + 1, "  worker_processes: " + str(workers) + ending)
    return "".join(lines), "".join(config_lines), profile


def serve(args):
    redis_container, redis_port = validate_redis_selection(args.redis_container, args.redis_port)
    if not args.allow_test_database:
        raise RuntimeError("Use --allow-test-database for the dedicated disposable MySQL fixture")
    if not sys.platform.startswith("linux"):
        raise RuntimeError("Run this supervisor inside the dedicated Linux test host")
    # Validate/render before creating a run directory, schema or any process.
    manifest, edge_config, edge_profile = render_edge_worker_configuration(
        (ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig"),
        (ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8"),
        getattr(args, "edge_workers", 2))
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
    redis_database = None
    for candidate in range(8, 16):
        value = command(["docker", "exec", redis_container, "redis-cli", "-n", str(candidate), "DBSIZE"]).stdout.strip()
        if value == "0":
            redis_database = candidate
            break
    if redis_database is None:
        raise RuntimeError("No empty isolated Redis database; refusing to flush existing data")
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
    # Test fixture is plaintext; transport correctness is unchanged in application code.
    env.pop("KAFKA_SECURITY_PROPERTIES", None)
    manifest_path = folder / "apisix.yaml"
    manifest_path.write_text(manifest, encoding="utf-8")
    edge_config_path = folder / "config.yaml"
    edge_config_path.write_text(edge_config, encoding="utf-8")
    container = "shortlink-perf-apisix-" + suffix
    processes, logs = [], []
    state = dict(runId=run_id, folder=str(folder), database=database, redisDatabase=redis_database,
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
                                                        "node total sender slots"],
                                       "isSingleVariableCpuExperiment":False,
                                       "nodeQueueBudgetHeldConstant":True,
                                       "cpuAffinityMeaning":"2/4 workers use 4-7; 8 workers use 0-7; Java remains 0-7, so CPU sets overlap"},
                 configurationId="local-diag-poll" + str(args.metadata_poll_millis) + "-workers" + str(args.metadata_workers),
                 resourceProfile={"javaHeapMaxMiB":384, "javaActiveProcessors":2,
                                  "javaCpuSet":"0-7", **edge_profile,"dependencyCpuSet":"8-11",
                                  "loadGeneratorCpuSet":"12-15", "wslLogicalProcessors":os.cpu_count(),
                                  "metadataWorkers":args.metadata_workers, "metadataPollDefaultMillis":1000,
                                  "commandPool":16,"idPool":2,"redirectPool":8,"adminPool":12,
                                  "kafkaReplicationFactor":1,"kafkaPartitions":2},
                 maximumCreatedRows=200000,
                 budgets={"minimumFreeDiskBytes":10*1024**3, "maxRuntimeSeconds":args.max_runtime_seconds})
    state_path = folder / "state.json"
    try:
        for dependency in (MYSQL, redis_container, KAFKA, "shortlink-refactor-it-minio-1"):
            command(["docker", "update", "--cpuset-cpus", "8-11", dependency])
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
            artifact = ROOT / module / "target" / filename
            stream = (folder / (module + ".log")).open("wb")
            logs.append(stream)
            proc = subprocess.Popen(["taskset", "-c", "0-7", "java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2",
                                     "-Dfile.encoding=UTF-8", "-jar", str(artifact),
                                     "--spring.profiles.active=production",
                                     "--spring.config.location=classpath:application-production.properties",
                                     "--spring.data.redis.password=",
                                     "--spring.data.redis.database=" + str(redis_database),
                                     "--shortlink.metadata.poll-millis=" + str(args.metadata_poll_millis),
                                     "--shortlink.metadata.workers=" + str(args.metadata_workers),
                                     "--shortlink.metadata.consumer-group=shortlink-metadata-perf-" + suffix],
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
                        help="Temporary APISIX workers; node queue 2000/16MiB, sender slots 8/16/32; 8 workers expand CPU affinity from 4-7 to 0-7 shared with Java")
    parser.add_argument("--redis-container", default=REDIS,
                        help="Already running default Redis, or shortlink-perf-redis-[a-z0-9-]+; never created or cleared")
    parser.add_argument("--redis-port", type=int, choices=(16379,16380), default=REDIS_PORT,
                        help="16379 for the default Redis container; 16380 for an alternate container")
    parser.add_argument("--campaign-profile", choices=("regression","peak"), default="regression",
                        help="Evidence label only; peak records metadata-peak-v7 without changing resources or protections")
    args = parser.parse_args(argv)
    try:
        validate_redis_selection(args.redis_container, args.redis_port)
    except ValueError as error:
        parser.error(str(error))
    return args


if __name__ == "__main__":
    serve(parse_args())
