"""HTTP adapter for Java-owned Agent capabilities."""

import hashlib
import json
import re
from collections.abc import Mapping
from datetime import datetime, timedelta
from typing import Any, Never
from uuid import uuid4

import httpx

from shortlink_agent.application.errors import CapabilityError
from shortlink_agent.application.models import (
    CapabilityCallContext,
    CapabilityResult,
    GroupStatsCapabilityQuery,
    ShortLinksCapabilityQuery,
)
from shortlink_agent.config import Settings
from shortlink_agent.security.authority_tokens import (
    AuthorityTokenExchangeClient,
    authority_ssl_context,
)

_GROUPS_V1_PATH = "/internal/short-link-admin/v1/agent-capabilities/v1/groups/list"
_GROUPS_LEGACY_PATH = "/internal/short-link-admin/v1/agent-tools/groups"
_SHORT_LINKS_V1_PATH = "/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query"
_SHORT_LINKS_LEGACY_PATH = "/internal/short-link-admin/v1/agent-tools/short-links/page"
_GROUP_STATS_V1_PATH = "/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query"
_GROUP_STATS_LEGACY_PATH = "/internal/short-link-admin/v1/agent-tools/group/stats"
_V1_CAPABILITY_SCOPES = {
    _GROUPS_V1_PATH: frozenset({"capability:group:read"}),
    _SHORT_LINKS_V1_PATH: frozenset({"capability:group:read"}),
    _GROUP_STATS_V1_PATH: frozenset({"capability:stats:read"}),
}

_V1_ENVELOPE_FIELDS = {"schemaVersion", "requestId", "snapshot", "data", "warnings"}
_SNAPSHOT_FIELDS = {"snapshotId", "source", "observedAt", "expiresAt", "contentHash"}
_GROUP_FIELDS = {"gid", "name", "sortOrder", "shortLinkCount"}
_GROUP_STATS_FIELDS = {"gid", "pv", "uv", "uip"}
_SHORT_LINKS_DATA_FIELDS = {
    "gid",
    "current",
    "size",
    "total",
    "pages",
    "hasNext",
    "sort",
    "records",
}
_SHORT_LINK_FIELDS = {
    "fullShortUrl",
    "describe",
    "validity",
    "expiresAt",
    "createdAt",
    "todayPv",
    "todayUv",
    "todayUip",
    "totalPv",
    "totalUv",
    "totalUip",
}
_SHORT_LINK_SORTS = frozenset(
    {
        "CREATED_AT_DESC",
        "TODAY_PV_DESC",
        "TODAY_UV_DESC",
        "TODAY_UIP_DESC",
        "TOTAL_PV_DESC",
        "TOTAL_UV_DESC",
        "TOTAL_UIP_DESC",
    }
)
_SHORT_LINK_SORT_TO_LEGACY = {
    "CREATED_AT_DESC": None,
    "TODAY_PV_DESC": "todayPv",
    "TODAY_UV_DESC": "todayUv",
    "TODAY_UIP_DESC": "todayUip",
    "TOTAL_PV_DESC": "totalPv",
    "TOTAL_UV_DESC": "totalUv",
    "TOTAL_UIP_DESC": "totalUip",
}
_GID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_SHORT_URL_PATTERN = re.compile(r"^[^\s\x00-\x1f\x7f]{1,2048}$")
_SNAPSHOT_ID_PATTERN = re.compile(r"^snap-[A-Za-z0-9_-]{1,128}$")
_CONTENT_HASH_PATTERN = re.compile(r"^sha256:[a-f0-9]{64}$")
_RFC3339_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$"
)

ALLOWED_CAPABILITY_OPERATIONS = frozenset(
    {
        ("GET", _GROUPS_LEGACY_PATH),
        ("GET", _SHORT_LINKS_LEGACY_PATH),
        ("GET", "/internal/short-link-admin/v1/agent-tools/short-link/stats"),
        ("GET", _GROUP_STATS_LEGACY_PATH),
        ("GET", "/internal/short-link-admin/v1/agent-tools/group/access-records"),
        ("POST", _GROUPS_V1_PATH),
        ("POST", _SHORT_LINKS_V1_PATH),
        ("POST", _GROUP_STATS_V1_PATH),
    },
)

_PROBLEM_MESSAGES = {
    "VALIDATION_FAILED": "The Java capability rejected the query.",
    "CAPABILITY_FORBIDDEN": "The Java capability denied access to the requested resource.",
    "CAPABILITY_PROVIDER_FAILED": "The Java capability provider failed.",
}


class HttpAuthorityCapabilityGateway:
    """Call only explicit Java capabilities using the configured identity mode."""

    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        base_url = self._validated_base_url(settings.authority_base_url)
        self._internal_token = settings.authority_token_value()
        self._max_response_bytes = settings.capability_max_response_bytes
        self._groups_list_contract = settings.groups_list_contract
        self._short_links_contract = settings.short_links_contract
        self._group_stats_contract = settings.group_stats_contract
        self._v1_capability_auth_mode = settings.v1_capability_auth_mode
        self._token_exchange = (
            AuthorityTokenExchangeClient(settings, transport=transport)
            if settings.v1_capability_auth_mode == "token_exchange"
            else None
        )
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=httpx.Timeout(settings.capability_timeout_seconds),
            follow_redirects=False,
            verify=(
                authority_ssl_context(settings)
                if settings.v1_capability_auth_mode == "token_exchange"
                else True
            ),
            transport=transport,
        )

    async def list_groups(
        self,
        context: CapabilityCallContext,
    ) -> CapabilityResult:
        if self._groups_list_contract == "legacy":
            data = await self.get(_GROUPS_LEGACY_PATH, context, {})
            return CapabilityResult(data=data)

        raw_body = await self._request(
            "POST",
            _GROUPS_V1_PATH,
            context,
            json_body={},
        )
        return self._parse_groups_list_v1(raw_body, context)

    async def get(
        self,
        path: str,
        context: CapabilityCallContext,
        query: Mapping[str, object],
    ) -> Any:
        raw_body = await self._request(
            "GET",
            path,
            context,
            query=query,
        )
        body = self._json_object(raw_body)
        if body.get("success") is not True and str(body.get("code")) != "0":
            raise CapabilityError(
                "CAPABILITY_REJECTED",
                "The Java capability rejected the business request.",
            )
        return body.get("data")

    async def query_short_links(
        self,
        context: CapabilityCallContext,
        query: ShortLinksCapabilityQuery,
    ) -> CapabilityResult:
        self._validate_short_links_query(query)
        if self._short_links_contract == "legacy":
            data = await self.get(
                _SHORT_LINKS_LEGACY_PATH,
                context,
                {
                    "gid": query.gid,
                    "current": query.current,
                    "size": query.size,
                    "orderTag": _SHORT_LINK_SORT_TO_LEGACY.get(query.sort),
                },
            )
            return CapabilityResult(data=data)

        raw_body = await self._request(
            "POST",
            _SHORT_LINKS_V1_PATH,
            context,
            json_body={
                "gid": query.gid,
                "current": query.current,
                "size": query.size,
                "sort": query.sort,
            },
        )
        return self._parse_short_links_v1(raw_body, context, query)

    async def query_group_stats(
        self,
        context: CapabilityCallContext,
        query: GroupStatsCapabilityQuery,
    ) -> CapabilityResult:
        if self._group_stats_contract == "legacy":
            data = await self.get(
                _GROUP_STATS_LEGACY_PATH,
                context,
                {
                    "gid": query.gid,
                    "startDate": query.start.date().isoformat(),
                    "endDate": (query.end.date() - timedelta(days=1)).isoformat(),
                },
            )
            return CapabilityResult(data=data)

        raw_body = await self._request(
            "POST",
            _GROUP_STATS_V1_PATH,
            context,
            json_body={
                "gid": query.gid,
                "timeRange": {
                    "start": query.start.isoformat(),
                    "end": query.end.isoformat(),
                    "timezone": query.timezone,
                },
            },
        )
        return self._parse_group_stats_v1(raw_body, context, query)

    async def aclose(self) -> None:
        await self._client.aclose()
        if self._token_exchange is not None:
            await self._token_exchange.aclose()

    async def _request(
        self,
        method: str,
        path: str,
        context: CapabilityCallContext,
        *,
        query: Mapping[str, object] | None = None,
        json_body: Mapping[str, object] | None = None,
    ) -> bytes:
        operation = (method, path)
        if operation not in ALLOWED_CAPABILITY_OPERATIONS:
            raise CapabilityError(
                "CAPABILITY_NOT_ALLOWLISTED",
                "The requested Java capability is not allowed.",
            )

        try:
            headers = await self._headers(context, path)
            async with self._client.stream(
                method,
                path,
                params=(
                    {key: value for key, value in query.items() if value is not None}
                    if query
                    else None
                ),
                json=json_body,
                headers=headers,
            ) as response:
                self._check_content_length(response)
                raw_body = await self._read_limited(response)
        except httpx.TimeoutException as exc:
            raise CapabilityError(
                "CAPABILITY_TIMEOUT",
                "The Java capability request timed out.",
                retryable=True,
            ) from exc
        except httpx.HTTPError as exc:
            raise CapabilityError(
                "CAPABILITY_UNAVAILABLE",
                "The Java capability is unavailable.",
                retryable=True,
            ) from exc

        if response.status_code < 200 or response.status_code >= 300:
            self._raise_problem(response.status_code, raw_body)
        return raw_body

    def _parse_group_stats_v1(
        self,
        raw_body: bytes,
        context: CapabilityCallContext,
        query: GroupStatsCapabilityQuery,
    ) -> CapabilityResult:
        data, snapshot, warnings = self._parse_v1_envelope(
            raw_body,
            context,
            source="admin/group-stats",
        )
        if not isinstance(data, dict) or set(data) != _GROUP_STATS_FIELDS:
            self._invalid_contract()
        if data.get("gid") != query.gid or not _GID_PATTERN.fullmatch(query.gid):
            self._invalid_contract()
        for metric in ("pv", "uv", "uip"):
            value = data.get(metric)
            if type(value) is not int or value < 0:
                self._invalid_contract()

        expected_hash = self._content_hash(data)
        if snapshot.get("contentHash") != expected_hash:
            raise CapabilityError(
                "CAPABILITY_CONTENT_HASH_MISMATCH",
                "The Java capability response failed integrity validation.",
            )
        return CapabilityResult(
            data=data,
            snapshot={
                "snapshotId": snapshot["snapshotId"],
                "source": snapshot["source"],
                "observedAt": snapshot["observedAt"],
                "expiresAt": snapshot["expiresAt"],
                "contentHash": snapshot["contentHash"],
            },
            warnings=tuple(warnings),
        )

    def _parse_groups_list_v1(
        self,
        raw_body: bytes,
        context: CapabilityCallContext,
    ) -> CapabilityResult:
        data, snapshot, warnings = self._parse_v1_envelope(
            raw_body,
            context,
            source="admin/groups",
        )
        if not isinstance(data, list) or len(data) > 1_000:
            self._invalid_contract()

        normalized: list[dict[str, object]] = []
        gids: set[str] = set()
        for group in data:
            if not isinstance(group, dict) or set(group) != _GROUP_FIELDS:
                self._invalid_contract()
            gid = group.get("gid")
            name = group.get("name")
            sort_order = group.get("sortOrder")
            short_link_count = group.get("shortLinkCount")
            if (
                not isinstance(gid, str)
                or not _GID_PATTERN.fullmatch(gid)
                or gid in gids
                or not isinstance(name, str)
                or not 1 <= len(name) <= 128
                or type(sort_order) is not int
                or not -(2**31) <= sort_order <= 2**31 - 1
                or type(short_link_count) is not int
                or not 0 <= short_link_count <= 2**31 - 1
            ):
                self._invalid_contract()
            gids.add(gid)
            normalized.append(
                {
                    "gid": gid,
                    "name": name,
                    "sortOrder": sort_order,
                    "shortLinkCount": short_link_count,
                }
            )

        if snapshot["contentHash"] != self._content_hash(normalized):
            raise CapabilityError(
                "CAPABILITY_CONTENT_HASH_MISMATCH",
                "The Java capability response failed integrity validation.",
            )
        return CapabilityResult(
            data=normalized,
            snapshot=snapshot,
            warnings=tuple(warnings),
        )

    def _parse_short_links_v1(
        self,
        raw_body: bytes,
        context: CapabilityCallContext,
        query: ShortLinksCapabilityQuery,
    ) -> CapabilityResult:
        data, snapshot, warnings = self._parse_v1_envelope(
            raw_body,
            context,
            source="admin/short-links",
        )
        if not isinstance(data, dict) or set(data) != _SHORT_LINKS_DATA_FIELDS:
            self._invalid_contract()
        if query.sort not in _SHORT_LINK_SORTS:
            self._invalid_contract()

        current = data.get("current")
        size = data.get("size")
        total = data.get("total")
        pages = data.get("pages")
        has_next = data.get("hasNext")
        records = data.get("records")
        if (
            data.get("gid") != query.gid
            or not _GID_PATTERN.fullmatch(query.gid)
            or current != query.current
            or type(current) is not int
            or not 1 <= current <= 10_000
            or size != query.size
            or type(size) is not int
            or not 1 <= size <= 500
            or type(total) is not int
            or not 0 <= total <= 9_007_199_254_740_991
            or type(pages) is not int
            or not 0 <= pages <= 9_007_199_254_740_991
            or type(has_next) is not bool
            or data.get("sort") != query.sort
            or not isinstance(records, list)
            or len(records) > size
            or total < len(records)
        ):
            self._invalid_contract()
        expected_pages = 0 if total == 0 else ((total - 1) // size) + 1
        if (
            pages != expected_pages
            or has_next != (current < pages)
            or (current > pages and records)
        ):
            self._invalid_contract()

        normalized_records: list[dict[str, object]] = []
        full_short_urls: set[str] = set()
        for record in records:
            normalized_records.append(self._parse_short_link_record(record, full_short_urls))
        normalized: dict[str, object] = {
            "gid": query.gid,
            "current": current,
            "size": size,
            "total": total,
            "pages": pages,
            "hasNext": has_next,
            "sort": query.sort,
            "records": normalized_records,
        }
        if snapshot["contentHash"] != self._content_hash(normalized):
            raise CapabilityError(
                "CAPABILITY_CONTENT_HASH_MISMATCH",
                "The Java capability response failed integrity validation.",
            )
        return CapabilityResult(
            data=normalized,
            snapshot=snapshot,
            warnings=tuple(warnings),
        )

    def _parse_short_link_record(
        self,
        record: object,
        full_short_urls: set[str],
    ) -> dict[str, object]:
        if not isinstance(record, dict) or set(record) != _SHORT_LINK_FIELDS:
            self._invalid_contract()
        full_short_url = record.get("fullShortUrl")
        describe = record.get("describe")
        validity = record.get("validity")
        expires_at = record.get("expiresAt")
        created_at = record.get("createdAt")
        if (
            not isinstance(full_short_url, str)
            or not _SHORT_URL_PATTERN.fullmatch(full_short_url)
            or full_short_url in full_short_urls
            or (describe is not None and (not isinstance(describe, str) or len(describe) > 1_024))
            or validity not in {"PERMANENT", "CUSTOM"}
        ):
            self._invalid_contract()
        created = self._date_time(created_at)
        if validity == "PERMANENT":
            if expires_at is not None:
                self._invalid_contract()
        else:
            expires = self._date_time(expires_at)
            if expires <= created:
                self._invalid_contract()
        for metric in (
            "todayPv",
            "todayUv",
            "todayUip",
            "totalPv",
            "totalUv",
            "totalUip",
        ):
            value = record.get(metric)
            if type(value) is not int or not 0 <= value <= 2**31 - 1:
                self._invalid_contract()
        full_short_urls.add(full_short_url)
        return {
            "fullShortUrl": full_short_url,
            "describe": describe,
            "validity": validity,
            "expiresAt": expires_at,
            "createdAt": created_at,
            "todayPv": record["todayPv"],
            "todayUv": record["todayUv"],
            "todayUip": record["todayUip"],
            "totalPv": record["totalPv"],
            "totalUv": record["totalUv"],
            "totalUip": record["totalUip"],
        }

    @staticmethod
    def _validate_short_links_query(query: ShortLinksCapabilityQuery) -> None:
        if (
            not isinstance(query.gid, str)
            or not _GID_PATTERN.fullmatch(query.gid)
            or type(query.current) is not int
            or not 1 <= query.current <= 10_000
            or type(query.size) is not int
            or not 1 <= query.size <= 500
            or query.sort not in _SHORT_LINK_SORTS
        ):
            raise CapabilityError(
                "CAPABILITY_QUERY_INVALID",
                "The short-link capability query is invalid.",
            )

    def _parse_v1_envelope(
        self,
        raw_body: bytes,
        context: CapabilityCallContext,
        *,
        source: str,
    ) -> tuple[Any, dict[str, str], list[str]]:
        body = self._json_object(raw_body)
        if set(body) != _V1_ENVELOPE_FIELDS:
            self._invalid_contract()
        if body.get("schemaVersion") != "1.0" or body.get("requestId") != context.trace_id:
            self._invalid_contract()

        snapshot = body.get("snapshot")
        warnings = body.get("warnings")
        if not isinstance(snapshot, dict) or set(snapshot) != _SNAPSHOT_FIELDS:
            self._invalid_contract()
        if not isinstance(warnings, list) or len(warnings) > 32:
            self._invalid_contract()
        if any(not isinstance(warning, str) or len(warning) > 512 for warning in warnings):
            self._invalid_contract()

        snapshot_id = snapshot.get("snapshotId")
        observed_at = snapshot.get("observedAt")
        expires_at = snapshot.get("expiresAt")
        content_hash = snapshot.get("contentHash")
        if (
            not isinstance(snapshot_id, str)
            or not _SNAPSHOT_ID_PATTERN.fullmatch(snapshot_id)
            or snapshot.get("source") != source
            or not isinstance(content_hash, str)
            or not _CONTENT_HASH_PATTERN.fullmatch(content_hash)
        ):
            self._invalid_contract()
        observed = self._date_time(observed_at)
        expires = self._date_time(expires_at)
        if expires <= observed:
            self._invalid_contract()

        return (
            body.get("data"),
            {
                "snapshotId": snapshot_id,
                "source": source,
                "observedAt": observed_at,
                "expiresAt": expires_at,
                "contentHash": content_hash,
            },
            warnings,
        )

    async def _headers(
        self,
        context: CapabilityCallContext,
        path: str,
    ) -> dict[str, str]:
        headers = {
            "Accept": "application/json",
            "X-Agent-Session-ID": context.session_id,
            "X-Agent-Trace-ID": context.trace_id,
            "X-Agent-Run-ID": context.trace_id,
            "X-Agent-Tool-Call-ID": f"tool-{uuid4().hex}",
            "X-Request-ID": context.trace_id,
        }
        required_scopes = _V1_CAPABILITY_SCOPES.get(path)
        if self._v1_capability_auth_mode == "token_exchange" and required_scopes is not None:
            if self._token_exchange is None:
                raise CapabilityError(
                    "TOKEN_EXCHANGE_NOT_CONFIGURED",
                    "The Authority Token exchange is not configured.",
                )
            headers["Authorization"] = await self._token_exchange.authorization(
                required_scopes,
                request_id=context.trace_id,
            )
            return headers

        headers["X-Agent-Username"] = context.actor.username
        if context.actor.user_id:
            headers["X-Agent-UserId"] = context.actor.user_id
        if context.actor.real_name:
            headers["X-Agent-RealName"] = context.actor.real_name
        if self._internal_token:
            headers["X-Agent-Internal-Token"] = self._internal_token
        return headers

    def _raise_problem(self, status_code: int, raw_body: bytes) -> None:
        try:
            body = self._json_object(raw_body)
        except CapabilityError:
            body = {}
        code = body.get("code")
        if code in _PROBLEM_MESSAGES:
            raise CapabilityError(
                code,
                _PROBLEM_MESSAGES[code],
                retryable=bool(body.get("retryable")),
            )
        raise CapabilityError(
            "CAPABILITY_HTTP_ERROR",
            "The Java capability rejected the request.",
            retryable=status_code >= 500,
        )

    def _json_object(self, raw_body: bytes) -> dict[str, Any]:
        try:
            body = json.loads(raw_body)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise CapabilityError(
                "CAPABILITY_RESPONSE_INVALID",
                "The Java capability returned an invalid response.",
            ) from exc
        if not isinstance(body, dict):
            self._invalid_contract()
        return body

    @staticmethod
    def _content_hash(
        data: Mapping[str, Any] | list[dict[str, object]],
    ) -> str:
        canonical = json.dumps(
            data,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return f"sha256:{hashlib.sha256(canonical).hexdigest()}"

    @staticmethod
    def _date_time(value: object) -> datetime:
        if not isinstance(value, str) or not _RFC3339_PATTERN.fullmatch(value):
            HttpAuthorityCapabilityGateway._invalid_contract()
        try:
            normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
            parsed = datetime.fromisoformat(normalized)
        except ValueError:
            HttpAuthorityCapabilityGateway._invalid_contract()
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            HttpAuthorityCapabilityGateway._invalid_contract()
        return parsed

    @staticmethod
    def _invalid_contract() -> Never:
        raise CapabilityError(
            "CAPABILITY_RESPONSE_INVALID",
            "The Java capability returned an invalid response.",
        )

    def _check_content_length(self, response: httpx.Response) -> None:
        value = response.headers.get("Content-Length")
        if value is None:
            return
        try:
            content_length = int(value)
        except ValueError:
            return
        if content_length > self._max_response_bytes:
            raise CapabilityError(
                "CAPABILITY_RESPONSE_TOO_LARGE",
                "The Java capability response exceeded the allowed size.",
            )

    async def _read_limited(self, response: httpx.Response) -> bytes:
        body = bytearray()
        async for chunk in response.aiter_bytes():
            body.extend(chunk)
            if len(body) > self._max_response_bytes:
                raise CapabilityError(
                    "CAPABILITY_RESPONSE_TOO_LARGE",
                    "The Java capability response exceeded the allowed size.",
                )
        return bytes(body)

    @staticmethod
    def _validated_base_url(value: str) -> str:
        try:
            url = httpx.URL(value)
        except httpx.InvalidURL as exc:
            raise ValueError("authority_base_url is invalid") from exc
        if url.scheme not in {"http", "https"} or not url.host:
            raise ValueError("authority_base_url must be an absolute HTTP(S) URL")
        if url.username or url.password:
            raise ValueError("authority_base_url must not contain credentials")
        return str(url).rstrip("/")
