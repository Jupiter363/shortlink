package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class DimensionBreakdownTest {
    @Test
    void normalizesOnlySetOrderingAndPreservesExactStoredValues() {
        var options = DimensionBreakdown.options(request(List.of("province", "device"), List.of(
                new DimensionFilter("province", "IN", List.of("浙江", "广东省", "浙江")),
                new DimensionFilter("device", "IS_UNKNOWN", List.of()))));
        assertEquals(List.of("province", "device"), options.dimensions());
        assertEquals(List.of("device", "province"), options.filters().stream().map(DimensionFilter::dimension).toList());
        assertNull(options.filters().get(0).values());
        assertEquals(List.of("广东省", "浙江"), options.filters().get(1).values());
        assertFalse(options.filterMaps().get(0).containsKey("values"));
    }

    @Test
    void rejectsUnboundedOrAmbiguousDimensionQueries() {
        for (List<String> dimensions : List.of(List.<String>of(), List.of("province", "province"),
                List.of("province", "device", "os", "browser"), List.of("city"), List.of("country; SELECT 1")))
            assertThrows(QueryFailure.class, () -> DimensionBreakdown.options(request(dimensions, null)));
        for (DimensionFilter filter : List.of(new DimensionFilter("city", "IN", List.of("x")),
                new DimensionFilter("hour", "IN", List.of("24")), new DimensionFilter("weekday", "IN", List.of("0")),
                new DimensionFilter("day", "IN", List.of("2026-02-30")), new DimensionFilter("hour", "IN", List.of("01")),
                new DimensionFilter("country", "LIKE", List.of("C%")), new DimensionFilter("country", "IN", List.of()),
                new DimensionFilter("country", "IN", List.of("")), new DimensionFilter("device", "IN", List.of("line\nbreak")),
                new DimensionFilter("country", "IS_UNKNOWN", List.of("CN")),
                new DimensionFilter("country", "IN", Collections.nCopies(21, "CN")),
                new DimensionFilter("country", "IN", List.of("a".repeat(257)))))
            assertThrows(QueryFailure.class, () -> DimensionBreakdown.options(request(List.of("country"), List.of(filter))));
        var duplicate = new DimensionFilter("country", "IN", List.of("CN"));
        assertThrows(QueryFailure.class, () -> DimensionBreakdown.options(request(List.of("country"), List.of(duplicate, duplicate))));
        var nineFilters = DimensionBreakdown.DIMENSIONS.stream().limit(9)
                .map(d -> new DimensionFilter(d, "IS_UNKNOWN", null)).toList();
        assertThrows(QueryFailure.class, () -> DimensionBreakdown.options(request(List.of("country"), nineFilters)));
    }

    @Test
    void legacyWireShapeIsUnchangedAndOtherKindsCannotIgnoreDimensionFields() throws Exception {
        var legacy = new QueryRequest("tenant", "alice", 1, "g", List.of(1L), 0L, 100L,
                null, "REQUESTED", null, null, 100, "METRICS");
        var json = new ObjectMapper();
        String wire = json.writeValueAsString(legacy);
        assertFalse(wire.contains("dimensions")); assertFalse(wire.contains("filters"));
        assertEquals(legacy, json.readValue(wire, QueryRequest.class));
        var invalid = new QueryRequest("tenant", "alice", 1, "g", List.of(1L), 0L, 100L,
                null, "REQUESTED", null, null, 100, "METRICS", null, List.of());
        assertThrows(QueryFailure.class, () -> DimensionBreakdown.validateRequest(invalid));
    }

    @Test
    void usesClickFactsExactEscapedValuesAndExplicitUnknownPredicates() {
        var options = DimensionBreakdown.options(request(List.of("province", "isp", "hour"), List.of(
                new DimensionFilter("browser", "IN", List.of("x'\\ OR 1=1")),
                new DimensionFilter("refererDomain", "IS_UNKNOWN", null))));
        String sql = DimensionBreakdown.sql("AUTHORIZED_DEDUPLICATED_FACTS", 1, 2, options);
        assertTrue(sql.contains("FROM (AUTHORIZED_DEDUPLICATED_FACTS) WHERE kind='CLICK' AND occurred_at>=1 AND occurred_at<2"));
        assertTrue(sql.contains("browser IN ('x\\'\\\\ OR 1=1')"));
        assertTrue(sql.contains("if(referer_domain='','UNKNOWN','KNOWN')='UNKNOWN'"));
        assertTrue(sql.contains("country NOT IN ('CN','中国','China')"));
        assertTrue(sql.contains("'NOT_APPLICABLE'"));
        assertTrue(sql.contains("network")); assertTrue(sql.contains("Asia/Shanghai"));
        assertTrue(sql.endsWith("LIMIT 5002"));
    }

    @Test
    void weightedCoverageAndRatiosUseAllFilteredClicksWithoutAddingBucketUv() {
        var acc = accumulator(List.of("province", "device"));
        acc.add(summary(13, 2, 2, 0, 1));
        acc.add(bucket(8, "KNOWN", "广东省", "KNOWN", "Mobile"));
        acc.add(bucket(2, "KNOWN", "广东省", "KNOWN", "PC"));
        acc.add(bucket(2, "UNKNOWN", "", "UNKNOWN", ""));
        acc.add(bucket(1, "NOT_APPLICABLE", "", "KNOWN", "PC"));
        var result = acc.finish();
        assertEquals(2L, result.summary().get("uv"));
        assertEquals(13L, result.summary().get("ratioDenominator"));
        assertEquals(8.0 / 13, result.items().get(0).get("pvRatio"));
        var province = map(map(result.summary().get("dimensionQuality")).get("province"));
        assertEquals("PARTIAL", province.get("status"));
        assertEquals(10L, province.get("knownCount"));
        assertEquals(2L, province.get("unknownCount"));
        assertEquals(12L, province.get("eligibleCount"));
        assertEquals(1L, province.get("notApplicableCount"));
        assertEquals(10.0 / 12, province.get("coverage"));
        assertEquals(Map.of("COUNTRY_UNKNOWN", 1L, "UNKNOWN_VALUE", 1L), province.get("reasonCounts"));
        var unknown = result.items().stream().filter(row -> "UNKNOWN".equals(map(map(row.get("dimensions")).get("province")).get("state"))).findFirst().orElseThrow();
        assertNull(map(map(unknown.get("dimensions")).get("province")).get("value"));
    }

    @Test
    void retainsGeoConflictAndEmptyResultsWithoutInventingZeroBuckets() {
        var conflict = accumulator(List.of("province"));
        conflict.add(summary(1, 1, 1, 1, 0)); conflict.add(bucket(1, "KNOWN", "浙江"));
        var quality = map(map(conflict.finish().summary().get("dimensionQuality")).get("province"));
        assertEquals("PARTIAL", quality.get("status"));
        assertEquals(1L, quality.get("versionConflictCount"));
        var empty = accumulator(List.of("province"));
        empty.add(summary(0, 0, 0, 0, 0));
        assertTrue(empty.finish().items().isEmpty());
        assertEquals("EMPTY", map(map(empty.finish().summary().get("dimensionQuality")).get("province")).get("status"));
        var unknown = accumulator(List.of("province"));
        unknown.add(summary(1, 1, 1, 0, 1)); unknown.add(bucket(1, "UNKNOWN", ""));
        assertEquals("UNKNOWN", map(map(unknown.finish().summary().get("dimensionQuality")).get("province")).get("status"));
    }

    @Test
    void requiresCompleteUniqueBoundedBucketsAndSortsDeterministically() {
        var acc = accumulator(List.of("refererDomain"));
        acc.add(summary(5000, 1, 1, 0, 0));
        for (int i = 4999; i >= 0; i--) acc.add(bucket(1, "KNOWN", String.format(Locale.ROOT, "%04d.example", i)));
        assertEquals(5000, acc.finish().items().size());
        assertEquals("0000.example", map(map(acc.finish().items().get(0).get("dimensions")).get("refererDomain")).get("value"));
        assertEquals("TOO_LARGE", assertThrows(QueryFailure.class, () -> acc.add(bucket(1, "KNOWN", "5000.example"))).code);
        var duplicate = accumulator(List.of("device"));
        duplicate.add(bucket(1, "KNOWN", "PC"));
        assertThrows(QueryFailure.class, () -> duplicate.add(bucket(1, "KNOWN", "PC")));
        var truncated = accumulator(List.of("device"));
        truncated.add(summary(2, 1, 1, 0, 0)); truncated.add(bucket(1, "KNOWN", "PC"));
        assertThrows(QueryFailure.class, truncated::finish);
    }

    private static DimensionBreakdown.Accumulator accumulator(List<String> dimensions) {
        return new DimensionBreakdown.Accumulator(DimensionBreakdown.options(request(dimensions, null)), 0, 100);
    }
    private static QueryRequest request(List<String> dimensions, List<DimensionFilter> filters) {
        return new QueryRequest("tenant", "alice", 1, "g", List.of(1L), 0L, 100L,
                null, "REQUESTED", null, null, 500, DimensionBreakdown.KIND, dimensions, filters);
    }
    private static Map<String, Object> summary(long pv, long uv, long uip, long conflicts, long countryUnknown) {
        return Map.of("group_row", 1, "pv", pv, "uv", uv, "uip", uip, "geo_conflicts", conflicts, "country_unknown", countryUnknown);
    }
    private static Map<String, Object> bucket(long pv, String... key) {
        return Map.of("group_row", 0, "bucket_key", List.of(key), "pv", pv, "uv", 1, "uip", 1);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
