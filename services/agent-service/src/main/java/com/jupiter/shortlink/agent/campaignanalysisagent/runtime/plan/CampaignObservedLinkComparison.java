package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Gap;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.SlotResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.VerifiedSlot;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Streams paired observations from actual validated durable pages; it does not publish a selection. */
public final class CampaignObservedLinkComparison {
    /** Negative differences count arithmetic observations, including limited ones; this is not an eligible selection. */
    public record Summary(CampaignParentCoverage.Summary coverage, long pairedMembers,
                          long negativeObservedDifferences, long incompatiblePairs, long unverifiedPairs) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> QUALITY_FIELDS = List.of("availability", "completeness", "freshness", "provisional",
            "collectionQuality", "missingMetrics", "approximation");
    private final CampaignParentCoverage coverage;

    public CampaignObservedLinkComparison(CampaignParentCoverage coverage) {
        this.coverage = Objects.requireNonNull(coverage);
    }

    /**
     * The sink receives every paired object, including zero and incompatible/limited observations.
     * No Top N, sorting cap, fabricated missing row or selectionComplete flag is introduced here.
     * The caller owns durable sink transactions and final publication; a sink failure propagates.
     */
    public Summary compare(Caller current, String scopeArtifactId, List<CampaignParentCoverage.Period> periods,
                           SlotResolver slots, ArtifactAuthorizer authorizer, Metric metric,
                           Consumer<Gap> gaps, Consumer<Result> sink) {
        return compareRange(current, scopeArtifactId, periods, slots, authorizer, metric, gaps, sink, null);
    }

    public Summary compareShard(Caller current, String scopeArtifactId, List<CampaignParentCoverage.Period> periods,
                                SlotResolver slots, ArtifactAuthorizer authorizer, Metric metric,
                                Consumer<Gap> gaps, Consumer<Result> sink, int shardIndex) {
        if (shardIndex < 0) throw new IllegalArgumentException("COVERAGE_SHARD_INVALID");
        return compareRange(current, scopeArtifactId, periods, slots, authorizer, metric, gaps, sink, shardIndex);
    }

    private Summary compareRange(Caller current, String scopeArtifactId, List<CampaignParentCoverage.Period> periods,
                                 SlotResolver slots, ArtifactAuthorizer authorizer, Metric metric,
                                 Consumer<Gap> gaps, Consumer<Result> sink, Integer shardIndex) {
        Objects.requireNonNull(metric);
        Objects.requireNonNull(sink);
        if (periods == null || periods.size() != 2) throw new IllegalArgumentException("TWO_FROZEN_PERIODS_REQUIRED");
        List<CampaignParentCoverage.Period> frozenPeriods = List.copyOf(periods);
        Period baselinePeriod = period(frozenPeriods.get(0));
        Period targetPeriod = period(frozenPeriods.get(1));
        class PairSink implements Consumer<VerifiedSlot> {
            private int pendingShard = -1;
            private Map<Long, Row> baseline;
            private QueryObservation baselineObservation;
            private long paired, negative, incompatible, unverified;

            @Override public void accept(VerifiedSlot slot) {
                if (slot.periodIndex() == 0) {
                    pendingShard = slot.shardIndex();
                    baseline = new LinkedHashMap<>();
                    for (JsonNode value : slot.rows()) {
                        Row row = row(value);
                        if (baseline.putIfAbsent(row.linkId(), row) != null) throw new IllegalStateException("LINK_METRICS_DUPLICATE_ID");
                    }
                    baselineObservation = observation(slot);
                    return;
                }
                if (slot.periodIndex() != 1) throw new IllegalStateException("COMPARISON_PERIOD_INVALID");
                if (baseline == null || pendingShard != slot.shardIndex()) {
                    clear(); // A missing baseline remains a coverage gap, never a synthesized zero.
                    return;
                }
                QueryObservation targetObservation = observation(slot);
                try {
                    for (JsonNode value : slot.rows()) {
                        Row target = row(value);
                        Row before = baseline.remove(target.linkId());
                        if (before == null) throw new IllegalStateException("LINK_METRICS_PAIR_MISMATCH");
                        Result result = CampaignLinkComparability.assess(before, target, baselinePeriod, targetPeriod,
                                baselineObservation, targetObservation, metric);
                        sink.accept(result);
                        paired = Math.addExact(paired, 1);
                        if (result.delta().signum() < 0) negative = Math.addExact(negative, 1);
                        if (result.comparability() == Comparability.INCOMPATIBLE) incompatible = Math.addExact(incompatible, 1);
                        else if (result.comparability() == Comparability.UNVERIFIED) unverified = Math.addExact(unverified, 1);
                    }
                    if (!baseline.isEmpty()) throw new IllegalStateException("LINK_METRICS_PAIR_MISMATCH");
                } finally { clear(); }
            }

            private void clear() { baseline = null; baselineObservation = null; pendingShard = -1; }
        }
        PairSink pairs = new PairSink();
        var checked = shardIndex == null
                ? coverage.check(current, scopeArtifactId, frozenPeriods, slots, authorizer, gaps, pairs)
                : coverage.checkShard(current, scopeArtifactId, frozenPeriods, slots, authorizer, gaps, pairs, shardIndex);
        return new Summary(checked, pairs.paired, pairs.negative, pairs.incompatible, pairs.unverified);
    }

    private static Period period(CampaignParentCoverage.Period value) {
        ZoneId zone = ZoneId.of(value.timeZone());
        long start = LocalDate.parse(value.startDate()).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = LocalDate.parse(value.endDate()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new Period(start, end, value.timeZone());
    }

    private static Row row(JsonNode value) {
        return new Row(integer(value, "linkId"), integer(value, "pv"), integer(value, "uv"),
                integer(value, "uip"), integer(value, "denied"));
    }

    private static QueryObservation observation(VerifiedSlot slot) {
        JsonNode snapshot = slot.snapshot();
        Map<String, Object> quality = new LinkedHashMap<>();
        for (String name : QUALITY_FIELDS) if (snapshot.has(name))
            quality.put(name, JSON.convertValue(snapshot.get(name), Object.class));
        return new QueryObservation(slot.artifactId(), slot.requestHash(), text(snapshot, "snapshotId"),
                optionalInteger(snapshot, "snapshotCreatedAt"), optionalInteger(snapshot, "effectiveEnd"),
                text(snapshot, "metricVersion"), text(snapshot, "recoveryEpoch"),
                object(snapshot.get("sourceCut")), object(snapshot.get("manifestVersion")), quality);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("QUERY_OBSERVATION_INVALID");
        return value.textValue();
    }

    private static Long optionalInteger(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        return integer(node, field);
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("QUERY_OBSERVATION_INVALID");
        return value.longValue();
    }

    private static Map<String, Object> object(JsonNode value) {
        if (value == null || value.isNull()) return Map.of();
        if (!value.isObject()) throw new IllegalArgumentException("QUERY_OBSERVATION_INVALID");
        return JSON.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }
}
