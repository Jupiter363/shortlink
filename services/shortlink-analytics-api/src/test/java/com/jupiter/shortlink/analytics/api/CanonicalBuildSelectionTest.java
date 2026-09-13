package com.jupiter.shortlink.analytics.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalBuildSelectionTest {
    private static final long WINDOW = 300000L;

    @Test
    void weekOfDailyBuildsUsesSevenRangesInsteadOfTwoThousandWindowPredicates() {
        List<Map<String, Object>> manifests = new ArrayList<>();
        for (int day = 0; day < 7; day++)
            for (int window = 0; window < 288; window++)
                manifests.add(row("day-" + day, (day * 288L + window) * WINDOW));
        var clauses = AnalyticsQueryService.canonicalClauses(manifests);
        assertEquals(7, clauses.size());
        assertEquals("(build_id='day-0' AND window_start>=0 AND window_start<86400000)", clauses.get(0));
        assertEquals("(build_id='day-6' AND window_start>=518400000 AND window_start<604800000)", clauses.get(6));
    }

    @Test
    void newerRevisionsAndUnselectedGapsCannotLeakOlderBuildRows() {
        var clauses = AnalyticsQueryService.canonicalClauses(List.of(
                row("old", 4 * WINDOW), row("new", 2 * WINDOW), row("old", 0),
                row("old", WINDOW), row("old", 3 * WINDOW), row("old", 6 * WINDOW)));
        assertEquals(List.of(
                "(build_id='old' AND window_start>=0 AND window_start<600000)",
                "(build_id='old' AND window_start>=900000 AND window_start<1500000)",
                "(build_id='old' AND window_start>=1800000 AND window_start<2100000)",
                "(build_id='new' AND window_start>=600000 AND window_start<900000)"), clauses);
        assertTrue(AnalyticsQueryService.canonicalClauses(List.of()).isEmpty());
    }

    private static Map<String, Object> row(String build, long window) {
        return Map.of("build_id", build, "window_start", window);
    }
}
