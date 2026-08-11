"""Deterministic minimization before data reaches a model or response."""

from collections.abc import Mapping, Sequence
from itertools import islice
from typing import Any


class AgentDataSanitizer:
    """Remove credentials and mask identity-bearing fields recursively."""

    _removed_keys = frozenset(
        {
            "authorization",
            "cookie",
            "credential",
            "password",
            "secret",
            "token",
        },
    )
    _masked_keys = frozenset(
        {
            "clientip",
            "destinationip",
            "ip",
            "ipaddress",
            "realname",
            "remoteip",
            "sourceip",
            "user",
            "userid",
            "username",
        },
    )

    def __init__(
        self,
        *,
        max_depth: int = 8,
        max_collection_items: int = 200,
        max_string_length: int = 4_096,
    ) -> None:
        self._max_depth = max_depth
        self._max_collection_items = max_collection_items
        self._max_string_length = max_string_length

    def sanitize(self, value: Any) -> Any:
        return self._sanitize(value, depth=0)

    def _sanitize(self, value: Any, *, depth: int) -> Any:
        if depth >= self._max_depth:
            return "[truncated]"
        if isinstance(value, Mapping):
            result: dict[str, Any] = {}
            for index, (key, item) in enumerate(value.items()):
                if index >= self._max_collection_items:
                    result["_truncated"] = True
                    break
                text_key = str(key)
                normalized_key = text_key.replace("_", "").replace("-", "").lower()
                if any(secret in normalized_key for secret in self._removed_keys):
                    continue
                if normalized_key in self._masked_keys:
                    result[text_key] = self._mask(text_key, item)
                else:
                    result[text_key] = self._sanitize(item, depth=depth + 1)
            return result
        if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
            items = list(islice(value, self._max_collection_items + 1))
            truncated = len(items) > self._max_collection_items
            result = [
                self._sanitize(item, depth=depth + 1)
                for item in items[: self._max_collection_items]
            ]
            if truncated:
                result.append("[truncated]")
            return result
        if isinstance(value, str):
            return value[: self._max_string_length]
        if value is None or isinstance(value, (bool, int, float)):
            return value
        return str(value)[: self._max_string_length]

    def _mask(self, key: str, value: Any) -> str:
        text = str(value or "")
        normalized_key = key.replace("_", "").replace("-", "").lower()
        if normalized_key in {
            "clientip",
            "destinationip",
            "ip",
            "ipaddress",
            "remoteip",
            "sourceip",
        }:
            parts = text.split(".")
            if len(parts) == 4:
                return f"{parts[0]}.{parts[1]}.*.*"
        if len(text) <= 2:
            return "***"
        return f"{text[:2]}***"
