"""Import the common route/plugin/TLS manifest over authenticated, verified HTTPS.

No etcd writes, deletes, wildcard resources, redirects, or TLS verification bypass.
Each stable resource id is PUT then read back. A partial run is safe to rerun;
operators keep public admission closed until the complete manifest is verified.
"""
import argparse
import hashlib
import http.client
import json
import os
import pathlib
import re
import ssl
import urllib.parse
import yaml


def resolve(value):
    if isinstance(value, str):
        def replace(match):
            result = os.environ.get(match[1], "").strip()
            if not result:
                raise ValueError("Missing manifest setting: " + match[1])
            return result
        return re.sub(r"\$\{\{([A-Z0-9_]+)\}\}", replace, value)
    if isinstance(value, list):
        return [resolve(item) for item in value]
    if isinstance(value, dict):
        return {key: resolve(item) for key, item in value.items()}
    return value


def contains(actual, expected):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(key in actual and contains(actual[key], value) for key, value in expected.items())
    if isinstance(expected, list):
        return isinstance(actual, list) and len(actual) == len(expected) and all(contains(a, b) for a, b in zip(actual, expected))
    return actual == expected


def validate_limiter_keys(manifest):
    """Keep management/redirect scope explicit, including the protected chat route."""
    expected = {"shortlink-management": "shortlink-management:$remote_addr",
                "shortlink-agent-chat": "shortlink-management:$remote_addr",
                "shortlink-redirect": "shortlink-redirect:$remote_addr"}
    found = set()
    for route in manifest.get("routes", []):
        identity = route.get("id")
        if identity not in expected:
            continue
        if identity in found:
            raise ValueError("Duplicate common route identity")
        found.add(identity)
        for name in ("limit-req", "limit-conn"):
            limit = route.get("plugins", {}).get(name, {})
            if limit.get("key_type") != "var_combination" or limit.get("key") != expected[identity]:
                raise ValueError("Explicit route-scoped limiter key required: " + identity + "/" + name)
    if found != set(expected):
        raise ValueError("All common routes with explicit limiter keys are required")


def validate_agent_chat_route(manifest):
    """The long read budget must only apply to protected, exact-path Agent chat."""
    routes = {route.get("id"): route for route in manifest.get("routes", [])}
    management = routes.get("shortlink-management", {})
    chat = routes.get("shortlink-agent-chat", {})
    if (chat.get("uri") != "/api/short-link/admin/v1/agent/chat" or "uris" in chat
            or not isinstance(chat.get("priority"), (int, float))
            or chat["priority"] <= management.get("priority", 100)
            or not chat.get("hosts") or chat["hosts"] != management.get("hosts")):
        raise ValueError("Exact chat path, management Host and higher priority are required")
    plugins = chat.get("plugins", {})
    if (plugins != management.get("plugins")
            or plugins.get("shortlink-boundary", {}).get("mode") != "management"
            or plugins.get("proxy-control", {}).get("request_buffering") is not False):
        raise ValueError("Chat must retain every management boundary, proxy and limiter setting")
    expected_upstream = dict(management.get("upstream", {}))
    expected_upstream["timeout"] = {"connect": 1, "send": 3, "read": 50}
    if chat.get("upstream") != expected_upstream or chat["upstream"].get("retries") != 0:
        raise ValueError("Chat must retain the management upstream with a 50s read budget and no retries")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, help="TLS manifest generated from the common apisix.yaml")
    parser.add_argument("--admin-url", required=True, help="https://<management-only hostname>:9180")
    parser.add_argument("--ca-file", required=True)
    parser.add_argument("--key-file", required=True, help="Admin API key secret file")
    parser.add_argument("--report", required=True, help="Non-secret result and manifest digest")
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.admin_url)
    if url.scheme != "https" or not url.hostname or url.username or url.path not in ("", "/") or url.query or url.fragment:
        raise ValueError("A direct HTTPS management endpoint is required")
    context = ssl.create_default_context(cafile=args.ca_file)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    key = pathlib.Path(args.key_file).read_text(encoding="utf-8").strip()
    if not 32 <= len(key) <= 4096 or "\n" in key or "\r" in key:
        raise ValueError("Invalid Admin API key file")
    manifest_path = pathlib.Path(args.manifest)
    if manifest_path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Manifest exceeds import budget")
    manifest = resolve(yaml.safe_load(manifest_path.read_text(encoding="utf-8")))
    if set(manifest) - {"routes", "global_rules", "ssls"} or not all(manifest.get(k) for k in ("routes", "global_rules", "ssls")):
        raise ValueError("Expected exactly the common routes/global_rules plus generated TLS resources")
    validate_limiter_keys(manifest)
    validate_agent_chat_route(manifest)
    resources = []
    for kind in ("global_rules", "ssls", "routes"):
        seen = set()
        for item in manifest[kind]:
            resource_id = str(item.get("id", ""))
            if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,64}", resource_id) or resource_id in seen:
                raise ValueError("Invalid or duplicate resource id")
            seen.add(resource_id)
            if kind == "routes" and (not item.get("hosts") or any("*" in host for host in item["hosts"])):
                raise ValueError("Explicit route hosts required")
            resources.append((kind, resource_id, item))
    if len(resources) > 1000:
        raise ValueError("Manifest resource budget exceeded")

    def exchange(method, path, payload=None):
        connection = http.client.HTTPSConnection(url.hostname, url.port or 443, timeout=5, context=context)
        try:
            body = None if payload is None else json.dumps(payload, separators=(",", ":"))
            connection.request(method, "/apisix/admin/" + path, body,
                               {"X-API-KEY": key, "Content-Type": "application/json"})
            response = connection.getresponse()
            data = response.read(2 * 1024 * 1024 + 1)
            if response.status not in (200, 201) or len(data) > 2 * 1024 * 1024:
                # Do not print response bodies: SSL resources can contain private keys.
                raise RuntimeError("Admin API " + method + " " + path + " status=" + str(response.status))
            return json.loads(data)
        finally:
            connection.close()

    results = []
    for kind, resource_id, item in resources:
        path = kind + "/" + resource_id
        exchange("PUT", path, item)
        actual = exchange("GET", path).get("value", {})
        expected = {k: v for k, v in item.items() if k != "id" and not (kind == "ssls" and k == "key")}
        if not contains(actual, expected):
            raise RuntimeError("Read-back mismatch: " + path)
        results.append({"resource": path, "verified": True})
    report = {"manifestSha256": hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest(),
              "resources": results, "tlsVerified": True}
    pathlib.Path(args.report).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("Imported and verified " + str(len(results)) + " resources over HTTPS.")


if __name__ == "__main__":
    main()
