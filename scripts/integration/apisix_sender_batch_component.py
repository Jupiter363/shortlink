"""Finite real Kafka/APISIX batch component; the primary agent runs it.

PYTHONPATH=.work/apisix-bootstrap-deps python3 -B scripts/integration/apisix_sender_batch_component.py --static-only
PYTHONPATH=.work/apisix-bootstrap-deps python3 -B scripts/integration/apisix_sender_batch_component.py --run-component

No host ports, image pulls or existing dependencies/topics are used. Exactly
72 synthetic business GETs: eight individually acknowledged warm requests and
64 requests during a short pause of this component's own Kafka broker. Reports
contain counters and hashes, never consumed payloads, credentials or Docker logs.
"""
import argparse
import datetime as dt
import http.client
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import socket
import sys
import time
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
FOUR_PATH = ROOT / "scripts/integration/apisix_sender_four_component.py"
SPEC = importlib.util.spec_from_file_location("sender_four_parent", FOUR_PATH)
four = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(four)
base = four.base
WARM, HELD, CONCURRENCY, BATCH_SIZE = 8, 64, 4, 32
QUEUE_COUNT, QUEUE_BYTES, BATCH_BYTES = 1000, 8 * 1024 * 1024, 65536
BATCH_COUNTERS = ("calls", "event_attempts", "produce_requests", "produce_records",
                  "metadata_requests", "observation_complete")
CONTROL_BODY_LIMIT, CONTROL_TIMEOUT = 256 * 1024, 0.25


class DockerUnixConnection(http.client.HTTPConnection):
    """Only the local Docker daemon socket; no TCP endpoint or caller URL."""
    def __init__(self):
        super().__init__("localhost", timeout=CONTROL_TIMEOUT)

    def connect(self):
        channel = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            channel.settimeout(self.timeout)
            channel.connect("/var/run/docker.sock")
        except BaseException:
            channel.close()
            raise
        self.sock = channel


def docker_control_http(identity, action):
    """Bounded private control transport; ownership is checked by the caller."""
    base.require(re.fullmatch(r"[a-f0-9]{64}", identity or "") is not None, "CONTROL_REQUIRES_FULL_CONTAINER_ID")
    base.require(action in ("inspect", "pause", "unpause"), "DOCKER_CONTROL_ACTION_NOT_ALLOWED")
    method, suffix, expected = ("GET", "json", 200) if action == "inspect" else ("POST", action, 204)
    connection = DockerUnixConnection()
    try:
        connection.request(method, "/v1.44/containers/" + identity + "/" + suffix,
                           body=None, headers={"Connection": "close"})
        response = connection.getresponse()
        body = response.read(CONTROL_BODY_LIMIT + 1)
        base.require(len(body) <= CONTROL_BODY_LIMIT, "DOCKER_CONTROL_RESPONSE_TOO_LARGE")
        base.require(response.status == expected, "DOCKER_CONTROL_HTTP_STATUS_" + str(response.status))
        if action != "inspect":
            base.require(not body, "DOCKER_CONTROL_UNEXPECTED_MUTATION_BODY")
            return None
        try:
            data = json.loads(body)
        except (ValueError, UnicodeError):
            raise base.ComponentFailure("DOCKER_CONTROL_INVALID_INSPECT_JSON") from None
        base.require(isinstance(data, dict), "DOCKER_CONTROL_INVALID_INSPECT_SHAPE")
        return data
    except (OSError, http.client.HTTPException):
        raise base.ComponentFailure("DOCKER_CONTROL_TRANSPORT_" + action.upper()) from None
    finally:
        connection.close()


def batch_profile():
    value = base.manifest("batch32", queue_count=QUEUE_COUNT, explicit_concurrency=CONCURRENCY)
    logger = value["routes"][1]["plugins"]["shortlink-request-logger"]
    logger.update(queue_bytes=QUEUE_BYTES, send_batch_size=BATCH_SIZE, send_batch_bytes=BATCH_BYTES)
    return value


def topic_arguments(program, args):
    """Change only the parent's private topic creation to exactly two partitions."""
    result = list(args)
    if program == "kafka-topics.sh" and "--create" in result:
        base.require(result.count("--topic") == 1 and result[result.index("--topic") + 1] == base.TOPIC,
                     "UNEXPECTED_TOPIC_CREATION")
        base.require(result.count("--partitions") == 1, "PARTITION_ARGUMENT_MISSING_OR_DUPLICATE")
        result[result.index("--partitions") + 1] = "2"
    return result


def parse_batch_metrics(body):
    snapshot = base.parse_metrics(body)
    batch = {}
    for line in body.splitlines():
        if not line or line.startswith("#"):
            continue
        match = base.SAMPLE_RE.match(line)
        if not match or not match.group(1).startswith(base.PREFIX + "batch_"):
            continue
        name = match.group(1)[len(base.PREFIX + "batch_"):]
        base.require(name in BATCH_COUNTERS and not match.group(2) and name not in batch,
                     "UNKNOWN_DUPLICATE_OR_LABELLED_BATCH_METRIC")
        value = float(match.group(3))
        base.require(math.isfinite(value) and value >= 0 and value.is_integer(), "INVALID_BATCH_METRIC_VALUE")
        batch[name] = int(value)
    base.require(set(batch) == set(BATCH_COUNTERS), "MISSING_BATCH_METRIC")
    base.require(batch["observation_complete"] in (0, 1), "INVALID_BATCH_OBSERVATION_COMPLETE")
    snapshot["batch"] = batch
    return snapshot


def enforce_budget(snapshot):
    values = snapshot["values"]
    base.require(values["pending_count"] <= QUEUE_COUNT and values["pending_bytes"] <= QUEUE_BYTES,
                 "SHARED_QUEUE_BUDGET_EXCEEDED")
    base.require(values["inflight_count"] <= CONCURRENCY * BATCH_SIZE
                 and values["inflight_count"] <= values["pending_count"]
                 and values["active_senders"] <= CONCURRENCY, "ITEM_OR_SLOT_BUDGET_EXCEEDED")
    base.require(values["observation_faults"] == 0, "OBSERVATION_FAULT")


def public_snapshot(snapshot):
    return {**base.sanitized_metrics(snapshot), "batch": snapshot["batch"]}


def paused_complete(snapshot, held):
    values = snapshot["values"]
    return (values["observation_complete"] == 1 and snapshot["batch"]["observation_complete"] == 1
            and values["events_attempted"] == WARM + held and values["events_delivered"] == WARM
            and values["events_failed"] == 0 and values["events_rejected"] == 0
            and values["pending_count"] == held and len(snapshot["workers"]) == 1)


def parse_offsets(output):
    offsets = {}
    for line in output.splitlines():
        if not line.strip():
            continue
        fields = line.strip().split(":")
        base.require(len(fields) == 3 and fields[0] == base.TOPIC
                     and fields[1] in ("0", "1") and fields[2].isdigit(), "INVALID_TOPIC_OFFSET_BOUNDARY")
        partition, end = int(fields[1]), int(fields[2])
        base.require(partition not in offsets and 0 <= end <= WARM + HELD, "DUPLICATE_OR_EXCESS_TOPIC_OFFSET")
        offsets[partition] = end
    base.require(set(offsets) == {0, 1}, "UNEXPECTED_TOPIC_PARTITION_SET")
    return offsets


def batch_proof(before, after, expected):
    base.require(before["observation_complete"] == after["observation_complete"] == 1,
                 "BATCH_COVERAGE_INCOMPLETE")
    delta = {key: after[key] - before[key] for key in BATCH_COUNTERS if key != "observation_complete"}
    base.require(all(value >= 0 for value in delta.values()), "BATCH_COUNTER_RESET")
    base.require(delta["event_attempts"] == delta["produce_records"] == expected,
                 "BATCH_ITEM_ATTEMPTS_NOT_EXACT")
    base.require(0 < delta["produce_requests"] < delta["produce_records"]
                 and 0 < delta["calls"] <= expected, "NO_REAL_MULTI_RECORD_PRODUCE_PROOF")
    return {"delta": delta, "recordsPerProduceRequest": delta["produce_records"] / delta["produce_requests"],
            "wireRecordsExceedProduceRequests": True,
            "meaning": "Real adapter transport counters plus exact broker records; not async enqueue success"}


class BatchComponent(four.FourSlotComponent):
    def __init__(self):
        super().__init__()
        self.last_snapshot, self.warm_snapshot, self.blocked = None, None, None

    def broker_control(self, action):
        base.require(action in ("pause", "unpause"), "BROKER_CONTROL_ACTION_NOT_ALLOWED")
        base.require(self.broker in self.owned and re.fullmatch(r"[a-f0-9]{64}", self.broker or "") is not None,
                     "UNOWNED_BROKER_CONTROL_ID")
        # Inspect immediately before either mutation. Nothing from the raw
        # Docker response (notably Config.Env) is retained in public evidence.
        inspected = docker_control_http(self.broker, "inspect")
        config, state = inspected.get("Config"), inspected.get("State")
        base.require(inspected.get("Id") == self.broker and isinstance(config, dict)
                     and isinstance(config.get("Labels"), dict)
                     and config["Labels"].get(base.LABEL) == self.run_id, "BROKER_CONTROL_OWNERSHIP_MISMATCH")
        base.require(isinstance(state, dict) and type(state.get("Paused")) is bool,
                     "BROKER_CONTROL_PAUSE_STATE_UNKNOWN")
        if action == "pause":
            base.require(not state["Paused"], "BROKER_ALREADY_PAUSED_BEFORE_FAULT")
            # The request can reach Docker even if its response is lost.
            # Set the recovery intent before sending, and keep it on failure.
            self.broker_paused = True
            docker_control_http(self.broker, "pause")
        else:
            if state["Paused"]:
                docker_control_http(self.broker, "unpause")
            # A successful re-inspect that already reports false also resolves
            # an earlier ambiguous unpause without a spurious second POST.
            self.broker_paused = False

    def docker(self, *args, **kwargs):
        # Inherited cleanup may retry an ambiguous unpause. Keep that retry on
        # the same allowlisted Unix transport and repeat the ownership proof.
        if args and args[0] in ("pause", "unpause"):
            base.require(len(args) == 2 and args[1] == self.broker, "UNOWNED_DOCKER_PAUSE_REQUEST")
            self.broker_control(args[0])
            return SimpleNamespace(returncode=0, stdout="", stderr="")
        return base.Component.docker(self, *args, **kwargs)

    def kafka_cli(self, program, *args, **kwargs):
        return base.Component.kafka_cli(self, program, *topic_arguments(program, args), **kwargs)

    def sample(self):
        status, _, body = self.http("/shortlink/metrics", diagnostic=True)
        base.require(status == 200, "METRICS_HTTP_NOT_200")
        snapshot = parse_batch_metrics(body)
        if self.identity is None:
            self.identity = snapshot["identity"]
        base.require(snapshot["identity"] == self.identity, "APISIX_OBSERVATION_IDENTITY_CHANGED")
        enforce_budget(snapshot)
        self.samples.append({"elapsedMs": round((time.monotonic() - self.sample_started) * 1000, 2),
                             **public_snapshot(snapshot)})
        base.require(len(self.samples) <= 4000, "COMPONENT_SAMPLE_BUDGET_EXCEEDED")
        self.last_snapshot = snapshot
        return snapshot

    def requests(self, paths, accepted=True):
        paths = tuple(paths)
        base.require(self.request_count + len(paths) <= WARM + HELD, "BATCH_COMPONENT_REQUEST_BUDGET_EXCEEDED")
        # Deliberately bypass the four-slot fixture's 34-request cap, retaining
        # the base HTTP assertions and its independent finite 100-request cap.
        return base.Component.requests(self, paths, accepted=accepted)

    def start(self):
        super().start()
        previous = self.manifest_path.stat().st_mtime_ns
        content = base.yaml.safe_dump(batch_profile(), sort_keys=False) + "#END\n"
        # Preserve the inode used by Docker's existing single-file bind mount.
        with self.manifest_path.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        stamp = max(time.time_ns(), previous + 1000000000)
        os.utime(self.manifest_path, ns=(stamp, stamp))
        self.ready("batch32")

    def offsets(self):
        return parse_offsets(self.kafka_cli("kafka-get-offsets.sh", "--topic", base.TOPIC, "--time", "-1").stdout)

    def warm_case(self):
        base.require(self.offsets() == {0: 0, 1: 0}, "FRESH_PRIVATE_TOPIC_NOT_EMPTY")
        for index in range(WARM):
            self.requests(["W%03d" % index])
            self.wait_counts(index + 1, index + 1, 0, 0)
        self.warm_snapshot = self.last_snapshot
        batch = self.warm_snapshot["batch"]
        base.require(batch["observation_complete"] == 1 and batch["metadata_requests"] > 0
                     and batch["event_attempts"] == batch["produce_records"] == WARM,
                     "REAL_BATCH_ADAPTER_WARMUP_NOT_PROVEN")
        return {"http302": WARM, "terminal": public_snapshot(self.warm_snapshot),
                "metadataWarmed": True, "allFourSlotMetadataWarmed": False,
                "topicPartitions": 2, "runtimeWorkerCount": 1}

    def hold_case(self):
        started = time.monotonic()
        held = 0
        try:
            self.broker_control("pause")
            # Small chunks give multiple actual snapshots while the owned
            # broker cannot acknowledge. No callback/timer/clock is mocked.
            for begin in range(0, HELD, 16):
                base.require(time.monotonic() - started < 0.65, "PAUSE_REQUEST_WINDOW_EXHAUSTED")
                self.requests(["B%03d" % index for index in range(begin, begin + 16)])
                held += 16
                snapshot = self.sample()
                base.require(paused_complete(snapshot, held), "PAUSED_EVENTS_NOT_UNCONFIRMED_AND_CONSERVED")
            deadline = started + 0.7
            while time.monotonic() < deadline:
                snapshot = self.sample()
                if (paused_complete(snapshot, HELD) and snapshot["values"]["active_senders"] == CONCURRENCY
                        and snapshot["values"]["queued_count"] >= 40):
                    self.blocked = snapshot
                    break
                time.sleep(0.005)
            base.require(self.blocked is not None, "FOUR_OCCUPIED_SLOTS_AND_FORTY_QUEUED_NOT_OBSERVED")
            observation_ms = round((time.monotonic() - started) * 1000, 2)
        finally:
            # Ownership check occurs again before the only fault reversal.
            # On an ambiguous unpause, inherited cleanup retries this own ID.
            if self.broker_paused:
                self.broker_control("unpause")
        pause_ms = (time.monotonic() - started) * 1000
        base.require(pause_ms < 800, "PAUSE_TOO_LONG_ACK_RETRY_COULD_DUPLICATE")
        return {"http302Added": HELD, "pauseUntilObservationMs": observation_ms,
                "pauseIncludingControlCommandsMs": round(pause_ms, 2),
                "faultControlTransport": "Docker v1.44 API via local AF_UNIX socket",
                "blocked": public_snapshot(self.blocked), "noDeliveryBeforeBrokerResumed": True,
                "meaning": "Four slots own items; a slot may still await metadata, not necessarily Produce ACK"}

    def verify_kafka(self):
        before = self.offsets()
        base.require(sum(before.values()) == len(self.expected_paths), "KAFKA_TOTAL_END_OFFSETS_NOT_EXACT")
        base.require(all(end > 0 for end in before.values()), "BOTH_PARTITIONS_NOT_EXERCISED")
        outputs, counts = [], {}
        for partition, end in sorted(before.items()):
            output = self.kafka_cli("kafka-console-consumer.sh", "--topic", base.TOPIC,
                                    "--partition", str(partition), "--offset", "0", "--max-messages", str(end),
                                    "--timeout-ms", "5000", "--property", "print.key=true", "--property",
                                    "key.separator=\t", timeout=20).stdout
            counts[partition] = len([line for line in output.splitlines() if line])
            base.require(counts[partition] == end, "PARTITION_READ_DID_NOT_REACH_BOUNDARY")
            outputs.append(output.rstrip("\n") + "\n")
        result = base.validate_records("".join(outputs), self.expected_paths)
        after = self.offsets()
        base.require(before == after, "KAFKA_OFFSETS_CHANGED_DURING_EXACT_VERIFICATION")
        result.update(partitions=2, endOffsets=before, recordsByPartition=counts,
                      endOffsetsRecheckedUnchanged=True, allRecordsInBothPartitionsRead=True)
        return result

    def terminal_case(self):
        self.wait_counts(WARM + HELD, WARM + HELD, 0, 0)
        terminal = self.last_snapshot
        proof = batch_proof(self.warm_snapshot["batch"], terminal["batch"], HELD)
        for key in ("retry_attempts", "producer_failures", "timer_failures", "drain_errors"):
            base.require(terminal["values"][key] == self.warm_snapshot["values"][key] == 0,
                         "UNEXPECTED_SENDER_FAILURE_OR_RETRY")
        kafka = self.verify_kafka()
        self.sample()
        base.require(self.last_snapshot["values"] == terminal["values"]
                     and self.last_snapshot["batch"] == terminal["batch"], "TERMINAL_COUNTERS_CHANGED_DURING_KAFKA_READ")
        base.require(self.request_count == WARM + HELD and kafka["uniqueDecisionIds"] == WARM + HELD,
                     "FINAL_HTTP_KAFKA_BUDGET_MISMATCH")
        return {"totalHttp302": self.request_count, "terminal": public_snapshot(terminal),
                "batchProof": proof, "kafka": kafka,
                "maxObservedInflightItems": max(row["counters"]["inflight_count"] for row in self.samples),
                "maxObservedActiveSlots": max(row["counters"]["active_senders"] for row in self.samples),
                "inflightUnit": "items", "activeSendersUnit": "slots"}

    def run(self):
        started, problem, hashes = time.monotonic(), None, {}
        files = source_files()
        try:
            hashes = {key: base.digest(path.read_bytes()) for key, path in files.items()}
            self.start()
            self.record("BC01-real-batch-adapter-warm-two-partitions", self.warm_case)
            self.record("BC02-owned-broker-pause-shared-item-budget", self.hold_case)
            self.record("BC03-real-wire-batch-exact-two-partition-content", self.terminal_case)
            base.require(all(base.digest(path.read_bytes()) == hashes[key] for key, path in files.items()),
                         "COMPONENT_SOURCE_CHANGED_DURING_RUN")
        except BaseException as error:
            problem = base.public_error(error)
        finally:
            cleanup_errors = self.cleanup()
        result = {"schema": "shortlink_apisix_batch_component_v1", "runId": self.run_id,
                  "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                  "passed": problem is None and not cleanup_errors, "error": problem,
                  "cleanupComplete": self.cleanup_complete, "cleanupErrors": cleanup_errors,
                  "elapsedMs": round((time.monotonic() - started) * 1000, 2), "cases": self.results,
                  "sourceSha256": hashes, "images": self.image_ids, "requestCount": self.request_count,
                  "expectedKafkaRecords": len(self.expected_paths), "hostPortsPublished": 0,
                  "runtime": {"workers": 1, "sendConcurrency": CONCURRENCY, "sendBatchSize": BATCH_SIZE,
                              "sendBatchBytes": BATCH_BYTES, "queueCount": QUEUE_COUNT, "queueBytes": QUEUE_BYTES,
                              "kafkaPartitions": 2, "kafkaBrokers": 1, "kafkaReplicationFactor": 1},
                  "limits": ["Finite 72-GET component; not throughput, long-run or replicated durability evidence",
                             "Single broker with two partitions; no multi-broker partial ACK failure injected",
                             "Real synchronous adapter/ACKs; no mocked producer, queue, timer or delivery callback",
                             "Short own-broker pause must finish within 800ms; slow hosts fail this component",
                             "Slot ownership during pause does not prove each slot has sent a Produce request",
                             "Item in-flight peak is sampled; short post-resume batches may fall between samples",
                             "No unknown ACK-after-append, process-crash or global exactly-once guarantee",
                             "Default batch1 compatibility belongs to the existing original components"]}
        with (self.folder / "result-batch.json").open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(result, indent=2) + "\n")
        with (self.folder / "metrics-batch.jsonl").open("x", encoding="utf-8") as stream:
            stream.write("".join(json.dumps(row, sort_keys=True) + "\n" for row in self.samples))
        return result


def source_files():
    return {"componentBatch": Path(__file__), "componentFour": FOUR_PATH, "parentComponent": four.PARENT,
            "logger": base.APISIX_ROOT / "plugins/apisix/plugins/shortlink-request-logger.lua",
            "adapter": base.APISIX_ROOT / "plugins/apisix/plugins/shortlink-kafka-batch.lua",
            **{name: ROOT / ("scripts/performance/" + name + ".py") for name in
               ("test_edge_metrics", "test_edge_sender_concurrency", "test_edge_sender_batch", "test_edge_kafka_batch")}}


def static_checks():
    cases = []

    def rejected(operation):
        try:
            operation()
        except (AssertionError, ValueError):
            return
        raise AssertionError("INVALID_STATIC_FIXTURE_ACCEPTED")

    with patch("subprocess.run", side_effect=AssertionError("STATIC_TEST_ATTEMPTED_SUBPROCESS")), \
            patch("http.client.HTTPConnection", side_effect=AssertionError("STATIC_TEST_ATTEMPTED_HTTP")), \
            patch("socket.socket", side_effect=AssertionError("STATIC_TEST_ATTEMPTED_SOCKET")):
        # Also retain the parent strict content/conservation/ownership tests.
        parent_cases = base.static_checks()
        base.require(all(case["passed"] for case in parent_cases), "PARENT_STATIC_CONTRACT_FAILED")
        cases.extend(parent_cases)
        values = {key: 0 for key in BATCH_COUNTERS}
        values["observation_complete"] = 1
        text = base.synthetic_metrics() + "".join(base.PREFIX + "batch_" + key + " " + str(value) + "\n"
                                                  for key, value in values.items())
        snapshot = parse_batch_metrics(text)
        enforce_budget(snapshot)
        for invalid in (text.replace(base.PREFIX + "batch_calls 0\n", ""),
                        text + base.PREFIX + "batch_calls 0\n",
                        text.replace("batch_calls 0", 'batch_calls{unexpected="label"} 0'),
                        text.replace("batch_calls 0", "batch_calls NaN"),
                        text.replace("batch_calls 0", "batch_calls -1"),
                        text.replace("batch_calls 0", "batch_calls 1.5")):
            rejected(lambda invalid=invalid: parse_batch_metrics(invalid))
        base.require(parse_batch_metrics(text.replace("batch_observation_complete 1", "batch_observation_complete 0"))
                     ["batch"]["observation_complete"] == 0, "INCOMPLETE_BATCH_PROMOTED")
        cases.append({"id": "BS01-strict-batch-metrics", "passed": True, "negativeVariants": 6})
        for field, value in (("pending_count", QUEUE_COUNT + 1), ("pending_bytes", QUEUE_BYTES + 1),
                             ("inflight_count", 129), ("active_senders", 5)):
            altered = {**snapshot, "values": dict(snapshot["values"])}
            altered["values"][field] = value
            rejected(lambda altered=altered: enforce_budget(altered))
        valid = {**snapshot, "values": dict(snapshot["values"]), "workers": {"synthetic": {}}}
        valid["values"].update(events_attempted=72, events_delivered=8, events_rejected=0, pending_count=64,
                               queued_count=60, inflight_count=4, active_senders=4)
        base.require(paused_complete(valid, HELD), "VALID_HELD_ITEMS_REJECTED")
        for field, value in (("events_delivered", 9), ("events_failed", 1), ("observation_complete", 0)):
            altered = {**valid, "values": dict(valid["values"])}
            altered["values"][field] = value
            base.require(not paused_complete(altered, HELD), "FALSE_PAUSED_ACK_PROOF")
        cases.append({"id": "BS02-item-slot-byte-bounds-and-no-premature-delivery", "passed": True, "negativeVariants": 7})
        offsets = base.TOPIC + ":0:31\n" + base.TOPIC + ":1:41\n"
        base.require(parse_offsets(offsets) == {0: 31, 1: 41}, "VALID_OFFSET_SET_REJECTED")
        for invalid in (base.TOPIC + ":0:72\n", offsets + base.TOPIC + ":0:31\n",
                        offsets.replace(":1:41", ":2:41"), offsets.replace(":1:41", ":1:-1")):
            rejected(lambda invalid=invalid: parse_offsets(invalid))
        cases.append({"id": "BS03-two-partition-boundaries-not-fixed-max-only", "passed": True, "negativeVariants": 4})
        after = dict(values, calls=6, event_attempts=64, produce_requests=6, produce_records=64, metadata_requests=3)
        base.require(batch_proof(values, after, 64)["wireRecordsExceedProduceRequests"], "VALID_WIRE_BATCH_REJECTED")
        for invalid in (dict(after, produce_requests=64), dict(after, produce_records=65),
                        dict(after, event_attempts=65), dict(after, observation_complete=0)):
            rejected(lambda invalid=invalid: batch_proof(values, invalid, 64))
        cases.append({"id": "BS04-actual-produce-records-exceed-requests", "passed": True, "negativeVariants": 4})
        config = batch_profile()
        logger = config["routes"][1]["plugins"]["shortlink-request-logger"]
        base.require(logger["send_concurrency"] == 4 and logger["send_batch_size"] == 32
                     and logger["send_batch_bytes"] == 65536 and logger["queue_count"] == 1000
                     and logger["queue_bytes"] == 8388608, "BATCH_RUNTIME_PROFILE_MISMATCH")
        arguments = topic_arguments("kafka-topics.sh", ("--create", "--topic", base.TOPIC, "--partitions", "1"))
        base.require(arguments[-1] == "2" and topic_arguments("kafka-topics.sh", ("--list",)) == ["--list"],
                     "PRIVATE_TOPIC_PARTITION_WIRING_MISMATCH")
        base.require("shortlink-request-logger" not in config["routes"][0]["plugins"], "READINESS_POLLUTES_EVENT_COUNT")
        cases.append({"id": "BS05-private-profile-and-two-partition-creation", "passed": True})
        identity = "a" * 64
        component = object.__new__(BatchComponent)
        component.run_id, component.broker, component.owned, component.broker_paused = "synthetic", identity, [identity], False
        inspected = {"Id": identity, "Config": {"Labels": {base.LABEL: "synthetic"}}, "State": {"Paused": False}}
        # These mocks replace only the daemon control transport. The runtime
        # Kafka producer/ACK and APISIX data path are never mocked.
        control_name = __name__ + ".docker_control_http"
        with patch(control_name) as control:
            component.owned = []
            rejected(lambda: component.broker_control("pause"))
            control.assert_not_called()
            component.owned = [identity]
            for wrong in ({**inspected, "Id": "b" * 64},
                          {**inspected, "Config": {"Labels": {base.LABEL: "other"}}},
                          {**inspected, "State": {"Paused": "false"}}):
                control.reset_mock()
                control.return_value = wrong
                rejected(lambda: component.broker_control("pause"))
                base.require(control.call_count == 1, "UNPROVEN_OWNER_WAS_MUTATED")
            control.reset_mock()
            control.side_effect = [inspected, base.ComponentFailure("SYNTHETIC_UNKNOWN_PAUSE")]
            try:
                component.broker_control("pause")
            except base.ComponentFailure:
                pass
            base.require(component.broker_paused, "UNKNOWN_PAUSE_LOST_RECOVERY_INTENT")
            control.reset_mock()
            control.side_effect = [{**inspected, "State": {"Paused": True}}, None]
            component.docker("unpause", identity)
            base.require(not component.broker_paused and control.call_count == 2, "CLEANUP_UNPAUSE_NOT_OWNERSHIP_CHECKED")
            component.broker_paused = True
            control.side_effect = [{**inspected, "State": {"Paused": True}}, base.ComponentFailure("SYNTHETIC_UNKNOWN_UNPAUSE")]
            try:
                component.broker_control("unpause")
            except base.ComponentFailure:
                pass
            base.require(component.broker_paused, "UNKNOWN_UNPAUSE_LOST_RECOVERY_INTENT")
            control.reset_mock()
            control.side_effect = [inspected]
            component.broker_control("unpause")
            base.require(not component.broker_paused and control.call_count == 1, "RESOLVED_UNPAUSE_SENT_SECOND_MUTATION")
        cases.append({"id": "BS06-unix-control-ownership-and-ambiguous-recovery", "passed": True})
        with patch.object(DockerUnixConnection, "request") as request, \
                patch.object(DockerUnixConnection, "getresponse") as response, \
                patch.object(DockerUnixConnection, "close") as close:
            rejected(lambda: docker_control_http("../other", "pause"))
            rejected(lambda: docker_control_http(identity, "delete"))
            request.assert_not_called()
            response.return_value = SimpleNamespace(status=200, read=Mock(return_value=json.dumps(inspected).encode()))
            base.require(docker_control_http(identity, "inspect") == inspected, "VALID_INSPECT_NOT_PARSED")
            base.require(request.call_args[0] == ("GET", "/v1.44/containers/" + identity + "/json"), "CONTROL_URL_NOT_FIXED")
            response.return_value.read.assert_called_once_with(CONTROL_BODY_LIMIT + 1)
            response.return_value = SimpleNamespace(status=204, read=Mock(return_value=b""))
            docker_control_http(identity, "pause")
            base.require(request.call_args[0][0] == "POST", "PAUSE_NOT_POST")
            for status, body in ((500, b"private-do-not-print"), (404, b"private-do-not-print"),
                                 (200, b"x" * (CONTROL_BODY_LIMIT + 1))):
                response.return_value = SimpleNamespace(status=status, read=Mock(return_value=body))
                rejected(lambda: docker_control_http(identity, "inspect"))
            request.side_effect = OSError("private-socket-error")
            try:
                docker_control_http(identity, "pause")
            except base.ComponentFailure as error:
                base.require(str(error) == "DOCKER_CONTROL_TRANSPORT_PAUSE", "CONTROL_ERROR_LEAKS_PRIVATE_DETAIL")
            else:
                raise AssertionError("CONTROL_TRANSPORT_FAILURE_ACCEPTED")
            base.require(close.call_count == 6, "CONTROL_CONNECTION_NOT_ALWAYS_CLOSED")
        cases.append({"id": "BS07-unix-control-response-status-byte-and-close-bounds", "passed": True})
    return {"schema": "shortlink_apisix_batch_static_v1", "passed": True, "cases": cases,
            "sourceSha256": {key: base.digest(path.read_bytes()) for key, path in source_files().items()},
            "realDockerCommands": 0, "realHttpRequests": 0, "outputFilesWritten": 0,
            "scope": "Offline harness checks only; real batch proof requires --run-component"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--static-only", action="store_true")
    action.add_argument("--run-component", action="store_true")
    args = parser.parse_args()
    if args.static_only:
        result = static_checks()
        print(json.dumps(result, indent=2))
    else:
        component = BatchComponent()
        result = component.run()
        print(json.dumps({"passed": result["passed"], "cases": len(result["cases"]),
                          "report": str(component.folder / "result-batch.json")}, ensure_ascii=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
