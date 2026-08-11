"""FastAPI application factory."""

import hmac
import inspect
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import Annotated

from fastapi import FastAPI, Header, Request, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import SecretStr

from shortlink_agent.api.models import (
    AgentChatRequest,
    LegacyAgentRunData,
    LegacyErrorEnvelope,
    LegacyHealthData,
    LivenessResponse,
    ReadinessResponse,
    ResultEnvelope,
)
from shortlink_agent.application.errors import AgentRuntimeError, InvalidTrustedActorError
from shortlink_agent.application.models import AgentRunCommand, TrustedActor
from shortlink_agent.application.ports import AgentRuntimePort
from shortlink_agent.capabilities.http_gateway import HttpAuthorityCapabilityGateway
from shortlink_agent.config import Settings, get_settings
from shortlink_agent.runtime.agentscope_adapter import (
    CAMPAIGN_AGENT_TYPE,
    AgentScopeRuntimeAdapter,
)
from shortlink_agent.runtime.probe import (
    AgentScopeRuntimeProbe,
    ModelConfigurationProbe,
    RuntimeProbe,
)
from shortlink_agent.security.delegation import (
    DelegationSecurityContext,
    DelegationTokenError,
    DelegationTokenVerifier,
    DelegationTokenVerifierPort,
    reset_current_delegation,
    set_current_delegation,
)

_INTERNAL_API_PREFIX = "/internal/short-link-agent/v1/"
_INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token"
_CHAT_PATH = "/internal/short-link-agent/v1/chat"


def create_app(
    settings: Settings | None = None,
    runtime_probe: RuntimeProbe | None = None,
    model_probe: RuntimeProbe | None = None,
    agent_runtime: AgentRuntimePort | None = None,
    delegation_verifier: DelegationTokenVerifierPort | None = None,
) -> FastAPI:
    """Create an application with explicit, testable dependencies."""

    resolved_settings = settings or get_settings()
    resolved_runtime_probe = runtime_probe or AgentScopeRuntimeProbe(
        resolved_settings.required_agentscope_version,
    )
    resolved_model_probe = model_probe or ModelConfigurationProbe(
        resolved_settings.model_api_key_value(),
        resolved_settings.model_name,
    )
    resolved_agent_runtime = agent_runtime or AgentScopeRuntimeAdapter(
        resolved_settings,
        HttpAuthorityCapabilityGateway(resolved_settings),
    )
    resolved_delegation_verifier = delegation_verifier
    if resolved_delegation_verifier is None and resolved_settings.runtime_auth_mode != "legacy":
        resolved_delegation_verifier = DelegationTokenVerifier(resolved_settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            yield
        finally:
            close = getattr(resolved_agent_runtime, "aclose", None)
            if close is not None:
                close_result = close()
                if inspect.isawaitable(close_result):
                    await close_result
            if resolved_delegation_verifier is not None:
                await resolved_delegation_verifier.aclose()

    app = FastAPI(
        title="Shortlink Agent Runtime",
        version=resolved_settings.service_version,
        lifespan=lifespan,
    )

    @app.middleware("http")
    async def authenticate_internal_api(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        if not request.url.path.startswith(_INTERNAL_API_PREFIX):
            return await call_next(request)

        if request.url.path == _CHAT_PATH and resolved_settings.runtime_auth_mode != "legacy":
            authorization = request.headers.get("Authorization")
            bearer_token = _bearer_token(authorization)
            if authorization and bearer_token is None:
                return _legacy_error_response(
                    status.HTTP_401_UNAUTHORIZED,
                    "DELEGATION_TOKEN_INVALID",
                    "The Delegation Token is invalid.",
                )
            if bearer_token:
                if resolved_delegation_verifier is None:
                    return _legacy_error_response(
                        status.HTTP_503_SERVICE_UNAVAILABLE,
                        "DELEGATION_AUTH_NOT_CONFIGURED",
                        "Delegation authentication is not configured.",
                        retryable=True,
                    )
                try:
                    principal = await resolved_delegation_verifier.verify(
                        bearer_token,
                        required_scopes=frozenset({"agent:run"}),
                    )
                except DelegationTokenError as exc:
                    return _delegation_error_response(exc)
                request.state.delegation_principal = principal
                context_token = set_current_delegation(
                    DelegationSecurityContext(
                        principal=principal,
                        subject_token=SecretStr(bearer_token),
                    ),
                )
                try:
                    return await call_next(request)
                finally:
                    reset_current_delegation(context_token)
            if resolved_settings.runtime_auth_mode == "delegation_jwt":
                return _legacy_error_response(
                    status.HTTP_401_UNAUTHORIZED,
                    "DELEGATION_TOKEN_REQUIRED",
                    "A Delegation Token is required.",
                )

        legacy_error = _authenticate_legacy_internal(request, resolved_settings)
        if legacy_error is not None:
            return legacy_error
        return await call_next(request)

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        request: Request,
        _: RequestValidationError,
    ) -> JSONResponse:
        if request.url.path.startswith(_INTERNAL_API_PREFIX):
            return _legacy_error_response(
                status.HTTP_422_UNPROCESSABLE_CONTENT,
                "VALIDATION_FAILED",
                "Request validation failed.",
            )
        return _legacy_error_response(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "VALIDATION_FAILED",
            "Request validation failed.",
        )

    @app.get(
        "/health/live",
        response_model=LivenessResponse,
        tags=["health"],
    )
    def live() -> LivenessResponse:
        return LivenessResponse(
            service=resolved_settings.service_name,
            version=resolved_settings.service_version,
            timestamp=datetime.now(UTC),
        )

    @app.get(
        "/health/ready",
        response_model=ReadinessResponse,
        tags=["health"],
    )
    def ready(response: Response) -> ReadinessResponse:
        checks = [resolved_runtime_probe.check(), resolved_model_probe.check()]
        readiness_status = "UP" if all(check.status == "UP" for check in checks) else "DOWN"
        if readiness_status == "DOWN":
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return ReadinessResponse(
            status=readiness_status,
            service=resolved_settings.service_name,
            version=resolved_settings.service_version,
            checks=checks,
            timestamp=datetime.now(UTC),
        )

    @app.get(
        "/internal/short-link-agent/v1/health",
        response_model=ResultEnvelope[LegacyHealthData],
        tags=["compatibility"],
    )
    def legacy_health() -> ResultEnvelope[LegacyHealthData]:
        return ResultEnvelope.ok(
            LegacyHealthData(service=resolved_settings.legacy_service_name),
        )

    @app.post(
        "/internal/short-link-agent/v1/chat",
        response_model=ResultEnvelope[LegacyAgentRunData],
        tags=["compatibility"],
    )
    async def legacy_chat(
        http_request: Request,
        request: AgentChatRequest,
        trusted_username: Annotated[
            str | None,
            Header(alias="X-Agent-Username"),
        ] = None,
        trusted_user_id: Annotated[
            str | None,
            Header(alias="X-Agent-UserId"),
        ] = None,
        trusted_real_name: Annotated[
            str | None,
            Header(alias="X-Agent-RealName"),
        ] = None,
    ) -> ResultEnvelope[LegacyAgentRunData] | JSONResponse:
        try:
            principal = getattr(http_request.state, "delegation_principal", None)
            if principal is not None:
                if principal.session_id != request.session_id:
                    return _legacy_error_response(
                        status.HTTP_403_FORBIDDEN,
                        "DELEGATION_SESSION_MISMATCH",
                        "The Delegation Token does not grant access to this session.",
                    )
                actor = TrustedActor(
                    username=principal.username,
                    user_id=principal.subject,
                )
            else:
                actor = _trusted_actor(
                    trusted_username,
                    trusted_user_id,
                    trusted_real_name,
                )
            command = AgentRunCommand(
                session_id=request.session_id,
                agent_type=request.agent_type or CAMPAIGN_AGENT_TYPE,
                message=request.message,
            )
            result = await resolved_agent_runtime.run(command, actor)
        except AgentRuntimeError as exc:
            return _legacy_error_response(
                exc.status_code,
                exc.code,
                exc.public_message,
                retryable=exc.retryable,
            )
        return ResultEnvelope.ok(LegacyAgentRunData.model_validate(result))

    return app


def _authenticate_legacy_internal(
    request: Request,
    settings: Settings,
) -> JSONResponse | None:
    configured_token = settings.internal_token_value()
    if not configured_token and not settings.internal_token_dev_mode:
        return _legacy_error_response(
            status.HTTP_401_UNAUTHORIZED,
            "INTERNAL_TOKEN_NOT_CONFIGURED",
            "Internal token is not configured.",
        )
    if configured_token:
        provided_token = request.headers.get(_INTERNAL_TOKEN_HEADER, "")
        if not hmac.compare_digest(
            provided_token.encode("utf-8"),
            configured_token.encode("utf-8"),
        ):
            return _legacy_error_response(
                status.HTTP_401_UNAUTHORIZED,
                "INTERNAL_TOKEN_INVALID",
                "Invalid internal token.",
            )
    return None


def _bearer_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, separator, value = authorization.partition(" ")
    if not separator or scheme.lower() != "bearer" or not value.strip():
        return None
    return value.strip()


def _delegation_error_response(error: DelegationTokenError) -> JSONResponse:
    if error.code in {"JWKS_UNAVAILABLE", "REVOCATION_STATE_UNAVAILABLE"}:
        return _legacy_error_response(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            error.code,
            "The Delegation Token validation service is unavailable.",
            retryable=True,
        )
    if error.code == "DELEGATION_SCOPE_FORBIDDEN":
        return _legacy_error_response(
            status.HTTP_403_FORBIDDEN,
            error.code,
            "The Delegation Token does not grant the required scope.",
        )
    return _legacy_error_response(
        status.HTTP_401_UNAUTHORIZED,
        "DELEGATION_TOKEN_INVALID",
        "The Delegation Token is invalid.",
    )


def _trusted_actor(
    username: str | None,
    user_id: str | None,
    real_name: str | None,
) -> TrustedActor:
    normalized_username = (username or "").strip()
    if not normalized_username or len(normalized_username) > 128:
        raise InvalidTrustedActorError
    normalized_user_id = _optional_header(user_id, max_length=128)
    normalized_real_name = _optional_header(real_name, max_length=256)
    return TrustedActor(
        username=normalized_username,
        user_id=normalized_user_id,
        real_name=normalized_real_name,
    )


def _optional_header(value: str | None, *, max_length: int) -> str | None:
    normalized = (value or "").strip()
    if not normalized:
        return None
    if len(normalized) > max_length:
        raise InvalidTrustedActorError
    return normalized


def _legacy_error_response(
    status_code: int,
    code: str,
    message: str,
    *,
    retryable: bool = False,
) -> JSONResponse:
    envelope = LegacyErrorEnvelope(
        code=code,
        message=message,
        retryable=retryable,
    )
    return JSONResponse(status_code=status_code, content=envelope.model_dump())
