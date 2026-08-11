"""Framework-neutral application models."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


@dataclass(frozen=True, slots=True)
class TrustedActor:
    """Identity asserted by the Java admin compatibility boundary."""

    username: str
    user_id: str | None = None
    real_name: str | None = None


@dataclass(frozen=True, slots=True)
class AgentRunCommand:
    """One synchronous compatibility run request."""

    session_id: str
    agent_type: str
    message: str


@dataclass(frozen=True, slots=True)
class CapabilityCallContext:
    """Trusted context forwarded to a Java authority capability."""

    actor: TrustedActor
    session_id: str
    trace_id: str


@dataclass(frozen=True, slots=True)
class GroupStatsCapabilityQuery:
    """Versioned semantic query sent to the Java authority."""

    gid: str
    start: datetime
    end: datetime
    timezone: str


@dataclass(frozen=True, slots=True)
class ShortLinksCapabilityQuery:
    """Versioned short-link page query sent to the Java authority."""

    gid: str
    current: int
    size: int
    sort: str


@dataclass(frozen=True, slots=True)
class CapabilityResult:
    """Data plus authority-owned snapshot provenance."""

    data: Any
    snapshot: dict[str, Any] | None = None
    warnings: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class ToolExecution:
    """Sanitized record of one read-only capability invocation."""

    name: str
    arguments: dict[str, Any]
    success: bool
    duration_ms: int
    data: Any = None
    snapshot: dict[str, Any] | None = None
    warnings: tuple[str, ...] = ()
    error_code: str | None = None
    error_message: str | None = None


@dataclass(frozen=True, slots=True)
class AgentRunResult:
    """Product response independent from AgentScope message types."""

    session_id: str
    trace_id: str
    answer: str
    cards: tuple[dict[str, Any], ...] = ()
    pending_actions: tuple[dict[str, Any], ...] = ()
    tool_calls: tuple[ToolExecution, ...] = ()
    data_sources: tuple[dict[str, Any], ...] = ()
    trace_events: tuple[dict[str, Any], ...] = ()
    warnings: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class ReadOnlyToolDefinition:
    """Agent-framework-neutral definition of one allowed tool."""

    name: str
    description: str
    handler: Callable[..., Awaitable[Any]] = field(repr=False)
