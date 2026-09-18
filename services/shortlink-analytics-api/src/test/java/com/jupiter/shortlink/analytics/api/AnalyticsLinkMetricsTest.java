package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AnalyticsLinkMetricsTest {
    final ObjectMapper json = new ObjectMapper();
    final AuthorizationClient auth = mock(AuthorizationClient.class);
    final ClickHouseReader ch = mock(ClickHouseReader.class);
    final AtomicReference<String> ownership = new AtomicReference<>("v1");
    final AtomicReference<String> epoch = new AtomicReference<>("epoch");
    final AtomicInteger reads = new AtomicInteger();
    final long end = Math.floorDiv(System.currentTimeMillis(), 300_000) * 300_000;
    final long start = end - 300_000;
    List<Map<String, Object>> raw;
    JdbcTemplate db;
    AnalyticsQueryService service;

    @BeforeEach
    void setup() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE analytics_manifest(recovery_epoch VARCHAR(128),window_start BIGINT,"
                + "build_id VARCHAR(64),manifest_revision BIGINT,source_observed_at BIGINT,finalized BOOLEAN,"
                + "replica_ids TEXT,source_cut TEXT,coverage_proof TEXT)");
        db.update("INSERT INTO analytics_manifest VALUES('epoch',?,'build',1,?,TRUE,"
                + "'[\"http://localhost:3\"]','{}','{\"n\":5,\"digest\":\"7\"}')", start, end);
        db.execute("CREATE TABLE analytics_snapshot(snapshot_id VARCHAR(64),recovery_epoch VARCHAR(128),"
                + "tenant_id VARCHAR(128),subject_id VARCHAR(128),ownership_version VARCHAR(128),"
                + "request_hash VARCHAR(128),result_json TEXT,expires_at TIMESTAMP)");
        when(auth.authorize(any())).thenAnswer(i ->
                new AuthorizationClient.Scope("tenant", List.of(11L, 22L, 33L), ownership.get()));
        when(auth.activeEpoch()).thenAnswer(i -> epoch.get());
        when(auth.coverage(anyLong())).thenReturn(Map.of("recoveryEpoch", "epoch", "observedAt", end));
        raw = List.of(row(1, 0, 4, 1, 1, 1), row(0, 22, 2, 1, 1, 0), row(0, 11, 2, 1, 1, 1));
        when(ch.query(anyString(), anyString(), anyInt())).thenAnswer(i -> {
            if (((String) i.getArgument(1)).contains("groupBitXor")) return List.of(Map.of("build_id", "build", "n", 5, "digest", "7"));
            reads.incrementAndGet();
            return raw;
        });
        service = new AnalyticsQueryService(db, json, auth, ch, new ApiSettings("test-internal-token-long-enough",
                "http://localhost:1", "http://localhost:2", "http://localhost:3", "test", "", "test"));
    }

    @Test
    void frozenPagesKeepIndependentGroupDistinctCountsAndAuthorizedZeros() {
        var first = service.query(request(null, null, 1, LinkMetrics.KIND));
        var meta = map(first.get("meta"));
        assertEquals(11L, map(((List<?>) first.get("items")).get(0)).get("linkId"));
        assertEquals(1L, map(map(first.get("metrics")).get("requested")).get("uv"));
        assertEquals(List.of(11L, 22L, 33L), meta.get("linkIds"));
        assertEquals("owned", meta.get("gid"));
        assertEquals(LinkMetrics.KIND, meta.get("queryKind"));
        assertEquals(3, meta.get("totalRows"));
        assertEquals("UNKNOWN", map(meta.get("collectionQuality")).get("status"));
        assertEquals(Map.of(), meta.get("dimensionQuality"));
        raw = List.of(row(1, 0, 99, 99, 99, 0));
        var second = service.query(request(meta.get("snapshotId").toString(), meta.get("nextCursor").toString(), 2, LinkMetrics.KIND));
        var items = (List<?>) second.get("items");
        assertEquals(22L, ((Number) map(items.get(0)).get("linkId")).longValue());
        assertEquals(33L, ((Number) map(items.get(1)).get("linkId")).longValue());
        assertEquals(0, ((Number) map(items.get(1)).get("pv")).intValue());
        assertEquals(1, ((Number) map(map(second.get("metrics")).get("requested")).get("uv")).intValue());
        assertEquals(meta.get("snapshotId"), map(second.get("meta")).get("snapshotId"));
        assertNull(map(second.get("meta")).get("nextCursor"));
        assertEquals(1, reads.get());
        verify(ch, never()).queryOptionalProof(anyString(), anyString(), anyInt());
    }

    @Test
    void continuationBindsKindOwnershipAndEpoch() {
        var meta = map(service.query(request(null, null, 1, LinkMetrics.KIND)).get("meta"));
        String id = meta.get("snapshotId").toString(), cursor = meta.get("nextCursor").toString();
        assertEquals("QUERY_SCOPE_CHANGED", assertThrows(QueryFailure.class,
                () -> service.query(request(id, cursor, 1, "METRICS"))).code);
        ownership.set("v2");
        assertEquals("QUERY_SCOPE_CHANGED", assertThrows(QueryFailure.class,
                () -> service.query(request(id, cursor, 1, LinkMetrics.KIND))).code);
        ownership.set("v1"); epoch.set("new-epoch");
        assertEquals("SNAPSHOT_EXPIRED", assertThrows(QueryFailure.class,
                () -> service.query(request(id, cursor, 1, LinkMetrics.KIND))).code);
        assertEquals(1, reads.get());
    }

    @Test
    void missingSummaryAndUnauthorizedRowsNeverBecomeZeroSnapshots() {
        raw = List.of(row(0, 11, 2, 1, 1, 0));
        assertEquals("UNAVAILABLE", assertThrows(QueryFailure.class,
                () -> service.query(request(null, null, 500, LinkMetrics.KIND))).code);
        raw = List.of(row(1, 0, 2, 1, 1, 0), row(0, 999, 2, 1, 1, 0));
        assertEquals("UNAVAILABLE", assertThrows(QueryFailure.class,
                () -> service.query(request(null, null, 500, LinkMetrics.KIND))).code);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM analytics_snapshot", Integer.class));
    }

    @Test
    void namedWindowsAreRejectedRatherThanSilentlyCollapsed() {
        var q = new QueryRequest("tenant", "alice", 1, "owned", List.of(11L), start, end,
                List.of("2h"), "COMMON_AVAILABLE_END", null, null, 100, LinkMetrics.KIND);
        assertEquals("INVALID_QUERY", assertThrows(QueryFailure.class, () -> service.query(q)).code);
        verifyNoInteractions(ch);
    }

    @Test
    void authorizationChangeAfterQueryPreventsSnapshotPublication() {
        when(auth.authorize(any())).thenReturn(
                new AuthorizationClient.Scope("tenant", List.of(11L, 22L, 33L), "v1"),
                new AuthorizationClient.Scope("tenant", List.of(11L, 22L, 33L), "v2"));
        assertEquals("QUERY_SCOPE_CHANGED", assertThrows(QueryFailure.class,
                () -> service.query(request(null, null, 500, LinkMetrics.KIND))).code);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM analytics_snapshot", Integer.class));
    }

    private QueryRequest request(String snapshot, String cursor, int size, String kind) {
        return new QueryRequest("tenant", "alice", 1, "owned", List.of(11L, 22L, 33L), start, end,
                null, "REQUESTED", snapshot, cursor, size, kind);
    }

    private static Map<String, Object> row(int group, long link, long pv, long uv, long uip, long denied) {
        return Map.of("group_row", group, "linkId", link, "pv", pv, "uv", uv, "uip", uip, "denied", denied);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
