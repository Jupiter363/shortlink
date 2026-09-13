package com.jupiter.shortlink.analytics.api;

import static com.jupiter.shortlink.analytics.api.ClickHouseReader.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.SourceCut;
import com.jupiter.shortlink.contract.Topics;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** First observation in bounded retained facts, never a claim about lifetime visitor identity. */
public final class VisitorHistory {
    public static final long RETENTION = 180L * 86_400_000;
    public static final String SEMANTIC = "FIRST_OBSERVED_IN_RETAINED_DATASET";
    public static final long PROOF_TIMEOUT_MILLIS = 1000;
    private VisitorHistory() {}

    public record Plan(boolean available, String predicate, long start, long end, String reason,
            List<SourceCut.Range> ranges, String proof) {
        public Plan(boolean available, String predicate, long start, long end, String reason) {
            this(available, predicate, start, end, reason, List.of(), "UNPROVEN");
        }
        public Plan(boolean available, String predicate, long start, long end, String reason, List<SourceCut.Range> ranges) {
            this(available, predicate, start, end, reason, ranges, "UNPROVEN");
        }
    }

    public static Plan plan(Object sourceCut, ObjectMapper json, long snapshotTime, long end) {
        long start = Math.max(0, snapshotTime - RETENTION);
        try {
            List<SourceCut> cuts = new ArrayList<>();
            collect(sourceCut, json, cuts);
            Map<String, SourceCut.Range> ranges = new TreeMap<>();
            for (SourceCut cut : cuts) for (var range : cut.ranges()) {
                // Visitor identity comes only from clicks; lagging request/denial logs do not
                // change a visitor's first observation and cannot gate this evidence.
                if (!Topics.CLICK_RAW.equals(range.topic())) continue;
                if (range.start() != 0) return new Plan(false, "0", start, end, "HISTORY_NOT_COVERED");
                String key = range.clusterId() + "\u0000" + range.topicId() + "\u0000" + range.partition();
                var previous = ranges.get(key);
                if (previous == null || range.end() > previous.end()) ranges.put(key, range);
            }
            if (ranges.isEmpty() || end <= start)
                return new Plan(false, "0", start, end, "HISTORY_NOT_COVERED");
            List<String> predicates = new ArrayList<>();
            for (var range : ranges.values()) predicates.add("(cluster_id=" + quote(range.clusterId())
                    + " AND topic_id=" + quote(range.topicId()) + " AND source_topic=" + quote(Topics.CLICK_RAW)
                    + " AND source_partition=" + range.partition()
                    + " AND source_offset>=0 AND source_offset<" + range.end() + ")");
            return new Plan(true, "(" + String.join(" OR ", predicates) + ")", start, end, "", List.copyOf(ranges.values()));
        } catch (RuntimeException invalid) {
            return new Plan(false, "0", start, end, "HISTORY_NOT_COVERED");
        }
    }

    public static String visibilitySql(Plan plan) {
        return "SELECT cluster_id,topic_id,source_partition,uniqExact(source_offset) receipts"
                + " FROM event_receipts WHERE " + plan.predicate()
                + " GROUP BY cluster_id,topic_id,source_partition";
    }

    /** Only the optional read may degrade; proof validation and authorization stay fail closed. */
    public static Plan optionalProof(Plan plan, Supplier<List<Map<String, Object>>> read,
            Function<List<Map<String, Object>>, Plan> verify) {
        if (Thread.currentThread().isInterrupted())
            throw new QueryFailure("UNAVAILABLE", "History proof interrupted");
        if (!plan.available()) return plan;
        List<Map<String, Object>> visible;
        try {
            visible = read.get();
        } catch (QueryFailure failure) {
            if (Thread.currentThread().isInterrupted()
                    || !Set.of("TOO_LARGE", "UNAVAILABLE").contains(failure.code)) throw failure;
            return new Plan(false, "0", plan.start(), plan.end(),
                    "TOO_LARGE".equals(failure.code) ? "HISTORY_PROOF_BUDGET_EXCEEDED" : "HISTORY_PROOF_UNAVAILABLE",
                    plan.ranges(), "UNPROVEN");
        }
        if (Thread.currentThread().isInterrupted())
            throw new QueryFailure("UNAVAILABLE", "History proof interrupted");
        return verify.apply(visible);
    }

    /** A first-seen claim requires every receipt in the frozen prefix, not merely this visitor. */
    public static Plan verifyVisibility(Plan plan, List<Map<String, Object>> visible) {
        if (!plan.available()) return plan;
        Map<String, Long> actual = new HashMap<>();
        for (var row : visible) actual.put(row.get("cluster_id") + "\u0000" + row.get("topic_id")
                + "\u0000" + row.get("source_partition"), number(row.get("receipts")));
        if (plan.ranges().isEmpty() || plan.ranges().stream().anyMatch(range ->
                actual.getOrDefault(range.clusterId() + "\u0000" + range.topicId() + "\u0000" + range.partition(), 0L)
                        != range.end()))
            return new Plan(false, "0", plan.start(), plan.end(), "HISTORY_RECEIPTS_INCOMPLETE", plan.ranges());
        return new Plan(true, plan.predicate(), plan.start(), plan.end(), "", plan.ranges(), "CONTIGUOUS_OFFSET_PREFIX");
    }

    public static Plan verifyVisibility(Plan plan, List<Map<String, Object>> visible,
            Object trustedCut, Object trustedReceipts, ObjectMapper json, boolean sameEpoch) {
        if (plan.available() && sameEpoch && trustedReceipts instanceof List<?> expectedRows) {
            Plan expectedPlan = plan(trustedCut, json, plan.start(), plan.end());
            if (expectedPlan.available() && expectedPlan.ranges().equals(plan.ranges())) {
                Map<String, Long> actual = new HashMap<>(), expected = new HashMap<>();
                Set<String> clickKeys = new HashSet<>();
                for (var range : plan.ranges()) clickKeys.add(range.clusterId() + "\u0000" + range.topicId() + "\u0000" + range.partition());
                for (var row : visible) actual.put(row.get("cluster_id") + "\u0000" + row.get("topic_id")
                        + "\u0000" + row.get("source_partition"), number(row.get("receipts")));
                try {
                    for (Object value : expectedRows) {
                        if (!(value instanceof Map<?, ?> row)) continue;
                        String key = row.get("clusterId") + "\u0000" + row.get("topicId") + "\u0000" + row.get("partition");
                        if (clickKeys.contains(key)) expected.put(key, number(row.get("count")));
                    }
                    boolean complete = !expected.isEmpty() && plan.ranges().stream().allMatch(range -> {
                        String key = range.clusterId() + "\u0000" + range.topicId() + "\u0000" + range.partition();
                        return expected.containsKey(key) && expected.get(key).equals(actual.getOrDefault(key, 0L));
                    });
                    return new Plan(complete, complete ? plan.predicate() : "0", plan.start(), plan.end(),
                            complete ? "" : "HISTORY_RECEIPTS_INCOMPLETE", plan.ranges(),
                            complete ? "WORKER_RECEIPT_COUNTS" : "UNPROVEN");
                } catch (RuntimeException invalid) { /* Malformed optional evidence cannot authorize history. */ }
            }
        }
        return verifyVisibility(plan, visible);
    }

    private static void collect(Object value, ObjectMapper json, List<SourceCut> result) {
        if (value instanceof SourceCut cut) result.add(cut);
        else if (value instanceof Map<?, ?> map) {
            if (map.containsKey("ranges")) result.add(json.convertValue(map, SourceCut.class));
            else for (Object nested : map.values()) collect(nested, json, result);
        } else if (value instanceof List<?> list) {
            for (Object nested : list) collect(nested, json, result);
        } else if (value instanceof String text) {
            try { collect(json.readValue(text, Map.class), json, result); }
            catch (Exception invalid) { throw new IllegalArgumentException("Invalid frozen history cut"); }
        } else throw new IllegalArgumentException("Invalid frozen history cut");
    }

    public static String enrich(String currentFacts, Plan plan, String tenant, List<Long> linkIds) {
        if (!plan.available()) return "SELECT *,toInt64(0) scope_first_seen,toInt64(0) link_first_seen,"
                + "toUInt8(0) scope_history_known,toUInt8(0) link_history_known,toInt64(0) history_earliest"
                + " FROM (" + currentFacts + ")";
        String ids = linkIds.isEmpty() ? "0" : String.join(",", linkIds.stream().map(String::valueOf).toList());
        String history = "SELECT * FROM (SELECT kind,event_id,any(link_id) link_id,"
                + "any(occurred_at) occurred_at,any(visitor_hash) visitor_hash FROM event_receipts WHERE "
                + plan.predicate() + " AND tenant_id=" + quote(tenant)
                + " AND validation_result='VALID' GROUP BY kind,event_id HAVING uniqExact(payload_hash)=1)"
                + " WHERE kind='CLICK' AND visitor_hash!='' AND link_id IN (" + ids + ")"
                + " AND occurred_at>=" + plan.start() + " AND occurred_at<" + plan.end();
        String currentColumns = String.join(",", List.of("kind", "tenant_id", "event_id", "link_id", "occurred_at",
                "visitor_hash", "ip_hash", "browser", "os", "device", "country", "province", "city", "network",
                "geo_status", "geo_version", "geo_version_count", "referer_domain", "request_source", "decision_stage", "status")
                .stream().map(field -> "current." + field + " AS " + field).toList());
        return "WITH retained_history AS (" + history + ") SELECT " + currentColumns + ","
                + "hs.first_seen scope_first_seen,hl.first_seen link_first_seen,"
                + "(hs.visitor_hash!='') scope_history_known,(hl.visitor_hash!='') link_history_known,"
                + "hs.earliest history_earliest FROM (" + currentFacts + ") current"
                + " LEFT JOIN (SELECT visitor_hash,min(occurred_at) first_seen,"
                + "min(min(occurred_at)) OVER () earliest FROM retained_history GROUP BY visitor_hash) hs"
                + " ON current.visitor_hash=hs.visitor_hash"
                + " LEFT JOIN (SELECT link_id,visitor_hash,min(occurred_at) first_seen FROM retained_history"
                + " GROUP BY link_id,visitor_hash) hl ON current.link_id=hl.link_id"
                + " AND current.visitor_hash=hl.visitor_hash";
    }

    public static String metricsSql(String windowStart) {
        return metricsSql(windowStart, "");
    }

    public static String metricsSql(String windowStart, String prefix) {
        StringBuilder sql = new StringBuilder();
        for (String scope : List.of("scope", "link")) {
            String known = scope + "_history_known";
            String first = scope + "_first_seen";
            sql.append(",uniqCombined64If(visitor_hash,kind='CLICK' AND visitor_hash!='' AND ")
                    .append(known).append(" AND ").append(first).append(">=").append(windowStart)
                    .append(") ").append(prefix).append(scope).append("_new_uv");
            sql.append(",uniqCombined64If(visitor_hash,kind='CLICK' AND visitor_hash!='' AND ")
                    .append(known).append(" AND ").append(first).append("<").append(windowStart)
                    .append(") ").append(prefix).append(scope).append("_old_uv");
            sql.append(",uniqCombined64If(visitor_hash,kind='CLICK' AND visitor_hash!='' AND NOT ")
                    .append(known).append(") ").append(prefix).append(scope).append("_unknown_uv");
        }
        return sql.append(",countIf(kind='CLICK' AND visitor_hash='') ").append(prefix).append("missing_visitor_clicks")
                .append(",minIf(history_earliest,scope_history_known) ").append(prefix).append("history_earliest_raw").toString();
    }

    @SuppressWarnings("unchecked")
    public static void materialize(Map<String, Object> target, Map<String, Object> raw, Plan plan, boolean group) {
        String scope = group ? "scope" : "link";
        long newUv = number(raw.get(scope + "_new_uv")), oldUv = number(raw.get(scope + "_old_uv"));
        long unknown = number(raw.get(scope + "_unknown_uv"));
        long uv = number(raw.get("uv")), missingClicks = number(raw.get("missing_visitor_clicks"));
        if (!plan.available()) unknown = uv;
        long known = newUv + oldUv;
        List<Map<String, Object>> types = new ArrayList<>();
        if (plan.available() && known > 0) {
            // The estimates are distinct sets, ratios are relative to observed known+unknown UV.
            long denominator = known + unknown;
            types.add(Map.of("uvType", "newUser", "cnt", newUv, "ratio", (double) newUv / denominator));
            types.add(Map.of("uvType", "oldUser", "cnt", oldUv, "ratio", (double) oldUv / denominator));
        }
        target.put("uvTypeStats", types);
        var quality = new LinkedHashMap<String, Object>();
        quality.put("status", !plan.available() ? "UNKNOWN" : uv == 0 && missingClicks == 0 ? "EMPTY"
                : known == 0 ? "UNKNOWN" : unknown > 0 || missingClicks > 0 ? "PARTIAL" : "AVAILABLE");
        quality.put("semantic", SEMANTIC);
        quality.put("scope", group ? "AUTHORIZED_GROUP" : "SHORT_LINK");
        quality.put("maxHistoryDays", 180);
        quality.put("historyStart", plan.start());
        quality.put("historyEnd", plan.end());
        quality.put("historyProof", plan.proof());
        quality.put("historySourceTopic", Topics.CLICK_RAW);
        long earliest = number(raw.get("history_earliest_raw"));
        quality.put("earliestObservedAt", earliest == 0 ? null : earliest);
        quality.put("newUv", newUv);
        quality.put("oldUv", oldUv);
        quality.put("unknownUv", unknown);
        quality.put("missingVisitorClicks", missingClicks);
        quality.put("knownCount", known);
        quality.put("unknownCount", unknown);
        quality.put("coverage", known + unknown == 0 ? null : (double) known / (known + unknown));
        quality.put("reason", plan.available() ? unknown > 0 ? "VISITOR_NOT_IN_FROZEN_HISTORY"
                : missingClicks > 0 ? "MISSING_VISITOR_HASH" : "" : plan.reason());
        quality.put("historicalMeaning", "First observed within retained and readable history; not lifetime-new visitors");
        var dimensions = new LinkedHashMap<>((Map<String, Object>) target.getOrDefault("dimensionQuality", Map.of()));
        dimensions.put("uvTypeStats", quality);
        target.put("dimensionQuality", dimensions);
    }

    private static long number(Object value) { return value == null ? 0L : AnalyticsQueryService.number(value); }

    @SuppressWarnings("unchecked")
    public static void labelScope(Map<String, Object> target, int authorizedLinks) {
        Object all = target.get("dimensionQuality");
        if (all instanceof Map<?, ?> dimensions && dimensions.get("uvTypeStats") instanceof Map<?, ?> quality) {
            ((Map<String, Object>) quality).put("scope", authorizedLinks == 1 ? "SHORT_LINK" : "AUTHORIZED_GROUP");
            ((Map<String, Object>) quality).put("authorizedLinkCount", authorizedLinks);
        }
    }

    public static void materializeRecords(Map<String, Object> target, List<Map<String, Object>> rows, Plan plan) {
        Set<String> all = new HashSet<>(), fresh = new HashSet<>(), old = new HashSet<>(), unknown = new HashSet<>();
        long missing = 0, earliest = 0;
        for (var row : rows) {
            String visitor = Objects.toString(row.get("visitorHash"), "");
            if (visitor.isEmpty()) { missing++; continue; }
            all.add(visitor);
            switch (Objects.toString(row.get("uvType"), "UNKNOWN")) {
                case "newUser" -> fresh.add(visitor);
                case "oldUser" -> old.add(visitor);
                default -> unknown.add(visitor);
            }
            long observed = number(row.get("historyEarliestObservedAt"));
            if (observed > 0 && (earliest == 0 || observed < earliest)) earliest = observed;
        }
        materialize(target, Map.of("uv", all.size(), "scope_new_uv", fresh.size(), "scope_old_uv", old.size(),
                "scope_unknown_uv", unknown.size(), "missing_visitor_clicks", missing, "history_earliest_raw", earliest), plan, true);
    }
}
