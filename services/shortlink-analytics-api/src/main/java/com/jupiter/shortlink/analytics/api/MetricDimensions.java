package com.jupiter.shortlink.analytics.api;

import java.time.*;
import java.util.*;

/**
 * Bounded dimensions over the same canonical facts as PV. All report calendar keys use Shanghai
 * time.
 */
public final class MetricDimensions {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private MetricDimensions() {}

    public static String sql(long start, long end) {
        return sql(start, end, true);
    }

    public static String sql(long start, long end, boolean dailyIncluded) {
        String time = "fromUnixTimestamp64Milli(occurred_at,'Asia/Shanghai')";
        List<String> daily = new ArrayList<>();
        LocalDate first = Instant.ofEpochMilli(start).atZone(ZONE).toLocalDate(),
                last = Instant.ofEpochMilli(end - 1).atZone(ZONE).toLocalDate();
        if (java.time.temporal.ChronoUnit.DAYS.between(first, last) > 180)
            throw new QueryFailure("TOO_LARGE", "Too many calendar dimensions");
        for (LocalDate date = first; dailyIncluded && !date.isAfter(last); date = date.plusDays(1)) {
            long from = date.atStartOfDay(ZONE).toInstant().toEpochMilli(),
                    to = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            String condition = "kind='CLICK' AND occurred_at>=" + from + " AND occurred_at<" + to;
            daily.add(
                    "tuple('"
                            + date
                            + "',countIf("
                            + condition
                            + "),uniqCombined64If(visitor_hash,"
                            + condition
                            + " AND visitor_hash!=''),uniqCombined64If(ip_hash,"
                            + condition
                            + " AND ip_hash!=''))");
        }
        return (dailyIncluded ? ",["
                + String.join(",", daily)
                + "] daily_raw" : "")
                + ",sumMapIf([toString(toHour("
                + time
                + "))],[toUInt64(1)],kind='CLICK') hour_raw"
                + ",sumMapIf([toString(toDayOfWeek("
                + time
                + "))],[toUInt64(1)],kind='CLICK')"
                + " weekday_raw,sumMapIf([browser],[toUInt64(1)],kind='CLICK' AND browser NOT IN"
                + " ('','UNKNOWN','Unknown')) browser_raw,sumMapIf([os],[toUInt64(1)],kind='CLICK'"
                + " AND os NOT IN ('','UNKNOWN','Unknown'))"
                + " os_raw,sumMapIf([device],[toUInt64(1)],kind='CLICK' AND device NOT IN"
                + " ('','UNKNOWN','Unknown')) device_raw,topKIf(5,20,'counts')(ip_hash,kind='CLICK'"
                + " AND ip_hash!='') ip_raw,topKIf(1,100,'counts')(visitor_hash,kind='CLICK' AND"
                + " visitor_hash!='') visitor_raw"
                + geographySql();
    }

    public static Map<String, Object> materialize(Map<String, Object> raw, long start, long end) {
        Map<String, Object> result = new LinkedHashMap<>();
        long pv = number(raw.get("pv"));
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Object value : list(raw.get("daily_raw"))) {
            var row = list(value);
            if (row.size() != 4)
                throw new QueryFailure("UNAVAILABLE", "Invalid daily dimension response");
            LocalDate date = LocalDate.parse(row.get(0).toString());
            long from = date.atStartOfDay(ZONE).toInstant().toEpochMilli(),
                    to = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
            if (from < end && to > start)
                daily.add(
                        Map.of(
                                "date",
                                row.get(0),
                                "pv",
                                number(row.get(1)),
                                "uv",
                                number(row.get(2)),
                                "uip",
                                number(row.get(3))));
        }
        if (daily.isEmpty())
            for (LocalDate date = Instant.ofEpochMilli(start).atZone(ZONE).toLocalDate();
                    !date.isAfter(Instant.ofEpochMilli(end - 1).atZone(ZONE).toLocalDate());
                    date = date.plusDays(1))
                daily.add(Map.of("date", date.toString(), "pv", 0L, "uv", 0L, "uip", 0L));
        result.put("daily", daily);
        var hours = histogram(raw.get("hour_raw"), 0, 24);
        result.put("hourStats", hours);
        result.put("weekdayStats", histogram(raw.get("weekday_raw"), 1, 7));
        var browsers = categories(raw.get("browser_raw"), "browser", pv);
        var devices = categories(raw.get("device_raw"), "device", pv);
        result.put("browserStats", browsers);
        result.put("osStats", categories(raw.get("os_raw"), "os", pv));
        result.put("deviceStats", devices);
        var ips = top(raw.get("ip_raw"), "ipHash", pv);
        result.put("topIpStats", ips);
        result.put("topVisitorStats", top(raw.get("visitor_raw"), "visitorHash", pv));
        result.put(
                "peakHourShare",
                ratio(hours.stream().mapToLong(Long::longValue).max().orElse(0), pv));
        result.put("topBrowserShare", browsers.isEmpty() ? null : browsers.get(0).get("ratio"));
        result.put("topDeviceShare", devices.isEmpty() ? null : devices.get(0).get("ratio"));
        result.put("topIpShare", ips.isEmpty() ? null : ips.get(0).get("ratio"));
        var visitors = top(raw.get("visitor_raw"), "visitorHash", pv);
        result.put("topVisitorShare", visitors.isEmpty() ? null : visitors.get(0).get("ratio"));
        result.put(
                "repeatVisitRatio",
                pv == 0 ? 0D : Math.max(0D, 1D - (double) number(raw.get("uv")) / pv));
        result.putAll(materializeGeography(raw));
        result.put("uvTypeStats", List.of());
        return result;
    }

    private static String known(String field) {
        return "(" + field + " NOT IN ('','UNKNOWN','Unknown','unknown','0'))";
    }

    private static String geographySql() {
        StringBuilder sql = new StringBuilder();
        for (String field : List.of("country", "province", "network")) {
            String eligible = "province".equals(field) ? " AND country IN ('CN','中国','China')" : "";
            String valid = known(field);
            sql.append(",sumMapIf([").append(field)
                    .append("],[toUInt64(1)],kind='CLICK'").append(eligible)
                    .append(" AND ").append(valid).append(") ").append(field).append("_raw");
            sql.append(",sumMapIf([if(geo_status IN ('','UNKNOWN','Unknown'),")
                    .append("'LEGACY_OR_UNCOLLECTED',if(geo_status IN ('RESOLVED','OK'),")
                    .append("'FIELD_UNAVAILABLE',geo_status))],[toUInt64(1)],kind='CLICK'")
                    .append(eligible).append(" AND NOT ").append(valid)
                    .append(") ").append(field).append("_unknown_raw");
        }
        return sql.append(",countIf(kind='CLICK' AND country IN ('CN','中国','China')) cn_pv")
                .append(",countIf(kind='CLICK' AND geo_version_count>1) geo_conflict_pv")
                .append(",sumMapIf([if(geo_status='','LEGACY_OR_UNCOLLECTED',geo_status)],")
                .append("[toUInt64(1)],kind='CLICK') geo_status_raw")
                .append(",sumMapIf([if(geo_version='','UNVERSIONED',geo_version)],")
                .append("[toUInt64(1)],kind='CLICK') geo_version_raw").toString();
    }

    /** Ratios use all valid CLICK events; unknown geography is never a real region or ISP. */
    public static Map<String, Object> materializeGeography(Map<String, Object> raw) {
        long pv = number(raw.get("pv"));
        var countries = categories(raw.get("country_raw"), "country", pv);
        var provinces = categories(raw.get("province_raw"), "locale", pv);
        var networks = categories(raw.get("network_raw"), "network", pv);
        var result = new LinkedHashMap<String, Object>();
        result.put("countryStats", countries);
        result.put("localeCnStats", provinces);
        result.put("networkStats", networks);
        result.put("topRegionShare", provinces.isEmpty() ? null : provinces.get(0).get("ratio"));
        result.put("geoStatusStats", categories(raw.get("geo_status_raw"), "status", pv));
        result.put("geoVersionStats", categories(raw.get("geo_version_raw"), "version", pv));
        var dimensions = new LinkedHashMap<String, Object>();
        var country = coverage(countries, pv, counts(raw.get("country_unknown_raw")), "COUNTRY");
        long cnPv = number(raw.get("cn_pv"));
        // Unknown country may conceal CN visits: keep it in the eligible denominator.
        long provinceEligible = cnPv + Math.max(0L, pv - sum(countries));
        var provinceReasons = new LinkedHashMap<>(counts(raw.get("province_unknown_raw")));
        long unknownCountry = Math.max(0L, pv - sum(countries));
        if (unknownCountry > 0) provinceReasons.put("COUNTRY_UNKNOWN", unknownCountry);
        var province = coverage(provinces, provinceEligible, provinceReasons, "CN_PROVINCE");
        dimensions.put("country", country);
        dimensions.put("countryStats", country);
        dimensions.put("localeCnStats", province);
        dimensions.put("topRegionShare", province);
        dimensions.put("networkStats", coverage(networks, pv,
                counts(raw.get("network_unknown_raw")), "ISP"));
        dimensions.put("uvTypeStats", Map.of("status", "UNKNOWN", "semantic",
                "FIRST_OBSERVED_IN_RETAINED_DATASET", "reason", "HISTORY_NOT_COVERED",
                "maxHistoryDays", 180));
        long conflicts = number(raw.get("geo_conflict_pv"));
        if (conflicts > 0) {
            for (String key : List.of("country", "countryStats", "localeCnStats", "topRegionShare", "networkStats")) {
                @SuppressWarnings("unchecked") var existing = (Map<String, Object>) dimensions.get(key);
                var revised = new LinkedHashMap<>(existing);
                revised.put("status", "PARTIAL");
                revised.put("versionConflictCount", conflicts);
                revised.put("reason", "GEO_VERSION_CONFLICT");
                dimensions.put(key, revised);
            }
            result.put("topRegionShare", null);
        }
        result.put("geoSelectionPolicy", "COHERENT_TUPLE_NONEMPTY_VERSION_THEN_LEXICOGRAPHIC");
        result.put("dimensionQuality", dimensions);
        return result;
    }

    private static long sum(List<Map<String, Object>> values) {
        return values.stream().mapToLong(row -> number(row.get("cnt"))).sum();
    }

    private static Map<String, Object> coverage(List<Map<String, Object>> values, long total,
            Map<String, Long> reasons, String semantic) {
        long known = sum(values), unknown = Math.max(0L, total - known);
        var result = new LinkedHashMap<String, Object>();
        result.put("status", total == 0 ? "EMPTY" : known == 0 ? "UNKNOWN"
                : unknown > 0 ? "PARTIAL" : "AVAILABLE");
        result.put("knownCount", known);
        result.put("unknownCount", unknown);
        result.put("eligibleCount", total);
        result.put("coverage", total == 0 ? null : ratio(known, total));
        result.put("semantic", semantic);
        var fixedReasons = new LinkedHashMap<>(reasons);
        long explained = reasons.values().stream().mapToLong(Long::longValue).sum();
        if (unknown > explained) fixedReasons.put("LEGACY_OR_UNCOLLECTED", unknown - explained);
        result.put("reasonCounts", fixedReasons);
        result.put("historicalBackfill", "REQUIRES_RETAINED_RAW_AND_VERSIONED_REBUILD");
        return result;
    }

    public static List<String> missingMetrics(Map<String, Object> dimensions) {
        List<String> missing = new ArrayList<>();
        for (String key : List.of("country", "localeCnStats", "topRegionShare", "networkStats", "uvTypeStats")) {
            if (!(dimensions.get(key) instanceof Map<?, ?> quality)
                    || !Set.of("AVAILABLE", "EMPTY").contains(quality.get("status"))) missing.add(key);
        }
        return missing;
    }

    public static Map<String, Object> approximation() {
        var result = new LinkedHashMap<String, Object>();
        for (String metric : List.of("pv", "denied", "daily.pv", "hourStats", "weekdayStats", "browserStats",
                "osStats", "deviceStats", "peakHourShare", "topBrowserShare", "topDeviceShare",
                "countryStats", "localeCnStats", "networkStats", "topRegionShare"))
            result.put(metric, Map.of("type", "EXACT", "algorithm", "eventId-dedup", "version", "click-v1"));
        for (String metric : List.of("uv", "uip", "daily.uv", "daily.uip", "repeatVisitRatio", "uvTypeStats"))
            result.put(metric, Map.of("type", "APPROXIMATE", "algorithm", "uniqCombined64", "version", "click-v1"));
        for (String metric : List.of("topIpStats", "topVisitorStats", "topIpShare", "topVisitorShare"))
            result.put(metric, Map.of("type", "APPROXIMATE", "algorithm", "topK-counts", "version", "click-v1"));
        return result;
    }

    /** Quality of a frozen access-record set, independent of subsequent page size. */
    public static Map<String, Object> recordDimensions(List<Map<String, Object>> rows) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("pv", (long) rows.size());
        for (String field : List.of("country", "province", "network", "geoStatus", "geoVersion")) {
            Map<String, Long> values = new TreeMap<>(), reasons = new TreeMap<>();
            for (var row : rows) {
                String country = Objects.toString(row.get("country"), "");
                if (field.equals("province") && !Set.of("CN", "中国", "China").contains(country)) continue;
                String value = Objects.toString(row.get(field), "");
                if (!Set.of("", "UNKNOWN", "Unknown", "unknown", "0").contains(value))
                    values.merge(value, 1L, Long::sum);
                else reasons.merge(Objects.toString(row.get("geoStatus"), "LEGACY_OR_UNCOLLECTED"), 1L, Long::sum);
            }
            String key = field.equals("geoStatus") ? "geo_status" : field.equals("geoVersion") ? "geo_version" : field;
            raw.put(key + "_raw", List.of(new ArrayList<>(values.keySet()), new ArrayList<>(values.values())));
            raw.put(key + "_unknown_raw", List.of(new ArrayList<>(reasons.keySet()), new ArrayList<>(reasons.values())));
        }
        raw.put("cn_pv", rows.stream().filter(row -> Set.of("CN", "中国", "China")
                .contains(Objects.toString(row.get("country"), ""))).count());
        raw.put("geo_conflict_pv", rows.stream().filter(row -> "1".equals(Objects.toString(row.get("geoVersionConflict"), "0"))).count());
        return materializeGeography(raw);
    }

    private static List<Long> histogram(Object value, int first, int length) {
        Map<String, Long> map = counts(value);
        List<Long> result = new ArrayList<>();
        for (int i = first; i < first + length; i++)
            result.add(map.getOrDefault(Integer.toString(i), 0L));
        return result;
    }

    private static List<Map<String, Object>> categories(Object value, String key, long pv) {
        List<Map<String, Object>> rows = new ArrayList<>();
        counts(value)
                .forEach(
                        (name, count) ->
                                rows.add(
                                        Map.of(
                                                key,
                                                name,
                                                "cnt",
                                                count,
                                                "ratio",
                                                ratio(count, pv))));
        rows.sort(
                Comparator.<Map<String, Object>>comparingLong(r -> number(r.get("cnt")))
                        .reversed()
                        .thenComparing(r -> r.get(key).toString()));
        return rows;
    }

    private static List<Map<String, Object>> top(Object value, String key, long pv) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object entry : list(value)) {
            var tuple =
                    entry instanceof Map<?, ?> named
                            ? List.of(named.get("item"), named.get("count"), named.get("error"))
                            : list(entry);
            if (tuple.size() != 3)
                throw new QueryFailure("UNAVAILABLE", "Invalid frequent-items response");
            long count = number(tuple.get(1)), error = number(tuple.get(2));
            rows.add(
                    Map.of(
                            key,
                            tuple.get(0),
                            "cnt",
                            count,
                            "error",
                            error,
                            "ratio",
                            ratio(count, pv),
                            "approximate",
                            true));
        }
        return rows;
    }

    private static Map<String, Long> counts(Object value) {
        var tuple = list(value);
        if (tuple.isEmpty()) return Map.of();
        if (tuple.size() != 2) throw new QueryFailure("UNAVAILABLE", "Invalid histogram response");
        var keys = list(tuple.get(0));
        var values = list(tuple.get(1));
        if (keys.size() != values.size())
            throw new QueryFailure("UNAVAILABLE", "Invalid histogram response");
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++)
            map.put(keys.get(i).toString(), number(values.get(i)));
        return map;
    }

    private static List<?> list(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list;
        throw new QueryFailure("UNAVAILABLE", "Invalid dimension response");
    }

    private static long number(Object value) {
        return value == null ? 0 : AnalyticsQueryService.number(value);
    }

    private static double ratio(long count, long total) {
        return total == 0 ? 0D : Math.min(1D, (double) count / total);
    }
}
