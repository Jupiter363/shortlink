"""Offline entrypoint checks in a separate repository root, with every runtime adapter mocked."""
import contextlib
import copy
import hashlib
import http.server as http_server
import importlib.util
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
JARS = {
    "shortlink-command": ("shortlink-command-1.0-SNAPSHOT.jar", 8001, 8101),
    "admin": ("shortlink-admin.jar", 8002, 8102),
    "shortlink-redirect": ("shortlink-redirect-1.0-SNAPSHOT.jar", 8003, 8103),
}


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeProcess:
    def __init__(self, pid):
        self.pid, self.returncode, self.stopped = pid, None, False

    def poll(self):
        return self.returncode

    def terminate(self):
        self.stopped, self.returncode = True, 0

    def kill(self):
        self.terminate()

    def wait(self, timeout=None):
        return self.returncode


class RepositoryJarPathsTests(unittest.TestCase):
    def setUp(self):
        self.evidence = ROOT / ".work/repository-layout-execution-20260910"
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="scripts-migration-test-", dir=self.evidence)
        self.candidate = Path(self.temporary.name).resolve()
        self.assertNotEqual(self.candidate, ROOT)
        for relative in (
            "scripts/performance/supervisor.py", "scripts/performance/kafka_probe_profile.py",
            "scripts/e2e/run_create_redirect_e2e.py", "scripts/integration/production_jar_components.py",
            "scripts/integration/component_adapters.py", "deploy/apisix/apisix.yaml",
            "deploy/apisix/config.yaml",
        ):
            target = self.candidate / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        ddl = self.candidate / "deploy/mysql/001-business-schema.sql"
        ddl.parent.mkdir(parents=True)
        ddl.write_text("-- offline DDL fixture, SQL adapter is mocked\n", encoding="utf-8")
        (ddl.parent / "004-route-membership.sql").write_text(
            "-- offline membership DDL fixture, SQL adapter is mocked\n", encoding="utf-8")
        self.bodies = {}
        for module, (filename, _, _) in JARS.items():
            target = self.candidate / "services" / module / "target" / filename
            target.parent.mkdir(parents=True)
            self.bodies[module] = (self.candidate.name + ":" + module).encode()
            target.write_bytes(self.bodies[module])
            self.assertFalse((self.candidate / module / "target").exists())
        self.launches, self.processes, self.states, self.jar_reads, self.commands = [], [], [], [], []

    def tearDown(self):
        # TemporaryDirectory cleanup is confined to this newly created test root.
        self.assertEqual(self.candidate.parent, self.evidence.resolve())
        self.assertTrue(self.candidate.name.startswith("scripts-migration-test-"))
        self.temporary.cleanup()

    def popen(self, argv, **kwargs):
        self.launches.append((list(argv), copy.deepcopy(kwargs["env"])))
        process = FakeProcess(20000 + len(self.launches))
        self.processes.append(process)
        return process

    def three_service_entry(self, performance, legacy_only=False, extra_args=()):
        relative = "scripts/performance/supervisor.py" if performance else "scripts/e2e/run_create_redirect_e2e.py"
        with patch.object(sys, "path", [str(self.candidate / "scripts/performance"), *sys.path]):
            module = load(self.candidate / relative, "migrated_supervisor" if performance else "migrated_e2e")
        self.assertEqual(module.ROOT, self.candidate)
        self.assertEqual(module.JARS, JARS)
        if legacy_only:
            for name, (filename, _, _) in JARS.items():
                old = self.candidate / name / "target" / filename
                old.parent.mkdir(parents=True)
                old.write_bytes(b"legacy artifact must not be used")
                (self.candidate / "services" / name / "target" / filename).unlink()
        args = (module.parse_args(["--allow-test-database", "--max-runtime-seconds", "0"])
                if performance else module.parse_args(["--allow-test-database", "--max-runtime-seconds", "0", *extra_args]))
        original_mkdir, original_bytes, original_write = Path.mkdir, Path.read_bytes, module.write_json

        def mkdir(path, *args, **kwargs):
            if str(path).replace("\\", "/").startswith("/var/lib/shortlink-perf"):
                return None
            path.resolve().relative_to(self.candidate)
            return original_mkdir(path, *args, **kwargs)

        def read_bytes(path):
            if path.suffix == ".jar":
                self.jar_reads.append(path)
            return original_bytes(path)

        def write(path, value):
            if path.name == "observer-secret.json":
                return None
            path.resolve().relative_to(self.candidate)
            if path.name == "state.json":
                self.states.append(copy.deepcopy(value))
            return original_write(path, value)

        def command(argv, **kwargs):
            self.commands.append(list(argv))
            if argv[:2] == ["docker", "run"]:
                self.assertIn("ADMIN_UPSTREAM_HOST=host.docker.internal", argv)
                self.assertFalse(any("GATEWAY_UPSTREAM_HOST" in value for value in argv))
            if argv[:4] == ["docker", "inspect", "--type", "container"]:
                name = argv[-1]
                if name == args.mysql_container:
                    internal_port, published_port = 3306, args.mysql_port
                else:
                    self.assertEqual(name, args.redis_container)
                    internal_port, published_port = 6379, args.redis_port
                stdout = json.dumps([{"Id": "fixture-" + name, "Name": "/" + name,
                    "State": {"Running": True}, "NetworkSettings": {"Ports": {
                        str(internal_port) + "/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(published_port)}]}}}])
            elif argv[:2] == ["docker", "inspect"]:
                stdout = json.dumps([{"State": {"Pid": 12345}, "NetworkSettings": {"Networks": {
                    module.NETWORK: {"IPAddress": "172.31.0.7", "Gateway": "172.31.0.1"}}}}])
            else:
                stdout = "0" if argv[-1] == "DBSIZE" else ""
            return SimpleNamespace(stdout=stdout, stderr="", returncode=0)

        probe = MagicMock()
        probe.__enter__.return_value = probe
        probe.connect_ex.return_value = 1
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(sys, "platform", "linux"))
            stack.enter_context(patch.object(Path, "mkdir", mkdir))
            stack.enter_context(patch.object(Path, "read_bytes", read_bytes))
            stack.enter_context(patch.object(module, "write_json", write))
            stack.enter_context(patch.object(module, "sql", return_value=""))
            stack.enter_context(patch.object(module, "command", side_effect=command))
            stack.enter_context(patch.object(module, "request_http", return_value=(200, {}, b'{"status":"UP"}')))
            stack.enter_context(patch.object(os, "chmod"))
            stack.enter_context(patch.object(socket, "socket", return_value=probe))
            stack.enter_context(patch.object(subprocess, "Popen", side_effect=self.popen))
            stack.enter_context(patch.object(subprocess, "run", side_effect=AssertionError("Unexpected real command")))
            stack.enter_context(patch.object(module.time, "sleep", side_effect=AssertionError("Unexpected sleep")))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            if performance:
                stack.enter_context(patch.object(module, "inspect_actual_profile", return_value={"passed": True}))
            if legacy_only:
                with self.assertRaises(FileNotFoundError):
                    module.serve(args)
            else:
                module.serve(args)

    def assert_three_service_identity(self, performance):
        ready = [state for state in self.states if state["phase"] == "READY"]
        self.assertEqual(len(ready), 1)
        state = ready[0]
        self.assertEqual(set(state["jvmArtifacts"]), set(JARS))
        self.assertEqual(len(self.launches), 3)
        self.assertEqual(len(self.jar_reads), 3)
        if not performance:
            for service, internal_port in (("mysql", 3306), ("redis", 6379)):
                binding = state["dependencyBindings"][service]
                self.assertEqual(binding["container"], state[service + "Container"])
                self.assertEqual(binding["containerId"], "fixture-" + state[service + "Container"])
                self.assertEqual(binding["hostIp"], "127.0.0.1")
                self.assertEqual(binding["hostPort"], state[service + "Port"])
                self.assertEqual(binding["containerPort"], internal_port)
        for i, (name, (filename, port, management)) in enumerate(JARS.items()):
            expected = self.candidate / "services" / name / "target" / filename
            argv, env = self.launches[i]
            self.assertEqual(Path(argv[argv.index("-jar") + 1]), expected)
            self.assertEqual(self.jar_reads[i], expected)
            self.assertEqual(argv[:3], ["taskset", "-c", "0-7"] if performance else ["java", "-Xms64m", "-Xmx384m"])
            for flag in ("-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                         "--spring.profiles.active=production",
                         "--spring.config.location=classpath:application-production.properties",
                         "--spring.data.redis.password=", "--spring.data.redis.database=8"):
                self.assertIn(flag, argv)
            self.assertEqual(env["REDIS_PORT"], "16379")
            self.assertEqual(env["KAFKA_BOOTSTRAP_SERVERS"], "localhost:19092")
            self.assertEqual(env["ADMIN_ALLOWED_HOSTS"], state["managementHost"])
            self.assertEqual(env["APISIX_CIDRS"], "172.31.0.7/32")
            artifact = state["jvmArtifacts"][name]
            self.assertEqual(set(artifact), {"pid", "businessPort", "healthPort", "sha256"})
            self.assertEqual(artifact, {"pid": 20001+i, "businessPort": port, "healthPort": management,
                                       "sha256": hashlib.sha256(self.bodies[name]).hexdigest()})
        self.assertTrue(all(process.stopped for process in self.processes))
        self.assertEqual(self.states[-1]["phase"], "STOPPED")
        self.assertTrue(self.states[-1]["servicesStopped"])
        if performance:
            self.assertEqual(state["resourceProfile"]["javaHeapMaxMiB"], 384)
            self.assertEqual(state["resourceProfile"]["dependencyCpuSet"], "8-11")
            self.assertEqual(state["resourceProfile"]["loadGeneratorCpuSet"], "12-15")

    def test_performance_entry_launches_and_hashes_new_root_artifacts(self):
        self.three_service_entry(True)
        self.assert_three_service_identity(True)

    def test_e2e_entry_launches_and_hashes_new_root_artifacts(self):
        self.three_service_entry(False)
        self.assert_three_service_identity(False)

    def test_e2e_explicit_resources_are_used_and_recorded_without_old_container_fallback(self):
        self.three_service_entry(False, extra_args=[
            "--mysql-container", "owned-mysql", "--redis-container", "owned-redis",
            "--kafka-container", "owned-kafka", "--network", "owned-network",
            "--mysql-port", "23306", "--redis-port", "26379",
            "--kafka-bootstrap", "localhost:29092", "--kafka-host", "owned-broker",
            "--object-endpoint", "http://127.0.0.1:29000"])
        state = next(value for value in self.states if value["phase"] == "READY")
        self.assertEqual(state["mysqlContainer"], "owned-mysql")
        self.assertEqual(state["redisContainer"], "owned-redis")
        self.assertEqual(state["kafkaContainer"], "owned-kafka")
        self.assertEqual(state["kafkaContainerBootstrap"], "localhost:9092")
        self.assertEqual(state["network"], "owned-network")
        for _, env in self.launches:
            self.assertIn("127.0.0.1:23306/shortlink_cr_e2e_", env["BUSINESS_DB_URL"])
            self.assertEqual(env["REDIS_PORT"], "26379")
            self.assertEqual(env["KAFKA_BOOTSTRAP_SERVERS"], "localhost:29092")
            self.assertEqual(env["OBJECT_ENDPOINT"], "http://127.0.0.1:29000")
        docker_run = next(argv for argv in self.commands if argv[:2] == ["docker", "run"])
        self.assertEqual(docker_run[docker_run.index("--network") + 1], "owned-network")
        self.assertIn("KAFKA_HOST=owned-broker", docker_run)
        redis_calls = [argv for argv in self.commands if "redis-cli" in argv]
        self.assertTrue(redis_calls)
        self.assertTrue(all(argv[2] == "owned-redis" for argv in redis_calls))
        self.assertFalse(any("FLUSHDB" in argv or "FLUSHALL" in argv for argv in self.commands))

    def test_performance_does_not_fall_back_to_legacy_target(self):
        self.three_service_entry(True, legacy_only=True)
        self.assertFalse(any(state["phase"] == "READY" for state in self.states))
        self.assertEqual(self.states[-1]["phase"], "FAILED")
        self.assertTrue(all(process.stopped for process in self.processes))

    def test_e2e_does_not_fall_back_to_legacy_target(self):
        self.three_service_entry(False, legacy_only=True)
        self.assertFalse(any(state["phase"] == "READY" for state in self.states))
        self.assertEqual(self.states[-1]["phase"], "FAILED")
        self.assertTrue(all(process.stopped for process in self.processes))

    def fake_jdk(self, name):
        target = self.candidate / name / "bin/java.exe"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"not executable; entrypoint only checks its location")
        return target.parent.parent

    def component_entry(self, environment, selection="all", platform="win32", rejection=None):
        adapter = load(self.candidate / "scripts/integration/component_adapters.py", "offline_component_adapter")
        self.assertEqual(adapter.ROOT, self.candidate)
        environment = {"SHORTLINK_IT_BUSINESS_DB_URL": "jdbc:mysql://127.0.0.1:13306/shortlink_business_it",
                       "SHORTLINK_IT_DB_USERNAME": "fixture", "SHORTLINK_IT_DB_PASSWORD": "fixture", **environment}
        code = self.candidate / "scripts/integration/production_jar_components.py"
        def http(port, method, path):
            self.assertIn(port, (18102, 18103))
            self.assertEqual(method, "GET")
            return 200, {}, b"metric 1\n" if path == "/actuator/prometheus" else b'{"status":"UP"}'
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.dict(os.environ, environment, clear=True))
            stack.enter_context(patch.dict(sys.modules, {"component_adapters": adapter}))
            stack.enter_context(patch.object(sys, "argv", [str(code), "--module", selection]))
            stack.enter_context(patch.object(sys, "platform", platform))
            stack.enter_context(patch.object(adapter, "http", side_effect=http))
            stack.enter_context(patch.object(subprocess, "Popen", side_effect=self.popen))
            stack.enter_context(patch.object(subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True))
            stack.enter_context(patch.object(subprocess, "run", side_effect=AssertionError("Unexpected command")))
            stack.enter_context(patch.object(socket, "socket", side_effect=AssertionError("Unexpected socket")))
            stub = MagicMock(server_address=("127.0.0.1", 28001))
            server_factory = stack.enter_context(patch.object(http_server, "ThreadingHTTPServer", return_value=stub))
            worker = MagicMock()
            stack.enter_context(patch.object(threading, "Thread", return_value=worker))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            with self.assertRaises(SystemExit) as exited:
                runpy.run_path(str(code), run_name="__main__")
        if rejection:
            self.assertIn(rejection, str(exited.exception))
            self.assertEqual(self.launches, [])
            self.assertFalse((self.candidate / ".work/component-results").exists())
        else:
            self.assertEqual(exited.exception.code, 0)
            expected_names = ["admin", "shortlink-redirect"] if selection == "all" else [selection]
            self.assertEqual(len(self.launches), len(expected_names))
            for name, (argv, env) in zip(expected_names, self.launches):
                filename = JARS[name][0]
                expected = self.candidate / "services" / name / "target" / filename
                self.assertEqual(Path(argv[3]), expected)
                self.assertTrue(expected.is_file())
                port, management = (18002, 18102) if name == "admin" else (18003, 18103)
                self.assertEqual(argv[1:], ["-Dfile.encoding=UTF-8", "-jar", str(expected),
                    "--spring.profiles.active=production", "--spring.config.location=classpath:application-production.properties",
                    "--server.port=" + str(port), "--management.server.port=" + str(management), "--spring.data.redis.password=",
                    "--management.endpoint.health.probes.enabled=true", "--management.endpoint.health.show-details=always"])
                self.assertEqual(env["KAFKA_BOOTSTRAP_SERVERS"], "localhost:19092")
                self.assertEqual(env["REDIS_PORT"], "16379")
                self.assertEqual(env["BUSINESS_DB_USERNAME"], "fixture")
                self.assertEqual(env["BUSINESS_DB_PASSWORD"], "fixture")
                self.assertEqual(env["ADMIN_ALLOWED_HOSTS"], "admin.it.test")
                self.assertEqual(env["APISIX_CIDRS"], "127.0.0.0/8")
                self.assertEqual(env["COMMAND_URL"], environment.get("SHORTLINK_IT_COMMAND_URL", "http://127.0.0.1:28001"))
                self.assertIn("ACCOUNT_PII_KEY", env)
                self.assertIn("AGENT_INTERNAL_TOKEN", env)
            self.assertTrue(all(process.stopped for process in self.processes))
            reports = list((self.candidate / ".work/component-results").glob("production-jars-*.json"))
            self.assertEqual(len(reports), 1)
            rows = json.loads(reports[0].read_text(encoding="utf-8"))
            self.assertEqual([row["module"] for row in rows], expected_names)
            self.assertTrue(all(row["healthHttpStatus"] == 200 and row["metricsHttpStatus"] == 200 for row in rows))
            for row in rows:
                self.assertEqual(row["jarSha256"], hashlib.sha256(self.bodies[row["module"]]).hexdigest())
            if environment.get("SHORTLINK_IT_COMMAND_URL"):
                server_factory.assert_not_called()
            else:
                server_factory.assert_called_once()
                stub.shutdown.assert_called_once()
                stub.server_close.assert_called_once()
                worker.join.assert_called_once_with(timeout=5)

    def test_component_explicit_jdk_wins_and_both_jars_use_new_root(self):
        selected, fallback = self.fake_jdk("explicit-jdk"), self.fake_jdk("fallback-jdk")
        self.component_entry({"SHORTLINK_IT_JAVA_HOME": str(selected), "JAVA_HOME": str(fallback)})
        self.assertTrue(all(Path(argv[0]) == selected / "bin/java.exe" for argv, _ in self.launches))

    def test_component_java_home_fallback_and_single_module(self):
        selected = self.fake_jdk("fallback-jdk")
        self.component_entry({"JAVA_HOME": str(selected)}, selection="admin")
        self.assertEqual(Path(self.launches[0][0][0]), selected / "bin/java.exe")

    def test_component_empty_explicit_home_uses_java_home(self):
        selected = self.fake_jdk("fallback-jdk")
        self.component_entry({"SHORTLINK_IT_JAVA_HOME": "", "JAVA_HOME": str(selected)}, selection="shortlink-redirect")
        self.assertEqual(Path(self.launches[0][0][0]), selected / "bin/java.exe")

    def test_component_explicit_command_does_not_start_a_stub(self):
        selected = self.fake_jdk("explicit-command-jdk")
        self.component_entry({"JAVA_HOME": str(selected), "SHORTLINK_IT_COMMAND_URL": "http://127.0.0.1:28011"})

    def test_component_missing_java_home_fails_before_output_or_process(self):
        self.component_entry({}, rejection="SHORTLINK_IT_JAVA_HOME or JAVA_HOME")

    def test_component_invalid_explicit_jdk_does_not_silently_fall_back(self):
        fallback = self.fake_jdk("fallback-jdk")
        self.component_entry({"SHORTLINK_IT_JAVA_HOME": str(self.candidate / "missing-jdk"), "JAVA_HOME": str(fallback)},
                             rejection="bin/java.exe")

    def test_component_rejects_non_windows_before_output_or_process(self):
        self.component_entry({}, platform="linux", rejection="on Windows")


if __name__ == "__main__":
    unittest.main()
