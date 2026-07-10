package com.nageoffer.shortlink.agent.harness.action.service;

public class AgentActionException extends RuntimeException {

    private final String code;

    public AgentActionException(String code, String message) {
        this(code, message, null);
    }

    public AgentActionException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Agent action error code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
