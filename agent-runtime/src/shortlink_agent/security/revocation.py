"""Bounded revocation convergence for Delegation Tokens."""

import asyncio
import json
import re
import ssl
from collections import OrderedDict
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from time import monotonic
from typing import Any, Protocol

import httpx

from shortlink_agent.config import Settings

_SESSION_ID = re.compile(r"^as-s-[A-Za-z0-9_-]{1,123}$")
_CONTEXT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_EVENT_ID = re.compile(r"^ase-[A-Za-z0-9_-]{1,128}$")
_MAX_GRANT_VERSION = 9_223_372_036_854_775_807
_SESSION_REVOCATION_REASONS = frozenset(
    {"USER_CLOSED", "ADMIN_REVOKED", "SESSION_EXPIRED", "SECURITY_RESPONSE"}
)
_SESSION_REVOKED_EVENT_KEYS = frozenset(
    {
        "eventId",
        "eventType",
        "occurredAt",
        "tenantId",
        "sessionId",
        "grantVersion",
        "status",
        "reasonCode",
        "revokedAt",
    }
)
_REJECTION_REASONS = frozenset(
    {
        "SESSION_NOT_FOUND",
        "SESSION_REVOKED",
        "SESSION_EXPIRED",
        "GRANT_VERSION_MISMATCH",
        "TOKEN_REVOKED",
        "TOKEN_NOT_CURRENT",
    }
)


class RevocationStateError(Exception):
    """Fail-closed revocation decision with a stable internal reason."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True, slots=True)
class RevocationStateSnapshot:
    """Low-cardinality counters suitable for metrics and health diagnostics."""

    cache_entries: int
    event_entries: int
    inflight_checks: int
    cache_hits: int
    cache_misses: int
    source_checks: int
    source_failures: int
    revoked_rejections: int
    events_applied: int
    stale_events_ignored: int
    last_source_success_at: datetime | None
    last_source_failure_at: datetime | None


@dataclass(frozen=True, slots=True)
class SessionRevokedEvent:
    """Validated projection of ``agent.session.revoked.v1`` event data."""

    session_id: str
    grant_version: int

    def __post_init__(self) -> None:
        if (
            not _SESSION_ID.fullmatch(self.session_id)
            or type(self.grant_version) is not int
            or not 1 <= self.grant_version <= _MAX_GRANT_VERSION
        ):
            raise ValueError("invalid agent.session.revoked.v1 payload")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "SessionRevokedEvent":
        session_id = payload.get("sessionId")
        grant_version = payload.get("grantVersion")
        if (
            set(payload) != _SESSION_REVOKED_EVENT_KEYS
            or not isinstance(payload.get("eventId"), str)
            or not _EVENT_ID.fullmatch(payload["eventId"])
            or not isinstance(payload.get("tenantId"), str)
            or not _CONTEXT_ID.fullmatch(payload["tenantId"])
            or not isinstance(session_id, str)
            or not _SESSION_ID.fullmatch(session_id)
            or type(grant_version) is not int
            or not 1 <= grant_version <= _MAX_GRANT_VERSION
            or payload.get("eventType") != "agent.session.revoked.v1"
            or payload.get("status") != "REVOKED"
            or payload.get("reasonCode") not in _SESSION_REVOCATION_REASONS
            or not isinstance(payload.get("occurredAt"), str)
            or not _valid_timestamp(payload["occurredAt"])
            or not isinstance(payload.get("revokedAt"), str)
            or not _valid_timestamp(payload["revokedAt"])
        ):
            raise ValueError("invalid agent.session.revoked.v1 payload")
        return cls(session_id=session_id, grant_version=grant_version)


class RevocationStateProvider(Protocol):
    async def assert_active(
        self,
        *,
        session_id: str,
        grant_version: int,
        token_id: str,
    ) -> None: ...

    async def aclose(self) -> None: ...


@dataclass(frozen=True, slots=True)
class _CacheEntry:
    active: bool
    expires_at: float


@dataclass(frozen=True, slots=True)
class _EventEntry:
    grant_version: int
    expires_at: float


class AuthorityRevocationStateProvider:
    """Check Java-owned Grant/JTI state with bounded local convergence caches."""

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._path = settings.delegation_revocation_check_path
        self._cache_ttl_seconds = settings.revocation_cache_ttl_seconds
        self._event_ttl_seconds = settings.revocation_event_ttl_seconds
        self._max_entries = settings.revocation_cache_max_entries
        self._max_response_bytes = settings.revocation_max_response_bytes
        self._maximum_response_age = timedelta(
            seconds=(
                settings.delegation_clock_skew_seconds + settings.revocation_timeout_seconds + 1
            ),
        )
        self._maximum_response_future = timedelta(
            seconds=settings.delegation_clock_skew_seconds,
        )
        self._client = httpx.AsyncClient(
            base_url=settings.authority_base_url,
            timeout=httpx.Timeout(settings.revocation_timeout_seconds),
            follow_redirects=False,
            verify=_authority_ssl_context(settings),
            transport=transport,
        )
        self._cache: OrderedDict[tuple[str, int, str], _CacheEntry] = OrderedDict()
        self._events: OrderedDict[str, _EventEntry] = OrderedDict()
        self._inflight: dict[tuple[str, int, str], asyncio.Task[bool]] = {}
        self._lock = asyncio.Lock()
        self._cache_hits = 0
        self._cache_misses = 0
        self._source_checks = 0
        self._source_failures = 0
        self._revoked_rejections = 0
        self._events_applied = 0
        self._stale_events_ignored = 0
        self._last_source_success_at: datetime | None = None
        self._last_source_failure_at: datetime | None = None

    async def assert_active(
        self,
        *,
        session_id: str,
        grant_version: int,
        token_id: str,
    ) -> None:
        key = (session_id, grant_version, token_id)
        task: asyncio.Task[bool]
        async with self._lock:
            now = monotonic()
            event = self._events.get(session_id)
            if event is not None and event.expires_at <= now:
                self._events.pop(session_id, None)
                event = None
            if event is not None and grant_version <= event.grant_version:
                self._revoked_rejections += 1
                raise RevocationStateError("DELEGATION_TOKEN_REVOKED")
            cached = self._cache.get(key)
            if cached is not None and cached.expires_at <= now:
                self._cache.pop(key, None)
                cached = None
            if cached is not None:
                self._cache.move_to_end(key)
                self._cache_hits += 1
                if not cached.active:
                    self._revoked_rejections += 1
                    raise RevocationStateError("DELEGATION_TOKEN_REVOKED")
                return
            self._cache_misses += 1
            existing = self._inflight.get(key)
            if existing is None:
                if len(self._inflight) >= self._max_entries:
                    raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE")
                task = asyncio.create_task(self._fetch_and_release(key))
                self._inflight[key] = task
            else:
                task = existing

        active = await asyncio.shield(task)
        if not active:
            async with self._lock:
                self._revoked_rejections += 1
            raise RevocationStateError("DELEGATION_TOKEN_REVOKED")

    async def ingest_session_revoked(
        self,
        payload: Mapping[str, Any] | SessionRevokedEvent,
    ) -> bool:
        """Apply an idempotent event as an acceleration hint, never as authority."""

        event = (
            payload
            if isinstance(payload, SessionRevokedEvent)
            else SessionRevokedEvent.from_payload(payload)
        )
        async with self._lock:
            now = monotonic()
            self._purge_expired(now)
            current = self._events.get(event.session_id)
            if current is not None and event.grant_version <= current.grant_version:
                self._stale_events_ignored += 1
                return False
            self._events[event.session_id] = _EventEntry(
                grant_version=event.grant_version,
                expires_at=now + self._event_ttl_seconds,
            )
            self._events.move_to_end(event.session_id)
            self._events_applied += 1
            self._evict_oldest(self._events)
            for key in tuple(self._cache):
                if key[0] == event.session_id and key[1] <= event.grant_version:
                    self._cache.pop(key, None)
            return True

    async def snapshot(self) -> RevocationStateSnapshot:
        async with self._lock:
            self._purge_expired(monotonic())
            return RevocationStateSnapshot(
                cache_entries=len(self._cache),
                event_entries=len(self._events),
                inflight_checks=len(self._inflight),
                cache_hits=self._cache_hits,
                cache_misses=self._cache_misses,
                source_checks=self._source_checks,
                source_failures=self._source_failures,
                revoked_rejections=self._revoked_rejections,
                events_applied=self._events_applied,
                stale_events_ignored=self._stale_events_ignored,
                last_source_success_at=self._last_source_success_at,
                last_source_failure_at=self._last_source_failure_at,
            )

    async def aclose(self) -> None:
        async with self._lock:
            tasks = tuple(self._inflight.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        await self._client.aclose()

    async def _fetch_and_cache(self, key: tuple[str, int, str]) -> bool:
        session_id, grant_version, token_id = key
        async with self._lock:
            self._source_checks += 1
        try:
            async with self._client.stream(
                "POST",
                self._path,
                json={
                    "sessionId": session_id,
                    "grantVersion": grant_version,
                    "tokenId": token_id,
                },
                headers={
                    "Accept": "application/json",
                    "Cache-Control": "no-store",
                },
            ) as response:
                raw_body = await self._read_limited(response)
            if response.status_code < 200 or response.status_code >= 300:
                raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE")
            media_type = response.headers.get("content-type", "").partition(";")[0]
            if media_type.lower() != "application/json":
                raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE")
            active = self._parse_response(raw_body, key)
        except RevocationStateError:
            await self._record_source_failure()
            raise
        except httpx.HTTPError as exc:
            await self._record_source_failure()
            raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE") from exc

        async with self._lock:
            now = monotonic()
            self._last_source_success_at = datetime.now(UTC)
            self._cache[key] = _CacheEntry(
                active=active,
                expires_at=now + self._cache_ttl_seconds,
            )
            self._cache.move_to_end(key)
            self._evict_oldest(self._cache)
        return active

    async def _fetch_and_release(self, key: tuple[str, int, str]) -> bool:
        try:
            return await self._fetch_and_cache(key)
        finally:
            current = asyncio.current_task()
            async with self._lock:
                if self._inflight.get(key) is current:
                    self._inflight.pop(key, None)

    async def _read_limited(self, response: httpx.Response) -> bytes:
        content_length = response.headers.get("content-length")
        if content_length is not None:
            try:
                if int(content_length) > self._max_response_bytes:
                    raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE")
            except ValueError as exc:
                raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE") from exc
        body = bytearray()
        async for chunk in response.aiter_bytes():
            body.extend(chunk)
            if len(body) > self._max_response_bytes:
                raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE")
        return bytes(body)

    def _parse_response(
        self,
        raw_body: bytes,
        key: tuple[str, int, str],
    ) -> bool:
        try:
            body = json.loads(raw_body)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE") from exc
        session_id, grant_version, token_id = key
        checked_at = body.get("checkedAt") if isinstance(body, dict) else None
        parsed_checked_at = _parse_timestamp(checked_at) if isinstance(checked_at, str) else None
        now_utc = datetime.now(UTC)
        active = body.get("active") if isinstance(body, dict) else None
        expected_keys = {
            "active",
            "sessionId",
            "grantVersion",
            "tokenId",
            "checkedAt",
        }
        if active is False:
            expected_keys.add("reasonCode")
        if (
            not isinstance(body, dict)
            or type(active) is not bool
            or set(body) != expected_keys
            or body.get("sessionId") != session_id
            or type(body.get("grantVersion")) is not int
            or body.get("grantVersion") != grant_version
            or body.get("tokenId") != token_id
            or not isinstance(checked_at, str)
            or parsed_checked_at is None
            or parsed_checked_at < now_utc - self._maximum_response_age
            or parsed_checked_at > now_utc + self._maximum_response_future
            or (active is False and body.get("reasonCode") not in _REJECTION_REASONS)
        ):
            raise RevocationStateError("REVOCATION_STATE_UNAVAILABLE")
        return active

    async def _record_source_failure(self) -> None:
        async with self._lock:
            self._source_failures += 1
            self._last_source_failure_at = datetime.now(UTC)

    def _purge_expired(self, now: float) -> None:
        for key, entry in tuple(self._cache.items()):
            if entry.expires_at <= now:
                self._cache.pop(key, None)
        for session_id, entry in tuple(self._events.items()):
            if entry.expires_at <= now:
                self._events.pop(session_id, None)

    def _evict_oldest(self, values: OrderedDict[Any, Any]) -> None:
        while len(values) > self._max_entries:
            values.popitem(last=False)


def _authority_ssl_context(settings: Settings) -> ssl.SSLContext:
    context = ssl.create_default_context(cafile=settings.authority_mtls_ca_file)
    if settings.authority_mtls_cert_file and settings.authority_mtls_key_file:
        context.load_cert_chain(
            certfile=settings.authority_mtls_cert_file,
            keyfile=settings.authority_mtls_key_file,
        )
    return context


def _valid_timestamp(value: str) -> bool:
    return _parse_timestamp(value) is not None


def _parse_timestamp(value: str) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(UTC)
