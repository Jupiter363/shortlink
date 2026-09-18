package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LinkMetricsTest {
    @Test
    void preservesLongCountersWithoutSummingDistinctVisitors() {
        var accumulator = new LinkMetrics.Accumulator(List.of(22L, 11L), 1000, 2000);
        accumulator.add(row(1, 0, 4_294_967_298L, 1, 1, 0));
        accumulator.add(row(0, 11, 4_294_967_296L, 1, 1, 0));
        accumulator.add(row(0, 22, 2, 1, 1, 0));
        var result = accumulator.finish();
        assertEquals(4_294_967_298L, result.summary().get("pv"));
        assertEquals(1L, result.summary().get("uv"));
        assertEquals(1L, result.summary().get("uip"));
        assertEquals(List.of(11L, 22L), result.items().stream().map(r -> r.get("linkId")).toList());
    }

    @Test
    void rejectsDuplicateRowsTruncatedResultsAndMalformedCounters() {
        var duplicate = new LinkMetrics.Accumulator(List.of(11L), 0, 1);
        duplicate.add(row(0, 11, 1, 1, 1, 0));
        assertThrows(QueryFailure.class, () -> duplicate.add(row(0, 11, 1, 1, 1, 0)));
        var truncated = new LinkMetrics.Accumulator(List.of(11L), 0, 1);
        truncated.add(row(1, 0, 1, 1, 1, 0));
        assertThrows(QueryFailure.class, truncated::finish);
        var invalid = new LinkMetrics.Accumulator(List.of(11L), 0, 1);
        var raw = new LinkedHashMap<>(row(0, 11, 1, 1, 1, 0));
        raw.put("uv", "1.5");
        assertThrows(QueryFailure.class, () -> invalid.add(raw));
        raw.put("uv", "9223372036854775808");
        assertThrows(QueryFailure.class, () -> invalid.add(raw));
        raw.remove("uv");
        assertThrows(QueryFailure.class, () -> invalid.add(raw));
    }

    @Test
    void emptyAuthorizedScopeHasAZeroSummaryWithoutInventedLinks() {
        var accumulator = new LinkMetrics.Accumulator(List.of(), 0, 1);
        accumulator.add(row(1, 0, 0, 0, 0, 0));
        assertEquals(List.of(), accumulator.finish().items());
        assertEquals(0L, accumulator.finish().summary().get("pv"));
    }

    private static Map<String, Object> row(int group, long link, long pv, long uv, long uip, long denied) {
        return Map.of("group_row", group, "linkId", link, "pv", pv, "uv", uv, "uip", uip, "denied", denied);
    }
}
