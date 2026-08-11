package com.nageoffer.shortlink.admin.authority.capability.exception;

import org.springframework.http.HttpStatus;

public class AgentCapabilityException extends RuntimeException {

    private final HttpStatus status;

    private final String code;

    private final boolean retryable;

    public AgentCapabilityException(
            HttpStatus status,
            String code,
            String publicMessage,
            boolean retryable
    ) {
        this(status, code, publicMessage, retryable, null);
    }

    private AgentCapabilityException(
            HttpStatus status,
            String code,
            String publicMessage,
            boolean retryable,
            Throwable cause
    ) {
        super(publicMessage, cause);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public static AgentCapabilityException validation(String message) {
        return new AgentCapabilityException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                message,
                false
        );
    }

    public static AgentCapabilityException forbidden() {
        return new AgentCapabilityException(
                HttpStatus.FORBIDDEN,
                "CAPABILITY_FORBIDDEN",
                "The capability request is not authorized for the current actor.",
                false
        );
    }

    public static AgentCapabilityException providerFailed() {
        return providerFailed(null);
    }

    public static AgentCapabilityException providerFailed(Throwable cause) {
        return new AgentCapabilityException(
                HttpStatus.BAD_GATEWAY,
                "CAPABILITY_PROVIDER_FAILED",
                "The capability provider failed.",
                true,
                cause
        );
    }
}
