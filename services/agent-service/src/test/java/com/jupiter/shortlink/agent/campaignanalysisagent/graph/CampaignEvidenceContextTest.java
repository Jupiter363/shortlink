package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignEvidenceContextTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WINDOWS = 2016;

    @Test
    void largeProvenanceInTopLevelAndRepeatedRankingQualityBecomesBoundedAuditEvidence() throws Exception {
        Map<String, Object> cuts = sourceCuts(WINDOWS);
        Map<String, Object> versions = versions(WINDOWS);
        Map<String, Object> meta = metadata(cuts, versions);
        var rows = List.of(
                Map.of("linkId", "alpha", "pv", 13L, "quality", meta),
                Map.of("linkId", "beta", "pv", 7L, "quality", meta));
        var input = Map.of("type", "ranking", "rows", rows, "meta", meta,
                "metrics", Map.of("pv", 20L, "uv", 17L, "uip", 4L));
        byte[] before = JSON.writeValueAsBytes(input);

        Map<?, ?> result = map(CampaignEvidenceContext.compact(input));

        assertThat(before.length).isGreaterThan(1_000_000);
        assertThat(JSON.writeValueAsBytes(result).length).isLessThan(100_000);
        assertProvenanceSummaries(map(result.get("meta")), cuts, versions);
        for (Object row : (List<?>) result.get("rows")) {
            assertProvenanceSummaries(map(map(row).get("quality")), cuts, versions);
        }
        assertThat(result.get("metrics")).isEqualTo(input.get("metrics"));
        assertThat(JSON.writeValueAsBytes(input)).containsExactly(before);
        assertThat(cuts).hasSize(WINDOWS).doesNotContainKey("compacted");
        assertThat(meta.get("sourceCut")).isSameAs(cuts);
    }

    @Test
    void summaryDigestIsDeterministicAndChangesWhenSourceOffsetsChange() throws Exception {
        Map<String, Object> cuts = sourceCuts(WINDOWS);
        Map<?, ?> first = summary(CampaignEvidenceContext.compact(Map.of("meta", Map.of("sourceCut", cuts))), "sourceCut");
        Map<?, ?> repeated = summary(CampaignEvidenceContext.compact(Map.of("meta", Map.of("sourceCut", sourceCuts(WINDOWS)))), "sourceCut");
        byte[] original = JSON.writeValueAsBytes(cuts);

        assertThat(first.get("sha256")).isEqualTo(repeated.get("sha256"));
        assertThat(first.get("sha256")).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        assertThat(((Number) first.get("originalJsonBytes")).longValue()).isEqualTo(original.length);
        cuts.put("1757635200", Map.of("endOffset", 999999L));

        Map<?, ?> changed = summary(CampaignEvidenceContext.compact(Map.of("meta", Map.of("sourceCut", cuts))), "sourceCut");
        assertThat(changed.get("sha256")).isNotEqualTo(first.get("sha256"));
        assertThat(changed.get("entryCount")).isEqualTo(first.get("entryCount"));
    }

    @Test
    void allFiveThousandBusinessDimensionRowsAndQualityRemainAvailable() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            rows.add(Map.of("province", "province-" + i, "device", i % 2 == 0 ? "MOBILE" : "UNKNOWN",
                    "pv", (long) i + 1, "uv", 1L, "uip", 1L));
        }
        var metrics = Map.of("requested", Map.of("pv", 12_502_500L, "uv", 123L, "uip", 7L));
        var meta = metadata(sourceCuts(WINDOWS), versions(WINDOWS));
        meta.put("dimensions", List.of("province", "device"));
        meta.put("filters", Map.of("operator", List.of("NOT_APPLICABLE")));
        meta.put("resultComplete", true);
        var input = Map.of("type", "dimension_breakdown", "rows", rows, "meta", meta, "metrics", metrics);

        Map<?, ?> result = map(CampaignEvidenceContext.compact(input));

        assertThat((List<?>) result.get("rows")).hasSize(5000).isEqualTo(rows);
        assertThat(result.get("metrics")).isEqualTo(metrics);
        assertThat(map(result.get("meta")).get("dimensions")).isEqualTo(meta.get("dimensions"));
        assertThat(map(result.get("meta")).get("filters")).isEqualTo(meta.get("filters"));
        assertThat(map(result.get("meta")).get("dimensionQuality")).isEqualTo(meta.get("dimensionQuality"));
        assertThat(map(result.get("meta")).get("approximation")).isEqualTo(meta.get("approximation"));
    }

    @Test
    void businessSourceCutAndUnknownMetadataExtensionsAreNeverRewritten() {
        Map<String, Object> businessValues = sourceCuts(WINDOWS);
        var extension = Map.of("sourceCut", businessValues, "manifestVersion", versions(WINDOWS));
        var row = Map.of("sourceCut", businessValues, "pv", 9L);
        var input = Map.of("type", "ranking", "sourceCut", businessValues, "rows", List.of(row),
                "meta", Map.of("customAudit", extension, "unexpectedLargeMap", businessValues));

        Map<?, ?> result = map(CampaignEvidenceContext.compact(input));

        assertThat(result).isEqualTo(input);
        assertThat(result.get("sourceCut")).isSameAs(businessValues);
        assertThat(map(result.get("meta")).get("customAudit")).isEqualTo(extension);
        assertThat(map(result.get("meta")).get("unexpectedLargeMap")).isEqualTo(businessValues);
    }

    @Test
    void smallAuditHashMapsAndScalarVersionsKeepTheirOriginalContract() {
        var meta = Map.of("sourceCut", Map.of("manifestSelectionHash", "sha256-selection"),
                "manifestVersion", "click-v1", "windowVersions", Map.of("window-1", "revision-2"));
        var input = Map.of("meta", meta);

        Map<?, ?> result = map(CampaignEvidenceContext.compact(input));

        assertThat(result).isEqualTo(input);
        assertThat(map(result.get("meta")).get("sourceCut")).isEqualTo(meta.get("sourceCut"));
        assertThat(map(result.get("meta")).get("manifestVersion")).isEqualTo("click-v1");
    }

    @Test
    void compactionIsIdempotentAndDoesNotReplaceAnExistingAuditSummary() throws Exception {
        var input = Map.of("type", "comparison", "rows", List.of(
                Map.of("gid", "alpha", "quality", metadata(sourceCuts(WINDOWS), versions(WINDOWS)))));

        Object first = CampaignEvidenceContext.compact(input);
        Object second = CampaignEvidenceContext.compact(first);

        assertThat(second).isEqualTo(first);
        assertThat(JSON.writeValueAsBytes(second)).containsExactly(JSON.writeValueAsBytes(first));
        Map<?, ?> quality = map(map(((List<?>) map(second).get("rows")).get(0)).get("quality"));
        assertThat(map(quality.get("sourceCut")).get("entryCount")).isEqualTo(WINDOWS);
    }

    @Test
    void ordinaryStatisticsWithoutATypeCanCompactProvenanceAndRetainSnapshotContinuation() {
        var meta = metadata(sourceCuts(WINDOWS), versions(WINDOWS));
        var continuation = Map.of("nextCursor", "cursor-2", "snapshotId", "snapshot-1", "nextPageIndex", 2);
        var input = Map.of("meta", meta, "items", List.of(Map.of("date", "2026-09-12", "pv", 13L)),
                "nextCursor", "cursor-2", "continuation", continuation);

        Map<?, ?> result = map(CampaignEvidenceContext.compact(input));

        assertThat(map(map(result.get("meta")).get("sourceCut")).get("compacted")).isEqualTo(true);
        assertThat(result.get("items")).isEqualTo(input.get("items"));
        assertThat(result.get("continuation")).isEqualTo(continuation);
        assertThat(result.get("nextCursor")).isEqualTo("cursor-2");
        for (String key : List.of("snapshotId", "jobId", "recoveryEpoch", "requestedStart", "requestedEnd",
                "effectiveEnd", "availability", "completeness", "freshness", "collectionQuality")) {
            assertThat(map(result.get("meta")).get(key)).as(key).isEqualTo(meta.get(key));
        }
    }

    @Test
    void missingQualityAndNullQualityAreNotInventedOrPromotedToComplete() {
        var missing = Map.of("linkId", "alpha", "pv", 3L);
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("linkId", "beta");
        explicitNull.put("quality", null);
        var input = Map.of("type", "ranking", "rows", List.of(missing, explicitNull));

        Map<?, ?> result = map(CampaignEvidenceContext.compact(input));

        assertThat(result).isEqualTo(input);
        assertThat(result.containsKey("meta")).isFalse();
        List<?> rows = (List<?>) result.get("rows");
        assertThat(map(rows.get(0)).containsKey("quality")).isFalse();
        assertThat(map(rows.get(1)).containsKey("quality")).isTrue();
        assertThat(map(rows.get(1)).get("quality")).isNull();
    }

    @Test
    void utf8ByteBudgetCompactsALargeSingleEntryMapWithoutTruncatingItsAuditReference() throws Exception {
        var provenance = Map.of("window-1", "来源".repeat(1000));
        var input = Map.of("meta", Map.of("sourceCut", provenance, "snapshotId", "快照-1"));

        Map<?, ?> summary = summary(CampaignEvidenceContext.compact(input), "sourceCut");

        assertThat(summary.get("entryCount")).isEqualTo(1);
        assertThat(((Number) summary.get("originalJsonBytes")).longValue())
                .isEqualTo(JSON.writeValueAsBytes(provenance).length).isGreaterThan(4096L);
        assertThat(map(summary.get("auditReference")).get("snapshotId")).isEqualTo("快照-1");
    }

    @Test
    void manySmallWindowVersionsAreBoundedEvenWhenTheyFitWithinTheByteBudget() throws Exception {
        Map<String, Object> versions = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) versions.put(Integer.toString(i), i);
        assertThat(JSON.writeValueAsBytes(versions).length).isLessThan(4096);

        Map<?, ?> summary = summary(CampaignEvidenceContext.compact(Map.of("meta", Map.of("windowVersions", versions))), "windowVersions");

        assertThat(summary.get("compacted")).isEqualTo(true);
        assertThat(summary.get("entryCount")).isEqualTo(65);
    }

    @Test
    void auditReferencesUseLocalMetadataBeforeTopLevelFallback() {
        var input = Map.of("snapshotId", "outer-snapshot", "jobId", "outer-job", "recoveryEpoch", "outer-epoch",
                "meta", Map.of("sourceCut", sourceCuts(WINDOWS), "snapshotId", "inner-snapshot"));

        Map<?, ?> reference = map(summary(CampaignEvidenceContext.compact(input), "sourceCut").get("auditReference"));

        assertThat(reference).isEqualTo(Map.of("snapshotId", "inner-snapshot", "jobId", "outer-job", "recoveryEpoch", "outer-epoch"));
        assertThat(reference.containsKey("manifestSelectionHash")).isFalse();
    }

    @Test
    void rowQualityIsOnlyCompactedForDocumentedRankingAndComparisonResults() {
        var row = Map.of("quality", Map.of("sourceCut", sourceCuts(WINDOWS)), "pv", 2L);
        var input = Map.of("type", "unknown_extension", "rows", List.of(row));

        assertThat(CampaignEvidenceContext.compact(input)).isEqualTo(input);
    }

    @Test
    void nonMapValuesPassThroughUnchanged() {
        var list = List.of(Map.of("sourceCut", sourceCuts(65)));

        assertThat(CampaignEvidenceContext.compact(null)).isNull();
        assertThat(CampaignEvidenceContext.compact("version-1")).isEqualTo("version-1");
        assertThat(CampaignEvidenceContext.compact(list)).isSameAs(list);
    }

    private static void assertProvenanceSummaries(Map<?, ?> meta, Map<String, Object> cuts,
            Map<String, Object> versions) throws Exception {
        for (String key : List.of("sourceCut", "manifestVersion", "windowVersions")) {
            Map<?, ?> summary = map(meta.get(key));
            assertThat(summary.get("compacted")).isEqualTo(true);
            assertThat(summary.get("entryCount")).isEqualTo(WINDOWS);
            assertThat(String.valueOf(summary.get("sha256"))).matches("[0-9a-f]{64}");
            assertThat(((Number) summary.get("originalJsonBytes")).longValue())
                    .isEqualTo(JSON.writeValueAsBytes(key.equals("sourceCut") ? cuts : versions).length);
            assertThat(summary.get("auditReference")).isEqualTo(Map.of(
                    "snapshotId", "snapshot-1", "jobId", "job-1", "recoveryEpoch", "epoch-1",
                    "manifestSelectionHash", "selection-1"));
            assertThat(String.valueOf(summary.get("description"))).isNotBlank();
        }
        assertThat(meta.get("completeness")).isEqualTo("PARTIAL");
        assertThat(meta.get("collectionQuality")).isEqualTo(Map.of("status", "UNKNOWN"));
    }

    private static Map<String, Object> metadata(Map<String, Object> cuts, Map<String, Object> versions) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceCut", cuts);
        meta.put("manifestVersion", versions);
        meta.put("windowVersions", versions);
        meta.put("snapshotId", "snapshot-1");
        meta.put("jobId", "job-1");
        meta.put("recoveryEpoch", "epoch-1");
        meta.put("manifestSelectionHash", "selection-1");
        meta.put("requestedStart", 1757635200000L);
        meta.put("requestedEnd", 1758240000000L);
        meta.put("effectiveEnd", 1758239900000L);
        meta.put("availability", "AVAILABLE");
        meta.put("completeness", "PARTIAL");
        meta.put("freshness", "STALE");
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        meta.put("dimensionQuality", Map.of("province", Map.of("knownPv", 13L, "unknownPv", 7L)));
        meta.put("approximation", Map.of("uv", true, "uip", true));
        return meta;
    }

    private static Map<String, Object> sourceCuts(int count) {
        Map<String, Object> cuts = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("topic", "shortlink-click-events");
            source.put("partition", 0);
            source.put("startOffset", (long) i * 100);
            source.put("endOffset", (long) i * 100 + 99);
            source.put("receiptHash", "0123456789abcdef".repeat(4));
            cuts.put(Long.toString(1757635200L + i * 300L), Map.of("sources", List.of(source)));
        }
        return cuts;
    }

    private static Map<String, Object> versions(int count) {
        Map<String, Object> versions = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            versions.put(Long.toString(1757635200L + i * 300L), Map.of("buildId", "build-" + i, "revision", i + 1));
        }
        return versions;
    }

    private static Map<?, ?> summary(Object value, String key) {
        return map(map(map(value).get("meta")).get(key));
    }

    private static Map<?, ?> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<?, ?>) value;
    }
}
