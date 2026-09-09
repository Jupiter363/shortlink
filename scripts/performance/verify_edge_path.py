"""Verify a READY fixture with 12 cases and at most 15 sequential HTTP probes.

Only EP12 may reread its metrics endpoint (four reads total, 25ms apart,
one-second total deadline) while the four preceding edge log records publish.

Run explicitly inside the dedicated WSL host, outside the APISIX net namespace:
  python3 -B scripts/performance/verify_edge_path.py /path/to/state.json
An optional --fixture must name the supervisor's same private fixture path.
The script inspects the existing container and uses nsenter; it never starts,
reloads or modifies services/configuration. It does not follow redirects, load
test, consume Kafka, read observer secrets or print credentials/target URLs.
"""
import argparse
from contextlib import contextmanager
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
RECOVERY = "/api/short-link/v1/user/has-username?username=edgepathprobe"
MAX_BODY = 256 * 1024
AFTER_METRIC_READS = 4
AFTER_METRIC_INTERVAL = .025
AFTER_METRIC_BUDGET = 1.0
METRIC_NAMES = {
    "shortlink_edge_events_attempted", "shortlink_edge_events_delivered",
    "shortlink_edge_events_failed", "shortlink_edge_events_rejected",
    "shortlink_edge_pending_count", "shortlink_edge_pending_bytes",
    "shortlink_edge_started_at_seconds", "shortlink_edge_retained_worker_generations",
    "shortlink_edge_observation_faults", "shortlink_edge_observation_complete",
}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def _read_json(path, budget):
    require(path.stat().st_size <= budget, "Input file exceeds its read budget")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _load(state_path, fixture_override):
    require(sys.platform.startswith("linux"), "Use the dedicated Linux performance host")
    state_path = state_path.resolve()
    state_path.relative_to((ROOT / ".work/performance").resolve())
    state = _read_json(state_path, 256 * 1024)
    require(state.get("phase") == "READY", "Supervisor is not READY")
    require(Path(state["folder"]).resolve() == state_path.parent and state_path.name == "state.json",
            "State does not identify its own performance folder")
    require(re.fullmatch(r"shortlink-perf-apisix-[a-f0-9]{8}", state.get("apisix", "")),
            "Expected the isolated performance APISIX container")
    run_id = state.get("runId", "")
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,100}", run_id), "Invalid run identity")
    private = (Path("/var/lib/shortlink-perf") / run_id).resolve()
    expected_fixture = (private / "fixture.json").resolve()
    require(Path(state["fixturePath"]).resolve() == expected_fixture, "Unexpected private fixture location")
    fixture_path = (fixture_override or expected_fixture).resolve()
    require(fixture_path == expected_fixture, "Fixture override must identify this run's private fixture")
    require(stat.S_IMODE(fixture_path.stat().st_mode) == 0o600, "Fixture must retain mode 0600")
    fixture = _read_json(fixture_path, 64 * 1024 * 1024)
    require(fixture.get("fixtureStatus") == "READY", "Fixture is not READY")
    require(fixture.get("runId") == run_id and fixture.get("database") == state.get("database"),
            "Fixture identity differs from the running environment")
    require(fixture.get("normalizedClientIp") == "127.0.0.1", "A/B fixture client IP must be loopback")
    for field in ("managementHost", "redirectHost"):
        require(fixture.get(field) == state.get(field) and
                re.fullmatch(r"[A-Za-z0-9.-]{1,253}", state[field]), "Unexpected fixture Host")
    links = fixture.get("links")
    require(isinstance(links, list) and links, "Fixture has no stable primary short link")
    link = links[0]
    require(re.fullmatch(r"[A-Za-z0-9]{9}", link.get("shortUri", "")), "Invalid fixture short URI")
    require(isinstance(link.get("originUrl"), str) and 1 <= len(link["originUrl"]) <= 8192,
            "Invalid expected Location")
    # Only the small noncredential subset enters the network namespace child.
    context = {"runId": run_id, "apisix": state["apisix"], "apisixPid": int(state["apisixPid"]),
               "apisixIp": str(ipaddress.ip_address(state["apisixIp"])),
               "networkGateway": str(ipaddress.ip_address(state["networkGateway"])),
               "managementHost": state["managementHost"], "redirectHost": state["redirectHost"],
               "shortUri": link["shortUri"], "expectedLocation": link["originUrl"]}
    require(context["apisixPid"] > 1, "Invalid APISIX PID")
    result = subprocess.run(["docker", "inspect", "--format",
                             "{{json .State}}\n{{json .NetworkSettings.Networks}}", context["apisix"]],
                            capture_output=True, text=True, timeout=5, check=False)
    require(result.returncode == 0, "APISIX inspect failed")
    rows = result.stdout.splitlines()
    require(len(rows) == 2, "APISIX inspect returned an unexpected shape")
    process, networks = map(json.loads, rows)
    require(process.get("Running") is True and process.get("Pid") == context["apisixPid"],
            "APISIX process changed; refresh the supervisor identity")
    require(any(node.get("IPAddress") == context["apisixIp"] and
                node.get("Gateway") == context["networkGateway"] for node in networks.values()),
            "APISIX address changed; refresh the supervisor identity")
    require(os.stat("/proc/self/ns/net").st_ino !=
            os.stat(f"/proc/{context['apisixPid']}/ns/net").st_ino,
            "Launch the parent verifier outside the APISIX network namespace")
    return context


def _http(host, port, path, headers, method="GET", expected_source=None):
    connection = http.client.HTTPConnection(host, port, timeout=2)
    started = time.monotonic()
    try:
        connection.connect()
        source = connection.sock.getsockname()[0]
        if expected_source:
            require(source == expected_source, "TCP source differs from the frozen path")
        connection.request(method, path, headers={"Connection": "close", **headers})
        response = connection.getresponse()
        body = response.read(MAX_BODY + 1)
        require(len(body) <= MAX_BODY, "HTTP evidence exceeded its bounded body budget")
        return {"status": response.status, "headers": response.getheaders(), "body": body,
                "socketSource": source, "elapsedMs": round((time.monotonic() - started) * 1000, 2)}
    finally:
        connection.close()


def _headers(context, forwarded="127.0.0.1", scheme="http", forged=False, management=False):
    result = {"Host": context["managementHost" if management else "redirectHost"],
              "X-Forwarded-For": forwarded, "X-Forwarded-Proto": scheme}
    if forged:
        result.update({"X-Internal-Token": "forged-edge-path-probe",
                       "x-shortlink-tenant-id": "999999", "x-shortlink-username": "forged",
                       "x-shortlink-auth-version": "1", "X-Real-IP": "198.51.100.99",
                       "Forwarded": "for=198.51.100.99;proto=https",
                       "X-Forwarded-Host": "untrusted.invalid", "X-Request-ID": "caller-controlled-probe"})
    return result


def _header(response, name):
    return [value for key, value in response["headers"] if key.lower() == name.lower()]


def _status(response, expected):
    require(response["status"] == expected, f"Expected HTTP {expected}; received {response['status']}")
    require(not _header(response, "Location"), "Rejected/internal response unexpectedly has Location")
    return {key: response[key] for key in ("status", "socketSource", "elapsedMs")}


def _redirect(response, context, method, secure=None):
    require(response["status"] == 302, f"Expected HTTP 302; received {response['status']}")
    require(_header(response, "Location") == [context["expectedLocation"]], "Location differs from fixture")
    result = {key: response[key] for key in ("status", "socketSource", "elapsedMs")}
    result["locationMatches"] = True
    if method == "HEAD":
        require(response["body"] == b"", "HEAD returned a body")
        require(not _header(response, "Set-Cookie"), "HEAD created a visitor cookie")
        result.update(bodyBytes=0, createsCookie=False)
    else:
        cookies = _header(response, "Set-Cookie")
        require(len(cookies) == 1 and re.match(r"sl_uv=[a-f0-9]{32};", cookies[0]), "Missing visitor cookie")
        attributes = [piece.strip().lower() for piece in cookies[0].split(";")[1:]]
        require(("secure" in attributes) is secure, "Forwarded scheme did not produce the expected Secure flag")
        result.update(createsCookie=True, secureCookie=secure)
    return result


def _metrics(response):
    _status(response, 200)
    text = response["body"].decode("utf-8")
    values, workers = {}, {}
    for line in text.splitlines():
        worker = re.fullmatch(r'shortlink_edge_worker_pending_(count|bytes)\{worker_id="(\d+)",generation="([A-Za-z0-9:-]{1,100})"\} ([0-9.eE+-]+)', line)
        if worker:
            key = (worker[2], worker[3])
            record = workers.setdefault(key, {})
            require(worker[1] not in record, "Duplicate worker metric")
            number = float(worker[4])
            require(math.isfinite(number) and number >= 0, "Invalid worker pending metric")
            record[worker[1]] = number
        match = re.fullmatch(r"(shortlink_edge_[a-z_]+) ([0-9.eE+-]+)", line)
        if match and match[1] in METRIC_NAMES:
            require(match[1] not in values, "Duplicate aggregate metric")
            number = float(match[2])
            require(math.isfinite(number) and number >= 0, "Invalid aggregate metric")
            values[match[1]] = number
    require(values.keys() == METRIC_NAMES, "Required edge observation metric is missing")
    require(values["shortlink_edge_observation_faults"] == 0, "Edge observation has bookkeeping faults")
    require(values["shortlink_edge_observation_complete"] in (0, 1), "Invalid completeness flag")
    identity = re.findall(r'^shortlink_edge_instance_info\{instance="([A-Za-z0-9_.-]+)",boot_id="([A-Za-z0-9-]+)"\} 1$', text, re.M)
    require(len(identity) == 1, "Missing or ambiguous edge boot identity")
    require(values["shortlink_edge_started_at_seconds"] > 0, "Missing observation start time")
    require(len(workers) == values["shortlink_edge_retained_worker_generations"], "Worker generation series is missing")
    require(all(record.keys() == {"count", "bytes"} for record in workers.values()), "Incomplete per-worker pending pair")
    for kind in ("count", "bytes"):
        require(sum(record[kind] for record in workers.values()) == values["shortlink_edge_pending_" + kind],
                "Worker pending sum differs from aggregate")
    if values["shortlink_edge_observation_complete"] == 1:
        require(values["shortlink_edge_events_attempted"] == sum(values["shortlink_edge_events_" + name]
                for name in ("delivered", "failed", "rejected")) + values["shortlink_edge_pending_count"],
                "Complete observation violates event conservation")
    return {"status": 200, "instance": identity[0][0], "bootId": identity[0][1], "metrics": values,
            "workerGenerationsChecked": len(workers),
            "meaning": "Presence and conservation check; pending may still include Kafka sends"}


class _AfterObservationError(ValueError):
    def __init__(self, reason, evidence):
        super().__init__(reason)
        self.evidence = evidence


@contextmanager
def _observation_budget():
    # The verifier owns this single-threaded Linux netns child. A per-socket
    # timeout alone would restart on successive reads and not bound the whole
    # observation. Never replace another caller's active interval timer.
    require(hasattr(signal, "setitimer"), "EP12 deadline requires the Linux verifier")
    require(signal.getitimer(signal.ITIMER_REAL) == (0.0, 0.0), "EP12 found an active deadline timer")
    previous = signal.getsignal(signal.SIGALRM)
    def expired(signum, frame):
        raise TimeoutError("EP12 observation deadline exceeded")
    try:
        signal.signal(signal.SIGALRM, expired)
        signal.setitimer(signal.ITIMER_REAL, AFTER_METRIC_BUDGET)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def _observe_after(before, read, *, clock=None, sleep=None):
    """Retry only a valid, monotonic observation with fewer than four events."""
    clock = clock or time.monotonic
    sleep = sleep or time.sleep
    started = clock()
    evidence = {"metricsReadAttempts": 0, "maximumMetricsReads": AFTER_METRIC_READS,
                "additionalMetricsReads": 0, "attemptedDeltas": [],
                "observationBudgetMs": int(AFTER_METRIC_BUDGET * 1000),
                "retryIntervalMs": int(AFTER_METRIC_INTERVAL * 1000)}
    def in_budget():
        elapsed = clock() - started
        require(0 <= elapsed < AFTER_METRIC_BUDGET, "EP12 observation deadline exceeded")
    try:
        require(before is not None, "Missing before observation")
        previous = before
        with _observation_budget():
            for attempt in range(AFTER_METRIC_READS):
                in_budget()
                if attempt:
                    # Do not schedule a sleep/read that cannot fit its interval.
                    require(clock() - started + AFTER_METRIC_INTERVAL < AFTER_METRIC_BUDGET,
                            "EP12 observation deadline exceeded")
                    sleep(AFTER_METRIC_INTERVAL)
                    in_budget()
                evidence["metricsReadAttempts"] += 1
                evidence["additionalMetricsReads"] = evidence["metricsReadAttempts"] - 1
                value = read()  # Includes the unchanged _metrics structure/conservation checks.
                in_budget()
                require(before["bootId"] == value["bootId"] and before["instance"] == value["instance"] and
                        before["metrics"]["shortlink_edge_started_at_seconds"] ==
                        value["metrics"]["shortlink_edge_started_at_seconds"],
                        "Edge boot changed during path verification")
                for name in ("attempted", "delivered", "failed", "rejected"):
                    key = "shortlink_edge_events_" + name
                    require(value["metrics"][key] >= before["metrics"][key] and
                            value["metrics"][key] >= previous["metrics"][key],
                            "Edge event counter regressed during path verification")
                require(value["metrics"]["shortlink_edge_retained_worker_generations"] >= 1,
                        "No retained worker observation")
                delta = value["metrics"]["shortlink_edge_events_attempted"] - before["metrics"]["shortlink_edge_events_attempted"]
                evidence["attemptedDeltas"].append(delta)
                if delta >= 4:
                    value.update(attemptedDelta=delta, concurrentTrafficPossible=delta > 4,
                                 **evidence, observationElapsedMs=round((clock()-started)*1000, 3))
                    return value
                previous = value
        raise ValueError("Four APISIX business/denial probes were not observed")
    except (OSError, ValueError, http.client.HTTPException) as error:
        evidence["observationElapsedMs"] = round(max(0, clock()-started)*1000, 3)
        # Fixed assertions/classes only, never transport response bodies/headers.
        reason = str(error) if type(error) is ValueError else type(error).__name__
        raise _AfterObservationError(reason, evidence) from error


def _case(rows, case_id, action):
    try:
        rows.append({"id": case_id, "passed": True, "details": action()})
    except (OSError, ValueError, http.client.HTTPException) as error:
        # Error values are fixed assertions or exception class names, never raw headers/bodies.
        reason = str(error) if isinstance(error, ValueError) else type(error).__name__
        failure = {"id": case_id, "passed": False, "reason": reason}
        if isinstance(error, _AfterObservationError):
            failure["details"] = error.evidence
        rows.append(failure)


def _inside(context):
    require(os.stat("/proc/self/ns/net").st_ino ==
            os.stat(f"/proc/{context['apisixPid']}/ns/net").st_ino, "Child is outside the trusted APISIX netns")
    rows, snapshots = [], {}
    path = "/" + context["shortUri"]
    def direct(headers, method="GET"):
        return _http(context["networkGateway"], 8003, path, headers, method, context["apisixIp"])
    def edge(path, headers, method="GET"):
        return _http("127.0.0.1", 9080, path, headers, method, "127.0.0.1")
    def snapshot(label):
        def read():
            return _metrics(_http("127.0.0.1", 9099, "/shortlink/metrics", {}, expected_source="127.0.0.1"))
        value = _observe_after(snapshots.get("before"), read) if label == "after" else read()
        snapshots[label] = value
        return value
    _case(rows, "EP03_metrics_before", lambda: snapshot("before"))
    _case(rows, "EP04_direct_trusted_get", lambda: _redirect(direct(_headers(context)), context, "GET", False))
    _case(rows, "EP05_direct_trusted_head", lambda: _redirect(direct(_headers(context), "HEAD"), context, "HEAD"))
    _case(rows, "EP06_direct_invalid_xff_rejected", lambda: _status(direct(_headers(context, forwarded="invalid-ip")), 400))
    _case(rows, "EP07_direct_trusted_https_scheme", lambda: _redirect(direct(_headers(context, scheme="https")), context, "GET", True))
    _case(rows, "EP08_edge_overwrites_forwarding_claims", lambda: _redirect(
        edge(path, _headers(context, forwarded="invalid-ip", scheme="https", forged=True)), context, "GET", False))
    _case(rows, "EP09_edge_head", lambda: _redirect(
        edge(path, _headers(context, forwarded="invalid-ip", scheme="https", forged=True), "HEAD"), context, "HEAD"))
    _case(rows, "EP10_edge_forged_management_identity_rejected", lambda: _status(
        edge("/api/short-link/admin/v1/group", _headers(context, forged=True, management=True)), 401))
    _case(rows, "EP11_edge_internal_route_rejected", lambda: _status(
        edge("/internal/v1/events/quality", _headers(context, forged=True)), 404))
    _case(rows, "EP12_metrics_after", lambda: snapshot("after"))
    return rows


def verify(state_path, fixture=None):
    context = _load(state_path, fixture)
    rows = []
    _case(rows, "EP01_untrusted_direct_gateway", lambda: _status(_http(
        "127.0.0.1", 8000, RECOVERY, _headers(context, context["apisixIp"], forged=True, management=True),
        expected_source="127.0.0.1"), 403))
    _case(rows, "EP02_untrusted_direct_redirect", lambda: _status(_http(
        "127.0.0.1", 8003, "/" + context["shortUri"], _headers(context, context["apisixIp"], forged=True),
        expected_source="127.0.0.1"), 403))
    result = subprocess.run(["nsenter", "--target", str(context["apisixPid"]), "--net", sys.executable,
                             "-B", str(Path(__file__).resolve()), "--inside-netns"],
                            input=json.dumps(context), text=True, encoding="utf-8", capture_output=True,
                            timeout=25, check=False)
    require(result.returncode in (0, 1) and len(result.stdout) <= 128 * 1024, "Namespace verifier failed")
    child = json.loads(result.stdout)
    require(isinstance(child, dict) and len(child.get("cases", [])) == 10, "Incomplete namespace evidence")
    rows.extend(child["cases"])
    return {"suite": "performance_edge_path", "runId": context["runId"],
            "passed": all(row["passed"] for row in rows), "caseCount": len(rows),
            "maximumHttpRequests": 15, "concurrency": 1, "followsRedirects": False, "cases": rows,
            "path": {"directSocketSource": context["apisixIp"], "directForwardedClient": "127.0.0.1",
                     "edgeSocketSource": "127.0.0.1", "edgeForwardedClient": "127.0.0.1",
                     "meaning": "A uses container-to-host 8003; B uses netns loopback 9080 plus APISIX upstream"}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", nargs="?", type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--inside-netns", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        if args.inside_netns:
            raw = sys.stdin.read(16385)
            require(len(raw) <= 16384, "Namespace input exceeded its budget")
            rows = _inside(json.loads(raw))
            value = {"cases": rows, "passed": all(row["passed"] for row in rows)}
        else:
            require(args.state is not None, "Specify the READY performance state.json")
            value = verify(args.state, args.fixture)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Do not expose the private input object, command stderr or response bodies.
        value = {"suite": "performance_edge_path", "passed": False,
                 "setupError": str(error) if type(error) is ValueError else type(error).__name__}
    print(json.dumps(value, ensure_ascii=False, allow_nan=False))
    return 0 if value["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
