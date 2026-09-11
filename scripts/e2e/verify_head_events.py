"""Read-only producer acceptance for the isolated public HTTP HEAD-only case.

Run inside the existing shortlink-refactor-it WSL distribution. The script only
queries Redirect's event-quality endpoint and Kafka offsets/records. It does not
start services, send business requests, commit consumer offsets, or claim that
statistics have been aggregated or stored. The observer credential stays in
memory and is never passed to a subprocess or written to evidence.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import time
from typing import Any


_TOPICS = ("shortlink.click.raw.v1", "shortlink.gateway.request.v1")
_KAFKA_BIN = "/opt/kafka/bin/"
_MAX_RECORDS = 20_000


def verify(run_dir: Path, kafka_container: str | None = None, bootstrap: str | None = None,
           budget_seconds: float = 30) -> dict:
    """Verify fixed Kafka offset ranges and return/write credential-free evidence."""
    if os.environ.get("WSL_DISTRO_NAME") != "shortlink-refactor-it":
        raise AssertionError("head-events: Run inside the existing shortlink-refactor-it WSL distribution")
    if not 0 < budget_seconds <= 30:
        raise AssertionError("head-events: The complete observation budget must be at most 30 seconds")
    run_dir = run_dir.resolve(strict=True)
    state = json.loads((run_dir / "state.json").read_text(encoding="utf-8-sig"))
    kafka_container = kafka_container or state.get("kafkaContainer")
    bootstrap = bootstrap or state.get("kafkaContainerBootstrap")
    if not isinstance(kafka_container, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", kafka_container):
        raise AssertionError("head-events: State or explicit arguments must name the actual Kafka container")
    if not isinstance(bootstrap, str) or not bootstrap:
        raise AssertionError("head-events: State or explicit arguments must specify the in-container bootstrap")
    business = json.loads((run_dir / "business.json").read_text(encoding="utf-8-sig"))
    observer = json.loads((run_dir / "observer-secret.json").read_text(encoding="utf-8-sig"))
    secret = observer.get("internalToken")
    if not isinstance(secret, str) or len(secret) < 32:
        raise AssertionError("head-events: Missing observer credential")
    producer = state.get("producerInstanceId")
    observations = business.get("observations", {})
    link_id = observations.get("headOnlyLinkId")
    short_uri = observations.get("headOnlyShortUri")
    if (
        business.get("status") != "PASSED"
        or not isinstance(producer, str)
        or not producer.startswith("e2e-")
        or type(link_id) is not int
        or link_id <= 0
        or not isinstance(short_uri, str)
        or not re.fullmatch(r"[A-Za-z0-9]{9}", short_uri)
        or observations.get("headOnlyHeadRequests") != 1
        or observations.get("headOnlyGetRequests") != 0
    ):
        raise AssertionError("head-events: Missing successful, independent HEAD-only business evidence")
    if Path(state.get("folder", "")).resolve() != run_dir:
        raise AssertionError("head-events: Run directory does not match the deployment state")

    started = time.monotonic()
    deadline = started + budget_seconds
    log_lines: list[str] = []

    def log(message: str) -> None:
        log_lines.append(datetime.now(timezone.utc).isoformat() + " " + message)

    def require(condition: bool, message: str) -> None:
        if not condition:
            raise AssertionError("head-events: " + message)

    def remaining() -> float:
        value = deadline - time.monotonic()
        require(value > 0, "The 30-second observation budget expired")
        return value

    def command(tool: str, args: list[str]) -> str:
        require(tool in ("kafka-get-offsets.sh", "kafka-console-consumer.sh"), "Non-read-only Kafka tool rejected")
        try:
            result = subprocess.run(
                ["docker", "exec", kafka_container, _KAFKA_BIN + tool, "--bootstrap-server", bootstrap] + args,
                capture_output=True, timeout=remaining(), check=False,
            )
        except subprocess.TimeoutExpired:
            raise AssertionError("head-events: Kafka observation exceeded the 30-second budget") from None
        except OSError:
            raise AssertionError("head-events: Kafka observer could not execute") from None
        # Never print captured topic values: raw click records contain visitor data.
        require(result.returncode == 0, "Read-only Kafka command failed (" + tool + ")")
        require(len(result.stdout) <= 16 * 1024 * 1024, "Kafka observer output exceeded its bounded budget")
        try:
            return result.stdout.decode("utf-8")
        except UnicodeError:
            raise AssertionError("head-events: Kafka observer output was not UTF-8") from None

    def quality() -> dict:
        connection = http.client.HTTPConnection("127.0.0.1", 8003, timeout=min(3, remaining()))
        try:
            connection.request("GET", "/internal/v1/events/quality", headers={"X-Internal-Token": secret})
            response = connection.getresponse()
            content = response.read(65_537)
            require(response.status == 200 and len(content) <= 65_536, "Redirect quality endpoint unavailable")
            data = json.loads(content)
        except (OSError, http.client.HTTPException, ValueError):
            raise AssertionError("head-events: Redirect quality observation failed") from None
        finally:
            connection.close()
        require(isinstance(data, dict) and data.get("producerInstanceId") == producer,
                "Quality response does not belong to this producer instance")
        sanitized: dict[str, Any] = {"producerInstanceId": producer, "lanes": {}}
        for timestamp in ("startedAt", "observedAt"):
            require(type(data.get(timestamp)) is int, "Quality timestamp missing")
            sanitized[timestamp] = data[timestamp]
        for lane in ("click", "result"):
            counters = data.get("lanes", {}).get(lane, {})
            sanitized["lanes"][lane] = {}
            for key in ("attempted", "delivered", "failed", "rejected", "pending"):
                value = counters.get(key)
                require(type(value) is int and value >= 0, "Quality lane counter missing or invalid")
                sanitized["lanes"][lane][key] = value
            require(counters["failed"] == 0 and counters["rejected"] == 0,
                    "Producer loss prevents asserting HEAD-only event absence")
        return sanitized

    def wait_drained() -> dict:
        while True:
            snapshot = quality()
            if all(lane["pending"] == 0 and lane["attempted"] == lane["delivered"]
                   for lane in snapshot["lanes"].values()):
                return snapshot
            time.sleep(min(0.2, remaining()))

    def offsets(topic: str, boundary: str) -> dict[int, int]:
        output = command("kafka-get-offsets.sh", ["--topic", topic, "--time", boundary])
        result = {}
        for line in output.splitlines():
            match = re.fullmatch(re.escape(topic) + r":(\d+):(\d+)", line)
            require(match is not None, "Unexpected Kafka offset output")
            partition, offset = map(int, match.groups())
            require(partition not in result, "Kafka offset listing duplicated a partition")
            result[partition] = offset
        require(bool(result), "Kafka topic has no observable partitions")
        return result

    def scan(topic: str, partition: int, start: int, end: int) -> dict:
        span = end - start
        require(0 <= span <= _MAX_RECORDS, "Kafka partition exceeded the finite observation budget")
        evidence = {"topic": topic, "partition": partition, "startOffset": start,
                    "endOffsetExclusive": end, "recordsScanned": 0, "sameLinkOtherProducer": 0,
                    "matchingEvents": [], "unrelatedMalformedRecords": 0}
        if span == 0:
            return evidence
        output = command("kafka-console-consumer.sh", [
            "--topic", topic, "--partition", str(partition), "--offset", str(start),
            "--max-messages", str(span), "--timeout-ms", str(max(100, int(remaining() * 1000))),
            "--consumer-property", "enable.auto.commit=false",
            "--consumer-property", "isolation.level=read_committed",
            "--property", "print.partition=true", "--property", "print.offset=true",
            "--property", "print.value=true",
        ])
        expected_offset = start
        for line in output.splitlines():
            match = re.fullmatch(r"Partition:(\d+)\tOffset:(\d+)\t(.*)", line)
            require(match is not None, "Kafka record formatter did not include partition and offset")
            actual_partition, actual_offset, raw = match.groups()
            require(int(actual_partition) == partition and int(actual_offset) == expected_offset,
                    "Kafka scan has missing/duplicated offsets; cannot prove an event is absent")
            expected_offset += 1
            evidence["recordsScanned"] += 1
            try:
                event = json.loads(raw)
            except ValueError:
                require(producer not in raw, "This producer has an unreadable raw record")
                evidence["unrelatedMalformedRecords"] += 1
                continue
            if not isinstance(event, dict):
                continue
            if event.get("linkId") != link_id:
                continue
            if event.get("producerInstanceId") != producer:
                evidence["sameLinkOtherProducer"] += 1
                continue
            # Both identity predicates are mandatory before any matching count.
            selected = {key: event.get(key) for key in (
                "producerInstanceId", "linkId", "schemaVersion", "occurredAt", "source",
                "stage", "method", "status", "domainNorm", "shortUri", "decisionId",
            ) if key in event}
            selected["partition"] = partition
            selected["offset"] = int(actual_offset)
            evidence["matchingEvents"].append(selected)
        require(expected_offset == end, "Kafka scan did not reach the captured end offset")
        return evidence

    output_path = run_dir / "head-events.json"
    log_path = run_dir / "head-events.log"
    try:
        log("Started bounded, read-only HEAD producer verification")
        drained_before = wait_drained()
        log("Both event producer lanes drained without producer loss")
        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = {(topic, boundary): executor.submit(offsets, topic, boundary)
                       for topic in _TOPICS for boundary in ("earliest", "latest")}
            boundaries = {key: future.result() for key, future in futures.items()}
            ranges = []
            for topic in _TOPICS:
                first, last = boundaries[(topic, "earliest")], boundaries[(topic, "latest")]
                require(first.keys() == last.keys(), "Topic partition set changed during observation")
                ranges.extend((topic, partition, first[partition], last[partition]) for partition in first)
            require(sum(end - start for _, _, start, end in ranges) <= _MAX_RECORDS,
                    "Topics exceed the finite read-only observation budget")
            scanned = list(executor.map(lambda item: scan(*item), ranges))
        drained_after = wait_drained()
        remaining()
        clicks = [event for item in scanned if item["topic"] == _TOPICS[0] for event in item["matchingEvents"]]
        results = [event for item in scanned if item["topic"] == _TOPICS[1] for event in item["matchingEvents"]]
        heads = [event for event in results if event.get("source") == "REDIRECT"
                 and event.get("method") == "HEAD" and event.get("status") == 302]
        require(len(clicks) == 0, "HEAD-only link emitted a click event")
        require(len(heads) == 1, "Expected exactly one REDIRECT HEAD 302 request result")
        require(len(results) == 1, "Unexpected additional request results for the HEAD-only link")
        require(heads[0].get("shortUri") == short_uri
                and heads[0].get("domainNorm") == observations.get("headOnlyHost")
                and heads[0].get("stage") == "BUSINESS", "HEAD result identity/stage mismatch")
        evidence = {"status": "PASSED", "scope": "producer-to-Kafka raw events only",
                    "doesNotProve": "Analytics aggregation, statistics storage, or delivery outside captured offsets",
                    "runId": state["runId"], "producerInstanceId": producer,
                    "headOnlyLinkId": link_id, "headOnlyShortUri": short_uri,
                    "matchingClickEvents": len(clicks), "matchingRequestEvents": len(results),
                    "matchingRedirectHead302Events": len(heads),
                    "elapsedSeconds": round(time.monotonic() - started, 3),
                    "qualityBefore": drained_before, "qualityAfter": drained_after,
                    "partitions": scanned, "consumerOffsetsCommitted": False}
        output_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        log("PASS: 0 matching click events; 1 matching REDIRECT HEAD 302 result; complete captured offset coverage")
        return evidence
    except (AssertionError, ValueError, KeyError) as error:
        # Only our assertions have contextual text; parser messages might contain source data.
        message = str(error) if isinstance(error, AssertionError) else "head-events: Invalid observation input"
        output_path.write_text(json.dumps({"status": "FAILED", "reason": message}, indent=2) + "\n", encoding="utf-8")
        log(message)
        raise AssertionError(message) from None
    finally:
        log_path.write_text("\n".join(log_lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--kafka-container", help="Override the actual container recorded in state.json")
    parser.add_argument("--bootstrap", help="Override state.json's in-container bootstrap address")
    arguments = parser.parse_args()
    result = verify(arguments.run_dir, arguments.kafka_container, arguments.bootstrap)
    print(json.dumps({key: result[key] for key in (
        "status", "matchingClickEvents", "matchingRequestEvents", "matchingRedirectHead302Events", "elapsedSeconds"
    )}))
