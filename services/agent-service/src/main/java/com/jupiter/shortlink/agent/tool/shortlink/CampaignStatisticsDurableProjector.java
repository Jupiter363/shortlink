package com.jupiter.shortlink.agent.tool.shortlink;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStatisticsPlanFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery.Bound;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.StatisticsJobFixedExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects complete, published FIXED statistics jobs; never submits or resumes a remote job.
 * Every source is resolved from its frozen step, not from an untrusted job/artifact reference.
 */
public final class CampaignStatisticsDurableProjector {
    private static final String INVALID = "STATISTICS_DURABLE_EVIDENCE_INVALID";
    private static final int MAX_PAGES = 10;
    private static final int MAX_ROWS = 5000;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final CampaignRunStore runs;
    private final CampaignStatisticsResultStore results;

    public CampaignStatisticsDurableProjector(CampaignRunStore runs, CampaignStatisticsResultStore results) {
        this.runs = Objects.requireNonNull(runs);
        this.results = Objects.requireNonNull(results);
    }

    /** The mapping is keyed by the original ordered query plan's query key. */
    public Map<String, Object> compare(Caller current, RunToken token, CampaignStatisticsQueryPlan.Plan plan,
                                       Map<String, String> stepIds, ArtifactAuthorizer authorizer, Clock clock) {
        Objects.requireNonNull(plan); Objects.requireNonNull(stepIds); Objects.requireNonNull(clock);
        require(stepIds.keySet().equals(plan.queries().stream().map(CampaignStatisticsQueryPlan.Query::key)
                .collect(java.util.stream.Collectors.toSet())));
        Map<String, Bound> bound = bounds(current, token, authorizer);
        require("COMPARISON".equals(operation(token).get("kind"))
                && plan.planId().equals(operation(token).get("queryPlanId"))
                && bound.keySet().equals(new HashSet<>(stepIds.values())));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var query : plan.queries()) {
            Evidence evidence = load(current, token, bound.get(stepIds.get(query.key())), "METRICS",
                    query.arguments(), authorizer);
            Map<String, Object> row = new LinkedHashMap<>(query.arguments());
            row.put("key", query.key());
            row.put("status", "READY");
            if (!query.scope().label().isEmpty()) row.put("scopeLabel", query.scope().label());
            if (!query.period().label().isEmpty()) row.put("periodLabel", query.period().label());
            for (String metric : List.of("pv", "uv", "uip")) row.put(metric, number(evidence.summary(), metric));
            row.put("quality", map(evidence.snapshot()));
            rows.add(row);
        }
        List<Map<String, Object>> scopes = plan.scopes().stream().map(scope -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("gid", scope.gid());
            if (!scope.fullShortUrl().isEmpty()) value.put("fullShortUrl", scope.fullShortUrl());
            return value;
        }).toList();
        List<Map<String, Object>> periods = plan.periods().stream().map(period ->
                Map.<String, Object>of("startDate", period.startDate().toString(), "endDate", period.endDate().toString())).toList();
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("type", "comparison");
        projected.put("status", "READY");
        projected.put("rows", List.copyOf(rows));
        projected.put("comparisons", CampaignStatisticsComparisonCalculator.calculate(rows, scopes, periods, clock));
        projected.put("meta", Map.of("planId", plan.planId(), "queryCount", rows.size(), "resultComplete", true,
                "businessTimezone", "Asia/Shanghai", "rateUnit", "ratio"));
        requireCurrentDefinition(current, token);
        return projected;
    }

    /** Ranking uses every published page, then checks exact membership and whole-window PV. */
    public Map<String, Object> rank(Caller current, RunToken token, String stepId, String gid,
                                    String startDate, String endDate, String metric, int limit,
                                    ArtifactAuthorizer authorizer) {
        require(Set.of("pv", "uv", "uip").contains(metric) && limit >= 1 && limit <= 50);
        Map<String, Object> operation = operation(token);
        require("RANKING".equals(operation.get("kind")) && metric.equals(operation.get("metric"))
                && Integer.valueOf(limit).equals(operation.get("limit")));
        Map<String, Object> query = Map.of("gid", gid, "startDate", startDate, "endDate", endDate);
        Map<String, Bound> bound = bounds(current, token, authorizer);
        require(bound.size() == 1 && bound.containsKey(stepId));
        Evidence evidence = load(current, token, bound.get(stepId),
                "LINK_METRICS", query, authorizer);
        Set<Long> expected = ids(evidence.snapshot().get("linkIds"));
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : evidence.items()) {
            long id = count(item.get("linkId"));
            require(expected.contains(id) && seen.add(id));
            Map<String, Object> row = map(item);
            for (String counter : List.of("pv", "uv", "uip")) number(item, counter);
            items.add(row);
        }
        require(expected.equals(seen) && expected.size() == evidence.receipt().spec().totalRows());
        var ranking = CampaignStatisticsRankingCalculator.calculate(items, map(evidence.summary()), query, metric, limit);
        require(ranking.complete());
        Map<String, Object> meta = map(evidence.snapshot());
        meta.put("metric", metric); meta.put("limit", limit);
        meta.put("rankedCount", ranking.rows().size()); meta.put("totalLinks", items.size());
        meta.put("resultComplete", true); meta.put("rankingBasis", "OBSERVED_WHOLE_WINDOW_METRICS");
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("type", "ranking"); projected.put("status", "READY");
        projected.put("rows", ranking.rows()); projected.put("metrics", map(evidence.summary()));
        projected.put("meta", meta);
        requireCurrentDefinition(current, token);
        return projected;
    }

    private Map<String, Bound> bounds(Caller current, RunToken token, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(current); Objects.requireNonNull(token); Objects.requireNonNull(authorizer);
        if (!current.equals(token.definition().caller())) throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
        requireCurrentDefinition(current, token);
        return FrozenStatisticsJobQuery.resolve(token.definition(), StatisticsJobFixedExecutor.REF);
    }

    private void requireCurrentDefinition(Caller current, RunToken token) {
        RunRecord stored = runs.loadRun(current, token.definition().runId())
                .orElseThrow(CampaignStatisticsDurableProjector::invalid);
        require(stored.status() == RunStatus.ACTIVE && token.definition().equals(stored.definition()));
    }

    private static Map<String, Object> operation(RunToken token) {
        Object value = FrozenCampaignRun.read(token.definition()).inputs().inputValues().get("operation");
        require(value instanceof Map<?, ?>);
        Map<String, Object> operation = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> {
            require(key instanceof String);
            operation.put((String) key, item);
        });
        require(CampaignStatisticsPlanFactory.OPERATION_SCHEMA.equals(operation.get("schemaVersion")));
        return operation;
    }

    private Evidence load(Caller current, RunToken token, Bound bound, String kind,
                          Map<String, Object> query, ArtifactAuthorizer authorizer) {
        require(bound != null && query != null);
        ChildRecord child = runs.child(token, bound.child().childId()).orElseThrow(CampaignStatisticsDurableProjector::invalid);
        require(child.state() == ChildState.READY && bound.child().equals(child.spec())
                && bound.target().artifactId().equals(child.artifactId()) && child.jobId() != null);
        StatisticsJobResultProtocol protocol = new StatisticsJobResultProtocol(child);
        Map<String, Object> request = protocol.request();
        require(kind.equals(request.get("queryKind")) && kind.equals(bound.request().get("queryKind"))
                && Objects.equals(request.get("gid"), query.get("gid"))
                && Objects.equals(request.get("startDate"), query.get("startDate"))
                && Objects.equals(request.get("endDate"), query.get("endDate"))
                && Objects.equals(normalize(request.get("fullShortUrl")), normalize(query.get("fullShortUrl"))));
        Receipt receipt = results.receipt(token, child.spec().childId()).orElseThrow(CampaignStatisticsDurableProjector::invalid);
        var spec = receipt.spec();
        require(receipt.published() && receipt.complete() && receipt.nextPageIndex() == receipt.requiredPages()
                && spec.totalRows() >= 0 && spec.totalRows() <= MAX_ROWS
                && spec.pageCount() <= MAX_PAGES && spec.pageCount() == pages(spec.totalRows())
                && child.jobId().equals(spec.jobId()) && child.artifactId().equals(spec.artifactId())
                && child.spec().wire().hash().equals(spec.requestHash())
                && bound.scopeRef().equals(spec.scopeRef()) && bound.periodsRef().equals(spec.periodsRef()));
        Artifact artifact = runs.readArtifact(current, spec.artifactId(), authorizer);
        ArtifactMetadata meta = artifact.metadata();
        var definition = token.definition();
        require(CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(meta.ref().type())
                && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(meta.ref().schemaVersion())
                && current.equals(meta.owner()) && definition.runId().equals(meta.runId())
                && definition.planId().equals(meta.planId()) && definition.revision() == meta.revision()
                && child.spec().childId().equals(meta.childId()) && child.spec().actionId().equals(meta.actionId())
                && StatisticsJobFixedExecutor.REF.version().equals(meta.executorVersion())
                && spec.artifactId().equals(meta.ref().artifactId())
                && spec.scopeRef().equals(meta.ref().scopeRef()) && spec.periodsRef().equals(meta.ref().periodsRef())
                && spec.expiresAtMillis() == meta.ref().expiresAt().toEpochMilli()
                && CampaignRunStore.sha256(artifact.payloadJson()).equals(meta.ref().payloadHash()));
        JsonNode snapshot = object(receipt.snapshotJson());
        JsonNode metrics = object(receipt.metricsJson());
        JsonNode manifest = object(artifact.payloadJson());
        JsonNode provenance = object(meta.provenanceJson());
        require(fields(manifest).equals(Set.of("jobId", "artifactId", "pageCount", "receivedPageCount",
                "totalRows", "chainHash", "resultComplete", "meta", "metrics"))
                && snapshot.equals(object(meta.qualityJson())) && snapshot.equals(manifest.get("meta"))
                && metrics.equals(manifest.get("metrics"))
                && child.jobId().equals(text(manifest, "jobId"))
                && spec.artifactId().equals(text(manifest, "artifactId"))
                && count(manifest.get("pageCount")) == spec.pageCount()
                && count(manifest.get("receivedPageCount")) == receipt.storedPages()
                && count(manifest.get("totalRows")) == spec.totalRows()
                && receipt.chainHash().equals(text(manifest, "chainHash"))
                && manifest.path("resultComplete").isBoolean() && manifest.path("resultComplete").booleanValue()
                && child.jobId().equals(text(provenance, "jobId"))
                && spec.requestHash().equals(text(provenance, "requestHash"))
                && child.jobId().equals(text(snapshot, "snapshotId"))
                && kind.equals(text(snapshot, "queryKind"))
                && spec.totalRows() == count(snapshot.get("totalRows"))
                && spec.expiresAtMillis() == count(snapshot.get("snapshotExpiresAt"))
                && "AVAILABLE".equals(text(snapshot, "availability"))
                && "COMPLETE".equals(text(snapshot, "completeness"))
                && "FRESH".equals(text(snapshot, "freshness"))
                && (!snapshot.has("resultComplete") || bool(snapshot.get("resultComplete"), true))
                && (!snapshot.has("provisional") || bool(snapshot.get("provisional"), false))
                && (!snapshot.has("truncated") || bool(snapshot.get("truncated"), false)));
        if (request.containsKey("scope")) {
            require("FROZEN_SET".equals(text(provenance, "scopeMode"))
                    && provenance.get("scopeProof") != null
                    && provenance.get("scopeProof").equals(snapshot.get("scopeProof")));
        } else require("CURRENT_QUERY".equals(text(provenance, "scopeMode")));
        JsonNode summary = object(metrics.get("requested"));
        require("requested".equals(text(summary, "window"))
                && count(summary.get("startInclusive")) == count(snapshot.get("requestedStart"))
                && count(summary.get("endExclusive")) == count(snapshot.get("requestedEnd")));
        for (String counter : List.of("pv", "uv", "uip")) number(summary, counter);
        StatisticsJobResultProtocol.Status status = new StatisticsJobResultProtocol.Status(child.jobId(),
                "SUCCEEDED", spec.totalRows(), spec.pageCount(), spec.expiresAtMillis(), null);
        List<JsonNode> items = new ArrayList<>();
        String chain = initialChain(spec);
        for (int index = 0; index < receipt.requiredPages(); index++) {
            require(meta.equals(runs.inspectArtifact(current, spec.artifactId(), authorizer)));
            String body = results.readPage(current, spec.artifactId(), index, authorizer);
            JsonNode page = object(body);
            CampaignStatisticsResultStore.Page decoded = protocol.page(status, map(page), index);
            require(snapshot.equals(object(decoded.snapshotJson())) && metrics.equals(object(decoded.metricsJson()))
                    && decoded.rowCount() == page.path("items").size());
            page.path("items").forEach(item -> items.add(item.deepCopy()));
            chain = CampaignRunStore.sha256(chain + ":" + index + ":" + CampaignRunStore.sha256(body)
                    + ":" + decoded.rowCount());
        }
        require(items.size() == spec.totalRows() && chain.equals(receipt.chainHash())
                && meta.equals(runs.inspectArtifact(current, spec.artifactId(), authorizer))
                && receipt.equals(results.receipt(token, child.spec().childId()).orElse(null)));
        return new Evidence(receipt, snapshot, summary, List.copyOf(items));
    }

    private static String initialChain(CampaignStatisticsResultStore.ReceiptSpec spec) {
        try { return CampaignRunStore.sha256(CampaignStatisticsResultStore.SCHEMA_VERSION + ":"
                + CampaignRunStore.sha256(JSON.writeValueAsString(spec))); }
        catch (JsonProcessingException invalid) { throw invalid(); }
    }
    private static int pages(long rows) { return (int) (rows / 500 + (rows % 500 == 0 ? 0 : 1)); }
    private static long count(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0);
        return value.longValue();
    }
    private static BigDecimal number(JsonNode object, String name) {
        long value = count(object.get(name));
        return BigDecimal.valueOf(value);
    }
    private static Set<Long> ids(JsonNode values) {
        require(values != null && values.isArray() && values.size() <= MAX_ROWS);
        Set<Long> result = new HashSet<>();
        for (JsonNode value : values) require(result.add(count(value)));
        return result;
    }
    private static Set<String> fields(JsonNode object) {
        Set<String> result = new HashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }
    private static boolean bool(JsonNode value, boolean expected) {
        return value != null && value.isBoolean() && value.booleanValue() == expected;
    }
    private static String normalize(Object value) {
        return value == null ? "" : value.toString().trim().replaceFirst("(?i)^https?://", "");
    }
    private static String text(JsonNode object, String name) {
        JsonNode value = object == null ? null : object.get(name);
        require(value != null && value.isTextual() && !value.textValue().isBlank());
        return value.textValue();
    }
    private static JsonNode object(String value) {
        require(value != null);
        try { return object(JSON.readTree(value)); }
        catch (JsonProcessingException invalid) { throw invalid(); }
    }
    private static JsonNode object(JsonNode value) { require(value != null && value.isObject()); return value; }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode value) {
        require(value != null && value.isObject());
        return JSON.convertValue(value, LinkedHashMap.class);
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalStateException invalid() { return new IllegalStateException(INVALID); }
    private record Evidence(Receipt receipt, JsonNode snapshot, JsonNode summary, List<JsonNode> items) {}
}
