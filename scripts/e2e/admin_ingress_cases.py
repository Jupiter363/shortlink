"""Finite public Admin session and streaming-body checks on an isolated E2E state.

Starts no service and changes no proxy settings. Registers two unique accounts;
all HTTP requests enter the state's APISIX origin. Credentials stay in memory.
Each case has a total deadline; incomplete uploads always close their sockets.
"""
from __future__ import annotations

import argparse
import http.client
import json
from pathlib import Path
import re
import select
import socket
import threading
import time
from urllib.parse import urlencode, urlsplit
import uuid


ROOT = Path(__file__).resolve().parents[2]
REGISTER = "/api/short-link/admin/v1/user"
LOGIN = REGISTER + "/login"
LOGOUT = REGISTER + "/logout"
CHECK = "/api/short-link/v1/user/check-login"
LOOKUP = "/api/short-link/v1/user/has-username"
ROUTES = {("POST", REGISTER), ("POST", LOGIN), ("DELETE", LOGOUT),
          ("GET", CHECK), ("GET", LOOKUP)}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def remaining(deadline):
    value = deadline - time.monotonic()
    if value <= 0:
        raise TimeoutError("Case deadline exceeded")
    return value


def origin(base_url, management_host):
    base = urlsplit(base_url)
    if (base.scheme != "http" or base.hostname not in ("127.0.0.1", "::1")
            or base.username is not None or base.password is not None
            or base.path not in ("", "/") or base.query or base.fragment
            or base.port is None or not 1024 <= base.port <= 65535):
        raise ValueError("An explicit loopback HTTP test origin is required")
    if not re.fullmatch(r"[A-Za-z0-9.-]+(?::[0-9]{1,5})?", management_host):
        raise ValueError("Invalid management virtual host")
    return base


class Client:
    def __init__(self, base_url, management_host):
        self.base = origin(base_url, management_host)
        self.host = management_host

    def request(self, method, route, *, deadline, query=None, payload=None, account=None):
        require((method, route) in ROUTES, "Request is outside the finite route allowlist")
        path = route + ("?" + urlencode(query) if query else "")
        headers = {"Host": self.host, "Accept": "application/json", "Connection": "close",
                   "User-Agent": "Shortlink-Admin-Ingress-E2E/1.0"}
        if account is not None:
            headers.update(username=account["username"], token=account["token"])
        body = None
        if payload is not None:
            body = json.dumps(payload, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        conn = http.client.HTTPConnection(self.base.hostname, self.base.port, timeout=remaining(deadline))
        connected = []

        def abort():
            # A total deadline also bounds a response that trickles header/body bytes.
            for active_socket in connected:
                try:
                    active_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            conn.close()

        timer = threading.Timer(remaining(deadline), abort)
        timer.daemon = True
        timer.start()
        try:
            conn.connect()
            connected.append(conn.sock)
            remaining(deadline)
            conn.request(method, path, body=body, headers=headers)
            response = conn.getresponse()
            content = response.read(65_537)
            remaining(deadline)
            require(len(content) <= 65_536, "Response exceeds the bounded body size")
            try:
                parsed = json.loads(content)
            except (ValueError, UnicodeError):
                parsed = None
            return response.status, parsed
        finally:
            timer.cancel()
            conn.close()
            timer.join(timeout=1)

    @staticmethod
    def success(response):
        status, data = response
        require(status == 200, "Expected HTTP 200")
        require(isinstance(data, dict) and data.get("code") == "0"
                and data.get("success", True) is True, "Expected a successful API result")
        return data.get("data")


def incomplete_upload(base_url, management_host, *, content_length, deadline,
                      encoding=None, drip_interval=None):
    """Send headers and one body byte, then optionally drip; never finish a body.

    Reading the first response header, not a completed upload, proves the server
    answered while the declared body was incomplete. No session is sent here.
    """
    base = origin(base_url, management_host)
    require(content_length > 32, "Probe body must remain incomplete")
    require(encoding in (None, "gzip"), "Unsupported finite encoding probe")
    require(drip_interval is None or 0.1 <= drip_interval < 1,
            "Slow upload interval must remain below the edge idle timeout")
    header = (f"POST {LOGIN} HTTP/1.1\r\nHost: {management_host}\r\n"
              "Content-Type: application/json\r\nConnection: close\r\n"
              f"Content-Length: {content_length}\r\n")
    if encoding:
        header += "Content-Encoding: gzip\r\n"
    start = time.monotonic()
    sock = socket.create_connection((base.hostname, base.port), timeout=remaining(deadline))
    sent = 1
    try:
        sock.settimeout(remaining(deadline))
        sock.sendall((header + "\r\n").encode("ascii") + (b"\x1f" if encoding else b"{"))
        next_drip = time.monotonic() + drip_interval if drip_interval else deadline
        received = bytearray()
        while b"\r\n\r\n" not in received:
            wait = min(remaining(deadline), max(0, next_drip - time.monotonic()))
            readable, _, _ = select.select([sock], [], [], wait)
            if readable:
                sock.settimeout(remaining(deadline))
                part = sock.recv(8192 - len(received))
                require(bool(part), "Connection closed before a complete response header")
                received.extend(part)
                require(len(received) < 8192 or b"\r\n\r\n" in received,
                        "Response header exceeds the bounded size")
            elif drip_interval and time.monotonic() >= next_drip:
                require(sent + 1 < content_length, "Probe must not complete the request body")
                sock.settimeout(remaining(deadline))
                sock.sendall(b" ")
                sent += 1
                next_drip = time.monotonic() + drip_interval
        match = re.match(rb"HTTP/1\.[01] ([0-9]{3})(?: |\r\n)", received)
        require(match is not None, "Invalid HTTP response status")
        return {"httpStatus": int(match.group(1)), "elapsedSeconds": round(time.monotonic() - start, 3),
                "declaredBodyBytes": content_length, "sentBodyBytes": sent,
                "bodyIncompleteAtResponse": sent < content_length,
                "dripIntervalSeconds": drip_interval}
    finally:
        sock.close()


def run_cases(base_url, management_host):
    client = Client(base_url, management_host)
    accounts, rows = {}, []

    def case(name, seconds, action, needs_session=False):
        start = time.monotonic()
        row = {"id": name, "passed": False, "deadlineSeconds": seconds}
        if needs_session and len(accounts) != 2:
            row["error"] = "SESSION_SETUP_UNAVAILABLE"
        else:
            try:
                row.update(action(start + seconds) or {})
                row["passed"] = True
            except Exception as error:
                # Never emit exception text: transport errors may contain query tokens.
                row["errorType"] = type(error).__name__
                if type(error) is AssertionError:
                    row["error"] = str(error)  # Only this module's fixed, credential-free assertions.
        row["elapsedSeconds"] = round(time.monotonic() - start, 3)
        rows.append(row)

    def setup(deadline):
        for suffix in ("a", "b"):
            username, password = "ingress_" + uuid.uuid4().hex + suffix, "E2e!" + uuid.uuid4().hex
            client.success(client.request("POST", REGISTER, deadline=deadline,
                                          payload=dict(username=username, password=password, realName="Ingress E2E")))
            result = client.success(client.request("POST", LOGIN, deadline=deadline,
                                                    payload=dict(username=username, password=password)))
            require(isinstance(result, dict) and isinstance(result.get("token"), str),
                    "Login did not return a session token")
            accounts[suffix] = {"username": username, "token": result["token"]}
        return {"registeredAccounts": 2, "loginStatuses": [200, 200]}

    def check(account, deadline):
        require(client.success(client.request("GET", CHECK, deadline=deadline,
                                              account=account, query=account)) is True,
                "Verified session must remain valid")

    def matching(deadline):
        check(accounts["a"], deadline)
        return {"httpStatus": 200, "verifiedSession": True}

    def mismatch(deadline, field):
        forged = dict(accounts["a"])
        forged[field] = accounts["b"][field]
        status, body = client.request("DELETE", LOGOUT, deadline=deadline,
                                      account=accounts["a"], query=forged)
        require(status == 401 and isinstance(body, dict) and body.get("code") == "SESSION_ARGUMENT_MISMATCH",
                "Mismatched session arguments must be rejected at the Admin boundary")
        check(accounts["a"], deadline)
        check(accounts["b"], deadline)
        return {"httpStatus": status, "bothSessionsStillValid": True}

    def logout(deadline, name):
        account = accounts[name]
        client.success(client.request("DELETE", LOGOUT, deadline=deadline, account=account, query=account))
        status, body = client.request("GET", CHECK, deadline=deadline, account=account, query=account)
        require(status == 401 and isinstance(body, dict) and body.get("code") == "INVALID_SESSION",
                "A revoked token must be rejected before the controller")
        return {"logoutStatus": 200, "revokedTokenStatus": status}

    def upload(deadline, status, length, *, encoding=None, interval=None):
        # Leave three seconds inside the case budget for a normal recovery request.
        probe = incomplete_upload(base_url, management_host, content_length=length,
                                  encoding=encoding, drip_interval=interval, deadline=deadline - 3)
        require(probe["httpStatus"] == status,
                f"Expected incomplete-upload HTTP {status}, received {probe['httpStatus']}")
        require(probe["bodyIncompleteAtResponse"], "Rejection arrived after the body completed")
        if interval:
            require(4 <= probe["elapsedSeconds"] <= 8 and probe["sentBodyBytes"] >= 5,
                    "Slow upload must trigger the five-second body deadline while bytes keep arriving")
        else:
            require(probe["elapsedSeconds"] <= 3, "Headers must be rejected promptly without waiting for the body")
        client.success(client.request("GET", LOOKUP, deadline=deadline,
                                      query={"username": "ingress_recovery_" + uuid.uuid4().hex}))
        return {"probe": probe, "recoveryStatus": 200}

    case("AI01_register_and_login_two_accounts", 20, setup)
    case("AI02_matching_header_and_query_session", 10, matching, True)
    case("AI03_cross_username_logout_rejected", 10, lambda d: mismatch(d, "username"), True)
    case("AI04_cross_token_logout_rejected", 10, lambda d: mismatch(d, "token"), True)
    case("AI05_logout_revokes_original_token", 10, lambda d: logout(d, "a"), True)
    case("AI06_active_slow_upload_body_deadline", 11, lambda d: upload(d, 408, 1024, interval=0.4))
    case("AI07_oversized_body_rejected_before_upload", 6, lambda d: upload(d, 413, 256 * 1024 + 1))
    case("AI08_gzip_rejected_before_upload", 6, lambda d: upload(d, 415, 1024, encoding="gzip"))
    case("AI09_remaining_session_logout", 10, lambda d: logout(d, "b"), True)
    return {"suite": "admin-public-ingress", "passed": all(row["passed"] for row in rows),
            "case_count": len(rows), "cases": rows,
            "scope": {"publicApisixOnly": True, "newAccounts": True, "loadTest": False,
                      "agent": False, "credentialsInEvidence": False}}


def verify(state_path):
    state_path = Path(state_path).resolve()
    state = json.loads(state_path.read_text(encoding="utf-8"))
    folder = Path(state["folder"]).resolve()
    folder.relative_to((ROOT / ".work/e2e").resolve())
    require(state_path == folder / "state.json" and state.get("phase") == "READY"
            and state.get("apisix", "").startswith("shortlink-e2e-apisix-"),
            "A running isolated E2E supervisor state is required")
    result = run_cases(state["baseUrl"], state["managementHost"])
    (folder / "admin-ingress.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", type=Path)
    try:
        raise SystemExit(verify(parser.parse_args().state))
    except Exception as error:
        print(json.dumps({"suite": "admin-public-ingress", "passed": False,
                          "errorType": type(error).__name__}))
        raise SystemExit(1) from None
