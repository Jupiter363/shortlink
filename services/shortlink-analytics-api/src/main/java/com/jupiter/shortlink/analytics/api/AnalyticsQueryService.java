package com.jupiter.shortlink.analytics.api;

import static com.jupiter.shortlink.analytics.api.ClickHouseReader.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.contract.EventEnricher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

/**
 * Snapshot rows are materialized once; subsequent pages never execute an unconstrained
 * latest-version query.
 */
@Service
public class AnalyticsQueryService {
    static final long WINDOW = 300000L, MAX_RANGE = 7 * 86400000L, SNAPSHOT_TTL = 300000L;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuthorizationClient auth;
    private final ClickHouseReader ch;
    private final ApiSettings settings;
    private final CollectionQualityReader quality;

    @org.springframework.beans.factory.annotation.Autowired
    public AnalyticsQueryService(
            JdbcTemplate d,
            ObjectMapper j,
            AuthorizationClient a,
            ClickHouseReader c,
            ApiSettings s,
            CollectionQualityReader quality) {
        db = d;
        json = j;
        auth = a;
        ch = c;
        settings = s;
        this.quality = quality;
    }

    public AnalyticsQueryService(
            JdbcTemplate d,
            ObjectMapper j,
            AuthorizationClient a,
            ClickHouseReader c,
            ApiSettings s) {
        this(d, j, a, c, s, new CollectionQualityReader(d, ""));
    }

    public Map<String, Object> query(QueryRequest q) {
        q.boundedPageSize();
        DimensionBreakdown.validateRequest(q);
        var scope = auth.authorize(q);
        String epoch = auth.activeEpoch();
        if (q.snapshotId() != null) return page(q, scope, epoch);
        if (q.cursor() != null)
            throw new QueryFailure("SNAPSHOT_EXPIRED", "A cursor requires its original snapshot");
        validate(q);
        long requestedEnd = q.endExclusive(), effectiveEnd = requestedEnd;
        long coverageStart =
                q.windows() != null && q.windows().contains("7d")
                        ? requestedEnd - MAX_RANGE
                        : q.startInclusive();
        Map<String, Object> coverage = auth.coverage(Math.max(0, coverageStart));
        long observed = coverage.get("observedAt") instanceof Number n ? n.longValue() : 0;
        if ("COMMON_AVAILABLE_END".equals(q.endPolicy())) {
            if (observed > 0) effectiveEnd = Math.min(requestedEnd, observed);
            else {
                Long last =
                        db.queryForObject(
                                "SELECT MAX(window_start) FROM analytics_manifest WHERE"
                                        + " recovery_epoch=?",
                                Long.class,
                                epoch);
                if (last == null)
                    throw new QueryFailure("NOT_READY", "No published analytics windows");
                effectiveEnd = Math.min(requestedEnd, last + WINDOW);
            }
        }
        LinkedHashMap<String, long[]> windows = new LinkedHashMap<>();
        if (q.windows() != null && !q.windows().isEmpty())
            for (String w : q.windows())
                windows.put(w, new long[] {effectiveEnd - duration(w), effectiveEnd});
        else windows.put("requested", new long[] {q.startInclusive(), effectiveEnd});
        long start = windows.values().stream().mapToLong(w -> w[0]).min().orElseThrow();
        if (start < 0 || effectiveEnd <= start)
            throw new QueryFailure("NOT_READY", "Requested data is not yet available");
        if (start < coverageStart) {
            coverage = auth.coverage(start);
            observed = coverage.get("observedAt") instanceof Number n ? n.longValue() : 0;
        }
        long alignedStart = Math.floorDiv(start, WINDOW) * WINDOW;
        var manifests =
                db.queryForList(
                        "SELECT * FROM analytics_manifest WHERE recovery_epoch=? AND"
                                + " window_start>=? AND window_start<? ORDER BY window_start",
                        epoch,
                        alignedStart,
                        effectiveEnd);
        boolean canonical = manifests.size() == (effectiveEnd - alignedStart + WINDOW - 1) / WINDOW;
        long selectedEnd = effectiveEnd;
        boolean complete =
                canonical
                        && manifests.stream()
                                .allMatch(
                                        m ->
                                                number(m.getOrDefault("source_observed_at", 0))
                                                        >= Math.min(
                                                                selectedEnd,
                                                                number(m.get("window_start"))
                                                                        + WINDOW));
        boolean finalized =
                canonical
                        && manifests.stream()
                                .allMatch(
                                        r ->
                                                Boolean.TRUE.equals(r.get("finalized"))
                                                        || "1"
                                                                .equals(
                                                                        String.valueOf(
                                                                                r.get(
                                                                                        "finalized"))));
        String replica = settings.clickHouseUrls().split(",")[0];
        String facts;
        Object sourceCut;
        Map<String, Object> versions = new LinkedHashMap<>();
        if (canonical) {
            Set<String> eligible =
                    new LinkedHashSet<>(List.of(settings.clickHouseUrls().split(",")));
            String predicate = canonicalPredicate(manifests);
            Map<String, Object> cuts = new LinkedHashMap<>();
            for (var m : manifests) {
                long w = number(m.get("window_start"));
                eligible.retainAll(readList(m.get("replica_ids").toString()));
                cuts.put(Long.toString(w), readMap(m.get("source_cut").toString()));
                versions.put(
                        Long.toString(w),
                        Map.of(
                                "buildId",
                                m.get("build_id"),
                                "revision",
                                m.get("manifest_revision")));
            }
            if (eligible.isEmpty())
                throw new QueryFailure(
                        "NOT_READY", "No replica qualifies for every selected build");
            replica = eligible.iterator().next();
            new BuildProofVerifier(ch, json).verify(replica, manifests);
            facts = facts("rebuild_input", predicate, scope);
            sourceCut = cuts;
        } else {
            if (start < System.currentTimeMillis() - MAX_RANGE
                    && requestedEnd < System.currentTimeMillis() - 120000)
                throw new QueryFailure(
                        "NOT_READY", "Historical ranges require published immutable builds");
            // Live receipts may lag either source. They are explicitly PARTIAL and never evidence
            // of complete collection.
            if (coverage.get("sourceCut") instanceof Map<?, ?>
                    && epoch.equals(coverage.get("recoveryEpoch"))) {
                var fixed =
                        json.convertValue(
                                coverage.get("sourceCut"),
                                com.jupiter.shortlink.contract.SourceCut.class);
                if (fixed.ranges().isEmpty())
                    throw new QueryFailure("NOT_READY", "No initialized source roster");
                List<String> clauses = new ArrayList<>();
                for (var r : fixed.ranges())
                    clauses.add(
                            "(cluster_id="
                                    + quote(r.clusterId())
                                    + " AND topic_id="
                                    + quote(r.topicId())
                                    + " AND source_partition="
                                    + r.partition()
                                    + " AND source_offset>="
                                    + r.start()
                                    + " AND source_offset<"
                                    + r.end()
                                    + ")");
                String predicate = "(" + String.join(" OR ", clauses) + ")";
                var visible =
                        ch.query(
                                replica,
                                "SELECT"
                                    + " cluster_id,topic_id,source_partition,uniqExact(source_offset)"
                                    + " receipts FROM event_receipts WHERE "
                                        + predicate
                                        + " GROUP BY cluster_id,topic_id,source_partition",
                                4096);
                Map<String, Long> counts = new HashMap<>();
                for (var r : visible)
                    counts.put(
                            r.get("cluster_id")
                                    + ":"
                                    + r.get("topic_id")
                                    + ":"
                                    + r.get("source_partition"),
                            number(r.get("receipts")));
                complete = observed >= effectiveEnd;
                Object receiptList = coverage.get("receipts");
                if (!(receiptList instanceof List<?>)) complete = false;
                else
                    for (Object raw : (List<?>) receiptList) {
                        var expected = (Map<?, ?>) raw;
                        String key =
                                expected.get("clusterId")
                                        + ":"
                                        + expected.get("topicId")
                                        + ":"
                                        + expected.get("partition");
                        if (counts.getOrDefault(key, 0L) != number(expected.get("count")))
                            complete = false;
                    }
                facts = facts("event_receipts", predicate, scope);
                sourceCut = fixed;
            } else {
                var cuts =
                        ch.query(
                                replica,
                                "SELECT"
                                    + " cluster_id,topic_id,source_topic,source_partition,max(source_offset)+1"
                                    + " end_offset FROM event_receipts GROUP BY"
                                    + " cluster_id,topic_id,source_topic,source_partition",
                                4096);
                if (cuts.isEmpty())
                    throw new QueryFailure("NOT_READY", "No visible analytics receipts");
                List<String> clauses = new ArrayList<>();
                for (var cut : cuts)
                    clauses.add(
                            "(cluster_id="
                                    + quote(cut.get("cluster_id").toString())
                                    + " AND topic_id="
                                    + quote(cut.get("topic_id").toString())
                                    + " AND source_partition="
                                    + number(cut.get("source_partition"))
                                    + " AND source_offset<"
                                    + number(cut.get("end_offset"))
                                    + ")");
                facts = facts("event_receipts", "(" + String.join(" OR ", clauses) + ")", scope);
                sourceCut = cuts;
            }
        }
        long created = System.currentTimeMillis();
        var history = VisitorHistory.plan(sourceCut, json, created, effectiveEnd);
        if (history.available() && Set.of("METRICS", "ACCESS_RECORDS").contains(q.kind())) {
            var candidate = history;
            String selectedReplica = replica;
            var trustedCoverage = coverage;
            history = VisitorHistory.optionalProof(candidate,
                    () -> ch.queryOptionalProof(selectedReplica, VisitorHistory.visibilitySql(candidate), 4096),
                    visible -> VisitorHistory.verifyVisibility(candidate, visible,
                            trustedCoverage.get("sourceCut"), trustedCoverage.get("receipts"), json,
                            epoch.equals(trustedCoverage.get("recoveryEpoch"))));
        }
        if (Set.of("METRICS", "ACCESS_RECORDS").contains(q.kind()))
            facts = VisitorHistory.enrich(facts, history, scope.tenantId(), scope.linkIds());
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (DimensionBreakdown.KIND.equals(q.kind())) {
            var options = DimensionBreakdown.options(q);
            var accumulator = new DimensionBreakdown.Accumulator(options, start, effectiveEnd);
            for (var row : ch.query(replica, DimensionBreakdown.sql(facts, start, effectiveEnd, options),
                    DimensionBreakdown.MAX_BUCKETS + 1)) accumulator.add(row);
            var report = accumulator.finish();
            items.addAll(report.items());
            metrics.put("requested", report.summary());
        } else if (LinkMetrics.KIND.equals(q.kind())) {
            var accumulator = new LinkMetrics.Accumulator(scope.linkIds(), start, effectiveEnd);
            for (var row : ch.query(replica, LinkMetrics.sql(facts, start, effectiveEnd), 501))
                accumulator.add(row);
            var report = accumulator.finish();
            items.addAll(report.items());
            metrics.put("requested", report.summary());
        } else if ("METRICS".equals(q.kind())) {
            String aggregate =
                    "countIf(kind='CLICK') pv,uniqCombined64If(visitor_hash,kind='CLICK' AND"
                        + " visitor_hash!='') uv,uniqCombined64If(ip_hash,kind='CLICK' AND"
                        + " ip_hash!='') uip,countIf(kind='REQUEST' AND request_source='REDIRECT'"
                        + " AND decision_stage='BUSINESS' AND status IN (403,429)) denied";
            List<String> tupleWindows = new ArrayList<>();
            for (var entry : windows.entrySet())
                tupleWindows.add(
                        "("
                                + quote(entry.getKey())
                                + ","
                                + entry.getValue()[0]
                                + ","
                                + entry.getValue()[1]
                                + ")");
            // One ClickHouse read snapshot feeds all links and all windows, including group-level
            // true distinct UV.
            var allRows =
                    ch.query(
                            replica,
                            "SELECT tupleElement(report_window,1)"
                                    + " window_name,link_id,grouping(link_id) group_row,"
                                    + aggregate
                                    + MetricDimensions.sql(start, effectiveEnd)
                                    + VisitorHistory.metricsSql("tupleElement(report_window,2)")
                                    + " FROM ("
                                    + facts
                                    + ") ARRAY JOIN ["
                                    + String.join(",", tupleWindows)
                                    + "] AS report_window WHERE"
                                    + " occurred_at>=tupleElement(report_window,2) AND"
                                    + " occurred_at<tupleElement(report_window,3) GROUP BY GROUPING"
                                    + " SETS ((report_window,link_id),(report_window))",
                            1503);
            for (var entry : windows.entrySet()) {
                long[] w = entry.getValue();
                Map<Long, Map<String, Object>> byLink = new HashMap<>();
                Map<String, Object> total = Map.of();
                for (var r : allRows)
                    if (entry.getKey().equals(r.get("window_name"))) {
                        if (number(r.get("group_row")) == 1) total = r;
                        else byLink.put(number(r.get("link_id")), r);
                    }
                for (long link : scope.linkIds()) {
                    Map<String, Object> row = counts(byLink.getOrDefault(link, Map.of()));
                    row.putAll(
                            MetricDimensions.materialize(
                                    byLink.getOrDefault(link, Map.of()), w[0], w[1]));
                    VisitorHistory.materialize(row, byLink.getOrDefault(link, Map.of()), history, false);
                    VisitorHistory.labelScope(row, 1);
                    row.put("linkId", link);
                    row.put("window", entry.getKey());
                    row.put("startInclusive", w[0]);
                    row.put("endExclusive", w[1]);
                    items.add(row);
                }
                var totals = counts(total);
                totals.putAll(MetricDimensions.materialize(total, w[0], w[1]));
                VisitorHistory.materialize(totals, total, history, true);
                VisitorHistory.labelScope(totals, scope.linkIds().size());
                metrics.put(entry.getKey(), totals);
            }
        } else if ("ACCESS_RECORDS".equals(q.kind())) {
            var rows =
                    ch.query(
                            replica,
                            "SELECT event_id eventId,link_id linkId,occurred_at"
                                + " occurredAt,visitor_hash visitorHash,ip_hash"
                                + " ipHash,kind,status,browser,os,device,country,province,city,network,"
                                + "geo_status geoStatus,geo_version geoVersion,referer_domain refererDomain,"
                                + "(geo_version_count>1) geoVersionConflict,"
                                + "history_earliest historyEarliestObservedAt,"
                                + "if(scope_history_known,if(scope_first_seen>=" + start
                                + ",'newUser','oldUser'),'UNKNOWN') uvType"
                                + " FROM ("
                                    + facts
                                    + ") WHERE kind='CLICK' AND occurred_at>="
                                    + start
                                    + " AND occurred_at<"
                                    + effectiveEnd
                                    + " ORDER BY occurred_at DESC,event_id LIMIT 10001",
                            10000);
            for (var r : rows) {
                r.put("linkId", number(r.get("linkId")));
                r.put("occurredAt", number(r.get("occurredAt")));
                items.add(r);
            }
        } else {
            var rows =
                    ch.query(
                            replica,
                            "SELECT link_id linkId,max(occurred_at) lastOccurredAt,count() pv FROM"
                                    + " ("
                                    + facts
                                    + ") WHERE kind='CLICK' AND occurred_at>="
                                    + start
                                    + " AND occurred_at<"
                                    + effectiveEnd
                                    + " GROUP BY link_id ORDER BY link_id",
                            500);
            for (var r : rows) {
                r.replaceAll((k, v) -> number(v));
                items.add(r);
            }
        }
        var latestScope = auth.authorize(q);
        if (!scope.ownershipVersion().equals(latestScope.ownershipVersion())
                || !epoch.equals(auth.activeEpoch()))
            throw new QueryFailure(
                    "QUERY_SCOPE_CHANGED", "Scope or recovery epoch changed while querying");
        String id = UUID.randomUUID().toString();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryKind", q.kind());
        meta.put("gid", q.gid());
        meta.put("linkIds", scope.linkIds());
        meta.put("requestedStart", q.startInclusive());
        meta.put("requestedEnd", requestedEnd);
        meta.put("effectiveEnd", effectiveEnd);
        meta.put("snapshotId", id);
        meta.put("snapshotCreatedAt", created);
        meta.put("generatedAt", created);
        meta.put("snapshotExpiresAt", created + SNAPSHOT_TTL);
        meta.put("recoveryEpoch", epoch);
        meta.put("manifestVersion", versions);
        meta.put("sourceCut", sourceCut);
        meta.put("metricVersion", "click-v1");
        meta.put("detailDatasetVersion", EventEnricher.DATASET);
        meta.put("ruleVersion", null);
        meta.put("availability", "AVAILABLE");
        meta.put("freshness", finalized || created - effectiveEnd <= 120000 ? "FRESH" : "STALE");
        meta.put("completeness", complete ? "COMPLETE" : "PARTIAL");
        meta.put("provisional", !finalized);
        meta.put("approximation", DimensionBreakdown.KIND.equals(q.kind()) ? DimensionBreakdown.approximation()
                : LinkMetrics.KIND.equals(q.kind()) ? LinkMetrics.approximation() : MetricDimensions.approximation());
        meta.put("collectionQuality", quality.read(start, effectiveEnd, created));
        Map<String, Object> dimensions;
        if (DimensionBreakdown.KIND.equals(q.kind())) {
            dimensions = (Map<String, Object>) ((Map<?, ?>) metrics.get("requested")).get("dimensionQuality");
            DimensionBreakdown.metadata(meta, DimensionBreakdown.options(q), items.size());
        } else if (LinkMetrics.KIND.equals(q.kind())) {
            dimensions = Map.of();
            meta.put("totalRows", items.size());
            meta.put("aggregationLevel", "LINK_WINDOW");
        } else if (!metrics.isEmpty()) {
            String widest = windows.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue()[0])).orElseThrow().getKey();
            dimensions = (Map<String, Object>) ((Map<?, ?>) metrics.get(widest)).get("dimensionQuality");
            meta.put("dimensionQualityWindow", widest);
        } else {
            var recordDimensions = MetricDimensions.recordDimensions(items);
            VisitorHistory.materializeRecords(recordDimensions, items, history);
            VisitorHistory.labelScope(recordDimensions, scope.linkIds().size());
            dimensions = (Map<String, Object>) recordDimensions.get("dimensionQuality");
        }
        meta.put("dimensionQuality", dimensions);
        meta.put("missingMetrics", DimensionBreakdown.KIND.equals(q.kind()) ? DimensionBreakdown.missingMetrics(dimensions)
                : LinkMetrics.KIND.equals(q.kind()) ? List.of() : MetricDimensions.missingMetrics(dimensions));
        meta.put("businessTimezone", "Asia/Shanghai");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metrics", metrics);
        result.put("items", items);
        result.put("meta", meta);
        db.update(
                "INSERT INTO"
                    + " analytics_snapshot(snapshot_id,recovery_epoch,tenant_id,subject_id,ownership_version,request_hash,result_json,expires_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                id,
                epoch,
                q.tenantId(),
                q.subjectId(),
                scope.ownershipVersion(),
                hash(q),
                write(result),
                new Timestamp(created + SNAPSHOT_TTL));
        return slice(result, 0, q.boundedPageSize());
    }

    /** Bound boolean expression depth while keeping each published build tied to its exact window. */
    static String canonicalPredicate(List<Map<String, Object>> manifests) {
        return CanonicalBuildPredicate.predicate(canonicalSelections(manifests));
    }

    /** Preserve every selected revision while coalescing only adjacent windows of the same build. */
    static List<String> canonicalClauses(List<Map<String, Object>> manifests) {
        return CanonicalBuildPredicate.ranges(canonicalSelections(manifests));
    }

    private static List<CanonicalBuildPredicate.Selection> canonicalSelections(List<Map<String, Object>> manifests) {
        return manifests.stream().map(manifest -> new CanonicalBuildPredicate.Selection(
                manifest.get("build_id").toString(), number(manifest.get("window_start")))).toList();
    }

    private Map<String, Object> page(
            QueryRequest q, AuthorizationClient.Scope scope, String epoch) {
        var rows =
                db.queryForList(
                        "SELECT * FROM analytics_snapshot WHERE snapshot_id=?", q.snapshotId());
        if (rows.isEmpty()) throw new QueryFailure("SNAPSHOT_EXPIRED", "Snapshot is unavailable");
        var r = rows.get(0);
        if (!epoch.equals(r.get("recovery_epoch"))
                || ((java.util.Date) r.get("expires_at")).getTime() <= System.currentTimeMillis())
            throw new QueryFailure(
                    "SNAPSHOT_EXPIRED", "Snapshot expired or recovery epoch changed");
        if (!q.tenantId().equals(r.get("tenant_id")) || !q.subjectId().equals(r.get("subject_id")))
            throw new QueryFailure("FORBIDDEN", "Snapshot belongs to another subject");
        if (!scope.ownershipVersion().equals(r.get("ownership_version"))
                || !hash(q).equals(r.get("request_hash")))
            throw new QueryFailure("QUERY_SCOPE_CHANGED", "Snapshot query scope changed");
        int offset = 0;
        if (q.cursor() != null) {
            try {
                String raw =
                        new String(
                                Base64.getUrlDecoder().decode(q.cursor()),
                                java.nio.charset.StandardCharsets.UTF_8);
                String prefix = q.snapshotId() + ":";
                if (!raw.startsWith(prefix)) throw new IllegalArgumentException();
                offset = Integer.parseInt(raw.substring(prefix.length()));
            } catch (Exception e) {
                throw new QueryFailure("SNAPSHOT_EXPIRED", "Invalid snapshot cursor");
            }
        }
        return slice(readMap(r.get("result_json").toString()), offset, q.boundedPageSize());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> slice(Map<String, Object> result, int offset, int size) {
        var all = (List<Map<String, Object>>) result.get("items");
        if (offset < 0 || offset > all.size())
            throw new QueryFailure("SNAPSHOT_EXPIRED", "Invalid cursor offset");
        int end = Math.min(all.size(), offset + size);
        var meta = new LinkedHashMap<>((Map<String, Object>) result.get("meta"));
        meta.put(
                "nextCursor",
                end < all.size()
                        ? Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        (meta.get("snapshotId") + ":" + end)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : null);
        return Map.of(
                "items", all.subList(offset, end), "metrics", result.get("metrics"), "meta", meta);
    }

    private String facts(String table, String predicate, AuthorizationClient.Scope scope) {
        return AnalyticsFacts.sql(table, predicate, scope.tenantId(), scope.linkIds());
    }

    private static Map<String, Object> counts(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (String k : List.of("pv", "uv", "uip", "denied"))
            r.put(k, number(row.getOrDefault(k, 0)));
        return r;
    }

    static long number(Object v) {
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private void validate(QueryRequest q) {
        if (!Set.of("METRICS", "ACCESS_RECORDS", "ACTIVE_LINKS", LinkMetrics.KIND, DimensionBreakdown.KIND).contains(q.kind()))
            throw new QueryFailure("INVALID_QUERY", "Unknown queryKind");
        if (LinkMetrics.KIND.equals(q.kind())
                && (q.windows() != null || q.endPolicy() != null && !"REQUESTED".equals(q.endPolicy())))
            throw new QueryFailure("INVALID_QUERY", "LINK_METRICS requires one fixed requested interval");
        if (q.startInclusive() == null
                || q.endExclusive() == null
                || q.startInclusive() < 0
                || q.endExclusive() <= q.startInclusive()
                || q.endExclusive() - q.startInclusive() > MAX_RANGE)
            throw new QueryFailure(
                    "TOO_LARGE", "Query range must be positive and at most seven days");
        if (q.windows() != null
                && (q.windows().size() > 3
                        || q.windows().stream()
                                .anyMatch(w -> !Set.of("2h", "24h", "7d").contains(w))))
            throw new QueryFailure("INVALID_QUERY", "Only 2h, 24h and 7d windows are supported");
        if (q.endPolicy() != null
                && !Set.of("REQUESTED", "COMMON_AVAILABLE_END").contains(q.endPolicy()))
            throw new QueryFailure("INVALID_QUERY", "Unknown endPolicy");
        if ("COMMON_AVAILABLE_END".equals(q.endPolicy())
                && (q.windows() == null || q.windows().isEmpty()))
            throw new QueryFailure(
                    "INVALID_QUERY", "COMMON_AVAILABLE_END requires explicit named windows");
    }

    private long duration(String w) {
        return switch (w) {
            case "2h" -> 7200000L;
            case "24h" -> 86400000L;
            case "7d" -> MAX_RANGE;
            default -> throw new QueryFailure("INVALID_QUERY", "Unknown window");
        };
    }

    private String hash(QueryRequest q) {
        var fields = new ArrayList<Object>(Arrays.asList(
                                q.tenantId(),
                                q.subjectId(),
                                q.gid(),
                                q.linkIds() == null
                                        ? null
                                        : q.linkIds().stream().sorted().distinct().toList(),
                                q.startInclusive(),
                                q.endExclusive(),
                                q.windows(),
                                q.endPolicy(),
                                q.kind()));
        if (DimensionBreakdown.KIND.equals(q.kind())) {
            var options = DimensionBreakdown.options(q);
            fields.add(options.dimensions());
            fields.add(options.filterMaps());
        }
        return EventEnricher.sha256(write(fields));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readMap(String text) {
        try {
            return json.readValue(
                    text,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> readList(String text) {
        try {
            return json.readValue(
                    text, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
