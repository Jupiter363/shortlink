package com.jupiter.shortlink.risk;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Authority response. All timestamps are UTC epoch milliseconds; validUntil never slides on cache
 * reads.
 */
public record PolicySnapshot(
        String resourceKey,
        long policyRevision,
        long evaluatedAt,
        Long nextTransitionAt,
        long validUntil,
        PolicyState state,
        boolean disabled,
        boolean timeUnrestricted,
        String timezone,
        List<AllowedTimeWindow> allowedWindows,
        Set<String> blockedIpHashes,
        RateLimitRule rateLimit) {
    public PolicySnapshot {
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(state, "state");
        timezone = timezone == null ? "Asia/Shanghai" : timezone;
        ZoneId.of(timezone);
        allowedWindows = allowedWindows == null ? List.of() : List.copyOf(allowedWindows);
        blockedIpHashes = blockedIpHashes == null ? Set.of() : Set.copyOf(blockedIpHashes);
        if (resourceKey.isBlank()
                || policyRevision < 0
                || evaluatedAt < 0
                || validUntil < evaluatedAt) {
            throw new IllegalArgumentException("Invalid policy identity or authority interval");
        }
        if (state == PolicyState.KNOWN_ALLOWED
                && (disabled
                        || !timeUnrestricted
                        || !blockedIpHashes.isEmpty()
                        || rateLimit != null)) {
            throw new IllegalArgumentException("Allowed snapshot cannot contain restrictions");
        }
    }

    public boolean authoritativeAt(long now) {
        return state != PolicyState.UNKNOWN
                && now >= evaluatedAt
                && now < validUntil
                && (nextTransitionAt == null || now < nextTransitionAt);
    }
}
