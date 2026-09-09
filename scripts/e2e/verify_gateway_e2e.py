"""Verify the real edge; temporary limits only affect the supervisor's work copy.

Run inside the dedicated Linux test host after the public business cases finish.
Original route configuration is restored even when a probe fails. No Agent calls.
"""
import argparse
import json
import pathlib
import time

from gateway_security_cases import (RECOVERY, probe_connection_limit,
                                    probe_rate_limit, probe_restored_configuration,
                                    run_edge_security)
from run_create_redirect_e2e import ROOT, command, request_http, write_json


def verify(state_path):
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if state["phase"] != "READY" or not state["apisix"].startswith("shortlink-e2e-apisix-"):
        raise ValueError("A running isolated E2E supervisor is required")
    folder = pathlib.Path(state["folder"]).resolve()
    folder.relative_to((ROOT / ".work/e2e").resolve())
    manifest = pathlib.Path(state["manifest"]).resolve()
    if manifest != folder / "apisix.yaml":
        raise ValueError("Only the owned work manifest may be changed")
    original = manifest.read_text(encoding="utf-8")
    production = (ROOT / "deploy/apisix/apisix.yaml").read_text(encoding="utf-8")
    if original != production:
        raise ValueError("Work manifest must start with the production routes")
    business = json.loads((folder / "business.json").read_text(encoding="utf-8"))
    short_uri = business["observations"]["headOnlyShortUri"]
    reports = []

    def record(name, report):
        reports.append(report)
        write_json(folder / (name + ".json"), report)
        print(name + " " + ("PASSED" if report["passed"] else "FAILED"), flush=True)

    def install(text):
        # Preserve the mounted inode and allow standalone workers to observe it.
        # Reload only between probes, after every held connection has been closed.
        manifest.write_text(text, encoding="utf-8")
        result = command(["docker", "exec", state["apisix"], "apisix", "reload"], timeout=25)
        with (folder / "apisix-reload.log").open("a", encoding="utf-8") as log:
            log.write(result.stdout + result.stderr)
        time.sleep(3)

    record("gateway-security", run_edge_security(state["baseUrl"], state["managementHost"],
                                                   state["redirectHost"], short_uri))
    rows = []
    for name, port, host, path in (
            ("GS08_direct_gateway_peer_rejected", 8000, state["managementHost"], RECOVERY),
            ("GS09_direct_redirect_peer_rejected", 8003, state["redirectHost"], "/" + short_uri)):
        # Real TCP peer is loopback; forwarded strings cannot grant trust.
        status, _, _ = request_http(port, path, {"Host": host, "X-Forwarded-For": state["apisixIp"],
                                                "X-Forwarded-Proto": "http",
                                                "X-Internal-Token": "forged"})
        rows.append({"id": name, "passed": status == 403, "actual_status": status, "expected_status": 403})
    record("gateway-peer-boundary", {"suite": "untrusted_socket_peer", "passed": all(r["passed"] for r in rows),
                                      "case_count": len(rows), "cases": rows})
    try:
        management_rate = "      limit-req:\n        rate: 100\n        burst: 100\n"
        redirect_rate = "      limit-req:\n        rate: 1000\n        burst: 200\n"
        if original.count(management_rate) != 1 or original.count(redirect_rate) != 1:
            raise ValueError("Production limit layout changed; review probe configuration")
        low_rate = "      limit-req:\n        rate: 2\n        burst: 0\n"
        install(original.replace(management_rate, low_rate))
        record("gateway-management-rate", probe_rate_limit(state["baseUrl"], state["managementHost"], RECOVERY, 200))
        install(original.replace(redirect_rate, low_rate))
        # A valid-shaped missing alias exercises the redirect route without clicking HEAD-only.
        record("gateway-redirect-rate", probe_rate_limit(state["baseUrl"], state["redirectHost"], "/000000000", 404))
        connection = "      limit-conn:\n        conn: 200\n        burst: 100\n"
        if original.count(connection) != 2:
            raise ValueError("Production connection layout changed; review probe configuration")
        install(original.replace(connection, "      limit-conn:\n        conn: 2\n        burst: 0\n", 1))
        record("gateway-edge-connections", probe_connection_limit(state["baseUrl"], state["managementHost"]))
    finally:
        install(original)
    restored = probe_restored_configuration(state["baseUrl"], state["managementHost"], state["redirectHost"])
    restored["production_manifest_matches"] = manifest.read_text(encoding="utf-8") == production
    restored["passed"] = restored["passed"] and restored["production_manifest_matches"]
    record("gateway-config-restored", restored)
    pid = command(["docker", "inspect", "-f", "{{.State.Pid}}", state["apisix"]]).stdout.strip()
    invocation = ["nsenter", "--target", pid, "--net", "/usr/bin/python3", "-B",
                  str(ROOT / "scripts/e2e/gateway_security_cases.py"), "--gateway-admission",
                  state["networkGateway"] + ":8000", "--management-host", state["managementHost"]]
    result = command(invocation, timeout=15, check=False)
    (folder / "gateway-admission-error.log").write_text(result.stderr, encoding="utf-8")
    admission = json.loads(result.stdout)
    admission["process_exit_code"] = result.returncode
    admission["passed"] = admission["passed"] and result.returncode == 0
    record("gateway-admission", admission)
    summary = {"suite": "gateway-real-http", "passed": all(r["passed"] for r in reports),
               "case_count": sum(r["case_count"] for r in reports), "reports": reports,
               "productionManifestRestored": manifest.read_text(encoding="utf-8") == production,
               "scope": {"agent": False, "loadTest": False, "lowThresholdFunctionalChecks": True}}
    write_json(folder / "gateway-summary.json", summary)
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", type=pathlib.Path)
    raise SystemExit(verify(parser.parse_args().state))
