package com.jupiter.shortlink.membership;

import java.util.function.LongSupplier;

/** This lease is tied to one exact, completely applied cut and expires on a monotonic clock. */
public final class DenialLease {
    private final AppliedCut cut;
    private final long requestedAtNanos;
    private final long ttlNanos;
    private final LongSupplier nanoClock;

    DenialLease(AppliedCut cut, long requestedAtNanos, long ttlNanos, LongSupplier nanoClock) {
        this.cut = cut;
        this.requestedAtNanos = requestedAtNanos;
        this.ttlNanos = ttlNanos;
        this.nanoClock = nanoClock;
    }

    public AppliedCut cut() { return cut; }
    public long expiresAtNanos() { return requestedAtNanos + ttlNanos; }
    public boolean isValid() { return remainingNanos() > 0; }
    public long remainingNanos() {
        long elapsed = nanoClock.getAsLong() - requestedAtNanos;
        return elapsed < 0 || elapsed >= ttlNanos ? 0 : ttlNanos - elapsed;
    }
}
