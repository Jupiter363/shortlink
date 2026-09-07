"""Offline worker-profile tests; no supervisor serve or runtime is started.

Run with Python -B. The candidate is loaded while adjacent to this test; after
root promotes both files to scripts/performance, the adjacent supervisor.py is
loaded instead. Tests read public deployment sources and mock every write/process
boundary in the one early-failure integration check.
"""
import ast
import contextlib
import importlib.util
import io
from pathlib import Path
import re
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("supervisor-workers-candidate.py")
if not MODULE_PATH.exists():
    MODULE_PATH = Path(__file__).with_name("supervisor.py")
module_spec = importlib.util.spec_from_file_location("edge_worker_supervisor_under_test", str(MODULE_PATH))
supervisor = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(supervisor)


class EdgeWorkerProfileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = (supervisor.ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
        cls.config = (supervisor.ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8")

    def render(self, workers=2, manifest=None, config=None):
        return supervisor.render_edge_worker_configuration(
            self.manifest if manifest is None else manifest,
            self.config if config is None else config, workers)

    def test_cli_defaults_and_explicit_worker_choices(self):
        self.assertEqual(supervisor.parse_args([]).edge_workers, 2)
        for workers in (2, 4, 8):
            args = supervisor.parse_args(["--edge-workers", str(workers)])
            self.assertEqual(args.edge_workers, workers)
            self.assertEqual(args.metadata_workers, 4)
            self.assertEqual(args.redis_container, supervisor.REDIS)
            self.assertEqual(args.redis_port, 16379)

    def test_cli_rejects_other_workers_without_running_serve(self):
        with patch.object(supervisor, "serve") as serve:
            for value in ("0", "1", "3", "6", "16", "true", "4.0", "8.0"):
                with self.subTest(value=value), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    supervisor.parse_args(["--edge-workers", value])
            serve.assert_not_called()

    def test_programmatic_choices_require_exact_int(self):
        for value in (None, True, False, 2.0, 4.0, 8.0, "4", "8", 1, 6, 16):
            with self.subTest(value=value), self.assertRaises(ValueError):
                supervisor.edge_worker_profile(value)

    def test_node_budgets_are_constant_but_total_senders_change(self):
        for workers, per_count, per_bytes, slots in ((2, 1000, 8388608, 8), (4, 500, 4194304, 16),
                                                   (8, 250, 2097152, 32)):
            with self.subTest(workers=workers):
                profile = supervisor.edge_worker_profile(workers)
                self.assertEqual(profile["edgeWorkers"], workers)
                self.assertEqual(profile["edgeCpuSet"], "0-7" if workers == 8 else "4-7")
                self.assertEqual(profile["edgeQueueCountPerWorker"], per_count)
                self.assertEqual(profile["edgeQueueBytesPerWorker"], per_bytes)
                self.assertEqual(per_count * workers, profile["edgeQueueCountNode"])
                self.assertEqual(per_bytes * workers, profile["edgeQueueBytesNode"])
                self.assertEqual(profile["edgeQueueCountNode"], 2000)
                self.assertEqual(profile["edgeQueueBytesNode"], 16 * 1024 * 1024)
                self.assertEqual(profile["edgeSendConcurrencyPerWorker"], 4)
                self.assertEqual(profile["edgeSenderSlotsNode"], slots)
                self.assertEqual(profile["edgeSendBatchSize"], 32)
                self.assertEqual(profile["edgeSendBatchBytes"], 65536)

    def test_default_two_workers_matches_previous_temporary_output_exactly(self):
        manifest, config, _ = self.render(2)
        self.assertEqual(manifest, self.manifest)
        previous = self.config.replace("nginx_config:\n", "nginx_config:\n  worker_processes: 2\n", 1)
        self.assertEqual(config, previous)

    def test_four_workers_changes_only_two_queue_values_and_worker_directive(self):
        manifest, config, _ = self.render(4)
        expected = self.manifest.replace("        queue_count: 1000\n", "        queue_count: 500\n", 1)
        expected = expected.replace("        queue_bytes: 8388608\n", "        queue_bytes: 4194304\n", 1)
        self.assertEqual(manifest, expected)
        self.assertEqual(config, self.config.replace("nginx_config:\n", "nginx_config:\n  worker_processes: 4\n", 1))
        self.assertEqual(re.findall(r"\$\{\{[^}]+\}\}", manifest),
                         re.findall(r"\$\{\{[^}]+\}\}", self.manifest))

    def test_eight_workers_only_change_queue_values_and_worker_directive(self):
        manifest, config, profile = self.render(8)
        expected = self.manifest.replace("        queue_count: 1000\n", "        queue_count: 250\n", 1)
        expected = expected.replace("        queue_bytes: 8388608\n", "        queue_bytes: 2097152\n", 1)
        self.assertEqual(manifest, expected)
        self.assertEqual(config, self.config.replace("nginx_config:\n", "nginx_config:\n  worker_processes: 8\n", 1))
        self.assertEqual(profile["edgeCpuSet"], "0-7")
        self.assertEqual(re.findall(r"\$\{\{[^}]+\}\}", manifest),
                         re.findall(r"\$\{\{[^}]+\}\}", self.manifest))

    def test_actual_docker_run_command_uses_profile_cpu_without_starting_runtime(self):
        tree = ast.parse(MODULE_PATH.read_text(encoding="utf-8"))
        serve = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "serve")
        calls = [node for node in ast.walk(serve)
                 if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                 and node.func.id == "command" and node.args and isinstance(node.args[0], ast.List)
                 and len(node.args[0].elts) >= 2
                 and ast.literal_eval(ast.Tuple(elts=node.args[0].elts[:2], ctx=ast.Load())) == ("docker", "run")]
        self.assertEqual(len(calls), 1)
        expression = ast.Expression(body=calls[0])
        ast.fix_missing_locations(expression)
        for workers in (2, 4, 8):
            namespace = dict(vars(supervisor), container="offline-container", suffix="offline",
                             edge_config_path=Path("offline-config.yaml"), manifest_path=Path("offline-apisix.yaml"),
                             edge_profile=supervisor.edge_worker_profile(workers))
            with self.subTest(workers=workers), patch.object(supervisor, "command") as command:
                namespace["command"] = command
                eval(compile(expression, str(MODULE_PATH), "eval"), namespace)
                command.assert_called_once()
                argv = command.call_args[0][0]
                self.assertEqual(argv[argv.index("--cpuset-cpus") + 1], namespace["edge_profile"]["edgeCpuSet"])
                self.assertEqual(argv[argv.index("--memory") + 1], "1g")

    def test_duplicate_global_logger_blocks_and_inline_occurrence_rejected(self):
        bad_sources = [self.manifest + "\nglobal_rules:\n  - id: duplicate\n",
                       self.manifest.replace("routes:\n", "      shortlink-request-logger:\n        queue_count: 1000\nroutes:\n", 1),
                       self.manifest + "\nother: {shortlink-request-logger: {queue_count: 1}}\n"]
        for source in bad_sources:
            with self.subTest(source=source[-80:]), self.assertRaises(ValueError):
                self.render(4, manifest=source)

    def test_logger_outside_global_rules_rejected(self):
        source = "global_rules:\n  - id: unrelated\n" + self.manifest.replace("global_rules:\n", "other_routes:\n", 1)
        with self.assertRaises(ValueError):
            self.render(4, manifest=source)

    def test_changed_pinned_logger_values_fail_closed_for_all_profiles(self):
        expected = {"queue_count": 1000, "queue_bytes": 8388608, "max_event_bytes": 4096,
                    "send_concurrency": 4, "send_batch_size": 32, "send_batch_bytes": 65536}
        for key, value in expected.items():
            source = self.manifest.replace("        " + key + ": " + str(value),
                                           "        " + key + ": " + str(value + 1), 1)
            for workers in (2, 4, 8):
                with self.subTest(key=key, workers=workers), self.assertRaises(ValueError):
                    self.render(workers, manifest=source)

    def test_missing_duplicate_or_wrong_indent_queue_key_rejected(self):
        line = "        queue_count: 1000\n"
        for replacement in ("", line + line, "          queue_count: 1000\n", "\tqueue_count: 1000\n"):
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                self.render(4, manifest=self.manifest.replace(line, replacement, 1))

    def test_nginx_header_and_existing_worker_directive_ambiguity_rejected(self):
        bad_configs = [self.config.replace("nginx_config:\n", "other:\n", 1),
                       self.config + "\nnginx_config:\n  http: {}\n",
                       self.config + "\nnginx_config: {}\n",
                       self.config.replace("nginx_config:\n", "nginx_config:\n  worker_processes: 2\n", 1)]
        for config in bad_configs:
            with self.subTest(config=config[-80:]), self.assertRaises(ValueError):
                self.render(4, config=config)

    def test_crlf_and_field_comments_are_preserved(self):
        manifest = self.manifest.replace("queue_count: 1000", "queue_count: 1000 # fixed node budget").replace("\n", "\r\n")
        config = self.config.replace("\n", "\r\n")
        output, rendered, _ = self.render(4, manifest, config)
        self.assertIn("queue_count: 500 # fixed node budget\r\n", output)
        self.assertNotIn("\n", output.replace("\r\n", ""))
        self.assertNotIn("\n", rendered.replace("\r\n", ""))
        self.assertIn("  worker_processes: 4\r\n", rendered)

    def test_render_is_pure_and_does_not_read_or_write_files_or_run_commands(self):
        with patch.object(supervisor, "command") as command, patch.object(supervisor, "sql") as sql, \
                patch.object(supervisor, "request_http") as http, patch.object(Path, "read_text") as read, \
                patch.object(Path, "write_text") as write, patch.object(Path, "mkdir") as mkdir, \
                patch.object(supervisor.subprocess, "Popen") as popen, patch.object(supervisor.socket, "socket") as socket:
            first = self.render(4)
            second = self.render(4)
            self.assertEqual(first, second)
            for mocked in (command, sql, http, read, write, mkdir, popen, socket):
                mocked.assert_not_called()

    def test_invalid_deployment_is_rejected_before_serve_creates_anything(self):
        args = supervisor.parse_args(["--allow-test-database", "--edge-workers", "4"])
        source = self.manifest.replace("        send_concurrency: 4", "        send_concurrency: 9", 1)
        def read_source(path, *unused_args, **unused_kwargs):
            return source if path.name == "apisix.yaml" else self.config
        with patch.object(supervisor.sys, "platform", "linux"), patch.object(Path, "read_text", read_source), \
                patch.object(Path, "mkdir") as mkdir, patch.object(Path, "write_text") as write, \
                patch.object(supervisor, "sql") as sql, patch.object(supervisor, "command") as command, \
                patch.object(supervisor.subprocess, "Popen") as popen, patch.object(supervisor.socket, "socket") as socket:
            with self.assertRaisesRegex(ValueError, "EDGE_LOGGER_SOURCE_VALUE_CHANGED"):
                supervisor.serve(args)
            for mocked in (mkdir, write, sql, command, popen, socket):
                mocked.assert_not_called()

    def test_candidate_and_test_python_syntax(self):
        for path in (MODULE_PATH, Path(__file__)):
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))


if __name__ == "__main__":
    unittest.main(verbosity=2)
