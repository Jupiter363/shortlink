"""Offline APISIX/Admin boundary and fixture contracts; no socket or process starts."""
import copy
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import yaml

ROOT = Path(__file__).resolve().parents[2]


def load(relative, name):
    path = ROOT / relative
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    with patch.object(sys, "path", [str(path.parent), *sys.path]):
        spec.loader.exec_module(module)
    return module


edge = load("scripts/e2e/verify_gateway_e2e.py", "single_gateway_e2e")
security = load("scripts/e2e/gateway_security_cases.py", "single_gateway_security")
component = load("scripts/integration/production_jar_components.py", "single_gateway_component")
path_probe = load("scripts/performance/verify_edge_path.py", "single_gateway_path")
summary = load("scripts/performance/summarize.py", "single_gateway_summary")
e2e = load("scripts/e2e/run_create_redirect_e2e.py", "single_gateway_supervisor")
bootstrap = load("deploy/apisix/bootstrap-etcd.py", "single_gateway_bootstrap")


class AgentChatRouteTests(unittest.TestCase):
    def setUp(self):
        self.manifest = yaml.safe_load((ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8"))
        self.routes = {route["id"]: route for route in self.manifest["routes"]}

    def test_chat_has_an_exact_protected_route_and_other_timeout_budgets_are_preserved(self):
        self.assertEqual(set(self.routes), {"shortlink-management", "shortlink-agent-chat", "shortlink-redirect"})
        management, chat, redirect = (self.routes[name] for name in (
            "shortlink-management", "shortlink-agent-chat", "shortlink-redirect"))
        self.assertEqual(chat["uri"], "/api/short-link/admin/v1/agent/chat")
        self.assertNotIn("uris", chat)
        self.assertGreater(chat["priority"], management["priority"])
        self.assertEqual(chat["hosts"], management["hosts"])
        self.assertEqual(chat["plugins"], management["plugins"])
        self.assertEqual(chat["plugins"]["shortlink-boundary"], {"mode": "management"})
        self.assertEqual(chat["plugins"]["proxy-control"], {"request_buffering": False})
        self.assertIsNot(chat["plugins"], management["plugins"])
        expected = copy.deepcopy(management["upstream"])
        expected["timeout"]["read"] = 50
        self.assertEqual(chat["upstream"], expected)
        self.assertEqual(management["upstream"]["timeout"], {"connect": 1, "send": 3, "read": 10})
        self.assertEqual(redirect["upstream"]["timeout"], {"connect": 0.2, "send": 1, "read": 1})
        self.assertEqual([route["upstream"]["retries"] for route in self.routes.values()], [0, 0, 0])
        bootstrap.validate_limiter_keys(self.manifest)
        bootstrap.validate_agent_chat_route(self.manifest)

    def test_import_rejects_chat_scope_protection_and_upstream_drift(self):
        for name, mutate in (
                ("wildcard-path", lambda chat: chat.update(uri="/api/short-link/admin/v1/*")),
                ("extra-paths", lambda chat: chat.update(uris=["/*"])),
                ("host", lambda chat: chat.update(hosts=["wrong.example"])),
                ("missing-host", lambda chat: chat.pop("hosts")),
                ("priority", lambda chat: chat.update(priority=100)),
                ("boundary", lambda chat: chat["plugins"].pop("shortlink-boundary")),
                ("buffering", lambda chat: chat["plugins"]["proxy-control"].update(request_buffering=True)),
                ("limiter-budget", lambda chat: chat["plugins"]["limit-req"].update(rate=200)),
                ("read-timeout", lambda chat: chat["upstream"]["timeout"].update(read=10)),
                ("retries", lambda chat: chat["upstream"].update(retries=1)),
                ("upstream", lambda chat: chat["upstream"]["nodes"][0].update(host="wrong.example"))):
            with self.subTest(name=name):
                candidate = copy.deepcopy(self.manifest)
                mutate(next(route for route in candidate["routes"] if route["id"] == "shortlink-agent-chat"))
                with self.assertRaises(ValueError):
                    bootstrap.validate_agent_chat_route(candidate)

    def test_import_requires_the_chat_route_and_both_of_its_limiter_keys(self):
        for plugin in (None, "limit-req", "limit-conn"):
            with self.subTest(plugin=plugin):
                candidate = copy.deepcopy(self.manifest)
                if plugin is None:
                    candidate["routes"] = [route for route in candidate["routes"] if route["id"] != "shortlink-agent-chat"]
                else:
                    chat = next(route for route in candidate["routes"] if route["id"] == "shortlink-agent-chat")
                    chat["plugins"].pop(plugin)
                with self.assertRaises(ValueError):
                    bootstrap.validate_limiter_keys(candidate)


class LimiterFixtureTests(unittest.TestCase):
    def test_each_probe_changes_only_the_selected_limiter_and_preserves_admin_upstream(self):
        original = (ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
        baseline = yaml.safe_load(original)
        for route_id, plugin, values in (
                ("shortlink-management", "limit-req", {"rate": 2, "burst": 0}),
                ("shortlink-redirect", "limit-req", {"rate": 2, "burst": 0}),
                ("shortlink-management", "limit-conn", {"conn": 2, "burst": 0})):
            with self.subTest(route=route_id, plugin=plugin):
                # Reordered keys and normalized YAML deliberately differ from production formatting.
                rendered = edge.limit_fixture(yaml.safe_dump(baseline, sort_keys=True), route_id, plugin, **values)
                actual = yaml.safe_load(rendered)
                expected = copy.deepcopy(baseline)
                next(route for route in expected["routes"] if route["id"] == route_id)["plugins"][plugin].update(values)
                self.assertEqual(actual, expected)
                management = next(route for route in actual["routes"] if route["id"] == "shortlink-management")
                self.assertEqual(management["upstream"]["nodes"][0]["host"], "${{ADMIN_UPSTREAM_HOST}}")
                self.assertEqual(management["upstream"]["nodes"][0]["port"], 8002)
                self.assertTrue(rendered.endswith("#END\n"))

    def test_missing_or_duplicate_route_fails_instead_of_rewriting_another_route(self):
        route = {"id": "shortlink-management", "plugins": {"limit-req": {"rate": 100}}}
        for routes in ([], [route, route]):
            with self.subTest(routes=len(routes)), self.assertRaises(ValueError):
                edge.limit_fixture(yaml.safe_dump({"routes": routes}), "shortlink-management", "limit-req", rate=2, burst=0)

    def test_fixture_cannot_rewrite_upstream_or_plugin_identity(self):
        with self.assertRaises(ValueError):
            edge.limit_fixture("routes: []", "shortlink-management", "proxy-rewrite", rate=2, burst=0)
        with self.assertRaises(ValueError):
            edge.limit_fixture("routes: []", "shortlink-management", "limit-req", rate=2, key="remote_addr")


class AdminBoundaryTests(unittest.TestCase):
    def test_admin_admission_still_holds_64_bodies_from_a_trusted_peer(self):
        with patch.object(security, "_admission", return_value={"passed": True}) as probe:
            security.probe_admin_admission("192.0.2.1:8002", "admin.it.test")
        probe.assert_called_once_with("http://192.0.2.1:8002", "admin.it.test", 64, True,
                                      "GL03_admin_64_slot_admission_recovers", "admin_admission")

    def test_parent_direct_probes_target_admin_and_redirect_with_forged_headers(self):
        context = dict(runId="offline", apisixPid=123, apisixIp="192.0.2.2", networkGateway="192.0.2.1",
                       managementHost="admin.it.test", redirectHost="s.it.test", shortUri="abcdefghi")
        denied = dict(status=403, headers=[], socketSource="127.0.0.1", elapsedMs=0)
        child = SimpleNamespace(returncode=0, stdout=json.dumps({"cases": [{"passed": True}] * 10}))
        with patch.object(path_probe, "_load", return_value=context), \
             patch.object(path_probe, "_http", return_value=denied) as http, \
             patch.object(path_probe.subprocess, "run", return_value=child):
            result = path_probe.verify(Path("unused-state.json"))
        self.assertTrue(result["passed"])
        self.assertEqual([call.args[1] for call in http.call_args_list], [8002, 8003])
        self.assertEqual(result["cases"][0]["id"], "EP01_untrusted_direct_admin")
        self.assertEqual(http.call_args_list[0].args[3]["X-Forwarded-For"], context["apisixIp"])


class ReadinessAdapterTests(unittest.TestCase):
    def request(self, path, token):
        handler = object.__new__(component.ReadinessHandler)
        handler.path, handler.headers = path, {"X-Internal-Token": token}
        handler.send_response, handler.send_header, handler.end_headers = Mock(), Mock(), Mock()
        handler.wfile = io.BytesIO()
        handler.do_GET()
        return handler.send_response.call_args.args[0], json.loads(handler.wfile.getvalue())

    def test_only_authenticated_readiness_is_stubbed(self):
        self.assertEqual(self.request(component.READY_PATH, component.TOKEN), (200, {"ready": True}))
        self.assertEqual(self.request(component.READY_PATH, "forged")[0], 403)
        self.assertEqual(self.request("/internal/command/groups", component.TOKEN)[0], 503)

    def test_adapter_closes_after_java_failure_without_opening_real_sockets(self):
        server = Mock(server_address=("127.0.0.1", 28001))
        worker = Mock()
        with patch.object(component, "ThreadingHTTPServer", return_value=server), \
             patch.object(component.threading, "Thread", return_value=worker):
            with self.assertRaisesRegex(RuntimeError, "synthetic"):
                with component.command_adapter(None) as (url, mode):
                    self.assertEqual(url, "http://127.0.0.1:28001")
                    self.assertEqual(mode, "loopback-readiness-only-stub")
                    raise RuntimeError("synthetic")
        server.shutdown.assert_called_once()
        server.server_close.assert_called_once()
        worker.join.assert_called_once_with(timeout=5)

    def test_thread_start_failure_does_not_shutdown_an_unstarted_server(self):
        server = Mock(server_address=("127.0.0.1", 28001))
        worker = Mock()
        worker.start.side_effect = RuntimeError("thread unavailable")
        with patch.object(component, "ThreadingHTTPServer", return_value=server), \
             patch.object(component.threading, "Thread", return_value=worker), self.assertRaises(RuntimeError):
            with component.command_adapter(None):
                self.fail("Unstarted adapter must not yield")
        server.shutdown.assert_not_called()
        server.server_close.assert_called_once()


class HistoricalEvidenceTests(unittest.TestCase):
    def test_current_snapshot_has_three_jvms_but_existing_gateway_evidence_is_preserved(self):
        current = summary.compact({})
        self.assertEqual(set(current["jvms"]), {"admin", "shortlink-command", "shortlink-redirect"})
        old = summary.compact({"processes": {"gateway": {"status": "AVAILABLE", "data": {"pid": 42}}}})
        self.assertEqual(old["jvms"]["gateway"]["pid"], 42)


class IsolatedSupervisorTests(unittest.TestCase):
    MYSQL, REDIS, KAFKA, NETWORK = "single-it-mysql", "single-it-redis", "single-it-kafka", "single-it-net"

    @staticmethod
    def dependency(name, private_port, public_port):
        return {"Id": "id-" + name, "Name": "/" + name, "State": {"Running": True},
                "NetworkSettings": {"Ports": {str(private_port) + "/tcp": [
                    {"HostIp": "127.0.0.1", "HostPort": str(public_port)}]}}}

    def arguments(self):
        return e2e.parse_args(["--allow-test-database", "--max-runtime-seconds", "0",
                              "--mysql-container", self.MYSQL, "--mysql-port", "23306",
                              "--redis-container", self.REDIS, "--redis-port", "26379",
                              "--kafka-container", self.KAFKA, "--kafka-bootstrap", "localhost:29092",
                              "--kafka-host", self.KAFKA, "--network", self.NETWORK,
                              "--object-endpoint", "http://127.0.0.1:29000"])

    def test_named_container_must_publish_the_exact_private_port_on_explicit_loopback(self):
        good = self.dependency(self.REDIS, 6379, 26379)
        with patch.object(e2e, "command", return_value=SimpleNamespace(stdout=json.dumps([good]))) as command:
            observed = e2e.verify_published_port(self.REDIS, 6379, 26379)
        self.assertEqual(observed["containerId"], "id-" + self.REDIS)
        self.assertEqual((observed["hostPort"], observed["containerPort"]), (26379, 6379))
        command.assert_called_once_with(["docker", "inspect", "--type", "container", self.REDIS])
        invalid = []
        for field, value in (("HostIp", "0.0.0.0"), ("HostIp", "::1"), ("HostPort", "16379")):
            row = copy.deepcopy(good)
            row["NetworkSettings"]["Ports"]["6379/tcp"][0][field] = value
            invalid.append(row)
        missing = copy.deepcopy(good)
        missing["NetworkSettings"]["Ports"]["6379/tcp"] = None
        invalid.append(missing)
        stopped = copy.deepcopy(good)
        stopped["State"]["Running"] = False
        invalid.append(stopped)
        wrong_name = copy.deepcopy(good)
        wrong_name["Name"] = "/other-redis"
        invalid.append(wrong_name)
        for row in invalid:
            with self.subTest(row=row), patch.object(e2e, "command", return_value=SimpleNamespace(stdout=json.dumps([row]))):
                with self.assertRaises(ValueError):
                    e2e.verify_published_port(self.REDIS, 6379, 26379)

    def test_mismatched_container_and_port_fail_before_schema_database_selection_or_processes(self):
        args = self.arguments()
        for mismatch in ("mysql", "redis"):
            mysql = self.dependency(self.MYSQL, 3306, 13306 if mismatch == "mysql" else 23306)
            redis = self.dependency(self.REDIS, 6379, 16379 if mismatch == "redis" else 26379)
            responses = [SimpleNamespace(stdout=json.dumps([row])) for row in (mysql, redis)]
            with self.subTest(mismatch=mismatch), contextlib.ExitStack() as stack:
                stack.enter_context(patch.object(e2e.sys, "platform", "linux"))
                command = stack.enter_context(patch.object(e2e, "command", side_effect=responses))
                mutations = [stack.enter_context(patch.object(Path, "mkdir")),
                             stack.enter_context(patch.object(e2e, "sql")),
                             stack.enter_context(patch.object(e2e.subprocess, "Popen")),
                             stack.enter_context(patch.object(e2e.socket, "socket"))]
                with self.assertRaisesRegex(ValueError, "Dependency must publish"):
                    e2e.serve(args)
                for mutation in mutations:
                    mutation.assert_not_called()
                self.assertTrue(all(call.args[0][:4] == ["docker", "inspect", "--type", "container"]
                                    for call in command.call_args_list))

    def simulated_serve(self, cleanup_failure=False):
        args = self.arguments()
        manifest = (ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
        states, launches, commands = [], [], []

        def run(argv, **unused):
            commands.append(argv)
            stdout, returncode = "", 0
            if argv[:4] == ["docker", "inspect", "--type", "container"]:
                row = self.dependency(self.MYSQL, 3306, 23306) if argv[-1] == self.MYSQL else self.dependency(self.REDIS, 6379, 26379)
                stdout = json.dumps([row])
            elif argv[:2] == ["docker", "inspect"]:
                stdout = json.dumps([{"State": {"Pid": 123}, "NetworkSettings": {"Networks": {
                    self.NETWORK: {"IPAddress": "10.242.91.3", "Gateway": "10.242.91.1"}}}}])
            elif argv[-1] == "DBSIZE":
                stdout = "0"
            elif argv[:2] in (["docker", "rm"], ["docker", "ps"]) and cleanup_failure:
                returncode = 1
            return SimpleNamespace(returncode=returncode, stdout=stdout, stderr="daemon unavailable" if returncode else "")

        def launch(argv, **kwargs):
            launches.append((argv, kwargs["env"]))
            proc = Mock(pid=2000 + len(launches))
            proc.poll.return_value = None
            return proc

        probe = Mock()
        probe.__enter__ = Mock(return_value=probe)
        probe.__exit__ = Mock(return_value=False)
        probe.connect_ex.return_value = 1
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(e2e.sys, "platform", "linux"))
            stack.enter_context(patch.object(e2e, "command", side_effect=run))
            stack.enter_context(patch.object(e2e, "sql", return_value=""))
            stack.enter_context(patch.object(e2e, "request_http", return_value=(200, {}, b'{"status":"UP"}')))
            stack.enter_context(patch.object(e2e, "write_json", side_effect=lambda path, value: states.append(copy.deepcopy(value)) if path.name == "state.json" else None))
            stack.enter_context(patch.object(Path, "read_text", side_effect=lambda *a, **k: manifest))
            stack.enter_context(patch.object(Path, "read_bytes", return_value=b"mock jar"))
            stack.enter_context(patch.object(Path, "write_text"))
            stack.enter_context(patch.object(Path, "mkdir"))
            stack.enter_context(patch.object(Path, "open", side_effect=lambda *a, **k: io.BytesIO()))
            stack.enter_context(patch.object(e2e.os, "chmod"))
            stack.enter_context(patch.object(e2e.subprocess, "Popen", side_effect=launch))
            stack.enter_context(patch.object(e2e.socket, "socket", return_value=probe))
            stack.enter_context(patch.object(e2e.time, "sleep", side_effect=AssertionError("Offline tests must not wait")))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            if cleanup_failure:
                with self.assertRaisesRegex(RuntimeError, "not fully stopped"):
                    e2e.serve(args)
            else:
                e2e.serve(args)
        return states, launches, commands

    def test_explicit_fixture_endpoints_reach_every_java_process_and_evidence(self):
        states, launches, commands = self.simulated_serve()
        ready = next(state for state in states if state["phase"] == "READY")
        self.assertEqual((ready["mysqlContainer"], ready["mysqlPort"]), (self.MYSQL, 23306))
        self.assertEqual((ready["redisContainer"], ready["redisPort"]), (self.REDIS, 26379))
        self.assertEqual(ready["dependencyBindings"]["redis"]["containerId"], "id-" + self.REDIS)
        self.assertEqual(ready["kafkaBootstrap"], "localhost:29092")
        self.assertEqual(ready["kafkaContainer"], self.KAFKA)
        self.assertEqual(len(launches), 3)
        for argv, env in launches:
            self.assertIn("--spring.data.redis.database=8", argv)
            self.assertEqual(env["REDIS_PORT"], "26379")
            self.assertIn("127.0.0.1:23306/", env["BUSINESS_DB_URL"])
            self.assertEqual(env["KAFKA_BOOTSTRAP_SERVERS"], "localhost:29092")
        self.assertEqual(states[-1]["phase"], "STOPPED")
        self.assertTrue(states[-1]["servicesStopped"])
        self.assertTrue(states[-1]["apisixCleanup"]["absenceVerified"])
        self.assertEqual([argv for argv in commands if argv[-1] == "DBSIZE"],
                         [["docker", "exec", self.REDIS, "redis-cli", "-n", "8", "DBSIZE"]])

    def test_failed_removal_cannot_publish_a_successful_stop(self):
        states, unused_launches, unused_commands = self.simulated_serve(cleanup_failure=True)
        self.assertEqual(states[-1]["phase"], "CLEANUP_FAILED")
        self.assertFalse(states[-1]["servicesStopped"])
        self.assertTrue(states[-1]["cleanupErrors"])
        self.assertNotIn("STOPPED", [state["phase"] for state in states])

    def test_successful_rm_with_a_remaining_container_is_also_rejected(self):
        outputs = [SimpleNamespace(returncode=0, stdout="removed", stderr=""),
                   SimpleNamespace(returncode=0, stdout="container-still-present", stderr="")]
        with patch.object(e2e, "command", side_effect=outputs), self.assertRaisesRegex(RuntimeError, "not verified"):
            e2e.remove_owned_apisix("shortlink-e2e-apisix-owned")


if __name__ == "__main__":
    unittest.main()
