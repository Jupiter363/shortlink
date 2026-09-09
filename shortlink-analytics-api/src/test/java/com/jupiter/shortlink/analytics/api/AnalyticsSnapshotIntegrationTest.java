package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.EventIdentity;
import com.jupiter.shortlink.contract.TimeValidation;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class AnalyticsSnapshotIntegrationTest {
    @Test
    void conflictDetectionPrecedesLinkScopeButPreservesTenantIsolation() throws Exception {
        String tenant = "conflict-it-" + UUID.randomUUID(),
                epoch = UUID.randomUUID().toString(),
                shared = "shared-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        var json = new ObjectMapper();
        var links = new AtomicReference<List<Long>>(List.of(9101L, 9102L));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body =
                            json.writeValueAsBytes(
                                    exchange.getRequestURI().getPath().endsWith("readiness")
                                            ? Map.of("ready", true, "recoveryEpoch", epoch)
                                            : Map.of(
                                                    "allowed",
                                                    true,
                                                    "tenantId",
                                                    tenant,
                                                    "linkIds",
                                                    links.get(),
                                                    "ownershipVersion",
                                                    "scope-" + links.get()));
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        try {
            insertConflict(json, tenant, 9101, now - 1000, tenant + "collision", 1, "a");
            insertConflict(json, tenant, 9102, now - 1000, tenant + "collision", 2, "b");
            insertConflict(json, tenant, 9101, now - 1000, shared, 3, "a");
            insertConflict(json, tenant + "foreign", 9101, now - 1000, shared, 4, "b");
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var settings =
                    new ApiSettings(
                            "analytics-conflict-it-token-123456",
                            url,
                            url,
                            "http://127.0.0.1:18123",
                            "shortlink_it",
                            "shortlink-it-only",
                            "shortlink_analytics_it");
            var db =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    "jdbc:mysql://127.0.0.1:13306/shortlink_analytics_control_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                                    "root",
                                    "shortlink-it-only"));
            var service =
                    new AnalyticsQueryService(
                            db,
                            json,
                            new AuthorizationClient(settings, json),
                            new ClickHouseReader(settings, json),
                            settings);
            for (List<Long> scope :
                    List.of(List.of(9101L, 9102L), List.of(9101L), List.of(9102L))) {
                links.set(scope);
                var result =
                        service.query(
                                new QueryRequest(
                                        tenant,
                                        "alice",
                                        1,
                                        null,
                                        scope,
                                        now - 60000,
                                        now,
                                        null,
                                        "REQUESTED",
                                        null,
                                        null,
                                        100,
                                        "METRICS"));
                var metrics = (Map<?, ?>) ((Map<?, ?>) result.get("metrics")).get("requested");
                assertEquals(
                        scope.contains(9101L) ? 1L : 0L,
                        ((Number) metrics.get("pv")).longValue(),
                        "The conflicting event is excluded for any scope; another tenant cannot"
                                + " invalidate a valid event");
            }
        } finally {
            server.stop(0);
        }
    }

    private void insertConflict(
            ObjectMapper json,
            String tenant,
            long link,
            long time,
            String event,
            long offset,
            String hash)
            throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "CLICK");
        row.put("cluster_id", tenant);
        row.put("topic_id", "conflict-topic");
        row.put("source_topic", "shortlink.click.raw.v1");
        row.put("source_partition", 0);
        row.put("source_offset", offset);
        row.put("received_at", time);
        row.put("timestamp_type", "LogAppendTime");
        row.put("event_id", EventIdentity.bind(time, event));
        row.put("payload_hash", hash.repeat(64));
        row.put("tenant_id", tenant);
        row.put("link_id", link);
        row.put("occurred_at", time);
        row.put("visitor_hash", "visitor");
        row.put("ip_hash", "hash-only");
        row.put("validation_result", "VALID");
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:18123/?database=shortlink_analytics_it&query=INSERT%20INTO%20event_receipts%20FORMAT%20JSONEachRow"))
                        .header("X-ClickHouse-User", "shortlink_it")
                        .header("X-ClickHouse-Key", "shortlink-it-only")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(row) + "\n"))
                        .build();
        assertEquals(
                200,
                HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode());
    }

    @Test
    void realClickHouseSnapshotPreservesPagesAndReauthorizesAfterMove() throws Exception {
        String tenant = "api-it-" + UUID.randomUUID(), epoch = UUID.randomUUID().toString();
        long now = System.currentTimeMillis(), start = now - 120000;
        AtomicReference<String> ownership = new AtomicReference<>("scope-v1");
        ObjectMapper json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] result =
                            json.writeValueAsBytes(
                                    exchange.getRequestURI().getPath().endsWith("readiness")
                                            ? Map.of("ready", true, "recoveryEpoch", epoch)
                                            : Map.of(
                                                    "allowed",
                                                    true,
                                                    "tenantId",
                                                    tenant,
                                                    "linkIds",
                                                    List.of(9001, 9002),
                                                    "ownershipVersion",
                                                    ownership.get()));
                    exchange.sendResponseHeaders(200, result.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(result);
                    }
                });
        server.start();
        try {
            String authority = "http://127.0.0.1:" + server.getAddress().getPort();
            var settings =
                    new ApiSettings(
                            "analytics-it-internal-token-123456",
                            authority,
                            authority,
                            "http://127.0.0.1:18123",
                            "shortlink_it",
                            "shortlink-it-only",
                            "shortlink_analytics_it");
            var db =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    "jdbc:mysql://127.0.0.1:13306/shortlink_analytics_control_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                                    "root",
                                    "shortlink-it-only"));
            insert(json, tenant, 9001, now - 1000, "e1");
            insert(json, tenant, 9002, now - 1000, "e2");
            var service =
                    new AnalyticsQueryService(
                            db,
                            json,
                            new AuthorizationClient(settings, json),
                            new ClickHouseReader(settings, json),
                            settings);
            var q =
                    new QueryRequest(
                            tenant,
                            "alice",
                            1,
                            null,
                            List.of(9001L, 9002L),
                            start,
                            now,
                            null,
                            "REQUESTED",
                            null,
                            null,
                            1,
                            "METRICS");
            var first = service.query(q);
            var meta = (Map<?, ?>) first.get("meta");
            String id = meta.get("snapshotId").toString(),
                    cursor = meta.get("nextCursor").toString();
            assertEquals("PARTIAL", meta.get("completeness"));
            assertEquals("UNKNOWN", ((Map<?, ?>) meta.get("collectionQuality")).get("status"));
            var total = (Map<?, ?>) ((Map<?, ?>) first.get("metrics")).get("requested");
            assertEquals(
                    1L,
                    ((Number) total.get("uv")).longValue(),
                    "Group UV deduplicates the shared domain visitor across links");
            assertEquals(
                    2L,
                    ((List<?>) total.get("hourStats"))
                            .stream().mapToLong(v -> ((Number) v).longValue()).sum());
            assertEquals(1D, total.get("topBrowserShare"));
            insert(json, tenant, 9002, now - 1000, "e3");
            var page =
                    new QueryRequest(
                            tenant,
                            "alice",
                            1,
                            null,
                            List.of(9001L, 9002L),
                            start,
                            now,
                            null,
                            "REQUESTED",
                            id,
                            cursor,
                            1,
                            "METRICS");
            var second = service.query(page);
            var item = (Map<?, ?>) ((List<?>) second.get("items")).get(0);
            assertEquals(
                    1L,
                    ((Number) item.get("pv")).longValue(),
                    "Later inserts cannot change a previously materialized page");
            assertEquals(
                    meta.get("snapshotCreatedAt"),
                    ((Map<?, ?>) second.get("meta")).get("snapshotCreatedAt"));
            ownership.set("scope-v2");
            assertEquals(
                    "QUERY_SCOPE_CHANGED",
                    assertThrows(QueryFailure.class, () -> service.query(page)).code);
            ownership.set("scope-v1");
            var multi =
                    service.query(
                            new QueryRequest(
                                    tenant,
                                    "alice",
                                    1,
                                    null,
                                    List.of(9001L, 9002L),
                                    now - 7L * 86400000,
                                    now,
                                    List.of("2h", "24h", "7d"),
                                    "REQUESTED",
                                    null,
                                    null,
                                    100,
                                    "METRICS"));
            assertEquals(6, ((List<?>) multi.get("items")).size());
            var records =
                    service.query(
                            new QueryRequest(
                                    tenant,
                                    "alice",
                                    1,
                                    null,
                                    List.of(9001L, 9002L),
                                    start,
                                    now,
                                    null,
                                    "REQUESTED",
                                    null,
                                    null,
                                    100,
                                    "ACCESS_RECORDS"));
            assertEquals(3, ((List<?>) records.get("items")).size());
            var record = (Map<?, ?>) ((List<?>) records.get("items")).get(0);
            assertTrue(record.containsKey("ipHash"));
            assertEquals("example.org", record.get("refererDomain"));
            assertFalse(record.containsKey("clientIp"));
        } finally {
            server.stop(0);
        }
    }

    private void insert(ObjectMapper json, String tenant, long link, long time, String id)
            throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "CLICK");
        row.put("cluster_id", tenant);
        row.put("topic_id", "api-topic");
        row.put("source_topic", "shortlink.click.raw.v1");
        row.put("source_partition", 0);
        row.put("source_offset", id.equals("e1") ? 1 : id.equals("e2") ? 2 : 3);
        row.put("received_at", time);
        row.put("timestamp_type", "LogAppendTime");
        row.put("event_id", EventIdentity.bind(time, tenant + id));
        row.put("payload_hash", "a".repeat(64));
        row.put("tenant_id", tenant);
        row.put("link_id", link);
        row.put("occurred_at", time);
        row.put("visitor_hash", id);
        row.put("ip_hash", id);
        row.put("validation_result", "VALID");
        row.put("validation_version", TimeValidation.VERSION);
        row.put("dataset_version", "detail-v1");
        row.put("parser_version", "builtin-ua-v1");
        row.put("hash_version", "hmac-sha256-128-v1");
        row.put("visitor_hash", tenant + "shared-domain-visitor");
        row.put("browser", "Chrome");
        row.put("os", "Windows");
        row.put("device", "Desktop");
        row.put("referer_domain", "example.org");
        var req =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:18123/?database=shortlink_analytics_it&query="
                                                + URLEncoder.encode(
                                                        "INSERT INTO event_receipts FORMAT"
                                                                + " JSONEachRow",
                                                        StandardCharsets.UTF_8)))
                        .header("X-ClickHouse-User", "shortlink_it")
                        .header("X-ClickHouse-Key", "shortlink-it-only")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(row) + "\n"))
                        .build();
        assertEquals(
                200,
                HttpClient.newHttpClient()
                        .send(req, HttpResponse.BodyHandlers.ofString())
                        .statusCode());
    }
}
