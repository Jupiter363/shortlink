package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.SourceCut;
import com.jupiter.shortlink.contract.Topics;
import org.junit.jupiter.api.Test;
import java.util.*;

class AnalyticsDimensionCoverageTest {
    @Test
    void countriesProvincesAndIspExposeKnownCoverageInsteadOfInventingUnknownCategories() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("pv", 10L);
        raw.put("country_raw", histogram(Map.of("CN", 6L, "US", 1L)));
        raw.put("province_raw", histogram(Map.of("广东省", 5L)));
        raw.put("network_raw", histogram(Map.of("电信", 6L)));
        raw.put("country_unknown_raw", histogram(Map.of("NON_PUBLIC", 2L, "NOT_CONFIGURED", 1L)));
        raw.put("province_unknown_raw", histogram(Map.of("FIELD_UNAVAILABLE", 1L)));
        raw.put("cn_pv", 6L);
        var report = MetricDimensions.materializeGeography(raw);
        assertEquals(.5D, report.get("topRegionShare"));
        var qualities = map(report.get("dimensionQuality"));
        assertEquals(3L, map(qualities.get("country")).get("unknownCount"));
        assertEquals(4L, map(qualities.get("localeCnStats")).get("unknownCount"));
        assertEquals("ISP", map(qualities.get("networkStats")).get("semantic"));
        assertEquals("PARTIAL", map(qualities.get("networkStats")).get("status"));
        assertEquals(List.of("country", "localeCnStats", "topRegionShare", "networkStats", "uvTypeStats"),
                MetricDimensions.missingMetrics(qualities));
        assertTrue(report.get("localeCnStats").toString().contains("广东省"));
        assertFalse(report.get("networkStats").toString().contains("WIFI"));
    }

    @Test
    void populatedDimensionsRemoveOnlyActuallyCoveredMissingMetrics() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("pv", 8L); raw.put("uv", 4L); raw.put("cn_pv", 8L);
        raw.put("country_raw", histogram(Map.of("CN", 8L)));
        raw.put("province_raw", histogram(Map.of("广东省", 8L)));
        raw.put("network_raw", histogram(Map.of("移动", 8L)));
        raw.put("scope_new_uv", 1L); raw.put("scope_old_uv", 3L);
        var report = MetricDimensions.materializeGeography(raw);
        VisitorHistory.materialize(report, raw, new VisitorHistory.Plan(true, "bounded", 100, 900, ""), true);
        assertEquals(List.of(), MetricDimensions.missingMetrics(map(report.get("dimensionQuality"))));
        var uv = map(map(report.get("dimensionQuality")).get("uvTypeStats"));
        assertEquals(VisitorHistory.SEMANTIC, uv.get("semantic"));
        assertEquals(100L, uv.get("historyStart"));
        assertEquals(1L, uv.get("newUv"));
        assertEquals(3L, uv.get("oldUv"));
    }

    @Test
    void geoVersionConflictCannotProduceRiskRegionConcentration() {
        var raw = Map.<String, Object>of("pv", 2L, "cn_pv", 2L,
                "country_raw", histogram(Map.of("CN", 2L)), "province_raw", histogram(Map.of("北京", 2L)),
                "geo_conflict_pv", 1L);
        var result = MetricDimensions.materializeGeography(raw);
        assertNull(result.get("topRegionShare"));
        var quality = map(map(result.get("dimensionQuality")).get("topRegionShare"));
        assertEquals("PARTIAL", quality.get("status"));
        assertEquals("GEO_VERSION_CONFLICT", quality.get("reason"));
    }

    @Test
    void historyUsesFrozenUpperOffsetsAndRejectsUncoveredEarlierSegments() {
        var json = new ObjectMapper();
        var earlier = new SourceCut(List.of(new SourceCut.Range("cluster", "topicId", Topics.CLICK_RAW, 0, 0, 7)));
        var later = new SourceCut(List.of(new SourceCut.Range("cluster", "topicId", Topics.CLICK_RAW, 0, 0, 9)));
        var plan = VisitorHistory.plan(Map.of("1", earlier, "2", later), json, 999, 900);
        assertTrue(plan.available());
        assertTrue(plan.predicate().contains("source_offset<9"));
        assertFalse(plan.predicate().contains("source_offset<7"));
        var partial = VisitorHistory.plan(new SourceCut(List.of(new SourceCut.Range("c", "t", Topics.CLICK_RAW, 0, 4, 9))), json, 999, 900);
        assertFalse(partial.available());
        var target = new LinkedHashMap<String, Object>();
        VisitorHistory.materialize(target, Map.of("uv", 6L), partial, true);
        assertEquals(List.of(), target.get("uvTypeStats"));
        assertEquals(6L, map(map(target.get("dimensionQuality")).get("uvTypeStats")).get("unknownUv"));
    }

    @Test
    void missingEarlierReceiptCannotAuthorizeFirstSeenAndTrustedCountsAllowLegitimateOffsetGaps() {
        var json = new ObjectMapper();
        var cut = new SourceCut(List.of(new SourceCut.Range("cluster", "topic", Topics.CLICK_RAW, 0, 0, 3)));
        var candidate = VisitorHistory.plan(cut, json, 1000, 900);
        var visible = List.of(Map.<String, Object>of("cluster_id", "cluster", "topic_id", "topic", "source_partition", 0, "receipts", 2));
        assertFalse(VisitorHistory.verifyVisibility(candidate, visible).available());
        var proved = VisitorHistory.verifyVisibility(candidate, visible, cut,
                List.of(Map.of("clusterId", "cluster", "topicId", "topic", "partition", 0, "count", 2)), json, true);
        assertTrue(proved.available()); assertEquals("WORKER_RECEIPT_COUNTS", proved.proof());
        assertFalse(VisitorHistory.verifyVisibility(candidate, visible, cut,
                List.of(Map.of("clusterId", "cluster", "topicId", "topic", "partition", 0, "count", 2)), json, false).available());
        var otherCut = new SourceCut(List.of(new SourceCut.Range("cluster", "topic", Topics.CLICK_RAW, 0, 0, 4)));
        assertFalse(VisitorHistory.verifyVisibility(candidate, visible, otherCut,
                List.of(Map.of("clusterId", "cluster", "topicId", "topic", "partition", 0, "count", 2)), json, true).available());
    }

    @Test
    void laggingOrAdvancedRequestPartitionsNeverGateACompleteClickHistory() {
        var json = new ObjectMapper();
        var click = new SourceCut.Range("c", "click-id", Topics.CLICK_RAW, 0, 0, 2);
        var snapshot = new SourceCut(List.of(click, new SourceCut.Range("c", "request-id", Topics.GATEWAY_REQUEST, 0, 10, 100)));
        var candidate = VisitorHistory.plan(snapshot, json, 1000, 900);
        assertTrue(candidate.available()); assertEquals(List.of(click), candidate.ranges());
        assertFalse(candidate.predicate().contains("request-id"));
        assertTrue(candidate.predicate().contains("source_topic='" + Topics.CLICK_RAW + "'"));
        var visible = List.of(Map.<String, Object>of("cluster_id", "c", "topic_id", "click-id", "source_partition", 0, "receipts", 2));
        assertTrue(VisitorHistory.verifyVisibility(candidate, visible).available());
        var advanced = new SourceCut(List.of(click, new SourceCut.Range("c", "request-id", Topics.GATEWAY_REQUEST, 0, 20, 900)));
        var proved = VisitorHistory.verifyVisibility(candidate, visible, advanced, List.of(
                Map.of("clusterId", "c", "topicId", "click-id", "partition", 0, "count", 2),
                Map.of("clusterId", "c", "topicId", "request-id", "partition", 0, "count", "not-used")), json, true);
        assertTrue(proved.available()); assertEquals("WORKER_RECEIPT_COUNTS", proved.proof());
    }

    @Test
    void missingClickRosterOrMissingClickPartitionCannotBorrowRequestCompleteness() {
        var json = new ObjectMapper();
        var request = new SourceCut.Range("c", "request", Topics.GATEWAY_REQUEST, 0, 0, 100);
        assertFalse(VisitorHistory.plan(new SourceCut(List.of(request)), json, 1000, 900).available());
        var click = new SourceCut.Range("c", "click", Topics.CLICK_RAW, 0, 0, 2);
        var cut = new SourceCut(List.of(click, request));
        var candidate = VisitorHistory.plan(cut, json, 1000, 900);
        var visible = List.of(Map.<String, Object>of("cluster_id", "c", "topic_id", "click", "source_partition", 0, "receipts", 1),
                Map.<String, Object>of("cluster_id", "c", "topic_id", "request", "source_partition", 0, "receipts", 100));
        assertFalse(VisitorHistory.verifyVisibility(candidate, visible).available());
        assertFalse(VisitorHistory.verifyVisibility(candidate, visible, cut, List.of(
                Map.of("clusterId", "c", "topicId", "click", "partition", 0, "count", 2),
                Map.of("clusterId", "c", "topicId", "request", "partition", 0, "count", 100)), json, true).available());
    }

    @Test
    void canonicalVisitorMissingFromRetainedReceiptsRemainsUnknownAndNotAllNew() {
        var target = new LinkedHashMap<String, Object>();
        VisitorHistory.materialize(target, Map.of("uv", 3L, "scope_unknown_uv", 3L, "missing_visitor_clicks", 1L),
                new VisitorHistory.Plan(true, "bounded", 100, 1000, ""), true);
        assertEquals(List.of(), target.get("uvTypeStats"));
        var quality = map(map(target.get("dimensionQuality")).get("uvTypeStats"));
        assertEquals(3L, quality.get("unknownUv"));
        assertEquals(1L, quality.get("missingVisitorClicks"));
        assertEquals("UNKNOWN", quality.get("status"));
    }

    @Test
    void sharedVisitorAcrossLinksUsesScopeFirstObservationAndRecordSummaryDoesNotDoubleCount() {
        var summary = new RecordDimensionSummary(100);
        for (long link : List.of(1L, 2L)) summary.add(Map.of("linkId", link, "visitorHash", "same", "uvType", "oldUser",
                "country", "CN", "province", "广东省", "network", "电信", "geoStatus", "RESOLVED", "geoVersion", "pinned-v1"));
        var report = summary.materialize(new VisitorHistory.Plan(true, "bounded", 1, 1000, ""));
        var quality = map(map(report.get("dimensionQuality")).get("uvTypeStats"));
        assertEquals(1L, quality.get("oldUv"));
        assertEquals(0L, quality.get("newUv"));
        assertEquals(List.of(), MetricDimensions.missingMetrics(map(report.get("dimensionQuality"))));
    }

    @Test
    void oldProofRemainsSupportedButNewProofRejectsDimensionMutationOrUnknownVersion() {
        assertFalse(DimensionProof.required(Map.of("n", 1, "digest", "x")));
        var expected = Map.<String, Object>of("n", 1, "dimensionVersion", "geo-v1", "dimensionDigest", "frozen");
        assertDoesNotThrow(() -> DimensionProof.verify(expected, Map.of("n", "1", "dimensionDigest", "frozen")));
        assertThrows(QueryFailure.class, () -> DimensionProof.verify(expected, Map.of("n", 1, "dimensionDigest", "changed")));
        assertThrows(QueryFailure.class, () -> DimensionProof.verify(expected, Map.of("n", 2, "dimensionDigest", "frozen")));
        assertThrows(QueryFailure.class, () -> DimensionProof.verify(Map.of("n", 1, "dimensionVersion", "geo-v2", "dimensionDigest", "frozen"),
                Map.of("n", 1, "dimensionDigest", "frozen")));
    }

    private static Object histogram(Map<String, Long> counts) { return List.of(new ArrayList<>(counts.keySet()), new ArrayList<>(counts.values())); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
