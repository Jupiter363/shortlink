package com.jupiter.shortlink.analytics.api.job;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;
import com.jupiter.shortlink.contract.EventIdentity;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

class JobClickHouseIntegrationTest {
    @Test
    void actualFrozenBuildProofAndDailyAndRecordSqlDeduplicateReceipts() throws Exception {
        String id = UUID.randomUUID().toString();
        long start = Instant.parse("2026-09-01T16:00:00Z").toEpochMilli();
        var json = new ObjectMapper();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var row = new LinkedHashMap<String, Object>();
            row.put("build_id", id);
            row.put("window_start", start);
            row.put("kind", i < 2 ? "CLICK" : "REQUEST");
            row.put("tenant_id", id);
            row.put("link_id", 5000000001L);
            row.put("event_id", EventIdentity.bind(start + 1, id + (i < 2 ? "click" : "deny")));
            row.put("payload_hash", "a".repeat(64));
            row.put("occurred_at", start + 1);
            row.put("received_at", start + 1);
            row.put("visitor_hash", "visitor");
            row.put("ip_hash", "hash-only");
            row.put("request_source", "REDIRECT");
            row.put("decision_stage", "BUSINESS");
            row.put("status", 403);
            row.put("validation_result", "VALID");
            row.put("receipt_id", id + i);
            rows.add(row);
        }
        String body =
                rows.stream()
                                .map(
                                        row -> {
                                            try {
                                                return json.writeValueAsString(row);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        })
                                .collect(java.util.stream.Collectors.joining("\n"))
                        + "\n";
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:18123/?database=shortlink_analytics_it&query=INSERT%20INTO%20rebuild_input%20FORMAT%20JSONEachRow"))
                        .header("X-ClickHouse-User", "shortlink_it")
                        .header("X-ClickHouse-Key", "shortlink-it-only")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        assertEquals(
                200,
                HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode());
        var settings =
                new ApiSettings(
                        "analytics-job-it-token-1234567890",
                        "http://localhost:1",
                        "http://localhost:2",
                        "http://127.0.0.1:18123",
                        "shortlink_it",
                        "shortlink-it-only",
                        "shortlink_analytics_it");
        try (var stream = new JobClickHouseStream(settings, json)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            List<Map<String, Object>> proof = new ArrayList<>();
            stream.query(
                    settings.clickHouseUrls(),
                    "SELECT count()"
                        + " n,toString(groupBitXor(cityHash64(receipt_id,payload_hash,validation_result)))"
                        + " digest FROM (SELECT receipt_id,payload_hash,validation_result FROM"
                        + " rebuild_input WHERE build_id="
                            + ClickHouseReader.quote(id)
                            + " GROUP BY receipt_id,payload_hash,validation_result)",
                    1,
                    deadline,
                    proof::add);
            var plan =
                    new ManifestPlan(
                            id,
                            List.of(
                                    new ManifestPlan.Window(
                                            start,
                                            id,
                                            1,
                                            "{}",
                                            start + 300000,
                                            "click-v1",
                                            "detail-v1",
                                            json.writeValueAsString(proof.get(0)))),
                            List.of(settings.clickHouseUrls()));
            assertEquals(settings.clickHouseUrls(), stream.verify(plan, deadline));
            for (String kind : List.of("METRICS", "ACCESS_RECORDS")) {
                var q =
                        new QueryRequest(
                                id,
                                "alice",
                                1,
                                null,
                                List.of(5000000001L),
                                start,
                                start + 300000,
                                null,
                                "REQUESTED",
                                null,
                                null,
                                500,
                                kind);
                var lease = new QueryJobService.Lease(id, "worker", 1, q, id, "ownership", plan);
                List<Map<String, Object>> result = new ArrayList<>();
                stream.query(
                        settings.clickHouseUrls(),
                        QueryJobService.sql(lease),
                        10,
                        deadline,
                        result::add);
                if (kind.equals("METRICS")) {
                    assertEquals(3, result.size());
                    var detail = result.stream().filter(row -> "0".equals(row.get("group_row").toString())).findFirst().orElseThrow();
                    assertEquals("2026-09-02", detail.get("day"));
                    assertEquals("1", detail.get("pv").toString());
                    assertEquals("1", detail.get("denied").toString());
                } else {
                    assertEquals(1, result.size());
                    assertEquals(
                            EventIdentity.bind(start + 1, id + "click"),
                            result.get(0).get("eventId"));
                }
            }
        }
    }
}
