package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CampaignLinkComparabilityTest {
    private static final long START = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;
    private static final Period BASELINE = new Period(START, START + DAY, "UTC");
    private static final Period TARGET = new Period(START + DAY, START + 2 * DAY, "UTC");
    private static final Row BEFORE = new Row(7, 13, 10, 8, 2);
    private static final Row AFTER = new Row(7, 3, 2, 1, 0);

    @Test
    void retainsObservedDeltasAndIndependentEvidenceWithoutInventingCollectionOrLifetimeCompleteness() {
        Map<String, Object> originalQuality = quality();
        Map<String, Object> collection = new LinkedHashMap<>(Map.of("status", "UNKNOWN"));
        List<Object> unknownExtension = new ArrayList<>(List.of("original"));
        originalQuality.put("collectionQuality", collection);
        originalQuality.put("extension", unknownExtension);
        originalQuality.put("nullExtension", null);
        QueryObservation before = observation("baseline-artifact", BASELINE, originalQuality);
        QueryObservation after = observation("target-artifact", TARGET, quality());
        collection.put("status", "COMPLETE");
        unknownExtension.clear();
        originalQuality.clear();

        Result pv = assess(BEFORE, AFTER, BASELINE, TARGET, before, after, Metric.PV);
        assertEquals(7, pv.linkId());
        assertEquals(13, pv.baseline());
        assertEquals(3, pv.target());
        assertEquals(BigInteger.valueOf(-10), pv.delta());
        assertEquals(new BigDecimal("-0.7692307692307692307692307692307692"), pv.rate());
        assertEquals(Comparability.VERIFIED, pv.comparability());
        assertEquals(Explanation.OBSERVED_ONLY, pv.explanation());
        assertEquals(List.of("baseline-artifact", "target-artifact"), pv.evidenceRefs());
        assertTrue(pv.reasonCodes().containsAll(List.of("OBJECT_LIFETIME_UNKNOWN",
                "COMMON_DATA_BASE_NOT_VERIFIED", "BASELINE_COLLECTION_COMPLETENESS_UNVERIFIED",
                "TARGET_COLLECTION_COMPLETENESS_UNVERIFIED", "BASELINE_MISSING_METRICS_REPORTED")));
        assertFalse(pv.reasonCodes().contains("METRIC_APPROXIMATE"));
        assertNotEquals(before.snapshotId(), after.snapshotId());
        assertNotEquals(before.sourceCut(), after.sourceCut());
        assertEquals("baseline-artifact-request", before.requestHash());
        assertEquals("UNKNOWN", ((Map<?, ?>) before.quality().get("collectionQuality")).get("status"));
        assertEquals(List.of("original"), before.quality().get("extension"));
        assertTrue(before.quality().containsKey("nullExtension"));
        assertNull(before.quality().get("nullExtension"));
        assertThrows(UnsupportedOperationException.class, () -> before.sourceCut().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<?, ?>) before.quality().get("collectionQuality")).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) before.quality().get("extension")).clear());
        assertThrows(UnsupportedOperationException.class, () -> pv.reasonCodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> pv.evidenceRefs().clear());

        Result uv = assess(BEFORE, AFTER, BASELINE, TARGET, before, after, Metric.UV);
        Result uip = assess(BEFORE, AFTER, BASELINE, TARGET, before, after, Metric.UIP);
        assertEquals(BigInteger.valueOf(-8), uv.delta());
        assertEquals(BigInteger.valueOf(-7), uip.delta());
        assertEquals(new BigDecimal("-0.8"), uv.rate());
        assertEquals(new BigDecimal("-0.875"), uip.rate());
        assertTrue(uv.reasonCodes().contains("METRIC_APPROXIMATE"));
        assertTrue(uip.reasonCodes().contains("METRIC_APPROXIMATE"));
        assertEquals(Explanation.OBSERVED_ONLY, uv.explanation());

        Row zero = new Row(7, 0, 0, 0, 0);
        Row largest = new Row(7, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0);
        Result fromZero = assess(zero, largest, BASELINE, TARGET, before, after, Metric.PV);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), fromZero.delta());
        assertNull(fromZero.rate());
        assertTrue(fromZero.reasonCodes().contains("BASELINE_ZERO"));
        Result toZero = assess(largest, zero, BASELINE, TARGET, before, after, Metric.PV);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).negate(), toZero.delta());
        assertEquals(new BigDecimal("-1"), toZero.rate());
        Result minimalDecline = assess(largest,
                new Row(7, Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE, 0),
                BASELINE, TARGET, before, after, Metric.PV);
        assertEquals(BigInteger.valueOf(-1), minimalDecline.delta());
        assertTrue(minimalDecline.rate().signum() < 0, "A real tiny decline must not round to zero");
        assertNull(assess(zero, zero, BASELINE, TARGET, before, after, Metric.PV).rate());
    }

    @Test
    void rejectsIdentityMixingAndKeepsFrozenTimingDefinitionAndQualityFailuresVisible() {
        QueryObservation before = observation("baseline", BASELINE, quality());
        QueryObservation after = observation("target", TARGET, quality());
        Period longer = new Period(TARGET.startInclusive(), TARGET.endExclusive() + DAY, "UTC");
        Period overlapping = new Period(START + DAY / 2, START + DAY + DAY / 2, "UTC");
        Period otherZone = new Period(TARGET.startInclusive(), TARGET.endExclusive(), "Asia/Shanghai");
        Map<String, Object> partial = quality();
        partial.put("completeness", "PARTIAL");
        Map<String, Object> missingPv = quality();
        missingPv.put("missingMetrics", List.of("pv", "producerCollectionCompleteness"));
        Map<String, Object> differentAlgorithm = quality();
        differentAlgorithm.put("approximation", Map.of("pv",
                Map.of("type", "EXACT", "algorithm", "other-counter", "version", "click-v1")));

        List<Failure> failures = List.of(
                new Failure(() -> compare(before, with(after, after.observedAt(), after.effectiveEnd(),
                        "click-v2", after.recoveryEpoch(), after.sourceCut(), after.quality())),
                        Comparability.INCOMPATIBLE, "METRIC_VERSION_MISMATCH"),
                new Failure(() -> compare(before, with(after, after.observedAt(), after.effectiveEnd(),
                        null, after.recoveryEpoch(), after.sourceCut(), after.quality())),
                        Comparability.UNVERIFIED, "METRIC_VERSION_UNKNOWN"),
                new Failure(() -> assess(BEFORE, AFTER, BASELINE, longer, before,
                        observation("longer", longer, quality()), Metric.PV),
                        Comparability.INCOMPATIBLE, "PERIOD_LENGTH_MISMATCH"),
                new Failure(() -> assess(BEFORE, AFTER, BASELINE, overlapping, before,
                        observation("overlap", overlapping, quality()), Metric.PV),
                        Comparability.INCOMPATIBLE, "PERIODS_OVERLAP"),
                new Failure(() -> assess(BEFORE, AFTER, BASELINE, otherZone, before, after, Metric.PV),
                        Comparability.INCOMPATIBLE, "PERIOD_TIMEZONE_MISMATCH"),
                new Failure(() -> compare(before, with(after, TARGET.endExclusive() - 1,
                        after.effectiveEnd(), after.metricVersion(), after.recoveryEpoch(),
                        after.sourceCut(), after.quality())),
                        Comparability.UNVERIFIED, "TARGET_PERIOD_OPEN_AT_OBSERVATION"),
                new Failure(() -> compare(before, with(after, null, after.effectiveEnd(),
                        after.metricVersion(), after.recoveryEpoch(), after.sourceCut(), after.quality())),
                        Comparability.UNVERIFIED, "TARGET_OBSERVED_AT_UNKNOWN"),
                new Failure(() -> compare(before, with(after, after.observedAt(),
                        TARGET.endExclusive() - 1, after.metricVersion(), after.recoveryEpoch(),
                        after.sourceCut(), after.quality())),
                        Comparability.INCOMPATIBLE, "TARGET_EFFECTIVE_END_MISMATCH"),
                new Failure(() -> compare(before, with(after, after.observedAt(), after.effectiveEnd(),
                        after.metricVersion(), "new-epoch", after.sourceCut(), after.quality())),
                        Comparability.UNVERIFIED, "RECOVERY_EPOCH_CHANGED"),
                new Failure(() -> compare(before, observation("partial", TARGET, partial)),
                        Comparability.UNVERIFIED, "TARGET_QUERY_QUALITY_UNVERIFIED"),
                new Failure(() -> compare(before, observation("missing", TARGET, missingPv)),
                        Comparability.UNVERIFIED, "TARGET_SELECTED_METRIC_MISSING"),
                new Failure(() -> compare(before, observation("unknown", TARGET, Map.of())),
                        Comparability.UNVERIFIED, "METRIC_APPROXIMATION_UNKNOWN"),
                new Failure(() -> compare(before, observation("changed", TARGET, differentAlgorithm)),
                        Comparability.INCOMPATIBLE, "METRIC_APPROXIMATION_ALGORITHM_MISMATCH"),
                new Failure(() -> compare(before, with(after, after.observedAt(), after.effectiveEnd(),
                        after.metricVersion(), after.recoveryEpoch(), Map.of(), after.quality())),
                        Comparability.UNVERIFIED, "TARGET_QUERY_PROVENANCE_UNKNOWN"),
                new Failure(() -> compare(before, with(after, after.observedAt(), after.effectiveEnd(),
                        after.metricVersion(), after.recoveryEpoch(),
                        Map.of("manifestSelectionHash", "different"), after.quality())),
                        Comparability.INCOMPATIBLE, "TARGET_QUERY_PROVENANCE_INCONSISTENT"));
        for (Failure failure : failures) {
            Result result = failure.comparison().get();
            assertEquals(failure.expected(), result.comparability(), failure.reason());
            assertTrue(result.reasonCodes().contains(failure.reason()), failure.reason());
            assertEquals(BigInteger.valueOf(-10), result.delta(), "Even limited observed differences remain visible");
            assertNull(result.rate(), failure.reason());
            assertEquals(Explanation.OBSERVED_ONLY, result.explanation());
            assertEquals(result, failure.comparison().get(), "No current-clock upgrade of a frozen observation");
        }
        assertThrows(IllegalArgumentException.class, () -> assess(BEFORE,
                new Row(8, 3, 2, 1, 0), BASELINE, TARGET, before, after, Metric.PV));
        assertThrows(IllegalArgumentException.class, () -> new Row(7, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Row(0, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Period(START, START, "UTC"));
    }

    private static Result compare(QueryObservation before, QueryObservation after) {
        return assess(BEFORE, AFTER, BASELINE, TARGET, before, after, Metric.PV);
    }

    private static QueryObservation observation(String artifact, Period period, Map<String, Object> quality) {
        return new QueryObservation(artifact, artifact + "-request", artifact + "-snapshot",
                period.endExclusive(), period.endExclusive(), "click-v1", "epoch-1",
                Map.of("manifestSelectionHash", artifact + "-cut"),
                Map.of("selectionHash", artifact + "-cut"), quality);
    }

    private static QueryObservation with(QueryObservation original, Long observedAt, Long effectiveEnd,
                                         String metricVersion, String epoch, Map<String, Object> sourceCut,
                                         Map<String, Object> quality) {
        return new QueryObservation(original.artifactRef(), original.requestHash(), original.snapshotId(),
                observedAt, effectiveEnd, metricVersion, epoch, sourceCut, original.manifestVersion(), quality);
    }

    private static Map<String, Object> quality() {
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("availability", "AVAILABLE");
        quality.put("completeness", "COMPLETE");
        quality.put("freshness", "FRESH");
        quality.put("provisional", false);
        quality.put("collectionQuality", Map.of("status", "UNKNOWN"));
        quality.put("missingMetrics", List.of("producerCollectionCompleteness"));
        quality.put("approximation", Map.of(
                "pv", Map.of("type", "EXACT", "algorithm", "eventId-dedup", "version", "click-v1"),
                "uv", Map.of("type", "APPROXIMATE", "algorithm", "uniqCombined64", "version", "click-v1"),
                "uip", Map.of("type", "APPROXIMATE", "algorithm", "uniqCombined64", "version", "click-v1")));
        return quality;
    }

    private record Failure(Supplier<Result> comparison, Comparability expected, String reason) {}
}
