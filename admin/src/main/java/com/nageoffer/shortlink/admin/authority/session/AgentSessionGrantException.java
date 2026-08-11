package com.nageoffer.shortlink.admin.authority.session;

public class AgentSessionGrantException extends RuntimeException {

    private final Reason reason;

    private AgentSessionGrantException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static AgentSessionGrantException invalid(String message) {
        return new AgentSessionGrantException(Reason.INVALID, message);
    }

    public static AgentSessionGrantException notFound() {
        return new AgentSessionGrantException(Reason.NOT_FOUND, "Agent session grant does not exist.");
    }

    public static AgentSessionGrantException forbidden() {
        return new AgentSessionGrantException(Reason.FORBIDDEN, "Agent session grant ownership does not match.");
    }

    public static AgentSessionGrantException inactive() {
        return new AgentSessionGrantException(Reason.INACTIVE, "Agent session grant is not active.");
    }

    public static AgentSessionGrantException expired() {
        return new AgentSessionGrantException(Reason.EXPIRED, "Agent session grant has expired.");
    }

    public static AgentSessionGrantException conflict() {
        return new AgentSessionGrantException(Reason.CONFLICT, "Agent session grant changed concurrently.");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID,
        NOT_FOUND,
        FORBIDDEN,
        INACTIVE,
        EXPIRED,
        CONFLICT
    }
}
