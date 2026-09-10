package com.jupiter.shortlink.risk;

/** Fixed, UTC-aligned global resource window. Revisions do not reset its consumed budget. */
public record RateLimitRule(long limit, int windowSeconds) {
    public RateLimitRule {
        if (limit < 1 || limit > 1_000_000_000L || windowSeconds < 1 || windowSeconds > 86_400) {
            throw new IllegalArgumentException("Invalid rate limit");
        }
    }
}
