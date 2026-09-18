package com.jupiter.shortlink.analytics.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Bounded exact click partitions with independent whole-window distinct visitor counts. */
public final class DimensionBreakdown {
    public static final String KIND = "DIMENSION_BREAKDOWN";
    public static final int MAX_BUCKETS = 5000;
    public static final Set<String> DIMENSIONS = Set.of("day", "hour", "weekday", "country",
            "province", "device", "os", "browser", "isp", "refererDomain");
    private static final Set<String> GEO = Set.of("country", "province", "isp");
    private static final Set<String> STATES = Set.of("KNOWN", "UNKNOWN", "NOT_APPLICABLE");
    private static final List<String> COUNTERS = List.of("pv", "uv", "uip");

    private DimensionBreakdown() {}

    public record Options(List<String> dimensions, List<DimensionFilter> filters) {
        public List<Map<String, Object>> filterMaps() {
            return filters.stream().map(filter -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dimension", filter.dimension());
                row.put("operator", filter.operator());
                if (filter.values() != null) row.put("values", filter.values());
                return row;
            }).toList();
        }
    }

    public static void validateRequest(QueryRequest q) {
        if (KIND.equals(q.kind())) {
            options(q);
            if (q.windows() != null || q.endPolicy() != null && !"REQUESTED".equals(q.endPolicy()))
                throw invalid("DIMENSION_BREAKDOWN requires one fixed requested interval");
        } else if (q.dimensions() != null || q.filters() != null) {
            throw invalid("Dimension fields require DIMENSION_BREAKDOWN");
        }
    }

    public static Options options(QueryRequest q) {
        var dimensions = q.dimensions();
        if (dimensions == null || dimensions.isEmpty() || dimensions.size() > 3)
            throw invalid("Choose between one and three dimensions");
        Set<String> seen = new HashSet<>();
        for (String dimension : dimensions)
            if (dimension == null || !DIMENSIONS.contains(dimension) || !seen.add(dimension))
                throw invalid("Dimensions must be unique supported names");
        var filters = q.filters() == null ? List.<DimensionFilter>of() : q.filters();
        if (filters.size() > 8) throw invalid("At most eight dimension filters are allowed");
        var normalized = new TreeMap<String, DimensionFilter>();
        for (var filter : filters) {
            if (filter == null || filter.dimension() == null || !DIMENSIONS.contains(filter.dimension())
                    || normalized.containsKey(filter.dimension()))
                throw invalid("Filters must use unique supported dimensions");
            if ("IS_UNKNOWN".equals(filter.operator())) {
                if (filter.values() != null && !filter.values().isEmpty())
                    throw invalid("IS_UNKNOWN does not accept values");
                normalized.put(filter.dimension(), new DimensionFilter(filter.dimension(), "IS_UNKNOWN", null));
            } else if ("IN".equals(filter.operator())) {
                if (filter.values() == null || filter.values().isEmpty() || filter.values().size() > 20)
                    throw invalid("IN requires between one and twenty values");
                SortedSet<String> values = new TreeSet<>();
                for (String value : filter.values()) {
                    validateValue(filter.dimension(), value);
                    values.add(value);
                }
                normalized.put(filter.dimension(), new DimensionFilter(filter.dimension(), "IN", List.copyOf(values)));
            } else throw invalid("Only IN and IS_UNKNOWN filters are supported");
        }
        return new Options(List.copyOf(dimensions), List.copyOf(normalized.values()));
    }

    private static void validateValue(String dimension, String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl))
            throw invalid("Filter values must be nonempty text of at most 256 characters");
        if (Set.of("hour", "weekday").contains(dimension)) {
            if (!value.matches("0|[1-9][0-9]?")) throw invalid("Calendar filter values must be integer text");
            int number = Integer.parseInt(value);
            if ("hour".equals(dimension) ? number > 23 : number < 1 || number > 7)
                throw invalid("Calendar filter value is out of range");
        } else if ("day".equals(dimension)) {
            try {
                if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(value).toString().equals(value))
                    throw invalid("Day filters require an ISO calendar date");
            } catch (DateTimeParseException failure) {
                throw invalid("Day filters require an ISO calendar date");
            }
        }
    }

    public static String sql(String facts, long start, long end, Options options) {
        List<String> keys = new ArrayList<>();
        for (String dimension : options.dimensions()) {
            String state = state(dimension);
            keys.add(state);
            keys.add("if(" + state + "='KNOWN'," + value(dimension) + ",'')");
        }
        StringBuilder predicate = new StringBuilder("kind='CLICK' AND occurred_at>=")
                .append(start).append(" AND occurred_at<").append(end);
        for (var filter : options.filters()) {
            String state = state(filter.dimension());
            if ("IS_UNKNOWN".equals(filter.operator())) predicate.append(" AND ").append(state).append("='UNKNOWN'");
            else predicate.append(" AND (").append(state).append("='KNOWN' AND ").append(value(filter.dimension()))
                    .append(" IN (").append(String.join(",", filter.values().stream().map(ClickHouseReader::quote).toList())).append("))");
        }
        return "SELECT bucket_key,grouping(bucket_key) group_row,count() pv,"
                + "uniqCombined64If(visitor_hash,visitor_hash!='') uv,uniqCombined64If(ip_hash,ip_hash!='') uip,"
                + "countIf(geo_version_count>1) geo_conflicts,countIf(NOT " + known("country", true) + ") country_unknown"
                + " FROM (SELECT *,[" + String.join(",", keys) + "] bucket_key FROM (" + facts + ") WHERE " + predicate + ")"
                + " GROUP BY GROUPING SETS ((bucket_key),()) ORDER BY group_row DESC,pv DESC,bucket_key LIMIT " + (MAX_BUCKETS + 2);
    }

    private static String value(String dimension) {
        String time = "fromUnixTimestamp64Milli(occurred_at,'Asia/Shanghai')";
        return switch (dimension) {
            case "day" -> "toString(toDate(" + time + "))";
            case "hour" -> "toString(toHour(" + time + "))";
            case "weekday" -> "toString(toDayOfWeek(" + time + "))";
            case "isp" -> "network";
            case "refererDomain" -> "referer_domain";
            case "country", "province", "device", "os", "browser" -> dimension;
            default -> throw invalid("Unsupported dimension");
        };
    }

    private static String state(String dimension) {
        return switch (dimension) {
            case "day", "hour", "weekday" -> "'KNOWN'";
            case "province" -> "if(NOT " + known("country", true) + ",'UNKNOWN',if(country NOT IN ('CN','中国','China'),"
                    + "'NOT_APPLICABLE',if(" + known("province", true) + ",'KNOWN','UNKNOWN')))";
            case "refererDomain" -> "if(referer_domain='','UNKNOWN','KNOWN')";
            default -> "if(" + known(value(dimension), GEO.contains(dimension)) + ",'KNOWN','UNKNOWN')";
        };
    }

    private static String known(String field, boolean geographic) {
        return "(" + field + " NOT IN ('','UNKNOWN','Unknown','unknown'" + (geographic ? ",'0'" : "") + "))";
    }

    public static Map<String, Object> approximation() {
        var all = MetricDimensions.approximation();
        var result = new LinkedHashMap<String, Object>();
        for (String key : COUNTERS) result.put(key, all.get(key));
        result.put("pvRatio", all.get("pv"));
        return result;
    }

    public static List<String> missingMetrics(Map<String, Object> quality) {
        return quality.entrySet().stream().filter(entry -> !(entry.getValue() instanceof Map<?, ?> row)
                || !Set.of("AVAILABLE", "EMPTY").contains(row.get("status"))).map(Map.Entry::getKey).toList();
    }

    public static void metadata(Map<String, Object> meta, Options options, long totalRows) {
        meta.put("dimensions", options.dimensions());
        meta.put("filters", options.filterMaps());
        meta.put("totalRows", totalRows);
        meta.put("resultComplete", true);
        meta.put("truncated", false);
        meta.put("aggregationLevel", KIND);
        meta.put("dimensionQualityScope", "FILTERED_FULL_WINDOW");
    }

    public record Result(List<Map<String, Object>> items, Map<String, Object> summary) {}

    public static final class Accumulator {
        private final Options options;
        private final long start, end;
        private final Map<List<String>, Map<String, Object>> buckets = new TreeMap<>(DimensionBreakdown::compareKeys);
        private Map<String, Object> summary;
        private long geoConflicts, countryUnknown;

        public Accumulator(Options options, long start, long end) {
            this.options = options;
            this.start = start;
            this.end = end;
        }

        public void add(Map<String, Object> raw) {
            long group = integer(raw.get("group_row"));
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String key : COUNTERS) counts.put(key, integer(raw.get(key)));
            if (group == 1) {
                if (summary != null) throw unavailable("Duplicate dimension summary");
                geoConflicts = integer(raw.get("geo_conflicts"));
                countryUnknown = integer(raw.get("country_unknown"));
                summary = counts;
                return;
            }
            if (group != 0 || !(raw.get("bucket_key") instanceof List<?> key)
                    || key.size() != options.dimensions().size() * 2 || (long) counts.get("pv") == 0)
                throw unavailable("Invalid dimension bucket");
            List<String> canonicalKey = new ArrayList<>();
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < options.dimensions().size(); i++) {
                String dimension = options.dimensions().get(i);
                if (!(key.get(i * 2) instanceof String state) || !STATES.contains(state)
                        || !(key.get(i * 2 + 1) instanceof String value)
                        || "NOT_APPLICABLE".equals(state) && !"province".equals(dimension)
                        || ("KNOWN".equals(state) ? value.isEmpty() : !value.isEmpty()))
                    throw unavailable("Invalid dimension value state");
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("value", "KNOWN".equals(state) ? value : null);
                cell.put("state", state);
                values.put(dimension, cell);
                canonicalKey.add(state); canonicalKey.add(value);
            }
            counts.put("dimensions", values);
            if (buckets.putIfAbsent(List.copyOf(canonicalKey), counts) != null)
                throw unavailable("Duplicate dimension bucket");
            if (buckets.size() > MAX_BUCKETS) throw new QueryFailure("TOO_LARGE", "Dimension breakdown exceeds 5000 buckets");
        }

        public Result finish() {
            if (summary == null) throw unavailable("Dimension summary is unavailable");
            long total = (long) summary.get("pv"), sum = 0;
            long[][] coverage = new long[options.dimensions().size()][3];
            List<Map<String, Object>> items = new ArrayList<>();
            try {
                for (var bucket : buckets.entrySet()) {
                    long pv = (long) bucket.getValue().get("pv");
                    sum = Math.addExact(sum, pv);
                    for (int i = 0; i < coverage.length; i++) {
                        int state = switch (bucket.getKey().get(i * 2)) {
                            case "KNOWN" -> 0;
                            case "UNKNOWN" -> 1;
                            default -> 2;
                        };
                        coverage[i][state] = Math.addExact(coverage[i][state], pv);
                    }
                    bucket.getValue().put("pvRatio", total == 0 ? null : (double) pv / total);
                    items.add(bucket.getValue());
                }
            } catch (ArithmeticException overflow) {
                throw new QueryFailure("TOO_LARGE", "Dimension count exceeds the signed 64-bit contract");
            }
            if (sum != total || geoConflicts > total || countryUnknown > total)
                throw unavailable("Dimension buckets do not cover the full summary");
            // The TreeMap establishes a deterministic value tie-breaker before the stable PV sort.
            items.sort(Comparator.<Map<String, Object>>comparingLong(row -> (long) row.get("pv")).reversed());
            Map<String, Object> qualities = new LinkedHashMap<>();
            for (int i = 0; i < coverage.length; i++) {
                String dimension = options.dimensions().get(i);
                long known = coverage[i][0], unknown = coverage[i][1], notApplicable = coverage[i][2];
                long eligible = total - notApplicable;
                Map<String, Object> quality = new LinkedHashMap<>();
                quality.put("status", eligible == 0 ? "EMPTY" : known == 0 ? "UNKNOWN" : unknown > 0 ? "PARTIAL" : "AVAILABLE");
                quality.put("knownCount", known); quality.put("unknownCount", unknown);
                quality.put("eligibleCount", eligible); quality.put("notApplicableCount", notApplicable);
                quality.put("coverage", eligible == 0 ? null : (double) known / eligible);
                quality.put("semantic", switch (dimension) {
                    case "province" -> "CN_PROVINCE";
                    case "refererDomain" -> "REFERRER_DOMAIN_NOT_CHANNEL_ATTRIBUTION";
                    case "isp" -> "ISP";
                    default -> dimension.toUpperCase(Locale.ROOT);
                });
                Map<String, Long> reasons = new LinkedHashMap<>();
                long unknownCountry = "province".equals(dimension) ? countryUnknown : 0;
                if (unknownCountry > unknown) throw unavailable("Province unknown coverage is inconsistent");
                if (unknownCountry > 0) reasons.put("COUNTRY_UNKNOWN", unknownCountry);
                if (unknown > unknownCountry) reasons.put("UNKNOWN_VALUE", unknown - unknownCountry);
                quality.put("reasonCounts", reasons);
                if (GEO.contains(dimension) && geoConflicts > 0) {
                    quality.put("status", "PARTIAL");
                    quality.put("reason", "GEO_VERSION_CONFLICT");
                    quality.put("versionConflictCount", geoConflicts);
                }
                qualities.put(dimension, quality);
            }
            summary.put("window", "requested"); summary.put("startInclusive", start); summary.put("endExclusive", end);
            summary.put("ratioDenominator", total); summary.put("dimensionQuality", qualities);
            return new Result(items, summary);
        }
    }

    private static int compareKeys(List<String> left, List<String> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int comparison = left.get(i).compareTo(right.get(i));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static long integer(Object value) {
        if (value == null) throw unavailable("Missing dimension counter");
        try {
            long result = new BigDecimal(value.toString()).longValueExact();
            if (result < 0) throw unavailable("Negative dimension counter");
            return result;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new QueryFailure("TOO_LARGE", "Dimension integer exceeds the signed 64-bit contract");
        }
    }

    private static QueryFailure invalid(String message) { return new QueryFailure("INVALID_QUERY", message); }
    private static QueryFailure unavailable(String message) { return new QueryFailure("UNAVAILABLE", message); }
}
