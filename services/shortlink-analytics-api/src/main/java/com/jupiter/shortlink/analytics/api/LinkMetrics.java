package com.jupiter.shortlink.analytics.api;

import java.math.BigDecimal;
import java.util.*;

/** Whole-window counts from one deduplicated fact set, including an independent group UV. */
public final class LinkMetrics {
    public static final String KIND = "LINK_METRICS";
    private static final List<String> COUNTERS = List.of("pv", "uv", "uip", "denied");

    private LinkMetrics() {}

    public static String sql(String facts, long start, long end) {
        return "SELECT link_id linkId,grouping(link_id) group_row,"
                + "countIf(kind='CLICK') pv,uniqCombined64If(visitor_hash,kind='CLICK' AND visitor_hash!='') uv,"
                + "uniqCombined64If(ip_hash,kind='CLICK' AND ip_hash!='') uip,"
                + "countIf(kind='REQUEST' AND request_source='REDIRECT' AND decision_stage='BUSINESS' AND status IN (403,429)) denied"
                + " FROM (" + facts + ") WHERE occurred_at>=" + start + " AND occurred_at<" + end
                + " GROUP BY GROUPING SETS ((link_id),()) ORDER BY group_row DESC,link_id";
    }

    public static Map<String, Object> approximation() {
        var all = MetricDimensions.approximation();
        var result = new LinkedHashMap<String, Object>();
        for (String counter : COUNTERS) result.put(counter, all.get(counter));
        return result;
    }

    public record Result(List<Map<String, Object>> items, Map<String, Object> summary) {}

    public static final class Accumulator {
        private final SortedSet<Long> links;
        private final long start, end;
        private final Map<Long, Map<String, Object>> rows = new TreeMap<>();
        private Map<String, Object> summary;

        public Accumulator(List<Long> links, long start, long end) {
            this.links = new TreeSet<>(links);
            this.start = start;
            this.end = end;
            if (this.links.size() > 500) throw new QueryFailure("TOO_LARGE", "Scope exceeds 500 links");
        }

        public void add(Map<String, Object> raw) {
            long group = integer(raw.get("group_row"));
            var report = new LinkedHashMap<String, Object>();
            for (String key : COUNTERS) report.put(key, integer(raw.get(key)));
            report.put("window", "requested");
            report.put("startInclusive", start);
            report.put("endExclusive", end);
            if (group == 1) {
                if (summary != null) throw invalid("Duplicate whole-window summary");
                summary = report;
            } else if (group == 0) {
                long link = integer(raw.get("linkId"));
                if (!links.contains(link)) throw invalid("Unexpected link in whole-window result");
                report.put("linkId", link);
                if (rows.putIfAbsent(link, report) != null) throw invalid("Duplicate whole-window link");
            } else throw invalid("Invalid whole-window grouping marker");
        }

        public Result finish() {
            if (summary == null) throw invalid("Whole-window summary is unavailable");
            long pv = 0, denied = 0;
            List<Map<String, Object>> items = new ArrayList<>();
            try {
                for (long link : links) {
                    Map<String, Object> row = rows.get(link);
                    if (row == null) {
                        row = new LinkedHashMap<>();
                        for (String key : COUNTERS) row.put(key, 0L);
                        row.put("window", "requested");
                        row.put("startInclusive", start);
                        row.put("endExclusive", end);
                        row.put("linkId", link);
                    }
                    pv = Math.addExact(pv, (long) row.get("pv"));
                    denied = Math.addExact(denied, (long) row.get("denied"));
                    items.add(row);
                }
            } catch (ArithmeticException overflow) {
                throw new QueryFailure("TOO_LARGE", "Analytics count exceeds the signed 64-bit contract");
            }
            if (pv != (long) summary.get("pv") || denied != (long) summary.get("denied"))
                throw invalid("Whole-window rows do not cover the group summary");
            return new Result(items, summary);
        }
    }

    private static long integer(Object value) {
        if (value == null) throw invalid("Missing whole-window counter");
        try {
            long result = new BigDecimal(value.toString()).longValueExact();
            if (result < 0) throw invalid("Negative whole-window counter");
            return result;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new QueryFailure("TOO_LARGE", "Analytics integer exceeds the signed 64-bit contract");
        }
    }

    private static QueryFailure invalid(String message) {
        return new QueryFailure("UNAVAILABLE", message);
    }
}
