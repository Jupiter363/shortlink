"""HTTP response models."""

from datetime import datetime
from typing import Any, Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class ResultEnvelope(BaseModel, Generic[T]):
    """Compatibility envelope used by the current Java clients."""

    model_config = ConfigDict(frozen=True)

    success: bool
    code: str
    message: str
    data: T

    @classmethod
    def ok(cls, data: T) -> "ResultEnvelope[T]":
        return cls(success=True, code="0", message="success", data=data)


class LegacyErrorEnvelope(BaseModel):
    """Stable error shape for callers of the transitional Java contract."""

    model_config = ConfigDict(frozen=True)

    success: Literal[False] = False
    code: str
    message: str
    data: None = None
    retryable: bool = False


class LegacyHealthData(BaseModel):
    """Payload expected by the existing admin Feign client."""

    model_config = ConfigDict(frozen=True)

    status: Literal["OK"] = "OK"
    service: str


class LivenessResponse(BaseModel):
    """Process liveness response."""

    model_config = ConfigDict(frozen=True)

    status: Literal["UP"] = "UP"
    service: str
    version: str
    timestamp: datetime


class DependencyCheck(BaseModel):
    """One readiness dependency check."""

    model_config = ConfigDict(frozen=True)

    name: str
    status: Literal["UP", "DOWN"]
    required_version: str | None = Field(default=None, alias="requiredVersion")
    installed_version: str | None = Field(default=None, alias="installedVersion")
    detail: str | None = None


class ReadinessResponse(BaseModel):
    """Aggregate readiness response."""

    model_config = ConfigDict(frozen=True)

    status: Literal["UP", "DOWN"]
    service: str
    version: str
    checks: list[DependencyCheck]
    timestamp: datetime


class AgentChatRequest(BaseModel):
    """Compatibility request accepted from the authenticated Java admin."""

    model_config = ConfigDict(
        frozen=True,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )

    session_id: str = Field(alias="sessionId", min_length=1, max_length=128)
    agent_type: str | None = Field(default=None, alias="agentType", max_length=64)
    message: str = Field(min_length=1, max_length=16_000)


class ToolExecutionResponse(BaseModel):
    """Sanitized compatibility view of one Agent tool call."""

    model_config = ConfigDict(
        frozen=True,
        from_attributes=True,
        populate_by_name=True,
    )

    name: str
    arguments: dict[str, Any]
    success: bool
    duration_ms: int = Field(alias="durationMs")
    data: Any = None
    snapshot: dict[str, Any] | None = None
    warnings: list[str] = Field(default_factory=list)
    error_code: str | None = Field(default=None, alias="errorCode")
    error_message: str | None = Field(default=None, alias="errorMessage")


class LegacyAgentRunData(BaseModel):
    """Response compatible with the existing Java AgentRunResult record."""

    model_config = ConfigDict(
        frozen=True,
        from_attributes=True,
        populate_by_name=True,
    )

    session_id: str = Field(alias="sessionId")
    trace_id: str = Field(alias="traceId")
    answer: str
    cards: list[dict[str, Any]]
    pending_actions: list[dict[str, Any]] = Field(alias="pendingActions")
    tool_calls: list[ToolExecutionResponse] = Field(alias="toolCalls")
    data_sources: list[dict[str, Any]] = Field(alias="dataSources")
    trace_events: list[dict[str, Any]] = Field(alias="traceEvents")
    warnings: list[str]
