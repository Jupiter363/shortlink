"""Offline Redis selection checks; Docker, sockets, SQL and process I/O are mocked."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("explicit_redis_supervisor", Path(__file__).with_name("supervisor.py"))
supervisor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(supervisor)
DEDICATED = "shortlink-perf-redis-20260908-breakthrough"


class ExplicitRedisDatabaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = (supervisor.ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
        cls.config = (supervisor.ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8")

    def test_cli_default_and_dedicated_database_boundaries(self):
        self.assertIsNone(supervisor.parse_args([]).redis_database)
        for database in (1, 7, 8, 15):
            with self.subTest(database=database):
                args = supervisor.parse_args(["--redis-container", DEDICATED, "--redis-port", "16381",
                                              "--redis-database", str(database)])
                self.assertEqual(args.redis_database, database)
        args = supervisor.parse_args(["--redis-container", "shortlink-perf-redis-other", "--redis-port", "16380",
                                      "--redis-database", "1"])
        self.assertEqual(args.redis_database, 1)

    def test_cli_refuses_default_instance_invalid_database_and_wrong_port(self):
        cases = [["--redis-database", "8"], ["--redis-database", "0"]]
        cases += [["--redis-container", DEDICATED, "--redis-port", "16381", "--redis-database", value]
                  for value in ("-1", "0", "16", "1.5", "true")]
        cases += [["--redis-container", DEDICATED, "--redis-database", "1"],
                  ["--redis-container", "unowned-redis", "--redis-port", "16380", "--redis-database", "1"]]
        with patch.object(supervisor, "command") as command:
            for argv in cases:
                with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    supervisor.parse_args(argv)
            command.assert_not_called()

    def test_explicit_selection_queries_only_requested_database_and_never_mutates(self):
        for database in range(1, 16):
            with self.subTest(database=database), patch.object(supervisor, "command",
                    return_value=SimpleNamespace(stdout="0\n")) as command:
                self.assertEqual(supervisor.select_empty_redis_database(DEDICATED, 16381, database), database)
                command.assert_called_once_with(["docker", "exec", DEDICATED, "redis-cli", "-n", str(database), "DBSIZE"])

    def test_nonempty_or_unconfirmed_explicit_database_never_falls_back(self):
        for response in ("1", "20\n", "", "(error) unavailable", "0\n1", "-1", "00"):
            with self.subTest(response=response), patch.object(supervisor, "command",
                    return_value=SimpleNamespace(stdout=response)) as command:
                with self.assertRaisesRegex(RuntimeError, "not confirmed empty"):
                    supervisor.select_empty_redis_database(DEDICATED, 16381, 1)
                command.assert_called_once()

    def test_default_selection_preserves_first_empty_8_to_15_policy(self):
        for container, port in ((supervisor.REDIS, 16379), (DEDICATED, 16381)):
            with self.subTest(container=container), patch.object(supervisor, "command", side_effect=[
                    SimpleNamespace(stdout="42"), SimpleNamespace(stdout="1"), SimpleNamespace(stdout="0")]) as command:
                self.assertEqual(supervisor.select_empty_redis_database(container, port), 10)
                self.assertEqual([call[0][0][-2:] for call in command.call_args_list],
                                 [["8", "DBSIZE"], ["9", "DBSIZE"], ["10", "DBSIZE"]])
        with patch.object(supervisor, "command", return_value=SimpleNamespace(stdout="1")) as command:
            with self.assertRaisesRegex(RuntimeError, "No empty isolated"):
                supervisor.select_empty_redis_database(DEDICATED, 16381)
            self.assertEqual([call[0][0][-2] for call in command.call_args_list], list(map(str, range(8, 16))))

    def test_probe_failure_is_propagated_without_retry_or_alternative_database(self):
        with patch.object(supervisor, "command", side_effect=RuntimeError("offline probe failure")) as command:
            with self.assertRaisesRegex(RuntimeError, "offline probe failure"):
                supervisor.select_empty_redis_database(DEDICATED, 16381, 3)
            command.assert_called_once()

    def test_programmatic_invalid_selection_rejects_before_io(self):
        cases = [(DEDICATED, 16381, value) for value in (-1, 0, 16, True, False, 0.0, "0")]
        cases += [(supervisor.REDIS, 16379, 0), (supervisor.REDIS, 16379, 15),
                  (DEDICATED, 16380, 0), ("shortlink-perf-redis-", 16380, 0)]
        for container, port, database in cases:
            args = supervisor.parse_args(["--allow-test-database"])
            args.redis_container, args.redis_port, args.redis_database = container, port, database
            with self.subTest(container=container, port=port, database=database), contextlib.ExitStack() as stack:
                mocks = [stack.enter_context(patch.object(Path, name)) for name in ("read_text", "write_text", "mkdir")]
                mocks += [stack.enter_context(patch.object(supervisor, name)) for name in ("sql", "command")]
                mocks.append(stack.enter_context(patch.object(supervisor.subprocess, "Popen")))
                mocks.append(stack.enter_context(patch.object(supervisor.socket, "socket")))
                with self.assertRaises(ValueError):
                    supervisor.serve(args)
                for mocked in mocks:
                    mocked.assert_not_called()

    def test_default_database_zero_is_rejected_even_when_dedicated_probe_would_be_empty(self):
        # DB0 is outside the observer's non-default DB contract; fail before DBSIZE.
        with patch.object(supervisor, "command", return_value=SimpleNamespace(stdout="0")) as command:
            with self.assertRaisesRegex(ValueError, "non-default integer from 1 to 15"):
                supervisor.select_empty_redis_database(DEDICATED, 16381, 0)
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                supervisor.parse_args(["--redis-container", DEDICATED, "--redis-port", "16381",
                                       "--redis-database", "0"])
            command.assert_not_called()

    def simulated_serve(self, database, probe_value="0", legacy=False):
        args = supervisor.parse_args(["--allow-test-database", "--edge-workers", "8", "--max-runtime-seconds", "0",
                                      "--redis-container", DEDICATED, "--redis-port", "16381"])
        args.redis_database = database
        if legacy:
            del args.redis_database
        states, commands, launches, sql_statements = [], [], [], []

        def read_source(path, *unused_args, **unused_kwargs):
            if path.name == "apisix.yaml":
                return self.manifest
            if path.name == "config.yaml":
                return self.config
            if path.name == "001-business-schema.sql":
                return "-- offline DDL placeholder"
            if path.name == "state.json":
                return json.dumps(states[-1])
            raise AssertionError("Unexpected mocked read: " + path.name)

        def write_json(path, value):
            if path.name == "state.json":
                states.append(copy.deepcopy(value))

        def command(argv, **kwargs):
            commands.append(list(argv))
            stdout = probe_value if argv[-1] == "DBSIZE" else ""
            if argv[:2] == ["docker", "inspect"]:
                stdout = json.dumps([{"State": {"Pid": 12345}, "NetworkSettings": {"Networks": {
                    supervisor.NETWORK: {"IPAddress": "172.31.0.7", "Gateway": "172.31.0.1"}}}}])
            return SimpleNamespace(stdout=stdout, stderr="", returncode=0)

        def popen(argv, **kwargs):
            launches.append((list(argv), kwargs["env"]))
            process = Mock(pid=20000 + len(launches))
            process.poll.return_value = None
            process.wait.return_value = 0
            return process

        socket_instance = Mock()
        socket_instance.__enter__ = Mock(return_value=socket_instance)
        socket_instance.__exit__ = Mock(return_value=False)
        socket_instance.connect_ex.return_value = 1
        probe_profile = {"passed": True, "profile": "OFFLINE_BOUNDED_JVM_PROFILE"}
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(supervisor.sys, "platform", "linux"))
            stack.enter_context(patch.object(Path, "read_text", read_source))
            stack.enter_context(patch.object(Path, "read_bytes", return_value=b"offline mock jar"))
            stack.enter_context(patch.object(Path, "write_text"))
            stack.enter_context(patch.object(Path, "mkdir"))
            stack.enter_context(patch.object(Path, "open", side_effect=lambda *a, **k: io.BytesIO()))
            stack.enter_context(patch.object(supervisor.os, "chmod"))
            stack.enter_context(patch.object(supervisor, "write_json", side_effect=write_json))
            stack.enter_context(patch.object(supervisor, "sql", side_effect=lambda statement, *a: sql_statements.append(statement) or ""))
            stack.enter_context(patch.object(supervisor, "command", side_effect=command))
            probe = stack.enter_context(patch.object(supervisor, "inspect_actual_profile",
                                                     return_value=probe_profile))
            stack.enter_context(patch.object(supervisor, "request_http", return_value=(200, {}, b'{"status":"UP"}')))
            stack.enter_context(patch.object(supervisor.subprocess, "Popen", side_effect=popen))
            stack.enter_context(patch.object(supervisor.socket, "socket", return_value=socket_instance))
            stack.enter_context(patch.object(supervisor.time, "sleep", side_effect=AssertionError("No waiting in offline test")))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            if probe_value == "0":
                supervisor.serve(args)
            else:
                with self.assertRaisesRegex(RuntimeError, "not confirmed empty"):
                    supervisor.serve(args)
        if probe_value == "0":
            probe.assert_called_once_with(supervisor.KAFKA)
            self.assertTrue(states)
            self.assertTrue(all(state["kafkaHealthProbeProfile"] == probe_profile for state in states))
        else:
            probe.assert_not_called()
        return states, commands, launches, sql_statements

    def test_actual_serve_records_selection_and_wires_all_java_clients(self):
        for database, legacy in ((1, False), (15, False), (None, False), (None, True)):
            with self.subTest(database=database, legacy=legacy):
                states, commands, launches, unused = self.simulated_serve(database, legacy=legacy)
                selected = 8 if database is None else database
                ready = next(state for state in states if state["phase"] == "READY")
                self.assertEqual(ready["redisDatabase"], selected)
                self.assertEqual(ready["redisDatabaseRequested"], database)
                self.assertEqual(ready["redisDatabaseSelection"],
                                 "automatic-empty-8-15" if database is None else "explicit-empty")
                self.assertEqual((ready["redisContainer"], ready["redisPort"]), (DEDICATED, 16381))
                self.assertEqual(states[-1]["phase"], "STOPPED")
                probes = [argv for argv in commands if argv[-1] == "DBSIZE"]
                self.assertEqual(probes, [["docker", "exec", DEDICATED, "redis-cli", "-n", str(selected), "DBSIZE"]])
                self.assertEqual(len(launches), 3)
                for argv, env in launches:
                    self.assertIn("--spring.data.redis.database=" + str(selected), argv)
                    self.assertEqual(env["REDIS_PORT"], "16381")
                self.assertFalse(any("FLUSHDB" in argv or "FLUSHALL" in argv for argv in commands))

    def test_actual_serve_nonempty_explicit_database_stops_before_schema_or_process_mutation(self):
        states, commands, launches, sql_statements = self.simulated_serve(1, probe_value="17")
        self.assertEqual(states, [])
        self.assertEqual(launches, [])
        self.assertEqual(sql_statements, ["SELECT 1;"])
        self.assertEqual(commands, [["docker", "exec", DEDICATED, "redis-cli", "-n", "1", "DBSIZE"]])


if __name__ == "__main__":
    unittest.main(verbosity=2)
