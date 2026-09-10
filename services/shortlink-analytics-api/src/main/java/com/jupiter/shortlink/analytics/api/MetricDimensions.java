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
        String time = "fromUnixTimestamp64Milli(occurred_at,'Asia/Shanghai')";
        List<String> daily = new ArrayList<>();
        LocalDate first = Instant.ofEpochMilli(start).atZone(ZONE).toLocalDate(),
                last = Instant.ofEpochMilli(end - 1).atZone(ZONE).toLocalDate();
        if (java.time.temporal.ChronoUnit.DAYS.between(first, last) > 180)
            throw new QueryFailure("TOO_LARGE", "Too many calendar dimensions");
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
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
        return ",["
                + String.join(",", daily)
                + "] daily_raw"
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
                + " visitor_hash!='') visitor_raw";
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
        // Geography, network and lifetime-new-visitor features have no collection source in this
        // dataset.
        result.put("localeCnStats", List.of());
        result.put("networkStats", List.of());
        result.put("uvTypeStats", List.of());
        return result;
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
