"""Health API contract tests."""

from typing import Literal

from fastapi.testclient import TestClient
from pydantic import SecretStr

from shortlink_agent.api.app import create_app
from shortlink_agent.api.models import DependencyCheck
from shortlink_agent.config import Settings


class StubRuntimeProbe:
    def __init__(self, status: Literal["UP", "DOWN"], name: str = "agentscope") -> None:
        self._status = status
        self._name = name

    def check(self) -> DependencyCheck:
        return DependencyCheck(
            name=self._name,
            status=self._status,
            requiredVersion="2.0.4.post1" if self._name == "agentscope" else None,
            installedVersion=(
                "2.0.4.post1" if self._name == "agentscope" and self._status == "UP" else None
            ),
        )


def make_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "environment": "test",
        "internal_token_dev_mode": True,
        "model_api_key": SecretStr("test-model-key"),
    }
    values.update(overrides)
    return Settings(**values)


def test_legacy_health_preserves_java_contract() -> None:
    client = TestClient(
        create_app(
            settings=make_settings(),
            runtime_probe=StubRuntimeProbe("UP"),
        ),
    )

    response = client.get("/internal/short-link-agent/v1/health")

    assert response.status_code == 200
    assert response.json() == {
        "success": True,
        "code": "0",
        "message": "success",
        "data": {
            "status": "OK",
            "service": "short-link-agent",
        },
    }


def test_liveness_does_not_depend_on_agentscope_or_model() -> None:
    client = TestClient(
        create_app(
            settings=make_settings(model_api_key=None),
            runtime_probe=StubRuntimeProbe("DOWN"),
        ),
    )

    response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.json()["service"] == "short-link-agent-runtime"


def test_readiness_is_up_when_runtime_and_model_checks_pass() -> None:
    client = TestClient(
        create_app(
            settings=make_settings(),
            runtime_probe=StubRuntimeProbe("UP"),
        ),
    )

    response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.json()["checks"][0] == {
        "name": "agentscope",
        "status": "UP",
        "requiredVersion": "2.0.4.post1",
        "installedVersion": "2.0.4.post1",
        "detail": None,
    }
    assert response.json()["checks"][1]["name"] == "model-configuration"
    assert response.json()["checks"][1]["status"] == "UP"


def test_readiness_is_service_unavailable_when_runtime_probe_fails() -> None:
    client = TestClient(
        create_app(
            settings=make_settings(),
            runtime_probe=StubRuntimeProbe("DOWN"),
        ),
    )

    response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"


def test_readiness_is_service_unavailable_when_model_is_not_configured() -> None:
    client = TestClient(
        create_app(
            settings=make_settings(model_api_key=None),
            runtime_probe=StubRuntimeProbe("UP"),
        ),
    )

    response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["checks"][1]["status"] == "DOWN"


def test_settings_are_injected_into_health_responses() -> None:
    settings = make_settings(
        service_name="runtime-test",
        legacy_service_name="legacy-test",
        service_version="test-version",
    )
    client = TestClient(
        create_app(
            settings=settings,
            runtime_probe=StubRuntimeProbe("UP"),
        ),
    )

    assert client.get("/health/live").json()["service"] == "runtime-test"
    assert (
        client.get("/internal/short-link-agent/v1/health").json()["data"]["service"]
        == "legacy-test"
    )


def test_internal_health_fails_closed_without_token_configuration() -> None:
    client = TestClient(
        create_app(
            settings=Settings(environment="test", model_api_key=SecretStr("key")),
            runtime_probe=StubRuntimeProbe("UP"),
        ),
    )

    response = client.get("/internal/short-link-agent/v1/health")

    assert response.status_code == 401
    assert response.json()["code"] == "INTERNAL_TOKEN_NOT_CONFIGURED"
