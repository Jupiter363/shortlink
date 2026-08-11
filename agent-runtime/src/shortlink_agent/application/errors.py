"""Stable application errors safe to expose at HTTP boundaries."""


class AgentRuntimeError(Exception):
    """Base error carrying only a stable public contract."""

    code = "AGENT_RUNTIME_FAILED"
    status_code = 500
    public_message = "Agent runtime failed."
    retryable = False


class InvalidTrustedActorError(AgentRuntimeError):
    code = "TRUSTED_ACTOR_INVALID"
    status_code = 400
    public_message = "Trusted agent user context is required."


class UnsupportedAgentTypeError(AgentRuntimeError):
    code = "AGENT_TYPE_UNSUPPORTED"
    status_code = 400
    public_message = "The requested agent type is not available in the Python runtime."


class ModelNotConfiguredError(AgentRuntimeError):
    code = "MODEL_NOT_CONFIGURED"
    status_code = 503
    public_message = "The Agent model provider is not configured."


class AgentRunTimedOutError(AgentRuntimeError):
    code = "AGENT_RUN_DEADLINE_EXCEEDED"
    status_code = 504
    public_message = "The Agent run exceeded its deadline."
    retryable = True


class AgentExecutionFailedError(AgentRuntimeError):
    code = "MODEL_PROVIDER_FAILED"
    status_code = 502
    public_message = "The Agent model provider request failed."
    retryable = True


class CapabilityError(Exception):
    """A sanitized Java capability failure exposed only to the Agent."""

    def __init__(self, code: str, public_message: str, *, retryable: bool = False) -> None:
        super().__init__(public_message)
        self.code = code
        self.public_message = public_message
        self.retryable = retryable
