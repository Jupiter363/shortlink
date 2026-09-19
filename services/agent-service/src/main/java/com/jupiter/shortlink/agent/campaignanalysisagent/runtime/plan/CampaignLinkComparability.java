package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, per-link comparison of already validated dense LINK_METRICS rows.
 * VERIFIED describes the comparison's structure, not producer collection completeness or
 * object lifetime. Neither a page snapshot nor this result establishes a shared cross-query
 * data cut. Callers retain the two query observations once, rather than copying them per row.
 */
public final class CampaignLinkComparability {
    private CampaignLinkComparability() {}

    public enum Metric { PV, UV, UIP }
    public enum Comparability { VERIFIED, UNVERIFIED, INCOMPATIBLE }
    /** COMPARABLE_CHANGE is reserved; the present query contract has no object lifetime proof. */
    public enum Explanation { OBSERVED_ONLY, COMPARABLE_CHANGE }

    public record Period(long startInclusive, long endExclusive, String timezone) {
        public Period {
            require(startInclusive >= 0 && endExclusive > startInclusive, "INVALID_PERIOD");
            require(text(timezone), "INVALID_TIMEZONE");
            ZoneId.of(timezone);
        }
    }

    /** JSON integer exactness and complete member coverage belong to the upstream reader. */
    public record Row(long linkId, long pv, long uv, long uip, long denied) {
        public Row {
            require(linkId > 0 && pv >= 0 && uv >= 0 && uip >= 0 && denied >= 0, "INVALID_ROW");
        }
    }

    /**
     * Times are epoch milliseconds. observedAt is the frozen snapshotCreatedAt, never now.
     * quality contains the original availability, completeness, freshness, provisional,
     * collectionQuality, missingMetrics and approximation fields, including unknown extensions.
     * Missing maps remain empty/unknown; JSON null values are preserved.
     */
    public record QueryObservation(String artifactRef, String requestHash, String snapshotId,
                                   Long observedAt, Long effectiveEnd, String metricVersion,
                                   String recoveryEpoch, Map<String, Object> sourceCut,
                                   Map<String, Object> manifestVersion, Map<String, Object> quality) {
        public QueryObservation {
            require(text(artifactRef), "ARTIFACT_REFERENCE_REQUIRED");
            require(observedAt == null || observedAt >= 0, "INVALID_OBSERVED_AT");
            require(effectiveEnd == null || effectiveEnd >= 0, "INVALID_EFFECTIVE_END");
            sourceCut = freezeMap(sourceCut, 0);
            manifestVersion = freezeMap(manifestVersion, 0);
            quality = freezeMap(quality, 0);
        }
    }

    public record Result(long linkId, Metric metric, long baseline, long target, BigInteger delta,
                         BigDecimal rate, Comparability comparability, Explanation explanation,
                         List<String> reasonCodes, List<String> evidenceRefs) {
        public Result {
            reasonCodes = List.copyOf(reasonCodes);
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    /** Query-level assessment also applies to real cohort summaries without inventing a link row. */
    public record QueryAssessment(Comparability comparability, List<String> reasonCodes) {
        public QueryAssessment {
            Objects.requireNonNull(comparability);
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    public static Result assess(Row baseline, Row target, Period baselinePeriod, Period targetPeriod,
                                QueryObservation baselineObservation, QueryObservation targetObservation,
                                Metric metric) {
        Objects.requireNonNull(baseline);
        Objects.requireNonNull(target);
        Objects.requireNonNull(baselinePeriod);
        Objects.requireNonNull(targetPeriod);
        Objects.requireNonNull(baselineObservation);
        Objects.requireNonNull(targetObservation);
        Objects.requireNonNull(metric);
        require(baseline.linkId() == target.linkId(), "LINK_ID_MISMATCH");

        QueryAssessment assessment = assessQuery(baselinePeriod, targetPeriod, baselineObservation, targetObservation, metric);
        List<String> reasons = new ArrayList<>(assessment.reasonCodes());
        long previous = value(baseline, metric);
        long current = value(target, metric);
        BigInteger delta = BigInteger.valueOf(current).subtract(BigInteger.valueOf(previous));
        if (previous == 0) reasons.add("BASELINE_ZERO");
        BigDecimal rate = previous > 0 && assessment.comparability() == Comparability.VERIFIED
                ? new BigDecimal(delta).divide(BigDecimal.valueOf(previous), MathContext.DECIMAL128)
                : null;
        return new Result(baseline.linkId(), metric, previous, current, delta, rate,
                assessment.comparability(), Explanation.OBSERVED_ONLY, reasons,
                List.of(baselineObservation.artifactRef(), targetObservation.artifactRef()));
    }

    public static QueryAssessment assessQuery(Period baselinePeriod, Period targetPeriod,
                                              QueryObservation baselineObservation, QueryObservation targetObservation,
                                              Metric metric) {
        Objects.requireNonNull(baselinePeriod);
        Objects.requireNonNull(targetPeriod);
        Objects.requireNonNull(baselineObservation);
        Objects.requireNonNull(targetObservation);
        Objects.requireNonNull(metric);
        Assessment assessment = new Assessment();
        if (!baselinePeriod.timezone().equals(targetPeriod.timezone())) {
            assessment.incompatible("PERIOD_TIMEZONE_MISMATCH");
        }
        if (baselinePeriod.endExclusive() - baselinePeriod.startInclusive()
                != targetPeriod.endExclusive() - targetPeriod.startInclusive()) {
            assessment.incompatible("PERIOD_LENGTH_MISMATCH");
        }
        if (baselinePeriod.startInclusive() < targetPeriod.endExclusive()
                && targetPeriod.startInclusive() < baselinePeriod.endExclusive()) {
            assessment.incompatible("PERIODS_OVERLAP");
        }
        compareDefinition(baselineObservation.metricVersion(), targetObservation.metricVersion(),
                "METRIC_VERSION", assessment);
        if (!text(baselineObservation.recoveryEpoch()) || !text(targetObservation.recoveryEpoch())) {
            assessment.unverified("RECOVERY_EPOCH_UNKNOWN");
        } else if (!baselineObservation.recoveryEpoch().equals(targetObservation.recoveryEpoch())) {
            assessment.unverified("RECOVERY_EPOCH_CHANGED");
        }

        inspect("BASELINE", baselinePeriod, baselineObservation, metric, assessment);
        inspect("TARGET", targetPeriod, targetObservation, metric, assessment);
        compareApproximation(baselineObservation, targetObservation, metric, assessment);
        // These limitations cannot be discharged by matching hashes, COMPLETE query output,
        // a dense zero row, or the passage of wall-clock time.
        assessment.reasons.add("COMMON_DATA_BASE_NOT_VERIFIED");
        assessment.reasons.add("OBJECT_LIFETIME_UNKNOWN");
        return new QueryAssessment(assessment.comparability, new ArrayList<>(assessment.reasons));
    }

    private static void inspect(String side, Period period, QueryObservation observation,
                                Metric metric, Assessment assessment) {
        if (!text(observation.requestHash()) || !text(observation.snapshotId())) {
            assessment.unverified(side + "_QUERY_IDENTITY_UNKNOWN");
        }
        if (observation.observedAt() == null) {
            assessment.unverified(side + "_OBSERVED_AT_UNKNOWN");
        } else if (observation.observedAt() < period.endExclusive()) {
            assessment.unverified(side + "_PERIOD_OPEN_AT_OBSERVATION");
        }
        if (observation.effectiveEnd() == null) {
            assessment.unverified(side + "_EFFECTIVE_END_UNKNOWN");
        } else if (observation.effectiveEnd() != period.endExclusive()) {
            assessment.incompatible(side + "_EFFECTIVE_END_MISMATCH");
        }
        Object sourceHash = observation.sourceCut().get("manifestSelectionHash");
        Object manifestHash = observation.manifestVersion().get("selectionHash");
        if (!(sourceHash instanceof String source) || !text(source)
                || !(manifestHash instanceof String manifest) || !text(manifest)) {
            assessment.unverified(side + "_QUERY_PROVENANCE_UNKNOWN");
        } else if (!sourceHash.equals(manifestHash)) {
            assessment.incompatible(side + "_QUERY_PROVENANCE_INCONSISTENT");
        }

        Map<String, Object> quality = observation.quality();
        if (!"AVAILABLE".equals(quality.get("availability"))
                || !"COMPLETE".equals(quality.get("completeness"))
                || !"FRESH".equals(quality.get("freshness"))
                || !Boolean.FALSE.equals(quality.get("provisional"))) {
            assessment.unverified(side + "_QUERY_QUALITY_UNVERIFIED");
        }
        if (!(quality.get("collectionQuality") instanceof Map<?, ?> collection)
                || !"COMPLETE".equals(collection.get("status"))) {
            assessment.reasons.add(side + "_COLLECTION_COMPLETENESS_UNVERIFIED");
        }
        Object missing = quality.get("missingMetrics");
        if (!(missing instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            assessment.unverified(side + "_MISSING_METRICS_UNKNOWN");
        } else if (!list.isEmpty()) {
            assessment.reasons.add(side + "_MISSING_METRICS_REPORTED");
            if (list.contains(key(metric))) assessment.unverified(side + "_SELECTED_METRIC_MISSING");
        }
    }

    private static void compareApproximation(QueryObservation baseline, QueryObservation target,
                                             Metric metric, Assessment assessment) {
        Map<?, ?> previous = approximation(baseline, metric);
        Map<?, ?> current = approximation(target, metric);
        for (String field : List.of("type", "algorithm", "version")) {
            compareDefinition(string(previous.get(field)), string(current.get(field)),
                    "METRIC_APPROXIMATION_" + field.toUpperCase(java.util.Locale.ROOT), assessment);
        }
        Object previousType = previous.get("type");
        Object currentType = current.get("type");
        if ("APPROXIMATE".equals(previousType) || "APPROXIMATE".equals(currentType)) {
            assessment.reasons.add("METRIC_APPROXIMATE");
        }
        if (!("EXACT".equals(previousType) || "APPROXIMATE".equals(previousType))
                || !("EXACT".equals(currentType) || "APPROXIMATE".equals(currentType))) {
            assessment.unverified("METRIC_APPROXIMATION_UNKNOWN");
        }
    }

    private static Map<?, ?> approximation(QueryObservation observation, Metric metric) {
        if (observation.quality().get("approximation") instanceof Map<?, ?> all
                && all.get(key(metric)) instanceof Map<?, ?> definition) return definition;
        return Map.of();
    }

    private static void compareDefinition(String baseline, String target, String name, Assessment assessment) {
        if (!text(baseline) || !text(target)) assessment.unverified(name + "_UNKNOWN");
        else if (!baseline.equals(target)) assessment.incompatible(name + "_MISMATCH");
    }

    private static long value(Row row, Metric metric) {
        return switch (metric) { case PV -> row.pv(); case UV -> row.uv(); case UIP -> row.uip(); };
    }

    private static String key(Metric metric) {
        return metric.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String string(Object value) { return value instanceof String text ? text : null; }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }

    private static Map<String, Object> freezeMap(Map<?, ?> values, int depth) {
        require(depth <= 32, "METADATA_TOO_DEEP");
        if (values == null) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            require(key instanceof String, "INVALID_METADATA_KEY");
            copy.put((String) key, freeze(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value, int depth) {
        require(depth <= 32, "METADATA_TOO_DEEP");
        if (value instanceof Map<?, ?> map) return freezeMap(map, depth);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(freeze(item, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Double doubleValue && Double.isFinite(doubleValue)
                || value instanceof Float floatValue && Float.isFinite(floatValue)) return value;
        throw new IllegalArgumentException("INVALID_METADATA_VALUE");
    }

    private static final class Assessment {
        private Comparability comparability = Comparability.VERIFIED;
        private final Set<String> reasons = new LinkedHashSet<>();
        private void unverified(String reason) {
            if (comparability == Comparability.VERIFIED) comparability = Comparability.UNVERIFIED;
            reasons.add(reason);
        }
        private void incompatible(String reason) {
            comparability = Comparability.INCOMPATIBLE;
            reasons.add(reason);
        }
    }
}
