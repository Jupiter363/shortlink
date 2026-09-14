"""Offline copy/integrity tests; no containers, network, or production mutation."""

import ast
import copy
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import edge_runtime as runtime


class EdgeRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.run_id = "20260913T000000Z-test1234"
        self.native_base = self.root / "native"
        self.private = self.native_base / self.run_id
        self.private.mkdir(parents=True, mode=0o700)
        self.work = self.root / "work" / self.run_id
        self.work.mkdir(parents=True)
        self.config = self.work / "config.yaml"
        self.manifest = self.work / "apisix.yaml"
        self.config.write_bytes(b"nginx_config:\r\n  worker_processes: 4\r\n")
        self.manifest.write_bytes(b"routes: []\n#END\n")
        self.plugins = self.root / "deploy" / "plugins"
        self.lua_dir = self.plugins / "apisix" / "plugins"
        self.lua_dir.mkdir(parents=True)
        self.lua = self.lua_dir / "shortlink-boundary.lua"
        self.lua.write_bytes(b"return {name='test'}\n")
        (self.private / "secretEnv").write_bytes(b"must-never-be-copied")
        self.addCleanup(patch.stopall)
        patch.object(runtime, "PRIVATE_BASE", self.native_base).start()

    def prepare(self):
        return runtime.prepare_edge_runtime(self.private, self.run_id,
                                            self.config, self.manifest, self.plugins)

    def test_copy_preserves_all_source_bytes_paths_and_excludes_secrets(self):
        original = {p: p.read_bytes() for p in (self.config, self.manifest, self.lua)}
        record = self.prepare()
        self.assertTrue(runtime.verify_edge_runtime(record))
        self.assertEqual(str(self.private / "edge-runtime"), record["root"])
        self.assertEqual(str(self.manifest), record["manifest"]["source"])
        self.assertEqual(str(self.config), record["config"]["source"])
        self.assertEqual(str(self.plugins), record["plugins"]["source"])
        for row in (record["config"], record["manifest"], *record["plugins"]["files"]):
            self.assertEqual(original[Path(row["source"])], Path(row["path"]).read_bytes())
            self.assertEqual(original[Path(row["source"])], Path(row["source"]).read_bytes())
            self.assertEqual(len(original[Path(row["source"])]), row["sizeBytes"])
        copied = {p.relative_to(record["root"]).as_posix() for p in Path(record["root"]).rglob("*") if p.is_file()}
        self.assertEqual({"config.yaml", "apisix.yaml", "plugins/apisix/plugins/shortlink-boundary.lua"}, copied)

    def test_existing_target_is_never_overwritten(self):
        record = self.prepare()
        before = Path(record["config"]["path"]).read_bytes()
        with self.assertRaises(FileExistsError):
            self.prepare()
        self.assertEqual(before, Path(record["config"]["path"]).read_bytes())

    def test_source_and_copy_tampering_are_rejected(self):
        record = self.prepare()
        for row in (record["config"], record["manifest"], *record["plugins"]["files"]):
            for field in ("source", "path"):
                with self.subTest(file=row[field]):
                    path = Path(row[field])
                    original = path.read_bytes()
                    path.write_bytes(original + b"--changed")
                    with self.assertRaisesRegex(ValueError, "SOURCE_OR_COPY_CHANGED"):
                        runtime.verify_edge_runtime(record)
                    path.write_bytes(original)

    def test_copy_rejects_source_changed_after_initial_snapshot(self):
        write = runtime._write_file

        def mutate_source(path, body):
            write(path, body)
            if path.name == "config.yaml":
                self.config.write_bytes(b"changed-after-snapshot\n")

        with patch.object(runtime, "_write_file", side_effect=mutate_source):
            with self.assertRaisesRegex(ValueError, "SOURCE_OR_COPY_CHANGED"):
                self.prepare()
        self.assertTrue((self.private / "edge-runtime").is_dir())
        with self.assertRaises(FileExistsError):
            self.prepare()

    def test_plugin_inventory_addition_removal_and_extra_runtime_files_reject(self):
        record = self.prepare()
        for root in (self.plugins, Path(record["plugins"]["path"])):
            added = root / "apisix/plugins/shortlink-added.lua"
            added.write_bytes(b"return {}")
            with self.assertRaisesRegex(ValueError, "INVENTORY_CHANGED"):
                runtime.verify_edge_runtime(record)
            added.unlink()
        path = Path(record["plugins"]["files"][0]["path"])
        original = path.read_bytes()
        path.unlink()
        with self.assertRaises(ValueError):
            runtime.verify_edge_runtime(record)
        path.write_bytes(original)
        extra = Path(record["root"]) / "secretEnv"
        extra.write_bytes(b"unexpected")
        with self.assertRaisesRegex(ValueError, "INVENTORY_CHANGED"):
            runtime.verify_edge_runtime(record)

    def test_invalid_paths_and_record_metadata_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "PRIVATE_RUN_PATH"):
            runtime.prepare_edge_runtime(self.private, "another-run", self.config, self.manifest, self.plugins)
        record = self.prepare()
        mutations = (
            lambda r: r.update(version=True),
            lambda r: r.update(root=str(self.private / "different")),
            lambda r: r["config"].update(path=str(self.manifest)),
            lambda r: r["manifest"].update(source=str(self.config)),
            lambda r: r["config"].update(sha256="0" * 64),
            lambda r: r["config"].update(sizeBytes=True),
            lambda r: r["plugins"].update(path=str(self.plugins)),
            lambda r: r["plugins"].update(files=[]),
            lambda r: r["plugins"].update(files=None),
            lambda r: r["plugins"]["files"].append(copy.deepcopy(r["plugins"]["files"][0])),
            lambda r: r["plugins"]["files"][0].update(relativePath="../../secretEnv"),
        )
        for mutate in mutations:
            changed = copy.deepcopy(record)
            mutate(changed)
            with self.assertRaises((ValueError, FileNotFoundError)):
                runtime.verify_edge_runtime(changed)

    def test_symlink_components_and_plugins_are_rejected(self):
        # Simulated lstat is portable to Windows hosts without symlink privilege.
        # It drives the real validation before any open/copy follows the target.
        original = Path.lstat
        for forbidden in (self.private, self.config, self.plugins, self.lua_dir, self.lua):
            def lstat(path, *args, **kwargs):
                if path == forbidden:
                    return SimpleNamespace(st_mode=stat.S_IFLNK | 0o777)
                return original(path, *args, **kwargs)
            with self.subTest(path=forbidden), patch.object(Path, "lstat", lstat):
                with self.assertRaisesRegex(ValueError, "SYMLINK_REJECTED"):
                    self.prepare()
            self.assertFalse((self.private / "edge-runtime").exists())

    def test_nonregular_plugin_is_rejected_before_open(self):
        original = Path.lstat

        def lstat(path, *args, **kwargs):
            if path == self.lua:
                return SimpleNamespace(st_mode=stat.S_IFIFO | 0o600, st_size=0)
            return original(path, *args, **kwargs)

        with patch.object(Path, "lstat", lstat):
            with self.assertRaisesRegex(ValueError, "REGULAR_BOUNDED_FILE"):
                self.prepare()
        self.assertFalse((self.private / "edge-runtime").exists())

    def test_plugin_tree_rejects_unapproved_files_and_directories(self):
        for path in (self.plugins / "secretEnv", self.lua_dir / "credential.txt"):
            path.write_bytes(b"not-lua")
            with self.assertRaisesRegex(ValueError, "NOT_ALLOWLISTED"):
                self.prepare()
            path.unlink()
        (self.plugins / "other").mkdir()
        with self.assertRaisesRegex(ValueError, "NOT_ALLOWLISTED"):
            self.prepare()

    def test_bounded_file_and_plugin_count(self):
        with patch.object(runtime, "MAX_FILE_BYTES", 2):
            with self.assertRaisesRegex(ValueError, "BOUNDED_FILE"):
                self.prepare()
        (self.lua_dir / "shortlink-second.lua").write_bytes(b"return {}")
        with patch.object(runtime, "MAX_PLUGIN_FILES", 1):
            with self.assertRaisesRegex(ValueError, "PLUGIN_BUDGET"):
                self.prepare()
        with patch.object(runtime, "MAX_TOTAL_BYTES", 30):
            with self.assertRaisesRegex(ValueError, "BUDGET"):
                self.prepare()

    def test_supervisor_preserves_original_manifest_and_mounts_only_runtime_paths(self):
        source = Path(__file__).with_name("supervisor.py").read_text(encoding="utf-8-sig")
        tree = ast.parse(source)
        docker_runs = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                       and isinstance(node.func, ast.Name) and node.func.id == "command"
                       and node.args and isinstance(node.args[0], ast.List)
                       and [x.s for x in node.args[0].elts[:2] if isinstance(x, ast.Str)] == ["docker", "run"]]
        self.assertEqual(1, len(docker_runs))
        mount_sources = [docker_runs[0].args[0].elts[index + 1]
                         for index, element in enumerate(docker_runs[0].args[0].elts)
                         if isinstance(element, ast.Str) and element.s == "-v"]
        expected = [f'state["edgeRuntimeFiles"]["{key}"]["path"] + ":{destination}:ro"'
                    for key, destination in (("config", "/usr/local/apisix/conf/config.yaml"),
                                             ("manifest", "/usr/local/apisix/conf/apisix.yaml"),
                                             ("plugins", "/opt/shortlink"))]
        self.assertEqual([ast.dump(ast.parse(value, mode="eval").body) for value in expected],
                         [ast.dump(node) for node in mount_sources])
        manifest_fields = [kw.value for node in ast.walk(tree) if isinstance(node, ast.Call)
                           for kw in node.keywords if kw.arg == "manifest"]
        self.assertEqual([ast.dump(ast.parse("str(manifest_path)", mode="eval").body)],
                         [ast.dump(node) for node in manifest_fields])


if __name__ == "__main__":
    unittest.main()
