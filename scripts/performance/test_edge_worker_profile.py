"""Offline worker-profile tests; no supervisor serve or runtime is started.

Run with Python -B. Load the adjacent supervisor.py, read public deployment
sources and mock every write/process boundary in serve wiring checks.
"""
import ast
import contextlib
import importlib.util
import io
from pathlib import Path
import re
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("supervisor.py")
module_spec = importlib.util.spec_from_file_location("edge_worker_supervisor_under_test", str(MODULE_PATH))
supervisor = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(supervisor)


class EdgeWorkerProfileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = (supervisor.ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8-sig")
        cls.config = (supervisor.ROOT / "deploy/apisix/config.yaml").read_text(encoding="utf-8")

    def render(self, workers=2, manifest=None, config=None, send_concurrency=1):
        return supervisor.render_edge_worker_configuration(
            self.manifest if manifest is None else manifest,
            self.config if config is None else config, workers, send_concurrency)

    def test_cli_defaults_and_independent_sender_choices(self):
        self.assertEqual(supervisor.parse_args([]).edge_send_concurrency, 1)
        for workers in (2, 4, 8):
            for concurrency in (1, 2, 4):
                args = supervisor.parse_args(["--edge-workers", str(workers),
                                              "--edge-send-concurrency", str(concurrency)])
                self.assertEqual((args.edge_workers, args.edge_send_concurrency), (workers, concurrency))

    def test_cli_rejects_other_sender_counts_without_serve(self):
        with patch.object(supervisor, "serve") as serve:
            for value in ("0", "3", "8", "-1", "true", "1.0"):
                with self.subTest(value=value), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    supervisor.parse_args(["--edge-send-concurrency", value])
            serve.assert_not_called()

    def test_programmatic_sender_choices_require_exact_int(self):
        for value in (None, True, False, 1.0, 2.0, 4.0, "1", 0, 3, 8, -1):
            with self.subTest(value=value), self.assertRaises(ValueError):
                supervisor.edge_worker_profile(8, value)
            with self.subTest(render=value), self.assertRaises(ValueError):
                self.render(8, send_concurrency=value)

    def test_all_worker_sender_combinations_change_only_sender_line(self):
        for workers in (2, 4, 8):
            default_manifest, default_config, default_profile = self.render(workers)
            self.assertEqual(supervisor.render_edge_worker_configuration(self.manifest, self.config, workers),
                             (default_manifest, default_config, default_profile))
            for concurrency in (1, 2, 4):
                with self.subTest(workers=workers, concurrency=concurrency):
                    manifest, config, profile = self.render(workers, send_concurrency=concurrency)
                    self.assertEqual(manifest, default_manifest.replace("        send_concurrency: 1\n",
                                                                       "        send_concurrency: %d\n" % concurrency, 1))
                    self.assertEqual(config, default_config)
                    self.assertEqual(profile, dict(default_profile, edgeSendConcurrencyPerWorker=concurrency,
                                                   edgeSenderSlotsNode=workers * concurrency))
                    self.assertEqual(profile["edgeQueueCountNode"], 2000)
                    self.assertEqual(profile["edgeQueueBytesNode"], 16 * 1024 * 1024)

    def test_selected_sender_value_does_not_relax_pinned_source_value(self):
        for concurrency in (2, 4):
            source = self.manifest.replace("        send_concurrency: 1", "        send_concurrency: %d" % concurrency, 1)
            with self.subTest(concurrency=concurrency), self.assertRaisesRegex(ValueError, "EDGE_LOGGER_SOURCE_VALUE_CHANGED"):
                self.render(8, manifest=source, send_concurrency=concurrency)

    def test_sender_replacement_preserves_comments_crlf_and_other_text(self):
        source = self.manifest.replace("send_concurrency: 1", "send_concurrency: 1 # retained comment").replace("\n", "\r\n")
        for concurrency in (1, 2):
            manifest, config, _ = self.render(2, manifest=source, config=self.config.replace("\n", "\r\n"),
                                               send_concurrency=concurrency)
            self.assertEqual(manifest, source.replace("send_concurrency: 1", "send_concurrency: %d" % concurrency, 1))
            self.assertNotIn("\n", config.replace("\r\n", ""))

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
        for workers, per_count, per_bytes, slots in ((2, 1000, 8388608, 2), (4, 500, 4194304, 4),
                                                   (8, 250, 2097152, 8)):
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
                self.assertEqual(profile["edgeSendConcurrencyPerWorker"], 1)
                self.assertEqual(profile["edgeSenderSlotsNode"], slots)
                self.assertEqual(profile["edgeSendBatchSize"], 32)
                self.assertEqual(profile["edgeSendBatchBytes"], 65536)

    def test_default_two_workers_preserves_deployed_source_exactly(self):
        manifest, config, _ = self.render(2)
        self.assertEqual(manifest, self.manifest)
        self.assertEqual(config, self.config)

    def test_four_workers_changes_only_two_queue_values_and_worker_directive(self):
        manifest, config, _ = self.render(4)
        expected = self.manifest.replace("        queue_count: 1000\n", "        queue_count: 500\n", 1)
        expected = expected.replace("        queue_bytes: 8388608\n", "        queue_bytes: 4194304\n", 1)
        self.assertEqual(manifest, expected)
        self.assertEqual(config, self.config.replace("  worker_processes: 2\n", "  worker_processes: 4\n", 1))
        self.assertEqual(re.findall(r"\$\{\{[^}]+\}\}", manifest),
                         re.findall(r"\$\{\{[^}]+\}\}", self.manifest))

    def test_eight_workers_only_change_queue_values_and_worker_directive(self):
        manifest, config, profile = self.render(8)
        expected = self.manifest.replace("        queue_count: 1000\n", "        queue_count: 250\n", 1)
        expected = expected.replace("        queue_bytes: 8388608\n", "        queue_bytes: 2097152\n", 1)
        self.assertEqual(manifest, expected)
        self.assertEqual(config, self.config.replace("  worker_processes: 2\n", "  worker_processes: 8\n", 1))
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
                    "send_concurrency": 1, "send_batch_size": 32, "send_batch_bytes": 65536}
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

    def test_worker_source_requires_unique_literal_integer_two_in_nginx_block(self):
        line = "  worker_processes: 2\n"
        bad_configs = [
            self.config.replace(line, "", 1),
            self.config.replace(line, line + line, 1),
            self.config.replace(line, '  "worker_processes": 2\n', 1),
            self.config.replace(line, "  'worker_processes': 2\n", 1),
            self.config.replace(line, "    worker_processes: 2\n", 1),
            self.config.replace(line, "\tworker_processes: 2\n", 1),
            self.config.replace(line, "", 1) + "\nother:\n  worker_processes: 2\n",
            self.config.replace(line, "", 1).replace("  http:\n", "  http:\n    worker_processes: 2\n", 1),
            self.config + '\n"nginx_config": {}\n',
            self.config + "\nother: {nginx_config: {worker_processes: 2}}\n",
            self.config + '\nother: {"worker_processes": 2}\n',
            self.config.replace("nginx_config:\n", '"nginx_config":\n', 1),
            self.config.replace("nginx_config:\n", "  nginx_config:\n", 1),
        ]
        for config in bad_configs:
            for workers in (2, 4, 8):
                with self.subTest(config=config[-100:], workers=workers), self.assertRaises(ValueError):
                    self.render(workers, config=config)

    def test_selected_worker_does_not_relax_pinned_source_or_accept_yaml_aliases(self):
        line = "  worker_processes: 2\n"
        for value in ("auto", "1", "4", "8", "true", "false", "null", "~", "2.0", "02", "+2",
                      '"2"', "'2'", "*workers", "&workers 2", "[2]", "{value: 2}"):
            config = self.config.replace(line, "  worker_processes: " + value + "\n", 1)
            for workers in (2, 4, 8):
                with self.subTest(source=value, selected=workers), self.assertRaises(ValueError):
                    self.render(workers, config=config)
        for config in (
            self.config.replace("nginx_config:\n", "nginx_config: &settings\n", 1),
            self.config.replace(line, "  <<: *settings\n" + line, 1),
            self.config + "\nother: &settings {}\n",
            self.config + "\n*settings: {}\n",
        ):
            with self.subTest(config=config[-100:]), self.assertRaises(ValueError):
                self.render(4, config=config)

    def test_worker_comments_crlf_and_other_bytes_are_preserved(self):
        source = "# worker_processes: auto; nginx_config: example\n" + self.config
        source = source.replace("worker_processes: 2\n", "worker_processes: 2 # retained\n", 1)
        for ending in ("\n", "\r\n"):
            config = source.replace("\n", ending)
            for workers in (2, 4, 8):
                with self.subTest(ending=ending, workers=workers):
                    _, rendered, _ = self.render(workers, config=config)
                    self.assertEqual(rendered, config.replace("worker_processes: 2 #", "worker_processes: %d #" % workers, 1))

    def test_invalid_worker_source_stops_serve_before_any_write_or_runtime(self):
        args = supervisor.parse_args(["--allow-test-database"])
        config = self.config.replace("worker_processes: 2", "worker_processes: auto", 1)
        def read_source(path, *unused_args, **unused_kwargs):
            return self.manifest if path.name == "apisix.yaml" else config
        with patch.object(supervisor.sys, "platform", "linux"), patch.object(Path, "read_text", read_source), \
                patch.object(Path, "mkdir") as mkdir, patch.object(Path, "write_text") as write, \
                patch.object(supervisor, "sql") as sql, patch.object(supervisor, "command") as command, \
                patch.object(supervisor.subprocess, "Popen") as popen, patch.object(supervisor.socket, "socket") as socket:
            with self.assertRaisesRegex(ValueError, "EDGE_NGINX_WORKER_SOURCE_VALUE_CHANGED"):
                supervisor.serve(args)
            for mocked in (mkdir, write, sql, command, popen, socket):
                mocked.assert_not_called()

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
        source = self.manifest.replace("        send_concurrency: 1", "        send_concurrency: 9", 1)
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

    def test_serve_passes_selected_or_legacy_default_sender_before_any_runtime(self):
        original_renderer = supervisor.render_edge_worker_configuration
        cases = [(workers, concurrency) for workers in (2, 4, 8) for concurrency in (1, 2, 4)] + [(8, None)]
        for workers, concurrency in cases:
            args = supervisor.parse_args(["--allow-test-database", "--edge-workers", str(workers)])
            if concurrency is None:
                del args.edge_send_concurrency  # Existing programmatic callers remain compatible.
            else:
                args.edge_send_concurrency = concurrency
            selected = 1 if concurrency is None else concurrency
            def read_source(path, *unused_args, **unused_kwargs):
                return self.manifest if path.name == "apisix.yaml" else self.config
            with self.subTest(workers=workers, concurrency=concurrency), \
                    patch.object(supervisor.sys, "platform", "linux"), patch.object(Path, "read_text", read_source), \
                    patch.object(supervisor, "render_edge_worker_configuration", wraps=original_renderer) as renderer, \
                    patch.object(Path, "mkdir", side_effect=RuntimeError("OFFLINE_STOP_BEFORE_RUN_CREATION")) as mkdir, \
                    patch.object(Path, "write_text") as write, patch.object(supervisor, "sql") as sql, \
                    patch.object(supervisor, "command") as command, patch.object(supervisor.subprocess, "Popen") as popen, \
                    patch.object(supervisor.socket, "socket") as socket:
                with self.assertRaisesRegex(RuntimeError, "OFFLINE_STOP_BEFORE_RUN_CREATION"):
                    supervisor.serve(args)
                renderer.assert_called_once_with(self.manifest, self.config, workers, selected)
                mkdir.assert_called_once()
                for mocked in (write, sql, command, popen, socket):
                    mocked.assert_not_called()

    def test_actual_runtime_state_profile_records_selected_sender_counts(self):
        tree = ast.parse(MODULE_PATH.read_text(encoding="utf-8"))
        serve = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "serve")
        state = next(node for node in serve.body if isinstance(node, ast.Assign)
                     and any(isinstance(target, ast.Name) and target.id == "state" for target in node.targets))
        resource_profile = next(keyword.value for keyword in state.value.keywords if keyword.arg == "resourceProfile")
        expression = ast.Expression(body=resource_profile)
        ast.fix_missing_locations(expression)
        for workers in (2, 4, 8):
            for concurrency in (1, 2, 4):
                args = supervisor.parse_args(["--edge-workers", str(workers), "--edge-send-concurrency", str(concurrency)])
                _, _, edge_profile = self.render(workers, send_concurrency=concurrency)
                with self.subTest(workers=workers, concurrency=concurrency), \
                        patch.object(supervisor.os, "cpu_count", return_value=16):
                    profile = eval(compile(expression, str(MODULE_PATH), "eval"),
                                   {"args": args, "edge_profile": edge_profile, "os": supervisor.os})
                    self.assertEqual(profile["edgeSendConcurrencyPerWorker"], concurrency)
                    self.assertEqual(profile["edgeSenderSlotsNode"], workers * concurrency)
                    self.assertEqual(profile["edgeQueueCountNode"], 2000)
                    self.assertEqual(profile["edgeQueueBytesNode"], 16 * 1024 * 1024)
                    self.assertEqual(profile["javaCpuSet"], "0-7")
                    self.assertEqual(profile["edgeCpuSet"], "0-7" if workers == 8 else "4-7")

    def test_candidate_and_test_python_syntax(self):
        for path in (MODULE_PATH, Path(__file__)):
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))


if __name__ == "__main__":
    unittest.main(verbosity=2)
