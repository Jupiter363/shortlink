"""Freeze bounded APISIX runtime files on the dedicated Linux filesystem.

Only two rendered YAML files and the normal shortlink Lua plugin tree are copied.
The workspace originals remain evidence. A failed/partial target is deliberately
retained and cannot be reused; verification never repairs changed inputs.
"""

import hashlib
import os
from pathlib import Path
import re
import stat


PRIVATE_BASE = Path("/var/lib/shortlink-perf")
MAX_FILE_BYTES = 1024 * 1024
MAX_PLUGIN_FILES = 64
MAX_TOTAL_BYTES = 8 * 1024 * 1024


def _directory(path):
    _no_symlink_components(path)
    if not stat.S_ISDIR(path.lstat().st_mode):
        raise ValueError("EDGE_RUNTIME_DIRECTORY_REQUIRED")


def _no_symlink_components(path):
    if not path.is_absolute() or ".." in path.parts:
        raise ValueError("EDGE_RUNTIME_ABSOLUTE_PATH_REQUIRED")
    for component in (*reversed(path.parents), path):
        info = component.lstat()
        if (stat.S_ISLNK(info.st_mode)
                or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)):
            raise ValueError("EDGE_RUNTIME_SYMLINK_REJECTED")


def _read_file(path):
    _no_symlink_components(path)
    initial = path.lstat()
    if not stat.S_ISREG(initial.st_mode) or initial.st_size > MAX_FILE_BYTES:
        raise ValueError("EDGE_RUNTIME_REGULAR_BOUNDED_FILE_REQUIRED")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        opened = os.fstat(descriptor)
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            body = stream.read(MAX_FILE_BYTES + 1)
        final = os.fstat(descriptor)
        _no_symlink_components(path)
        after = path.lstat()
        identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
        if (not stat.S_ISREG(opened.st_mode) or len(body) > MAX_FILE_BYTES
                or not (identity(initial) == identity(opened) == identity(final) == identity(after))):
            raise ValueError("EDGE_RUNTIME_SOURCE_CHANGED_DURING_READ")
        return body
    finally:
        os.close(descriptor)


def _plugin_snapshot(root):
    _directory(root)
    expected_directories = {root / "apisix", root / "apisix" / "plugins"}
    files = {}
    stack = [root]
    while stack:
        directory = stack.pop()
        for count, path in enumerate(directory.iterdir(), 1):
            if count > MAX_PLUGIN_FILES + 2:
                raise ValueError("EDGE_RUNTIME_PLUGIN_BUDGET_EXCEEDED")
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode):
                raise ValueError("EDGE_RUNTIME_SYMLINK_REJECTED")
            if stat.S_ISDIR(mode):
                if path not in expected_directories:
                    raise ValueError("EDGE_RUNTIME_PLUGIN_TREE_NOT_ALLOWLISTED")
                stack.append(path)
            elif (path.parent == root / "apisix" / "plugins"
                  and re.fullmatch(r"shortlink-[a-z0-9-]+\.lua", path.name)):
                files[path.relative_to(root).as_posix()] = _read_file(path)
                if len(files) > MAX_PLUGIN_FILES or sum(map(len, files.values())) > MAX_TOTAL_BYTES:
                    raise ValueError("EDGE_RUNTIME_PLUGIN_BUDGET_EXCEEDED")
            else:
                raise ValueError("EDGE_RUNTIME_PLUGIN_TREE_NOT_ALLOWLISTED")
    if not files:
        raise ValueError("EDGE_RUNTIME_PLUGIN_TREE_EMPTY")
    return files


def _digest(body):
    return hashlib.sha256(body).hexdigest()


def _write_file(path, body):
    with path.open("xb") as stream:
        stream.write(body)
        stream.flush()
        os.fsync(stream.fileno())
    path.chmod(0o644)


def _locations(private_dir, run_id, config_source, manifest_source, plugins_source):
    if (not isinstance(run_id, str) or re.fullmatch(r"[A-Za-z0-9_-]{1,80}", run_id) is None
            or private_dir != PRIVATE_BASE / run_id):
        raise ValueError("EDGE_RUNTIME_PRIVATE_RUN_PATH_MISMATCH")
    _directory(private_dir)
    if (config_source.name != "config.yaml" or manifest_source.name != "apisix.yaml"
            or config_source.parent != manifest_source.parent or config_source.parent.name != run_id):
        raise ValueError("EDGE_RUNTIME_SOURCE_RUN_PATH_MISMATCH")
    _directory(config_source.parent)
    _directory(plugins_source)


def prepare_edge_runtime(private_dir, run_id, config_source, manifest_source, plugins_source):
    """Create one immutable-by-contract copy; existing targets always reject."""
    private_dir, config_source, manifest_source, plugins_source = map(
        Path, (private_dir, config_source, manifest_source, plugins_source))
    _locations(private_dir, run_id, config_source, manifest_source, plugins_source)
    inputs = {"config": (config_source, _read_file(config_source)),
              "manifest": (manifest_source, _read_file(manifest_source))}
    plugins = _plugin_snapshot(plugins_source)
    if sum(len(body) for _, body in inputs.values()) + sum(map(len, plugins.values())) > MAX_TOTAL_BYTES:
        raise ValueError("EDGE_RUNTIME_TOTAL_BUDGET_EXCEEDED")
    root = private_dir / "edge-runtime"
    # mkdir is exclusive even for an empty, dangling-symlink or partially copied target.
    root.mkdir(mode=0o755)
    root.chmod(0o755)
    record = {"version": 1, "runId": run_id, "root": str(root)}
    for name, (source, body) in inputs.items():
        destination = root / source.name
        _write_file(destination, body)
        record[name] = {"source": str(source), "path": str(destination),
                        "sha256": _digest(body), "sizeBytes": len(body)}
    destination_plugins = root / "plugins"
    for directory in (destination_plugins, destination_plugins / "apisix", destination_plugins / "apisix/plugins"):
        directory.mkdir(mode=0o755)
        directory.chmod(0o755)
    record["plugins"] = {"source": str(plugins_source), "path": str(destination_plugins), "files": []}
    for relative, body in sorted(plugins.items()):
        destination = destination_plugins / relative
        _write_file(destination, body)
        record["plugins"]["files"].append({"relativePath": relative,
            "source": str(plugins_source / relative), "path": str(destination),
            "sha256": _digest(body), "sizeBytes": len(body)})
    verify_edge_runtime(record)
    return record


def verify_edge_runtime(record):
    """Fail closed on source/copy/content/path/inventory changes; return no secrets."""
    if not isinstance(record, dict) or type(record.get("version")) is not int or record["version"] != 1:
        raise ValueError("EDGE_RUNTIME_RECORD_VERSION_INVALID")
    try:
        root = Path(record["root"])
        config_source, manifest_source = Path(record["config"]["source"]), Path(record["manifest"]["source"])
        plugins_source = Path(record["plugins"]["source"])
        _locations(root.parent, record["runId"], config_source, manifest_source, plugins_source)
        if root.name != "edge-runtime":
            raise ValueError("EDGE_RUNTIME_ROOT_MISMATCH")
        _directory(root)
        expected_children = {"config.yaml", "apisix.yaml", "plugins"}
        if {path.name for path in root.iterdir()} != expected_children:
            raise ValueError("EDGE_RUNTIME_DESTINATION_INVENTORY_CHANGED")
        for name, source in (("config", config_source), ("manifest", manifest_source)):
            _verify_file(record[name], source, root / source.name)
        plugins_root = root / "plugins"
        if record["plugins"]["path"] != str(plugins_root):
            raise ValueError("EDGE_RUNTIME_PLUGIN_PATH_MISMATCH")
        source_files, copied_files = _plugin_snapshot(plugins_source), _plugin_snapshot(plugins_root)
        rows = record["plugins"]["files"]
        if not isinstance(rows, list) or not 1 <= len(rows) <= MAX_PLUGIN_FILES:
            raise ValueError("EDGE_RUNTIME_PLUGIN_INVENTORY_CHANGED")
        recorded_names = [row["relativePath"] for row in rows]
        if (len(set(recorded_names)) != len(recorded_names)
                or set(recorded_names) != set(source_files) or set(source_files) != set(copied_files)):
            raise ValueError("EDGE_RUNTIME_PLUGIN_INVENTORY_CHANGED")
        for row in rows:
            relative = row["relativePath"]
            _verify_file(row, plugins_source / relative, plugins_root / relative)
        total = record["config"]["sizeBytes"] + record["manifest"]["sizeBytes"] + sum(row["sizeBytes"] for row in rows)
        if total > MAX_TOTAL_BYTES:
            raise ValueError("EDGE_RUNTIME_TOTAL_BUDGET_EXCEEDED")
    except (KeyError, TypeError) as error:
        raise ValueError("EDGE_RUNTIME_RECORD_INVALID") from error
    return True


def _verify_file(record, source, destination):
    if record["source"] != str(source) or record["path"] != str(destination):
        raise ValueError("EDGE_RUNTIME_FILE_PATH_MISMATCH")
    if (type(record["sizeBytes"]) is not int or not 0 <= record["sizeBytes"] <= MAX_FILE_BYTES
            or not isinstance(record["sha256"], str)
            or re.fullmatch(r"[a-f0-9]{64}", record["sha256"]) is None):
        raise ValueError("EDGE_RUNTIME_FILE_RECORD_INVALID")
    for path in (source, destination):
        body = _read_file(path)
        if len(body) != record["sizeBytes"] or _digest(body) != record["sha256"]:
            raise ValueError("EDGE_RUNTIME_SOURCE_OR_COPY_CHANGED")
