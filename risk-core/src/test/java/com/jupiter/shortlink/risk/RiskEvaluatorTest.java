package com.jupiter.shortlink.risk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

class RiskEvaluatorTest {
    private final RiskEvaluator evaluator = new RiskEvaluator();
    private final long now = Instant.parse("2026-09-06T01:00:00Z").toEpochMilli();

    private PolicySnapshot policy(
            boolean unrestricted, List<AllowedTimeWindow> windows, Long transition) {
        return new PolicySnapshot(
                "42:100",
                7,
                now - 1,
                transition,
                now + 999,
                PolicyState.KNOWN_RESTRICTED,
                false,
                unrestricted,
                "Asia/Shanghai",
                windows,
                Set.of(),
                null);
    }

    @Test
    void emptyAllowedSetDeniesButUnrestrictedAllows() {
        assertEquals(
                403,
                evaluator.evaluate(policy(false, List.of(), null), "42:100", "ip", now).status());
        assertTrue(
                evaluator.evaluate(policy(true, List.of(), null), "42:100", "ip", now).allowed());
    }

    @Test
    void halfOpenBoundaryAndNaturalTransitionCannotReuseOldAllowance() {
        var policy = policy(false, List.of(new AllowedTimeWindow(8 * 3600, 9 * 3600)), null);
        assertEquals(403, evaluator.evaluate(policy, "42:100", "ip", now).status());
        assertEquals(
                503,
                evaluator.evaluate(policy(true, List.of(), now), "42:100", "ip", now).status());
        assertEquals(
                503,
                evaluator
                        .evaluate(policy(true, List.of(), null), "42:100", "ip", now + 999)
                        .status());
    }

    @Test
    void foreignResourceOrMissingSnapshotIsUnknown() {
        assertEquals(
                503,
                evaluator.evaluate(policy(true, List.of(), null), "43:100", "ip", now).status());
        assertEquals(503, evaluator.evaluate(null, "42:100", "ip", now).status());
    }

    @Test
    void invalidUnrestrictedClaimRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PolicySnapshot(
                                "42:100",
                                1,
                                now,
                                null,
                                now + 1000,
                                PolicyState.KNOWN_ALLOWED,
                                true,
                                true,
                                "UTC",
                                List.of(),
                                Set.of(),
                                null));
    }
}
