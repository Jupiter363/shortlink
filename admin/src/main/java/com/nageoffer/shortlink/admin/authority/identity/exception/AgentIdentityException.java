package com.nageoffer.shortlink.admin.authority.identity.exception;

import org.springframework.http.HttpStatus;

public class AgentIdentityException extends RuntimeException {

    private final HttpStatus status;

    private final String code;

    public AgentIdentityException(HttpStatus status, String code, String publicMessage) {
        super(publicMessage);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AgentIdentityException notConfigured() {
        return new AgentIdentityException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AGENT_IDENTITY_NOT_CONFIGURED",
                "Agent identity signing keys are not configured."
        );
    }

    public static AgentIdentityException sessionGrantUnavailable() {
        return new AgentIdentityException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AGENT_SESSION_GRANT_UNAVAILABLE",
                "Agent Session Grant is not enabled or configured."
        );
    }

    public static AgentIdentityException unauthenticatedSessionActor() {
        return new AgentIdentityException(
                HttpStatus.UNAUTHORIZED,
                "AGENT_SESSION_ACTOR_REQUIRED",
                "Agent session request requires an authenticated user."
        );
    }

    public static AgentIdentityException invalidToken() {
        return new AgentIdentityException(
                HttpStatus.UNAUTHORIZED,
                "TOKEN_INVALID",
                "The agent token is invalid."
        );
    }

    public static AgentIdentityException forbidden(String message) {
        return new AgentIdentityException(HttpStatus.FORBIDDEN, "TOKEN_EXCHANGE_FORBIDDEN", message);
    }

    public static AgentIdentityException invalidExchange(String message) {
        return new AgentIdentityException(HttpStatus.BAD_REQUEST, "TOKEN_EXCHANGE_INVALID", message);
    }
}
