package com.jupiter.shortlink.membership;

/** Administrative process-local token. A new drain invalidates every previous drain token. */
public final class DrainPermit {
    final Object issuer;
    final String generation;
    final String transitionId;
    final long committedAtNanos;

    DrainPermit(Object issuer, String generation, String transitionId, long committedAtNanos) {
        this.issuer = issuer;
        this.generation = generation;
        this.transitionId = transitionId;
        this.committedAtNanos = committedAtNanos;
    }

    public String generation() { return generation; }
}
