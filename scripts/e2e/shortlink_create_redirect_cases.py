"""Finite, opt-in public HTTP checks for short-link creation and redirection.

The caller starts an isolated deployment and explicitly supplies its APISIX origin
and management/redirect virtual hosts. This module starts no services, reads no
database, visits no internal/Agent/Analytics endpoint, and never follows Location.
Only public registration/login supplies session credentials. Returned evidence
contains no request bodies, passwords, session tokens, or Set-Cookie headers.

Example (only after the isolated deployment is ready):
    python shortlink_create_redirect_cases.py http://127.0.0.1:19080 \
        manage.e2e.test short.e2e.test
"""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import http.client
import json
import re
import time
from typing import Any
from urllib.parse import urlsplit
import uuid


_PREFIX = "/api/short-link/admin/v1"
_MANAGEMENT_ROUTES = {
    ("POST", _PREFIX + "/user"),
    ("POST", _PREFIX + "/user/login"),
    ("GET", _PREFIX + "/user/initialization"),
    ("POST", _PREFIX + "/create"),
    ("POST", _PREFIX + "/update"),
    ("POST", _PREFIX + "/recycle-bin/save"),
    ("POST", _PREFIX + "/recycle-bin/recover"),
}
_MAX_BODY = 65_536
_INITIALIZATION_SECONDS = 60
_CONVERGENCE_SECONDS = 5
_CONVERGENCE_INTERVAL_SECONDS = 0.2
_HTTP_TIMEOUT_SECONDS = 10
_GMT8 = timezone(timedelta(hours=8))


class _PublicClient:
    def __init__(self, base_url: str, management_host: str, redirect_host: str):
        base = urlsplit(base_url)
        if (
            base.scheme not in ("http", "https")
            or not base.hostname
            or base.username is not None
            or base.password is not None
            or base.path not in ("", "/")
            or base.query
            or base.fragment
        ):
            raise ValueError("base_url must be an HTTP(S) origin without credentials or a path")
        for host in (management_host, redirect_host):
            if not re.fullmatch(r"[A-Za-z0-9.-]+(?::[0-9]{1,5})?", host):
                raise ValueError("Virtual hosts must be bare DNS hosts, optionally with a port")
        self.base = base
        self.management_host = management_host.lower()
        self.redirect_host = redirect_host.lower()
        self.current_case = "configuration"
        self.calls: list[dict[str, Any]] = []
        self.cookies: dict[str, str] = {}

    def check(self, condition: bool, message: str) -> None:
        if not condition:
            raise AssertionError(f"{self.current_case}: {message}")

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        account: dict[str, str] | None = None,
        *,
        redirect: bool = False,
        timeout: float = _HTTP_TIMEOUT_SECONDS,
    ) -> tuple[int, dict[str, str], bytes, Any]:
        if redirect:
            self.check(method in ("GET", "HEAD") and bool(re.fullmatch(r"/[A-Za-z0-9]{9}", path)),
                       "Redirect request outside the permitted short-code scope")
            self.check(payload is None and account is None, "Credentials must not reach redirect routes")
        else:
            self.check((method, path) in _MANAGEMENT_ROUTES, "Management request outside the explicit allowlist")
        host = self.redirect_host if redirect else self.management_host
        headers = {"Host": host, "Accept": "application/json", "User-Agent": "Shortlink-Functional-E2E/1.0"}
        if account is not None:
            headers.update({"username": account["username"], "token": account["token"]})
        if redirect and host in self.cookies:
            headers["Cookie"] = self.cookies[host]
        body = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        connection_type = http.client.HTTPSConnection if self.base.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(self.base.hostname, self.base.port, timeout=timeout)
        try:
            # http.client does not implement redirect following or environment proxy discovery.
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            status = response.status
            response_headers = {key.lower(): value for key, value in response.getheaders()}
            content = response.read(_MAX_BODY + 1)
        except (OSError, http.client.HTTPException) as error:
            raise AssertionError(
                f"{self.current_case}: {method} {path} transport failed ({type(error).__name__})"
            ) from None
        finally:
            connection.close()
        self.check(len(content) <= _MAX_BODY, f"{method} {path} exceeded the response-body budget")
        parsed = None
        if content:
            try:
                parsed = json.loads(content)
            except (ValueError, UnicodeError):
                pass
        if redirect and "set-cookie" in response_headers:
            # Visitor continuity only; cookies remain in memory and are not evidence.
            cookie = response_headers["set-cookie"].split(";", 1)[0]
            if re.fullmatch(r"[A-Za-z0-9_-]+=[A-Za-z0-9_.~-]+", cookie):
                self.cookies[host] = cookie
        evidence: dict[str, Any] = {"method": method, "path": path, "host": host, "httpStatus": status,
                                    "bodyBytes": len(content)}
        if isinstance(parsed, dict) and isinstance(parsed.get("code"), str):
            # Application codes only, never reflected error messages or response bodies.
            code = parsed["code"]
            evidence["resultCode"] = code if re.fullmatch(r"[A-Za-z0-9_-]{1,64}", code) else "UNEXPECTED_CODE"
        if redirect and "location" in response_headers:
            evidence["location"] = response_headers["location"]
        self.calls.append(evidence)
        return status, response_headers, content, parsed

    def success(self, response: tuple[int, dict[str, str], bytes, Any]) -> Any:
        status, _, _, result = response
        self.check(status == 200, f"Expected HTTP 200, received {status}")
        self.check(isinstance(result, dict) and result.get("code") == "0", "Expected Result code='0'")
        self.check(result.get("success", True) is True, "Success flag contradicts Result code")
        return result.get("data")

    def rejected(self, response: tuple[int, dict[str, str], bytes, Any], status: int, code: str) -> None:
        actual, headers, _, result = response
        self.check(actual == status, f"Expected rejection HTTP {status}, received {actual}")
        self.check(isinstance(result, dict) and result.get("code") == code,
                   f"Expected rejection Result code='{code}'")
        self.check(result.get("success", False) is False, "Rejected request reported success")
        self.check(result.get("data") is None, "Rejected request exposed successful result data")
        self.check("location" not in headers, "Rejected management request must not redirect")


def run_cases(base_url: str, management_host: str, redirect_host: str) -> dict:
    """Run 16 finite public-route cases; raise a case-labelled AssertionError on failure.

    Accounts and three links are unique to this invocation. Test records are left in
    the caller's isolated schema for diagnosis. The result is JSON serializable
    and deliberately excludes all authentication and registration credentials.
    Initialization polling uses one second; cache convergence uses 0.2 seconds
    for at most five seconds. Requests are sequential and never a load test.
    """
    client = _PublicClient(base_url, management_host, redirect_host)
    run_id = uuid.uuid4().hex
    started_at = datetime.now(timezone.utc).isoformat()
    cases: list[dict[str, Any]] = []
    case_start = 0
    case_time = 0.0

    def begin(name: str) -> None:
        nonlocal case_start, case_time
        client.current_case = name
        case_start = len(client.calls)
        case_time = time.monotonic()

    def finish(**facts: Any) -> None:
        cases.append({"name": client.current_case, "status": "PASSED",
                      "elapsedSeconds": round(time.monotonic() - case_time, 3),
                      "evidence": client.calls[case_start:], "facts": facts})

    def create_account(suffix: str) -> dict[str, str]:
        username = f"e2e_{run_id}_{suffix}"
        password = "E2e!" + uuid.uuid4().hex
        client.success(client.request("POST", _PREFIX + "/user",
                                      {"username": username, "password": password, "realName": "HTTP E2E"}))
        login = client.success(client.request("POST", _PREFIX + "/user/login",
                                              {"username": username, "password": password}))
        client.check(isinstance(login, dict) and isinstance(login.get("token"), str) and bool(login["token"]),
                     "Public login did not return a session token")
        account = {"username": username, "token": login["token"]}
        deadline = time.monotonic() + _INITIALIZATION_SECONDS
        for attempt in range(_INITIALIZATION_SECONDS + 1):
            state = client.success(client.request("GET", _PREFIX + "/user/initialization", account=account))
            client.check(isinstance(state, dict), "Initialization state is not an object")
            if state.get("state") == "READY":
                client.check(isinstance(state.get("groupId"), str) and bool(state["groupId"]),
                             "READY initialization lacks the default group ID")
                account["gid"] = state["groupId"]
                return account
            client.check(time.monotonic() < deadline and attempt < _INITIALIZATION_SECONDS,
                         "Default-group initialization did not reach READY within 60 seconds")
            time.sleep(1)
        raise AssertionError(f"{client.current_case}: Initialization attempt budget exhausted")

    def creation(account: dict[str, str], purpose: str, target: str) -> dict[str, Any]:
        return {"requestId": f"e2e-{run_id}-{purpose}", "domain": client.redirect_host,
                "originUrl": target, "gid": account["gid"], "createdType": 0,
                "validDateType": 0, "validDate": None, "describe": "Isolated public HTTP E2E"}

    def assert_created(data: Any, target: str, account: dict[str, str]) -> dict[str, Any]:
        client.check(isinstance(data, dict), "Created result is not an object")
        client.check(isinstance(data.get("shortUri"), str) and bool(re.fullmatch(r"[A-Za-z0-9]{9}", data["shortUri"])),
                     "Created short code is not exactly nine Base62 characters")
        client.check(type(data.get("linkId")) is int and 0 < data["linkId"] < 2 ** 52,
                     "Created link ID is outside the 52-bit allocation range")
        client.check(data.get("fullShortUrl") == "https://" + client.redirect_host + "/" + data["shortUri"],
                     "Created URL does not match the configured host and short code")
        client.check(data.get("originUrl") == target and data.get("gid") == account["gid"],
                     "Created result does not preserve target and owner group")
        client.check(data.get("routeVersion") == 1 and data.get("targetRevision") == 1,
                     "New route must start at route/target revision 1")
        return data

    def redirect_once(short: dict[str, Any], target: str, method: str = "GET") -> None:
        status, headers, content, _ = client.request(method, "/" + short["shortUri"], redirect=True)
        client.check(status == 302, f"{method} expected 302, received {status}")
        client.check(headers.get("location") == target, f"{method} redirected to an unexpected target")
        if method == "HEAD":
            client.check(content == b"", "HEAD returned a response body")

    def await_redirect(short: dict[str, Any], target: str | None, previous: str | None) -> float:
        started = time.monotonic()
        deadline = started + _CONVERGENCE_SECONDS
        max_attempts = int(_CONVERGENCE_SECONDS / _CONVERGENCE_INTERVAL_SECONDS) + 1
        for _ in range(max_attempts):
            remaining = deadline - time.monotonic()
            client.check(remaining > 0, "Route cache did not converge within 5 seconds")
            status, headers, _, _ = client.request("GET", "/" + short["shortUri"], redirect=True,
                                                   timeout=min(_HTTP_TIMEOUT_SECONDS, remaining))
            elapsed = time.monotonic() - started
            client.check(elapsed <= _CONVERGENCE_SECONDS, "Route cache did not converge within 5 seconds")
            if target is None and status == 404:
                client.check("location" not in headers, "Unavailable link exposed a Location")
                return round(elapsed, 3)
            if target is not None and status == 302 and headers.get("location") == target:
                return round(elapsed, 3)
            allowed_previous = (status == 404 and previous is None) or (
                status == 302 and previous is not None and headers.get("location") == previous)
            client.check(allowed_previous, f"Unexpected HTTP {status} or target during cache convergence")
            time.sleep(min(_CONVERGENCE_INTERVAL_SECONDS, max(0, deadline - time.monotonic())))
        raise AssertionError(f"{client.current_case}: Cache-convergence attempt budget exhausted")

    begin("01_public_account_a_and_default_group")
    account_a = create_account("a")
    finish(defaultGroupReady=True)
    begin("02_public_account_b_and_default_group")
    account_b = create_account("b")
    client.check(account_a["gid"] != account_b["gid"], "Two accounts share a default group ID")
    finish(defaultGroupReady=True, distinctGroups=True)

    target_a = f"https://example.com/e2e/{run_id}/original"
    target_b = f"https://example.com/e2e/{run_id}/updated"
    create = creation(account_a, "primary", target_a)

    begin("03_unauthenticated_create_is_rejected")
    client.rejected(client.request("POST", _PREFIX + "/create", create), 401, "INVALID_SESSION")
    finish()
    begin("04_invalid_target_is_rejected")
    invalid = creation(account_a, "invalid", "javascript:alert(1)")
    client.rejected(client.request("POST", _PREFIX + "/create", invalid, account_a), 400, "REMOTE_400")
    finish()
    begin("05_create_nine_character_short_link")
    short = assert_created(client.success(client.request("POST", _PREFIX + "/create", create, account_a)),
                           target_a, account_a)
    finish(linkId=short["linkId"], shortUri=short["shortUri"], routeVersion=1, targetRevision=1)
    begin("06_identical_request_is_idempotent")
    repeated = client.success(client.request("POST", _PREFIX + "/create", create, account_a))
    client.check(repeated == short, "Identical requestId/payload returned a different committed result")
    finish(sameCommittedResult=True)
    begin("07_changed_payload_conflicts_with_request_id")
    client.rejected(client.request("POST", _PREFIX + "/create", {**create, "originUrl": target_b}, account_a),
                    409, "REMOTE_409")
    finish()
    begin("08_get_redirects_without_following_location")
    redirect_once(short, target_a)
    finish(followedLocation=False)
    begin("09_head_redirects_without_a_body")
    redirect_once(short, target_a, "HEAD")
    finish(bodyBytes=0, followedLocation=False)
    begin("10_repeated_real_gets_preserve_target")
    for _ in range(3):
        redirect_once(short, target_a)
    finish(visits=3, sameTarget=True)

    update = {"fullShortUrl": short["fullShortUrl"], "originGid": account_a["gid"],
              "gid": account_a["gid"], "originUrl": target_b, "validDateType": 0,
              "validDate": None, "describe": "Isolated public HTTP E2E update", "expectedVersion": 1}
    lifecycle = {"fullShortUrl": short["fullShortUrl"], "gid": account_a["gid"], "expectedVersion": 1}
    begin("11_cross_account_mutations_are_rejected")
    other_group_create = creation(account_a, "cross-account", target_a)
    client.rejected(client.request("POST", _PREFIX + "/create", other_group_create, account_b), 404, "REMOTE_404")
    client.rejected(client.request("POST", _PREFIX + "/update", update, account_b), 404, "REMOTE_404")
    client.rejected(client.request("POST", _PREFIX + "/recycle-bin/save", lifecycle, account_b), 404, "REMOTE_404")
    redirect_once(short, target_a)
    finish(existenceNotDisclosed=True, ownerTargetUnchanged=True)
    begin("12_update_invalidates_warmed_route_cache")
    client.success(client.request("POST", _PREFIX + "/update", update, account_a))
    update_convergence = await_redirect(short, target_b, target_a)
    redirect_once(short, target_b, "HEAD")
    finish(expectedRouteVersion=2, newTargetObserved=True, cacheConvergenceSeconds=update_convergence)
    begin("13_stale_update_version_is_rejected")
    client.rejected(client.request("POST", _PREFIX + "/update", {**update, "originUrl": target_a}, account_a),
                    409, "REMOTE_409")
    redirect_once(short, target_b)
    finish(newTargetPreserved=True)
    begin("14_recycle_and_restore_invalidate_cache")
    client.success(client.request("POST", _PREFIX + "/recycle-bin/save", {**lifecycle, "expectedVersion": 2}, account_a))
    recycle_convergence = await_redirect(short, None, target_b)
    client.success(client.request("POST", _PREFIX + "/recycle-bin/recover", {**lifecycle, "expectedVersion": 3}, account_a))
    restore_convergence = await_redirect(short, target_b, None)
    redirect_once(short, target_b)
    finish(recycledHttpStatus=404, restoredHttpStatus=302, expectedRouteVersion=4,
           recycleConvergenceSeconds=recycle_convergence, restoreConvergenceSeconds=restore_convergence)

    begin("15_expiration_stops_a_previously_working_redirect")
    expiration = datetime.fromtimestamp(time.time() + 12, _GMT8).replace(microsecond=0)
    expiring_target = f"https://example.com/e2e/{run_id}/expires"
    expires_create = {**creation(account_a, "expires", expiring_target), "validDateType": 1,
                      "validDate": expiration.strftime("%Y-%m-%d %H:%M:%S")}
    expiring = assert_created(client.success(client.request("POST", _PREFIX + "/create", expires_create, account_a)),
                              expiring_target, account_a)
    redirect_once(expiring, expiring_target)
    # A single future expiry, with bounded wall-clock waiting; never past-date creation.
    remaining = expiration.timestamp() + 1 - time.time()
    client.check(remaining <= 14, "Unexpected clock discontinuity while checking expiration")
    if remaining > 0:
        time.sleep(remaining)
    for _ in range(2):
        status, headers, _, _ = client.request("GET", "/" + expiring["shortUri"], redirect=True)
        client.check(status == 404, f"Expired link expected HTTP 404, received {status}")
        client.check("location" not in headers, "Expired link retained a Location header")
    finish(expiredHttpStatus=404, followedLocation=False)

    begin("16_independent_head_only_link")
    head_target = f"https://example.com/e2e/{run_id}/head-only"
    head_create = creation(account_a, "head-only", head_target)
    head_only = assert_created(client.success(client.request("POST", _PREFIX + "/create", head_create, account_a)),
                               head_target, account_a)
    redirect_once(head_only, head_target, "HEAD")
    finish(linkId=head_only["linkId"], shortUri=head_only["shortUri"], headRequests=1, getRequests=0,
           bodyBytes=0, followedLocation=False)

    return {"suite": "shortlink-public-create-redirect", "runId": run_id, "status": "PASSED",
            "startedAt": started_at, "finishedAt": datetime.now(timezone.utc).isoformat(),
            "caseCount": len(cases), "httpRequestCount": len(client.calls),
            "scope": {"agent": False, "analytics": False, "internalApi": False,
                      "databaseAccess": False, "loadTest": False, "followsRedirects": False},
            "observations": {"headOnlyLinkId": head_only["linkId"], "headOnlyShortUri": head_only["shortUri"],
                             "headOnlyHost": client.redirect_host, "headOnlyHttpStatus": 302,
                             "headOnlyHeadRequests": 1, "headOnlyGetRequests": 0,
                             "headOnlyExpectedClickEvents": 0, "headOnlyExpectedRequestEvents": 1,
                             "headOnlyTarget": head_target},
            "cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url", help="APISIX origin, for example http://127.0.0.1:19080")
    parser.add_argument("management_host", help="Configured public management virtual host")
    parser.add_argument("redirect_host", help="Configured short-link virtual host")
    arguments = parser.parse_args()
    print(json.dumps(run_cases(arguments.base_url, arguments.management_host, arguments.redirect_host),
                     ensure_ascii=False, indent=2))
