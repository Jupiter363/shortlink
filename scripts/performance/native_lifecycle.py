"""Bound request activity separately from export; never decides capacity PASS.

The wrapper captures spawned_monotonic_ns BEFORE Popen and /proc PID startTicks
immediately afterwards. All times use Linux CLOCK_MONOTONIC, including Go's
receipt. observe() never starts/stops a process or parses a large summary.
Only finalize(), after process exit and complete JSON parsing, can say COMPLETE.
"""
from dataclasses import dataclass
import json
import math
import os
from pathlib import Path
import re
import stat
import time

VERSION = "1.4.1-drained-receipt"
RECEIPT_KIND = "NATIVE_GO_REQUESTS_DRAINED"
MAX_RECEIPT_BYTES = 4096
_COUNTS = ("workersFinished", "activeRequests", "planned", "scheduled", "sent", "completed",
           "correct", "errors", "dropped", "cancelled", "requestElapsedNanos")
_KEYS = set(_COUNTS) | {"schemaVersion", "kind", "version", "runId", "label", "pid", "startTicks",
                       "drainedMonotonicNanos", "requestsDrained", "measurementComplete", "stopReason"}


class LifecycleError(ValueError):
    pass


def _require(condition, code):
    if not condition:
        raise LifecycleError(code)


def read_start_ticks(pid):
    _require(type(pid) is int and pid > 1, "INVALID_PID")
    raw = Path(f"/proc/{pid}/stat").read_text(encoding="ascii")
    fields = raw[raw.rfind(")") + 1:].split()
    _require(")" in raw and len(fields) >= 20 and fields[19].isdigit(), "INVALID_PROC_STAT")
    value = int(fields[19])
    _require(value > 0, "INVALID_START_TICKS")
    return value


def _unique_pairs(pairs):
    value = {}
    for key, item in pairs:
        _require(key not in value, "DUPLICATE_RECEIPT_KEY")
        value[key] = item
    return value


def read_receipt(path):
    """Bounded regular-file read; an absent path means not yet drained."""
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    except FileNotFoundError:
        return None
    try:
        info = os.fstat(fd)
        _require(stat.S_ISREG(info.st_mode) and 0 < info.st_size <= MAX_RECEIPT_BYTES, "INVALID_RECEIPT_FILE")
        raw = os.read(fd, MAX_RECEIPT_BYTES + 1)
        _require(len(raw) == info.st_size and len(raw) <= MAX_RECEIPT_BYTES, "INCOMPLETE_RECEIPT")
        if hasattr(os, "getuid"):
            _require(info.st_uid == os.getuid(), "RECEIPT_OWNER_MISMATCH")
    finally:
        os.close(fd)
    try:
        value = json.loads(raw, object_pairs_hook=_unique_pairs)
    except (ValueError, UnicodeError) as error:
        if isinstance(error, LifecycleError):
            raise
        raise LifecycleError("INVALID_RECEIPT_JSON") from None
    return value, raw


@dataclass(frozen=True)
class Decision:
    state: str
    reason: str
    # Even COMPLETE is lifecycle/HTTP integrity only; caller retains capacity gates.
    capacity_pass: bool = False


class NativeLifecycle:
    def __init__(self, *, run_id, label, pid, start_ticks, spawned_monotonic_ns,
                 active_budget_seconds, export_budget_seconds=60, workers=2048):
        _require(all(isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", v)
                     for v in (run_id, label)), "INVALID_RUN_IDENTITY")
        _require(all(type(v) is int and v > 0 for v in (pid, start_ticks, spawned_monotonic_ns)), "INVALID_PROCESS_IDENTITY")
        _require(workers in (512, 1024, 2048, 4096), "INVALID_OWNER_COUNT")
        _require(all(type(v) in (int, float) and math.isfinite(v) and v > 0
                     for v in (active_budget_seconds, export_budget_seconds))
                 and export_budget_seconds <= 120, "INVALID_LIFECYCLE_BUDGET")
        self.run_id, self.label, self.pid, self.start_ticks = run_id, label, pid, start_ticks
        self.spawned_ns, self.workers = spawned_monotonic_ns, workers
        self.active_deadline_ns = spawned_monotonic_ns + int(active_budget_seconds * 1_000_000_000)
        self.export_budget_ns = int(export_budget_seconds * 1_000_000_000)
        self.receipt, self._raw, self.failure = None, None, None
        self._last_now = spawned_monotonic_ns
        self.stop_requested_ns, self.stop_deadline_ns = None, None

    @property
    def request_deadline_ns(self):
        return (self.active_deadline_ns if self.stop_deadline_ns is None
                else min(self.active_deadline_ns, self.stop_deadline_ns))

    def request_stop(self, now_ns, grace_seconds=12):
        """Latch an external stop without killing the process or ending export.

        The caller sends its stop signal once and continues observe(). A valid
        receipt may establish timely drain even if a later poll first sees it.
        Repeated requests never move the first stop deadline or export origin.
        """
        if self.failure:
            return self.failure
        try:
            _require(type(now_ns) is int and now_ns >= self._last_now, "OBSERVER_CLOCK_REVERSED")
            _require(type(grace_seconds) in (int, float) and math.isfinite(grace_seconds)
                     and 0 < grace_seconds <= 12 and int(grace_seconds * 1_000_000_000) > 0,
                     "INVALID_STOP_GRACE")
        except LifecycleError as error:
            return self._fail(str(error))
        self._last_now = now_ns
        if self.stop_requested_ns is None:
            self.stop_requested_ns = now_ns
            self.stop_deadline_ns = min(self.active_deadline_ns,
                                        now_ns + int(grace_seconds * 1_000_000_000))
            self.export_budget_ns = min(self.export_budget_ns, 60 * 1_000_000_000)
        return (Decision("EXPORTING", "STOP_REQUESTED_AFTER_DRAIN") if self.receipt is not None
                else Decision("STOPPING", "STOP_REQUESTED_AWAITING_DRAIN"))

    def _fail(self, reason):
        self.failure = Decision("FAILED", reason)
        return self.failure

    def _validate(self, r, now):
        _require(isinstance(r, dict) and set(r) == _KEYS, "RECEIPT_SCHEMA_MISMATCH")
        _require(type(r["schemaVersion"]) is int and r["schemaVersion"] == 1
                 and r["kind"] == RECEIPT_KIND and r["version"] == VERSION, "RECEIPT_VERSION_MISMATCH")
        for key, expected in (("runId", self.run_id), ("label", self.label), ("pid", self.pid), ("startTicks", self.start_ticks)):
            _require(type(r[key]) is type(expected) and r[key] == expected, "RECEIPT_IDENTITY_MISMATCH")
        _require(all(type(r[k]) is int and r[k] >= 0 for k in _COUNTS), "INVALID_RECEIPT_COUNTS")
        drained = r["drainedMonotonicNanos"]
        _require(type(drained) is int and self.spawned_ns <= drained <= now, "INVALID_DRAIN_MONOTONIC_TIME")
        _require(drained <= self.active_deadline_ns, "ACTIVITY_DEADLINE_EXCEEDED")
        _require(self.stop_deadline_ns is None or drained <= self.stop_deadline_ns,
                 "STOP_DEADLINE_EXCEEDED")
        _require(r["requestElapsedNanos"] <= drained-self.spawned_ns, "INVALID_DRAIN_ELAPSED_TIME")
        _require(r["requestsDrained"] is True and r["workersFinished"] == self.workers
                 and r["activeRequests"] == 0 and r["sent"] == r["completed"], "REQUESTS_NOT_DRAINED")
        _require(type(r["measurementComplete"]) is bool and isinstance(r["stopReason"], str)
                 and re.fullmatch(r"[A-Z0-9_]{1,96}", r["stopReason"]), "INVALID_DRAIN_STATE")
        _require(r["correct"]+r["errors"] == r["completed"]
                 and r["sent"]+r["dropped"] == r["scheduled"]
                 and r["scheduled"]+r["cancelled"] == r["planned"], "RECEIPT_CONSERVATION_FAILED")

    def observe(self, receipt_path, *, now_ns=None, returncode=None):
        if self.failure:
            return self.failure
        now = time.monotonic_ns() if now_ns is None else now_ns
        try:
            _require(type(now) is int and now >= self._last_now, "OBSERVER_CLOCK_REVERSED")
            self._last_now = now
            entry = read_receipt(receipt_path)
            if entry is not None:
                value, raw = entry
                self._validate(value, now)
                _require(self._raw is None or self._raw == raw, "RECEIPT_CHANGED_AFTER_ACCEPTANCE")
                self.receipt, self._raw = value, raw
            elif self.receipt is not None:
                return self._fail("RECEIPT_REMOVED_AFTER_ACCEPTANCE")
        except LifecycleError as error:
            return self._fail(str(error))  # Only fixed codes from this module.
        except OSError:
            # OS exception strings can contain private paths; report fixed codes.
            return self._fail("INVALID_DRAIN_RECEIPT")
        if self.receipt is None:
            if returncode is not None:
                return self._fail("PROCESS_EXITED_WITHOUT_DRAIN_RECEIPT")
            if now > self.request_deadline_ns:
                return self._fail("STOP_TIMEOUT_REQUESTS_NOT_DRAINED"
                                  if self.stop_deadline_ns is not None
                                  and self.stop_deadline_ns < self.active_deadline_ns
                                  else "ACTIVITY_TIMEOUT_REQUESTS_NOT_DRAINED")
            if self.stop_requested_ns is not None:
                return Decision("STOPPING", "STOP_REQUESTED_AWAITING_DRAIN")
            return Decision("ACTIVE", "REQUESTS_NOT_YET_DRAINED")
        if now > self.receipt["drainedMonotonicNanos"] + self.export_budget_ns:
            return self._fail("EXPORT_TIMEOUT")
        if returncode is not None:
            if returncode != 0:
                return self._fail("PROCESS_EXIT_NONZERO")
            return Decision("EXITED", "FULL_SUMMARY_VALIDATION_REQUIRED")
        return Decision("EXPORTING", "REQUESTS_DRAINED_SUMMARY_NOT_COMPLETE")

    def finalize(self, summary, returncode, *, now_ns=None):
        if self.failure:
            return self.failure
        now = time.monotonic_ns() if now_ns is None else now_ns
        if self.receipt is None or returncode != 0:
            return self._fail("FINALIZATION_REQUIRES_RECEIPT_AND_ZERO_EXIT")
        try:
            _require(type(now) is int and self._last_now <= now
                     <= self.receipt["drainedMonotonicNanos"]+self.export_budget_ns, "EXPORT_TIMEOUT")
            _require(isinstance(summary, dict) and summary.get("kind") == "NATIVE_GO_REDIRECT_GENERATOR"
                     and summary.get("version") == VERSION and summary.get("schemaVersion") == 1
                     and summary.get("runId") == self.run_id and summary.get("label") == self.label,
                     "SUMMARY_IDENTITY_MISMATCH")
            _require(summary.get("measurementComplete") is True and summary.get("conservationPassed") is True
                     and not summary.get("requestsDrainedReceiptError"), "SUMMARY_RUN_FAILED")
            _require(self.receipt["measurementComplete"] is True and self.receipt["stopReason"] == "COMPLETED",
                     "DRAIN_RECORDED_UNSUCCESSFUL_RUN")
            all_counts = summary.get("all", {})
            for receipt_key, summary_key in (("planned","planned_arrivals"),("scheduled","scheduled"),
                    ("sent","sent"),("completed","completed"),("correct","correct"),("errors","errors"),
                    ("dropped","dropped"),("cancelled","cancelled_arrivals")):
                _require(type(all_counts.get(summary_key)) is int
                         and all_counts[summary_key] == self.receipt[receipt_key], "SUMMARY_DRAIN_COUNTS_MISMATCH")
            _require(all_counts.get("errors") == 0 and type(all_counts.get("not_completed")) is int
                     and all_counts["not_completed"] == 0, "HTTP_FAILURE_RECORDED")
        except (LifecycleError, TypeError, AttributeError):
            return self._fail("FINAL_SUMMARY_FAILED")
        if self.stop_requested_ns is not None:
            return self._fail("STOP_REQUESTED_RUN_NOT_ACCEPTED")
        return Decision("COMPLETE", "CAPACITY_GATES_STILL_REQUIRED")
