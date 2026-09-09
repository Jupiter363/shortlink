"""Prepare isolated performance fixtures through real account/create APIs.

This is preparation, not a benchmark. Run only after the performance supervisor
reports READY. Registration/login/default-group discovery use public APISIX;
seed writes use Admin's real create APIs with the supervisor's internal identity.
MySQL access is restricted to read-only SELECT transactions in the named new
performance schema. No direct link inserts, ID resets, Redis flushes, Agent,
Analytics, or external target requests are performed.

The raw fixture (including the internal credential) is written exclusively under
/var/lib/shortlink-perf/<runId> with verified mode 0600. Only a redacted summary
is written to the workspace. Target .local hosts are denied by FetchPolicy before
DNS/network fetch; FAILED metadata is expected and explicitly reported.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
from typing import Any
from urllib.parse import urlsplit
import uuid


_PREFIX = "/api/short-link/admin/v1"
_TARGET_ORIGIN = "https://shortlink-perf.local"
_METADATA_MODE = "local-host-policy-rejection"
_MAX_SEED_ROWS = 200_000
_MIN_MUTATION_ROWS = 256
_MIN_MUTATION_ROWS_PER_ACCOUNT = 64
_BATCH_SIZE = 500
_MAX_DRAIN_SECONDS = 300
_MAX_HTTP_BODY = 2 * 1024 * 1024
_SQL_TABLES = {"t_link_route", "t_metadata_job", "t_outbox", "t_account_identity"} | {
    "t_user_" + str(index) for index in range(16)
}


class PreparationError(RuntimeError):
    """Messages are deliberately safe for a public summary."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PreparationError(message)


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        raise PreparationError("Required JSON input is missing or malformed") from None


class FixturePreparer:
    def __init__(self, state_path: Path, link_count: int, account_count: int):
        require(os.environ.get("WSL_DISTRO_NAME") == "shortlink-refactor-it",
                "Prepare only inside the existing shortlink-refactor-it WSL distribution")
        require(1 <= account_count <= 100, "Account count must be in 1..100")
        require(link_count >= account_count * 2, "Each account needs at least two primary links")
        self.mutation_count = max(_MIN_MUTATION_ROWS, account_count * _MIN_MUTATION_ROWS_PER_ACCOUNT)
        require(link_count + self.mutation_count + account_count <= _MAX_SEED_ROWS,
                "Primary, mutation and idempotent seed rows together exceed the 200000-row budget")
        self.state_path = state_path.resolve(strict=True)
        self.state = _read_json(self.state_path)
        require(isinstance(self.state, dict) and self.state.get("phase") == "READY",
                "Performance supervisor must report READY before preparation")
        self.run_id = self.state.get("runId", "")
        require(bool(re.fullmatch(r"[A-Za-z0-9_-]{1,80}", self.run_id)), "Invalid isolated run ID")
        self.folder = Path(self.state.get("folder", "")).resolve(strict=True)
        allowed_workspace = Path(__file__).resolve().parents[2] / ".work" / "performance"
        require(self.folder.parent == allowed_workspace.resolve() and self.folder.name == self.run_id
                and self.state_path == self.folder / "state.json", "State is outside the performance run directory")
        self.database = self.state.get("database", "")
        require(bool(re.fullmatch(r"shortlink_perf_[a-f0-9]{10,32}", self.database)),
                "Only a newly named shortlink_perf_ schema is permitted")
        private_dir = Path(self.state.get("privateDir", "")).resolve(strict=True)
        require(private_dir == Path("/var/lib/shortlink-perf") / self.run_id,
                "Private directory must match /var/lib/shortlink-perf/<runId>")
        require(stat.S_IMODE(private_dir.stat().st_mode) == 0o700,
                "Private performance directory must have mode 0700")
        self.fixture_path = Path(self.state.get("fixturePath", "")).absolute()
        require(self.fixture_path == private_dir / "fixture.json" and not self.fixture_path.is_symlink(),
                "Fixture output must be the private run's fixture.json")
        observer_path = Path(self.state.get("observerSecret", str(self.folder / "observer-secret.json")))
        require(observer_path.resolve() in {private_dir / "observer-secret.json", self.folder / "observer-secret.json"},
                "Observer secret path is outside the isolated run")
        observer = _read_json(observer_path)
        self.internal_token = observer.get("internalToken")
        self.mysql_user = observer.get("mysqlUser")
        self.mysql_password = observer.get("mysqlPassword")
        require(isinstance(self.internal_token, str) and len(self.internal_token) >= 32,
                "Internal observer credential is missing")
        require(isinstance(self.mysql_user, str) and bool(re.fullmatch(r"[A-Za-z0-9_]+", self.mysql_user))
                and isinstance(self.mysql_password, str) and bool(self.mysql_password),
                "Read-only MySQL observer credentials are missing")
        self.mysql_container = self.state.get("mysqlContainer", "")
        require(self.mysql_container == "shortlink-refactor-it-mysql-1", "Unexpected MySQL container")
        require(self.state.get("managementHost") == "admin.perf.test"
                and self.state.get("redirectHost") == "s.perf.test", "Unexpected performance virtual hosts")
        self.public_origin = urlsplit(self.state.get("baseUrl", ""))
        require(self.public_origin.scheme == "http" and self.public_origin.hostname == "127.0.0.1"
                and self.public_origin.port == 19080 and self.public_origin.path in ("", "/")
                and not self.public_origin.query and not self.public_origin.fragment
                and self.public_origin.username is None and self.public_origin.password is None,
                "Public preparation requests must target the isolated loopback APISIX port 19080")
        require(self.state.get("agentsStarted") is False and self.state.get("analyticsStarted") is False,
                "Fixture preparation scope excludes Agent and Analytics")
        require(self.state.get("metadataMode") == _METADATA_MODE
                and type(self.state.get("metadataPollMillis")) is int
                and self.state["metadataPollMillis"] > 0, "Explicit metadata rejection profile is required")
        self.link_count = link_count
        self.account_count = account_count
        self.seed_id = uuid.uuid4().hex
        self.http_attempts = 0
        self.http_successes = 0
        self.sql_reads = 0
        self.batch_count = 0
        self.created_count = 0
        self.accounts: list[dict[str, Any]] = []
        self.links: list[dict[str, Any]] = []
        self.mutations: list[dict[str, Any]] = []
        self.drain_observations: list[dict[str, Any]] = []
        self.all_ids: set[int] = set()
        self.start = time.monotonic()
        self.started_at = datetime.now(timezone.utc).isoformat()
        self.summary_path = self.folder / "fixture-summary.json"

    def sql(self, statement: str) -> list[list[str]]:
        require(statement.startswith("SELECT ") and ";" not in statement and "--" not in statement
                and not re.search(r"\b(INTO|FOR\s+UPDATE|OUTFILE|DUMPFILE|SLEEP|BENCHMARK)\b", statement, re.I),
                "Observer SQL is not a permitted SELECT")
        tables = re.findall(r"\b(?:FROM|JOIN)\s+([A-Za-z0-9_]+)", statement, re.I)
        require(all(table in _SQL_TABLES for table in tables), "Observer SQL accesses a non-allowlisted table")
        environment = dict(os.environ, MYSQL_PWD=self.mysql_password)
        command = ["docker", "exec", "-i", "--env", "MYSQL_PWD", self.mysql_container,
                   "mysql", "--user=" + self.mysql_user, "--batch", "--raw", "--skip-column-names",
                   "--default-character-set=utf8mb4", "--connect-timeout=5", "--database=" + self.database]
        try:
            result = subprocess.run(command, input="START TRANSACTION READ ONLY;\n" + statement + ";\nCOMMIT;\n",
                                    text=True, encoding="utf-8", capture_output=True, env=environment,
                                    timeout=15, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise PreparationError("Read-only MySQL observation failed or exceeded 15 seconds") from None
        self.sql_reads += 1
        require(result.returncode == 0, "Read-only MySQL query failed")
        require(len(result.stdout) <= 4 * 1024 * 1024, "Read-only MySQL result exceeded its bounded budget")
        return [line.split("\t") for line in result.stdout.splitlines()]

    def request(self, method: str, path: str, payload: dict | None = None,
                account: dict | None = None, session: dict | None = None, public: bool = False) -> Any:
        if public:
            require((method, path) in {("POST", _PREFIX + "/user"), ("POST", _PREFIX + "/user/login"),
                                      ("GET", _PREFIX + "/user/initialization")}, "Public HTTP route is not allowlisted")
            host, port = "127.0.0.1", 19080
            headers = {"Host": self.state["managementHost"]}
            if session is not None:
                headers.update({"username": session["username"], "token": session["token"]})
        else:
            require(method == "POST" and path in {_PREFIX + "/create", _PREFIX + "/create/batch"}
                    and account is not None, "Internal-identity Admin preparation route is not allowlisted")
            host, port = "127.0.0.1", 8002
            headers = {"Host": self.state["managementHost"], "X-Internal-Token": self.internal_token,
                       "x-shortlink-tenant-id": str(account["tenantId"]),
                       "x-shortlink-username": account["username"],
                       "x-shortlink-auth-version": str(account["authVersion"])}
        headers.update({"Accept": "application/json", "User-Agent": "Shortlink-Fixture-Preparation/1.0"})
        body = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.http_attempts += 1
        request_started = time.monotonic()
        connection = http.client.HTTPConnection(host, port, timeout=60 if not public else 10)
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            status_code = response.status
            raw = response.read(_MAX_HTTP_BODY + 1)
        except (OSError, http.client.HTTPException) as error:
            self.last_http_failure = {"method": method, "path": path,
                "transportErrorType": type(error).__name__,
                "elapsedMillis": round((time.monotonic() - request_started) * 1000, 3)}
            # A create may have committed. Do not blindly issue a new idempotency key.
            raise PreparationError("HTTP preparation transport failed; retain this run for committed-result diagnosis") from None
        finally:
            connection.close()
        require(len(raw) <= _MAX_HTTP_BODY, "HTTP preparation response exceeded two MiB")
        if status_code != 200:
            # Keep only known public error codes; bodies, headers and credentials never leave memory.
            code = None
            try:
                rejected = json.loads(raw)
                candidate = rejected.get("code") if isinstance(rejected, dict) else None
                if isinstance(candidate, str) and re.fullmatch(r"(?:REMOTE|HTTP)_[1-5][0-9]{2}|STORE_UNAVAILABLE", candidate):
                    code = candidate
            except ValueError:
                pass
            self.last_http_failure = {"method": method, "path": path, "httpStatus": status_code,
                "publicErrorCode": code,
                "elapsedMillis": round((time.monotonic() - request_started) * 1000, 3)}
        require(status_code == 200, "HTTP preparation request rejected with status " + str(status_code))
        try:
            result = json.loads(raw)
        except ValueError:
            raise PreparationError("HTTP preparation response is not JSON") from None
        require(isinstance(result, dict) and result.get("code") == "0" and result.get("success", True) is True,
                "HTTP preparation Result did not report success")
        self.http_successes += 1
        return result.get("data")

    def register_account(self, index: int) -> dict:
        username = "perf_" + self.seed_id + "_" + str(index)
        password = "Perf!" + uuid.uuid4().hex
        self.request("POST", _PREFIX + "/user", {"username": username, "password": password,
                                                  "realName": "Performance fixture"}, public=True)
        login = self.request("POST", _PREFIX + "/user/login", {"username": username, "password": password}, public=True)
        require(isinstance(login, dict) and isinstance(login.get("token"), str) and bool(login["token"]),
                "Public login did not issue a session")
        session = {"username": username, "token": login["token"]}
        deadline = time.monotonic() + 60
        while True:
            initialized = self.request("GET", _PREFIX + "/user/initialization", session=session, public=True)
            require(isinstance(initialized, dict), "Initialization did not return an object")
            if initialized.get("state") == "READY":
                gid = initialized.get("groupId")
                require(isinstance(gid, str) and bool(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", gid)),
                        "READY default group lacks a valid group ID")
                break
            require(time.monotonic() < deadline, "Default group did not become READY within 60 seconds")
            time.sleep(1)
        queries = ["SELECT id,auth_version FROM t_user_" + str(shard)
                   + " WHERE username='" + username + "' AND disabled=0 AND del_flag=0 AND deletion_time=0"
                   for shard in range(16)]
        identities = self.sql(" UNION ALL ".join(queries))
        require(len(identities) == 1 and len(identities[0]) == 2, "Registered account identity is not globally unique")
        tenant, version = map(int, identities[0])
        require(tenant > 0 and version > 0, "Registered account identity/version is invalid")
        return {"username": username, "tenantId": str(tenant), "authVersion": version, "gid": gid}

    def creation_body(self, account: dict, purpose: str, index: int) -> dict:
        return {"requestId": "perf-" + self.seed_id + "-" + purpose + "-" + str(index),
                "domain": self.state["redirectHost"],
                "originUrl": _TARGET_ORIGIN + "/target/" + self.seed_id + "/" + purpose + "/" + str(index),
                "gid": account["gid"], "createdType": 0, "validDateType": 0, "validDate": None,
                "describe": "Performance fixture; metadata denied before DNS"}

    def normalize(self, response: dict, expected_url: str, account_index: int) -> dict:
        require(isinstance(response, dict) and type(response.get("linkId")) is int
                and 0 < response["linkId"] < 2 ** 52, "Creation response lacks a valid stable link ID")
        link_id = response["linkId"]
        require(link_id not in self.all_ids, "Distinct seeded rows returned a duplicate link ID")
        full = response.get("fullShortUrl")
        require(isinstance(full, str), "Creation response lacks its full URL")
        url = urlsplit(full)
        require(url.scheme == "https" and url.netloc == self.state["redirectHost"]
                and bool(re.fullmatch(r"/[A-Za-z0-9]{9}", url.path)) and not url.query and not url.fragment,
                "Creation response URL is outside the configured short-code domain")
        require(response.get("originUrl") == expected_url, "Creation response changed the fixture target")
        self.all_ids.add(link_id)
        self.created_count += 1
        return {"linkId": link_id, "shortUri": url.path[1:], "fullShortUrl": full,
                "originUrl": expected_url, "gid": self.accounts[account_index]["gid"],
                "accountIndex": account_index}

    def hydrate_routes(self, records: list[dict]) -> None:
        for start in range(0, len(records), _BATCH_SIZE):
            chunk = records[start:start + _BATCH_SIZE]
            ids = ",".join(str(item["linkId"]) for item in chunk)
            rows = self.sql("SELECT link_id,tenant_id,current_gid,domain_norm,short_uri,origin_url,route_version,route_status"
                            + " FROM t_link_route WHERE link_id IN (" + ids + ")")
            actual = {int(row[0]): row for row in rows}
            require(len(actual) == len(chunk), "Created routes are missing from the isolated authority")
            for record in chunk:
                row = actual[record["linkId"]]
                account = self.accounts[record["accountIndex"]]
                require(len(row) == 8 and row[1] == str(account["tenantId"]) and row[2] == record["gid"]
                        and row[3] == self.state["redirectHost"] and row[4] == record["shortUri"]
                        and row[5] == record["originUrl"] and int(row[6]) == 1 and row[7] == "ACTIVE",
                        "Created route identity/target/state/version does not match its committed response")
                record["routeVersion"] = int(row[6])
                record["state"] = row[7]

    def drain(self, expected_routes: int) -> None:
        start = time.monotonic()
        deadline = start + _MAX_DRAIN_SECONDS
        while True:
            rows = self.sql(
                "SELECT (SELECT COUNT(*) FROM t_outbox WHERE state<>'PUBLISHED'),"
                "(SELECT COUNT(*) FROM t_metadata_job WHERE state IN ('READY','RUNNING')),"
                "(SELECT COUNT(*) FROM t_link_route),"
                "(SELECT COUNT(*) FROM t_link_route WHERE metadata_status='PENDING'),"
                "(SELECT COUNT(*) FROM t_link_route WHERE metadata_status='FAILED'),"
                "(SELECT COUNT(*) FROM t_metadata_job WHERE state='FAILED'),"
                "(SELECT COUNT(*) FROM t_metadata_job WHERE state IN ('COMPLETED','OBSOLETE'))"
            )
            require(len(rows) == 1 and len(rows[0]) == 7, "Unexpected queue-observation result")
            outbox, metadata, routes, pending_routes, denied_routes, denied_jobs, unexpected_jobs = map(int, rows[0])
            require(routes == expected_routes, "Isolated schema changed during fixture preparation")
            require(unexpected_jobs == 0, "Metadata did not follow the fixed local-host rejection profile")
            if outbox == 0 and metadata == 0 and pending_routes == 0 and denied_routes == routes and denied_jobs == routes:
                self.drain_observations.append({"createdRoutes": routes, "outboxPending": outbox,
                                                "metadataPending": metadata, "metadataDeniedRoutes": denied_routes,
                                                "elapsedSeconds": round(time.monotonic() - start, 3)})
                return
            require(time.monotonic() < deadline, "Outbox/metadata did not drain within the five-minute batch budget")
            time.sleep(min(1, max(0, deadline - time.monotonic())))

    def seed_for_account(self, account_index: int, count: int, purpose: str) -> list[dict]:
        account = self.accounts[account_index]
        records = []
        for start in range(0, count, _BATCH_SIZE):
            bodies = [self.creation_body(account, purpose + "-a" + str(account_index), index)
                      for index in range(start, min(start + _BATCH_SIZE, count))]
            if len(bodies) == 1:
                response = self.request("POST", _PREFIX + "/create", bodies[0], account=account)
                chunk = [self.normalize(response, bodies[0]["originUrl"], account_index)]
            else:
                payload = {"requestId": "perf-" + self.seed_id + "-" + purpose + "-a" + str(account_index) + "-b" + str(start),
                           "domain": self.state["redirectHost"], "gid": account["gid"], "createdType": 0,
                           "validDateType": 0, "validDate": None,
                           "originUrls": [body["originUrl"] for body in bodies],
                           "describes": [body["describe"] for body in bodies]}
                result = self.request("POST", _PREFIX + "/create/batch", payload, account=account)
                require(isinstance(result, dict) and result.get("state") == "SUCCEEDED" and result.get("jobId") is None
                        and result.get("total") == len(bodies) and isinstance(result.get("baseLinkInfos"), list)
                        and len(result["baseLinkInfos"]) == len(bodies), "Synchronous batch did not commit every requested row")
                chunk = [self.normalize(row, body["originUrl"], account_index)
                         for row, body in zip(result["baseLinkInfos"], bodies)]
                self.batch_count += 1
            self.hydrate_routes(chunk)
            records.extend(chunk)
            self.drain(self.created_count)
            self.write_summary("PREPARING")
        return records

    def write_summary(self, phase: str, *, fixture_sha256: str | None = None, error: str | None = None) -> dict:
        summary = {"status": phase, "runId": self.run_id, "database": self.database,
                   "requestedPrimaryLinks": self.link_count, "requestedAccounts": self.account_count,
                   "requestedMutationLinks": self.mutation_count,
                   "minimumMutationLinksPerAccount": _MIN_MUTATION_ROWS_PER_ACCOUNT,
                   "preparedAccounts": len(self.accounts), "createdLinks": self.created_count,
                   "primaryLinks": len(self.links), "mutationLinks": len(self.mutations),
                   "idempotentLinks": sum("idempotent" in account for account in self.accounts),
                   "synchronousBatchRequests": self.batch_count, "httpRequests": self.http_attempts,
                   "httpSuccesses": self.http_successes,
                   "lastHttpFailure": getattr(self, "last_http_failure", None),
                   "httpSuccessRate": self.http_successes / self.http_attempts if self.http_attempts else None,
                   "readonlySqlQueries": self.sql_reads, "startedAt": self.started_at,
                   "elapsedSeconds": round(time.monotonic() - self.start, 3),
                   "metadataMode": _METADATA_MODE, "metadataTargetOrigin": _TARGET_ORIGIN,
                   "metadataWorkers": self.state["resourceProfile"]["metadataWorkers"],
                   "metadataPollMillis": self.state["metadataPollMillis"], "defaultMetadataPollMillis": 1000,
                   "metadataPollProfileDiffersFromDefault": self.state["metadataPollMillis"] != 1000,
                   "metadataMeaning": "Local-host policy rejection before DNS/network; not normal fetch capacity",
                   "maxDrainSecondsPerBatch": _MAX_DRAIN_SECONDS, "drainObservations": self.drain_observations,
                   "fixtureSha256": fixture_sha256, "fixtureMode": "0600" if phase == "READY" else None,
                   "directLinkSqlWrites": 0, "redisFlushes": 0, "idResets": 0,
                   "agentCalls": 0, "analyticsCalls": 0, "externalTargetRequests": 0}
        if error is not None:
            summary["error"] = error
        self.summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return summary

    def run(self) -> dict:
        # Reserve the private path before any mutation, and fail rather than silently
        # duplicating a previous or partially prepared fixture in the same database.
        require(not self.fixture_path.exists(), "Fixture already exists; preserve its data and refresh real versions before reuse")
        descriptor = None
        try:
            descriptor = os.open(str(self.fixture_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            os.fchmod(descriptor, 0o600)
            require(stat.S_IMODE(os.fstat(descriptor).st_mode) == 0o600, "Private fixture mode 0600 could not be enforced")
            os.write(descriptor, b'{"fixtureStatus":"PREPARING"}\n')
            os.fsync(descriptor)
            fresh = self.sql("SELECT (SELECT COUNT(*) FROM t_account_identity),(SELECT COUNT(*) FROM t_link_route)")
            require(fresh == [["0", "0"]], "Fixture preparation requires the supervisor's new empty schema")
            self.write_summary("PREPARING")
            for index in range(self.account_count):
                self.accounts.append(self.register_account(index))
            require(len({account["tenantId"] for account in self.accounts}) == self.account_count,
                    "Registered fixture accounts share a tenant identity")
            for index in range(self.account_count):
                count = self.link_count // self.account_count + (index < self.link_count % self.account_count)
                self.links.extend(self.seed_for_account(index, count, "primary"))
            for index in range(self.account_count):
                count = self.mutation_count // self.account_count + (index < self.mutation_count % self.account_count)
                self.mutations.extend(self.seed_for_account(index, count, "mutation"))
            for index, account in enumerate(self.accounts):
                body = self.creation_body(account, "idempotent", index)
                response = self.request("POST", _PREFIX + "/create", body, account=account)
                record = self.normalize(response, body["originUrl"], index)
                self.hydrate_routes([record])
                account["idempotent"] = {"body": body, "linkId": record["linkId"], "shortUri": record["shortUri"]}
            self.drain(self.created_count)
            require(len(self.links) == self.link_count and len(self.mutations) == self.mutation_count
                    and self.created_count == self.link_count + self.mutation_count + self.account_count,
                    "Final fixture row count does not match the bounded plan")
            fixture = {"fixtureStatus": "READY", "runId": self.run_id, "database": self.database,
                       "internalToken": self.internal_token, "managementHost": self.state["managementHost"],
                       "redirectHost": self.state["redirectHost"], "normalizedClientIp": "127.0.0.1",
                       "metadataMode": _METADATA_MODE, "metadataPollMillis": self.state["metadataPollMillis"],
                       "accounts": self.accounts, "links": self.links, "mutationLinks": self.mutations}
            content = (json.dumps(fixture, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
            os.lseek(descriptor, 0, os.SEEK_SET)
            os.ftruncate(descriptor, 0)
            with os.fdopen(os.dup(descriptor), "wb") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            require(stat.S_IMODE(os.fstat(descriptor).st_mode) == 0o600, "Private fixture permissions changed")
            return self.write_summary("READY", fixture_sha256=hashlib.sha256(content).hexdigest())
        except PreparationError as error:
            self.write_summary("FAILED", error=str(error))
            raise
        except (OSError, ValueError, KeyError, TypeError, IndexError):
            self.write_summary("FAILED", error="Preparation failed; preserve this run and do not blindly reseed")
            raise PreparationError("Preparation failed; preserve this run and do not blindly reseed") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)


def prepare(state_path: Path, links: int = 10_000, accounts: int = 20) -> dict:
    return FixturePreparer(state_path, links, accounts).run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("state", type=Path, help="READY performance supervisor state.json")
    parser.add_argument("--links", type=int, default=10_000)
    parser.add_argument("--accounts", type=int, default=20)
    options = parser.parse_args()
    try:
        result = prepare(options.state, options.links, options.accounts)
    except PreparationError as failure:
        raise SystemExit("Fixture preparation failed: " + str(failure)) from None
    print(json.dumps({key: result[key] for key in (
        "status", "database", "preparedAccounts", "primaryLinks", "mutationLinks", "idempotentLinks",
        "createdLinks", "fixtureSha256", "httpSuccessRate", "metadataMode", "elapsedSeconds"
    )}, ensure_ascii=False))
