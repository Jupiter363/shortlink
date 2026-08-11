"""Compatibility chat boundary tests."""

from fastapi.testclient import TestClient
from pydantic import SecretStr

from shortlink_agent.api.app import create_app
from shortlink_agent.application.errors import ModelNotConfiguredError
from shortlink_agent.application.models import (
    AgentRunCommand,
    AgentRunResult,
    ToolExecution,
    TrustedActor,
)
from shortlink_agent.config import Settings


class StubAgentRuntime:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.command: AgentRunCommand | None = None
        self.actor: TrustedActor | None = None

    async def run(
        self,
        command: AgentRunCommand,
        actor: TrustedActor,
    ) -> AgentRunResult:
        self.command = command
        self.actor = actor
        if self.error:
            raise self.error
        return AgentRunResult(
            session_id=command.session_id,
            trace_id="trace-1",
            answer="analysis complete",
            cards=({"type": "stats_summary"},),
            tool_calls=(
                ToolExecution(
                    name="get_group_stats",
                    arguments={"gid": "g1"},
                    success=True,
                    duration_ms=12,
                    data={"pv": 100},
                ),
            ),
            data_sources=({"type": "runtime", "framework": "agentscope"},),
            trace_events=({"traceId": "trace-1", "status": "success"},),
        )


def secured_settings() -> Settings:
    return Settings(
        environment="test",
        internal_token=SecretStr("runtime-token"),
        model_api_key=SecretStr("model-key"),
    )


def trusted_headers(**overrides: str) -> dict[str, str]:
    headers = {
        "X-Agent-Internal-Token": "runtime-token",
        "X-Agent-Username": "trusted-user",
        "X-Agent-UserId": "1001",
        "X-Agent-RealName": "Trusted User",
    }
    headers.update(overrides)
    return headers


def chat_body(**overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "sessionId": "session-1",
        "agentType": "campaign-analysis",
        "message": "analyze gid=g1",
    }
    body.update(overrides)
    return body


def test_chat_preserves_java_result_and_uses_only_trusted_headers() -> None:
    runtime = StubAgentRuntime()
    client = TestClient(
        create_app(settings=secured_settings(), agent_runtime=runtime),
    )

    response = client.post(
        "/internal/short-link-agent/v1/chat",
        headers=trusted_headers(),
        json=chat_body(),
    )

    assert response.status_code == 200
    assert response.json()["data"] == {
        "sessionId": "session-1",
        "traceId": "trace-1",
        "answer": "analysis complete",
        "cards": [{"type": "stats_summary"}],
        "pendingActions": [],
        "toolCalls": [
            {
                "name": "get_group_stats",
                "arguments": {"gid": "g1"},
                "success": True,
                "durationMs": 12,
                "data": {"pv": 100},
                "snapshot": None,
                "warnings": [],
                "errorCode": None,
                "errorMessage": None,
            },
        ],
        "dataSources": [{"type": "runtime", "framework": "agentscope"}],
        "traceEvents": [{"traceId": "trace-1", "status": "success"}],
        "warnings": [],
    }
    assert runtime.command == AgentRunCommand(
        session_id="session-1",
        agent_type="campaign-analysis",
        message="analyze gid=g1",
    )
    assert runtime.actor == TrustedActor("trusted-user", "1001", "Trusted User")


def test_chat_rejects_identity_in_request_body() -> None:
    runtime = StubAgentRuntime()
    client = TestClient(create_app(settings=secured_settings(), agent_runtime=runtime))

    response = client.post(
        "/internal/short-link-agent/v1/chat",
        headers=trusted_headers(),
        json=chat_body(username="spoofed-user"),
    )

    assert response.status_code == 422
    assert response.json()["code"] == "VALIDATION_FAILED"
    assert runtime.command is None


def test_chat_rejects_invalid_internal_token() -> None:
    runtime = StubAgentRuntime()
    client = TestClient(create_app(settings=secured_settings(), agent_runtime=runtime))

    response = client.post(
        "/internal/short-link-agent/v1/chat",
        headers=trusted_headers(**{"X-Agent-Internal-Token": "wrong"}),
        json=chat_body(),
    )

    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_TOKEN_INVALID"
    assert runtime.command is None


def test_chat_requires_trusted_username_header() -> None:
    runtime = StubAgentRuntime()
    headers = trusted_headers()
    headers.pop("X-Agent-Username")
    client = TestClient(create_app(settings=secured_settings(), agent_runtime=runtime))

    response = client.post(
        "/internal/short-link-agent/v1/chat",
        headers=headers,
        json=chat_body(),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "TRUSTED_ACTOR_INVALID"
    assert runtime.command is None


def test_chat_maps_runtime_errors_without_exposing_exception_details() -> None:
    runtime = StubAgentRuntime(ModelNotConfiguredError("secret provider detail"))
    client = TestClient(create_app(settings=secured_settings(), agent_runtime=runtime))

    response = client.post(
        "/internal/short-link-agent/v1/chat",
        headers=trusted_headers(),
        json=chat_body(),
    )

    assert response.status_code == 503
    assert response.json() == {
        "success": False,
        "code": "MODEL_NOT_CONFIGURED",
        "message": "The Agent model provider is not configured.",
        "data": None,
        "retryable": False,
    }
    assert "secret provider detail" not in response.text
