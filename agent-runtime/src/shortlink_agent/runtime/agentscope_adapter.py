"""AgentScope adapter for the read-only campaign migration slice."""

import asyncio
import logging
import re
import time
from collections.abc import Awaitable, Sequence
from datetime import UTC, date, datetime, timedelta
from datetime import time as datetime_time
from importlib.metadata import version
from typing import Any, Protocol
from uuid import uuid4
from zoneinfo import ZoneInfo

from pydantic import SecretStr

from shortlink_agent.application.errors import (
    AgentExecutionFailedError,
    AgentRunTimedOutError,
    CapabilityError,
    ModelNotConfiguredError,
    UnsupportedAgentTypeError,
)
from shortlink_agent.application.models import (
    AgentRunCommand,
    AgentRunResult,
    CapabilityCallContext,
    CapabilityResult,
    GroupStatsCapabilityQuery,
    ReadOnlyToolDefinition,
    ShortLinksCapabilityQuery,
    ToolExecution,
    TrustedActor,
)
from shortlink_agent.application.ports import CapabilityGateway
from shortlink_agent.config import Settings
from shortlink_agent.security.sanitization import AgentDataSanitizer

logger = logging.getLogger(__name__)

CAMPAIGN_AGENT_TYPE = "campaign-analysis"
_GID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_ALLOWED_ORDER_TAGS = frozenset(
    {
        "todayPv",
        "todayUv",
        "todayUip",
        "totalPv",
        "totalUv",
        "totalUip",
    },
)
_SHORT_LINK_SORT_BY_ORDER_TAG = {
    None: "CREATED_AT_DESC",
    "todayPv": "TODAY_PV_DESC",
    "todayUv": "TODAY_UV_DESC",
    "todayUip": "TODAY_UIP_DESC",
    "totalPv": "TOTAL_PV_DESC",
    "totalUv": "TOTAL_UV_DESC",
    "totalUip": "TOTAL_UIP_DESC",
}

_SYSTEM_PROMPT = """
You are the read-only campaign analysis Agent for the short-link admin console.
Use only the registered read-only tools when business data is required. Java
capability responses are the authoritative factual source. Never invent group
ownership, metrics, tool output, or write actions. Do not request or expose
credentials, internal headers, personal identifiers, or raw IP addresses.
Explain uncertainty explicitly and respond in the user's language.
""".strip()


class CampaignAgentSession(Protocol):
    async def reply(self, message: str) -> str:
        """Return the final text response."""

        ...


class CampaignAgentFactory(Protocol):
    def create(
        self,
        session_id: str,
        tools: Sequence[ReadOnlyToolDefinition],
    ) -> CampaignAgentSession:
        """Create isolated state for one compatibility run."""

        ...


class _AgentScopeSession:
    def __init__(self, agent: Any) -> None:
        self._agent = agent

    async def reply(self, message: str) -> str:
        from agentscope.message import UserMsg

        response = await self._agent.reply(UserMsg(name="user", content=message))
        return response.get_text_content() or ""


class AgentScopeCampaignFactory:
    """The only layer that constructs AgentScope-owned objects."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def create(
        self,
        session_id: str,
        tools: Sequence[ReadOnlyToolDefinition],
    ) -> CampaignAgentSession:
        from agentscope.agent import Agent, ReActConfig
        from agentscope.credential import DeepSeekCredential
        from agentscope.formatter import DeepSeekChatFormatter
        from agentscope.model import DeepSeekChatModel
        from agentscope.permission import PermissionContext, PermissionMode
        from agentscope.state import AgentState
        from agentscope.tool import FunctionTool, Toolkit

        model = DeepSeekChatModel(
            credential=DeepSeekCredential(
                api_key=SecretStr(self._settings.model_api_key_value()),
                base_url=self._settings.model_base_url,
            ),
            model=self._settings.model_name,
            parameters=DeepSeekChatModel.Parameters(
                max_tokens=self._settings.model_max_output_tokens,
            ),
            stream=False,
            max_retries=self._settings.model_max_retries,
            formatter=DeepSeekChatFormatter(),
            client_kwargs={"timeout": self._settings.model_timeout_seconds},
        )
        toolkit = Toolkit(
            tools=[
                FunctionTool(
                    tool.handler,
                    name=tool.name,
                    description=tool.description,
                    is_concurrency_safe=False,
                    is_read_only=True,
                )
                for tool in tools
            ],
        )
        state = AgentState(
            session_id=session_id,
            permission_context=PermissionContext(mode=PermissionMode.EXPLORE),
        )
        agent = Agent(
            name="shortlink-campaign-analysis",
            system_prompt=_SYSTEM_PROMPT,
            model=model,
            toolkit=toolkit,
            state=state,
            react_config=ReActConfig(max_iters=self._settings.react_max_iterations),
        )
        return _AgentScopeSession(agent)


class CampaignReadOnlyToolset:
    """Bind trusted run context to a fixed set of read-only capabilities."""

    def __init__(
        self,
        gateway: CapabilityGateway,
        context: CapabilityCallContext,
        settings: Settings,
        sanitizer: AgentDataSanitizer,
    ) -> None:
        self._gateway = gateway
        self._context = context
        self._max_page_size = settings.capability_max_page_size
        self._sanitizer = sanitizer
        self._business_timezone = settings.business_timezone
        self._business_zone = ZoneInfo(settings.business_timezone)
        self.executions: list[ToolExecution] = []

    def definitions(self) -> tuple[ReadOnlyToolDefinition, ...]:
        return (
            ReadOnlyToolDefinition(
                "list_groups",
                "List short-link groups owned by the current authenticated user.",
                self.list_groups,
            ),
            ReadOnlyToolDefinition(
                "page_short_links",
                "Page short links in an owned group. Page size is bounded by the runtime.",
                self.page_short_links,
            ),
            ReadOnlyToolDefinition(
                "get_short_link_stats",
                "Get statistics for a short link in an owned group and ISO date range.",
                self.get_short_link_stats,
            ),
            ReadOnlyToolDefinition(
                "get_group_stats",
                "Get aggregated statistics for an owned group and ISO date range.",
                self.get_group_stats,
            ),
            ReadOnlyToolDefinition(
                "get_group_access_records",
                "Page access records for an owned group and ISO date range.",
                self.get_group_access_records,
            ),
        )

    async def list_groups(self) -> dict[str, Any]:
        """List the current user's groups."""

        return await self._invoke_capability(
            "list_groups",
            {},
            self._gateway.list_groups(self._context),
        )

    async def page_short_links(
        self,
        gid: str,
        current: int = 1,
        size: int = 10,
        order_tag: str | None = None,
    ) -> dict[str, Any]:
        """Page short links for an owned group."""

        arguments = {
            "gid": gid,
            "current": current,
            "size": size,
            "orderTag": order_tag,
        }
        error = self._validate_gid(gid) or self._validate_page(current, size)
        if not error and order_tag is not None and order_tag not in _ALLOWED_ORDER_TAGS:
            error = "order_tag is not an allowed sort key."
        if error:
            return self._reject("page_short_links", arguments, error)
        return await self._invoke_capability(
            "page_short_links",
            arguments,
            self._gateway.query_short_links(
                self._context,
                ShortLinksCapabilityQuery(
                    gid=gid,
                    current=current,
                    size=size,
                    sort=_SHORT_LINK_SORT_BY_ORDER_TAG[order_tag],
                ),
            ),
        )

    async def get_short_link_stats(
        self,
        gid: str,
        full_short_url: str,
        start_date: str,
        end_date: str,
    ) -> dict[str, Any]:
        """Get one short link's statistics for an ISO date range."""

        arguments = {
            "gid": gid,
            "fullShortUrl": full_short_url,
            "startDate": start_date,
            "endDate": end_date,
        }
        error = self._validate_gid(gid) or self._validate_short_url(full_short_url)
        error = error or self._validate_date_range(start_date, end_date)
        if error:
            return self._reject("get_short_link_stats", arguments, error)
        return await self._invoke(
            "get_short_link_stats",
            "/internal/short-link-admin/v1/agent-tools/short-link/stats",
            arguments,
        )

    async def get_group_stats(
        self,
        gid: str,
        start_date: str,
        end_date: str,
    ) -> dict[str, Any]:
        """Get one group's aggregated statistics for an ISO date range."""

        arguments = {"gid": gid, "startDate": start_date, "endDate": end_date}
        error = self._validate_gid(gid) or self._validate_date_range(start_date, end_date)
        if error:
            return self._reject("get_group_stats", arguments, error)
        start_day = date.fromisoformat(start_date)
        end_exclusive_day = date.fromisoformat(end_date) + timedelta(days=1)
        return await self._invoke_capability(
            "get_group_stats",
            arguments,
            self._gateway.query_group_stats(
                self._context,
                GroupStatsCapabilityQuery(
                    gid=gid,
                    start=datetime.combine(
                        start_day,
                        datetime_time.min,
                        tzinfo=self._business_zone,
                    ),
                    end=datetime.combine(
                        end_exclusive_day,
                        datetime_time.min,
                        tzinfo=self._business_zone,
                    ),
                    timezone=self._business_timezone,
                ),
            ),
        )

    async def get_group_access_records(
        self,
        gid: str,
        start_date: str,
        end_date: str,
        current: int = 1,
        size: int = 10,
    ) -> dict[str, Any]:
        """Page one group's access records for an ISO date range."""

        arguments = {
            "gid": gid,
            "startDate": start_date,
            "endDate": end_date,
            "current": current,
            "size": size,
        }
        error = self._validate_gid(gid) or self._validate_date_range(start_date, end_date)
        error = error or self._validate_page(current, size)
        if error:
            return self._reject("get_group_access_records", arguments, error)
        return await self._invoke(
            "get_group_access_records",
            "/internal/short-link-admin/v1/agent-tools/group/access-records",
            arguments,
        )

    async def _invoke(
        self,
        name: str,
        path: str,
        arguments: dict[str, Any],
    ) -> dict[str, Any]:
        return await self._invoke_capability(
            name,
            arguments,
            self._legacy_capability(path, arguments),
        )

    async def _legacy_capability(
        self,
        path: str,
        arguments: dict[str, Any],
    ) -> CapabilityResult:
        data = await self._gateway.get(path, self._context, arguments)
        return CapabilityResult(data=data)

    async def _invoke_capability(
        self,
        name: str,
        arguments: dict[str, Any],
        operation: Awaitable[CapabilityResult],
    ) -> dict[str, Any]:
        started = time.perf_counter()
        try:
            result = await operation
            data = self._sanitizer.sanitize(result.data)
            sanitized_snapshot = self._sanitizer.sanitize(result.snapshot)
            snapshot = sanitized_snapshot if isinstance(sanitized_snapshot, dict) else None
            warnings = tuple(str(self._sanitizer.sanitize(item)) for item in result.warnings)
            execution = ToolExecution(
                name=name,
                arguments=arguments,
                success=True,
                duration_ms=self._duration_ms(started),
                data=data,
                snapshot=snapshot,
                warnings=warnings,
            )
            self.executions.append(execution)
            tool_result: dict[str, Any] = {
                "status": "SUCCESS",
                "data": data,
                "warnings": list(warnings),
            }
            if snapshot is not None:
                tool_result["snapshot"] = snapshot
            return tool_result
        except CapabilityError as exc:
            execution = ToolExecution(
                name=name,
                arguments=arguments,
                success=False,
                duration_ms=self._duration_ms(started),
                error_code=exc.code,
                error_message=exc.public_message,
            )
            self.executions.append(execution)
            return {
                "status": "ERROR",
                "error": {"code": exc.code, "message": exc.public_message},
            }
        except Exception as exc:  # Defensive boundary around third-party transports.
            logger.warning("Capability adapter failed: %s", type(exc).__name__)
            execution = ToolExecution(
                name=name,
                arguments=arguments,
                success=False,
                duration_ms=self._duration_ms(started),
                error_code="CAPABILITY_UNAVAILABLE",
                error_message="The Java capability is unavailable.",
            )
            self.executions.append(execution)
            return {
                "status": "ERROR",
                "error": {
                    "code": execution.error_code,
                    "message": execution.error_message,
                },
            }

    def _reject(
        self,
        name: str,
        arguments: dict[str, Any],
        message: str,
    ) -> dict[str, Any]:
        execution = ToolExecution(
            name=name,
            arguments=arguments,
            success=False,
            duration_ms=0,
            error_code="TOOL_INPUT_INVALID",
            error_message=message,
        )
        self.executions.append(execution)
        return {
            "status": "ERROR",
            "error": {"code": execution.error_code, "message": message},
        }

    def _validate_gid(self, gid: str) -> str | None:
        if not isinstance(gid, str) or not _GID_PATTERN.fullmatch(gid):
            return "gid must contain 1 to 64 letters, digits, underscores, or hyphens."
        return None

    def _validate_short_url(self, value: str) -> str | None:
        if (
            not isinstance(value, str)
            or not value
            or len(value) > 2_048
            or any(char.isspace() for char in value)
        ):
            return "full_short_url is invalid."
        return None

    def _validate_date_range(self, start: str, end: str) -> str | None:
        try:
            start_date = date.fromisoformat(start)
            end_date = date.fromisoformat(end)
        except (TypeError, ValueError):
            return "start_date and end_date must use yyyy-MM-dd."
        if end_date < start_date:
            return "end_date must not be before start_date."
        if (end_date - start_date).days > 366:
            return "The requested date range must not exceed 366 days."
        return None

    def _validate_page(self, current: int, size: int) -> str | None:
        if isinstance(current, bool) or not isinstance(current, int) or not 1 <= current <= 10_000:
            return "current must be an integer between 1 and 10000."
        if (
            isinstance(size, bool)
            or not isinstance(size, int)
            or size < 1
            or size > self._max_page_size
        ):
            return f"size must be between 1 and {self._max_page_size}."
        return None

    @staticmethod
    def _duration_ms(started: float) -> int:
        return max(0, round((time.perf_counter() - started) * 1_000))


class AgentScopeRuntimeAdapter:
    """Translate product runs into isolated AgentScope campaign sessions."""

    def __init__(
        self,
        settings: Settings,
        capability_gateway: CapabilityGateway,
        *,
        agent_factory: CampaignAgentFactory | None = None,
        sanitizer: AgentDataSanitizer | None = None,
    ) -> None:
        self._settings = settings
        self._gateway = capability_gateway
        self._factory = agent_factory or AgentScopeCampaignFactory(settings)
        self._sanitizer = sanitizer or AgentDataSanitizer()

    async def run(
        self,
        command: AgentRunCommand,
        actor: TrustedActor,
    ) -> AgentRunResult:
        agent_type = (command.agent_type or CAMPAIGN_AGENT_TYPE).strip().lower()
        if agent_type != CAMPAIGN_AGENT_TYPE:
            raise UnsupportedAgentTypeError
        if not self._settings.model_api_key_value():
            raise ModelNotConfiguredError

        trace_id = str(uuid4())
        started_at = datetime.now(UTC)
        started = time.perf_counter()
        context = CapabilityCallContext(actor, command.session_id, trace_id)
        toolset = CampaignReadOnlyToolset(
            self._gateway,
            context,
            self._settings,
            self._sanitizer,
        )
        try:
            session = self._factory.create(command.session_id, toolset.definitions())
            async with asyncio.timeout(self._settings.run_timeout_seconds):
                answer = await session.reply(command.message)
        except TimeoutError as exc:
            raise AgentRunTimedOutError from exc
        except Exception as exc:
            logger.warning("AgentScope execution failed: %s", type(exc).__name__)
            raise AgentExecutionFailedError from exc

        finished_at = datetime.now(UTC)
        executions = tuple(toolset.executions)
        warning_values = [
            f"Tool {execution.name} failed with {execution.error_code}."
            for execution in executions
            if not execution.success
        ]
        warning_values.extend(warning for execution in executions for warning in execution.warnings)
        warnings = tuple(dict.fromkeys(warning_values))
        return AgentRunResult(
            session_id=command.session_id,
            trace_id=trace_id,
            answer=answer or "The Agent produced no text response.",
            cards=self._build_cards(executions),
            tool_calls=executions,
            data_sources=self._data_sources(executions),
            trace_events=(
                {
                    "traceId": trace_id,
                    "nodeName": "agentscope_campaign_run",
                    "status": "success",
                    "startedAt": started_at.isoformat(),
                    "finishedAt": finished_at.isoformat(),
                    "durationMs": max(0, round((time.perf_counter() - started) * 1_000)),
                },
            ),
            warnings=warnings,
        )

    async def aclose(self) -> None:
        await self._gateway.aclose()

    def _data_sources(
        self,
        executions: tuple[ToolExecution, ...],
    ) -> tuple[dict[str, Any], ...]:
        sources: list[dict[str, Any]] = [
            {
                "type": "runtime",
                "framework": "agentscope",
                "version": version("agentscope"),
            },
            {
                "type": "llm",
                "provider": "deepseek",
                "model": self._settings.model_name,
            },
        ]
        if executions:
            sources.append(
                {
                    "type": "tool",
                    "executions": [execution.name for execution in executions],
                },
            )
        return tuple(sources)

    @staticmethod
    def _build_cards(
        executions: tuple[ToolExecution, ...],
    ) -> tuple[dict[str, Any], ...]:
        cards: list[dict[str, Any]] = []
        for execution in executions:
            card: dict[str, Any] = {
                "type": "tool_result" if execution.success else "tool_warning",
                "sourceTool": execution.name,
                "arguments": execution.arguments,
                "status": "SUCCESS" if execution.success else "ERROR",
            }
            if execution.success:
                card["summary"] = AgentScopeRuntimeAdapter._summarize(execution.data)
            else:
                card["errorCode"] = execution.error_code
            cards.append(card)
        return tuple(cards)

    @staticmethod
    def _summarize(data: Any) -> dict[str, Any]:
        if isinstance(data, list):
            return {"recordCount": len(data)}
        if isinstance(data, dict):
            summary = {
                key: data[key]
                for key in ("pv", "uv", "uip", "total", "current", "size")
                if key in data
            }
            records = data.get("records")
            if isinstance(records, list):
                summary["recordCount"] = len(records)
            return summary
        return {}
