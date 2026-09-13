package com.jupiter.shortlink.agent.riskprofile.model;

import java.util.*;

/** Bounded aggregate explanation for one window; never an input to risk scoring or policy actions. */
public record RiskWindowDimensions(
        String window,
        long startInclusive,
        long endExclusive,
        List<Bucket> countryStats,
        List<Bucket> localeCnStats,
        List<Bucket> networkStats,
        List<Bucket> uvTypeStats,
        List<Bucket> geoStatusStats,
        List<Bucket> geoVersionStats,
        Double topRegionShare,
        String geoSelectionPolicy,
        Map<String, Map<String, Object>> dimensionQuality,
        Map<String, Object> snapshotContext) {

    private static final int MAX_BUCKETS = 256;
    private static final Set<String> DIMENSIONS = Set.of(
            "country", "countryStats", "localeCnStats", "networkStats", "uvTypeStats", "topRegionShare");
    private static final Set<String> QUALITY_FIELDS = Set.of(
            "status", "knownCount", "unknownCount", "eligibleCount", "coverage", "semantic", "scope",
            "reason", "historicalBackfill", "maxHistoryDays", "historyStart", "historyEnd", "historyProof",
            "historySourceTopic", "earliestObservedAt", "newUv", "oldUv", "unknownUv",
            "missingVisitorClicks", "historicalMeaning", "authorizedLinkCount");
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "snapshotId", "snapshotCreatedAt", "requestedStart", "requestedEnd", "effectiveEnd",
            "recoveryEpoch", "metricVersion", "detailDatasetVersion", "businessTimezone",
            "availability", "freshness", "completeness", "provisional");

    public record Bucket(String value, long cnt, Double ratio) {
        public Bucket {
            if (value == null || value.isBlank() || value.length() > 256 || cnt < 0)
                throw new IllegalArgumentException("Invalid aggregate dimension bucket");
            ratio = fraction(ratio);
        }
    }

    public RiskWindowDimensions {
        if (!Set.of("2h", "24h", "7d", "requested").contains(window)
                || startInclusive < 0 || endExclusive < startInclusive)
            throw new IllegalArgumentException("Invalid dimension window");
        countryStats = bounded(countryStats);
        localeCnStats = bounded(localeCnStats);
        networkStats = bounded(networkStats);
        uvTypeStats = bounded(uvTypeStats);
        geoStatusStats = bounded(geoStatusStats);
        geoVersionStats = bounded(geoVersionStats);
        topRegionShare = fraction(topRegionShare);
        geoSelectionPolicy = geoSelectionPolicy == null ? "UNKNOWN" : shortText(geoSelectionPolicy);
        dimensionQuality = qualities(dimensionQuality);
        snapshotContext = context(snapshotContext);
    }

    public static RiskWindowDimensions from(
            String window, long start, long end, Map<String, Object> row, Map<String, Object> meta) {
        return new RiskWindowDimensions(window, start, end,
                buckets(row.get("countryStats"), "country"),
                buckets(row.get("localeCnStats"), "locale"),
                buckets(row.get("networkStats"), "network"),
                buckets(row.get("uvTypeStats"), "uvType"),
                buckets(row.get("geoStatusStats"), "status"),
                buckets(row.get("geoVersionStats"), "version"),
                fraction(row.get("topRegionShare")),
                row.get("geoSelectionPolicy") instanceof String policy ? policy : "UNKNOWN",
                qualities(row.get("dimensionQuality")), context(meta));
    }

    private static List<Bucket> buckets(Object value, String field) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> rows) || rows.size() > MAX_BUCKETS)
            throw new IllegalArgumentException("Dimension bucket budget exceeded or invalid");
        List<Bucket> result = new ArrayList<>(rows.size());
        for (Object entry : rows) {
            if (!(entry instanceof Map<?, ?> row) || !(row.get(field) instanceof String label))
                throw new IllegalArgumentException("Invalid dimension bucket");
            result.add(new Bucket(label, StatsEvidence.number(row.get("cnt")), fraction(row.get("ratio"))));
        }
        return List.copyOf(result);
    }

    private static List<Bucket> bounded(List<Bucket> buckets) {
        if (buckets == null) return List.of();
        if (buckets.size() > MAX_BUCKETS) throw new IllegalArgumentException("Dimension bucket budget exceeded");
        return List.copyOf(buckets);
    }

    private static Map<String, Map<String, Object>> qualities(Object source) {
        Map<?, ?> values = source instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String field : new TreeSet<>(DIMENSIONS)) {
            if (values.get(field) instanceof Map<?, ?> quality) result.put(field, quality(quality));
            else result.put(field, Map.of("status", "UNKNOWN", "reason", "DIMENSION_QUALITY_NOT_PROVIDED"));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> quality(Map<?, ?> source) {
        Map<String, Object> result = scalars(source, QUALITY_FIELDS);
        result.putIfAbsent("status", "UNKNOWN");
        if (source.get("reasonCounts") instanceof Map<?, ?> reasons) {
            if (reasons.size() > 64) throw new IllegalArgumentException("Dimension reason budget exceeded");
            Map<String, Long> counts = new TreeMap<>();
            reasons.forEach((key, value) -> {
                if (key instanceof String reason && reason.matches("[A-Z][A-Z0-9_]{0,95}"))
                    counts.put(reason, StatsEvidence.number(value));
            });
            result.put("reasonCounts", Collections.unmodifiableMap(counts));
        }
        if (source.get("reasons") instanceof List<?> reasons) result.put("reasons", texts(reasons));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> context(Map<?, ?> source) {
        if (source == null) return Map.of();
        Map<String, Object> result = scalars(source, CONTEXT_FIELDS);
        if (source.get("collectionQuality") instanceof Map<?, ?> collection)
            result.put("collectionQuality", quality(collection));
        if (source.get("missingMetrics") instanceof List<?> missing) result.put("missingMetrics", texts(missing));
        if (source.get("approximation") instanceof Map<?, ?> approximation) {
            Map<String, Object> methods = new TreeMap<>();
            for (String metric : List.of("pv", "uv", "uip", "countryStats", "localeCnStats",
                    "networkStats", "uvTypeStats", "topRegionShare")) {
                if (approximation.get(metric) instanceof Map<?, ?> method)
                    methods.put(metric, Collections.unmodifiableMap(scalars(method, Set.of("type", "algorithm", "version"))));
            }
            result.put("approximation", Collections.unmodifiableMap(methods));
        }
        // Envelope dimensionQuality describes its declared summary, not necessarily this row/window.
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> scalars(Map<?, ?> source, Set<String> allowed) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : new TreeSet<>(allowed)) {
            if (!source.containsKey(field)) continue;
            Object value = source.get(field);
            if (value == null || value instanceof Boolean || value instanceof Number) result.put(field, value);
            else if (value instanceof String text) result.put(field, shortText(text));
        }
        return result;
    }

    private static List<String> texts(List<?> source) {
        if (source.size() > 64) throw new IllegalArgumentException("Dimension metadata budget exceeded");
        return source.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(RiskWindowDimensions::shortText).toList();
    }

    private static String shortText(String value) {
        if (value.length() > 512) throw new IllegalArgumentException("Dimension metadata value too long");
        return value;
    }

    private static Double fraction(Object value) {
        if (value == null) return null;
        double number = Double.parseDouble(value.toString());
        if (!Double.isFinite(number) || number < 0 || number > 1)
            throw new IllegalArgumentException("Invalid dimension fraction");
        return number;
    }
}
