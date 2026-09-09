package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class MetricDimensionsTest {
    @Test
    void calendarUsesShanghaiAndKeepsLongCounts() {
        long start = Instant.parse("2026-09-05T16:00:00Z").toEpochMilli(), end = start + 86400000;
        var raw = new HashMap<String, Object>();
        raw.put("pv", 4294967296L);
        raw.put("uv", 2L);
        raw.put("daily_raw", List.of(List.of("2026-09-06", 4294967296L, 2L, 2L)));
        raw.put("hour_raw", List.of(List.of("0"), List.of(4294967296L)));
        raw.put("ip_raw", List.of(List.of("hash-only", 4294967296L, 3L)));
        raw.put("browser_raw", List.of(List.of("Chrome"), List.of(4294967296L)));
        var result = MetricDimensions.materialize(raw, start, end);
        assertEquals(4294967296L, ((List<?>) result.get("hourStats")).get(0));
        assertEquals(1D, result.get("peakHourShare"));
        assertEquals(List.of(), result.get("networkStats"));
        var ip = (Map<?, ?>) ((List<?>) result.get("topIpStats")).get(0);
        assertEquals("hash-only", ip.get("ipHash"));
        assertFalse(ip.containsKey("ip"));
        assertEquals(3L, ip.get("error"));
        assertTrue(MetricDimensions.sql(start, end).contains("'2026-09-06'"));
        assertFalse(MetricDimensions.sql(start, end).contains("'2026-09-07'"));
    }

    @Test
    void emptyCoveredWindowHasBusinessZerosButNoFabricatedCategories() {
        var result = MetricDimensions.materialize(Map.of(), 0, 1000);
        assertEquals(Collections.nCopies(24, 0L), result.get("hourStats"));
        assertNull(result.get("topBrowserShare"));
        assertEquals(List.of(), result.get("browserStats"));
    }
}
