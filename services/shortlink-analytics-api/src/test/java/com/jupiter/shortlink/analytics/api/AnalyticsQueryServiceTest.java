package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

class AnalyticsQueryServiceTest {
    @Test
    void rejectsOversizedPageBeforeAuthorization() {
        var a = mock(AuthorizationClient.class);
        var q =
                new AnalyticsQueryService(
                        mock(JdbcTemplate.class),
                        new ObjectMapper(),
                        a,
                        mock(ClickHouseReader.class),
                        settings());
        var request =
                new QueryRequest(
                        "1",
                        "alice",
                        1,
                        null,
                        List.of(1L),
                        0L,
                        100L,
                        null,
                        "REQUESTED",
                        null,
                        null,
                        501,
                        "METRICS");
        assertEquals("TOO_LARGE", assertThrows(QueryFailure.class, () -> q.query(request)).code);
        verifyNoInteractions(a);
    }

    @Test
    void longCountsNeverNarrowToInt() {
        assertEquals(4_294_967_296L, AnalyticsQueryService.number("4294967296"));
    }

    @Test
    void unauthorizedScopeNeverReadsStatistics() {
        var auth = mock(AuthorizationClient.class);
        var ch = mock(ClickHouseReader.class);
        var db = mock(JdbcTemplate.class);
        var request =
                new QueryRequest(
                        "1",
                        "alice",
                        1,
                        null,
                        List.of(1L),
                        0L,
                        100L,
                        null,
                        "REQUESTED",
                        null,
                        null,
                        10,
                        "METRICS");
        when(auth.authorize(request)).thenThrow(new QueryFailure("FORBIDDEN", "denied"));
        assertThrows(
                QueryFailure.class,
                () ->
                        new AnalyticsQueryService(db, new ObjectMapper(), auth, ch, settings())
                                .query(request));
        verifyNoInteractions(ch, db);
    }

    @Test
    void aCanonicalSevenDaySnapshotVerifiesEveryBuildInBatchesBeforeReadingFacts() throws Exception {
        var auth = mock(AuthorizationClient.class);
        var ch = mock(ClickHouseReader.class);
        var db = mock(JdbcTemplate.class);
        var quality = mock(CollectionQualityReader.class);
        var json = new ObjectMapper();
        long end = AnalyticsQueryService.MAX_RANGE;
        var request = new QueryRequest("tenant", "alice", 1, "group", List.of(11L), 0L, end,
                null, "REQUESTED", null, null, 10, LinkMetrics.KIND);
        when(auth.authorize(request)).thenReturn(new AuthorizationClient.Scope("tenant", List.of(11L), "v1"));
        when(auth.activeEpoch()).thenReturn("epoch");
        when(auth.coverage(0L)).thenReturn(Map.of("observedAt", end));
        when(db.queryForList(startsWith("SELECT * FROM analytics_manifest"), eq("epoch"), eq(0L), eq(end)))
                .thenReturn(canonicalManifests(json, false));
        when(quality.read(eq(0L), eq(end), anyLong())).thenReturn(Map.of("status", "UNKNOWN"));
        var queries = new ArrayList<String>();
        when(ch.query(eq("http://localhost:3"), anyString(), anyInt())).thenAnswer(call -> {
            String sql = call.getArgument(1);
            queries.add(sql);
            if (sql.contains("groupBitXor")) return List.of();
            assertEquals(17, queries.size(), "All 2016 build proofs must precede the facts read");
            assertTrue(sql.contains("(build_id,window_start) IN (("));
            assertTrue(sql.contains("('build-0',0)"));
            assertTrue(sql.contains("('build-2015',604500000)"));
            assertFalse(sql.contains("build_id='build-0' AND window_start"));
            assertTrue(sql.contains("tenant_id='tenant'"));
            assertTrue(sql.contains("link_id IN (11)"));
            return List.of(Map.of("group_row", 1, "pv", 0, "uv", 0, "uip", 0, "denied", 0));
        });
        var result = new AnalyticsQueryService(db, json, auth, ch, settings(), quality).query(request);
        var meta = (Map<?, ?>) result.get("meta");
        assertEquals("AVAILABLE", meta.get("availability"));
        assertEquals("COMPLETE", meta.get("completeness"));
        assertEquals(2016, ((Map<?, ?>) meta.get("manifestVersion")).size());
        assertEquals(8, queries.stream().filter(sql -> sql.contains("groupBitXor") && !sql.contains("dimensionDigest")).count());
        assertEquals(8, queries.stream().filter(sql -> sql.contains("dimensionDigest")).count());
        verify(auth, times(2)).authorize(request);
        verify(auth, times(2)).activeEpoch();
    }

    @Test
    void aBadProofInTheLastCanonicalWindowStopsBeforeFactsOrSnapshotCreation() throws Exception {
        var auth = mock(AuthorizationClient.class);
        var ch = mock(ClickHouseReader.class);
        var db = mock(JdbcTemplate.class);
        var json = new ObjectMapper();
        long end = AnalyticsQueryService.MAX_RANGE;
        var request = new QueryRequest("tenant", "alice", 1, "group", List.of(11L), 0L, end,
                null, "REQUESTED", null, null, 10, LinkMetrics.KIND);
        when(auth.authorize(request)).thenReturn(new AuthorizationClient.Scope("tenant", List.of(11L), "v1"));
        when(auth.activeEpoch()).thenReturn("epoch");
        when(auth.coverage(0L)).thenReturn(Map.of("observedAt", end));
        when(db.queryForList(startsWith("SELECT * FROM analytics_manifest"), eq("epoch"), eq(0L), eq(end)))
                .thenReturn(canonicalManifests(json, true));
        when(ch.query(anyString(), anyString(), anyInt())).thenReturn(List.of());
        var service = new AnalyticsQueryService(db, json, auth, ch, settings());
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> service.query(request)).code);
        verify(ch, times(15)).query(eq("http://localhost:3"), contains("groupBitXor"), anyInt());
        verify(ch, never()).query(anyString(), startsWith("SELECT link_id"), anyInt());
        verify(db, never()).update(startsWith("INSERT INTO analytics_snapshot"), any(Object[].class));
    }

    private List<Map<String, Object>> canonicalManifests(ObjectMapper json, boolean badLast) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2016; i++) {
            long start = i * AnalyticsQueryService.WINDOW;
            result.add(Map.of("window_start", start, "build_id", "build-" + i, "manifest_revision", 1,
                    "source_observed_at", start + AnalyticsQueryService.WINDOW, "finalized", true,
                    "replica_ids", "[\"http://localhost:3\"]", "source_cut", "{}", "coverage_proof",
                    json.writeValueAsString(Map.of("n", badLast && i == 2015 ? 1 : 0, "digest", "0",
                            "dimensionVersion", "geo-v1", "dimensionDigest", "0"))));
        }
        return result;
    }

    private ApiSettings settings() {
        return new ApiSettings(
                "test-internal-token-123456789",
                "http://localhost:1",
                "http://localhost:2",
                "http://localhost:3",
                "default",
                "",
                "test");
    }
}
