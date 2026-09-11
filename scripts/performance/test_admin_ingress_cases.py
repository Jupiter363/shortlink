"""Offline verification of finite Admin ingress probes; no network or service starts."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("admin_ingress_cases", ROOT / "scripts/e2e/admin_ingress_cases.py")
ingress = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ingress)


class PartialUploadTests(unittest.TestCase):
    def probe(self, status, response_at, **kwargs):
        now = [0.0]
        connection = Mock()
        connection.recv.return_value = f"HTTP/1.1 {status} Rejected\r\nContent-Length: 0\r\n\r\n".encode()

        def select(read, _write, _error, timeout):
            now[0] = min(response_at, now[0] + timeout)
            return (read, [], []) if now[0] >= response_at else ([], [], [])

        with patch.object(ingress.time, "monotonic", side_effect=lambda: now[0]), \
             patch.object(ingress.socket, "create_connection", return_value=connection), \
             patch.object(ingress.select, "select", side_effect=select):
            result = ingress.incomplete_upload("http://127.0.0.1:19080", "admin.e2e.test",
                                               deadline=8, **kwargs)
        connection.close.assert_called_once()
        return result, connection

    def test_slow_upload_keeps_sending_bytes_and_observes_response_before_completing_body(self):
        result, conn = self.probe(408, 5, content_length=1024, drip_interval=0.4)
        self.assertEqual(result["httpStatus"], 408)
        self.assertEqual(result["elapsedSeconds"], 5)
        self.assertEqual(result["sentBodyBytes"], 13)
        self.assertTrue(result["bodyIncompleteAtResponse"])
        self.assertEqual(conn.sendall.call_count, 13)
        wire = b"".join(call.args[0] for call in conn.sendall.call_args_list)
        self.assertIn(b"Content-Length: 1024\r\n", wire)
        self.assertNotIn(b"token:", wire.lower())
        self.assertEqual(wire.partition(b"\r\n\r\n")[2], b"{" + b" " * 12)

    def test_oversize_and_gzip_send_only_headers_and_first_byte(self):
        for status, kwargs, body in (
                (413, {"content_length": 262145}, b"{"),
                (415, {"content_length": 1024, "encoding": "gzip"}, b"\x1f")):
            with self.subTest(status=status):
                result, conn = self.probe(status, 0.05, **kwargs)
                self.assertEqual(result["sentBodyBytes"], 1)
                wire = conn.sendall.call_args.args[0]
                self.assertEqual(wire.partition(b"\r\n\r\n")[2], body)
                self.assertEqual(b"Content-Encoding: gzip\r\n" in wire, status == 415)

    def test_total_deadline_and_transport_failure_always_close_socket(self):
        for mode in ("deadline", "transport"):
            conn = Mock()
            now = [0.0]

            def select(_read, _write, _error, timeout):
                now[0] += timeout
                return [], [], []

            if mode == "transport":
                conn.sendall.side_effect = OSError("synthetic failure")
            with self.subTest(mode=mode), \
                 patch.object(ingress.time, "monotonic", side_effect=lambda: now[0]), \
                 patch.object(ingress.socket, "create_connection", return_value=conn), \
                 patch.object(ingress.select, "select", side_effect=select), \
                 self.assertRaises((TimeoutError, OSError)):
                ingress.incomplete_upload("http://127.0.0.1:19080", "admin.e2e.test",
                                          content_length=1024, deadline=1)
            conn.close.assert_called_once()

    def test_external_or_credentialed_origins_and_header_injection_rejected(self):
        for base, host in (("https://127.0.0.1:19080", "admin.test"),
                           ("http://example.com:19080", "admin.test"),
                           ("http://secret@127.0.0.1:19080", "admin.test"),
                           ("http://127.0.0.1:19080", "admin.test\r\nInjected: value")):
            with self.subTest(base=base), self.assertRaises(ValueError):
                ingress.origin(base, host)


class SessionSuiteTests(unittest.TestCase):
    def test_suite_checks_both_sessions_after_each_rejected_logout_and_redacts_credentials(self):
        sessions, requests = {}, []
        tokens = ["secret-session-A-token", "secret-session-B-token"]

        def request(method, route, *, deadline, query=None, payload=None, account=None):
            self.assertGreater(deadline, 0)
            requests.append((method, route, query, account))
            data = None
            if route == ingress.LOGIN:
                token = tokens[len(sessions)]
                sessions[payload["username"]] = token
                data = {"token": token}
            elif route in (ingress.CHECK, ingress.LOGOUT):
                if sessions.get(account["username"]) != account["token"]:
                    return 401, {"code": "INVALID_SESSION"}
                if account != query:
                    return 401, {"code": "SESSION_ARGUMENT_MISMATCH"}
                if route == ingress.LOGOUT:
                    sessions.pop(account["username"])
                else:
                    data = True
            return 200, {"code": "0", "data": data}

        def upload(_base, _host, *, content_length, deadline, encoding=None, drip_interval=None):
            return {"httpStatus": 415 if encoding else 408 if drip_interval else 413,
                    "elapsedSeconds": 5 if drip_interval else 0.1, "declaredBodyBytes": content_length,
                    "sentBodyBytes": 13 if drip_interval else 1, "bodyIncompleteAtResponse": True}

        with patch.object(ingress.Client, "request", side_effect=request), \
             patch.object(ingress, "incomplete_upload", side_effect=upload):
            result = ingress.run_cases("http://127.0.0.1:19080", "admin.test")
        self.assertTrue(result["passed"], result)
        self.assertEqual(result["case_count"], 9)
        self.assertEqual(len(sessions), 0)
        for index, (_, route, query, account) in enumerate(requests):
            if route == ingress.LOGOUT and query != account:
                followup = requests[index + 1:index + 3]
                self.assertEqual([row[1] for row in followup], [ingress.CHECK, ingress.CHECK])
                self.assertNotEqual(followup[0][3]["username"], followup[1][3]["username"])
        serialized = json.dumps(result)
        self.assertNotIn("secret-session", serialized)
        self.assertNotIn("E2e!", serialized)
        self.assertEqual(sum(route == ingress.LOOKUP for _, route, _, _ in requests), 3)

    def test_setup_failure_does_not_skip_independent_body_probes_or_leak_transport_detail(self):
        with patch.object(ingress.Client, "request", side_effect=OSError("secret-token-and-password")), \
             patch.object(ingress, "incomplete_upload", side_effect=TimeoutError("secret-token")) as body:
            result = ingress.run_cases("http://127.0.0.1:19080", "admin.test")
        self.assertFalse(result["passed"])
        self.assertEqual(body.call_count, 3)
        self.assertNotIn("secret-token", json.dumps(result))

    def test_http_failure_closes_connection_and_cancels_deadline_timer(self):
        conn, timer = Mock(), Mock()
        conn.request.side_effect = OSError("synthetic")
        with patch.object(ingress.http.client, "HTTPConnection", return_value=conn), \
             patch.object(ingress.threading, "Timer", return_value=timer), \
             self.assertRaises(OSError):
            ingress.Client("http://127.0.0.1:19080", "admin.test").request(
                "POST", ingress.LOGIN, deadline=ingress.time.monotonic() + 10, payload={})
        conn.close.assert_called_once()
        timer.cancel.assert_called_once()
        timer.join.assert_called_once_with(timeout=1)


if __name__ == "__main__":
    unittest.main()
