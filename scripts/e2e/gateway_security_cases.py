"""Small real-HTTP gateway boundary probes; no services or configuration are changed.

The caller installs an isolated APISIX rate=2/burst=0 or conn=2/burst=0
configuration before the corresponding probe. Run the two probes separately;
an active request-rate limit would obscure the connection-limit assertion.
Each suite has a five-second budget. Admission holds at most 65 concurrent
connections, closes them, then makes one recovery request. This is a boundary
check, not a load or throughput measurement. Incomplete-body probes use HTTP.
"""
import argparse
import http.client
import json
import re
import select
import socket
import time
import urllib.parse


LOGIN = "/api/short-link/admin/v1/user/login"
CREATE = "/api/short-link/admin/v1/create"
RECOVERY = "/api/short-link/v1/user/has-username?username=e2egatewayprobe"
BUDGET_SECONDS = 5.0


def _endpoint(base_url):
    parsed = urllib.parse.urlsplit(base_url)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ("", "/")):
        raise ValueError("Use an http(s) origin without credentials, path, query or fragment")
    return parsed


def _remaining(deadline, cap=1.0):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("The five-second functional-probe budget expired")
    return min(cap, remaining)


def _request(endpoint, host, method, path, deadline, headers=None, body=None):
    if not host or any(char in host for char in "\r\n"):
        raise ValueError("A single explicit Host is required")
    factory = http.client.HTTPSConnection if endpoint.scheme == "https" else http.client.HTTPConnection
    connection = factory(endpoint.hostname, endpoint.port or (443 if endpoint.scheme == "https" else 80),
                         timeout=_remaining(deadline))
    values = {"Host": host, "Connection": "close", **(headers or {})}
    if body is not None:
        values["Content-Type"] = "application/json"
    try:
        connection.request(method, path, body=body, headers=values)
        response = connection.getresponse()
        result = {"status": response.status,
                  "body_preview": response.read(1024).decode("utf-8", errors="replace")}
        return result
    finally:
        connection.close()


def _case(case_id, action):
    started = time.monotonic()
    try:
        details = action()
        return {"id": case_id, "passed": True, "elapsed_ms": round((time.monotonic() - started) * 1000, 1),
                "details": details}
    except Exception as error:
        return {"id": case_id, "passed": False, "elapsed_ms": round((time.monotonic() - started) * 1000, 1),
                "error": type(error).__name__ + ": " + str(error)}


def _require(condition, message):
    if not condition:
        raise AssertionError(message)


def _report(suite, started, cases, requests, maximum):
    return {"suite": suite, "passed": all(row["passed"] for row in cases), "case_count": len(cases),
            "request_count": requests, "max_open_connections": maximum,
            "elapsed_ms": round((time.monotonic() - started) * 1000, 1), "budget_seconds": BUDGET_SECONDS,
            "cases": cases}


def run_edge_security(base_url, management_host, redirect_host, known_short_uri) -> dict:
    """Seven named HTTP assertions, with no login or business mutations."""
    started = time.monotonic()
    deadline = started + BUDGET_SECONDS
    endpoint = _endpoint(base_url)
    uri = known_short_uri.removeprefix("/")
    if not re.fullmatch(r"[A-Za-z0-9]{1,32}", uri):
        raise ValueError("known_short_uri must be one existing Base62 alias")
    forged = {"x-shortlink-tenant-id": "999999", "x-shortlink-username": "forged",
              "x-shortlink-auth-version": "1", "x-agent-userid": "999999", "userId": "999999",
              "realName": "forged", "X-Internal-Token": "forged-internal-token",
              "username": "forgedsession", "token": "forged-session-token-that-is-not-in-redis",
              "Authorization": "Bearer forged"}
    specs = [
        ("GS01_unknown_host", "GET", "/" + uri, "unregistered-gateway-e2e.invalid", 404, None, None),
        ("GS02_public_internal_path", "GET", "/internal/command/risk/current", redirect_host, 404, None, None),
        ("GS03_management_internal_path", "GET", "/internal/command/risk/current", management_host, 404, None, None),
        ("GS04_public_post", "POST", "/" + uri, redirect_host, 405, None, b"{}"),
        ("GS05_encoded_slash", "GET", "/" + uri + "%2Fextra", redirect_host, 400, None, None),
        ("GS06_create_requires_login", "POST", CREATE, management_host, 401, None, b"{}"),
        ("GS07_forged_identity_is_not_a_session", "POST", CREATE, management_host, 401, forged, b"{}"),
    ]
    requests = 0
    rows = []
    for case_id, method, path, host, expected, headers, body in specs:
        def check(method=method, path=path, host=host, expected=expected, headers=headers, body=body):
            nonlocal requests
            requests += 1
            result = _request(endpoint, host, method, path, deadline, headers, body)
            _require(result["status"] == expected,
                     "Expected HTTP " + str(expected) + ", got " + str(result))
            return {"method": method, "path": path, "expected_status": expected, "actual_status": result["status"]}
        rows.append(_case(case_id, check))
    return _report("edge_security", started, rows, requests, 1)


def probe_rate_limit(base_url, host, path, expected_allowed_status) -> dict:
    """Three requests under isolated APISIX rate=2, burst=0; no retries."""
    started = time.monotonic()
    deadline = started + BUDGET_SECONDS
    endpoint = _endpoint(base_url)
    requests = 0

    def check():
        nonlocal requests
        # Drain any preceding request's excess; 0.7s exceeds the 0.5s interval.
        time.sleep(min(0.7, _remaining(deadline)))
        statuses = []
        for position in range(3):
            if position == 2:
                time.sleep(min(0.7, _remaining(deadline)))
            requests += 1
            statuses.append(_request(endpoint, host, "GET", path, deadline)["status"])
        expected = [expected_allowed_status, 429, expected_allowed_status]
        _require(statuses == expected, "Expected allow/reject/recover " + str(expected) + ", got " + str(statuses))
        return {"statuses": statuses, "required_isolated_config": {"rate": 2, "burst": 0},
                "recovery_wait_seconds": 0.7}

    row = _case("GL01_request_rate_allow_reject_recover", check)
    return _report("request_rate_limit", started, [row], requests, 1)


def _hold_login(endpoint, host, deadline, forwarded):
    if endpoint.scheme != "http":
        raise ValueError("Incomplete-body probes require the isolated HTTP endpoint")
    transport = socket.create_connection((endpoint.hostname, endpoint.port or 80), timeout=_remaining(deadline, 0.5))
    transport.settimeout(_remaining(deadline, 0.5))
    headers = {"Host": host, "Content-Type": "application/json", "Content-Length": "1024", "Connection": "close"}
    if forwarded:
        headers.update({"X-Forwarded-Proto": "http", "X-Forwarded-For": "198.51.100.250"})
    if any("\r" in value or "\n" in value for value in headers.values()):
        transport.close()
        raise ValueError("Invalid request header")
    wire = "POST " + LOGIN + " HTTP/1.1\r\n" + "".join(name + ": " + value + "\r\n" for name, value in headers.items()) + "\r\n{"
    try:
        transport.sendall(wire.encode("ascii"))
        return transport
    except Exception:
        transport.close()
        raise


def _admission(base_url, host, held_count, forwarded, case_id, suite):
    started = time.monotonic()
    deadline = started + BUDGET_SECONDS
    endpoint = _endpoint(base_url)
    requests = 0
    held = []
    maximum = 0

    def close_held():
        while held:
            transport = held.pop()
            try:
                transport.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            transport.close()

    def check():
        nonlocal requests, maximum
        try:
            for _ in range(held_count):
                requests += 1
                held.append(_hold_login(endpoint, host, deadline, forwarded))
                maximum = max(maximum, len(held))
            # A single bounded scheduling interval lets all headers enter admission.
            ready, _, _ = select.select(held, [], [], _remaining(deadline, 0.15))
            if ready:
                preview = ready[0].recv(256).decode("ascii", errors="replace").split("\r\n", 1)[0]
                raise AssertionError("A supposed held request completed early; check trusted peer/config: " + preview)
            requests += 1
            maximum = max(maximum, len(held) + 1)
            headers = {"X-Forwarded-Proto": "http", "X-Forwarded-For": "198.51.100.250"} if forwarded else None
            rejected = _request(endpoint, host, "GET", RECOVERY, deadline, headers)
            _require(rejected["status"] == 429,
                     "Expected HTTP 429 while " + str(held_count) + " bodies are incomplete, got " + str(rejected))
        finally:
            close_held()
        time.sleep(min(0.15, _remaining(deadline)))
        requests += 1
        recovered = _request(endpoint, host, "GET", RECOVERY, deadline, headers)
        _require(recovered["status"] == 200, "Admission did not recover after closing bodies: " + str(recovered))
        return {"held_incomplete_bodies": held_count, "rejected_status": rejected["status"],
                "recovered_status": recovered["status"], "all_held_connections_closed": True,
                "trusted_namespace_required": forwarded}

    try:
        row = _case(case_id, check)
    finally:
        close_held()
    return _report(suite, started, [row], requests, maximum)


def probe_connection_limit(base_url, management_host) -> dict:
    """Two unfinished public-login bodies; the third request must get edge 429.

Requires isolated management limit-conn conn=2, burst=0 and a nonrestrictive
request-rate setting. The recovery lookup is read-only and needs no session.
"""
    return _admission(base_url, management_host, 2, False,
                      "GL02_edge_connection_allow_reject_recover", "edge_connection_limit")


def probe_restored_configuration(base_url, management_host, redirect_host) -> dict:
    """Verify both route rates and management connection limits were restored.

    Under original rate=100/1000 and conn=200 settings, each pair completes
    within 0.5s without rate rejection and three unfinished login requests fit.
    Only the fresh fixture's nonexistent alias /000000000 is used; no HEAD-only link
    is visited. At most four connections are simultaneously open.
    """
    started = time.monotonic()
    deadline = started + BUDGET_SECONDS
    endpoint = _endpoint(base_url)
    requests = 0
    maximum = 0
    held = []

    def close_held():
        while held:
            transport = held.pop()
            try:
                transport.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            transport.close()

    def pair(host, path, expected):
        nonlocal requests, maximum
        starts, statuses = [], []
        for _ in range(2):
            starts.append(time.monotonic())
            requests += 1
            maximum = max(maximum, 1)
            statuses.append(_request(endpoint, host, "GET", path, deadline)["status"])
        elapsed = time.monotonic() - starts[0]
        start_gap = starts[1] - starts[0]
        _require(statuses == [expected, expected],
                 "Original rate was not restored for " + host + ": " + str(statuses))
        _require(start_gap < 0.5 and elapsed < 0.5,
                 "Insufficient rate-boundary evidence for " + host + ": pair took " + str(round(elapsed * 1000, 1)) + "ms")
        return {"statuses": statuses, "start_gap_ms": round(start_gap * 1000, 1),
                "pair_elapsed_ms": round(elapsed * 1000, 1)}

    def check():
        nonlocal requests, maximum
        time.sleep(min(0.7, _remaining(deadline)))
        management = pair(management_host, RECOVERY, 200)
        redirect = pair(redirect_host, "/000000000", 404)
        try:
            for _ in range(3):
                requests += 1
                held.append(_hold_login(endpoint, management_host, deadline, False))
                maximum = max(maximum, len(held))
            ready, _, _ = select.select(held, [], [], _remaining(deadline, 0.15))
            if ready:
                preview = ready[0].recv(256).decode("ascii", errors="replace").split("\r\n", 1)[0]
                raise AssertionError("Original connection limit was not restored: a held login completed early: " + preview)
            requests += 1
            maximum = max(maximum, len(held) + 1)
            fourth = _request(endpoint, management_host, "GET", RECOVERY, deadline)["status"]
            _require(fourth == 200, "Fourth management request was rejected while three bodies were held: " + str(fourth))
        finally:
            close_held()
        time.sleep(min(0.15, _remaining(deadline)))
        requests += 1
        recovered = _request(endpoint, management_host, "GET", RECOVERY, deadline)["status"]
        _require(recovered == 200, "Management did not recover after closing all held bodies: " + str(recovered))
        return {"management_pair": management, "redirect_pair": redirect,
                "quiet_seconds": 0.7, "held_incomplete_bodies": 3, "held_early_responses": 0,
                "fourth_status": fourth, "recovered_status": recovered,
                "all_held_connections_closed": True}

    try:
        row = _case("GL04_original_configuration_restored", check)
    finally:
        close_held()
    return _report("restored_configuration", started, [row], requests, maximum)


def probe_gateway_admission(address, management_host) -> dict:
    """Run from APISIX's network namespace so the real socket peer is trusted."""
    return _admission("http://" + address, management_host, 64, True,
                      "GL03_gateway_64_slot_admission_recovers", "gateway_admission")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-admission", metavar="HOST:8000", required=True,
                        help="Reachable Gateway address from the APISIX network namespace")
    parser.add_argument("--management-host", required=True)
    args = parser.parse_args()
    result = probe_gateway_admission(args.gateway_admission, args.management_host)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
