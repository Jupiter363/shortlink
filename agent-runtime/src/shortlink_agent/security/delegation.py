"""Delegation JWT verification with bounded asynchronous JWKS caching."""

import asyncio
import json
import re
from contextvars import ContextVar, Token
from dataclasses import dataclass, field
from time import monotonic
from typing import Any, Protocol

import httpx
import jwt
from pydantic import SecretStr

from shortlink_agent.config import Settings
from shortlink_agent.security.revocation import (
    AuthorityRevocationStateProvider,
    RevocationStateError,
    RevocationStateProvider,
)

_CONTEXT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_SCOPE = re.compile(r"^[A-Za-z0-9:_-]{1,128}$")
_DELEGATION_TOKEN_ID = re.compile(r"^adt-[A-Za-z0-9_-]{1,128}$")
_MAX_GRANT_VERSION = 9_223_372_036_854_775_807


class DelegationTokenError(Exception):
    """Stable authentication failure without provider details."""

    def __init__(self, code: str = "DELEGATION_TOKEN_INVALID") -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True, slots=True)
class DelegationPrincipal:
    """Verified identity and authorization context from a Runtime Token."""

    subject: str
    username: str
    tenant_id: str
    session_id: str
    scopes: frozenset[str]
    token_id: str
    grant_version: int
    issued_at: int
    expires_at: int


@dataclass(slots=True)
class DelegationSecurityContext:
    """Request-local credentials excluded from application domain models."""

    principal: DelegationPrincipal
    subject_token: SecretStr
    authority_token: SecretStr | None = None
    authority_token_expires_at_monotonic: float = 0.0
    authority_token_scopes: frozenset[str] = frozenset()
    authority_token_lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False)


class DelegationTokenVerifierPort(Protocol):
    async def verify(
        self,
        token: str,
        *,
        required_scopes: frozenset[str],
    ) -> DelegationPrincipal: ...

    async def aclose(self) -> None: ...


_CURRENT_DELEGATION: ContextVar[DelegationSecurityContext | None] = ContextVar(
    "shortlink_agent_delegation",
    default=None,
)


def set_current_delegation(
    context: DelegationSecurityContext,
) -> Token[DelegationSecurityContext | None]:
    return _CURRENT_DELEGATION.set(context)


def reset_current_delegation(token: Token[DelegationSecurityContext | None]) -> None:
    _CURRENT_DELEGATION.reset(token)


def current_delegation() -> DelegationSecurityContext | None:
    return _CURRENT_DELEGATION.get()


class JwksCache:
    """Fail-closed bounded JWKS cache with one forced refresh for unknown kids."""

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._url = settings.delegation_jwks_url
        self._ttl_seconds = settings.jwks_cache_ttl_seconds
        self._unknown_kid_refresh_seconds = settings.jwks_unknown_kid_refresh_seconds
        self._max_response_bytes = settings.jwks_max_response_bytes
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.jwks_timeout_seconds),
            follow_redirects=False,
            transport=transport,
        )
        self._keys: dict[str, dict[str, Any]] = {}
        self._expires_at = 0.0
        self._last_refresh_at = 0.0
        self._lock = asyncio.Lock()

    async def key(self, key_id: str) -> dict[str, Any]:
        if not key_id or len(key_id) > 128:
            raise DelegationTokenError()
        now = monotonic()
        if now < self._expires_at and key_id in self._keys:
            return self._keys[key_id]
        await self._refresh(force=key_id not in self._keys)
        key = self._keys.get(key_id)
        if key is None:
            raise DelegationTokenError()
        return key

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _refresh(self, *, force: bool) -> None:
        async with self._lock:
            now = monotonic()
            if not force and now < self._expires_at:
                return
            if (
                force
                and self._keys
                and now - self._last_refresh_at < self._unknown_kid_refresh_seconds
            ):
                return
            try:
                async with self._client.stream("GET", self._url) as response:
                    if response.status_code != 200:
                        raise DelegationTokenError("JWKS_UNAVAILABLE")
                    content_length = response.headers.get("content-length")
                    if (
                        content_length is not None
                        and int(content_length) > self._max_response_bytes
                    ):
                        raise DelegationTokenError("JWKS_INVALID")
                    body = bytearray()
                    async for chunk in response.aiter_bytes():
                        body.extend(chunk)
                        if len(body) > self._max_response_bytes:
                            raise DelegationTokenError("JWKS_INVALID")
                document = json.loads(body)
                self._keys = self._validated_keys(document)
                self._last_refresh_at = monotonic()
                self._expires_at = self._last_refresh_at + self._ttl_seconds
            except DelegationTokenError:
                raise
            except (httpx.HTTPError, UnicodeDecodeError, ValueError, TypeError) as exc:
                raise DelegationTokenError("JWKS_UNAVAILABLE") from exc

    def _validated_keys(self, document: Any) -> dict[str, dict[str, Any]]:
        if not isinstance(document, dict) or not isinstance(document.get("keys"), list):
            raise DelegationTokenError("JWKS_INVALID")
        raw_keys = document["keys"]
        if not 1 <= len(raw_keys) <= 10:
            raise DelegationTokenError("JWKS_INVALID")
        keys: dict[str, dict[str, Any]] = {}
        for raw_key in raw_keys:
            if not isinstance(raw_key, dict):
                raise DelegationTokenError("JWKS_INVALID")
            if (
                raw_key.get("kty") != "EC"
                or raw_key.get("crv") != "P-256"
                or raw_key.get("alg") != "ES256"
                or raw_key.get("use") not in {None, "sig"}
                or not isinstance(raw_key.get("kid"), str)
                or not isinstance(raw_key.get("x"), str)
                or not isinstance(raw_key.get("y"), str)
            ):
                raise DelegationTokenError("JWKS_INVALID")
            key_id = raw_key["kid"]
            if not _CONTEXT_ID.fullmatch(key_id) or key_id in keys or "d" in raw_key:
                raise DelegationTokenError("JWKS_INVALID")
            keys[key_id] = dict(raw_key)
        return keys


class DelegationTokenVerifier:
    """Verify Runtime Tokens against a cached Java-owned JWKS."""

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        revocation_state: RevocationStateProvider | None = None,
    ) -> None:
        self._settings = settings
        self._jwks = JwksCache(settings, transport=transport)
        self._revocation_state = revocation_state
        if self._revocation_state is None and settings.delegation_revocation_mode == "authority":
            self._revocation_state = AuthorityRevocationStateProvider(settings)

    async def verify(
        self,
        token: str,
        *,
        required_scopes: frozenset[str],
    ) -> DelegationPrincipal:
        if not token or len(token) > 16_384:
            raise DelegationTokenError()
        try:
            header = jwt.get_unverified_header(token)
            if (
                header.get("alg") != "ES256"
                or header.get("typ") != "JWT"
                or not isinstance(header.get("kid"), str)
            ):
                raise DelegationTokenError()
            jwk_document = await self._jwks.key(header["kid"])
            key = jwt.PyJWK.from_dict(jwk_document, algorithm="ES256").key
            claims = jwt.decode(
                token,
                key=key,
                algorithms=["ES256"],
                audience=self._settings.delegation_audience,
                issuer=self._settings.delegation_issuer,
                leeway=self._settings.delegation_clock_skew_seconds,
                options={
                    "require": [
                        "iss",
                        "aud",
                        "sub",
                        "tid",
                        "sid",
                        "scp",
                        "ctx_ver",
                        "grant_ver",
                        "jti",
                        "iat",
                        "nbf",
                        "exp",
                        "preferred_username",
                    ],
                    "strict_aud": True,
                },
            )
            principal = self._principal(claims)
            if not principal.scopes.issuperset(required_scopes):
                raise DelegationTokenError("DELEGATION_SCOPE_FORBIDDEN")
            if self._revocation_state is not None:
                await self._revocation_state.assert_active(
                    session_id=principal.session_id,
                    grant_version=principal.grant_version,
                    token_id=principal.token_id,
                )
            return principal
        except DelegationTokenError:
            raise
        except RevocationStateError as exc:
            raise DelegationTokenError(exc.code) from exc
        except (jwt.PyJWTError, ValueError, TypeError, KeyError) as exc:
            raise DelegationTokenError() from exc

    async def aclose(self) -> None:
        await self._jwks.aclose()
        if self._revocation_state is not None:
            await self._revocation_state.aclose()

    def _principal(self, claims: dict[str, Any]) -> DelegationPrincipal:
        subject = self._bounded_string(claims.get("sub"), 256)
        username = self._bounded_string(claims.get("preferred_username"), 128)
        tenant_id = self._context_id(claims.get("tid"))
        session_id = self._context_id(claims.get("sid"))
        token_id = self._bounded_string(claims.get("jti"), 128)
        if not _DELEGATION_TOKEN_ID.fullmatch(token_id):
            raise DelegationTokenError()
        if claims.get("ctx_ver") != 1 or type(claims.get("ctx_ver")) is not int:
            raise DelegationTokenError()
        grant_version = claims.get("grant_ver")
        if type(grant_version) is not int or not 1 <= grant_version <= _MAX_GRANT_VERSION:
            raise DelegationTokenError()
        if "parent_jti" in claims or "act" in claims:
            raise DelegationTokenError()
        issued_at = claims.get("iat")
        not_before = claims.get("nbf")
        expires_at = claims.get("exp")
        if not all(type(value) is int for value in (issued_at, not_before, expires_at)):
            raise DelegationTokenError()
        if not_before < issued_at - self._settings.delegation_clock_skew_seconds:
            raise DelegationTokenError()
        if expires_at <= issued_at or (
            expires_at - issued_at > self._settings.delegation_max_ttl_seconds
        ):
            raise DelegationTokenError()
        raw_scopes = claims.get("scp")
        if not isinstance(raw_scopes, list) or not raw_scopes:
            raise DelegationTokenError()
        scopes = frozenset(self._scope(each) for each in raw_scopes)
        if len(scopes) != len(raw_scopes):
            raise DelegationTokenError()
        return DelegationPrincipal(
            subject=subject,
            username=username,
            tenant_id=tenant_id,
            session_id=session_id,
            scopes=scopes,
            token_id=token_id,
            grant_version=grant_version,
            issued_at=issued_at,
            expires_at=expires_at,
        )

    def _bounded_string(self, value: Any, max_length: int) -> str:
        if not isinstance(value, str):
            raise DelegationTokenError()
        normalized = value.strip()
        if not normalized or len(normalized) > max_length:
            raise DelegationTokenError()
        return normalized

    def _context_id(self, value: Any) -> str:
        normalized = self._bounded_string(value, 128)
        if not _CONTEXT_ID.fullmatch(normalized):
            raise DelegationTokenError()
        return normalized

    def _scope(self, value: Any) -> str:
        normalized = self._bounded_string(value, 128)
        if not _SCOPE.fullmatch(normalized):
            raise DelegationTokenError()
        return normalized
