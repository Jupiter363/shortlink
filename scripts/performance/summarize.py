"""Offline performance evidence summary. Never imports an observer or runs commands.

Only the six fixed stage evidence files and state.json are read. Results are
allowlisted aggregates, not copied response bodies, URLs, credentials or fixtures.
Stage checks passing is distinct from an independently confirmed capacity claim.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
from collections import Counter

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ("gateway", "shortlink-command", "admin", "shortlink-redirect")
SAFE = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")
MAX_JSON = 8 * 1024 * 1024
MAX_LINE = 2 * 1024 * 1024
MAX_METRICS = 512 * 1024 * 1024


def num(value):
    return value if type(value) in (int, float) and math.isfinite(value) else None


def at(data, *keys):
    for key in keys:
        if not isinstance(data, dict):
            return None
        data = data.get(key)
    return data


def seconds(value):
    if not isinstance(value, str):
        return None
    parts = re.findall(r"(\d+(?:\.\d+)?)(ms|s|m|h)", value)
    if not parts or "".join(n + u for n, u in parts) != value:
        return None
    return sum(float(n) * {"ms": .001, "s": 1, "m": 60, "h": 3600}[u] for n, u in parts)


def stamp(value):
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.timestamp() if parsed.tzinfo is not None else None
    except (ValueError, TypeError, AttributeError):
        return None


def delta(before, after):
    a, b = num(before), num(after)
    return b - a if a is not None and b is not None and b >= a else None


def ratio(numerator, denominator):
    n, d = num(numerator), num(denominator)
    return n / d if n is not None and d is not None and d > 0 else None


def peak(values):
    values = [num(v) for v in values if num(v) is not None]
    return max(values) if values else None


def mean(values):
    values = [num(v) for v in values if num(v) is not None]
    return sum(values) / len(values) if values else None


def safe_text(value, default=None):
    return value if isinstance(value, str) and SAFE.fullmatch(value) else default


def read_json(path):
    try:
        if path.is_symlink() or path.stat().st_size > MAX_JSON:
            return None, "UNSAFE_OR_OVERSIZED"
        value = json.loads(path.read_text(encoding="utf-8-sig"))
        return (value, "AVAILABLE") if isinstance(value, dict) else (None, "INVALID_OBJECT")
    except FileNotFoundError:
        return None, "MISSING"
    except (ValueError, UnicodeError, OSError):
        return None, "PARTIAL_OR_UNREADABLE"


def metric(snapshot, service, name, lane=None):
    component = snapshot.get("apisix", {}) if service == "edge" else at(snapshot, "services", service) or {}
    if component.get("status") != "AVAILABLE":
        return None
    values = [num(m.get("value")) for m in component.get("metrics", [])
              if m.get("name") == name and (lane is None or at(m, "labels", "lane") == lane)]
    return sum(values) if values and all(x is not None for x in values) else None


def compact(raw):
    """Retain only fields used by aggregation; never retain arbitrary metric labels."""
    item = {"time": stamp(raw.get("observedAt")), "phase": safe_text(raw.get("stagePhase")),
            "drained": at(raw, "drain", "drained"), "jvms": {}, "queues": {}, "events": {}}
    database = raw.get("mysql", {})
    data = database.get("data", {}) if database.get("status") == "AVAILABLE" else {}
    item["rows"] = num(data.get("routeCount"))
    item["highwater"] = num(at(data, "idAllocator", "endExclusive"))
    digest = data.get("idStatementDigest", {})
    item["idDbCalls"] = num(digest.get("countStar")) if digest.get("status") == "AVAILABLE" else None
    for queue in ("outbox", "metadata"):
        item["queues"][queue] = {key: num(at(data, queue, key)) for key in
            ("pending", "oldestPendingAgeMs", "terminalFailed", "terminalFailedFirstAttempt", "terminalFailedRetryExhausted")}
    for service in SERVICES:
        proc = at(raw, "processes", service) or {}
        p = proc.get("data", {}) if proc.get("status") == "AVAILABLE" else {}
        item["jvms"][service] = {key: num(p.get(key)) for key in
            ("pid", "startTicks", "userTicks", "systemTicks", "clockTicksPerSecond", "rssBytes")}
        item["jvms"][service]["gcCount"] = metric(raw, service, "jvm_gc_pause_seconds_count")
        item["jvms"][service]["gcSeconds"] = metric(raw, service, "jvm_gc_pause_seconds_sum")
    item["issued"] = metric(raw, "shortlink-command", "shortlink_id_issued")
    quality = raw.get("redirectQuality", {})
    q = quality.get("data", {}) if quality.get("status") == "AVAILABLE" else {}
    # IDs are used only for reset detection and never copied into results.
    item["redirectIdentity"] = (q.get("producerInstanceId"), q.get("startedAt"))
    for lane in ("click", "result"):
        item["events"][lane] = {key: num(at(q, "lanes", lane, key)) for key in
            ("attempted", "delivered", "failed", "rejected", "pending")}
        item["events"][lane]["pendingBytes"] = metric(raw, "shortlink-redirect", "shortlink_events_pending_bytes", lane)
    edge = raw.get("apisix", {})
    labels = [m.get("labels", {}) for m in edge.get("metrics", [])
              if m.get("name") == "shortlink_edge_instance_info"] if edge.get("status") == "AVAILABLE" else []
    item["edgeIdentity"] = (labels[0].get("instance"), labels[0].get("boot_id")) if len(labels) == 1 else (None, None)
    item["events"]["edge"] = {key: metric(raw, "edge", "shortlink_edge_events_" + key)
                                for key in ("attempted", "delivered", "failed", "rejected")}
    item["events"]["edge"].update(pending=metric(raw, "edge", "shortlink_edge_pending_count"),
                                  pendingBytes=metric(raw, "edge", "shortlink_edge_pending_bytes"))
    item["edgeComplete"] = metric(raw, "edge", "shortlink_edge_observation_complete")
    return item


def read_metrics(path):
    samples, errors, count = [], [], 0
    try:
        if path.is_symlink():
            return samples, {"status": "UNSAFE", "samples": 0}
        remaining = path.stat().st_size
        if remaining > MAX_METRICS:
            return samples, {"status": "BUDGET_EXCEEDED", "samples": 0}
        with path.open("rb") as source:
            while remaining > 0 and count < 10000:
                line = source.readline(min(MAX_LINE + 1, remaining))
                remaining -= len(line)
                count += 1
                if len(line) > MAX_LINE:
                    errors.append("LINE_BUDGET_EXCEEDED")
                    break
                if not line.endswith(b"\n"):
                    errors.append("INCOMPLETE_TAIL")
                    break
                try:
                    raw = json.loads(line)
                    if not isinstance(raw, dict):
                        raise ValueError()
                    samples.append(compact(raw))
                except (ValueError, TypeError, AttributeError):
                    errors.append("INVALID_SAMPLE")
            if remaining > 0:
                errors.append("SAMPLE_BUDGET_EXCEEDED")
    except FileNotFoundError:
        errors.append("MISSING")
    except OSError:
        errors.append("UNREADABLE")
    return samples, {"status": "AVAILABLE" if samples and not errors else "PARTIAL" if samples else "UNAVAILABLE",
                     "samples": len(samples), "issues": sorted(set(errors))}


def same_process(a, b):
    return all(a.get(k) is not None and a.get(k) == b.get(k) for k in ("pid", "startTicks", "clockTicksPerSecond"))


def timing_summary(result, summary, measure):
    config = summary.get("config", {})
    nominal, warm = num(config.get("measure_ms")), num(config.get("warmup_ms"))
    actual, total = num(measure.get("phase_duration_ms")), num(summary.get("test_run_duration_ms"))
    runner_check = at(result, "checks", "measurementComplete")
    finished = stamp(result.get("finishedAt")) is not None
    # A k6 correctness-threshold failure can return nonzero after the whole
    # configured duration. Exit status is not evidence of an early stop.
    duration_complete = total >= warm + nominal - 1000 if (
        finished and nominal is not None and nominal > 0 and warm is not None and total is not None) else None
    early = not duration_complete if duration_complete is not None else None
    denominator = nominal / 1000 if duration_complete is True else (
        actual / 1000 if early is True and actual is not None and actual > 0 else None)
    return {"nominalWarmupMs": warm, "nominalMeasureMs": nominal, "actualMeasureMs": actual,
            "testRunDurationMs": total, "measurementComplete": duration_complete,
            "runnerMeasurementCheck": runner_check if type(runner_check) is bool else None,
            "completionEvidence": "RECORDED_TEST_DURATION_WITH_1000MS_TOLERANCE",
            "early": early, "rateDenominatorSeconds": denominator,
            "rateDenominatorBasis": "NOMINAL_CONFIGURED_MEASURE_SECONDS" if duration_complete is True else
                "ACTUAL_EARLY_PHASE_SECONDS" if denominator is not None else "UNAVAILABLE"}


def jvm_summary(service, before, after, samples):
    a, b = before["jvms"][service], after["jvms"][service]
    matching = same_process(a, b)
    user = delta(a["userTicks"], b["userTicks"]) if matching else None
    system = delta(a["systemTicks"], b["systemTicks"]) if matching else None
    elapsed = delta(before["time"], after["time"])
    cpu_seconds = ratio(user + system, a["clockTicksPerSecond"]) if user is not None and system is not None else None
    interval_cpu = []
    for first, second in zip(samples, samples[1:]):
        x, y = first["jvms"][service], second["jvms"][service]
        if not same_process(x, y):
            continue
        u, s = delta(x["userTicks"], y["userTicks"]), delta(x["systemTicks"], y["systemTicks"])
        interval = delta(first["time"], second["time"])
        value = ratio(ratio(u + s, x["clockTicksPerSecond"]), interval) if u is not None and s is not None else None
        if value is not None:
            interval_cpu.append(value * 100)
    usage = ratio(cpu_seconds, elapsed)
    return {"status": "AVAILABLE" if matching and cpu_seconds is not None else "UNAVAILABLE_OR_PROCESS_CHANGED",
            "userTicksDelta": user, "systemTicksDelta": system, "cpuSeconds": cpu_seconds,
            "cpuPercentMean": usage * 100 if usage is not None else None, "cpuPercentSampledPeak": peak(interval_cpu),
            "rssBytesBefore": a["rssBytes"], "rssBytesAfter": b["rssBytes"],
            "rssBytesSampledPeak": peak(x["jvms"][service]["rssBytes"] for x in samples),
            "gcCountDelta": delta(a["gcCount"], b["gcCount"]) if matching else None,
            "gcPauseSecondsDelta": delta(a["gcSeconds"], b["gcSeconds"]) if matching else None,
            "scope": "BEFORE_TO_AFTER_INCLUDES_WARMUP_MEASURE_AND_DRAIN"}


def load_stage(directory, state):
    names = {"result": "result.json", "summary": "k6-summary.json", "before": "before.json",
             "after": "after.json", "wave": "wave-verdict.json"}
    files, availability = {}, {}
    for key, name in names.items():
        files[key], availability[key] = read_json(directory / name)
    result, summary, wave = (files[key] or {} for key in ("result", "summary", "wave"))
    before, after = compact(files["before"] or {}), compact(files["after"] or {})
    samples, metrics_availability = read_metrics(directory / "metrics.jsonl")
    # Include boundary values without double-counting repeated entries from metrics.jsonl.
    timeline = {x["time"]: x for x in [before, *samples, after] if x["time"] is not None}
    all_samples = [timeline[key] for key in sorted(timeline)]
    config = summary.get("config", {})
    measure = summary.get("measure") or result.get("measure") or {}
    nominal_ms = num(config.get("measure_ms"))
    warm_ms = num(config.get("warmup_ms"))
    unit = seconds(config.get("time_unit"))
    target_rate = ratio(config.get("rate"), unit)
    finished = stamp(result.get("finishedAt")) is not None
    timing = timing_summary(result, summary, measure)
    denominator = timing["rateDenominatorSeconds"]
    mode = safe_text(result.get("mode") or summary.get("mode"))
    all_phase = summary.get("all", {})
    warmup = summary.get("warmup", {})
    is_create = mode in ("create", "batch")
    is_mutation = mode in ("update", "recycle", "restore")
    correct_rows = num(all_phase.get("correct_rows"))
    created = correct_rows if is_create else 0 if mode is not None and correct_rows is not None else None
    mutated = correct_rows if is_mutation else 0 if mode is not None and correct_rows is not None else None
    postchecks = wave.get("postChecks", wave.get("postchecks"))
    postchecks_valid = isinstance(postchecks, dict) and bool(postchecks) and all(type(v) is bool for v in postchecks.values())
    checks = {safe_text(k): v for k, v in (postchecks or {}).items() if safe_text(k) and type(v) is bool} if isinstance(postchecks, dict) else {}
    evidence_complete = (all(availability[key] == "AVAILABLE" for key in names)
        and metrics_availability["status"] == "AVAILABLE" and timing["measurementComplete"] is True
        and all(num(measure.get(key)) is not None for key in ("actual_sent", "completed", "correct")))
    if not finished:
        status = "RUNNING" if state.get("phase") == "READY" and state.get("servicesStopped") is not True else "UNAVAILABLE"
    elif result.get("capacityPassed") is not True:
        status = "PROTECTION_DIAGNOSTIC" if result.get("allowRejections") is True else "FAILED_STAGE_CHECKS"
    elif not evidence_complete or not postchecks_valid:
        status = "UNAVAILABLE_POSTCHECKS_OR_EVIDENCE"
    elif wave.get("wavePassed") is not True or not all(checks.values()):
        status = "FAILED_POSTCHECKS"
    else:
        status = "PASSED_STAGE_CHECKS"
    if directory.name.startswith("smoke-"):
        level = "SMOKE"
    elif nominal_ms is None:
        level = "UNAVAILABLE"
    elif nominal_ms >= 1800000:
        level = "STEADY_OBSERVATION"
    elif nominal_ms >= 600000 and warm_ms is not None and warm_ms >= 120000:
        level = "SINGLE_CONFIRMATION_CANDIDATE"
    else:
        level = "SHORT_EXPLORATION"
    queues = {}
    for queue in ("outbox", "metadata"):
        qa, qb = before["queues"][queue], after["queues"][queue]
        queues[queue] = {"pendingBefore": qa["pending"], "pendingAfter": qb["pending"],
            "pendingSampledPeak": peak(x["queues"][queue]["pending"] for x in all_samples),
            "oldestPendingAgeMsSampledPeak": peak(x["queues"][queue]["oldestPendingAgeMs"] for x in all_samples),
            "terminalFailedDelta": delta(qa["terminalFailed"], qb["terminalFailed"])}
    events = {}
    for lane in ("click", "result", "edge"):
        identity = "edgeIdentity" if lane == "edge" else "redirectIdentity"
        same = None not in before[identity] and before[identity] == after[identity]
        ea, eb = before["events"][lane], after["events"][lane]
        events[lane] = {"identityContinuous": same,
            **{k + "Delta": delta(ea[k], eb[k]) if same else None for k in ("attempted", "delivered", "failed", "rejected")},
            "pendingBefore": ea["pending"], "pendingAfter": eb["pending"],
            "pendingCountSampledPeak": peak(x["events"][lane]["pending"] for x in all_samples),
            "pendingBytesSampledPeak": peak(x["events"][lane]["pendingBytes"] for x in all_samples)}
    after_samples = [x for x in samples if x["phase"] == "after" and x["time"] is not None]
    first_drained = next((x for x in after_samples if x["drained"] is True), None)
    drain_seconds = delta(after_samples[0]["time"], first_drained["time"]) if after_samples and first_drained else None
    k6 = result.get("k6Process", {})
    count_fields = ("actual_sent", "completed", "received_http", "correct", "status_429", "status_503", "client_errors")
    counts = {key: num(measure.get(key)) for key in count_fields}
    process_same = same_process(before["jvms"]["shortlink-command"], after["jvms"]["shortlink-command"])
    return {"label": safe_text(directory.name, "INVALID_STAGE_NAME"), "mode": mode,
        "path": safe_text(result.get("path")), "phase": "measure", "status": status, "evidenceLevel": level,
        "capacityConfirmed": False, "resultCapacityPassed": result.get("capacityPassed") if type(result.get("capacityPassed")) is bool else None,
        "postChecks": checks if postchecks_valid else None, "wavePassed": wave.get("wavePassed") if type(wave.get("wavePassed")) is bool else None,
        "startedAt": result.get("startedAt") if stamp(result.get("startedAt")) is not None else None,
        "finishedAt": result.get("finishedAt") if stamp(result.get("finishedAt")) is not None else None,
        "stopReasons": [x for x in result.get("stopReasons", []) if safe_text(x)],
        "configuration": {key: config.get(key) if num(config.get(key)) is not None else safe_text(config.get(key))
                          for key in ("accounts", "workset", "distribution", "batch_size", "expected_status", "preallocated_vus", "max_vus", "seed")},
        "timing": timing,
        "measure": {"targetRate": target_rate, "targetRequests": num(result.get("targetMeasureRequests")),
                    **counts, "sentRate": ratio(counts["actual_sent"], denominator),
                    "correctRate": ratio(counts["correct"], counts["actual_sent"]),
                    "correctRateDenominator": "ACTUAL_SENT",
                    "correctBusinessQps": ratio(counts["correct"], denominator),
                    "correctRowsPerSecond": ratio(measure.get("correct_rows"), denominator),
                    "roundtripMs": {key: num(at(measure, "roundtrip_ms", key))
                                    if counts["completed"] is not None and counts["completed"] > 0 else None
                                    for key in ("p50", "p95", "p99")},
                    "droppedIterations": num(summary.get("dropped_iterations")),
                    "droppedScope": "WHOLE_SCENARIO_PHASE_ATTRIBUTION_UNAVAILABLE"},
        "rows": {"createdWholeScenario": created,
                 "createdWarmup": num(warmup.get("correct_rows")) if is_create else 0 if created is not None else None,
                 "createdMeasure": num(measure.get("correct_rows")) if is_create else 0 if created is not None else None,
                 "mutatedWholeScenario": mutated, "dbBefore": before["rows"], "dbAfter": after["rows"],
                 "dbActualDelta": delta(before["rows"], after["rows"])},
        "id": {"issuedDelta": delta(before["issued"], after["issued"]) if process_same else None,
               "highwaterBefore": before["highwater"], "highwaterAfter": after["highwater"],
               "highwaterDelta": delta(before["highwater"], after["highwater"]),
               "dbAllocationStatementsDelta": delta(before["idDbCalls"], after["idDbCalls"]),
               "highwaterMeaning": "RESERVED_END_EXCLUSIVE_NOT_ISSUED_IDS"},
        "queues": queues, "events": events,
        "drain": {"passedByRunner": result.get("drained") if type(result.get("drained")) is bool else None,
                  "observedSeconds": drain_seconds, "timingMeaning": "FIRST_AFTER_SAMPLE_TO_FIRST_DRAINED_SAMPLE_5S_RESOLUTION_NOT_EXACT_EXIT_TO_DRAIN"},
        "k6Process": {**{key: num(k6.get(key)) for key in ("samples", "cpuPercentMean", "cpuPercentPeak", "rssBytesPeak", "rssBytesSampleMean")},
                      "scope": "PROCESS_LIFETIME_SAMPLED_BY_STAGE_RUNNER"},
        "jvms": {service: jvm_summary(service, before, after, all_samples) for service in SERVICES},
        "kafka": {"status": "UNAVAILABLE", "reason": "NO_STAGE_OFFSET_EVIDENCE_IN_THIS_SCHEMA"},
        "availability": {"files": availability, "metrics": metrics_availability}}


def display(value, digits=2):
    if value is None:
        return "N/A"
    if isinstance(value, str):
        return value
    return str(value) if type(value) is int else f"{value:.{digits}f}"


def markdown(report):
    lines = ["# Performance results", "", "Offline evidence summary; no traffic was generated.", "",
        "Stage checks passing does not establish confirmed capacity. Smoke and short exploration remain separately labelled; three runs are never automatically certified as independent repetitions.", "",
        "Stages whose recorded test duration reaches the configured duration (1 second tolerance) use configured measurement seconds for rates, independently of threshold exit status. Early stops use actual measured phase duration and are labelled EARLY. Missing evidence is N/A. Latencies are roundtrip milliseconds; dropped iterations cover warmup and measure together.", "",
        "| Stage | Mode / path | Level / status | Target/s | Sent/s | Correct/sent % | Correct QPS | p50 / p95 / p99 ms | 429 / 503 / client error / dropped | Created all / DB delta |", "| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- |"]
    for s in report["stages"]:
        m = s["measure"]
        label = s["label"] + (" EARLY" if s["timing"]["early"] is True else "")
        correctness = m["correctRate"] * 100 if m["correctRate"] is not None else None
        lines.append("| " + " | ".join([label, f"{display(s['mode'])} / {display(s['path'])}", f"{s['evidenceLevel']} / {s['status']}",
            display(m["targetRate"]), display(m["sentRate"]), display(correctness, 3), display(m["correctBusinessQps"]),
            " / ".join(display(m["roundtripMs"][k]) for k in ("p50", "p95", "p99")),
            " / ".join(display(m[k]) for k in ("status_429", "status_503", "client_errors", "droppedIterations")),
            display(s["rows"]["createdWholeScenario"]) + " / " + display(s["rows"]["dbActualDelta"])]) + " |")
    lines.extend(["", "CPU: 100% means one logical CPU. ActiveProcessorCount is a JVM ergonomics setting, not a CPU quota. JVM CPU/RSS/GC cover before-to-after, including warmup and drain. Queue/event maxima are sampled observations, not continuous peaks. Kafka stage offsets are unavailable.", "",
        "Metadata terminal FAILED counts remain visible. Recorded fixture mode: " + display(report.get("metadataMode")) + "; these counts do not establish successful page metadata extraction.", "",
        "| Stage | ID issued / highwater / DB calls delta | Outbox peak / oldest ms | Metadata peak / oldest ms / failed delta | Drain observed s | k6 mean / peak CPU % / RSS MiB |", "| --- | --- | --- | --- | ---: | --- |"])
    for s in report["stages"]:
        q, k = s["queues"], s["k6Process"]
        lines.append("| " + " | ".join([s["label"], " / ".join(display(s["id"][x]) for x in ("issuedDelta", "highwaterDelta", "dbAllocationStatementsDelta")),
            " / ".join(display(q["outbox"][x]) for x in ("pendingSampledPeak", "oldestPendingAgeMsSampledPeak")),
            " / ".join(display(q["metadata"][x]) for x in ("pendingSampledPeak", "oldestPendingAgeMsSampledPeak", "terminalFailedDelta")),
            display(s["drain"]["observedSeconds"]), " / ".join(display(x) for x in (k["cpuPercentMean"], k["cpuPercentPeak"], ratio(k["rssBytesPeak"], 1048576)))]) + " |")
    lines.extend(["", "Event deltas include warmup, measurement and drain. Counter resets make deltas unavailable. Edge pending is the aggregate, without adding worker series again.", "",
        "| Stage | Lane | Failed / rejected delta | Pending count / bytes sampled peak | Pending after |",
        "| --- | --- | --- | --- | ---: |"])
    for s in report["stages"]:
        for lane, event in s["events"].items():
            lines.append("| " + " | ".join([s["label"], lane,
                " / ".join(display(event[k]) for k in ("failedDelta", "rejectedDelta")),
                " / ".join(display(event[k]) for k in ("pendingCountSampledPeak", "pendingBytesSampledPeak")),
                display(event["pendingAfter"])]) + " |")
    lines.extend(["", "| Stage | JVM | User / system ticks delta | Mean / sampled peak CPU % | RSS sampled peak MiB | GC count / pause seconds delta |",
        "| --- | --- | --- | --- | ---: | --- |"])
    for s in report["stages"]:
        for service, process in s["jvms"].items():
            lines.append("| " + " | ".join([s["label"], service,
                " / ".join(display(process[k]) for k in ("userTicksDelta", "systemTicksDelta")),
                " / ".join(display(process[k]) for k in ("cpuPercentMean", "cpuPercentSampledPeak")),
                display(ratio(process["rssBytesSampledPeak"], 1048576)),
                " / ".join(display(process[k]) for k in ("gcCountDelta", "gcPauseSecondsDelta"))]) + " |")
    return "\n".join(lines) + "\n"


def summarize(path):
    source = Path(path).resolve()
    folder = source.parent if source.name == "state.json" else source
    folder.relative_to((ROOT / ".work/performance").resolve())
    state, status = read_json(folder / "state.json")
    if status != "AVAILABLE" or not safe_text(state.get("runId")) or folder.name != state["runId"]:
        raise ValueError("VALID_ISOLATED_RUN_REQUIRED")
    stages_path = folder / "stages"
    if stages_path.is_symlink():
        raise ValueError("UNSAFE_STAGES_DIRECTORY")
    directories = sorted(x for x in stages_path.iterdir() if x.is_dir() and not x.is_symlink() and safe_text(x.name)) if stages_path.exists() else []
    if len(directories) > 1000:
        raise ValueError("STAGE_COUNT_BUDGET")
    stages = [load_stage(directory, state) for directory in directories]
    report = {"schemaVersion": 1, "runId": state["runId"], "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
              "status": "RUNNING" if any(s["status"] == "RUNNING" for s in stages) else "SUMMARIZED",
              "metadataMode": safe_text(state.get("metadataMode")),
              "stageCount": len(stages), "statusCounts": dict(Counter(s["status"] for s in stages)),
              "confirmedCapacity": {"status": "NOT_ESTABLISHED_AUTOMATICALLY", "reason": "INDEPENDENT_THREE_REPEAT_ACCEPTANCE_REQUIRES_EXPLICIT_REVIEW"},
              "cpuPercentMeaning": "100_PERCENT_IS_ONE_LOGICAL_CORE_NOT_ACTIVE_PROCESSOR_COUNT_QUOTA",
              "stages": stages}
    for name, body in (("results-summary.json", json.dumps(report, ensure_ascii=False, allow_nan=False, indent=2) + "\n"),
                       ("results-table.md", markdown(report))):
        target = folder / name
        temporary = folder / ("." + name + ".tmp")
        temporary.write_text(body, encoding="utf-8")
        os.replace(temporary, target)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path, help="Isolated run directory or state.json")
    args = parser.parse_args()
    result = summarize(args.run)
    print(json.dumps({"runId": result["runId"], "stageCount": result["stageCount"],
                      "statusCounts": result["statusCounts"], "confirmedCapacity": result["confirmedCapacity"]}, ensure_ascii=False))
