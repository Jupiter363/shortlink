package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AnalyticsDimensionBreakdownTest {
    final AuthorizationClient auth = mock(AuthorizationClient.class);
    final ClickHouseReader ch = mock(ClickHouseReader.class);
    final AtomicInteger reads = new AtomicInteger();
    final long end = Math.floorDiv(System.currentTimeMillis(), 300_000) * 300_000, start = end - 300_000;
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
                + "'[\"http://localhost:3\"]','{}','{\"n\":4,\"digest\":\"7\"}')", start, end);
        db.execute("CREATE TABLE analytics_snapshot(snapshot_id VARCHAR(64),recovery_epoch VARCHAR(128),"
                + "tenant_id VARCHAR(128),subject_id VARCHAR(128),ownership_version VARCHAR(128),"
                + "request_hash VARCHAR(128),result_json TEXT,expires_at TIMESTAMP)");
        when(auth.authorize(any())).thenReturn(new AuthorizationClient.Scope("tenant", List.of(11L), "v1"));
        when(auth.activeEpoch()).thenReturn("epoch");
        when(auth.coverage(anyLong())).thenReturn(Map.of("recoveryEpoch", "epoch", "observedAt", end));
        raw = List.of(Map.of("group_row", 1, "pv", 4, "uv", 1, "uip", 1, "geo_conflicts", 0, "country_unknown", 1),
                Map.of("group_row", 0, "bucket_key", List.of("KNOWN", "广东省", "KNOWN", "Mobile"), "pv", 3, "uv", 1, "uip", 1),
                Map.of("group_row", 0, "bucket_key", List.of("UNKNOWN", "", "KNOWN", "PC"), "pv", 1, "uv", 1, "uip", 1));
        when(ch.query(anyString(), anyString(), anyInt())).thenAnswer(i -> {
            if (((String) i.getArgument(1)).contains("groupBitXor")) return List.of(Map.of("build_id", "build", "n", 4, "digest", "7"));
            reads.incrementAndGet(); return raw;
        });
        service = new AnalyticsQueryService(db, new ObjectMapper(), auth, ch, new ApiSettings("test-internal-token-long-enough",
                "http://localhost:1", "http://localhost:2", "http://localhost:3", "test", "", "test"));
    }

    @Test
    void normalizedFilterContinuationKeepsFullWindowDenominatorAndQuality() {
        var first = service.query(request(null, null, List.of("province", "device"), List.of("Firefox", "Chrome", "Chrome")));
        var meta = map(first.get("meta"));
        assertEquals(2L, meta.get("totalRows")); assertEquals(true, meta.get("resultComplete"));
        assertEquals("FILTERED_FULL_WINDOW", meta.get("dimensionQualityScope"));
        assertEquals(List.of(Map.of("dimension", "browser", "operator", "IN", "values", List.of("Chrome", "Firefox"))), meta.get("filters"));
        assertEquals(.75D, map(((List<?>) first.get("items")).get(0)).get("pvRatio"));
        assertEquals(1L, map(map(first.get("metrics")).get("requested")).get("uv"));
        var quality = map(map(meta.get("dimensionQuality")).get("province"));
        assertEquals(3L, quality.get("knownCount")); assertEquals(1L, quality.get("unknownCount"));
        String id = meta.get("snapshotId").toString(), cursor = meta.get("nextCursor").toString();
        raw = List.of();
        var second = service.query(request(id, cursor, List.of("province", "device"), List.of("Chrome", "Firefox")));
        assertEquals(.25D, map(((List<?>) second.get("items")).get(0)).get("pvRatio"));
        assertEquals(4, ((Number) map(map(second.get("metrics")).get("requested")).get("pv")).intValue());
        assertEquals(3, ((Number) map(map(map(second.get("meta")).get("dimensionQuality")).get("province")).get("knownCount")).intValue());
        assertNull(map(second.get("meta")).get("nextCursor")); assertEquals(1, reads.get());
        assertEquals("QUERY_SCOPE_CHANGED", assertThrows(QueryFailure.class,
                () -> service.query(request(id, cursor, List.of("province", "device"), List.of("Chrome")))).code);
        assertEquals("QUERY_SCOPE_CHANGED", assertThrows(QueryFailure.class,
                () -> service.query(request(id, cursor, List.of("device", "province"), List.of("Chrome", "Firefox")))).code);
    }

    @Test
    void legacyKindCannotIgnoreFieldsOnInitialOrContinuationRequests() {
        var first = service.query(request(null, null, List.of("province", "device"), List.of("Chrome")));
        String snapshot = map(first.get("meta")).get("snapshotId").toString();
        for (String id : Arrays.asList(null, snapshot)) {
            var invalid = new QueryRequest("tenant", "alice", 1, "owned", List.of(11L), start, end,
                    null, "REQUESTED", id, null, 1, "METRICS", List.of("country"), List.of());
            assertEquals("INVALID_QUERY", assertThrows(QueryFailure.class, () -> service.query(invalid)).code);
        }
        assertEquals(1, reads.get());
    }

    @Test
    void exceedingBucketLimitNeverPublishesTruncatedSnapshot() {
        raw = new ArrayList<>();
        raw.add(Map.of("group_row", 1, "pv", 5001, "uv", 1, "uip", 1, "geo_conflicts", 0, "country_unknown", 0));
        for (int i = 0; i < 5001; i++) raw.add(Map.of("group_row", 0,
                "bucket_key", List.of("KNOWN", "province-" + i, "KNOWN", "PC"), "pv", 1, "uv", 1, "uip", 1));
        assertEquals("TOO_LARGE", assertThrows(QueryFailure.class,
                () -> service.query(request(null, null, List.of("province", "device"), List.of("Chrome")))).code);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM analytics_snapshot", Integer.class));
    }

    private QueryRequest request(String id, String cursor, List<String> dimensions, List<String> browsers) {
        return new QueryRequest("tenant", "alice", 1, "owned", List.of(11L), start, end,
                null, "REQUESTED", id, cursor, 1, DimensionBreakdown.KIND, dimensions,
                List.of(new DimensionFilter("browser", "IN", browsers)));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
