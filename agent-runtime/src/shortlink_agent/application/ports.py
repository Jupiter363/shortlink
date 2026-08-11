"""Application ports implemented by infrastructure adapters."""

from collections.abc import Mapping
from typing import Any, Protocol

from shortlink_agent.application.models import (
    AgentRunCommand,
    AgentRunResult,
    CapabilityCallContext,
    CapabilityResult,
    GroupStatsCapabilityQuery,
    ShortLinksCapabilityQuery,
    TrustedActor,
)


class AgentRuntimePort(Protocol):
    """Execute product Agent runs without exposing framework objects."""

    async def run(
        self,
        command: AgentRunCommand,
        actor: TrustedActor,
    ) -> AgentRunResult:
        """Execute one Agent run."""

        ...


class CapabilityGateway(Protocol):
    """Invoke a narrow, authorized Java capability."""

    async def list_groups(
        self,
        context: CapabilityCallContext,
    ) -> CapabilityResult:
        """List groups through the versioned authority capability."""

        ...

    async def get(
        self,
        path: str,
        context: CapabilityCallContext,
        query: Mapping[str, object],
    ) -> Any:
        """Invoke an allowlisted read-only capability."""

        ...

    async def query_short_links(
        self,
        context: CapabilityCallContext,
        query: ShortLinksCapabilityQuery,
    ) -> CapabilityResult:
        """Query the versioned page of short links in an owned group."""

        ...

    async def query_group_stats(
        self,
        context: CapabilityCallContext,
        query: GroupStatsCapabilityQuery,
    ) -> CapabilityResult:
        """Query the versioned group statistics capability."""

        ...

    async def aclose(self) -> None:
        """Release transport resources."""

        ...
