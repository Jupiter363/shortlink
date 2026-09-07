"""Real four-slot APISIX component; only the primary agent runs Docker/HTTP.

PYTHONPATH=.work/apisix-bootstrap-deps python3 -B scripts/integration/apisix_sender_four_component.py --static-only
PYTHONPATH=.work/apisix-bootstrap-deps python3 -B scripts/integration/apisix_sender_four_component.py --run-component

Reuses the original component's private network, containers, schema/metrics
parser, real Kafka reader and ownership-checked cleanup. No producer callbacks
are mocked. Exactly 34 business GET requests; this is not a throughput test.
"""
import argparse
import datetime as dt
import importlib.util
import json
from pathlib import Path
import sys
import time
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
PARENT = ROOT / "scripts/integration/apisix_sender_component.py"
SPEC = importlib.util.spec_from_file_location("sender_component_parent", PARENT)
base = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(base)


def four_blocked(snapshot):
    values = snapshot["values"]
    expected = {"events_attempted": 34, "events_delivered": 30, "events_failed": 0,
                "events_rejected": 0, "pending_count": 4, "inflight_count": 4,
                "active_senders": 4, "queued_count": 0, "observation_complete": 1,
                "observation_faults": 0}
    return all(values.get(key) == value for key, value in expected.items()) and len(snapshot["workers"]) == 1


class FourSlotComponent(base.Component):
    def __init__(self):
        super().__init__()
        self.concurrency_limit = 2

    def sample(self):
        status, _, body = self.http("/shortlink/metrics", diagnostic=True)
        base.require(status == 200, "METRICS_HTTP_NOT_200")
        snapshot = base.parse_metrics(body)
        if self.identity is None:
            self.identity = snapshot["identity"]
        base.require(snapshot["identity"] == self.identity, "APISIX_OBSERVATION_IDENTITY_CHANGED")
        base.require(snapshot["values"]["observation_faults"] == 0, "OBSERVATION_FAULT")
        base.require(snapshot["values"]["inflight_count"] <= self.concurrency_limit
                     and snapshot["values"]["active_senders"] <= self.concurrency_limit,
                     "CONFIGURED_SLOT_BUDGET_EXCEEDED")
        self.samples.append({"elapsedMs": round((time.monotonic() - self.sample_started) * 1000, 2),
                             "configuredConcurrencyLimit": self.concurrency_limit,
                             **base.sanitized_metrics(snapshot)})
        base.require(len(self.samples) <= 4000, "COMPONENT_SAMPLE_BUDGET_EXCEEDED")
        return snapshot

    def requests(self, paths, accepted=True):
        paths = tuple(paths)
        base.require(self.request_count + len(paths) <= 34, "FOUR_SLOT_COMPONENT_REQUEST_BUDGET_EXCEEDED")
        return super().requests(paths, accepted=accepted)

    def four_case(self):
        self.write_profile("explicit4", queue_count=64, explicit_concurrency=4)
        self.ready("explicit4")
        self.concurrency_limit = 4
        self.owned_label(self.broker)
        self.docker("pause", self.broker)
        self.broker_paused = True
        started = time.monotonic()
        found = None
        try:
            self.requests(["F000", "F001", "F002", "F003"], accepted=False)
            deadline = started + 0.7
            while time.monotonic() < deadline:
                candidate = self.sample()
                if four_blocked(candidate):
                    found = candidate
                    break
                time.sleep(0.01)
            base.require(found is not None, "FOUR_SIMULTANEOUS_REAL_SENDS_NOT_OBSERVED")
            elapsed = time.monotonic() - started
            base.require(elapsed < 0.8, "PAUSE_TOO_LONG_ACK_RETRY_COULD_DUPLICATE")
        finally:
            # The same created/labelled broker is the sole fault target. Its
            # cleanup also handles an exception during this unpause command.
            self.owned_label(self.broker)
            self.docker("unpause", self.broker)
            self.broker_paused = False
        self.expected_paths.update(["F000", "F001", "F002", "F003"])
        terminal = self.wait_counts(34, 34, 0, 0)
        kafka = self.verify_kafka()
        base.require(self.request_count == 34 and kafka["uniqueDecisionIds"] == 34, "FOUR_SLOT_FINAL_RECORD_COUNT_MISMATCH")
        return {"pauseUntilObservationMs": round(elapsed * 1000, 2),
                "blocked": base.sanitized_metrics(found), "noAckBeforeBrokerResumed": True,
                "terminal": terminal, "kafka": kafka, "http302Added": 4,
                "totalHttp302": 34, "configuredConcurrency": 4, "workerCount": 1}

    def run(self):
        started = time.monotonic()
        files = {"componentFour": Path(__file__), "parentComponent": PARENT,
                 "plugin": base.APISIX_ROOT / "plugins/apisix/plugins/shortlink-request-logger.lua"}
        hashes = {key: base.digest(path.read_bytes()) for key, path in files.items()}
        problem = None
        try:
            self.start()
            self.record("FC01-real-default2-first30", self.normal_case)
            self.record("FC02-real-explicit4-inflight-and-total34", self.four_case)
            base.require(all(base.digest(path.read_bytes()) == hashes[key] for key, path in files.items()),
                         "COMPONENT_PARENT_OR_PLUGIN_CHANGED_DURING_RUN")
        except BaseException as error:
            problem = base.public_error(error)
        finally:
            cleanup_errors = self.cleanup()
        result = {"schema": "shortlink_apisix_four_slot_component_v1", "runId": self.run_id,
                  "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                  "passed": problem is None and not cleanup_errors, "error": problem,
                  "cleanupComplete": self.cleanup_complete, "cleanupErrors": cleanup_errors,
                  "elapsedMs": round((time.monotonic() - started) * 1000, 2), "cases": self.results,
                  "sourceSha256": hashes, "images": self.image_ids, "requestCount": self.request_count,
                  "expectedKafkaRecords": len(self.expected_paths), "hostPortsPublished": 0,
                  "runtime": {"workers": 1, "defaultConcurrency": 2, "testedExplicitConcurrency": 4,
                              "kafkaPartitions": 1, "kafkaReplicationFactor": 1},
                  "limits": ["Finite four-slot component, not performance or production durability proof",
                             "No producer callback, ACK, timer or queue implementation mocked",
                             "Exactly 34 GETs; max8 and a fifth queued request are not exercised",
                             "Pause must expose four in-flight calls within 800ms, otherwise fail",
                             "No global exactly-once, unknown-ACK or worker-crash guarantee"]}
        with (self.folder / "result-four.json").open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(result, indent=2) + "\n")
        with (self.folder / "metrics-four.jsonl").open("x", encoding="utf-8") as stream:
            stream.write("".join(json.dumps(row, sort_keys=True) + "\n" for row in self.samples))
        return result


def static_checks():
    # Pure parser/fixture tests. Do not construct Component: its constructor
    # creates the eventual private output directory for a real execution.
    original = base.synthetic_metrics()
    correct = original.replace("events_attempted 8", "events_attempted 34").replace("events_delivered 0", "events_delivered 30")
    correct = correct.replace("events_rejected 6", "events_rejected 0").replace('reason="queue_count"} 6', 'reason="queue_count"} 0')
    for field in ("pending_count", "inflight_count", "active_senders"):
        correct = correct.replace(base.PREFIX + field + " 2", base.PREFIX + field + " 4")
        correct = correct.replace('worker_' + field + '{worker_id="0",generation="synthetic"} 2',
                                  'worker_' + field + '{worker_id="0",generation="synthetic"} 4')
    cases = []
    with patch("subprocess.run", side_effect=AssertionError("STATIC_TEST_ATTEMPTED_SUBPROCESS")), \
            patch("http.client.HTTPConnection", side_effect=AssertionError("STATIC_TEST_ATTEMPTED_HTTP")):
        snapshot = base.parse_metrics(correct)
        base.require(four_blocked(snapshot), "VALID_FOUR_SLOT_FIXTURE_REJECTED")
        cases.append({"id": "FS01-strict-four-slot-observation", "passed": True})
        for key, value in (("observation_complete", 0), ("inflight_count", 3), ("active_senders", 3),
                           ("queued_count", 1), ("events_delivered", 31), ("events_failed", 1)):
            candidate = {**snapshot, "values": dict(snapshot["values"])}
            candidate["values"][key] = value
            base.require(not four_blocked(candidate), "UNPROVEN_FOUR_SLOT_SNAPSHOT_ACCEPTED")
        cases.append({"id": "FS02-six-invalid-observations-rejected", "passed": True, "variants": 6})
        config = base.manifest("explicit4", queue_count=64, explicit_concurrency=4)
        base.require(config["routes"][1]["plugins"]["shortlink-request-logger"]["send_concurrency"] == 4,
                     "EXPLICIT_FOUR_NOT_IN_REAL_MANIFEST")
        base.require("send_concurrency" not in base.logger_configuration(), "DEFAULT_TWO_NOT_EXERCISED")
        cases.append({"id": "FS03-default-and-explicit-profile-wiring", "passed": True})
    return {"passed": True, "cases": cases, "sourceSha256": base.digest(Path(__file__).read_bytes()),
            "realDockerCommands": 0, "realHttpRequests": 0, "outputFilesWritten": 0,
            "scope": "Offline harness validation only; real four-slot proof requires --run-component"}


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
        component = FourSlotComponent()
        result = component.run()
        print(json.dumps({"passed": result["passed"], "cases": len(result["cases"]),
                          "report": str(component.folder / "result-four.json")}, ensure_ascii=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
