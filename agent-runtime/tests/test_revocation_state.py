"""Delegation Token revocation convergence tests."""

import asyncio
import json
from datetime import UTC, datetime
from typing import Any

import httpx
import pytest

from shortlink_agent.config import Settings
from shortlink_agent.security.revocation import (
    AuthorityRevocationStateProvider,
    RevocationStateError,
    SessionRevokedEvent,
)


def _checked_at() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def _settings(**overrides: Any) -> Settings:
    values: dict[str, Any] = {
        "environment": "test",
        "authority_base_url": "http://admin.test",
        "delegation_revocation_mode": "authority",
        "revocation_cache_ttl_seconds": 5,
        "revocation_cache_max_entries": 10,
    }
    values.update(overrides)
    return Settings(**values)


def _response(request: httpx.Request, *, active: bool) -> httpx.Response:
    body = json.loads(request.content)
    payload = {
        "active": active,
        "sessionId": body["sessionId"],
        "grantVersion": body["grantVersion"],
        "tokenId": body["tokenId"],
        "checkedAt": _checked_at(),
    }
    if not active:
        payload["reasonCode"] = "TOKEN_REVOKED"
    return httpx.Response(
        200,
        json=payload,
    )


@pytest.mark.asyncio
async def test_active_state_is_cached_and_concurrent_checks_are_coalesced() -> None:
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        await asyncio.sleep(0.01)
        return _response(request, active=True)

    provider = AuthorityRevocationStateProvider(
        _settings(),
        transport=httpx.MockTransport(handler),
    )
    try:
        await asyncio.gather(
            *(
                provider.assert_active(
                    session_id="session-1",
                    grant_version=2,
                    token_id="adt-token-1",
                )
                for _ in range(5)
            ),
        )
        await provider.assert_active(
            session_id="session-1",
            grant_version=2,
            token_id="adt-token-1",
        )
        snapshot = await provider.snapshot()
    finally:
        await provider.aclose()

    assert len(requests) == 1
    assert requests[0].url.path.endswith("/agent-identity/revocations/check")
    assert requests[0].headers["Cache-Control"] == "no-store"
    assert json.loads(requests[0].content) == {
        "sessionId": "session-1",
        "grantVersion": 2,
        "tokenId": "adt-token-1",
    }
    assert snapshot.source_checks == 1
    assert snapshot.cache_hits == 1
    assert snapshot.cache_misses == 5
    assert snapshot.source_failures == 0
    assert snapshot.last_source_success_at is not None


@pytest.mark.asyncio
async def test_inactive_state_is_rejected_and_cached() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return _response(request, active=False)

    provider = AuthorityRevocationStateProvider(
        _settings(),
        transport=httpx.MockTransport(handler),
    )
    try:
        for _ in range(2):
            with pytest.raises(RevocationStateError) as error:
                await provider.assert_active(
                    session_id="session-1",
                    grant_version=2,
                    token_id="adt-token-1",
                )
            assert error.value.code == "DELEGATION_TOKEN_REVOKED"
        snapshot = await provider.snapshot()
    finally:
        await provider.aclose()

    assert calls == 1
    assert snapshot.revoked_rejections == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "handler",
    [
        lambda _: httpx.Response(503),
        lambda request: httpx.Response(
            200,
            json={
                "active": True,
                "sessionId": "wrong-session",
                "grantVersion": 2,
                "tokenId": json.loads(request.content)["tokenId"],
                "checkedAt": _checked_at(),
            },
        ),
        lambda request: httpx.Response(
            200,
            json={
                "active": True,
                "sessionId": json.loads(request.content)["sessionId"],
                "grantVersion": 2,
                "tokenId": json.loads(request.content)["tokenId"],
                "checkedAt": "not-a-timestamp",
            },
        ),
        lambda request: httpx.Response(
            200,
            json={
                "active": True,
                "sessionId": json.loads(request.content)["sessionId"],
                "grantVersion": 2,
                "tokenId": json.loads(request.content)["tokenId"],
                "checkedAt": "2020-01-01T00:00:00Z",
            },
        ),
        lambda request: httpx.Response(
            200,
            content=json.dumps(
                {
                    "active": True,
                    "sessionId": json.loads(request.content)["sessionId"],
                    "grantVersion": 2,
                    "tokenId": json.loads(request.content)["tokenId"],
                    "checkedAt": _checked_at(),
                },
            ),
            headers={"Content-Type": "text/plain"},
        ),
    ],
)
async def test_unavailable_or_invalid_authority_response_fails_closed(
    handler: Any,
) -> None:
    provider = AuthorityRevocationStateProvider(
        _settings(),
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(RevocationStateError) as error:
            await provider.assert_active(
                session_id="session-1",
                grant_version=2,
                token_id="adt-token-1",
            )
        snapshot = await provider.snapshot()
    finally:
        await provider.aclose()

    assert error.value.code == "REVOCATION_STATE_UNAVAILABLE"
    assert snapshot.source_failures == 1
    assert snapshot.last_source_failure_at is not None


@pytest.mark.asyncio
async def test_expired_active_cache_fails_closed_when_authority_is_down() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return _response(request, active=True)
        return httpx.Response(503)

    provider = AuthorityRevocationStateProvider(
        _settings(revocation_cache_ttl_seconds=0.01),
        transport=httpx.MockTransport(handler),
    )
    try:
        await provider.assert_active(
            session_id="session-1",
            grant_version=2,
            token_id="adt-token-1",
        )
        await asyncio.sleep(0.02)
        with pytest.raises(RevocationStateError) as error:
            await provider.assert_active(
                session_id="session-1",
                grant_version=2,
                token_id="adt-token-1",
            )
    finally:
        await provider.aclose()

    assert error.value.code == "REVOCATION_STATE_UNAVAILABLE"
    assert calls == 2


@pytest.mark.asyncio
async def test_session_event_rejects_only_same_or_older_grant_versions() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return _response(request, active=True)

    provider = AuthorityRevocationStateProvider(
        _settings(),
        transport=httpx.MockTransport(handler),
    )
    try:
        applied = await provider.ingest_session_revoked(
            {
                "eventId": "ase-event-1",
                "eventType": "agent.session.revoked.v1",
                "occurredAt": _checked_at(),
                "tenantId": "tenant-default",
                "sessionId": "as-s-session-1",
                "grantVersion": 3,
                "status": "REVOKED",
                "reasonCode": "USER_CLOSED",
                "revokedAt": _checked_at(),
            },
        )
        duplicate = await provider.ingest_session_revoked(
            SessionRevokedEvent("as-s-session-1", 3),
        )
        with pytest.raises(RevocationStateError) as error:
            await provider.assert_active(
                session_id="as-s-session-1",
                grant_version=3,
                token_id="adt-token-old",
            )
        await provider.assert_active(
            session_id="as-s-session-1",
            grant_version=4,
            token_id="adt-token-new",
        )
        snapshot = await provider.snapshot()
    finally:
        await provider.aclose()

    assert applied is True
    assert duplicate is False
    assert error.value.code == "DELEGATION_TOKEN_REVOKED"
    assert calls == 1
    assert snapshot.events_applied == 1
    assert snapshot.stale_events_ignored == 1


@pytest.mark.asyncio
async def test_authority_cache_is_lru_bounded() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return _response(request, active=True)

    provider = AuthorityRevocationStateProvider(
        _settings(revocation_cache_max_entries=2),
        transport=httpx.MockTransport(handler),
    )
    try:
        for token_id in ("adt-token-1", "adt-token-2", "adt-token-3"):
            await provider.assert_active(
                session_id="session-1",
                grant_version=2,
                token_id=token_id,
            )
        snapshot = await provider.snapshot()
        await provider.assert_active(
            session_id="session-1",
            grant_version=2,
            token_id="adt-token-1",
        )
    finally:
        await provider.aclose()

    assert snapshot.cache_entries == 2
    assert calls == 4


def test_session_event_payload_is_strict() -> None:
    with pytest.raises(ValueError, match="invalid agent.session.revoked.v1 payload"):
        SessionRevokedEvent.from_payload(
            {"sessionId": "invalid session", "grantVersion": 1, "status": "REVOKED"},
        )


@pytest.mark.asyncio
async def test_authority_response_rejects_protocol_drift() -> None:
    responses = [
        {
            "active": True,
            "sessionId": "session-1",
            "grantVersion": 2,
            "tokenId": "adt-token-1",
            "checkedAt": _checked_at(),
            "reasonCode": "TOKEN_REVOKED",
        },
        {
            "active": False,
            "sessionId": "session-1",
            "grantVersion": 2,
            "tokenId": "adt-token-1",
            "checkedAt": _checked_at(),
        },
        {
            "active": True,
            "sessionId": "session-1",
            "grantVersion": 2,
            "tokenId": "adt-token-1",
            "checkedAt": _checked_at(),
            "unexpected": "field",
        },
    ]

    for response_body in responses:
        provider = AuthorityRevocationStateProvider(
            _settings(),
            transport=httpx.MockTransport(
                lambda _, payload=response_body: httpx.Response(200, json=payload),
            ),
        )
        try:
            with pytest.raises(RevocationStateError) as error:
                await provider.assert_active(
                    session_id="session-1",
                    grant_version=2,
                    token_id="adt-token-1",
                )
            assert error.value.code == "REVOCATION_STATE_UNAVAILABLE"
        finally:
            await provider.aclose()


@pytest.mark.asyncio
async def test_inflight_unique_checks_are_bounded() -> None:
    entered = asyncio.Event()
    release = asyncio.Event()

    async def handler(request: httpx.Request) -> httpx.Response:
        entered.set()
        await release.wait()
        return _response(request, active=True)

    provider = AuthorityRevocationStateProvider(
        _settings(revocation_cache_max_entries=1),
        transport=httpx.MockTransport(handler),
    )
    first = asyncio.create_task(
        provider.assert_active(
            session_id="session-1",
            grant_version=1,
            token_id="adt-token-1",
        ),
    )
    try:
        await entered.wait()
        with pytest.raises(RevocationStateError) as error:
            await provider.assert_active(
                session_id="session-2",
                grant_version=1,
                token_id="adt-token-2",
            )
        assert error.value.code == "REVOCATION_STATE_UNAVAILABLE"
        release.set()
        await first
    finally:
        release.set()
        await asyncio.gather(first, return_exceptions=True)
        await provider.aclose()
