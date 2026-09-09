"""Run a finite, reviewable series of performance stages; stop each ladder at failure.

The wave JSON is evidence, not an external command list. run_stage validates every
endpoint/resource budget. No concurrent loads; business state is never reset.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import sys

import evidence
import observe
import run_stage


def run(state_path, spec_path):
    state, folder = observe._load(state_path)
    spec = json.loads(Path(spec_path).read_text(encoding="utf-8-sig"))
    stop_on_stage_failure = spec.get("stopOnStageFailure", False)
    if type(stop_on_stage_failure) is not bool:
        raise ValueError("INVALID_STOP_ON_STAGE_FAILURE")
    name = spec["name"]
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", name) or len(spec["series"]) > 40:
        raise ValueError("INVALID_WAVE")
    destination = folder / (name + "-wave.json")
    if destination.exists():
        raise ValueError("PRESERVE_EXISTING_WAVE")
    result = {"name": name, "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "state": "RUNNING", "series": [], "spec": spec}
    evidence.write(destination, result)
    interrupted = False
    try:
        for series in spec["series"]:
            lane = {"name": series["name"], "stages": [], "lastPassed": None, "firstFailed": None}
            result["series"].append(lane)
            if len(series["steps"]) > 20:
                raise ValueError("TOO_MANY_STEPS")
            for step in series["steps"]:
                if (folder / "STOP_CAMPAIGN").exists():
                    result.update(state="STOPPED", stopReason="STOP_CAMPAIGN")
                    return result
                params = dict(series.get("defaults", {}), **step)
                if params["mode"] in ("update", "recycle", "restore"):
                    evidence.refresh_mutations(state_path)
                argv = [str(state_path)]
                for key, value in params.items():
                    option = "--" + key.replace("_", "-")
                    if isinstance(value, bool):
                        if value:
                            argv.append(option)
                    else:
                        argv.extend([option, str(value)])
                args = run_stage.parser().parse_args(argv)
                print(json.dumps({"event": "STAGE_START", "label": args.label, "mode": args.mode,
                    "path": args.path, "rate": args.rate, "unit": args.time_unit}), flush=True)
                outcome = run_stage.run(args)
                checks = {}
                directory = Path(outcome["stageDirectory"])
                summary_file = directory / "k6-summary.json"
                if summary_file.exists() and outcome.get("drained"):
                    summary = json.loads(summary_file.read_text())
                    before = json.loads((directory / "before.json").read_text())
                    after = json.loads((directory / "after.json").read_text())
                    all_phase = summary["all"]
                    created = after["mysql"]["data"]["routeCount"] - before["mysql"]["data"]["routeCount"]
                    expected_created = all_phase["correct_rows"] if args.mode in ("create", "batch") else 0
                    checks["createdRowsMatchConfirmedResponses"] = created == expected_created
                    quality_before = before["redirectQuality"]["data"]["lanes"]
                    quality_after = after["redirectQuality"]["data"]["lanes"]
                    click_delta = quality_after["click"]["attempted"] - quality_before["click"]["attempted"]
                    expected_clicks = all_phase["correct"] if args.mode == "redirect" and args.expected_status in (None, 302) else 0
                    checks["clickAttemptsMatchSuccessfulGET"] = click_delta == expected_clicks
                    if args.path in ("direct", "edge"):
                        evidence_source = (quality_after["result"]["attempted"] - quality_before["result"]["attempted"]
                            if args.path == "direct" else
                            run_stage.scalar(after, "shortlink_edge_events_attempted") - run_stage.scalar(before, "shortlink_edge_events_attempted"))
                        checks["requestEventsMatchReceivedHTTP"] = evidence_source == all_phase["received_http"]
                    if args.mode == "idempotent":
                        def issued(snapshot):
                            return next(m["value"] for m in snapshot["services"]["shortlink-command"]["metrics"]
                                if m["name"] == "shortlink_id_issued")
                        try:
                            checks["idempotencyDidNotIssueNewIds"] = issued(before) == issued(after)
                        except StopIteration:
                            checks["idempotencyIssuedMetricUnavailable"] = False
                    if args.mode in ("create", "batch", "idempotent", "update", "recycle", "restore"):
                        audit = evidence.integrity(state)
                        evidence.write(directory / "sql-integrity.json", audit)
                        checks["sqlIntegrity"] = audit["passed"]
                else:
                    checks["stageEvidenceAvailableAndDrained"] = False
                outcome["postChecks"] = checks
                outcome["wavePassed"] = outcome.get("capacityPassed") is True and all(checks.values())
                evidence.write(directory / "wave-verdict.json", outcome)
                compact = {key: outcome.get(key) for key in ("label", "status", "capacityPassed", "wavePassed",
                    "stopReasons", "drained", "measure", "postChecks", "roundtripP99Ms", "k6Process")}
                lane["stages"].append(compact)
                if outcome["wavePassed"]:
                    lane["lastPassed"] = params
                else:
                    lane["firstFailed"] = params
                print(json.dumps({"event": "STAGE_DONE", "label": args.label,
                    "passed": outcome["wavePassed"], "correctRate": outcome.get("measure", {}).get("correct_rate"),
                    "p99Ms": outcome.get("roundtripP99Ms"), "stopReasons": outcome["stopReasons"],
                    "postChecks": checks}, ensure_ascii=False), flush=True)
                evidence.write(destination, result)
                if not outcome.get("drained") or not all(checks.values()) or \
                        outcome.get("checks", {}).get("eventFailuresUnchanged") is not True or \
                        outcome.get("status") in ("STAGE_EXECUTION_ERROR", "BLOCKED_BEFORE_LOAD"):
                    result.update(state="STOPPED", stopReason="STAGE_REQUIRES_REVIEW", stopLabel=args.label)
                    return result
                if stop_on_stage_failure and not outcome["wavePassed"]:
                    result.update(state="STOPPED", stopReason="STAGE_FAILED", stopLabel=args.label)
                    return result
                if not outcome["wavePassed"] and not series.get("continueAfterCapacityFailure", False):
                    break
        result["state"] = "COMPLETED"
        return result
    except KeyboardInterrupt:
        interrupted = True
        result.update(state="STOPPED", stopReason="INTERRUPTED")
        return result
    except Exception as error:
        result.update(state="STOPPED", stopReason="ORCHESTRATION_ERROR", errorType=type(error).__name__)
        raise
    finally:
        result["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        evidence.write(destination, result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state")
    parser.add_argument("spec")
    arguments = parser.parse_args()
    final = run(arguments.state, arguments.spec)
    print(json.dumps({"event": "WAVE_FINISHED", "state": final["state"], "reason": final.get("stopReason")}), flush=True)
    sys.exit(0 if final["state"] == "COMPLETED" else 1)
