package com.jupiter.shortlink.membership;

import java.util.Set;

/** In-memory, process-local capability. Never serialize or persist monotonic timestamps. */
public final class RegistrationPermit {
    final Object issuer;
    final long committedAtNanos;
    private final AppliedCut cut;
    private final Set<RouteAddress> addresses;

    RegistrationPermit(Object issuer, AppliedCut cut, Set<RouteAddress> addresses, long committedAtNanos) {
        this.issuer = issuer;
        this.cut = cut;
        this.addresses = Set.copyOf(addresses);
        this.committedAtNanos = committedAtNanos;
    }

    public AppliedCut cut() { return cut; }
    public String generation() { return cut.generation(); }
    public long revision() { return cut.revision(); }
    public Set<RouteAddress> addresses() { return addresses; }
}
