"""Run real production JARs and an APISIX data plane in the dedicated Linux test host.

Requires already-running isolated MySQL/Redis/Kafka/MinIO dependencies.
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
KAFKA = "shortlink-refactor-it-kafka-1"
NETWORK = "shortlink-refactor-it_default"
MANAGEMENT_HOST = "admin.e2e.test"
REDIRECT_HOST = "s.e2e.test"
JARS = {
    "shortlink-command": ("shortlink-command-1.0-SNAPSHOT.jar", 8001, 8101),
    "admin": ("shortlink-admin.jar", 8002, 8102),
    "shortlink-redirect": ("shortlink-redirect-1.0-SNAPSHOT.jar", 8003, 8103),
}


def command(args, *, data=None, timeout=60, check=True):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, text=True)
    if check and result.returncode:
        raise RuntimeError("Command failed: " + " ".join(args[:4]) + "\n" + result.stderr[-1600:])
    return result


def sql(statement, database=None):
    args = ["docker", "exec", "-i", "-e", "MYSQL_PWD=" + os.getenv("SHORTLINK_E2E_MYSQL_ROOT_PASSWORD", "shortlink-it-only"), MYSQL,
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


def verify_published_port(container, internal_port, host_port):
    """The process endpoint must be the same fixture whose database we inspect."""
    info = json.loads(command(["docker", "inspect", "--type", "container", container]).stdout)
    if len(info) != 1 or info[0].get("Name") != "/" + container or not info[0].get("State", {}).get("Running"):
        raise ValueError("A running named dependency container is required: " + container)
    bindings = info[0].get("NetworkSettings", {}).get("Ports", {}).get(str(internal_port) + "/tcp")
    expected = {"HostIp": "127.0.0.1", "HostPort": str(host_port)}
    if bindings != [expected]:
        raise ValueError("Dependency must publish only 127.0.0.1:" + str(host_port)
                         + " -> " + str(internal_port) + "/tcp: " + container)
    return {"container": container, "containerId": info[0]["Id"],
            "hostIp": "127.0.0.1", "hostPort": host_port, "containerPort": internal_port}


def remove_owned_apisix(container):
    removed = command(["docker", "rm", "-f", container], check=False)
    remaining = command(["docker", "ps", "--all", "--quiet", "--filter", "name=^/" + re.escape(container) + "$"], check=False)
    # A nonzero inspect cannot distinguish an absent container from an unavailable daemon.
    # A successful exact-name listing with no result positively proves cleanup.
    if remaining.returncode != 0 or remaining.stdout.strip():
        raise RuntimeError("APISIX cleanup was not verified: " + container
                           + " (remove exit=" + str(removed.returncode)
                           + ", verification exit=" + str(remaining.returncode) + ")")
    return {"container": container, "removeExitCode": removed.returncode, "absenceVerified": True}


def serve(args):
    global MYSQL, REDIS, KAFKA, NETWORK
    if not args.allow_test_database:
        raise RuntimeError("Use --allow-test-database for the dedicated disposable MySQL fixture")
    if not sys.platform.startswith("linux"):
        raise RuntimeError("Run this supervisor inside the dedicated Linux test host")
    MYSQL = getattr(args, "mysql_container", MYSQL)
    REDIS = getattr(args, "redis_container", REDIS)
    KAFKA = getattr(args, "kafka_container", KAFKA)
    NETWORK = getattr(args, "network", NETWORK)
    for value in (MYSQL, REDIS, KAFKA, NETWORK):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", value):
            raise ValueError("Specify an existing isolated Docker container/network name")
    mysql_port, redis_port = getattr(args, "mysql_port", 13306), getattr(args, "redis_port", 16379)
    kafka_bootstrap = getattr(args, "kafka_bootstrap", "localhost:19092")
    kafka_host = getattr(args, "kafka_host", "kafka")
    object_endpoint = getattr(args, "object_endpoint", "http://127.0.0.1:19000")
    if any(not 1024 <= port <= 65535 for port in (mysql_port, redis_port)) or redis_port == 6379:
        raise ValueError("Use explicit isolated high MySQL/Redis ports; default Redis 6379 is not allowed")
    dependency_bindings = {
        "mysql": verify_published_port(MYSQL, 3306, mysql_port),
        "redis": verify_published_port(REDIS, 6379, redis_port),
    }
    run_id = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    folder = ROOT / ".work/e2e" / run_id
    folder.mkdir(parents=True)
    database = "shortlink_cr_e2e_" + uuid.uuid4().hex[:10]
    assert re.fullmatch(r"shortlink_cr_e2e_[a-f0-9]{10}", database)
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
        value = command(["docker", "exec", REDIS, "redis-cli", "-n", str(candidate), "DBSIZE"]).stdout.strip()
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
    jdbc = f"jdbc:mysql://127.0.0.1:{mysql_port}/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    env = dict(os.environ, BUSINESS_DB_URL=jdbc, BUSINESS_DB_CATALOG=database,
               BUSINESS_DB_USERNAME=app_user, BUSINESS_DB_PASSWORD=password,
               REDIRECT_DB_USERNAME=redirect_user, REDIRECT_DB_PASSWORD=password,
               INTERNAL_TOKEN=token, AGENT_INTERNAL_TOKEN="unused-agent-" + uuid.uuid4().hex,
               AGENT_SYSTEM_USERNAME="unused-e2e-agent", AGENT_URL="http://127.0.0.1:1",
               ANALYTICS_URL="http://127.0.0.1:1", ACCOUNT_PII_KEY="a1" * 16,
               SHORTCODE_FIXED_KEY_HEX="23" * 32, SHORTLINK_DEFAULT_DOMAIN=REDIRECT_HOST,
               SHORTLINK_ALLOWED_DOMAINS=REDIRECT_HOST, ADMIN_ALLOWED_HOSTS=MANAGEMENT_HOST,
               REDIS_HOST="127.0.0.1", REDIS_PORT=str(redis_port), REDIS_PASSWORD="",
               COMMAND_URL="http://127.0.0.1:8001",
               KAFKA_BOOTSTRAP_SERVERS=kafka_bootstrap, ANALYTICS_HASH_KEY="4b" * 32,
               REDIRECT_INSTANCE_ID="e2e-" + run_id, OBJECT_ENDPOINT=object_endpoint,
               IMPORT_ACCESS_KEY=os.getenv("SHORTLINK_E2E_IMPORT_ACCESS_KEY", "shortlink-it"),
               IMPORT_SECRET_KEY=os.getenv("SHORTLINK_E2E_IMPORT_SECRET_KEY", "shortlink-it-only"),
               IMPORT_BUCKET="shortlink-create-redirect-e2e")
    # Test fixture is plaintext; transport correctness is unchanged in application code.
    env.pop("KAFKA_SECURITY_PROPERTIES", None)
    manifest = (ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
    manifest_path = folder / "apisix.yaml"
    manifest_path.write_text(manifest, encoding="utf-8")
    container = "shortlink-e2e-apisix-" + suffix
    processes, logs = [], []
    state = dict(runId=run_id, folder=str(folder), database=database, redisDatabase=redis_database,
                 mysqlContainer=MYSQL, redisContainer=REDIS, network=NETWORK,
                 dependencyBindings=dependency_bindings,
                 mysqlPort=mysql_port, redisPort=redis_port,
                 kafkaContainer=KAFKA, kafkaContainerBootstrap="localhost:9092",
                 kafkaBootstrap=kafka_bootstrap, kafkaHost=kafka_host, objectEndpoint=object_endpoint,
                 apisix=container, managementHost=MANAGEMENT_HOST, redirectHost=REDIRECT_HOST,
                 baseUrl="http://127.0.0.1:19080", producerInstanceId=env["REDIRECT_INSTANCE_ID"],
                 phase="STARTING", topology="APISIX -> Admin / Redirect + Command; three real production JARs",
                 agentsStarted=False, analyticsStarted=False, jvmArtifacts={})
    state_path = folder / "state.json"
    try:
        command(["docker", "run", "-d", "--name", container, "--network", NETWORK,
                 "--add-host", "host.docker.internal:host-gateway", "-p", "127.0.0.1:19080:9080",
                 "-e", "KAFKA_HOST=" + kafka_host, "-e", "APISIX_INSTANCE_ID=e2e-edge-" + suffix,
                 "-e", "MANAGEMENT_HOST=" + MANAGEMENT_HOST, "-e", "SHORTLINK_HOST=" + REDIRECT_HOST,
                 "-e", "ADMIN_UPSTREAM_HOST=host.docker.internal", "-e", "REDIRECT_UPSTREAM_HOST=host.docker.internal",
                 "-v", str(ROOT / "deploy/apisix/config.yaml") + ":/usr/local/apisix/conf/config.yaml:ro",
                 "-v", str(manifest_path) + ":/usr/local/apisix/conf/apisix.yaml:ro",
                 "-v", str(ROOT / "deploy/apisix/plugins") + ":/opt/shortlink:ro", "apache/apisix:3.11.0-debian"])
        info = json.loads(command(["docker", "inspect", container]).stdout)[0]
        node = info["NetworkSettings"]["Networks"][NETWORK]
        env["APISIX_CIDRS"] = node["IPAddress"] + "/32"
        state.update(apisixIp=node["IPAddress"], networkGateway=node["Gateway"],
                     apisixPid=info["State"]["Pid"], manifest=str(manifest_path))
        write_json(folder / "observer-secret.json", {"internalToken": token})
        os.chmod(folder / "observer-secret.json", 0o600)
        for module, (filename, port, management) in JARS.items():
            artifact = ROOT / "services" / module / "target" / filename
            stream = (folder / (module + ".log")).open("wb")
            logs.append(stream)
            proc = subprocess.Popen(["java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2",
                                     "-Dfile.encoding=UTF-8", "-jar", str(artifact),
                                     "--spring.profiles.active=production",
                                     "--spring.config.location=classpath:application-production.properties",
                                     "--spring.data.redis.password=",
                                     "--spring.data.redis.database=" + str(redis_database)],
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
        (ROOT / ".work/e2e/latest-state.txt").write_text(str(state_path), encoding="utf-8")
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
        cleanup_errors = []
        for proc in reversed(processes):
            try:
                if proc.poll() is None:
                    proc.terminate()
            except OSError as error:
                cleanup_errors.append("Java terminate: " + str(error))
        for proc in reversed(processes):
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                try:
                    proc.kill()
                    proc.wait(timeout=5)
                except (OSError, subprocess.TimeoutExpired) as error:
                    cleanup_errors.append("Java kill/wait: " + str(error))
            except OSError as error:
                cleanup_errors.append("Java wait: " + str(error))
        try:
            edge_log = command(["docker", "logs", container], check=False)
            (folder / "apisix.log").write_text(edge_log.stdout + edge_log.stderr, encoding="utf-8")
        except (OSError, subprocess.SubprocessError) as error:
            state["edgeLogError"] = str(error)
        try:
            state["apisixCleanup"] = remove_owned_apisix(container)
        except (RuntimeError, OSError, subprocess.SubprocessError) as error:
            cleanup_errors.append(str(error))
        for stream in logs:
            stream.close()
        state["servicesStopped"] = not cleanup_errors
        if cleanup_errors:
            state["phase"] = "CLEANUP_FAILED"
            state["cleanupErrors"] = cleanup_errors
        elif state["phase"] != "FAILED":
            state["phase"] = "STOPPED"
        write_json(state_path, state)
        if cleanup_errors:
            raise RuntimeError("Owned E2E resources were not fully stopped: " + "; ".join(cleanup_errors))


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-test-database", action="store_true")
    parser.add_argument("--max-runtime-seconds", type=int, default=1200)
    parser.add_argument("--mysql-container", default=MYSQL)
    parser.add_argument("--redis-container", default=REDIS)
    parser.add_argument("--kafka-container", default=KAFKA)
    parser.add_argument("--network", default=NETWORK)
    parser.add_argument("--mysql-port", type=int, default=13306)
    parser.add_argument("--redis-port", type=int, default=16379)
    parser.add_argument("--kafka-bootstrap", default="localhost:19092")
    parser.add_argument("--kafka-host", default="kafka", help="Kafka DNS name on the APISIX Docker network; broker port 9092")
    parser.add_argument("--object-endpoint", default="http://127.0.0.1:19000")
    return parser.parse_args(argv)


if __name__ == "__main__":
    serve(parse_args())
