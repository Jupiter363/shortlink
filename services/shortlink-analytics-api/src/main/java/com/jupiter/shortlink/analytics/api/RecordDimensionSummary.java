package com.jupiter.shortlink.analytics.api;

import java.util.*;

/** Bounded job summary retains category/visitor keys, never the entire streamed record payload. */
public final class RecordDimensionSummary {
    private final int maxRows;
    private final Map<String, Map<String, Long>> histograms = new HashMap<>();
    private final Map<String, String> visitors = new HashMap<>();
    private long pv, cnPv, conflicts, missingVisitor, earliest;

    public RecordDimensionSummary(int maxRows) {
        if (maxRows < 1) throw new IllegalArgumentException("Record summary budget must be positive");
        this.maxRows = maxRows;
    }

    public void add(Map<String, Object> row) {
        if (pv >= maxRows) throw new QueryFailure("TOO_LARGE", "Record summary budget exceeded");
        pv++;
        String country = value(row, "country"), status = value(row, "geoStatus"), version = value(row, "geoVersion");
        boolean cn = Set.of("CN", "中国", "China").contains(country);
        if (cn) cnPv++;
        if ("1".equals(value(row, "geoVersionConflict"))) conflicts++;
        for (String field : List.of("country", "province", "network")) {
            if (field.equals("province") && !cn) continue;
            String value = value(row, field);
            if (!Set.of("", "UNKNOWN", "Unknown", "unknown", "0").contains(value)) count(field, value);
            else count(field + "_unknown", Set.of("", "UNKNOWN", "Unknown").contains(status)
                    ? "LEGACY_OR_UNCOLLECTED" : Set.of("RESOLVED", "OK").contains(status) ? "FIELD_UNAVAILABLE" : status);
        }
        count("geo_status", status.isEmpty() ? "LEGACY_OR_UNCOLLECTED" : status);
        count("geo_version", version.isEmpty() ? "UNVERSIONED" : version);
        String visitor = value(row, "visitorHash");
        if (visitor.isEmpty()) missingVisitor++;
        else {
            String type = value(row, "uvType");
            visitors.merge(visitor, type, (left, right) -> left.equals(right) ? left : "UNKNOWN");
        }
        Object observed = row.get("historyEarliestObservedAt");
        long time = observed == null ? 0 : Long.parseLong(observed.toString());
        if (time > 0 && (earliest == 0 || time < earliest)) earliest = time;
    }

    public Map<String, Object> materialize(VisitorHistory.Plan history) {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("pv", pv);
        raw.put("cn_pv", cnPv);
        raw.put("geo_conflict_pv", conflicts);
        histograms.forEach((key, value) -> raw.put(key + "_raw",
                List.of(new ArrayList<>(value.keySet()), new ArrayList<>(value.values()))));
        long fresh = visitors.values().stream().filter("newUser"::equals).count();
        long old = visitors.values().stream().filter("oldUser"::equals).count();
        raw.put("uv", (long) visitors.size());
        raw.put("scope_new_uv", fresh);
        raw.put("scope_old_uv", old);
        raw.put("scope_unknown_uv", visitors.size() - fresh - old);
        raw.put("missing_visitor_clicks", missingVisitor);
        raw.put("history_earliest_raw", earliest);
        var result = MetricDimensions.materializeGeography(raw);
        VisitorHistory.materialize(result, raw, history, true);
        return result;
    }

    private void count(String key, String value) {
        histograms.computeIfAbsent(key, ignored -> new TreeMap<>()).merge(value, 1L, Long::sum);
    }
    private static String value(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
}
