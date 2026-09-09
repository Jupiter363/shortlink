"""Offline affinity selection and real serve wiring with every I/O mocked."""
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
MODULE = Path(__file__).with_name("supervisor.py")
spec = importlib.util.spec_from_file_location("application_cpu_supervisor", str(MODULE))
supervisor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(supervisor)


class ApplicationCpuProfileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = (supervisor.ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
        cls.config = (supervisor.ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8")

    def test_cli_default_and_explicit_extended_choice(self):
        self.assertEqual(supervisor.parse_args([]).application_cpu_set, "0-7")
        self.assertIsNone(supervisor.parse_args([]).edge_cpu_set)
        args = supervisor.parse_args(["--edge-workers", "8", "--application-cpu-set", "0-11"])
        self.assertEqual(args.application_cpu_set, "0-11")
        self.assertEqual((args.edge_workers, args.edge_send_concurrency, args.edge_send_linger_millis), (8, 1, 5))

    def test_cli_partitioned_cpu_choice_requires_eight_workers(self):
        args = supervisor.parse_args(["--edge-workers", "8", "--application-cpu-set", "0-3"])
        self.assertEqual(args.application_cpu_set, "0-3")
        self.assertEqual((args.edge_send_concurrency, args.edge_send_linger_millis), (1, 5))

    def test_invalid_cli_cpu_values_or_worker_combinations_do_not_serve(self):
        cases = [["--application-cpu-set", value] for value in ("0-15", "4-7", "0,1", "0-11 ", "8", "true")]
        cases += [["--edge-workers", str(workers), "--application-cpu-set", cpu]
                  for workers in (2, 4) for cpu in ("0-3", "0-11")]
        with patch.object(supervisor, "serve") as serve:
            for argv in cases:
                with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    supervisor.parse_args(argv)
            serve.assert_not_called()

    def test_programmatic_values_are_strict_and_profiles_are_not_mutated(self):
        base = supervisor.edge_worker_profile(8)
        for value in (None, True, False, 7, 11.0, "0-15", "0-11 "):
            with self.subTest(value=value), self.assertRaises(ValueError):
                supervisor.application_cpu_profile(base, value)
        original = copy.deepcopy(base)
        extended = supervisor.application_cpu_profile(base, "0-11")
        self.assertEqual(base, original)
        self.assertIsNot(extended, base)

    def test_default_preserves_all_existing_worker_sender_affinities_and_manifests(self):
        for workers in (2, 4, 8):
            for senders in (1, 2, 4):
                manifest, config, base = supervisor.render_edge_worker_configuration(
                    self.manifest, self.config, workers, senders)
                actual = supervisor.application_cpu_profile(base)
                with self.subTest(workers=workers, senders=senders):
                    self.assertEqual({key: actual[key] for key in base}, base)
                    self.assertEqual(actual["javaCpuSet"], "0-7")
                    self.assertIsNone(actual["applicationDependencyCpuOverlap"])
                    self.assertEqual(actual["applicationCpuUnion"], "0-7")
                    self.assertEqual(actual["javaEdgeCpuOverlap"], base["edgeCpuSet"])
                    self.assertEqual(supervisor.render_edge_worker_configuration(
                        self.manifest, self.config, workers, senders), (manifest, config, base))

    def test_extended_affinity_changes_no_sender_queue_batch_linger_budget(self):
        base = dict(supervisor.edge_worker_profile(8, 4), edgeSendLingerMillis=5)
        extended = supervisor.application_cpu_profile(base, "0-11")
        self.assertEqual(extended["javaCpuSet"], "0-11")
        self.assertEqual(extended["edgeCpuSet"], "0-11")
        self.assertEqual(extended["applicationCpuSet"], "0-11")
        self.assertEqual(extended["applicationDependencyCpuOverlap"], "8-11")
        self.assertEqual(extended["applicationCpuUnion"], "0-11")
        self.assertEqual(extended["javaEdgeCpuOverlap"], "0-11")
        for key in set(base) - {"edgeCpuSet"}:
            self.assertEqual(extended[key], base[key], key)
        self.assertEqual(extended["edgeQueueCountNode"], 2000)
        self.assertEqual(extended["edgeQueueBytesNode"], 16 * 1024 * 1024)

    def test_partitioned_affinity_has_disjoint_same_union_and_preserves_all_budgets(self):
        base = dict(supervisor.edge_worker_profile(8, 4), edgeSendLingerMillis=5)
        original = copy.deepcopy(base)
        selected = supervisor.application_cpu_profile(base, "0-3")
        self.assertEqual(base, original)
        self.assertEqual((selected["javaCpuSet"], selected["edgeCpuSet"]), ("0-3", "4-7"))
        self.assertEqual(selected["applicationCpuSet"], "0-3")
        self.assertEqual(selected["applicationCpuUnion"], "0-7")
        self.assertIsNone(selected["javaEdgeCpuOverlap"])
        self.assertIsNone(selected["applicationDependencyCpuOverlap"])
        for key in set(base) - {"edgeCpuSet"}:
            self.assertEqual(selected[key], base[key], key)
        self.assertEqual((selected["edgeWorkers"], selected["edgeSenderSlotsNode"]), (8, 32))
        self.assertEqual((selected["edgeQueueCountNode"], selected["edgeQueueBytesNode"]), (2000, 16 * 1024 * 1024))

    def test_invalid_programmatic_serve_rejects_before_any_io(self):
        for workers, cpu in ((2, "0-11"), (4, "0-11"), (2, "0-3"), (4, "0-3"), (8, "0-15"), (8, True)):
            args = supervisor.parse_args(["--allow-test-database"])
            args.edge_workers, args.application_cpu_set = workers, cpu
            with self.subTest(workers=workers, cpu=cpu), patch.object(Path, "read_text") as read, \
                    patch.object(Path, "write_text") as write, patch.object(Path, "mkdir") as mkdir, \
                    patch.object(supervisor, "sql") as sql, patch.object(supervisor, "command") as command, \
                    patch.object(supervisor.subprocess, "Popen") as popen, \
                    patch.object(supervisor.socket, "socket") as socket:
                with self.assertRaisesRegex(ValueError, "APPLICATION_CPU_SET"):
                    supervisor.serve(args)
                for mocked in (read, write, mkdir, sql, command, popen, socket):
                    mocked.assert_not_called()

    def test_explicit_edge_cpu_is_bounded_and_preserves_all_other_profile_values(self):
        base = supervisor.application_cpu_profile(supervisor.edge_worker_profile(4, 2))
        self.assertEqual(supervisor.edge_cpu_profile(base), base)
        for selected in ("4-7", "0-7"):
            args = supervisor.parse_args(["--edge-workers", "4", "--edge-cpu-set", selected])
            self.assertEqual(args.edge_cpu_set, selected)
            actual = supervisor.edge_cpu_profile(base, selected)
            self.assertEqual(actual["edgeCpuSet"], selected)
            self.assertEqual(actual["javaEdgeCpuOverlap"], selected)
            self.assertEqual(actual["applicationCpuUnion"], "0-7")
            for key in set(base)-{"edgeCpuSet", "javaEdgeCpuOverlap"}:
                self.assertEqual(actual[key], base[key], key)

    def test_invalid_edge_cpu_options_refused_before_any_io(self):
        cases = [(2,"0-7","0-7"),(8,"0-7","0-7"),(4,"0-3","0-7"),(4,"0-11","0-7"),
                 (4,"0-7","0-11"),(4,"0-7",True),(4,"0-7","0,1,2,3,4,5,6,7")]
        for workers, java, edge in cases:
            with self.subTest(workers=workers, java=java, edge=edge):
                with self.assertRaises(ValueError):
                    supervisor.validate_edge_cpu_selection(workers, java, edge)
                args = supervisor.parse_args(["--allow-test-database"])
                args.edge_workers, args.application_cpu_set, args.edge_cpu_set = workers, java, edge
                with patch.object(Path,"read_text") as read, patch.object(Path,"mkdir") as mkdir, \
                     patch.object(supervisor,"command") as command, patch.object(supervisor,"sql") as sql, \
                     patch.object(supervisor.subprocess,"Popen") as popen:
                    with self.assertRaises(ValueError): supervisor.serve(args)
                    for mocked in (read,mkdir,command,sql,popen): mocked.assert_not_called()
        for argv in (["--edge-cpu-set","0-7"], ["--edge-workers","8","--edge-cpu-set","4-7"],
                     ["--edge-workers","4","--edge-cpu-set","0-11"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                supervisor.parse_args(argv)

    def simulated_serve(self, workers, cpu, selected_edge=None, probe_failure=None):
        args = supervisor.parse_args(["--allow-test-database", "--edge-workers", str(workers),
                                      "--edge-send-linger-millis", "5", "--max-runtime-seconds", "0"])
        if cpu is None:
            del args.application_cpu_set  # Legacy programmatic caller.
        else:
            args.application_cpu_set = cpu
        args.edge_cpu_set = selected_edge
        files, states, commands, processes, launches = {}, [], [], [], []

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

        def write_source(path, content, *unused_args, **unused_kwargs):
            files[str(path)] = content

        def json_write(path, value):
            if path.name == "state.json":
                states.append(copy.deepcopy(value))

        def fake_command(argv, **kwargs):
            commands.append(list(argv))
            stdout = ""
            if argv[:2] == ["docker", "inspect"]:
                stdout = json.dumps([{"State": {"Pid": 12345}, "NetworkSettings": {"Networks": {
                    supervisor.NETWORK: {"IPAddress": "172.31.0.7", "Gateway": "172.31.0.1"}}}}])
            elif argv[-1] == "DBSIZE":
                stdout = "0"
            return SimpleNamespace(stdout=stdout, stderr="", returncode=0)

        def fake_popen(argv, **kwargs):
            launches.append(list(argv))
            process = Mock(pid=20000 + len(processes))
            process.poll.return_value = None
            process.wait.return_value = 0
            processes.append(process)
            return process

        def fake_probe(container):
            self.assertEqual(container, supervisor.KAFKA)
            self.assertEqual(len([c for c in commands if c[:2] == ["docker", "update"]]), 4)
            self.assertFalse(any(c[:2] == ["docker", "run"] for c in commands))
            self.assertEqual(launches, [])
            if probe_failure is not None:
                raise ValueError(probe_failure)
            return {"passed": True, "profile": "OFFLINE_MOCK_BOUNDED_PROFILE"}

        socket_instance = Mock()
        socket_instance.__enter__ = Mock(return_value=socket_instance)
        socket_instance.__exit__ = Mock(return_value=False)
        socket_instance.connect_ex.return_value = 1
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(supervisor.sys, "platform", "linux"))
            stack.enter_context(patch.object(Path, "read_text", read_source))
            stack.enter_context(patch.object(Path, "read_bytes", return_value=b"offline mock jar"))
            stack.enter_context(patch.object(Path, "write_text", write_source))
            stack.enter_context(patch.object(Path, "mkdir"))
            stack.enter_context(patch.object(Path, "open", side_effect=lambda *a, **k: io.BytesIO()))
            stack.enter_context(patch.object(supervisor.os, "chmod"))
            stack.enter_context(patch.object(supervisor.os, "cpu_count", return_value=16))
            stack.enter_context(patch.object(supervisor, "write_json", side_effect=json_write))
            stack.enter_context(patch.object(supervisor, "sql", return_value=""))
            stack.enter_context(patch.object(supervisor, "command", side_effect=fake_command))
            preflight = stack.enter_context(patch.object(supervisor, "inspect_actual_profile", side_effect=fake_probe))
            stack.enter_context(patch.object(supervisor, "request_http", return_value=(200, {}, b'{"status":"UP"}')))
            stack.enter_context(patch.object(supervisor.subprocess, "Popen", side_effect=fake_popen))
            stack.enter_context(patch.object(supervisor.socket, "socket", return_value=socket_instance))
            sleep = stack.enter_context(patch.object(supervisor.time, "sleep", side_effect=AssertionError("No waiting in offline test")))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            if probe_failure is None:
                supervisor.serve(args)
            else:
                with self.assertRaisesRegex(ValueError, "^" + probe_failure + "$"):
                    supervisor.serve(args)
            preflight.assert_called_once_with(supervisor.KAFKA)
            sleep.assert_not_called()
        return files, states, commands, launches

    def test_health_probe_evidence_persisted_before_ready(self):
        _, states, _, _ = self.simulated_serve(4, "0-7")
        ready = next(s for s in states if s["phase"] == "READY")
        self.assertEqual(ready["kafkaHealthProbeProfile"],
                         {"passed": True, "profile": "OFFLINE_MOCK_BOUNDED_PROFILE"})
        self.assertEqual(states[-1]["kafkaHealthProbeProfile"], ready["kafkaHealthProbeProfile"])

    def test_failed_health_probe_starts_no_listener_and_preserves_failure_through_cleanup(self):
        _, states, commands, launches = self.simulated_serve(
            4, "0-7", probe_failure="KAFKA_PROBE_COMMAND_UNRECOGNIZED")
        self.assertFalse(any(c[:2] == ["docker", "run"] for c in commands))
        self.assertEqual(launches, [])
        self.assertEqual(states[-1]["phase"], "FAILED")
        self.assertEqual(states[-1]["error"], "KAFKA_PROBE_COMMAND_UNRECOGNIZED")
        self.assertTrue(states[-1]["servicesStopped"])
        self.assertNotIn("kafkaHealthProbeProfile", states[-1])

    def test_explicit_edge_cpu_state_docker_java_and_description_are_consistent(self):
        for selected in ("4-7", "0-7"):
            files, states, commands, launches = self.simulated_serve(4, "0-7", selected)
            ready = next(s for s in states if s["phase"] == "READY")
            profile = ready["resourceProfile"]
            self.assertEqual(ready["edgeCpuSetRequested"], selected)
            self.assertEqual((profile["javaCpuSet"],profile["edgeCpuSet"],profile["javaEdgeCpuOverlap"]), ("0-7",selected,selected))
            self.assertEqual(profile["applicationCpuUnion"], "0-7")
            self.assertEqual((profile["edgeWorkers"],profile["edgeSenderSlotsNode"]), (4,4))
            self.assertEqual((profile["edgeQueueCountNode"],profile["edgeQueueBytesNode"]), (2000,16*1024*1024))
            runs = [x for x in commands if x[:2]==["docker","run"]]
            self.assertEqual(runs[0][runs[0].index("--cpuset-cpus")+1], selected)
            self.assertTrue(all(x[:4]==["taskset","-c","0-7","java"] for x in launches))
            meaning = ready["edgeWorkerComparison"]["cpuAffinityMeaning"]
            self.assertIn("APISIX uses "+selected, meaning)
            self.assertIn("Java/APISIX overlap: "+selected, meaning)
            self.assertIn("application CPU union: 0-7", meaning)

    def test_actual_serve_docker_java_and_persisted_state_are_consistent(self):
        for workers, cpu in ((2, None), (4, "0-7"), (8, "0-7"), (8, "0-11"), (8, "0-3")):
            files, states, commands, launches = self.simulated_serve(workers, cpu)
            java_cpu = cpu or "0-7"
            edge_cpu = "0-11" if cpu == "0-11" else "4-7" if cpu == "0-3" else "0-7" if workers == 8 else "4-7"
            with self.subTest(workers=workers, cpu=cpu):
                ready = next(state for state in states if state["phase"] == "READY")
                profile = ready["resourceProfile"]
                self.assertEqual((profile["javaCpuSet"], profile["edgeCpuSet"]), (java_cpu, edge_cpu))
                self.assertEqual(profile["applicationCpuSet"], java_cpu)
                self.assertEqual(profile["applicationDependencyCpuOverlap"], "8-11" if cpu == "0-11" else None)
                self.assertEqual(profile["applicationCpuUnion"], "0-11" if cpu == "0-11" else "0-7")
                self.assertEqual(profile["javaEdgeCpuOverlap"], None if cpu == "0-3" else edge_cpu)
                self.assertEqual((profile["dependencyCpuSet"], profile["loadGeneratorCpuSet"]), ("8-11", "12-15"))
                self.assertEqual((profile["javaHeapMaxMiB"], profile["javaActiveProcessors"]), (384, 2))
                self.assertEqual((profile["edgeSendConcurrencyPerWorker"], profile["edgeSendLingerMillis"]), (1, 5))
                self.assertEqual((profile["edgeQueueCountNode"], profile["edgeQueueBytesNode"]), (2000, 16 * 1024 * 1024))
                self.assertEqual(profile["edgeSenderSlotsNode"], workers)
                docker_runs = [argv for argv in commands if argv[:2] == ["docker", "run"]]
                self.assertEqual(len(docker_runs), 1)
                self.assertEqual(docker_runs[0][docker_runs[0].index("--cpuset-cpus") + 1], edge_cpu)
                self.assertEqual(docker_runs[0][docker_runs[0].index("--memory") + 1], "1g")
                updates = [argv for argv in commands if argv[:2] == ["docker", "update"]]
                self.assertEqual(len(updates), 4)
                self.assertTrue(all(argv[argv.index("--cpuset-cpus") + 1] == "8-11" for argv in updates))
                self.assertEqual(len(launches), 4)
                for argv in launches:
                    self.assertEqual(argv[:4], ["taskset", "-c", java_cpu, "java"])
                    self.assertIn("-Xmx384m", argv)
                    self.assertIn("-XX:ActiveProcessorCount=2", argv)
                manifest = next(value for path, value in files.items() if path.endswith("apisix.yaml"))
                config = next(value for path, value in files.items() if path.endswith("config.yaml"))
                expected, expected_config, unused = supervisor.render_edge_worker_configuration(
                    self.manifest, self.config, workers, 1)
                self.assertEqual(manifest, supervisor.render_edge_linger_configuration(expected, 5))
                self.assertEqual(config, expected_config)
                self.assertIn("Java uses " + java_cpu, ready["edgeWorkerComparison"]["cpuAffinityMeaning"])
                self.assertIn("APISIX uses " + edge_cpu, ready["edgeWorkerComparison"]["cpuAffinityMeaning"])
                if cpu == "0-3":
                    self.assertIn("Java/APISIX overlap: none", ready["edgeWorkerComparison"]["cpuAffinityMeaning"])
                    self.assertIn("application CPU union: 0-7", ready["edgeWorkerComparison"]["cpuAffinityMeaning"])
                self.assertEqual(states[-1]["phase"], "STOPPED")


if __name__ == "__main__":
    unittest.main(verbosity=2)
