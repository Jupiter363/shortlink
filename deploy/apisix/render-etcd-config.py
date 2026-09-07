"""Merge the tested local plugin/budget configuration with the production etcd overlay."""
import argparse
import ipaddress
import os
import pathlib
import re
import urllib.parse
import yaml

ROOT = pathlib.Path(__file__).resolve().parent


def required(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError("Required setting missing: " + name)
    return value


def secret(name, minimum=1, maximum=4096):
    path = pathlib.Path(required(name))
    if not path.is_absolute() or path.stat().st_size > maximum:
        raise ValueError("Invalid secret file: " + name)
    value = path.read_text(encoding="utf-8").strip()
    if len(value) < minimum or any(c in value for c in "\r\n"):
        raise ValueError("Invalid secret length/format: " + name)
    return value


def resolve(value):
    if isinstance(value, str):
        return re.sub(r"\$\{\{([A-Z0-9_]+)\}\}", lambda m: required(m[1]), value)
    if isinstance(value, list):
        return [resolve(item) for item in value]
    if isinstance(value, dict):
        return {key: resolve(item) for key, item in value.items()}
    return value


def merge(target, overlay):
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            merge(target[key], value)
        else:
            target[key] = value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, help="Private runtime directory")
    args = parser.parse_args()
    base = yaml.safe_load((ROOT / "config.yaml").read_text(encoding="utf-8"))
    base.pop("deployment", None)
    merge(base, resolve(yaml.safe_load((ROOT / "config-etcd.template.yaml").read_text(encoding="utf-8"))))
    ipaddress.ip_address(required("APISIX_ADMIN_BIND_IP"))
    allowed = [str(ipaddress.ip_network(v.strip(), strict=False))
               for v in required("APISIX_ADMIN_ALLOWED_CIDRS").split(",")]
    if any(ipaddress.ip_network(v).prefixlen == 0 for v in allowed):
        raise ValueError("Admin API cannot allow the entire Internet")
    admin = base["deployment"]["admin"]
    admin["allow_admin"] = allowed
    admin["admin_key"] = [{"name": "shortlink-bootstrap", "role": "admin",
                           "key": secret("APISIX_ADMIN_KEY_FILE", 32)}]
    encryption = secret("APISIX_ENCRYPTION_KEY_FILE", 16, 64)
    if len(encryption.encode("utf-8")) != 16:
        raise ValueError("APISIX AES encryption key must be exactly 16 UTF-8 bytes")
    base["apisix"]["data_encryption"]["keyring"] = [encryption]
    etcd = base["deployment"]["etcd"]
    etcd["host"] = [v.strip() for v in required("APISIX_ETCD_ENDPOINTS").split(",")]
    for endpoint in etcd["host"]:
        url = urllib.parse.urlsplit(endpoint)
        if url.scheme != "https" or not url.hostname or url.username or url.query or url.fragment or url.path not in ("", "/"):
            raise ValueError("etcd requires explicit HTTPS endpoints without inline credentials")
    etcd["password"] = secret("APISIX_ETCD_PASSWORD_FILE")
    for key in ("APISIX_ETCD_CA_FILE", "APISIX_ADMIN_CERT_FILE", "APISIX_ADMIN_TLS_KEY_FILE"):
        # These are paths inside the APISIX runtime container, not local renderer paths.
        if not pathlib.PurePosixPath(required(key)).is_absolute():
            raise ValueError("Container secret mount must be absolute: " + key)
    output = pathlib.Path(args.output).resolve()
    if output == ROOT or ROOT in output.parents:
        raise ValueError("Use a private runtime directory outside deploy/apisix")
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = output / "config.yaml"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        yaml.safe_dump(base, stream, sort_keys=False)
    os.chmod(path, 0o600)
    print("Rendered traditional etcd configuration; private settings were not printed.")


if __name__ == "__main__":
    main()
