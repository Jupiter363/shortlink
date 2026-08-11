"""mTLS-backed RFC 8693 token exchange for Java Authority calls."""

import json
import ssl
from time import monotonic

import httpx
from pydantic import SecretStr

from shortlink_agent.application.errors import CapabilityError
from shortlink_agent.config import Settings
from shortlink_agent.security.delegation import DelegationSecurityContext, current_delegation

_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
_SUBJECT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt"
_ISSUED_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
_MAX_AUTHORITY_TOKEN_SECONDS = 120


def authority_ssl_context(settings: Settings) -> ssl.SSLContext:
    context = ssl.create_default_context(cafile=settings.authority_mtls_ca_file)
    if settings.authority_mtls_cert_file and settings.authority_mtls_key_file:
        context.load_cert_chain(
            certfile=settings.authority_mtls_cert_file,
            keyfile=settings.authority_mtls_key_file,
        )
    return context


class AuthorityTokenExchangeClient:
    """Exchange the request-local Runtime Token for a bounded Authority Token."""

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.authority_base_url,
            timeout=httpx.Timeout(settings.capability_timeout_seconds),
            follow_redirects=False,
            verify=authority_ssl_context(settings),
            transport=transport,
        )

    async def authorization(
        self,
        required_scopes: frozenset[str],
        *,
        request_id: str,
    ) -> str:
        context = current_delegation()
        if context is None:
            raise CapabilityError(
                "DELEGATION_CONTEXT_MISSING",
                "The capability call has no verified delegation context.",
            )
        async with context.authority_token_lock:
            cached = self._cached_authorization(context, required_scopes)
            if cached is not None:
                return cached
            return await self._exchange(
                context,
                required_scopes,
                request_id=request_id,
            )

    async def _exchange(
        self,
        context: DelegationSecurityContext,
        required_scopes: frozenset[str],
        *,
        request_id: str,
    ) -> str:
        requested_scope = " ".join(sorted(required_scopes))
        try:
            async with self._client.stream(
                "POST",
                self._settings.authority_token_exchange_path,
                data={
                    "grant_type": _GRANT_TYPE,
                    "subject_token": context.subject_token.get_secret_value(),
                    "subject_token_type": _SUBJECT_TOKEN_TYPE,
                    "audience": self._settings.authority_audience,
                    "scope": requested_scope,
                },
                headers={
                    "Accept": "application/json",
                    "X-Request-ID": request_id,
                },
            ) as response:
                raw_body = await self._read_limited(response)
        except httpx.TimeoutException as exc:
            raise CapabilityError(
                "TOKEN_EXCHANGE_TIMEOUT",
                "The Authority Token exchange timed out.",
                retryable=True,
            ) from exc
        except httpx.HTTPError as exc:
            raise CapabilityError(
                "TOKEN_EXCHANGE_UNAVAILABLE",
                "The Authority Token exchange is unavailable.",
                retryable=True,
            ) from exc

        if response.status_code < 200 or response.status_code >= 300:
            retryable = response.status_code >= 500
            raise CapabilityError(
                "TOKEN_EXCHANGE_REJECTED",
                "The Authority Token exchange rejected the request.",
                retryable=retryable,
            )
        token, expires_in, granted_scopes = self._parse_response(raw_body, required_scopes)
        context.authority_token = SecretStr(token)
        context.authority_token_expires_at_monotonic = monotonic() + expires_in
        context.authority_token_scopes = granted_scopes
        return "Bearer " + token

    def _cached_authorization(
        self,
        context: DelegationSecurityContext,
        required_scopes: frozenset[str],
    ) -> str | None:
        refresh_at = (
            context.authority_token_expires_at_monotonic
            - self._settings.authority_token_refresh_skew_seconds
        )
        if (
            context.authority_token is not None
            and monotonic() < refresh_at
            and context.authority_token_scopes.issuperset(required_scopes)
        ):
            return "Bearer " + context.authority_token.get_secret_value()
        return None

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _read_limited(self, response: httpx.Response) -> bytes:
        content_length = response.headers.get("content-length")
        if content_length is not None:
            try:
                if int(content_length) > self._settings.capability_max_response_bytes:
                    raise CapabilityError(
                        "TOKEN_EXCHANGE_RESPONSE_TOO_LARGE",
                        "The Authority Token exchange response is too large.",
                    )
            except ValueError as exc:
                raise CapabilityError(
                    "TOKEN_EXCHANGE_INVALID_RESPONSE",
                    "The Authority Token exchange response is invalid.",
                ) from exc
        body = bytearray()
        async for chunk in response.aiter_bytes():
            body.extend(chunk)
            if len(body) > self._settings.capability_max_response_bytes:
                raise CapabilityError(
                    "TOKEN_EXCHANGE_RESPONSE_TOO_LARGE",
                    "The Authority Token exchange response is too large.",
                )
        return bytes(body)

    def _parse_response(
        self,
        raw_body: bytes,
        required_scopes: frozenset[str],
    ) -> tuple[str, int, frozenset[str]]:
        try:
            body = json.loads(raw_body)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise CapabilityError(
                "TOKEN_EXCHANGE_INVALID_RESPONSE",
                "The Authority Token exchange response is invalid.",
            ) from exc
        if not isinstance(body, dict):
            self._invalid_response()
        token = body.get("access_token")
        expires_in = body.get("expires_in")
        token_type = body.get("token_type")
        issued_token_type = body.get("issued_token_type")
        raw_scope = body.get("scope")
        if (
            not isinstance(token, str)
            or not token
            or len(token) > 16_384
            or type(expires_in) is not int
            or not 1 <= expires_in <= _MAX_AUTHORITY_TOKEN_SECONDS
            or not isinstance(token_type, str)
            or token_type.lower() != "bearer"
            or issued_token_type != _ISSUED_TOKEN_TYPE
            or not isinstance(raw_scope, str)
        ):
            self._invalid_response()
        granted_scopes = frozenset(raw_scope.split())
        if granted_scopes != required_scopes:
            self._invalid_response()
        return token, expires_in, granted_scopes

    def _invalid_response(self) -> None:
        raise CapabilityError(
            "TOKEN_EXCHANGE_INVALID_RESPONSE",
            "The Authority Token exchange response is invalid.",
        )
