"""Java capability HTTP adapter tests."""

import asyncio
import json
from datetime import datetime
from pathlib import Path
from urllib.parse import parse_qs
from zoneinfo import ZoneInfo

import httpx
import pytest
from pydantic import SecretStr

from shortlink_agent.application.errors import CapabilityError
from shortlink_agent.application.models import (
    CapabilityCallContext,
    GroupStatsCapabilityQuery,
    ShortLinksCapabilityQuery,
    TrustedActor,
)
from shortlink_agent.capabilities.http_gateway import HttpAuthorityCapabilityGateway
from shortlink_agent.config import Settings
from shortlink_agent.security.delegation import (
    DelegationPrincipal,
    DelegationSecurityContext,
    reset_current_delegation,
    set_current_delegation,
)

_GROUP_STATS_RESPONSE_EXAMPLE = (
    Path(__file__).resolve().parents[2]
    / "schemas"
    / "agent-capabilities"
    / "v1"
    / "examples"
    / "group-stats-query-response.json"
)
_GROUPS_LIST_RESPONSE_EXAMPLE = (
    Path(__file__).resolve().parents[2]
    / "schemas"
    / "agent-capabilities"
    / "v1"
    / "examples"
    / "groups-list-response.json"
)
_SHORT_LINKS_RESPONSE_EXAMPLE = (
    Path(__file__).resolve().parents[2]
    / "schemas"
    / "agent-capabilities"
    / "v1"
    / "examples"
    / "short-links-query-response.json"
)


def gateway_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "environment": "test",
        "authority_base_url": "http://admin.test",
        "authority_internal_token": SecretStr("authority-token"),
        "capability_max_response_bytes": 1_024,
    }
    values.update(overrides)
    return Settings(**values)


def call_context() -> CapabilityCallContext:
    return CapabilityCallContext(
        actor=TrustedActor("trusted-user", "1001", "Trusted User"),
        session_id="session-1",
        trace_id="trace-1",
    )


def group_stats_query() -> GroupStatsCapabilityQuery:
    zone = ZoneInfo("Asia/Shanghai")
    return GroupStatsCapabilityQuery(
        gid="g1",
        start=datetime(2026, 7, 10, tzinfo=zone),
        end=datetime(2026, 7, 17, tzinfo=zone),
        timezone="Asia/Shanghai",
    )


def group_stats_response(*, content_hash: str | None = None) -> dict[str, object]:
    response = json.loads(_GROUP_STATS_RESPONSE_EXAMPLE.read_text(encoding="utf-8"))
    if content_hash is not None:
        response["snapshot"]["contentHash"] = content_hash
    return response


def groups_list_response(*, content_hash: str | None = None) -> dict[str, object]:
    response = json.loads(_GROUPS_LIST_RESPONSE_EXAMPLE.read_text(encoding="utf-8"))
    if content_hash is not None:
        response["snapshot"]["contentHash"] = content_hash
    return response


def short_links_query() -> ShortLinksCapabilityQuery:
    return ShortLinksCapabilityQuery(
        gid="g1",
        current=2,
        size=2,
        sort="TOTAL_PV_DESC",
    )


def short_links_response(*, content_hash: str | None = None) -> dict[str, object]:
    response = json.loads(_SHORT_LINKS_RESPONSE_EXAMPLE.read_text(encoding="utf-8"))
    if content_hash is not None:
        response["snapshot"]["contentHash"] = content_hash
    return response


@pytest.mark.asyncio
async def test_gateway_forwards_trusted_context_and_returns_envelope_data() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "success": True,
                "code": "0",
                "message": "success",
                "data": {"pv": 100},
            },
        )

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(),
        transport=httpx.MockTransport(handler),
    )
    try:
        data = await gateway.get(
            "/internal/short-link-admin/v1/agent-tools/group/stats",
            call_context(),
            {"gid": "g1", "startDate": "2026-07-01", "endDate": "2026-07-07"},
        )
    finally:
        await gateway.aclose()

    assert data == {"pv": 100}
    assert captured_request is not None
    assert captured_request.url.path == "/internal/short-link-admin/v1/agent-tools/group/stats"
    assert captured_request.url.params["gid"] == "g1"
    assert captured_request.headers["X-Agent-Username"] == "trusted-user"
    assert captured_request.headers["X-Agent-UserId"] == "1001"
    assert captured_request.headers["X-Agent-RealName"] == "Trusted User"
    assert captured_request.headers["X-Agent-Internal-Token"] == "authority-token"
    assert captured_request.headers["X-Agent-Session-ID"] == "session-1"
    assert captured_request.headers["X-Agent-Trace-ID"] == "trace-1"


@pytest.mark.asyncio
async def test_groups_list_v1_consumer_validates_snapshot_and_canonical_hash() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(200, json=groups_list_response())

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(groups_list_contract="v1"),
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await gateway.list_groups(call_context())
    finally:
        await gateway.aclose()

    assert result.data == [
        {"gid": "g1", "name": "Marketing", "sortOrder": 10, "shortLinkCount": 3},
        {"gid": "g2", "name": "Product", "sortOrder": 5, "shortLinkCount": 2},
    ]
    assert result.snapshot == {
        "snapshotId": "snap-groups-1",
        "source": "admin/groups",
        "observedAt": "2026-07-17T02:00:00Z",
        "expiresAt": "2026-07-17T02:05:00Z",
        "contentHash": "sha256:eebc387e17eecf08b328f6213b57adcbb2a7b533aa811a0c4934334176fa6d33",
    }
    assert captured_request is not None
    assert captured_request.method == "POST"
    assert captured_request.url.path == (
        "/internal/short-link-admin/v1/agent-capabilities/v1/groups/list"
    )
    assert json.loads(captured_request.content) == {}
    assert captured_request.headers["X-Request-ID"] == "trace-1"


@pytest.mark.asyncio
async def test_groups_list_legacy_contract_is_an_independent_rollback_switch() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(200, json={"code": "0", "data": [{"gid": "g1"}]})

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(groups_list_contract="legacy"),
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await gateway.list_groups(call_context())
    finally:
        await gateway.aclose()

    assert result.data == [{"gid": "g1"}]
    assert result.snapshot is None
    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/short-link-admin/v1/agent-tools/groups"
    assert captured_request.headers["X-Agent-Username"] == "trusted-user"


@pytest.mark.asyncio
async def test_groups_list_v1_rejects_content_hash_mismatch() -> None:
    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(groups_list_contract="v1"),
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json=groups_list_response(content_hash="sha256:" + "0" * 64),
            ),
        ),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.list_groups(call_context())
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_CONTENT_HASH_MISMATCH"


@pytest.mark.asyncio
async def test_groups_list_v1_failure_never_downgrades_to_legacy_automatically() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(503, json={"code": "CAPABILITY_PROVIDER_FAILED"})

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(groups_list_contract="v1"),
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.list_groups(call_context())
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_PROVIDER_FAILED"
    assert [(request.method, request.url.path) for request in requests] == [
        ("POST", "/internal/short-link-admin/v1/agent-capabilities/v1/groups/list")
    ]


@pytest.mark.asyncio
async def test_short_links_v1_consumer_validates_minimized_page_and_hash() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(200, json=short_links_response())

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(short_links_contract="v1"),
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await gateway.query_short_links(call_context(), short_links_query())
    finally:
        await gateway.aclose()

    assert result.data["gid"] == "g1"
    assert result.data["current"] == 2
    assert result.data["total"] == 3
    assert result.data["records"] == [
        {
            "fullShortUrl": "nurl.ink/a",
            "describe": "Launch",
            "validity": "CUSTOM",
            "expiresAt": "2026-08-01T00:00:00Z",
            "createdAt": "2026-07-10T01:00:00Z",
            "todayPv": 12,
            "todayUv": 8,
            "todayUip": 7,
            "totalPv": 120,
            "totalUv": 80,
            "totalUip": 70,
        }
    ]
    assert result.snapshot["source"] == "admin/short-links"
    assert captured_request is not None
    assert captured_request.method == "POST"
    assert captured_request.url.path == (
        "/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query"
    )
    assert json.loads(captured_request.content) == {
        "gid": "g1",
        "current": 2,
        "size": 2,
        "sort": "TOTAL_PV_DESC",
    }


@pytest.mark.asyncio
async def test_short_links_legacy_contract_is_an_independent_rollback_switch() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={"code": "0", "data": {"records": [], "total": 0}},
        )

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(short_links_contract="legacy"),
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await gateway.query_short_links(call_context(), short_links_query())
    finally:
        await gateway.aclose()

    assert result.data == {"records": [], "total": 0}
    assert result.snapshot is None
    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/short-link-admin/v1/agent-tools/short-links/page"
    assert dict(captured_request.url.params) == {
        "gid": "g1",
        "current": "2",
        "size": "2",
        "orderTag": "totalPv",
    }


@pytest.mark.asyncio
async def test_short_links_v1_rejects_content_hash_mismatch() -> None:
    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(short_links_contract="v1"),
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json=short_links_response(content_hash="sha256:" + "0" * 64),
            ),
        ),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.query_short_links(call_context(), short_links_query())
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_CONTENT_HASH_MISMATCH"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "drift",
    [
        "raw_origin",
        "page_echo",
        "page_math",
        "duplicate_url",
        "invalid_validity",
        "invalid_expiry_order",
    ],
)
async def test_short_links_v1_rejects_contract_and_pagination_drift(drift: str) -> None:
    response = short_links_response()
    if drift == "raw_origin":
        response["data"]["records"][0]["originUrl"] = "https://private.example"
    elif drift == "page_echo":
        response["data"]["current"] = 1
    elif drift == "page_math":
        response["data"]["pages"] = 3
    elif drift == "duplicate_url":
        response["data"]["records"].append(dict(response["data"]["records"][0]))
    elif drift == "invalid_validity":
        response["data"]["records"][0]["validity"] = "PERMANENT"
    else:
        response["data"]["records"][0]["expiresAt"] = "2026-07-10T01:00:00Z"

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(short_links_contract="v1"),
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=response)),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.query_short_links(call_context(), short_links_query())
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_RESPONSE_INVALID"


@pytest.mark.asyncio
async def test_short_links_v1_failure_never_downgrades_to_legacy() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(503, json={"code": "CAPABILITY_PROVIDER_FAILED"})

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(short_links_contract="v1"),
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(CapabilityError):
            await gateway.query_short_links(call_context(), short_links_query())
    finally:
        await gateway.aclose()

    assert [(request.method, request.url.path) for request in requests] == [
        ("POST", "/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query")
    ]


@pytest.mark.asyncio
async def test_short_links_invalid_semantic_query_is_rejected_without_http_call() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=short_links_response())

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(),
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.query_short_links(
                call_context(),
                ShortLinksCapabilityQuery("g1", 1, 2, "DROP_TABLE_DESC"),
            )
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_QUERY_INVALID"
    assert calls == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("drift", ["extra_owner", "duplicate_gid", "stale_snapshot_order"])
async def test_groups_list_v1_rejects_schema_and_provenance_drift(drift: str) -> None:
    response = groups_list_response()
    if drift == "extra_owner":
        response["data"][0]["owner"] = "must-not-cross-boundary"
    elif drift == "duplicate_gid":
        response["data"][1]["gid"] = "g1"
    else:
        response["snapshot"]["expiresAt"] = response["snapshot"]["observedAt"]

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(groups_list_contract="v1"),
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=response)),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.list_groups(call_context())
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_RESPONSE_INVALID"


@pytest.mark.asyncio
async def test_group_stats_v1_consumer_validates_snapshot_and_canonical_hash() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(200, json=group_stats_response())

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(group_stats_contract="v1"),
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await gateway.query_group_stats(call_context(), group_stats_query())
    finally:
        await gateway.aclose()

    assert result.data == {"gid": "g1", "pv": 100, "uv": 80, "uip": 60}
    assert result.snapshot == {
        "snapshotId": "snap-1",
        "source": "admin/group-stats",
        "observedAt": "2026-07-17T02:00:00Z",
        "expiresAt": "2026-07-17T02:05:00Z",
        "contentHash": "sha256:ecbef1fa10df7cd02d9ae2b5905a2a70a0adf4eea5524db58d74ddcd8d0a64fb",
    }
    assert captured_request is not None
    assert captured_request.method == "POST"
    assert captured_request.url.path == (
        "/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query"
    )
    assert json.loads(captured_request.content) == {
        "gid": "g1",
        "timeRange": {
            "start": "2026-07-10T00:00:00+08:00",
            "end": "2026-07-17T00:00:00+08:00",
            "timezone": "Asia/Shanghai",
        },
    }
    assert captured_request.headers["X-Request-ID"] == "trace-1"
    assert captured_request.headers["X-Agent-Run-ID"] == "trace-1"
    assert captured_request.headers["X-Agent-Tool-Call-ID"].startswith("tool-")


@pytest.mark.asyncio
async def test_group_stats_v1_rejects_content_hash_mismatch() -> None:
    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(group_stats_contract="v1"),
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                json=group_stats_response(content_hash="sha256:" + "0" * 64),
            ),
        ),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.query_group_stats(call_context(), group_stats_query())
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_CONTENT_HASH_MISMATCH"


@pytest.mark.asyncio
async def test_group_stats_legacy_contract_is_an_explicit_rollback_switch() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(200, json={"code": "0", "data": {"pv": 100}})

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(group_stats_contract="legacy"),
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await gateway.query_group_stats(call_context(), group_stats_query())
    finally:
        await gateway.aclose()

    assert result.data == {"pv": 100}
    assert result.snapshot is None
    assert captured_request is not None
    assert captured_request.method == "GET"
    assert captured_request.url.path == "/internal/short-link-admin/v1/agent-tools/group/stats"
    assert captured_request.url.params["startDate"] == "2026-07-10"
    assert captured_request.url.params["endDate"] == "2026-07-16"


@pytest.mark.asyncio
async def test_v1_group_stats_exchanges_and_caches_authority_token() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path.endswith("/agent-identity/token/exchange"):
            return httpx.Response(
                200,
                json={
                    "access_token": "authority-token",
                    "issued_token_type": ("urn:ietf:params:oauth:token-type:access_token"),
                    "token_type": "Bearer",
                    "expires_in": 120,
                    "scope": "capability:stats:read",
                },
            )
        return httpx.Response(200, json=group_stats_response())

    settings = gateway_settings(
        v1_capability_auth_mode="token_exchange",
        authority_token_exchange_path=(
            "/internal/short-link-admin/v1/agent-identity/token/exchange"
        ),
    )
    gateway = HttpAuthorityCapabilityGateway(
        settings,
        transport=httpx.MockTransport(handler),
    )
    context_token = set_current_delegation(
        DelegationSecurityContext(
            principal=DelegationPrincipal(
                subject="1001",
                username="trusted-user",
                tenant_id="tenant-default",
                session_id="session-1",
                scopes=frozenset({"agent:run", "capability:stats:read"}),
                token_id="adt-1",
                grant_version=1,
                issued_at=1,
                expires_at=9999999999,
            ),
            subject_token=SecretStr("runtime-token"),
        ),
    )
    try:
        await asyncio.gather(
            gateway.query_group_stats(call_context(), group_stats_query()),
            gateway.query_group_stats(call_context(), group_stats_query()),
        )
    finally:
        reset_current_delegation(context_token)
        await gateway.aclose()

    exchange_requests = [
        each for each in requests if each.url.path.endswith("/agent-identity/token/exchange")
    ]
    capability_requests = [
        each for each in requests if each.url.path.endswith("/group-stats/query")
    ]
    assert len(exchange_requests) == 1
    exchange_form = parse_qs(exchange_requests[0].content.decode("utf-8"))
    assert exchange_form["subject_token"] == ["runtime-token"]
    assert exchange_form["audience"] == ["shortlink-authority"]
    assert exchange_form["scope"] == ["capability:stats:read"]
    assert len(capability_requests) == 2
    for request in capability_requests:
        assert request.headers["Authorization"] == "Bearer authority-token"
        assert "X-Agent-Internal-Token" not in request.headers
        assert "X-Agent-Username" not in request.headers


@pytest.mark.asyncio
async def test_v1_groups_list_exchanges_only_group_read_scope() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path.endswith("/agent-identity/token/exchange"):
            return httpx.Response(
                200,
                json={
                    "access_token": "group-authority-token",
                    "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
                    "token_type": "Bearer",
                    "expires_in": 120,
                    "scope": "capability:group:read",
                },
            )
        return httpx.Response(200, json=groups_list_response())

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(v1_capability_auth_mode="token_exchange"),
        transport=httpx.MockTransport(handler),
    )
    context_token = set_current_delegation(
        DelegationSecurityContext(
            principal=DelegationPrincipal(
                subject="1001",
                username="trusted-user",
                tenant_id="tenant-default",
                session_id="session-1",
                scopes=frozenset({"agent:run", "capability:group:read"}),
                token_id="adt-1",
                grant_version=1,
                issued_at=1,
                expires_at=9999999999,
            ),
            subject_token=SecretStr("runtime-token"),
        ),
    )
    try:
        await gateway.list_groups(call_context())
    finally:
        reset_current_delegation(context_token)
        await gateway.aclose()

    exchange_request = next(
        request
        for request in requests
        if request.url.path.endswith("/agent-identity/token/exchange")
    )
    capability_request = next(
        request for request in requests if request.url.path.endswith("/groups/list")
    )
    exchange_form = parse_qs(exchange_request.content.decode("utf-8"))
    assert exchange_form["scope"] == ["capability:group:read"]
    assert capability_request.headers["Authorization"] == "Bearer group-authority-token"
    assert "X-Agent-Internal-Token" not in capability_request.headers
    assert "X-Agent-Username" not in capability_request.headers


@pytest.mark.asyncio
async def test_v1_short_links_exchanges_only_group_read_scope() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path.endswith("/agent-identity/token/exchange"):
            return httpx.Response(
                200,
                json={
                    "access_token": "group-authority-token",
                    "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
                    "token_type": "Bearer",
                    "expires_in": 120,
                    "scope": "capability:group:read",
                },
            )
        return httpx.Response(200, json=short_links_response())

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(v1_capability_auth_mode="token_exchange"),
        transport=httpx.MockTransport(handler),
    )
    context_token = set_current_delegation(
        DelegationSecurityContext(
            principal=DelegationPrincipal(
                subject="1001",
                username="trusted-user",
                tenant_id="tenant-default",
                session_id="session-1",
                scopes=frozenset({"agent:run", "capability:group:read"}),
                token_id="adt-1",
                grant_version=1,
                issued_at=1,
                expires_at=9999999999,
            ),
            subject_token=SecretStr("runtime-token"),
        ),
    )
    try:
        await gateway.query_short_links(call_context(), short_links_query())
    finally:
        reset_current_delegation(context_token)
        await gateway.aclose()

    exchange_request = next(
        request
        for request in requests
        if request.url.path.endswith("/agent-identity/token/exchange")
    )
    capability_request = next(
        request for request in requests if request.url.path.endswith("/short-links/query")
    )
    exchange_form = parse_qs(exchange_request.content.decode("utf-8"))
    assert exchange_form["scope"] == ["capability:group:read"]
    assert capability_request.headers["Authorization"] == "Bearer group-authority-token"
    assert "X-Agent-Internal-Token" not in capability_request.headers
    assert "X-Agent-Username" not in capability_request.headers


@pytest.mark.asyncio
async def test_token_exchange_mode_fails_closed_without_delegation_context() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(500)

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(v1_capability_auth_mode="token_exchange"),
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.query_group_stats(call_context(), group_stats_query())
    finally:
        await gateway.aclose()

    assert error.value.code == "DELEGATION_CONTEXT_MISSING"
    assert calls == 0


@pytest.mark.asyncio
async def test_gateway_rejects_paths_outside_allowlist_without_http_call() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={})

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(),
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(CapabilityError, match="not allowed") as error:
            await gateway.get(
                "/internal/short-link-admin/v1/users/export",
                call_context(),
                {},
            )
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_NOT_ALLOWLISTED"
    assert calls == 0


@pytest.mark.asyncio
async def test_gateway_rejects_oversized_response() -> None:
    body = json.dumps({"code": "0", "data": {"value": "x" * 2_000}}).encode()

    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(),
        transport=httpx.MockTransport(lambda _: httpx.Response(200, content=body)),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.get(
                "/internal/short-link-admin/v1/agent-tools/groups",
                call_context(),
                {},
            )
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_RESPONSE_TOO_LARGE"


@pytest.mark.asyncio
async def test_gateway_does_not_reflect_downstream_error_body() -> None:
    gateway = HttpAuthorityCapabilityGateway(
        gateway_settings(),
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                403,
                json={"message": "database secret and user details"},
            ),
        ),
    )
    try:
        with pytest.raises(CapabilityError) as error:
            await gateway.get(
                "/internal/short-link-admin/v1/agent-tools/groups",
                call_context(),
                {},
            )
    finally:
        await gateway.aclose()

    assert error.value.code == "CAPABILITY_HTTP_ERROR"
    assert "database secret" not in error.value.public_message


def test_gateway_rejects_base_url_with_embedded_credentials() -> None:
    with pytest.raises(ValueError, match="must not contain credentials"):
        HttpAuthorityCapabilityGateway(
            gateway_settings(authority_base_url="http://user:password@admin.test"),
        )
