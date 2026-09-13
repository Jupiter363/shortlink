package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VisitorHistoryOptionalProofTest {
    private final VisitorHistory.Plan candidate = new VisitorHistory.Plan(true, "bounded", 10, 100, "");

    @ParameterizedTest
    @ValueSource(strings = {"TOO_LARGE", "UNAVAILABLE"})
    void onlyOptionalReadFailuresBecomeExplicitUnknownHistory(String code) {
        var unavailable = VisitorHistory.optionalProof(candidate,
                () -> { throw new QueryFailure(code, "do not expose internal failure details"); },
                rows -> { fail("An incomplete read cannot validate evidence"); return candidate; });
        assertFalse(unavailable.available());
        assertEquals("0", unavailable.predicate());
        assertEquals("UNPROVEN", unavailable.proof());
        assertEquals("TOO_LARGE".equals(code) ? "HISTORY_PROOF_BUDGET_EXCEEDED" : "HISTORY_PROOF_UNAVAILABLE", unavailable.reason());
        var report = new LinkedHashMap<String, Object>();
        VisitorHistory.materialize(report, Map.of("uv", 2L), unavailable, true);
        var quality = (Map<?, ?>) ((Map<?, ?>) report.get("dimensionQuality")).get("uvTypeStats");
        assertEquals("UNKNOWN", quality.get("status"));
        assertEquals(2L, quality.get("unknownUv"));
        assertEquals(List.of(), report.get("uvTypeStats"));
        assertEquals(candidate.start(), quality.get("historyStart"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "NOT_READY", "SNAPSHOT_EXPIRED", "QUERY_SCOPE_CHANGED"})
    void securityFailuresNeverDegrade(String code) {
        var failure = new QueryFailure(code, "denied");
        assertSame(failure, assertThrows(QueryFailure.class, () -> VisitorHistory.optionalProof(candidate,
                () -> { throw failure; }, rows -> candidate)));
    }

    @Test
    void validationFailureIsOutsideOptionalReadCatchEvenWhenItsCodeIsUnavailable() {
        var failure = new QueryFailure("UNAVAILABLE", "invalid proof");
        assertSame(failure, assertThrows(QueryFailure.class, () -> VisitorHistory.optionalProof(candidate,
                List::of, rows -> { throw failure; })));
    }

    @Test
    void interruptedReadDoesNotReturnAnUnknownSuccessOrClearInterrupt() {
        var failure = new QueryFailure("UNAVAILABLE", "interrupted");
        try {
            assertSame(failure, assertThrows(QueryFailure.class, () -> VisitorHistory.optionalProof(candidate,
                    () -> { Thread.currentThread().interrupt(); throw failure; }, rows -> candidate)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
