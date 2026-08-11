"""Delegation JWT and JWKS boundary tests."""

import base64
from datetime import UTC, datetime
from typing import Any

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from pydantic import SecretStr

from shortlink_agent.api.app import create_app
from shortlink_agent.application.models import AgentRunCommand, AgentRunResult, TrustedActor
from shortlink_agent.config import Settings
from shortlink_agent.security.delegation import (
    DelegationTokenError,
    DelegationTokenVerifier,
)
from shortlink_agent.security.revocation import RevocationStateError


class StubAgentRuntime:
    def __init__(self) -> None:
        self.command: AgentRunCommand | None = None
        self.actor: TrustedActor | None = None

    async def run(self, command: AgentRunCommand, actor: TrustedActor) -> AgentRunResult:
        self.command = command
        self.actor = actor
        return AgentRunResult(
            session_id=command.session_id,
            trace_id="trace-1",
            answer="done",
        )


class StubRevocationState:
    def __init__(self, error: RevocationStateError | None = None) -> None:
        self.error = error
        self.checked: tuple[str, int, str] | None = None
        self.closed = False

    async def assert_active(
        self,
        *,
        session_id: str,
        grant_version: int,
        token_id: str,
    ) -> None:
        self.checked = (session_id, grant_version, token_id)
        if self.error is not None:
            raise self.error

    async def aclose(self) -> None:
        self.closed = True


def _b64url(value: int) -> str:
    raw = value.to_bytes(32, "big")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _key_material(key_id: str = "agent-key-1") -> tuple[ec.EllipticCurvePrivateKey, dict[str, str]]:
    private_key = ec.generate_private_key(ec.SECP256R1())
    numbers = private_key.public_key().public_numbers()
    jwk = {
        "kty": "EC",
        "crv": "P-256",
        "use": "sig",
        "alg": "ES256",
        "kid": key_id,
        "x": _b64url(numbers.x),
        "y": _b64url(numbers.y),
    }
    return private_key, jwk


def _claims(**overrides: Any) -> dict[str, Any]:
    now = int(datetime.now(UTC).timestamp())
    claims: dict[str, Any] = {
        "iss": "shortlink-admin",
        "aud": "shortlink-agent-runtime",
        "sub": "1001",
        "tid": "tenant-default",
        "sid": "session-1",
        "scp": ["agent:run", "capability:stats:read"],
        "ctx_ver": 1,
        "grant_ver": 1,
        "jti": "adt-test-1",
        "iat": now,
        "nbf": now,
        "exp": now + 300,
        "preferred_username": "trusted-user",
    }
    claims.update(overrides)
    return claims


def _token(
    private_key: ec.EllipticCurvePrivateKey,
    *,
    key_id: str = "agent-key-1",
    claims: dict[str, Any] | None = None,
) -> str:
    return jwt.encode(
        claims or _claims(),
        private_key,
        algorithm="ES256",
        headers={"kid": key_id, "typ": "JWT"},
    )


def _settings(**overrides: Any) -> Settings:
    values: dict[str, Any] = {
        "environment": "test",
        "runtime_auth_mode": "delegation_jwt",
        "delegation_jwks_url": "http://admin.test/jwks.json",
        "internal_token": SecretStr("legacy-token"),
        "model_api_key": SecretStr("model-key"),
    }
    values.update(overrides)
    return Settings(**values)


def _verifier(
    settings: Settings,
    jwks: dict[str, Any],
    *,
    calls: list[httpx.Request] | None = None,
) -> DelegationTokenVerifier:
    def handler(request: httpx.Request) -> httpx.Response:
        if calls is not None:
            calls.append(request)
        return httpx.Response(200, json=jwks)

    return DelegationTokenVerifier(settings, transport=httpx.MockTransport(handler))


@pytest.mark.asyncio
async def test_verifier_accepts_es256_token_and_caches_jwks() -> None:
    private_key, public_jwk = _key_material()
    calls: list[httpx.Request] = []
    verifier = _verifier(_settings(), {"keys": [public_jwk]}, calls=calls)
    try:
        first = await verifier.verify(
            _token(private_key),
            required_scopes=frozenset({"agent:run"}),
        )
        second = await verifier.verify(
            _token(private_key),
            required_scopes=frozenset({"agent:run"}),
        )
    finally:
        await verifier.aclose()

    assert first.username == "trusted-user"
    assert first.session_id == "session-1"
    assert first.grant_version == 1
    assert second.subject == "1001"
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_unknown_kid_refresh_is_throttled() -> None:
    trusted_private_key, trusted_public_jwk = _key_material()
    unknown_private_key, _ = _key_material("agent-key-unknown")
    calls: list[httpx.Request] = []
    verifier = _verifier(
        _settings(jwks_unknown_kid_refresh_seconds=60),
        {"keys": [trusted_public_jwk]},
        calls=calls,
    )
    try:
        await verifier.verify(
            _token(trusted_private_key),
            required_scopes=frozenset({"agent:run"}),
        )
        for _ in range(2):
            with pytest.raises(DelegationTokenError):
                await verifier.verify(
                    _token(unknown_private_key, key_id="agent-key-unknown"),
                    required_scopes=frozenset({"agent:run"}),
                )
    finally:
        await verifier.aclose()

    assert len(calls) == 1


@pytest.mark.asyncio
async def test_verifier_checks_grant_version_and_maps_revocation_failure() -> None:
    private_key, public_jwk = _key_material()
    state = StubRevocationState(
        RevocationStateError("REVOCATION_STATE_UNAVAILABLE"),
    )
    settings = _settings()
    verifier = DelegationTokenVerifier(
        settings,
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"keys": [public_jwk]}),
        ),
        revocation_state=state,
    )
    try:
        with pytest.raises(DelegationTokenError) as error:
            await verifier.verify(
                _token(private_key, claims=_claims(grant_ver=7)),
                required_scopes=frozenset({"agent:run"}),
            )
    finally:
        await verifier.aclose()

    assert error.value.code == "REVOCATION_STATE_UNAVAILABLE"
    assert state.checked == ("session-1", 7, "adt-test-1")
    assert state.closed is True


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("claim_overrides", "required_scopes"),
    [
        ({"aud": "shortlink-authority"}, frozenset({"agent:run"})),
        ({"ctx_ver": 2}, frozenset({"agent:run"})),
        ({"grant_ver": 0}, frozenset({"agent:run"})),
        ({"parent_jti": "adt-parent"}, frozenset({"agent:run"})),
        ({"scp": ["capability:stats:read"]}, frozenset({"agent:run"})),
    ],
)
async def test_verifier_rejects_wrong_claims_or_scope(
    claim_overrides: dict[str, Any],
    required_scopes: frozenset[str],
) -> None:
    private_key, public_jwk = _key_material()
    verifier = _verifier(_settings(), {"keys": [public_jwk]})
    try:
        with pytest.raises(DelegationTokenError):
            await verifier.verify(
                _token(private_key, claims=_claims(**claim_overrides)),
                required_scopes=required_scopes,
            )
    finally:
        await verifier.aclose()


def test_chat_uses_verified_claims_and_ignores_spoofed_identity_headers() -> None:
    private_key, public_jwk = _key_material()
    settings = _settings()
    verifier = _verifier(settings, {"keys": [public_jwk]})
    runtime = StubAgentRuntime()

    with TestClient(
        create_app(
            settings=settings,
            agent_runtime=runtime,
            delegation_verifier=verifier,
        ),
    ) as client:
        response = client.post(
            "/internal/short-link-agent/v1/chat",
            headers={
                "Authorization": "Bearer " + _token(private_key),
                "X-Agent-Internal-Token": "wrong-legacy-token",
                "X-Agent-Username": "spoofed-user",
            },
            json={
                "sessionId": "session-1",
                "agentType": "campaign-analysis",
                "message": "analyze gid=g1",
            },
        )

    assert response.status_code == 200
    assert runtime.actor == TrustedActor("trusted-user", "1001", None)


def test_chat_rejects_session_mismatch() -> None:
    private_key, public_jwk = _key_material()
    settings = _settings()
    runtime = StubAgentRuntime()
    with TestClient(
        create_app(
            settings=settings,
            agent_runtime=runtime,
            delegation_verifier=_verifier(settings, {"keys": [public_jwk]}),
        ),
    ) as client:
        response = client.post(
            "/internal/short-link-agent/v1/chat",
            headers={"Authorization": "Bearer " + _token(private_key)},
            json={
                "sessionId": "session-other",
                "agentType": "campaign-analysis",
                "message": "analyze gid=g1",
            },
        )

    assert response.status_code == 403
    assert response.json()["code"] == "DELEGATION_SESSION_MISMATCH"
    assert runtime.command is None


def test_dual_mode_does_not_fallback_for_malformed_bearer() -> None:
    settings = _settings(runtime_auth_mode="dual")
    runtime = StubAgentRuntime()
    with TestClient(
        create_app(
            settings=settings,
            agent_runtime=runtime,
            delegation_verifier=_verifier(settings, {"keys": [_key_material()[1]]}),
        ),
    ) as client:
        response = client.post(
            "/internal/short-link-agent/v1/chat",
            headers={
                "Authorization": "Basic invalid",
                "X-Agent-Internal-Token": "legacy-token",
                "X-Agent-Username": "trusted-user",
            },
            json={
                "sessionId": "session-1",
                "agentType": "campaign-analysis",
                "message": "analyze gid=g1",
            },
        )

    assert response.status_code == 401
    assert response.json()["code"] == "DELEGATION_TOKEN_INVALID"
    assert runtime.command is None
