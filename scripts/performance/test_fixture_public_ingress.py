"""Public-session fixture regression; all HTTP/SQL are replaced with local mocks."""

import json
import os
from pathlib import Path
import stat
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import prepare_fixtures as fixtures


PREFIX = "/api/short-link/admin/v1"
TOKEN = "fixture-session-" + "a" * 32
USERNAME = "perf_" + "b" * 32 + "_0"


class PublicFixtureIngressTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        folder = Path(self.directory.name)
        # Only __init__'s real WSL/schema/secret-file discovery is bypassed.
        # request/register/seed/run and private/public serialization execute normally.
        self.preparer = fixtures.FixturePreparer.__new__(fixtures.FixturePreparer)
        p = self.preparer
        p.state = {"managementHost": "admin.perf.test", "redirectHost": "s.perf.test",
                   "resourceProfile": {"metadataWorkers": 8}, "metadataPollMillis": 1000}
        p.run_id = "mock-fixture-run"
        p.database = "shortlink_perf_" + "a" * 10
        p.folder = folder
        p.fixture_path = folder / "fixture.json"
        p.summary_path = folder / "fixture-summary.json"
        p.internal_token = "legacy-private-" + "c" * 32
        p.seed_id = "b" * 32
        p.link_count, p.account_count, p.mutation_count = 1, 1, 2
        p.http_attempts = p.http_successes = p.sql_reads = p.batch_count = p.created_count = 0
        p.accounts, p.links, p.mutations, p.drain_observations = [], [], [], []
        p.all_ids = set()
        p.start, p.started_at = time.monotonic(), "2026-09-13T00:00:00Z"
        p.sql = Mock(return_value=[["42", "1"]])
        p.hydrate_routes = Mock()
        p.drain = Mock()
        self.http = patch.object(fixtures.http.client, "HTTPConnection").start()
        self.addCleanup(patch.stopall)
        self.reply(None)

    def reply(self, data, status=200):
        response = self.http.return_value.getresponse.return_value
        response.status = status
        response.read.return_value = json.dumps({"code": "0", "data": data}).encode()

    @staticmethod
    def account():
        return {"username": USERNAME, "tenantId": "42", "authVersion": 1, "gid": "g1",
                "session": {"username": USERNAME, "token": TOKEN}}

    def assert_public_call(self, path):
        self.http.assert_called_with("127.0.0.1", 19080, timeout=60)
        args, kwargs = self.http.return_value.request.call_args
        self.assertEqual(("POST", path), args)
        headers = kwargs["headers"]
        self.assertEqual("admin.perf.test", headers["Host"])
        self.assertEqual(USERNAME, headers["username"])
        self.assertEqual(TOKEN, headers["token"])
        self.assertFalse(any(k.lower().startswith(("x-shortlink-", "x-agent-"))
                             or k.lower() == "x-internal-token" for k in headers))
        self.http.return_value.close.assert_called_once()

    def test_single_create_uses_public_session(self):
        body = {"requestId": "unique-single", "gid": "g1"}
        self.preparer.request("POST", PREFIX + "/create", body, account=self.account())
        self.assert_public_call(PREFIX + "/create")
        self.assertEqual(body, json.loads(self.http.return_value.request.call_args.kwargs["body"]))

    def test_batch_create_uses_public_session(self):
        body = {"requestId": "unique-batch", "originUrls": ["https://shortlink-perf.local/1"]}
        self.preparer.request("POST", PREFIX + "/create/batch", body, account=self.account())
        self.assert_public_call(PREFIX + "/create/batch")
        self.assertEqual(body, json.loads(self.http.return_value.request.call_args.kwargs["body"]))

    def test_protected_routes_reject_missing_session_before_http(self):
        for method, suffix in (("POST", "/create"), ("POST", "/create/batch"),
                               ("GET", "/user/initialization")):
            with self.subTest(path=suffix):
                with self.assertRaisesRegex(fixtures.PreparationError, "login session"):
                    self.preparer.request(method, PREFIX + suffix)
        self.http.assert_not_called()

    def test_legacy_identity_without_session_is_rejected(self):
        account = self.account()
        del account["session"]
        with self.assertRaisesRegex(fixtures.PreparationError, "login session"):
            self.preparer.request("POST", PREFIX + "/create", {}, account=account)
        self.http.assert_not_called()

    def test_account_mismatch_and_ambiguous_session_are_rejected(self):
        account = self.account()
        account["session"]["username"] = "different_user"
        with self.assertRaisesRegex(fixtures.PreparationError, "does not match"):
            self.preparer.request("POST", PREFIX + "/create", {}, account=account)
        with self.assertRaisesRegex(fixtures.PreparationError, "ambiguous"):
            self.preparer.request("POST", PREFIX + "/create", {},
                                  account=self.account(), session=self.account()["session"])
        self.http.assert_not_called()

    def test_malformed_session_cannot_become_http_headers(self):
        for session in (None, {}, {"username": USERNAME, "token": "short"},
                        {"username": USERNAME, "token": TOKEN + "\r\nInjected: true"},
                        {"username": USERNAME + "\n", "token": TOKEN},
                        {"username": USERNAME, "token": "a" * 257}):
            with self.subTest(session_type=type(session).__name__):
                with self.assertRaisesRegex(fixtures.PreparationError, "login session"):
                    self.preparer.request("POST", PREFIX + "/create/batch", {}, session=session)
        self.http.assert_not_called()

    def test_register_keeps_real_session_and_initialization_uses_it(self):
        response = self.http.return_value.getresponse.return_value
        response.read.side_effect = [json.dumps({"code": "0", "data": x}).encode()
                                    for x in (None, {"token": TOKEN}, {"state": "READY", "groupId": "g1"})]
        account = self.preparer.register_account(0)
        self.assertEqual({"username": USERNAME, "token": TOKEN}, account["session"])
        calls = self.http.return_value.request.call_args_list
        self.assertEqual([PREFIX + "/user", PREFIX + "/user/login", PREFIX + "/user/initialization"],
                         [call.args[1] for call in calls])
        for call in calls[:2]:
            self.assertNotIn("token", call.kwargs["headers"])
        self.assertEqual(TOKEN, calls[2].kwargs["headers"]["token"])
        for call in self.http.call_args_list:
            self.assertEqual(("127.0.0.1", 19080), call.args)

    def test_unknown_route_and_session_on_anonymous_route_fail_closed(self):
        for method, path in (("GET", PREFIX + "/create"), ("POST", PREFIX + "/create/other"),
                             ("POST", "/api/short-link/v1/create")):
            with self.assertRaisesRegex(fixtures.PreparationError, "allowlisted"):
                self.preparer.request(method, path, account=self.account())
        with self.assertRaisesRegex(fixtures.PreparationError, "Anonymous"):
            self.preparer.request("POST", PREFIX + "/user/login", session=self.account()["session"])
        self.http.assert_not_called()

    def test_failure_summary_does_not_include_response_or_session(self):
        response = self.http.return_value.getresponse.return_value
        response.status = 403
        response.read.return_value = json.dumps({"code": "HTTP_403", "token": TOKEN}).encode()
        with self.assertRaises(fixtures.PreparationError) as rejected:
            self.preparer.request("POST", PREFIX + "/create", {}, account=self.account())
        self.preparer.accounts = [self.account()]
        self.preparer.write_summary("FAILED", error=str(rejected.exception))
        public = self.preparer.summary_path.read_text()
        self.assertNotIn(TOKEN, public)
        self.assertNotIn(USERNAME, public)
        self.assertNotIn(self.preparer.internal_token, public)
        self.assertEqual(403, json.loads(public)["lastHttpFailure"]["httpStatus"])

    @unittest.skipUnless(os.name == "posix", "Real mode 0600 and O_NOFOLLOW require POSIX")
    def test_run_saves_session_only_in_private_fixture_and_preserves_seed_ids(self):
        p = self.preparer
        p.sql.side_effect = [[["0", "0"]], [["42", "1"]]]
        next_id = 0

        def response_data():
            nonlocal next_id
            call = self.http.return_value.request.call_args
            path, body = call.args[1], call.kwargs["body"]
            payload = json.loads(body) if body else None
            if path == PREFIX + "/user":
                data = None
            elif path == PREFIX + "/user/login":
                data = {"token": TOKEN}
            elif path == PREFIX + "/user/initialization":
                data = {"state": "READY", "groupId": "g1"}
            else:
                self.assertEqual(TOKEN, call.kwargs["headers"]["token"])
                self.assertTrue(payload["requestId"].startswith("perf-" + p.seed_id + "-"))
                rows = []
                for origin in payload.get("originUrls", [payload.get("originUrl")]):
                    next_id += 1
                    rows.append({"linkId": next_id, "originUrl": origin,
                                 "fullShortUrl": "https://s.perf.test/" + str(next_id).zfill(9)})
                data = ({"state": "SUCCEEDED", "jobId": None, "total": len(rows), "baseLinkInfos": rows}
                        if path.endswith("/batch") else rows[0])
            return json.dumps({"code": "0", "data": data}).encode()

        self.http.return_value.getresponse.return_value.read.side_effect = lambda limit: response_data()
        result = p.run()
        self.assertEqual("READY", result["status"])
        fixture = json.loads(p.fixture_path.read_text())
        self.assertEqual(TOKEN, fixture["accounts"][0]["session"]["token"])
        self.assertEqual(0o600, stat.S_IMODE(p.fixture_path.stat().st_mode))
        public = p.summary_path.read_text()
        for secret in (TOKEN, USERNAME, p.internal_token):
            self.assertNotIn(secret, public)
        self.assertEqual(4, result["createdLinks"])
        self.assertEqual(1, result["synchronousBatchRequests"])
        self.assertEqual(4, len(p.all_ids))
        self.assertEqual(3, p.drain.call_count)
        for call in self.http.return_value.request.call_args_list:
            self.assertFalse(any(k.lower().startswith("x-shortlink-") or k.lower() == "x-internal-token"
                                 for k in call.kwargs["headers"]))
        with self.assertRaisesRegex(fixtures.PreparationError, "already exists"):
            p.run()


if __name__ == "__main__":
    unittest.main()
