package com.jupiter.shortlink.id;

public final class IdGenerationException extends RuntimeException {
    public enum Reason {
        CLOSED,
        TIMEOUT,
        INTERRUPTED,
        UNAVAILABLE,
        SPACE_EXHAUSTED,
        DATABASE,
        COMMIT_UNKNOWN,
        CONFIGURATION
    }

    private final Reason reason;

    public IdGenerationException(Reason reason, String message) {
        this(reason, message, null);
    }

    public IdGenerationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
