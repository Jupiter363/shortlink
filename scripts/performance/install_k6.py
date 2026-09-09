"""Install one verified official Linux k6 release into the dedicated test host."""
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
import urllib.request

VERSION = "2.2.0"
DEST = Path("/opt/shortlink-perf/tools")


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent":"shortlink-isolated-performance"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(100 * 1024 * 1024 + 1)
    if len(data) > 100 * 1024 * 1024:
        raise ValueError("Download exceeds tool budget")
    return data


def install():
    if os.environ.get("WSL_DISTRO_NAME") != "shortlink-refactor-it":
        raise RuntimeError("Use the dedicated Linux test host")
    release = json.loads(fetch("https://api.github.com/repos/grafana/k6/releases/tags/v" + VERSION))
    name = "k6-v" + VERSION + "-linux-amd64.tar.gz"
    archive = next(asset for asset in release["assets"] if asset["name"] == name)
    checks = next(asset for asset in release["assets"] if "checksum" in asset["name"].lower())
    checksums = fetch(checks["browser_download_url"]).decode("utf-8")
    expected = next(line.split()[0] for line in checksums.splitlines() if line.split()[-1].lstrip("*") == name)
    payload = fetch(archive["browser_download_url"])
    actual = hashlib.sha256(payload).hexdigest()
    if actual != expected:
        raise RuntimeError("Official checksum mismatch")
    DEST.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as bundle:
        member = bundle.getmember("k6-v" + VERSION + "-linux-amd64/k6")
        binary = bundle.extractfile(member).read()
    target = DEST / ("k6-v" + VERSION)
    target.write_bytes(binary)
    target.chmod(0o755)
    evidence = {"version":VERSION,"source":archive["browser_download_url"],"archiveSha256":actual,
                "binarySha256":hashlib.sha256(binary).hexdigest(),"path":str(target)}
    (DEST / "k6-install.json").write_text(json.dumps(evidence, indent=2))
    print(json.dumps(evidence))


if __name__ == "__main__":
    install()
