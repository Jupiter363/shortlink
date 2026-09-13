package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.SourceCut;
import com.jupiter.shortlink.contract.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

class AnalyticsAccessRecordProjectionTest {
    @Test
    void sourceKindAndStatusAreProjectedAndKeptWithoutDefaultingUnknownResults() {
        var json = new ObjectMapper();
        var auth = mock(AuthorizationClient.class);
        var ch = mock(ClickHouseReader.class);
        var db = mock(JdbcTemplate.class);
        long end = System.currentTimeMillis(), start = end - 60_000;
        var scope = new AuthorizationClient.Scope("tenant", List.of(11L), "ownership-v1");
        when(auth.authorize(any())).thenReturn(scope);
        when(auth.activeEpoch()).thenReturn("epoch");
        var cut = new SourceCut(List.of(new SourceCut.Range("cluster", "topic", Topics.CLICK_RAW, 0, 1, 4)));
        when(auth.coverage(anyLong())).thenReturn(Map.of("recoveryEpoch", "epoch", "observedAt", end,
                "sourceCut", json.convertValue(cut, Map.class), "receipts", List.of(Map.of("clusterId", "cluster",
                        "topicId", "topic", "partition", 0, "count", 3))));
        var known = row("known", start + 1); known.put("kind", "CLICK"); known.put("status", 307);
        var zero = row("legacy-zero", start + 2); zero.put("kind", "CLICK"); zero.put("status", 0);
        var absent = row("missing", start + 3);
        when(ch.query(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(1);
            if (sql.contains("uniqExact(source_offset)")) return List.of(Map.of("cluster_id", "cluster", "topic_id", "topic",
                    "source_partition", 0, "receipts", 3));
            assertTrue(sql.contains("ipHash,kind,status,"), "Access rows must project the stored kind/status columns");
            assertFalse(sql.contains("302 status"));
            return List.of(known, zero, absent);
        });
        var settings = new ApiSettings("projection-test-internal-token", "http://localhost:1", "http://localhost:2", "http://localhost:3", "test", "", "test");
        var result = new AnalyticsQueryService(db, json, auth, ch, settings).query(new QueryRequest("tenant", "alice", 1,
                "owned", List.of(11L), start, end, null, "REQUESTED", null, null, 100, "ACCESS_RECORDS"));
        var rows = (List<?>) result.get("items");
        assertEquals("CLICK", ((Map<?, ?>) rows.get(0)).get("kind"));
        assertEquals(307, ((Map<?, ?>) rows.get(0)).get("status"));
        assertEquals(0, ((Map<?, ?>) rows.get(1)).get("status"));
        assertFalse(((Map<?, ?>) rows.get(2)).containsKey("kind"));
        assertFalse(((Map<?, ?>) rows.get(2)).containsKey("status"));
    }

    private static Map<String, Object> row(String id, long occurredAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("eventId", id); row.put("linkId", 11L); row.put("occurredAt", occurredAt);
        return row;
    }
}
