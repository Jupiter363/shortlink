package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.SourceCut;
import com.jupiter.shortlink.contract.Topics;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class AnalyticsHistoryProofFallbackTest {
    final ObjectMapper json = new ObjectMapper();
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final AuthorizationClient auth = mock(AuthorizationClient.class);
    final ClickHouseReader ch = mock(ClickHouseReader.class);
    final long end = Math.floorDiv(System.currentTimeMillis(), 300_000) * 300_000;
    final long start = end - 300_000;
    AnalyticsQueryService service;
    QueryFailure factsFailure;

    @BeforeEach
    void setup() throws Exception {
        var scope = new AuthorizationClient.Scope("tenant", List.of(11L), "v1");
        when(auth.authorize(any())).thenReturn(scope);
        when(auth.activeEpoch()).thenReturn("epoch");
        when(auth.coverage(anyLong())).thenReturn(Map.of("recoveryEpoch", "epoch", "observedAt", end));
        var cut = new SourceCut(List.of(new SourceCut.Range("cluster", "topic", Topics.CLICK_RAW, 0, 0, 3)));
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("window_start", start); manifest.put("build_id", "build");
        manifest.put("manifest_revision", 1); manifest.put("source_observed_at", end);
        manifest.put("source_cut", json.writeValueAsString(cut)); manifest.put("finalized", true);
        manifest.put("replica_ids", "[\"http://localhost:3\"]");
        manifest.put("coverage_proof", "{\"n\":3,\"digest\":\"7\"}");
        when(db.queryForList(startsWith("SELECT * FROM analytics_manifest"), eq("epoch"), eq(start), eq(end)))
                .thenReturn(List.of(manifest));
        when(ch.query(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(1);
            if (sql.contains("groupBitXor")) return List.of(Map.of("n", 3, "digest", "7"));
            if (factsFailure != null) throw factsFailure;
            assertTrue(sql.contains("toUInt8(0) scope_history_known"));
            assertFalse(sql.contains("retained_history"));
            if (sql.startsWith("SELECT event_id")) return List.of(record("one", "visitor-a"), record("two", "visitor-b"));
            return List.of(Map.of("window_name", "requested", "group_row", 1, "pv", 3L, "uv", 2L, "uip", 2L),
                    Map.of("window_name", "requested", "group_row", 0, "link_id", 11L, "pv", 3L, "uv", 2L, "uip", 2L));
        });
        service = new AnalyticsQueryService(db, json, auth, ch, new ApiSettings("test-internal-token-long-enough",
                "http://localhost:1", "http://localhost:2", "http://localhost:3", "test", "", "test"));
    }

    @ParameterizedTest
    @CsvSource({"METRICS,TOO_LARGE", "METRICS,UNAVAILABLE", "ACCESS_RECORDS,TOO_LARGE", "ACCESS_RECORDS,UNAVAILABLE"})
    void unavailableHistoryKeepsAuthorizedFactsAndDisclosesUnknown(String kind, String code) {
        when(ch.queryOptionalProof(anyString(), anyString(), anyInt())).thenThrow(new QueryFailure(code, "proof unavailable"));
        var result = service.query(request(kind));
        if ("METRICS".equals(kind)) {
            var requested = (Map<?, ?>) ((Map<?, ?>) result.get("metrics")).get("requested");
            assertEquals(3L, requested.get("pv")); assertEquals(2L, requested.get("uv")); assertEquals(2L, requested.get("uip"));
            assertEquals(List.of(), requested.get("uvTypeStats"));
        } else {
            var records = (List<?>) result.get("items");
            assertEquals(2, records.size());
            for (Object row : records) assertEquals("UNKNOWN", ((Map<?, ?>) row).get("uvType"));
        }
        var quality = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) result.get("meta")).get("dimensionQuality")).get("uvTypeStats");
        assertEquals("UNKNOWN", quality.get("status")); assertEquals(2L, quality.get("unknownUv"));
        assertEquals("TOO_LARGE".equals(code) ? "HISTORY_PROOF_BUDGET_EXCEEDED" : "HISTORY_PROOF_UNAVAILABLE", quality.get("reason"));
        verify(ch).queryOptionalProof(anyString(), contains("uniqExact(source_offset)"), eq(4096));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "NOT_READY", "SNAPSHOT_EXPIRED"})
    void protectedProofFailuresStopBeforeFacts(String code) {
        var failure = new QueryFailure(code, "protected failure");
        when(ch.queryOptionalProof(anyString(), anyString(), anyInt())).thenThrow(failure);
        assertSame(failure, assertThrows(QueryFailure.class, () -> service.query(request("METRICS"))));
        verify(ch, never()).query(anyString(), startsWith("SELECT tupleElement"), anyInt());
    }

    @Test
    void currentFactsFailureNeverBecomesAZeroReport() {
        when(ch.queryOptionalProof(anyString(), anyString(), anyInt())).thenThrow(new QueryFailure("TOO_LARGE", "proof budget"));
        factsFailure = new QueryFailure("UNAVAILABLE", "facts unavailable");
        assertSame(factsFailure, assertThrows(QueryFailure.class, () -> service.query(request("METRICS"))));
    }

    @Test
    void epochChangeStillRejectsAfterHistoryFallback() {
        when(ch.queryOptionalProof(anyString(), anyString(), anyInt())).thenThrow(new QueryFailure("UNAVAILABLE", "proof unavailable"));
        when(auth.activeEpoch()).thenReturn("epoch", "new-epoch");
        assertEquals("QUERY_SCOPE_CHANGED", assertThrows(QueryFailure.class, () -> service.query(request("METRICS"))).code);
    }

    @Test
    void authorizationFailureDoesNotReadOptionalProof() {
        when(auth.authorize(any())).thenThrow(new QueryFailure("FORBIDDEN", "revoked"));
        assertEquals("FORBIDDEN", assertThrows(QueryFailure.class, () -> service.query(request("METRICS"))).code);
        verifyNoInteractions(ch);
    }

    private QueryRequest request(String kind) {
        return new QueryRequest("tenant", "alice", 1, "owned", List.of(11L), start, end,
                null, "REQUESTED", null, null, 100, kind);
    }

    private Map<String, Object> record(String event, String visitor) {
        return new LinkedHashMap<>(Map.of("eventId", event, "linkId", 11L, "occurredAt", start + 1,
                "visitorHash", visitor, "uvType", "UNKNOWN"));
    }
}
