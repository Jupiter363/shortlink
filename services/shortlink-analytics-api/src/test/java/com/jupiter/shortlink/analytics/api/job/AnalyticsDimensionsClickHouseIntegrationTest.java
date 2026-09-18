package com.jupiter.shortlink.analytics.api.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;
import com.jupiter.shortlink.contract.SourceCut;
import com.jupiter.shortlink.contract.Topics;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Requires a separately provisioned, disposable geo IT schema. Never touches the local UAT DB. */
class AnalyticsDimensionsClickHouseIntegrationTest {
    final ObjectMapper json = new ObjectMapper();
    ApiSettings settings;
    final String tenant = "dimensions-it-" + UUID.randomUUID();
    final long start = Math.floorDiv(System.currentTimeMillis() - 600_000L, 300_000L) * 300_000L;
    final long end = start + 300_000L;

    @BeforeEach
    void isolatedConfiguration() {
        String password = System.getenv("ANALYTICS_DIMENSION_IT_CLICKHOUSE_PASSWORD");
        Assumptions.assumeTrue(password != null, "Explicit isolated ClickHouse IT credentials are required");
        String database = env("ANALYTICS_DIMENSION_IT_CLICKHOUSE_DATABASE", "shortlink_geo_it");
        assertTrue(database.equals("shortlink_geo_it") || database.startsWith("shortlink_dimensions_it_"),
                "Dimension IT must use its isolated database");
        settings = new ApiSettings("dimension-it-internal-token", "http://localhost:1", "http://localhost:2",
                env("ANALYTICS_DIMENSION_IT_CLICKHOUSE_URL", "http://127.0.0.1:18123"),
                env("ANALYTICS_DIMENSION_IT_CLICKHOUSE_USER", "shortlink_it"), password, database);
    }

    @Test
    void realInstantAndJobDimensionsUseFrozenHistoryCoherentGeographyAndDistinctGroupVisitors() throws Exception {
        List<Map<String, Object>> receipts = new ArrayList<>();
        receipts.add(receipt(0, 101, start - 86_400_000L, "visitor-a"));
        receipts.add(receipt(1, 202, start - 7_200_000L, "visitor-b"));
        var legacy = receipt(2, 101, start + 1000, "visitor-a");
        legacy.put("country", "UNKNOWN"); legacy.put("province", "UNKNOWN");
        legacy.put("network", "UNKNOWN"); legacy.put("geo_status", "UNKNOWN"); legacy.put("geo_version", "");
        receipts.add(legacy);
        var enriched = receipt(2, 101, start + 1000, "visitor-a");
        receipts.add(enriched);
        receipts.add(receipt(3, 202, start + 2000, "visitor-a"));
        var alternateResult = receipt(4, 101, start + 3000, "visitor-c");
        alternateResult.put("status", 307);
        receipts.add(alternateResult);
        var anonymous = receipt(5, 101, start + 4000, "");
        anonymous.put("status", 0);
        anonymous.put("country", "UNKNOWN"); anonymous.put("province", "UNKNOWN"); anonymous.put("network", "UNKNOWN");
        anonymous.put("geo_status", "NON_PUBLIC");
        receipts.add(anonymous);
        var denied = receipt(6, 101, start + 5000, "visitor-a");
        denied.put("kind", "REQUEST"); denied.put("status", 429);
        denied.put("topic_id", "request-topic-id"); denied.put("source_topic", Topics.GATEWAY_REQUEST);
        denied.put("source_offset", 0); denied.put("event_id", tenant + "-request-event"); denied.put("receipt_id", tenant + "-request-receipt");
        receipts.add(denied);
        var foreign = receipt(6, 101, start - 20_000L, "visitor-c");
        foreign.put("tenant_id", tenant + "-foreign"); receipts.add(foreign);
        insert("event_receipts", receipts);

        // Login/admin requests have advanced far beyond the single landed request receipt.
        var cut = new SourceCut(List.of(new SourceCut.Range(tenant, "topic-id", Topics.CLICK_RAW, 0, 0, 7),
                new SourceCut.Range(tenant, "request-topic-id", Topics.GATEWAY_REQUEST, 0, 0, 1000)));
        var reader = reader();
        var candidate = VisitorHistory.plan(cut, json, System.currentTimeMillis(), end);
        var history = VisitorHistory.verifyVisibility(candidate, reader.query(settings.clickHouseUrls(), VisitorHistory.visibilitySql(candidate), 4096));
        assertTrue(history.available());
        var auth = mock(AuthorizationClient.class);
        when(auth.authorize(any())).thenReturn(new AuthorizationClient.Scope(tenant, List.of(101L, 202L), "v1"));
        when(auth.activeEpoch()).thenReturn(tenant);
        when(auth.coverage(anyLong())).thenReturn(Map.of("recoveryEpoch", tenant, "observedAt", end,
                "sourceCut", json.convertValue(cut, Map.class), "receipts", List.of(Map.of("clusterId", tenant,
                        "topicId", "topic-id", "partition", 0, "count", 7), Map.of("clusterId", tenant,
                        "topicId", "request-topic-id", "partition", 0, "count", 1000))));
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE analytics_manifest(recovery_epoch VARCHAR(128),window_start BIGINT)");
        db.execute("CREATE TABLE analytics_snapshot(snapshot_id VARCHAR(64),recovery_epoch VARCHAR(128),tenant_id VARCHAR(128),"
                + "subject_id VARCHAR(128),ownership_version VARCHAR(128),request_hash VARCHAR(128),result_json VARCHAR(16777216),expires_at TIMESTAMP)");
        var service = new AnalyticsQueryService(db, json, auth, reader, settings);
        var result = service.query(request("METRICS", null, null));
        var total = map(map(result.get("metrics")).get("requested"));
        assertEquals(4L, total.get("pv")); assertEquals(2L, total.get("uv")); assertEquals(1L, total.get("denied"));
        var uvQuality = map(map(total.get("dimensionQuality")).get("uvTypeStats"));
        assertEquals(1L, uvQuality.get("newUv")); assertEquals(1L, uvQuality.get("oldUv"));
        assertEquals(1L, uvQuality.get("missingVisitorClicks"));
        assertEquals("WORKER_RECEIPT_COUNTS", uvQuality.get("historyProof"));
        assertEquals(Topics.CLICK_RAW, uvQuality.get("historySourceTopic"));
        assertEquals("PARTIAL", map(result.get("meta")).get("completeness"));
        assertEquals(.75D, total.get("topRegionShare"));
        assertEquals(3L, map(((List<?>) total.get("networkStats")).get(0)).get("cnt"));
        var records = service.query(request("ACCESS_RECORDS", null, null));
        var recordItems = (List<Map<String, Object>>) records.get("items");
        assertEquals(4, recordItems.size());
        var replayed = recordItems.stream().filter(row -> (tenant + "-event-2").equals(row.get("eventId"))).findFirst().orElseThrow();
        assertEquals("CN", replayed.get("country")); assertEquals("广东省", replayed.get("province"));
        assertEquals("电信", replayed.get("network")); assertEquals("oldUser", replayed.get("uvType"));
        assertEquals("CLICK", replayed.get("kind")); assertEquals("302", replayed.get("status").toString());
        var alternateRecord = recordItems.stream().filter(row -> (tenant + "-event-4").equals(row.get("eventId"))).findFirst().orElseThrow();
        assertEquals("CLICK", alternateRecord.get("kind")); assertEquals("307", alternateRecord.get("status").toString());
        var unknownRecord = recordItems.stream().filter(row -> (tenant + "-event-5").equals(row.get("eventId"))).findFirst().orElseThrow();
        assertEquals("0", unknownRecord.get("status").toString(), "Unknown stored results must not default to a redirect");
        var multipleWindows = service.query(request("METRICS", null, List.of("2h", "24h")));
        var twoHours = map(map(multipleWindows.get("metrics")).get("2h"));
        var day = map(map(multipleWindows.get("metrics")).get("24h"));
        assertEquals(2L, twoHours.get("uv")); assertEquals(3L, day.get("uv"));
        assertEquals(1L, map(map(twoHours.get("dimensionQuality")).get("uvTypeStats")).get("newUv"));
        assertEquals(2L, map(map(day.get("dimensionQuality")).get("uvTypeStats")).get("newUv"));

        // An earlier event above the frozen upper offset cannot turn visitor-c into an old visitor.
        insert("event_receipts", List.of(receipt(7, 101, start - 30_000L, "visitor-c")));
        var repeated = map(map(service.query(request("METRICS", null, null)).get("metrics")).get("requested"));
        assertEquals(1L, map(map(repeated.get("dimensionQuality")).get("uvTypeStats")).get("newUv"));

        List<Map<String, Object>> buildRows = new ArrayList<>();
        for (var source : receipts.stream().filter(row -> tenant.equals(row.get("tenant_id"))
                && ((Number) row.get("occurred_at")).longValue() >= start
                && !"".equals(row.get("geo_version"))).toList()) {
            var row = new LinkedHashMap<>(source);
            for (String key : List.of("cluster_id", "topic_id", "source_topic", "source_partition", "source_offset", "timestamp_type")) row.remove(key);
            row.put("build_id", tenant); row.put("window_start", start); buildRows.add(row);
        }
        insert("rebuild_input", buildRows);
        var dimensionProof = reader.query(settings.clickHouseUrls(), DimensionProof.sql(tenant), 1).get(0);
        var rawProof = reader.query(settings.clickHouseUrls(), "SELECT count() n,toString(groupBitXor(cityHash64(receipt_id,payload_hash,validation_result))) digest"
                + " FROM (SELECT receipt_id,payload_hash,validation_result FROM rebuild_input WHERE build_id=" + ClickHouseReader.quote(tenant)
                + " GROUP BY receipt_id,payload_hash,validation_result)", 1).get(0);
        rawProof.put("dimensionDigest", dimensionProof.get("dimensionDigest")); rawProof.put("dimensionVersion", "geo-v1");
        var plan = new ManifestPlan(tenant, List.of(new ManifestPlan.Window(start, tenant, 1, json.writeValueAsString(cut),
                end, "click-v1", "detail-geo-v1", json.writeValueAsString(rawProof))), List.of(settings.clickHouseUrls()));
        var lease = new QueryJobService.Lease(tenant, "worker", 1, request("METRICS", null, null), tenant, "v1", plan, System.currentTimeMillis());
        try (var stream = new JobClickHouseStream(settings, json)) {
            assertEquals(settings.clickHouseUrls(), stream.verify(plan, System.nanoTime() + Duration.ofSeconds(30).toNanos()));
        }
        var jobRows = reader.query(settings.clickHouseUrls(), QueryJobService.sql(lease, history), 1000);
        var jobTotal = jobRows.stream().filter(row -> "1".equals(row.get("whole_window").toString())).findFirst().orElseThrow();
        assertEquals("4", jobTotal.get("pv").toString()); assertEquals("2", jobTotal.get("uv").toString());
        assertEquals("1", jobTotal.get("scope_new_uv").toString()); assertEquals("1", jobTotal.get("scope_old_uv").toString());

        var recordLease = new QueryJobService.Lease(tenant, "worker", 1, request("ACCESS_RECORDS", null, null),
                tenant, "v1", plan, System.currentTimeMillis());
        var jobRecords = reader.query(settings.clickHouseUrls(), QueryJobService.sql(recordLease, history), 1000);
        assertEquals(4, jobRecords.size());
        for (var expected : recordItems) {
            var actual = jobRecords.stream().filter(row -> expected.get("eventId").equals(row.get("eventId"))).findFirst().orElseThrow();
            assertEquals(expected.get("kind"), actual.get("kind"));
            assertEquals(expected.get("status").toString(), actual.get("status").toString());
        }

        // Conflicting dimension versions suppress only concentration evidence, preserving PV/UV.
        var conflict = new LinkedHashMap<>(enriched); conflict.put("geo_version", "fixture-v2"); conflict.put("province", "北京");
        insert("event_receipts", List.of(conflict));
        var changed = map(map(service.query(request("METRICS", null, null)).get("metrics")).get("requested"));
        assertEquals(4L, changed.get("pv")); assertNull(changed.get("topRegionShare"));
        var frozen = service.query(request("METRICS", map(result.get("meta")).get("snapshotId").toString(), null));
        assertEquals(.75D, map(map(frozen.get("metrics")).get("requested")).get("topRegionShare"));
    }

    private QueryRequest request(String kind, String snapshot, List<String> windows) {
        return new QueryRequest(tenant, "alice", 1, "owned", List.of(101L, 202L), start, end, windows,
                windows == null ? "REQUESTED" : "COMMON_AVAILABLE_END", snapshot, null, 500, kind);
    }

    @Test
    void jobDailyFirstObservedBoundaryDiffersFromWholeRequestedWindow() throws Exception {
        long firstDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(2)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long secondDay = firstDay + 86_400_000L, requestedEnd = secondDay + 86_400_000L;
        var first = receipt(0, 303, firstDay + 3_600_000L, "returning-visitor");
        var second = receipt(1, 303, secondDay + 3_600_000L, "returning-visitor");
        insert("event_receipts", List.of(first, second));
        var buildRows = new ArrayList<Map<String, Object>>();
        for (var source : List.of(first, second)) {
            var row = new LinkedHashMap<>(source);
            for (String key : List.of("cluster_id", "topic_id", "source_topic", "source_partition", "source_offset", "timestamp_type")) row.remove(key);
            row.put("build_id", tenant); row.put("window_start", firstDay); buildRows.add(row);
        }
        insert("rebuild_input", buildRows);
        var cut = new SourceCut(List.of(new SourceCut.Range(tenant, "topic-id", Topics.CLICK_RAW, 0, 0, 2)));
        var candidate = VisitorHistory.plan(cut, json, System.currentTimeMillis(), requestedEnd);
        var reader = reader();
        var history = VisitorHistory.verifyVisibility(candidate, reader.query(settings.clickHouseUrls(), VisitorHistory.visibilitySql(candidate), 4096));
        assertTrue(history.available());
        var q = new QueryRequest(tenant, "alice", 1, "owned", List.of(303L), firstDay, requestedEnd,
                null, "REQUESTED", null, null, 500, "METRICS");
        var plan = new ManifestPlan(tenant, List.of(new ManifestPlan.Window(firstDay, tenant, 1, json.writeValueAsString(cut),
                requestedEnd, "click-v1", "detail-geo-v1", "{}")), List.of(settings.clickHouseUrls()));
        var lease = new QueryJobService.Lease(tenant, "worker", 1, q, tenant, "v1", plan, System.currentTimeMillis());
        var rows = reader.query(settings.clickHouseUrls(), QueryJobService.sql(lease, history), 100);
        var total = rows.stream().filter(row -> "1".equals(row.get("whole_window").toString())).findFirst().orElseThrow();
        assertEquals("1", total.get("scope_new_uv").toString()); assertEquals("0", total.get("scope_old_uv").toString());
        var days = rows.stream().filter(row -> "0".equals(row.get("group_row").toString()))
                .sorted(Comparator.comparing(row -> row.get("day").toString())).toList();
        assertEquals(2, days.size());
        assertEquals("1", days.get(0).get("daily_link_new_uv").toString());
        assertEquals("0", days.get(0).get("daily_link_old_uv").toString());
        assertEquals("0", days.get(1).get("daily_link_new_uv").toString());
        assertEquals("1", days.get(1).get("daily_link_old_uv").toString());
    }

    @Test
    void linkMetricsDeduplicateAcrossDaysAndLinksInReceiptAndFrozenJobFacts() throws Exception {
        long firstDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(10)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long lastDay = firstDay + 8 * 86_400_000L, requestedEnd = lastDay + 86_400_000L;
        var first = receipt(0, 303, firstDay + 1000, "shared-visitor");
        var repeated = receipt(1, 303, lastDay + 1000, "shared-visitor");
        var anotherLink = receipt(2, 404, lastDay + 2000, "shared-visitor");
        var denied = receipt(3, 303, lastDay + 3000, "shared-visitor");
        denied.put("kind", "REQUEST"); denied.put("status", 429);
        var foreignLink = receipt(4, 505, lastDay + 4000, "other-visitor");
        var receipts = List.of(first, first, repeated, anotherLink, denied, foreignLink);
        insert("event_receipts", receipts);
        var buildRows = new ArrayList<Map<String, Object>>();
        for (var source : receipts) {
            var row = new LinkedHashMap<>(source);
            for (String key : List.of("cluster_id", "topic_id", "source_topic", "source_partition", "source_offset", "timestamp_type")) row.remove(key);
            row.put("build_id", tenant); row.put("window_start", firstDay); buildRows.add(row);
        }
        insert("rebuild_input", buildRows);
        var links = List.of(303L, 404L, 606L);
        var q = new QueryRequest(tenant, "alice", 1, "owned", links, firstDay, requestedEnd,
                null, "REQUESTED", null, null, 500, LinkMetrics.KIND);
        var plan = new ManifestPlan(tenant, List.of(new ManifestPlan.Window(firstDay, tenant, 1, "{}",
                requestedEnd, "click-v1", "detail-geo-v1", "{}")), List.of(settings.clickHouseUrls()));
        var lease = new QueryJobService.Lease(tenant, "worker", 1, q, tenant, "v1", plan);
        String receiptFacts = AnalyticsFacts.sql("event_receipts", "1", tenant, links);
        for (String sql : List.of(LinkMetrics.sql(receiptFacts, firstDay, requestedEnd), QueryJobService.sql(lease))) {
            var accumulator = new LinkMetrics.Accumulator(links, firstDay, requestedEnd);
            for (var row : reader().query(settings.clickHouseUrls(), sql, 501)) accumulator.add(row);
            var report = accumulator.finish();
            assertEquals(3L, report.summary().get("pv"));
            assertEquals(1L, report.summary().get("uv"));
            assertEquals(1L, report.summary().get("uip"));
            assertEquals(1L, report.summary().get("denied"));
            assertEquals(2L, report.items().get(0).get("pv"));
            assertEquals(1L, report.items().get(0).get("uv"));
            assertEquals(1L, report.items().get(1).get("uv"));
            assertEquals(0L, report.items().get(2).get("pv"));
        }
    }

    @Test
    void missingClickPartitionStillProducesUnknownEvenWhenRequestsAreIrrelevant() throws Exception {
        insert("event_receipts", List.of(receipt(0, 303, start + 1000, "visible-visitor")));
        var cut = new SourceCut(List.of(new SourceCut.Range(tenant, "topic-id", Topics.CLICK_RAW, 0, 0, 1),
                new SourceCut.Range(tenant, "topic-id", Topics.CLICK_RAW, 1, 0, 1),
                new SourceCut.Range(tenant, "requests", Topics.GATEWAY_REQUEST, 0, 0, 1000)));
        var candidate = VisitorHistory.plan(cut, json, System.currentTimeMillis(), end);
        var visible = reader().query(settings.clickHouseUrls(), VisitorHistory.visibilitySql(candidate), 4096);
        var history = VisitorHistory.verifyVisibility(candidate, visible, cut, List.of(
                Map.of("clusterId", tenant, "topicId", "topic-id", "partition", 0, "count", 1),
                Map.of("clusterId", tenant, "topicId", "topic-id", "partition", 1, "count", 1),
                Map.of("clusterId", tenant, "topicId", "requests", "partition", 0, "count", 1000)), json, true);
        assertFalse(history.available()); assertEquals("HISTORY_RECEIPTS_INCOMPLETE", history.reason());
        var report = new LinkedHashMap<String, Object>();
        VisitorHistory.materialize(report, Map.of("uv", 1L), history, true);
        assertEquals(List.of(), report.get("uvTypeStats"));
        var quality = map(map(report.get("dimensionQuality")).get("uvTypeStats"));
        assertEquals("UNKNOWN", quality.get("status")); assertEquals(1L, quality.get("unknownUv"));
    }

    @Test
    void dimensionBreakdownFiltersJointFactsAndKeepsUnknownSeparateFromForeignProvince() throws Exception {
        var first = receipt(0, 101, start + 1000, "visitor-a");
        first.put("province", "浙江"); first.put("device", "Mobile"); first.put("referer_domain", "news.example");
        var sameBucket = receipt(1, 202, start + 2000, "visitor-a");
        sameBucket.put("province", "浙江"); sameBucket.put("device", "Mobile"); sameBucket.put("referer_domain", "news.example");
        var desktop = receipt(2, 101, start + 3000, "visitor-b"); desktop.put("referer_domain", "search.example");
        var unknown = receipt(3, 101, start + 4000, "visitor-a");
        unknown.put("country", "UNKNOWN"); unknown.put("province", "UNKNOWN");
        unknown.put("device", "Mobile"); unknown.put("referer_domain", "");
        var foreign = receipt(4, 101, start + 5000, "visitor-c");
        foreign.put("country", "US"); foreign.put("province", "California");
        foreign.put("device", "Mobile"); foreign.put("referer_domain", "news.example");
        var denied = receipt(5, 101, start + 6000, "visitor-a");
        denied.put("kind", "REQUEST"); denied.put("status", 429);
        var outsideScope = receipt(6, 999, start + 7000, "visitor-outside");
        var receipts = List.of(first, first, sameBucket, desktop, unknown, foreign, denied, outsideScope);
        insert("event_receipts", receipts);
        var builds = new ArrayList<Map<String, Object>>();
        for (var source : receipts) {
            var row = new LinkedHashMap<>(source);
            for (String key : List.of("cluster_id", "topic_id", "source_topic", "source_partition", "source_offset", "timestamp_type")) row.remove(key);
            row.put("build_id", tenant); row.put("window_start", start); builds.add(row);
        }
        insert("rebuild_input", builds);
        var dimensions = List.of("province", "device", "refererDomain");
        var links = List.of(101L, 202L);
        var filters = List.of(List.<DimensionFilter>of(),
                List.of(new DimensionFilter("device", "IN", List.of("Mobile")),
                        new DimensionFilter("refererDomain", "IN", List.of("news.example"))),
                List.of(new DimensionFilter("province", "IS_UNKNOWN", null)));
        for (int i = 0; i < filters.size(); i++) {
            var q = new QueryRequest(tenant, "alice", 1, "owned", links, start, end,
                    null, "REQUESTED", null, null, 500, DimensionBreakdown.KIND, dimensions, filters.get(i));
            var options = DimensionBreakdown.options(q);
            var plan = new ManifestPlan(tenant, List.of(new ManifestPlan.Window(start, tenant, 1, "{}",
                    end, "click-v1", "detail-geo-v1", "{}")), List.of(settings.clickHouseUrls()));
            var lease = new QueryJobService.Lease(tenant, "worker", 1, q, tenant, "v1", plan);
            String facts = AnalyticsFacts.sql("event_receipts", "1", tenant, links);
            for (String sql : List.of(DimensionBreakdown.sql(facts, start, end, options), QueryJobService.sql(lease))) {
                var acc = new DimensionBreakdown.Accumulator(options, start, end);
                for (var row : reader().query(settings.clickHouseUrls(), sql, 5001)) acc.add(row);
                var report = acc.finish();
                assertEquals(new long[] {5, 3, 1}[i], report.summary().get("pv"));
                assertEquals(new long[] {3, 2, 1}[i], report.summary().get("uv"));
                var quality = map(map(report.summary().get("dimensionQuality")).get("province"));
                assertEquals(new long[] {1, 1, 0}[i], quality.get("notApplicableCount"));
                assertEquals(new long[] {1, 0, 1}[i], quality.get("unknownCount"));
                if (i == 1) {
                    assertEquals(2, report.items().size());
                    assertEquals(2.0 / 3, report.items().get(0).get("pvRatio"));
                    assertEquals("浙江", map(map(report.items().get(0).get("dimensions")).get("province")).get("value"));
                }
                if (i == 2) assertEquals("UNKNOWN", map(map(report.items().get(0).get("dimensions")).get("province")).get("state"));
            }
        }
    }
    private Map<String, Object> receipt(int offset, long link, long time, String visitor) {
        var row = new LinkedHashMap<String, Object>();
        row.put("kind", "CLICK"); row.put("cluster_id", tenant); row.put("topic_id", "topic-id"); row.put("source_topic", Topics.CLICK_RAW);
        row.put("source_partition", 0); row.put("source_offset", offset); row.put("received_at", System.currentTimeMillis()); row.put("timestamp_type", "LogAppendTime");
        row.put("event_id", tenant + "-event-" + offset); row.put("payload_hash", "a".repeat(64)); row.put("tenant_id", tenant); row.put("link_id", link);
        row.put("occurred_at", time); row.put("visitor_hash", visitor); row.put("ip_hash", "ip-" + visitor);
        row.put("browser", "Chrome"); row.put("os", "Windows"); row.put("device", "PC"); row.put("country", "CN");
        row.put("province", "广东省"); row.put("city", "广州市"); row.put("network", "电信"); row.put("geo_status", "RESOLVED"); row.put("geo_version", "fixture-v1");
        row.put("referer_domain", "example.com"); row.put("request_source", "REDIRECT"); row.put("decision_stage", "BUSINESS"); row.put("status", 302);
        row.put("validation_result", "VALID"); row.put("receipt_id", tenant + "-receipt-" + offset); return row;
    }
    private ClickHouseReader reader() {
        return new ClickHouseReader(settings, json) {
            @Override public List<Map<String, Object>> query(String replica, String sql, int limit) {
                try { return super.query(replica, sql, limit); }
                catch (QueryFailure failure) {
                    try { throw new AssertionError("Isolated fixture SQL failed: " + send(sql + " FORMAT JSONEachRow").body(), failure); }
                    catch (AssertionError error) { throw error; }
                    catch (Exception error) { throw failure; }
                }
            }
        };
    }
    private void insert(String table, List<Map<String, Object>> rows) throws Exception {
        StringBuilder body = new StringBuilder("INSERT INTO " + table + " FORMAT JSONEachRow\n");
        for (var row : rows) body.append(json.writeValueAsString(row)).append('\n');
        var result = send(body.toString()); assertEquals(200, result.statusCode(), result.body());
    }
    private HttpResponse<String> send(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(settings.clickHouseUrls() + "/?database=" + settings.clickHouseDatabase()))
                .timeout(Duration.ofSeconds(25)).header("X-ClickHouse-User", settings.clickHouseUser())
                .header("X-ClickHouse-Key", settings.clickHousePassword()).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static String env(String name, String fallback) { return Objects.requireNonNullElse(System.getenv(name), fallback); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
