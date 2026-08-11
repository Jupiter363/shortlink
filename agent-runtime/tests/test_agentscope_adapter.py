"""AgentScope runtime adapter tests without model network calls."""

from collections.abc import Sequence
from typing import Any

import pytest
from pydantic import SecretStr

from shortlink_agent.application.errors import (
    AgentExecutionFailedError,
    ModelNotConfiguredError,
    UnsupportedAgentTypeError,
)
from shortlink_agent.application.models import (
    AgentRunCommand,
    CapabilityCallContext,
    CapabilityResult,
    GroupStatsCapabilityQuery,
    ReadOnlyToolDefinition,
    ShortLinksCapabilityQuery,
    TrustedActor,
)
from shortlink_agent.config import Settings
from shortlink_agent.runtime.agentscope_adapter import (
    AgentScopeCampaignFactory,
    AgentScopeRuntimeAdapter,
    CampaignReadOnlyToolset,
)
from shortlink_agent.security.sanitization import AgentDataSanitizer


class StubCapabilityGateway:
    def __init__(self) -> None:
        self.calls: list[tuple[str, CapabilityCallContext, dict[str, object]]] = []
        self.closed = False

    async def get(
        self,
        path: str,
        context: CapabilityCallContext,
        query: dict[str, object],
    ) -> Any:
        self.calls.append((path, context, query))
        return {
            "pv": 100,
            "uv": 50,
            "ip": "10.20.30.40",
            "apiToken": "must-not-reach-model",
        }

    async def list_groups(
        self,
        context: CapabilityCallContext,
    ) -> CapabilityResult:
        self.calls.append(("groups-v1", context, {}))
        return CapabilityResult(
            data=[
                {
                    "gid": "g1",
                    "name": "Marketing",
                    "sortOrder": 10,
                    "shortLinkCount": 3,
                }
            ],
            snapshot={
                "snapshotId": "snap-groups-1",
                "source": "admin/groups",
                "observedAt": "2026-07-17T02:00:00Z",
                "expiresAt": "2026-07-17T02:05:00Z",
                "contentHash": "sha256:test",
            },
        )

    async def aclose(self) -> None:
        self.closed = True

    async def query_group_stats(
        self,
        context: CapabilityCallContext,
        query: GroupStatsCapabilityQuery,
    ) -> CapabilityResult:
        self.calls.append(("group-stats-v1", context, {"gid": query.gid}))
        return CapabilityResult(
            data={
                "gid": query.gid,
                "pv": 100,
                "uv": 50,
                "ip": "10.20.30.40",
                "apiToken": "must-not-reach-model",
            },
            snapshot={
                "snapshotId": "snap-1",
                "source": "admin/group-stats",
                "observedAt": "2026-07-17T02:00:00Z",
                "expiresAt": "2026-07-17T02:05:00Z",
                "contentHash": "sha256:test",
            },
            warnings=("provider warning",),
        )

    async def query_short_links(
        self,
        context: CapabilityCallContext,
        query: ShortLinksCapabilityQuery,
    ) -> CapabilityResult:
        self.calls.append(
            (
                "short-links-v1",
                context,
                {
                    "gid": query.gid,
                    "current": query.current,
                    "size": query.size,
                    "sort": query.sort,
                },
            )
        )
        return CapabilityResult(
            data={
                "gid": query.gid,
                "current": query.current,
                "size": query.size,
                "total": 0,
                "pages": 0,
                "hasNext": False,
                "sort": query.sort,
                "records": [],
            },
            snapshot={
                "snapshotId": "snap-short-links-1",
                "source": "admin/short-links",
                "observedAt": "2026-07-17T02:00:00Z",
                "expiresAt": "2026-07-17T02:05:00Z",
                "contentHash": "sha256:test",
            },
        )


class ToolCallingSession:
    def __init__(self, tools: Sequence[ReadOnlyToolDefinition]) -> None:
        self._tools = {tool.name: tool for tool in tools}

    async def reply(self, message: str) -> str:
        tool = self._tools["get_group_stats"]
        result = await tool.handler(
            gid="g1",
            start_date="2026-07-01",
            end_date="2026-07-07",
        )
        assert result["status"] == "SUCCESS"
        assert result["data"]["ip"] == "10.20.*.*"
        assert "apiToken" not in result["data"]
        assert result["snapshot"]["snapshotId"] == "snap-1"
        return f"completed: {message}"


class StubAgentFactory:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.session_id: str | None = None
        self.tools: Sequence[ReadOnlyToolDefinition] = ()

    def create(
        self,
        session_id: str,
        tools: Sequence[ReadOnlyToolDefinition],
    ) -> ToolCallingSession:
        if self.error:
            raise self.error
        self.session_id = session_id
        self.tools = tools
        return ToolCallingSession(tools)


def adapter_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "environment": "test",
        "model_api_key": SecretStr("model-key"),
        "model_name": "deepseek-test",
    }
    values.update(overrides)
    return Settings(**values)


def test_agentscope_factory_registers_only_explicit_read_only_tools() -> None:
    settings = adapter_settings()
    gateway = StubCapabilityGateway()
    toolset = CampaignReadOnlyToolset(
        gateway,
        CapabilityCallContext(TrustedActor("user"), "session-1", "trace-1"),
        settings,
        AgentDataSanitizer(),
    )

    session = AgentScopeCampaignFactory(settings).create("session-1", toolset.definitions())
    registered_tools = session._agent.toolkit.tool_groups[0].tools

    assert {tool.name for tool in registered_tools} == {
        "list_groups",
        "page_short_links",
        "get_short_link_stats",
        "get_group_stats",
        "get_group_access_records",
    }
    assert all(tool.is_read_only for tool in registered_tools)


@pytest.mark.asyncio
async def test_list_groups_tool_uses_versioned_semantic_gateway_and_keeps_snapshot() -> None:
    settings = adapter_settings()
    gateway = StubCapabilityGateway()
    toolset = CampaignReadOnlyToolset(
        gateway,
        CapabilityCallContext(TrustedActor("trusted-user"), "session-1", "trace-1"),
        settings,
        AgentDataSanitizer(),
    )

    result = await toolset.list_groups()

    assert result["status"] == "SUCCESS"
    assert result["data"][0]["gid"] == "g1"
    assert result["snapshot"]["source"] == "admin/groups"
    assert gateway.calls == [
        (
            "groups-v1",
            CapabilityCallContext(TrustedActor("trusted-user"), "session-1", "trace-1"),
            {},
        )
    ]
    assert toolset.executions[0].name == "list_groups"
    assert toolset.executions[0].snapshot["snapshotId"] == "snap-groups-1"


@pytest.mark.asyncio
async def test_adapter_runs_read_only_tools_and_returns_framework_neutral_result() -> None:
    gateway = StubCapabilityGateway()
    factory = StubAgentFactory()
    adapter = AgentScopeRuntimeAdapter(
        adapter_settings(),
        gateway,
        agent_factory=factory,
    )

    result = await adapter.run(
        AgentRunCommand("session-1", "campaign-analysis", "analyze g1"),
        TrustedActor("trusted-user", "1001", "Trusted User"),
    )

    assert result.session_id == "session-1"
    assert result.answer == "completed: analyze g1"
    assert factory.session_id == "session-1"
    assert {tool.name for tool in factory.tools} == {
        "list_groups",
        "page_short_links",
        "get_short_link_stats",
        "get_group_stats",
        "get_group_access_records",
    }
    assert gateway.calls[0][1].actor.username == "trusted-user"
    assert result.tool_calls[0].data == {
        "gid": "g1",
        "pv": 100,
        "uv": 50,
        "ip": "10.20.*.*",
    }
    assert result.tool_calls[0].snapshot["source"] == "admin/group-stats"
    assert result.warnings == ("provider warning",)
    assert result.data_sources[0]["framework"] == "agentscope"
    assert result.data_sources[1] == {
        "type": "llm",
        "provider": "deepseek",
        "model": "deepseek-test",
    }


@pytest.mark.asyncio
async def test_adapter_rejects_agent_types_not_yet_migrated() -> None:
    adapter = AgentScopeRuntimeAdapter(
        adapter_settings(),
        StubCapabilityGateway(),
        agent_factory=StubAgentFactory(),
    )

    with pytest.raises(UnsupportedAgentTypeError):
        await adapter.run(
            AgentRunCommand("session-1", "security-risk", "analyze"),
            TrustedActor("trusted-user"),
        )


@pytest.mark.asyncio
async def test_adapter_fails_closed_when_model_key_is_missing() -> None:
    factory = StubAgentFactory()
    adapter = AgentScopeRuntimeAdapter(
        adapter_settings(model_api_key=None),
        StubCapabilityGateway(),
        agent_factory=factory,
    )

    with pytest.raises(ModelNotConfiguredError):
        await adapter.run(
            AgentRunCommand("session-1", "campaign-analysis", "analyze"),
            TrustedActor("trusted-user"),
        )

    assert factory.session_id is None


@pytest.mark.asyncio
async def test_adapter_maps_factory_failures_to_stable_error() -> None:
    adapter = AgentScopeRuntimeAdapter(
        adapter_settings(),
        StubCapabilityGateway(),
        agent_factory=StubAgentFactory(RuntimeError("secret provider detail")),
    )

    with pytest.raises(AgentExecutionFailedError):
        await adapter.run(
            AgentRunCommand("session-1", "campaign-analysis", "analyze"),
            TrustedActor("trusted-user"),
        )


@pytest.mark.asyncio
async def test_adapter_closes_capability_transport() -> None:
    gateway = StubCapabilityGateway()
    adapter = AgentScopeRuntimeAdapter(
        adapter_settings(),
        gateway,
        agent_factory=StubAgentFactory(),
    )

    await adapter.aclose()

    assert gateway.closed is True


@pytest.mark.asyncio
async def test_tool_input_bounds_block_invalid_page_before_java_call() -> None:
    gateway = StubCapabilityGateway()
    toolset = CampaignReadOnlyToolset(
        gateway,
        CapabilityCallContext(TrustedActor("user"), "session-1", "trace-1"),
        adapter_settings(capability_max_page_size=50),
        AgentDataSanitizer(),
    )
    tools = {definition.name: definition for definition in toolset.definitions()}

    result = await tools["page_short_links"].handler(
        gid="g1",
        current=1,
        size=51,
    )

    assert result["error"]["code"] == "TOOL_INPUT_INVALID"
    assert gateway.calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("order_tag", "expected_sort"),
    [
        (None, "CREATED_AT_DESC"),
        ("todayPv", "TODAY_PV_DESC"),
        ("todayUv", "TODAY_UV_DESC"),
        ("todayUip", "TODAY_UIP_DESC"),
        ("totalPv", "TOTAL_PV_DESC"),
        ("totalUv", "TOTAL_UV_DESC"),
        ("totalUip", "TOTAL_UIP_DESC"),
    ],
)
async def test_page_short_links_maps_legacy_order_tag_to_semantic_sort(
    order_tag: str | None,
    expected_sort: str,
) -> None:
    gateway = StubCapabilityGateway()
    context = CapabilityCallContext(
        TrustedActor("trusted-user"),
        "session-1",
        "trace-1",
    )
    toolset = CampaignReadOnlyToolset(
        gateway,
        context,
        adapter_settings(),
        AgentDataSanitizer(),
    )

    result = await toolset.page_short_links(
        gid="g1",
        current=2,
        size=20,
        order_tag=order_tag,
    )

    assert result["status"] == "SUCCESS"
    assert result["data"]["sort"] == expected_sort
    assert result["snapshot"]["source"] == "admin/short-links"
    assert gateway.calls == [
        (
            "short-links-v1",
            context,
            {
                "gid": "g1",
                "current": 2,
                "size": 20,
                "sort": expected_sort,
            },
        )
    ]
