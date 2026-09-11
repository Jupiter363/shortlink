"""Standalone APISIX limiter isolation regression; no business services or load test.

python3 -B scripts/integration/apisix_limiter_component.py --static-only
python3 -B scripts/integration/apisix_limiter_component.py --run-component

Requires PyYAML and, for the component run, Linux Docker with already available
apache/apisix:3.11.0-debian and nginx:1.27.4-alpine images. The component creates
one internal network and two labelled containers, publishes no ports, and removes
only those exact container/network IDs. It never operates existing dependencies.
"""
import argparse
import contextlib
import copy
import datetime as dt
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import runpy
import select
import subprocess
import sys
import tempfile
import time
from unittest.mock import patch
import uuid

import yaml

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
APISIX = ROOT / "deploy/apisix"
IMAGE = "apache/apisix:3.11.0-debian"
STUB_IMAGE = "nginx:1.27.4-alpine"
MANAGEMENT = "admin.limiter.it"
REDIRECT = "s.limiter.it"
MANAGEMENT_PATH = "/api/short-link/v1/user/has-username?username=limiterprobe"
DIAGNOSTIC_PATH = "/__limiter_worker__"
EXPECTED_KEYS = {"shortlink-management": "shortlink-management:$remote_addr",
                 "shortlink-redirect": "shortlink-redirect:$remote_addr"}


def require(value, message):
    if not value:
        raise AssertionError(message)


def bootstrap_module():
    spec = importlib.util.spec_from_file_location("limiter_bootstrap", APISIX / "bootstrap-etcd.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def production():
    return yaml.safe_load((APISIX / "apisix.yaml").read_text(encoding="utf-8"))


def record(rows, identity, operation):
    begin = time.monotonic()
    try:
        details = operation()
        row = {"id": identity, "passed": True, "details": details}
    except Exception as error:
        row = {"id": identity, "passed": False, "error": type(error).__name__ + ": " + str(error)}
    row["elapsedMs"] = round((time.monotonic() - begin) * 1000, 2)
    rows.append(row)
    return row["passed"]


def static_checks():
    module, original, results = bootstrap_module(), production(), []

    def budgets():
        module.validate_limiter_keys(original)
        require(len(original["routes"]) == 2, "Unexpected common route count")
        for route in original["routes"]:
            plugins = route["plugins"]
            req, conn = plugins["limit-req"], plugins["limit-conn"]
            pair = (100, 100) if route["id"] == "shortlink-management" else (1000, 200)
            require((req["rate"], req["burst"]) == pair, "Request budget changed")
            require((conn["conn"], conn["burst"], conn["default_conn_delay"]) == (200, 100, 0.1),
                    "Connection budget changed")
            require(req["rejected_code"] == conn["rejected_code"] == 429, "Rejection status changed")
            require(req.get("nodelay", False) is False, "Request delay semantics changed")
        return {"validatedPlugins": 4, "productionBudgetsPreserved": True}

    record(results, "LS01-production-keys-and-budgets", budgets)

    def invalid_keys():
        rejected = 0
        for index in range(2):
            for name in ("limit-req", "limit-conn"):
                for mutation in ("legacy", "wrong-prefix", "wrong-type", "missing"):
                    candidate = copy.deepcopy(original)
                    plugins = candidate["routes"][index]["plugins"]
                    if mutation == "missing":
                        del plugins[name]
                    elif mutation == "legacy":
                        plugins[name].update(key_type="var", key="remote_addr")
                    elif mutation == "wrong-prefix":
                        plugins[name]["key"] = "other-route:$remote_addr"
                    else:
                        plugins[name]["key_type"] = "var"
                    try:
                        module.validate_limiter_keys(candidate)
                    except ValueError:
                        rejected += 1
                    else:
                        raise AssertionError("Unsafe limiter configuration accepted")
        require(rejected == 16, "Incomplete negative matrix")
        return {"unsafeVariantsRejected": rejected}

    record(results, "LS02-all-four-import-guards", invalid_keys)
    work = ROOT / ".work"
    work.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="limiter-static-", dir=work) as temporary:
        folder = Path(temporary).resolve()
        require(folder.parent == work.resolve(), "Unexpected temporary directory")
        cert, key = folder / "fixture-cert.pem", folder / "fixture-key.pem"
        cert.write_text("SYNTHETIC CERTIFICATE FOR RENDER-WIRING TEST", encoding="utf-8")
        key.write_text("SYNTHETIC KEY FOR RENDER-WIRING TEST", encoding="utf-8")
        output = folder / "tls"
        environment = {"APISIX_TLS_CERT_FILE": str(cert), "APISIX_TLS_KEY_FILE": str(key),
                       "APISIX_TLS_SNIS": MANAGEMENT + "," + REDIRECT,
                       "MANAGEMENT_HOST": MANAGEMENT, "SHORTLINK_HOST": REDIRECT,
                       "KAFKA_HOST": "unused", "APISIX_INSTANCE_ID": "limiter-static",
                       "ADMIN_UPSTREAM_HOST": "stub", "REDIRECT_UPSTREAM_HOST": "stub"}

        def render():
            with patch.dict(os.environ, environment), patch.object(sys, "argv", ["render-tls-config.py", "--output", str(output)]), \
                    patch("ssl.SSLContext") as context, contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(APISIX / "render-tls-config.py"), run_name="__main__")
                context.return_value.load_cert_chain.assert_called_once_with(str(cert), str(key))
            rendered = yaml.safe_load((output / "apisix.yaml").read_text(encoding="utf-8"))
            require(rendered["routes"] == original["routes"], "TLS renderer changed common routes")
            with patch.dict(os.environ, environment):
                resolved = module.resolve(rendered)
            module.validate_limiter_keys(resolved)
            for route in resolved["routes"]:
                for name in ("limit-req", "limit-conn"):
                    expected = route["plugins"][name]
                    actual = copy.deepcopy(expected)
                    require(module.contains(actual, expected), "Readback rejected matching limiter")
                    actual.update(key_type="var", key="remote_addr")
                    require(not module.contains(actual, expected), "Etcd readback accepted legacy limiter")
            return {"validatedPlugins": 4, "tlsRouteCopyAndEtcdResolution": True,
                    "scope": "Renderer wiring; PEM validation mocked, not a TLS handshake test"}

        record(results, "LS03-tls-render-and-etcd-readback", render)

        def reject_before_network():
            rendered = yaml.safe_load((output / "apisix.yaml").read_text(encoding="utf-8"))
            rendered["routes"][0]["plugins"]["limit-req"].update(key_type="var", key="remote_addr")
            bad = folder / "unsafe.yaml"
            bad.write_text(yaml.safe_dump(rendered), encoding="utf-8")
            api_key = folder / "admin-key"
            api_key.write_text("synthetic-component-key-0000000000000000", encoding="utf-8")
            args = ["bootstrap-etcd.py", "--manifest", str(bad), "--admin-url", "https://127.0.0.1:9180",
                    "--ca-file", str(cert), "--key-file", str(api_key), "--report", str(folder / "report.json")]
            with patch.dict(os.environ, environment), patch.object(sys, "argv", args), \
                    patch.object(module.ssl, "create_default_context"), \
                    patch.object(module.http.client, "HTTPSConnection") as connection:
                try:
                    module.main()
                except ValueError as error:
                    require("route-scoped limiter" in str(error), "Rejected for an unrelated reason")
                else:
                    raise AssertionError("Unsafe import unexpectedly completed")
                connection.assert_not_called()
            require(not (folder / "report.json").exists(), "Unsafe import produced a success report")
            return {"adminHttpCalls": 0, "unsafeManifestRejectedBeforeImport": True}

        record(results, "LS04-unsafe-import-no-http", reject_before_network)
    return results


class Component:
    def __init__(self):
        self.run_id = uuid.uuid4().hex[:12]
        self.folder = ROOT / ".work" / ("apisix-limiter-" + self.run_id)
        self.folder.mkdir(parents=True)
        self.network = "shortlink-limiter-it-" + self.run_id
        self.network_id = None
        self.owned = []
        self.connections = []
        self.requests = 0
        self.base = production()
        self.original_sha = hashlib.sha256((APISIX / "apisix.yaml").read_bytes()).hexdigest()
        self.manifest = self.folder / "apisix.yaml"
        self.results = []

    def docker(self, *args, check=True, timeout=30):
        result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError("Docker " + args[0] + " failed: " + result.stderr[-1000:])
        return result

    def fixture(self, label, legacy, lane):
        value = copy.deepcopy(self.base)
        environment = {"MANAGEMENT_HOST": MANAGEMENT, "SHORTLINK_HOST": REDIRECT,
                       "KAFKA_HOST": "unused", "APISIX_INSTANCE_ID": "limiter-component",
                       "ADMIN_UPSTREAM_HOST": "limiter-stub", "REDIRECT_UPSTREAM_HOST": "limiter-stub"}
        with patch.dict(os.environ, environment):
            value = bootstrap_module().resolve(value)
        # No Kafka service is part of this isolated limiter component. Replace the
        # unrelated event rule with bounded test-only worker/source response headers.
        value["global_rules"] = [{"id": "limiter-observation", "plugins": {"response-rewrite": {
            "headers": {"set": {"X-Limiter-Worker": "$pid", "X-Limiter-Source": "$remote_addr",
                                   "X-Limiter-Profile": label}}}}}]
        for route in value["routes"]:
            req, conn = route["plugins"]["limit-req"], route["plugins"]["limit-conn"]
            req.update(rate=2 if lane == "req" else 10000, burst=0 if lane == "req" else 1000)
            conn.update(conn=20 if lane == "req" else 2, burst=0)
            if legacy:
                req.update(key_type="var", key="remote_addr")
                conn.update(key_type="var", key="remote_addr")
        value["routes"].append({"id": "limiter-worker-probe", "priority": 1000,
                                "hosts": [MANAGEMENT], "uri": DIAGNOSTIC_PATH,
                                "upstream": copy.deepcopy(value["routes"][0]["upstream"])})
        return value

    def write_profile(self, label, legacy, lane):
        previous = self.manifest.stat().st_mtime_ns if self.manifest.exists() else 0
        text = yaml.safe_dump(self.fixture(label, legacy, lane), sort_keys=False) + "#END\n"
        # Preserve the inode of the bind-mounted standalone manifest.
        with self.manifest.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        stamp = max(time.time_ns(), previous + 1_000_000_000)
        os.utime(self.manifest, ns=(stamp, stamp))
        self.profile = label

    def start(self):
        require(sys.platform.startswith("linux"), "Component runs only in the dedicated Linux Docker host")
        for image in (IMAGE, STUB_IMAGE):
            self.docker("image", "inspect", image)  # Do not pull/install dependencies implicitly.
        self.network_id = self.docker("network", "create", "--internal", "--label",
                                      "shortlink.component.run=" + self.run_id, self.network).stdout.strip()
        config = yaml.safe_load((APISIX / "config.yaml").read_text(encoding="utf-8"))
        config["nginx_config"]["worker_processes"] = 2
        config["nginx_config"]["http"]["keepalive_timeout"] = "30s"
        config["nginx_config"]["http"]["client_body_timeout"] = "5s"
        (self.folder / "config.yaml").write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")
        (self.folder / "nginx.conf").write_text('events { worker_connections 128; }\nhttp {\n'
            'server { listen 8002; location / { return 200 "management"; } }\n'
            'server { listen 8003; location / { return 302 https://destination.limiter.it/; } }\n}\n', encoding="utf-8")
        self.write_profile("legacy-req", True, "req")
        stub = self.docker("run", "--detach", "--name", "limiter-stub-" + self.run_id,
                           "--network", self.network, "--network-alias", "limiter-stub",
                           "--label", "shortlink.component.run=" + self.run_id,
                           "--mount", "type=bind,src=" + str(self.folder / "nginx.conf") + ",dst=/etc/nginx/nginx.conf,readonly",
                           STUB_IMAGE).stdout.strip()
        self.owned.append(stub)
        self.edge = self.docker("run", "--detach", "--name", "limiter-edge-" + self.run_id,
            "--network", self.network, "--label", "shortlink.component.run=" + self.run_id,
            "--mount", "type=bind,src=" + str(self.folder / "config.yaml") + ",dst=/usr/local/apisix/conf/config.yaml,readonly",
            "--mount", "type=bind,src=" + str(self.manifest) + ",dst=/usr/local/apisix/conf/apisix.yaml,readonly",
            "--mount", "type=bind,src=" + str(APISIX / "plugins") + ",dst=/opt/shortlink,readonly", IMAGE).stdout.strip()
        self.owned.append(self.edge)
        info = json.loads(self.docker("inspect", self.edge).stdout)[0]
        self.image_id = info["Image"]
        node = info["NetworkSettings"]["Networks"][self.network]
        network_info = json.loads(self.docker("network", "inspect", self.network_id).stdout)[0]
        # Internal networks intentionally omit a container default gateway. The
        # host's directly connected bridge address remains the HTTP source.
        self.address = node["IPAddress"]
        self.source = network_info["IPAM"]["Config"][0]["Gateway"]
        deadline = time.monotonic() + 45
        while True:
            connection = self.connection()
            try:
                reply = self.request(connection, MANAGEMENT, DIAGNOSTIC_PATH)
                require(reply["status"] == 200 and reply["profile"] == self.profile, "Not ready")
                break
            except (OSError, AssertionError, http.client.HTTPException) as error:
                require(time.monotonic() < deadline, "APISIX component initialization timeout: " + str(error))
                time.sleep(0.5)
            finally:
                connection.close()

    def connection(self):
        connection = http.client.HTTPConnection(self.address, 9080, timeout=2)
        self.connections.append(connection)
        return connection

    def request(self, connection, host, path):
        self.requests += 1
        connection.request("GET", path, headers={"Host": host, "X-Forwarded-For": "198.51.100.99"})
        response = connection.getresponse()
        body = response.read(65537)
        require(len(body) <= 65536, "Unexpected response body size")
        headers = {key.lower(): value for key, value in response.getheaders()}
        if response.status == 200 and path == DIAGNOSTIC_PATH and not headers.get("x-limiter-worker", "").isdigit():
            raise RuntimeError("Diagnostic headers absent or invalid: " + repr({name: headers.get(name) for name in
                               ("x-limiter-worker", "x-limiter-source", "x-limiter-profile")}))
        require(headers.get("x-limiter-worker", "").isdigit(), "Worker PID header missing: " + repr(headers.get("x-limiter-worker")))
        require(headers.get("x-limiter-source") == self.source, "Unexpected actual source IP: " + repr(headers.get("x-limiter-source")))
        return {"status": response.status, "worker": headers["x-limiter-worker"], "source": headers["x-limiter-source"],
                "profile": headers.get("x-limiter-profile"), "location": headers.get("location")}

    def pair(self):
        selected = {}
        for _ in range(24):
            connection = self.connection()
            reply = self.request(connection, MANAGEMENT, DIAGNOSTIC_PATH)
            require(reply["status"] == 200 and reply["profile"] == self.profile, "Worker has stale configuration")
            if reply["worker"] in selected:
                connection.close()
            else:
                selected[reply["worker"]] = connection
                if len(selected) == 2:
                    return list(selected.values()), list(selected)
        raise AssertionError("Could not pin two distinct worker connections within 24 diagnostic requests")

    def install(self, label, legacy, lane):
        for connection in self.connections:
            connection.close()
        self.connections = []
        self.write_profile(label, legacy, lane)
        time.sleep(3)

    @staticmethod
    def status(reply, expected):
        require(reply["status"] == expected, f"Expected {expected}, got {reply['status']}")
        if expected == 302:
            require(reply["location"] == "https://destination.limiter.it/", "Wrong stub Location")

    def request_case(self, legacy):
        label = "legacy-req" if legacy else "isolated-req"
        self.install(label, legacy, "req")
        connections, workers = self.pair()
        first, second = connections
        begun = time.monotonic()
        replies = [self.request(first, MANAGEMENT, MANAGEMENT_PATH), self.request(second, REDIRECT, "/Ab")]
        self.status(replies[0], 200)
        self.status(replies[1], 429 if legacy else 302)
        if not legacy:
            replies += [self.request(second, MANAGEMENT, MANAGEMENT_PATH), self.request(first, REDIRECT, "/Ab")]
            self.status(replies[2], 429)
            self.status(replies[3], 429)
        elapsed = time.monotonic() - begun
        require(elapsed < 0.5, "Requests crossed the rate=2 recovery interval; isolation evidence is inconclusive")
        require(replies[0]["worker"] != replies[1]["worker"], "Cross-worker request check used only one worker")
        recovery = []
        for host, path in ((MANAGEMENT, MANAGEMENT_PATH), (REDIRECT, "/Ab")):
            time.sleep(0.7)
            reply = self.request(first, host, path)
            self.status(reply, 200 if host == MANAGEMENT else 302)
            recovery.append(reply)
        return {"legacyDefectExpected": legacy, "sharedActualSource": self.source, "workers": workers,
                "replies": replies, "recovery": recovery, "competingRequestsElapsedMs": round(elapsed * 1000, 2),
                "semantics": "Sequential requests pinned to different workers; not a strict concurrent rate upper-bound proof"}

    def partial(self, connection):
        self.requests += 1
        connection.putrequest("POST", "/api/short-link/admin/v1/user/login", skip_host=True)
        connection.putheader("Host", MANAGEMENT)
        connection.putheader("Content-Type", "application/json")
        connection.putheader("Content-Length", "1024")
        connection.endheaders(b"{")

    def connection_case(self, legacy):
        self.install("legacy-conn" if legacy else "isolated-conn", legacy, "conn")
        held, workers = self.pair()
        try:
            for connection in held:
                self.partial(connection)
            readable, _, _ = select.select([connection.sock for connection in held], [], [], 0.15)
            require(not readable, "A partial login returned before the connection assertion")
            third = self.connection()
            redirect = self.request(third, REDIRECT, "/Ab")
            self.status(redirect, 429 if legacy else 302)
            management = self.request(third, MANAGEMENT, MANAGEMENT_PATH)
            self.status(management, 429)
        finally:
            for connection in held:
                connection.close()
        time.sleep(0.3)
        recovery = []
        for host, path in ((MANAGEMENT, MANAGEMENT_PATH), (REDIRECT, "/Ab")):
            reply = self.request(third, host, path)
            self.status(reply, 200 if host == MANAGEMENT else 302)
            recovery.append(reply)
        return {"legacyDefectExpected": legacy, "sharedActualSource": self.source,
                "heldWorkerIds": workers, "heldIncompleteRequests": 2,
                "redirectWhileManagementFull": redirect, "thirdManagement": management,
                "recovery": recovery, "heldConnectionsClosed": all(c.sock is None for c in held)}

    def cleanup(self):
        for connection in self.connections:
            connection.close()
        errors = []
        for identity in reversed(self.owned):
            info = json.loads(self.docker("inspect", identity).stdout)[0]
            require(info["Config"]["Labels"].get("shortlink.component.run") == self.run_id, "Cleanup ownership mismatch")
            logs = self.docker("logs", "--tail", "300", identity, check=False)
            (self.folder / (identity[:12] + ".log")).write_text(logs.stdout + logs.stderr, encoding="utf-8")
            result = self.docker("rm", "--force", identity, check=False)
            if result.returncode:
                errors.append("container:" + identity[:12])
        if self.network_id:
            result = self.docker("network", "rm", self.network_id, check=False)
            if result.returncode:
                errors.append("network")
        require(not errors, "Owned cleanup failed: " + ",".join(errors))
        require(hashlib.sha256((APISIX / "apisix.yaml").read_bytes()).hexdigest() == self.original_sha,
                "Production route source changed during the component test")

    def run(self, static_results):
        cleaned = False
        try:
            self.start()
            for identity, action in (
                ("LC01-legacy-request-bucket-contamination", lambda: self.request_case(True)),
                ("LC02-request-route-isolation-and-worker-sharing", lambda: self.request_case(False)),
                ("LC03-legacy-connection-bucket-contamination", lambda: self.connection_case(True)),
                ("LC04-connection-route-isolation-and-worker-sharing", lambda: self.connection_case(False)),
            ):
                if not record(self.results, identity, action):
                    break
        except Exception as error:
            self.results.append({"id": "COMPONENT_SETUP", "passed": False, "error": type(error).__name__ + ": " + str(error)})
        finally:
            try:
                self.cleanup()
                cleaned = True
            except Exception as error:
                self.results.append({"id": "COMPONENT_CLEANUP", "passed": False, "error": type(error).__name__ + ": " + str(error)})
        report = {"suite": "apisix_limiter_isolation", "runId": self.run_id,
                  "observedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "static": static_results,
                  "component": self.results, "passed": cleaned and len(self.results) == 4 and
                      all(row["passed"] for row in static_results + self.results),
                  "image": IMAGE, "imageId": getattr(self, "image_id", None), "workerCount": 2,
                  "httpRequestsIncludingReadinessAndDiagnostics": self.requests,
                  "productionManifestSha256": self.original_sha, "ownedResourcesRemoved": cleaned,
                  "scope": {"actualApisix": True, "upstreams": "nginx stubs", "businessServices": False,
                            "kafkaEvents": False, "performanceTest": False, "tlsOrEtcdRuntime": False,
                            "note": "Temporary finite limits; TLS/etcd manifest wiring checked separately in static cases"}}
        path = self.folder / "result.json"
        path.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps({"report": str(path), "passed": report["passed"], "staticCases": len(static_results),
                          "componentCases": len(self.results), "ownedResourcesRemoved": cleaned}))
        return 0 if report["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--static-only", action="store_true")
    modes.add_argument("--run-component", action="store_true")
    args = parser.parse_args()
    results = static_checks()
    if args.static_only or not all(row["passed"] for row in results):
        print(json.dumps({"suite": "apisix_limiter_static", "passed": all(row["passed"] for row in results),
                          "cases": results, "actualHttpRequests": 0}))
        return 0 if all(row["passed"] for row in results) else 1
    return Component().run(results)


if __name__ == "__main__":
    raise SystemExit(main())
