"""Finite APISIX sync-sender component against a real, private Kafka broker.

python3 -B scripts/integration/apisix_sender_component.py --static-only
python3 -B scripts/integration/apisix_sender_component.py --run-component

The latter requires Linux, PyYAML and three already installed images. No image
pulls, host ports, existing networks, dependencies or topics are used. Only exact
IDs created by this run and bearing its ownership label can be removed. Reports
contain counters, assertions and hashes, never Kafka payloads or raw Docker logs.
This is a finite component/fault test, not a throughput or capacity measurement.
"""
import argparse
import base64
import copy
import datetime as dt
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from unittest.mock import patch

import yaml

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
APISIX_ROOT = ROOT / "deploy/apisix"
OUTPUT_ROOT = ROOT / ".work/gateway-send-v1/component"
APISIX_IMAGE = "apache/apisix:3.11.0-debian"
KAFKA_IMAGE = "apache/kafka:3.9.0"
STUB_IMAGE = "nginx:1.27.4-alpine"
LABEL = "shortlink.sender.component.run"
TOPIC = "shortlink.gateway.request.v1"
HOST = "s.sender.it"
PREFIX = "shortlink_edge_"
REASONS = ("identity_unavailable", "worker_exiting", "encoding", "event_size",
           "queue_count", "queue_bytes", "timer_unavailable")
COUNTERS = ("events_attempted", "events_delivered", "events_failed", "events_rejected",
            "pending_count", "pending_bytes", "queued_count", "inflight_count", "active_senders",
            "oldest_pending_seconds", "send_attempts", "send_seconds_sum", "send_seconds_max",
            "retry_attempts", "producer_creations", "producer_failures", "timer_failures", "drain_errors",
            "retained_worker_generations", "observation_faults", "observation_complete", "started_at_seconds")
WORKER_FIELDS = ("pending_count", "pending_bytes", "inflight_count", "active_senders")
SAMPLE_RE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+([^\s]+)$')
LABEL_RE = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"(?:,|$)')


def require(condition, code):
    if not condition:
        raise AssertionError(code)


class ComponentFailure(RuntimeError):
    """Only fixed, non-sensitive error codes may be put in the public report."""


def public_error(error):
    return str(error) if isinstance(error, (ComponentFailure, AssertionError)) else type(error).__name__


def digest(value):
    return hashlib.sha256(value).hexdigest()


def metric_labels(raw):
    if not raw:
        return {}
    value, position, labels = raw[1:-1], 0, {}
    for match in LABEL_RE.finditer(value):
        require(match.start() == position and match.group(1) not in labels, "INVALID_METRIC_LABELS")
        labels[match.group(1)] = json.loads('"' + match.group(2) + '"')
        position = match.end()
    require(position == len(value), "INVALID_METRIC_LABELS")
    return labels


def parse_metrics(body):
    """Strict parser. Missing/duplicate series cannot turn into a zero/drained sample."""
    values, workers, reasons, identity = {}, {}, {}, None
    for line in body.splitlines():
        if not line or line.startswith("#"):
            continue
        match = SAMPLE_RE.match(line)
        require(match is not None, "MALFORMED_METRIC_SAMPLE")
        name, labels = match.group(1), metric_labels(match.group(2))
        if not name.startswith(PREFIX):
            continue
        number = float(match.group(3))
        require(math.isfinite(number) and number >= 0, "INVALID_METRIC_NUMBER")
        key = name[len(PREFIX):]
        if key in COUNTERS:
            require(not labels and key not in values, "DUPLICATE_OR_LABELLED_SCALAR")
            values[key] = number
        elif key == "instance_info":
            require(identity is None and set(labels) == {"instance", "boot_id"} and number == 1,
                    "INVALID_INSTANCE_SERIES")
            identity = (labels["instance"], labels["boot_id"])
        elif key == "rejections_by_reason":
            reason = labels.get("reason")
            require(set(labels) == {"reason"} and reason in REASONS and reason not in reasons,
                    "INVALID_REJECTION_SERIES")
            reasons[reason] = number
        elif key.startswith("worker_"):
            field = key[len("worker_"):]
            require(field in WORKER_FIELDS and set(labels) == {"worker_id", "generation"},
                    "INVALID_WORKER_SERIES")
            worker = workers.setdefault((labels["worker_id"], labels["generation"]), {})
            require(field not in worker, "DUPLICATE_WORKER_SERIES")
            worker[field] = number
    require(set(values) == set(COUNTERS) and set(reasons) == set(REASONS) and identity is not None,
            "MISSING_REQUIRED_METRIC")
    require(all(set(worker) == set(WORKER_FIELDS) for worker in workers.values()), "MISSING_WORKER_METRIC")
    require(values["observation_complete"] in (0, 1), "INVALID_OBSERVATION_COMPLETE")
    if values["observation_complete"] == 1:
        require(values["events_attempted"] == values["events_delivered"] + values["events_failed"]
                + values["events_rejected"] + values["pending_count"], "COUNTERS_DO_NOT_CONSERVE")
        require(values["pending_count"] == values["queued_count"] + values["inflight_count"],
                "QUEUE_PLUS_INFLIGHT_MISMATCH")
        for key in WORKER_FIELDS:
            require(sum(worker[key] for worker in workers.values()) == values[key], "WORKER_TOTAL_MISMATCH")
        require(sum(reasons.values()) == values["events_rejected"], "REJECTION_REASON_MISMATCH")
    return {"values": values, "reasons": reasons, "workers": workers, "identity": identity}


def sanitized_metrics(snapshot):
    return {"counters": snapshot["values"], "rejectionsByReason": snapshot["reasons"],
            "workerCount": len(snapshot["workers"]),
            "workers": [dict(values) for _, values in sorted(snapshot["workers"].items())],
            "identitySha256": digest(json.dumps(snapshot["identity"]).encode())}


def validate_records(output, expected_paths):
    require(len(output.encode("utf-8")) <= 1024 * 1024, "CONSUMER_OUTPUT_TOO_LARGE")
    lines = [line for line in output.splitlines() if line]
    require(len(lines) == len(expected_paths), "KAFKA_RECORD_COUNT_MISMATCH")
    identities, paths = set(), set()
    for line in lines:
        key, separator, payload = line.partition("\t")
        require(separator == "\t", "KAFKA_KEY_NOT_CAPTURED")
        event = json.loads(payload)
        require(key == event.get("decisionId") and key not in identities, "DUPLICATE_OR_MISMATCHED_DECISION_ID")
        require(event.get("schemaVersion") == 1 and event.get("source") == "APISIX"
                and event.get("stage") == "EDGE" and event.get("method") == "GET"
                and event.get("status") == 302 and event.get("reason") == "UPSTREAM_RESPONSE", "KAFKA_EVENT_FIELDS_INVALID")
        occurred = event.get("occurredAt")
        require(type(occurred) is int and occurred > 0 and key.startswith("v1:" + str(occurred) + ":"),
                "EVENT_IDENTITY_NOT_TIME_BOUND")
        require(isinstance(event.get("requestId"), str) and event["requestId"]
                and event.get("traceId") == event["requestId"]
                and key == "v1:" + str(occurred) + ":" + event.get("producerInstanceId", "") + ":" + event["requestId"],
                "EVENT_IDENTITY_FIELDS_MISMATCH")
        path = event.get("shortUri")
        require(event.get("domainNorm") == HOST and path in expected_paths and path not in paths,
                "EVENT_PATH_MISSING_EXTRA_OR_DUPLICATE")
        identities.add(key)
        paths.add(path)
    require(paths == set(expected_paths), "KAFKA_PATH_SET_MISMATCH")
    return {"records": len(lines), "uniqueDecisionIds": len(identities), "uniqueSyntheticPaths": len(paths),
            "exactKeyPayloadMatch": True, "noDuplicateOrExtraRecord": True,
            "decisionSetSha256": digest("\n".join(sorted(identities)).encode())}


def logger_configuration(queue_count=64, explicit_concurrency=None):
    result = {"brokers": [{"host": "sender-kafka", "port": 9092}], "instance_id": "sender-component",
              "queue_count": queue_count, "queue_bytes": 1048576, "max_event_bytes": 4096}
    if explicit_concurrency is not None:
        result["send_concurrency"] = explicit_concurrency
    return result


def manifest(profile, queue_count=64, explicit_concurrency=None):
    upstream = {"type": "roundrobin", "nodes": {"sender-stub:8003": 1}}
    return {"routes": [
        {"id": "sender-readiness", "priority": 1000, "hosts": [HOST], "uri": "/__sender_ready__",
         "plugins": {"response-rewrite": {"headers": {"set": {"X-Component-Profile": profile}}}},
         "upstream": copy.deepcopy(upstream)},
        {"id": "sender-events", "hosts": [HOST], "uri": "/*",
         "plugins": {"shortlink-boundary": {"mode": "redirect"},
                     "shortlink-request-logger": logger_configuration(queue_count, explicit_concurrency)},
         "upstream": copy.deepcopy(upstream)}]}


# The diagnostic location invokes only the real plugin's schema validator. It
# neither replaces the producer nor changes callbacks, timers, queues or clocks.
SCHEMA_LUA = r'''
local p=require("apisix.plugins.shortlink-request-logger")
local core=require("apisix.core")
local function conf(n)
  local c={brokers={{host="sender-kafka",port=9092}},instance_id="sender-component",
    queue_count=64,queue_bytes=1048576,max_event_bytes=4096}
  c.send_concurrency=n;return c
end
local missing=conf(nil)
local defaultValid=p.check_schema(missing)
local result={defaultValid=defaultValid==true,defaultValue=p.schema.properties.send_concurrency.default,
  minValue=p.schema.properties.send_concurrency.minimum,maxValue=p.schema.properties.send_concurrency.maximum}
for _,n in ipairs({0,1,2,8,9,1.5}) do result["accept"..tostring(n)]=p.check_schema(conf(n))==true end
ngx.header.content_type="application/json"
ngx.say(core.json.encode(result))
'''


class Component:
    def __init__(self):
        self.run_id = uuid.uuid4().hex[:12]
        self.folder = OUTPUT_ROOT / self.run_id
        self.folder.mkdir(parents=True, exist_ok=False)
        self.network = "shortlink-sender-it-" + self.run_id
        self.network_id, self.owned, self.image_ids = None, [], {}
        self.network_create_started = False
        self.edge, self.broker, self.address = None, None, None
        self.broker_paused = False
        self.manifest_path = self.folder / "apisix.yaml"
        self.expected_paths, self.request_count, self.samples = set(), 0, []
        self.identity, self.sample_started = None, time.monotonic()
        self.results, self.cleanup_complete = [], False

    def docker(self, *args, timeout=30, check=True):
        try:
            reply = subprocess.run(["docker", *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, encoding="utf-8", timeout=timeout)
        except subprocess.TimeoutExpired:
            raise ComponentFailure("DOCKER_COMMAND_TIMEOUT_" + str(args[0]).upper())
        if check and reply.returncode:
            raise ComponentFailure("DOCKER_COMMAND_FAILED_" + str(args[0]).upper())
        require(len(reply.stdout.encode("utf-8")) <= 2 * 1024 * 1024, "DOCKER_OUTPUT_BUDGET_EXCEEDED")
        return reply

    def owned_label(self, identity, network=False):
        require(identity == self.network_id if network else identity in self.owned, "UNOWNED_RESOURCE_ID")
        query = '{{index .Labels "' + LABEL + '"}}' if network else '{{index .Config.Labels "' + LABEL + '"}}'
        args = ("network", "inspect") if network else ("inspect",)
        actual = self.docker(*args, "--format", query, identity).stdout.strip()
        require(actual == self.run_id, "RESOURCE_OWNERSHIP_LABEL_MISMATCH")

    def container(self, role, image, extra=()):
        cidfile = self.folder / (role + ".cid")
        args = ["create", "--cidfile", str(cidfile), "--name", "sender-" + role + "-" + self.run_id,
                "--network", self.network, "--network-alias", "sender-" + role,
                "--label", LABEL + "=" + self.run_id, *extra, image]
        try:
            reply = self.docker(*args)
            identity = reply.stdout.strip()
        finally:
            # cidfile survives an ambiguous CLI return, so cleanup can still be
            # restricted to this invocation's created ID (never scan by name).
            if cidfile.exists():
                created = cidfile.read_text(encoding="utf-8").strip()
                require(re.fullmatch(r"[a-f0-9]{64}", created) is not None, "INVALID_CREATED_CONTAINER_ID")
                if created not in self.owned:
                    self.owned.append(created)
        require(identity in self.owned, "CREATED_CONTAINER_ID_NOT_RECORDED")
        self.owned_label(identity)
        self.docker("start", identity)
        return identity

    def write_profile(self, profile, queue_count=64, explicit_concurrency=None):
        previous = self.manifest_path.stat().st_mtime_ns if self.manifest_path.exists() else 0
        text = yaml.safe_dump(manifest(profile, queue_count, explicit_concurrency), sort_keys=False) + "#END\n"
        with self.manifest_path.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        stamp = max(time.time_ns(), previous + 1000000000)
        os.utime(self.manifest_path, ns=(stamp, stamp))

    def http(self, path, diagnostic=False):
        connection = http.client.HTTPConnection(self.address, 9099 if diagnostic else 9080, timeout=2)
        try:
            connection.request("GET", path, headers={"Host": HOST})
            reply = connection.getresponse()
            body = reply.read(65537)
            require(len(body) <= 65536, "HTTP_RESPONSE_BUDGET_EXCEEDED")
            return reply.status, {key.lower(): value for key, value in reply.getheaders()}, body.decode("utf-8")
        finally:
            connection.close()

    def ready(self, profile):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                status, headers, _ = self.http("/__sender_ready__")
                if status == 302 and headers.get("x-component-profile") == profile:
                    return
            except (OSError, http.client.HTTPException):
                pass
            time.sleep(0.25)
        raise ComponentFailure("APISIX_PROFILE_READINESS_TIMEOUT")

    def kafka_cli(self, program, *args, timeout=30, check=True):
        require(self.broker in self.owned, "BROKER_NOT_OWNED")
        return self.docker("exec", self.broker, "/opt/kafka/bin/" + program,
                           "--bootstrap-server", "sender-kafka:9092", *args, timeout=timeout, check=check)

    def kafka_ready(self):
        deadline = time.monotonic() + 75
        while time.monotonic() < deadline:
            try:
                reply = self.kafka_cli("kafka-topics.sh", "--list", timeout=8, check=False)
                if reply.returncode == 0:
                    return
            except ComponentFailure:
                pass
            time.sleep(0.5)
        raise ComponentFailure("PRIVATE_KAFKA_READINESS_TIMEOUT")

    def start(self):
        require(sys.platform.startswith("linux"), "COMPONENT_REQUIRES_LINUX_DOCKER_HOST")
        for image in (APISIX_IMAGE, KAFKA_IMAGE, STUB_IMAGE):
            self.image_ids[image] = self.docker("image", "inspect", "--format", "{{.Id}}", image).stdout.strip()
        self.network_create_started = True
        self.network_id = self.docker("network", "create", "--internal", "--label", LABEL + "=" + self.run_id,
                                      self.network).stdout.strip()
        require(re.fullmatch(r"[a-f0-9]{64}", self.network_id) is not None, "INVALID_CREATED_NETWORK_ID")
        self.owned_label(self.network_id, network=True)
        environment = {
            "KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
            "KAFKA_LISTENERS": "PLAINTEXT://:9092,CONTROLLER://:9093",
            "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://sender-kafka:9092",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
            "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER", "KAFKA_INTER_BROKER_LISTENER_NAME": "PLAINTEXT",
            "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@sender-kafka:9093",
            "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1", "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
            "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1", "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS": "0",
            "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "false", "KAFKA_LOG_DIRS": "/tmp/kafka-logs",
            "KAFKA_HEAP_OPTS": "-Xms256m -Xmx256m",
            "CLUSTER_ID": base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip("=")}
        extra = []
        for key, value in environment.items():
            extra.extend(["--env", key + "=" + value])
        self.broker = self.container("kafka", KAFKA_IMAGE, extra)
        self.kafka_ready()
        self.kafka_cli("kafka-topics.sh", "--create", "--topic", TOPIC, "--partitions", "1",
                       "--replication-factor", "1", "--config", "message.timestamp.type=LogAppendTime")
        config = yaml.safe_load((APISIX_ROOT / "config.yaml").read_text(encoding="utf-8"))
        config["nginx_config"]["worker_processes"] = 1
        config["nginx_config"]["http_configuration_snippet"] = (
            'lua_shared_dict shortlink_edge_metrics 1m;\nserver {\n listen 9099;\n'
            ' location = /shortlink/metrics { content_by_lua_block {\n'
            ' require("apisix.plugins.shortlink-request-logger").metrics()\n } }\n'
            ' location = /sender/schema { content_by_lua_block {\n' + SCHEMA_LUA + '\n } }\n}\n')
        (self.folder / "config.yaml").write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")
        (self.folder / "nginx.conf").write_text(
            'events { worker_connections 128; }\nhttp { server { listen 8003; '
            'location / { return 302 https://destination.sender.it/; } } }\n', encoding="utf-8")
        self.write_profile("default2")
        self.container("stub", STUB_IMAGE, ["--mount", "type=bind,src=" + str(self.folder / "nginx.conf")
                                           + ",dst=/etc/nginx/nginx.conf,readonly"])
        self.edge = self.container("edge", APISIX_IMAGE, [
            "--mount", "type=bind,src=" + str(self.folder / "config.yaml") + ",dst=/usr/local/apisix/conf/config.yaml,readonly",
            "--mount", "type=bind,src=" + str(self.manifest_path) + ",dst=/usr/local/apisix/conf/apisix.yaml,readonly",
            "--mount", "type=bind,src=" + str(APISIX_ROOT / "plugins") + ",dst=/opt/shortlink,readonly"])
        networks = json.loads(self.docker("inspect", "--format", "{{json .NetworkSettings.Networks}}", self.edge).stdout)
        self.address = networks[self.network]["IPAddress"]
        self.ready("default2")
        self.sample()

    def sample(self):
        status, _, body = self.http("/shortlink/metrics", diagnostic=True)
        require(status == 200, "METRICS_HTTP_NOT_200")
        snapshot = parse_metrics(body)
        if self.identity is None:
            self.identity = snapshot["identity"]
        require(snapshot["identity"] == self.identity, "APISIX_OBSERVATION_IDENTITY_CHANGED")
        require(snapshot["values"]["observation_faults"] == 0, "OBSERVATION_FAULT")
        require(snapshot["values"]["inflight_count"] <= 2 and snapshot["values"]["active_senders"] <= 2,
                "TWO_SLOT_BUDGET_EXCEEDED")
        self.samples.append({"elapsedMs": round((time.monotonic() - self.sample_started) * 1000, 2),
                             **sanitized_metrics(snapshot)})
        require(len(self.samples) <= 4000, "COMPONENT_SAMPLE_BUDGET_EXCEEDED")
        return snapshot

    def wait_counts(self, attempted, delivered, failed, rejected, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            snapshot = self.sample()
            v = snapshot["values"]
            require(v["events_attempted"] <= attempted and v["events_delivered"] <= delivered
                    and v["events_failed"] <= failed and v["events_rejected"] <= rejected,
                    "UNEXPECTED_TERMINAL_COUNT")
            if (v["observation_complete"] == 1 and v["events_attempted"] == attempted
                    and v["events_delivered"] == delivered and v["events_failed"] == failed
                    and v["events_rejected"] == rejected and v["pending_count"] == 0
                    and v["pending_bytes"] == 0 and v["active_senders"] == 0):
                return sanitized_metrics(snapshot)
            time.sleep(0.05)
        raise ComponentFailure("TERMINAL_ACK_DRAIN_TIMEOUT")

    def requests(self, paths, accepted=True):
        for path in paths:
            require(self.request_count < 100, "FINITE_COMPONENT_REQUEST_BUDGET_EXCEEDED")
            require(re.fullmatch(r"[A-Za-z0-9]+", path) is not None, "INVALID_SYNTHETIC_PATH")
            status, headers, _ = self.http("/" + path)
            self.request_count += 1
            require(status == 302 and headers.get("location") == "https://destination.sender.it/", "HTTP_GET_NOT_CORRECT_302")
            if accepted:
                self.expected_paths.add(path)

    def verify_kafka(self):
        result = self.kafka_cli("kafka-get-offsets.sh", "--topic", TOPIC, "--time", "-1")
        rows = [line for line in result.stdout.splitlines() if line.strip()]
        require(len(rows) == 1 and rows[0].startswith(TOPIC + ":0:"), "UNEXPECTED_TOPIC_PARTITION_SET")
        end = int(rows[0].rsplit(":", 1)[1])
        require(end == len(self.expected_paths), "KAFKA_END_OFFSET_NOT_EXACT")
        output = self.kafka_cli("kafka-console-consumer.sh", "--topic", TOPIC, "--partition", "0", "--offset", "0",
                                "--max-messages", str(end), "--timeout-ms", "5000", "--property", "print.key=true",
                                "--property", "key.separator=\t", timeout=20).stdout
        result = validate_records(output, self.expected_paths)
        result.update(partitions=1, endOffset=end, verification="Real Kafka partition read, not a synthetic ACK callback")
        return result

    def schema_case(self):
        status, _, body = self.http("/sender/schema", diagnostic=True)
        require(status == 200, "SCHEMA_DIAGNOSTIC_NOT_200")
        data = json.loads(body)
        expected = {"defaultValid": True, "defaultValue": 2, "minValue": 1, "maxValue": 8,
                    "accept0": False, "accept1": True, "accept2": True, "accept8": True,
                    "accept9": False, "accept1.5": False}
        require(data == expected, "REAL_PLUGIN_SCHEMA_CONTRACT_MISMATCH")
        return {"realPluginSchema": data, "runtimeWorkerCount": 1,
                "defaultIsExercisedWithoutConfigField": True, "max8IsSchemaAcceptanceNotAnEightSlotLoadTest": True}

    def normal_case(self):
        self.requests(["A%03d" % i for i in range(30)])
        snapshot = self.wait_counts(30, 30, 0, 0)
        return {"http302": 30, "terminal": snapshot, "kafka": self.verify_kafka()}

    def paused_admission(self, paths, attempted, delivered, rejected):
        self.owned_label(self.broker)
        self.docker("pause", self.broker)
        self.broker_paused = True
        started = time.monotonic()
        try:
            self.requests(paths, accepted=False)
            deadline = started + 0.7
            found = None
            while time.monotonic() < deadline:
                candidate = self.sample()
                v = candidate["values"]
                if (v["observation_complete"] == 1 and v["events_attempted"] == attempted
                        and v["events_delivered"] == delivered and v["events_failed"] == 0
                        and v["events_rejected"] == rejected and v["pending_count"] == 2
                        and v["inflight_count"] == 2 and v["queued_count"] == 0 and v["active_senders"] == 2):
                    found = candidate
                    break
                time.sleep(0.01)
            require(found is not None, "TWO_SIMULTANEOUS_REAL_SENDS_NOT_OBSERVED")
            elapsed = time.monotonic() - started
            require(elapsed < 0.8, "PAUSE_TOO_LONG_ACK_RETRY_COULD_DUPLICATE")
            return {"pauseUntilObservationMs": round(elapsed * 1000, 2), "blocked": sanitized_metrics(found),
                    "noDeliveryBeforeBrokerResumed": True}
        finally:
            self.docker("unpause", self.broker)
            self.broker_paused = False

    def concurrent_case(self):
        held = self.paused_admission(["B000", "B001"], 32, 30, 0)
        self.expected_paths.update(["B000", "B001"])
        self.wait_counts(32, 32, 0, 0)
        self.requests(["B%03d" % i for i in range(2, 30)])
        snapshot = self.wait_counts(60, 60, 0, 0)
        return {"http302": 30, "twoSlots": held, "terminal": snapshot, "kafka": self.verify_kafka()}

    def queue_case(self):
        self.write_profile("queue2", queue_count=2, explicit_concurrency=2)
        self.ready("queue2")
        held = self.paused_admission(["C%03d" % i for i in range(8)], 68, 60, 6)
        require(held["blocked"]["rejectionsByReason"]["queue_count"] == 6, "QUEUE_REJECTION_REASON_MISMATCH")
        self.expected_paths.update(["C000", "C001"])
        snapshot = self.wait_counts(68, 62, 0, 6)
        return {"http302": 8, "admitted": 2, "queueCountRejected": 6, "queueBudget": 2,
                "twoSlotsDoNotDoubleBudget": True, "twoSlots": held, "terminal": snapshot, "kafka": self.verify_kafka()}

    def outage_case(self):
        self.write_profile("outage", queue_count=64, explicit_concurrency=2)
        self.ready("outage")
        self.owned_label(self.broker)
        self.docker("stop", "--time", "10", self.broker, timeout=20)
        state = self.docker("inspect", "--format", "{{.State.Running}}", self.broker).stdout.strip()
        require(state == "false", "BROKER_DID_NOT_STOP")
        started = time.monotonic()
        self.requests(["D000", "D001"], accepted=False)
        failed = self.wait_counts(70, 62, 2, 6)
        failure_ms = round((time.monotonic() - started) * 1000, 2)
        self.owned_label(self.broker)
        self.docker("start", self.broker)
        self.kafka_ready()
        unchanged = self.verify_kafka()
        self.requests(["E%03d" % i for i in range(30)])
        final = self.wait_counts(100, 92, 2, 6)
        return {"http302DuringOutage": 2, "boundedFailureMs": failure_ms, "failed": failed,
                "noOutageEventInKafka": unchanged, "recoveryHttp302": 30, "terminal": final,
                "kafka": self.verify_kafka(), "contract": "Expected failed/rejected outcomes remain visible, never relabelled delivered"}

    def record(self, identity, operation):
        started = time.monotonic()
        try:
            self.results.append({"id": identity, "passed": True, "details": operation(),
                                 "elapsedMs": round((time.monotonic() - started) * 1000, 2)})
        except Exception as error:
            self.results.append({"id": identity, "passed": False, "error": public_error(error),
                                 "elapsedMs": round((time.monotonic() - started) * 1000, 2)})
            raise

    def cleanup(self):
        errors = []
        if self.network_create_started and not self.network_id:
            # A timeout cannot prove whether Docker created the network. Do not
            # scan/prune resources or report successful cleanup without its ID.
            errors.append("NETWORK_CREATE_RESULT_UNKNOWN_MANUAL_OWNERSHIP_REVIEW_REQUIRED")
        if self.broker_paused and self.broker in self.owned:
            try:
                self.owned_label(self.broker)
                self.docker("unpause", self.broker)
                self.broker_paused = False
            except Exception:
                errors.append("OWNED_BROKER_UNPAUSE_FAILED")
        for identity in reversed(self.owned):
            try:
                self.owned_label(identity)
                self.docker("rm", "--force", "--volumes", identity)
            except Exception:
                errors.append("OWNED_CONTAINER_CLEANUP_FAILED")
        if self.network_id:
            try:
                self.owned_label(self.network_id, network=True)
                self.docker("network", "rm", self.network_id)
            except Exception:
                errors.append("OWNED_NETWORK_CLEANUP_FAILED")
        self.cleanup_complete = not errors
        return errors

    def run(self):
        problem, cleanup_errors = None, []
        started = time.monotonic()
        plugin = APISIX_ROOT / "plugins/apisix/plugins/shortlink-request-logger.lua"
        source_hash = digest(plugin.read_bytes())
        try:
            self.start()
            self.record("SC01-real-schema-default2-max8", self.schema_case)
            self.record("SC02-real-kafka-first30", self.normal_case)
            self.record("SC03-two-inflight-total60", self.concurrent_case)
            self.record("SC04-shared-queue-two-not-four", self.queue_case)
            self.record("SC05-broker-outage-and-recovery", self.outage_case)
            require(digest(plugin.read_bytes()) == source_hash, "PLUGIN_CHANGED_DURING_COMPONENT_RUN")
        except BaseException as error:
            problem = public_error(error)
        finally:
            cleanup_errors = self.cleanup()
        result = {"schema": "shortlink_apisix_sender_component_v1", "runId": self.run_id,
                  "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "passed": problem is None and not cleanup_errors,
                  "error": problem, "cleanupComplete": self.cleanup_complete, "cleanupErrors": cleanup_errors,
                  "elapsedMs": round((time.monotonic() - started) * 1000, 2), "cases": self.results,
                  "images": self.image_ids, "pluginSha256": source_hash, "requestCount": self.request_count,
                  "expectedKafkaRecords": len(self.expected_paths), "hostPortsPublished": 0,
                  "runtime": {"workers": 1, "sendConcurrency": 2, "kafkaPartitions": 1, "kafkaReplicationFactor": 1},
                  "limits": ["Single worker/single broker component, not production capacity or replicated durability proof",
                             "Queue-count bound tested; queue-bytes and max8 parallel runtime are not exercised",
                             "No duplicate observed in these finite successful sends; at-least-once does not promise global exactly-once",
                             "Short broker pause must be observed within 800ms; slow test hosts fail rather than mask ACK retry ambiguity",
                             "Outage is fail-before-send; unknown ACK-after-append duplication is not injected",
                             "No shutdown/crash/reload loss guarantee is established by this component"]}
        (self.folder / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (self.folder / "metrics.jsonl").write_text("".join(json.dumps(s, sort_keys=True) + "\n" for s in self.samples), encoding="utf-8")
        return result


def synthetic_metrics():
    values = {name: 0 for name in COUNTERS}
    values.update(events_attempted=8, events_rejected=6, pending_count=2, pending_bytes=1024,
                  inflight_count=2, active_senders=2, observation_complete=1,
                  retained_worker_generations=1, started_at_seconds=1)
    rows = [PREFIX + key + " " + str(value) for key, value in values.items()]
    rows.append(PREFIX + 'instance_info{instance="fixture",boot_id="boot"} 1')
    for field in WORKER_FIELDS:
        rows.append(PREFIX + 'worker_' + field + '{worker_id="0",generation="synthetic"} ' + str(values[field]))
    for reason in REASONS:
        rows.append(PREFIX + 'rejections_by_reason{reason="' + reason + '"} ' + str(6 if reason == "queue_count" else 0))
    return "\n".join(rows) + "\n"


def static_checks():
    results = []

    def check(identity, operation):
        try:
            details = operation()
            results.append({"id": identity, "passed": True, "details": details})
        except Exception as error:
            results.append({"id": identity, "passed": False, "error": public_error(error)})

    def parser():
        valid = synthetic_metrics()
        result = parse_metrics(valid)
        require(result["values"]["inflight_count"] == 2 and result["values"]["pending_count"] == 2, "STATIC_SLOT_INVARIANT")
        variants = [valid.replace(PREFIX + "pending_count 2\n", ""), valid + PREFIX + "pending_count 2\n",
                    valid.replace(PREFIX + "events_attempted 8", PREFIX + "events_attempted 7"),
                    valid.replace(PREFIX + "queued_count 0", PREFIX + "queued_count 2"),
                    valid.replace(PREFIX + "pending_count 2", PREFIX + "pending_count NaN"),
                    valid.replace(PREFIX + "pending_count 2", PREFIX + "pending_count -1"),
                    valid.replace('reason="queue_count"} 6', 'reason="queue_count"} 0'),
                    valid.replace('worker_inflight_count{worker_id="0",generation="synthetic"} 2',
                                  'worker_inflight_count{worker_id="0",generation="synthetic"} 1')]
        for candidate in variants:
            try:
                parse_metrics(candidate)
            except (AssertionError, ValueError):
                continue
            raise AssertionError("UNSAFE_METRIC_SAMPLE_ACCEPTED")
        concurrent = valid.replace(PREFIX + "observation_complete 1", PREFIX + "observation_complete 0")
        concurrent = concurrent.replace(PREFIX + "events_attempted 8", PREFIX + "events_attempted 9")
        require(parse_metrics(concurrent)["values"]["observation_complete"] == 0, "INCOMPLETE_SAMPLE_WAS_PROMOTED")
        return {"negativeVariants": len(variants), "incompleteRemainsIncomplete": True}

    def records():
        event = {"decisionId": "v1:123:instance:request", "schemaVersion": 1, "occurredAt": 123,
                 "producerInstanceId": "instance", "source": "APISIX", "stage": "EDGE", "method": "GET",
                 "status": 302, "reason": "UPSTREAM_RESPONSE", "domainNorm": HOST, "shortUri": "A000",
                 "requestId": "request", "traceId": "request"}
        line = event["decisionId"] + "\t" + json.dumps(event) + "\n"
        require(validate_records(line, {"A000"})["uniqueDecisionIds"] == 1, "SYNTHETIC_RECORD_NOT_ACCEPTED")
        variants = [line + line, "different-key\t" + json.dumps(event), line.replace('"occurredAt": 123', '"occurredAt": 124'),
                    line.replace('"shortUri": "A000"', '"shortUri": "B000"'),
                    line.replace('"status": 302', '"status": 500')]
        for candidate in variants:
            try:
                validate_records(candidate, {"A000"})
            except (AssertionError, ValueError):
                continue
            raise AssertionError("UNSAFE_KAFKA_RECORD_ACCEPTED")
        return {"negativeVariants": len(variants), "payloadsNeverWrittenToReport": True}

    def fixture():
        config = manifest("default2")
        logger = config["routes"][1]["plugins"]["shortlink-request-logger"]
        require("send_concurrency" not in logger, "DEFAULT_CONCURRENCY_NOT_EXERCISED")
        require(set(config) == {"routes"} and len(config["routes"]) == 2, "UNEXPECTED_COMPONENT_ROUTE_SHAPE")
        require("shortlink-request-logger" not in config["routes"][0]["plugins"], "READINESS_WOULD_POLLUTE_EVENT_COUNT")
        require(logger_configuration(2, 2)["queue_count"] == 2, "QUEUE_BUDGET_NOT_SHARED")
        return {"readinessHasNoLogger": True, "defaultFieldOmitted": True, "noProductionManifestMutation": True}

    def ownership():
        component = object.__new__(Component)
        component.run_id, component.owned, component.network_id = "synthetic", ["a" * 64], "b" * 64
        for identity, network in (("c" * 64, False), ("c" * 64, True)):
            with patch.object(Component, "docker") as command:
                try:
                    component.owned_label(identity, network)
                except AssertionError:
                    command.assert_not_called()
                else:
                    raise AssertionError("UNOWNED_RESOURCE_WAS_INSPECTED")
        with patch.object(Component, "docker") as command:
            command.return_value.stdout = "different-owner\n"
            try:
                component.owned_label("a" * 64)
            except AssertionError:
                pass
            else:
                raise AssertionError("WRONG_LABEL_WAS_ACCEPTED")
        return {"exactIdAndLabelGuards": 3, "realDockerCommands": 0}

    with patch("subprocess.run", side_effect=AssertionError("STATIC_TEST_ATTEMPTED_SUBPROCESS")), \
            patch("http.client.HTTPConnection", side_effect=AssertionError("STATIC_TEST_ATTEMPTED_HTTP")):
        check("SS01-strict-metrics-and-conservation", parser)
        check("SS02-exact-kafka-content-no-duplicate", records)
        check("SS03-isolated-manifest-default-and-queue", fixture)
        check("SS04-cleanup-ownership", ownership)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--static-only", action="store_true")
    action.add_argument("--run-component", action="store_true")
    args = parser.parse_args()
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    if args.static_only:
        cases = static_checks()
        result = {"schema": "shortlink_apisix_sender_static_v1", "passed": all(case["passed"] for case in cases),
                  "cases": cases, "realDockerCommands": 0, "realHttpRequests": 0,
                  "scope": "Offline harness logic only; does not claim real Kafka/APISIX verification"}
        path = OUTPUT_ROOT / "static-result.json"
        path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    else:
        component = Component()
        result = component.run()
        path = component.folder / "result.json"
    print(json.dumps({"passed": result["passed"], "cases": len(result["cases"]), "report": str(path)}, ensure_ascii=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
