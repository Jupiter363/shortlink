package com.jupiter.shortlink.agent.riskprofile.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable source provenance retained with a profile and checked again before a policy command.
 */
public record StatsEvidence(
        String tenantId, long linkId, Map<String, Object> meta, String ruleVersion) {
    public static final String CURRENT_RULE_VERSION = "security-risk-v2";

    public StatsEvidence {
        if (tenantId == null || tenantId.isBlank() || linkId < 1)
            throw new IllegalArgumentException("Stable statistics identity is required");
        meta = immutableMap(meta);
        if (ruleVersion == null || ruleVersion.isBlank())
            throw new IllegalArgumentException("Rule version is required");
    }

    public static boolean usable(Map<?, ?> envelope) {
        if (!(envelope.get("meta") instanceof Map<?, ?> metadata)) return false;
        return "AVAILABLE".equals(metadata.get("availability"))
                && "COMPLETE".equals(metadata.get("completeness"))
                && metadata.get("snapshotId") instanceof String id
                && !id.isBlank()
                && metadata.get("recoveryEpoch") instanceof String epoch
                && !epoch.isBlank()
                && ((envelope.get("metrics") instanceof Map<?, ?> metrics && !metrics.isEmpty())
                        || (envelope.get("items") instanceof List<?> items && !items.isEmpty()));
    }

    public boolean permitsAutomaticAction(long now) {
        if (!"AVAILABLE".equals(meta.get("availability"))
                || !"COMPLETE".equals(meta.get("completeness"))
                || !"FRESH".equals(meta.get("freshness"))
                || !(meta.get("provisional") instanceof Boolean)
                || !(meta.get("collectionQuality") instanceof Map<?, ?> quality)
                || !"NORMAL".equals(quality.get("status"))) return false;
        if (!CURRENT_RULE_VERSION.equals(ruleVersion)) return false;
        try {
            if (now > executeBefore() || now >= number(meta.get("snapshotExpiresAt"))) return false;
            if (!(meta.get("approximation") instanceof Map<?, ?> approximation)
                    || !(approximation.get("pv") instanceof Map<?, ?> pv)
                    || !"EXACT".equals(pv.get("type"))) return false;
            return meta.get("snapshotId") instanceof String snapshot
                    && !snapshot.isBlank()
                    && meta.get("recoveryEpoch") instanceof String epoch
                    && !epoch.isBlank();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Every rule declares the metrics it actually consumes. No uncalibrated approximation can
     * qualify a rule.
     */
    public boolean supportsAutomaticReason(
            com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode reason) {
        return switch (reason) {
            case TRAFFIC_SPIKE -> exact("pv");
            case PEAK_HOUR_BURST -> exact("pv") && exact("peakHourShare");
            case IP_CONCENTRATION -> exact("topIpShare");
            case HIGH_REPEAT_VISIT -> exact("pv") && exact("uv");
            default -> false;
        };
    }

    private boolean exact(String metric) {
        return meta.get("approximation") instanceof Map<?, ?> approximation
                && approximation.get(metric) instanceof Map<?, ?> method
                && "EXACT".equals(method.get("type"));
    }

    public long executeBefore() {
        return Math.min(
                Math.addExact(number(meta.get("effectiveEnd")), 120_000L),
                Math.addExact(number(meta.get("snapshotCreatedAt")), 600_000L));
    }

    public Map<String, Object> commandEvidence() {
        return Map.of(
                "snapshotId",
                requireText(meta.get("snapshotId")),
                "recoveryEpoch",
                requireText(meta.get("recoveryEpoch")),
                "effectiveEnd",
                number(meta.get("effectiveEnd")),
                "evidenceCreatedAt",
                number(meta.get("snapshotCreatedAt")),
                "executeBefore",
                executeBefore());
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "tenantId", tenantId, "linkId", linkId, "meta", meta, "ruleVersion", ruleVersion);
    }

    public static StatsEvidence from(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> map) || !(map.get("meta") instanceof Map<?, ?> meta))
            throw new IllegalArgumentException("Invalid evidence");
        return new StatsEvidence(
                requireText(map.get("tenantId")),
                number(map.get("linkId")),
                copyMap(meta),
                requireText(map.get("ruleVersion")));
    }

    public static void requireSameSnapshot(Map<String, Object> first, Map<String, Object> second) {
        for (String field :
                List.of(
                        "snapshotId",
                        "recoveryEpoch",
                        "effectiveEnd",
                        "manifestVersion",
                        "sourceCut",
                        "metricVersion",
                        "detailDatasetVersion")) {
            if (first.get(field) == null || !Objects.equals(first.get(field), second.get(field))) {
                throw new IllegalStateException(
                        "Statistics windows do not share a frozen " + field);
            }
        }
    }

    public static long number(Object value) {
        if (value == null)
            throw new IllegalArgumentException("Required count or timestamp is missing");
        try {
            long result = new BigDecimal(value.toString()).longValueExact();
            if (result < 0) throw new IllegalArgumentException("Negative count or timestamp");
            return result;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Count is not an exact nonnegative BIGINT", invalid);
        }
    }

    public static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null) throw new IllegalArgumentException("Statistics metadata is required");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, immutable(value)));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) return immutableMap(copyMap(map));
        if (value instanceof List<?> list)
            return list.stream().map(StatsEvidence::immutable).toList();
        return value;
    }

    private static String requireText(Object value) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("Required evidence field is missing");
        return text;
    }
}
