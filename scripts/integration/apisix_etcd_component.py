"""Traditional APISIX + new isolated TLS/auth etcd. No existing container mutations."""
import datetime
import http.client
import json
import os
import pathlib
import secrets
import socket
import ssl
import subprocess
import sys
import time
import uuid
sys.dont_write_bytecode = True
from component_adapters import ROOT, docker, case, RESULTS, require

run = uuid.uuid4().hex[:10]
directory = ROOT / ".work" / ("apisix-etcd-" + run)
directory.mkdir(parents=True)
etcd = "shortlink-refactor-it-etcd-" + run
apisix = "shortlink-refactor-it-apisix-etcd-" + run
cert = directory / "cert.pem"
key = directory / "key.pem"
admin_key = directory / "admin-key"
etcd_password = directory / "etcd-password"
encryption_key = directory / "encryption-key"
for path, value in ((admin_key, secrets.token_hex(32)), (etcd_password, secrets.token_hex(24)),
                    (encryption_key, secrets.token_hex(8))):
    path.write_text(value, encoding="utf-8")


def linux(path):
    path = pathlib.Path(path).resolve()
    return "/mnt/" + path.drive[0].lower() + str(path)[2:].replace("\\", "/")


subprocess.run(["wsl", "-d", os.getenv("SHORTLINK_IT_WSL", "shortlink-refactor-it"), "--exec", "openssl",
    "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=admin-api.it.test",
    "-addext", "subjectAltName=DNS:admin-api.it.test,DNS:s.it.test,DNS:admin.it.test,DNS:" + etcd + ",IP:127.0.0.1",
    "-keyout", linux(key), "-out", linux(cert)], check=True, capture_output=True)
environment = dict(os.environ, APISIX_TLS_CERT_FILE=str(cert), APISIX_TLS_KEY_FILE=str(key),
    APISIX_TLS_SNIS="s.it.test,admin.it.test", MANAGEMENT_HOST="admin.it.test", SHORTLINK_HOST="s.it.test",
    KAFKA_HOST="kafka", APISIX_INSTANCE_ID="etcd-adapter-it", ADMIN_UPSTREAM_HOST="apisix-stubs", REDIRECT_UPSTREAM_HOST="apisix-stubs",
    APISIX_ADMIN_BIND_IP="0.0.0.0", APISIX_ADMIN_ALLOWED_CIDRS="127.0.0.1/32,172.16.0.0/12",
    APISIX_ADMIN_KEY_FILE=str(admin_key), APISIX_ENCRYPTION_KEY_FILE=str(encryption_key),
    APISIX_ADMIN_CERT_FILE="/run/secrets/cert.pem", APISIX_ADMIN_TLS_KEY_FILE="/run/secrets/key.pem",
    APISIX_ETCD_CA_FILE="/run/secrets/cert.pem", APISIX_ETCD_USERNAME="shortlink",
    APISIX_ETCD_PASSWORD_FILE=str(etcd_password), APISIX_ETCD_ENDPOINTS="https://" + etcd + ":2379")
for script, output in (("render-tls-config.py", "tls"), ("render-etcd-config.py", "traditional")):
    subprocess.run([sys.executable, str(ROOT / "deploy/apisix" / script), "--output", str(directory / output)],
                   check=True, env=environment)

context = ssl.create_default_context(cafile=str(cert))
started = []


def admin(method, path, api_key=None):
    connection = http.client.HTTPSConnection("127.0.0.1", 19180, timeout=5, context=context)
    try:
        connection.request(method, "/apisix/admin/" + path, headers={} if api_key is None else {"X-API-KEY": api_key})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def public(method, path, host="s.it.test", headers=None, port=19444):
    with socket.create_connection(("127.0.0.1", port), 5) as transport:
        with context.wrap_socket(transport, server_hostname="s.it.test") as secure:
            values = {"Host": host, "Connection": "close", **(headers or {})}
            wire = method + " " + path + " HTTP/1.1\r\n" + "".join(k + ": " + v + "\r\n" for k, v in values.items()) + "\r\n"
            secure.sendall(wire.encode())
            response = http.client.HTTPResponse(secure, method=method)
            response.begin()
            return response.status, dict((k.lower(), v) for k, v in response.getheaders()), response.read()


def await_ready(action):
    deadline = time.monotonic() + 45
    while True:
        try:
            if action():
                return
        except (OSError, RuntimeError, ssl.SSLError, http.client.HTTPException):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError("Adapter initialization exceeded bounded wait")
        time.sleep(1)


try:
    docker("run", "--detach", "--rm", "--name", etcd, "--network", "shortlink-refactor-it_default",
           "-v", linux(directory) + ":/run/secrets:ro", "quay.io/coreos/etcd:v3.5.17", "/usr/local/bin/etcd",
           "--name", "adapter", "--data-dir", "/tmp/etcd-data", "--listen-client-urls", "https://0.0.0.0:2379",
           "--advertise-client-urls", "https://" + etcd + ":2379", "--listen-peer-urls", "http://127.0.0.1:2380",
           "--initial-advertise-peer-urls", "http://127.0.0.1:2380", "--initial-cluster", "adapter=http://127.0.0.1:2380",
           "--cert-file", "/run/secrets/cert.pem", "--key-file", "/run/secrets/key.pem")
    started.append(etcd)
    ctl = ["exec", etcd, "/usr/local/bin/etcdctl", "--endpoints=https://127.0.0.1:2379", "--cacert=/run/secrets/cert.pem"]
    await_ready(lambda: "healthy" in docker(*ctl, "endpoint", "health"))
    password = etcd_password.read_text()
    docker(*ctl, "user", "add", "root", "--new-user-password=" + password)
    docker(*ctl, "user", "grant-role", "root", "root")
    docker(*ctl, "role", "add", "shortlink")
    docker(*ctl, "role", "grant-permission", "shortlink", "readwrite", "/shortlink/apisix", "--prefix")
    docker(*ctl, "user", "add", "shortlink", "--new-user-password=" + password)
    docker(*ctl, "user", "grant-role", "shortlink", "shortlink")
    docker(*ctl, "auth", "enable")
    docker("run", "--detach", "--rm", "--name", apisix, "--hostname", "apisix-a-" + run, "--network", "shortlink-refactor-it_default",
           "-p", "127.0.0.1:19180:9180", "-p", "127.0.0.1:19444:9443",
           "-v", linux(directory / "traditional/config.yaml") + ":/usr/local/apisix/conf/config.yaml:ro",
           "-v", linux(directory) + ":/run/secrets:ro",
           "-v", linux(ROOT / "deploy/apisix/plugins") + ":/opt/shortlink:ro", "apache/apisix:3.11.0-debian")
    started.append(apisix)
    await_ready(lambda: admin("GET", "routes")[0] == 401)

    def bootstrap():
        reports = []
        for n in (1, 2):
            report = directory / ("bootstrap-" + str(n) + ".json")
            subprocess.run([sys.executable, str(ROOT / "deploy/apisix/bootstrap-etcd.py"), "--manifest",
                str(directory / "tls/apisix.yaml"), "--admin-url", "https://127.0.0.1:19180", "--ca-file", str(cert),
                "--key-file", str(admin_key), "--report", str(report)], check=True, env=environment, capture_output=True)
            reports.append(json.loads(report.read_text()))
        require(reports[0]["manifestSha256"] == reports[1]["manifestSha256"], "Idempotent import changed manifest")
        require(len(reports[1]["resources"]) == 4, "Common resource count mismatch")
        return reports[1]

    case("ET01-authenticated-tls-idempotent-bootstrap", bootstrap)

    def unauthenticated():
        missing = admin("GET", "routes")[0]
        wrong = admin("GET", "routes", "invalid-component-test-key")[0]
        require(missing == 401 and wrong == 401, "Admin API accepted missing/incorrect key")
        return dict(missingKey=missing, incorrectKey=wrong, tlsVerified=True)
    case("ET02-admin-auth-required", unauthenticated)
    await_ready(lambda: public("HEAD", "/Ab9")[0] == 302)

    def redirect():
        status, headers, _ = public("GET", "/Ab9")
        require(status == 302 and headers.get("location") == "https://destination.it.test/", "Imported upstream mismatch")
        status_head, _, body = public("HEAD", "/Ab9")
        require(status_head == 302 and not body, "HEAD changed")
        return dict(get=status, head=status_head, headBodyBytes=len(body), tlsVerified=True)
    case("ET03-public-tls-get-head", redirect)

    def boundaries():
        results = {}
        for method, path, host, expected in (("POST", "/Ab9", "s.it.test", 405),
            ("GET", "/Ab9", "unknown.it.test", 404), ("GET", "/internal/command/risk/current", "s.it.test", 404),
            ("GET", "/internal/command/risk/current", "admin.it.test", 404), ("GET", "/Ab%2F9", "s.it.test", 400)):
            status = public(method, path, host)[0]
            require(status == expected, "Boundary mismatch " + method + " " + path)
            results[method + " " + host + path] = status
        return results
    case("ET04-common-host-method-path-boundaries", boundaries)

    def management():
        status, _, body = public("GET", "/api/short-link/admin/v1/group", "admin.it.test",
            {"x-shortlink-tenant-id": "forged", "X-Internal-Token": "forged", "X-Forwarded-Proto": "http"})
        echoed = json.loads(body)
        require(status == 200 and echoed["spoofedTenant"] == "" and echoed["internalToken"] == "" and echoed["forwardedProto"] == "https", "Identity boundary drift")
        return dict(status=status, forwardedProto=echoed["forwardedProto"], forgedHeadersRemoved=True)
    case("ET05-management-identity-boundary", management)

    def persisted():
        docker("restart", apisix)
        await_ready(lambda: public("HEAD", "/Ab9")[0] == 302)
        status, payload = admin("GET", "routes", admin_key.read_text())
        require(status == 200 and json.loads(payload)["total"] == 2, "Restart lost etcd routes")
        return dict(routes=2, reloadFromEtcd=True)
    case("ET06-restart-loads-etcd-resources", persisted)

    def node_boot_identity():
        topic = "shortlink.gateway.request.v1"
        offsets = docker("exec", "shortlink-refactor-it-kafka-1", "/opt/kafka/bin/kafka-get-offsets.sh",
                         "--bootstrap-server", "localhost:9092", "--topic", topic, "--time", "-1")
        floors = [line.rsplit(":", 2)[1:] for line in offsets.splitlines() if line.startswith(topic + ":")]
        def evidence_request(port):
            status, _, body = public("GET", "/api/short-link/admin/v1/group", "admin.it.test",
                                     headers={"X-Request-ID": "client-controlled-value"}, port=port)
            require(status == 200, "Event fixture request failed")
            request_id = json.loads(body)["requestId"]
            require(request_id != "client-controlled-value", "Client request id survived the boundary")
            return request_id
        first_id = evidence_request(19444)
        # Wait for the async lane to deliver before stopping the first process.
        time.sleep(3)
        docker("restart", apisix)
        await_ready(lambda: public("HEAD", "/Ab9")[0] == 302)
        restarted_id = evidence_request(19444)
        second = apisix + "-second"
        docker("run", "--detach", "--rm", "--name", second, "--hostname", "apisix-b-" + run,
               "--network", "shortlink-refactor-it_default", "-p", "127.0.0.1:19445:9443",
               "-v", linux(directory / "traditional/config.yaml") + ":/usr/local/apisix/conf/config.yaml:ro",
               "-v", linux(directory) + ":/run/secrets:ro",
               "-v", linux(ROOT / "deploy/apisix/plugins") + ":/opt/shortlink:ro", "apache/apisix:3.11.0-debian")
        started.append(second)
        await_ready(lambda: public("HEAD", "/Ab9", port=19445)[0] == 302)
        second_id = evidence_request(19445)
        time.sleep(3)
        found = {}
        expected = {first_id, restarted_id, second_id}
        for partition, offset in floors:
            # A bounded component consumer reads only records produced since this case.
            command = ["wsl", "-d", os.getenv("SHORTLINK_IT_WSL", "shortlink-refactor-it"), "--exec", "docker", "exec",
                       "shortlink-refactor-it-kafka-1", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092",
                       "--topic", topic, "--partition", partition, "--offset", offset, "--timeout-ms", "6000", "--max-messages", "200"]
            result = subprocess.run(command, capture_output=True, timeout=25)
            for line in result.stdout.decode(errors="replace").splitlines():
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if event.get("requestId") in expected:
                    found[event["requestId"]] = event
        (directory / "event-identity-evidence.json").write_text(json.dumps(dict(expected=sorted(expected), found=found, floors=floors), indent=2), encoding="utf-8")
        require(set(found) == expected, "Async Kafka records did not arrive within the component budget: " + str(len(found)) + "/3")
        identities = [found[key]["producerInstanceId"] for key in (first_id, restarted_id, second_id)]
        require(len(set(identities)) == 3, "Shared etcd conflated node identity or process restart")
        require(all(":apisix-a-" + run + ":" in identities[i] for i in (0, 1)) and ":apisix-b-" + run + ":" in identities[2], "Fixed node hostname missing")
        require(all(found[key]["decisionId"] == "v1:" + str(found[key]["occurredAt"]) + ":" + found[key]["producerInstanceId"] + ":" + key for key in expected), "Decision id is not bound to occurredAt and the controlled request id")
        suffixes = [found[key]["decisionId"].split(":", 2)[2] for key in expected]
        require(all(1 <= len(suffix) <= 256 and all(0x21 <= ord(char) <= 0x7e for char in suffix) for suffix in suffixes), "EventIdentity suffix must be bounded printable ASCII")
        require(all(found[key]["decisionId"].split(":", 2)[1] != str(found[key]["occurredAt"] + 1) for key in expected), "Changed occurrence time retained a valid binding")
        return dict(producerInstanceIds=identities, kafkaRecords=len(found), sameNodeNewBoot=True,
                    sharedEtcdDistinctNodes=True, occurredAtBound=True, clientRequestIdReplaced=True,
                    suffixAsciiBound=256, suffixLengths=[len(suffix) for suffix in suffixes])
    case("ET07-node-and-boot-event-identities", node_boot_identity)
finally:
    for name in reversed(started):
        log = subprocess.run(["wsl", "-d", os.getenv("SHORTLINK_IT_WSL", "shortlink-refactor-it"), "--exec", "docker", "logs", name], capture_output=True, timeout=10)
        (directory / (name + ".log")).write_text((log.stdout + log.stderr).decode(errors="replace"), encoding="utf-8")
        docker("stop", "--time", "5", name)
    output = ROOT / ".work/component-results"
    output.mkdir(parents=True, exist_ok=True)
    path = output / ("etcd-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")
    path.write_text(json.dumps(dict(apisixVersion="3.11.0", etcdVersion="3.5.17", results=RESULTS), indent=2), encoding="utf-8")
    print(path)
raise SystemExit(0 if len(RESULTS) == 7 and all(row["outcome"] == "PASS" for row in RESULTS) else 1)
