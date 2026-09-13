"""Download pinned official ip2region data; runtime never fetches data over the network."""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path

COMMIT = "5244ebad081e9f32b2113bc08e980489f0e6c6fe"  # upstream v3.15.0
REPOSITORY = "https://github.com/lionsoul2014/ip2region"
PINNED = {
    "v4": (10647009, "9f9c76a8bcb234d55be3a8d2e4d828f76624470652ab33697b42e5129a1319ec"),
    "v6": (36088996, "98e8af04c288b16a6a70ca4d0047b54d1e7d51d99f625593f8a843ed6bad331f"),
}


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "shortlink-geo-setup"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read(64 * 1024 * 1024 + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(".work/local-dev/geo"))
    args = parser.parse_args()
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    manifest = {"source": REPOSITORY, "commit": COMMIT, "release": "v3.15.0", "license": "Apache-2.0", "files": {}}
    for family in ("v4", "v6"):
        name = f"ip2region_{family}.xdb"
        expected_size, expected_sha = PINNED[family]
        path = folder / name
        body = path.read_bytes() if path.is_file() else fetch(f"https://raw.githubusercontent.com/lionsoul2014/ip2region/{COMMIT}/data/{name}")
        if len(body) != expected_size or hashlib.sha256(body).hexdigest() != expected_sha:
            raise ValueError(f"Pinned upstream content check failed: {name}")
        if not path.exists():
            path.write_bytes(body)
        manifest["files"][family] = {"name": name, "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    license_data = fetch(f"https://raw.githubusercontent.com/lionsoul2014/ip2region/{COMMIT}/LICENSE.md")
    (folder / "LICENSE.ip2region").write_bytes(license_data)
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
