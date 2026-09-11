"""Boot production Admin/Redirect with isolated dependencies; collect health/metrics and stop.

The optional in-process Command adapter answers authenticated readiness only.
It never implements business writes, account, Agent, analytics, or redirect behavior.
"""
import argparse
from contextlib import contextmanager
import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import pathlib
import subprocess
import sys
import threading
import time

sys.dont_write_bytecode = True
from component_adapters import ROOT, http

ARTIFACTS = {
    "admin": ("shortlink-admin.jar", 18002, 18102),
    "shortlink-redirect": ("shortlink-redirect-1.0-SNAPSHOT.jar", 18003, 18103),
}
TOKEN = "production-config-component-test-token-32"
READY_PATH = "/internal/short-link-command/v1/risk/ready"


class ReadinessHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        authorized = self.headers.get("X-Internal-Token") == TOKEN
        status = 200 if authorized and self.path == READY_PATH else 403 if not authorized else 503
        payload = b'{"ready":true}' if status == 200 else b'{"error":"readiness-adapter-only"}'
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


@contextmanager
def command_adapter(configured_url):
    if configured_url:
        yield configured_url, "explicit-command-service"
        return
    server = ThreadingHTTPServer(("127.0.0.1", 0), ReadinessHandler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, name="component-command-ready", daemon=True)
    started = False
    try:
        worker.start()
        started = True
        yield "http://127.0.0.1:" + str(server.server_address[1]), "loopback-readiness-only-stub"
    finally:
        if started:
            server.shutdown()
        server.server_close()
        if started:
            worker.join(timeout=5)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", choices=["all", *ARTIFACTS], default="all")
    args = parser.parse_args(argv)
    if sys.platform != "win32":
        raise SystemExit("Run this component entry on Windows with a Java 17 JDK.")
    java_home = os.getenv("SHORTLINK_IT_JAVA_HOME") or os.getenv("JAVA_HOME")
    if not java_home or not java_home.strip():
        raise SystemExit("Set SHORTLINK_IT_JAVA_HOME or JAVA_HOME to a Windows Java 17 JDK.")
    java = pathlib.Path(java_home) / "bin/java.exe"
    if not java.is_file():
        raise SystemExit("The selected Java home must contain bin/java.exe; Java 17 is required.")
    selected = {name: value for name, value in ARTIFACTS.items() if args.module in ("all", name)}
    for module, (filename, _, _) in selected.items():
        if not (ROOT / "services" / module / "target" / filename).is_file():
            raise FileNotFoundError("Build the selected production artifact: " + module + "/" + filename)
    env = dict(os.environ,
        REDIS_HOST="127.0.0.1", REDIS_PORT=os.getenv("SHORTLINK_IT_REDIS_PORT", "16379"), REDIS_PASSWORD="",
        APISIX_CIDRS="127.0.0.0/8", ADMIN_ALLOWED_HOSTS="admin.it.test",
        INTERNAL_TOKEN=TOKEN, SHORTLINK_ALLOWED_DOMAINS="s.example",
        BUSINESS_DB_URL=os.environ["SHORTLINK_IT_BUSINESS_DB_URL"],
        BUSINESS_DB_USERNAME=os.environ["SHORTLINK_IT_DB_USERNAME"],
        BUSINESS_DB_PASSWORD=os.environ["SHORTLINK_IT_DB_PASSWORD"],
        REDIRECT_DB_USERNAME=os.environ["SHORTLINK_IT_DB_USERNAME"],
        REDIRECT_DB_PASSWORD=os.environ["SHORTLINK_IT_DB_PASSWORD"],
        ACCOUNT_PII_KEY="a1" * 16, AGENT_SYSTEM_USERNAME="unused-component-agent",
        AGENT_INTERNAL_TOKEN="unused-component-agent-token-00000000",
        AGENT_URL="http://127.0.0.1:1", ANALYTICS_URL="http://127.0.0.1:1",
        ANALYTICS_HASH_KEY="11" * 32, KAFKA_BOOTSTRAP_SERVERS="localhost:19092")
    # The isolated Kafka fixture has plaintext listeners.
    env.pop("KAFKA_SECURITY_PROPERTIES", None)
    output = ROOT / ".work/component-results"
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    env["REDIRECT_INSTANCE_ID"] = "component-production-" + stamp
    rows = []
    with command_adapter(os.getenv("SHORTLINK_IT_COMMAND_URL")) as (command_url, adapter_mode):
        env["COMMAND_URL"] = command_url
        for module, (filename, port, management) in selected.items():
            artifact = ROOT / "services" / module / "target" / filename
            log = output / (module + "-production-" + stamp + ".log")
            with log.open("wb") as stream:
                command = [str(java), "-Dfile.encoding=UTF-8", "-jar", str(artifact),
                    "--spring.profiles.active=production", "--spring.config.location=classpath:application-production.properties",
                    "--server.port=" + str(port), "--management.server.port=" + str(management),
                    "--spring.data.redis.password=", "--management.endpoint.health.probes.enabled=true",
                    "--management.endpoint.health.show-details=always"]
                process = subprocess.Popen(command, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                           creationflags=subprocess.CREATE_NO_WINDOW)
                try:
                    deadline = time.monotonic() + 60
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError(module + " failed to start; see " + log.name)
                        try:
                            status, _, body = http(management, "GET", "/actuator/health/liveness")
                            if status == 200:
                                break
                        except (OSError, TimeoutError):
                            pass
                        if time.monotonic() > deadline:
                            raise RuntimeError(module + " startup budget exceeded")
                        time.sleep(1)
                    health_status, _, health = http(management, "GET", "/actuator/health")
                    health_deadline = time.monotonic() + 30
                    while health_status != 200 and time.monotonic() < health_deadline:
                        time.sleep(5)
                        health_status, _, health = http(management, "GET", "/actuator/health")
                    metrics_status, _, metrics = http(management, "GET", "/actuator/prometheus")
                    if metrics_status != 200:
                        raise RuntimeError(module + " Prometheus endpoint failed")
                    metric_path = output / (module + "-prometheus-" + stamp + ".txt")
                    metric_path.write_bytes(metrics)
                    rows.append(dict(module=module, pid=process.pid, jar=filename,
                        jarSha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                        businessPort=port, managementPort=management, commandAdapter=adapter_mode,
                        scope="production startup/health/metrics only; no business E2E",
                        liveness=json.loads(body), healthHttpStatus=health_status, health=json.loads(health),
                        metricsHttpStatus=metrics_status, metrics=metric_path.name, log=log.name))
                    print(module, "STARTED", health_status, flush=True)
                    if health_status != 200:
                        print(health.decode(), flush=True)
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    (output / ("production-jars-" + stamp + ".json")).write_text(json.dumps(rows, indent=2), encoding="utf-8")
    return 0 if rows and all(row["healthHttpStatus"] == 200 for row in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
