package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Period;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Slot;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only proof of one frozen cohort's complete joint-dimension query, never a query executor. */
public final class CampaignDimensionEvidence {
    private static final int MAX_BUCKETS = 5000;
    private static final Set<String> ROW_FIELDS = Set.of("dimensions", "pv", "uv", "uip", "pvRatio");
    private static final Set<String> CELL_FIELDS = Set.of("state", "value");
    private static final Set<String> MANIFEST_FIELDS = Set.of("jobId", "artifactId", "pageCount", "receivedPageCount",
            "totalRows", "chainHash", "resultComplete", "meta", "metrics");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final CampaignRunStore runs;
    private final CampaignStatisticsResultStore results;

    public CampaignDimensionEvidence(CampaignRunStore runs, CampaignStatisticsResultStore results) {
        this.runs = Objects.requireNonNull(runs);
        this.results = Objects.requireNonNull(results);
    }

    /**
     * Only verify can mint this proof. No bucket payloads are retained; at most ten page hashes bind
     * later reads to the exact pages whose global uniqueness and PV totals were checked.
     */
    public static final class VerifiedQuery {
        private final ArtifactMetadata metadata;
        private final JsonNode snapshot;
        private final JsonNode metrics;
        private final int pageCount;
        private final long totalRows;
        private final Map<String, Object> request;
        private final Slot sourceSlot;
        private final FrozenQueryScope scope;
        private final Period period;
        private final List<String> dimensions;
        private final List<Map<String, Object>> filters;
        private final List<String> pageHashes;

        private VerifiedQuery(Context context, Slot sourceSlot, FrozenQueryScope scope, Period period,
                              List<String> dimensions, List<Map<String, Object>> filters, List<String> pageHashes) {
            this.metadata = context.artifact().metadata();
            this.snapshot = context.snapshot().deepCopy();
            this.metrics = context.metrics().deepCopy();
            this.pageCount = context.receipt().spec().pageCount();
            this.totalRows = context.receipt().spec().totalRows();
            this.request = context.protocol().request(); // The protocol deep-freezes original wire values.
            this.sourceSlot = sourceSlot;
            this.scope = scope;
            this.period = period;
            this.dimensions = List.copyOf(dimensions);
            this.filters = filters.stream().map(Map::copyOf).toList();
            this.pageHashes = List.copyOf(pageHashes);
        }

        public ArtifactMetadata metadata() { return metadata; }
        public JsonNode snapshot() { return snapshot.deepCopy(); }
        public JsonNode metrics() { return metrics.deepCopy(); }
        /** Remote page count; a zero-row result has pageCount=0 but still has readable page zero. */
        public int pageCount() { return pageCount; }
        public long totalRows() { return totalRows; }
        public Map<String, Object> request() { return request; }
        public Slot sourceSlot() { return sourceSlot; }
        public FrozenQueryScope scope() { return scope; }
        public Period period() { return period; }
        public List<String> dimensions() { return dimensions; }
        public List<Map<String, Object>> filters() { return filters; }
    }

    public VerifiedQuery verify(Caller current, Slot slot, FrozenQueryScope scope, Period period,
                                List<String> dimensions, List<Map<String, Object>> filters,
                                ArtifactAuthorizer authorizer) {
        List<String> selectedDimensions = dimensions(dimensions);
        List<Map<String, Object>> selectedFilters = filters(filters);
        Context context = bind(current, slot, scope, period, selectedDimensions, selectedFilters, authorizer);
        List<String> hashes = new ArrayList<>(context.receipt().requiredPages());
        String chain = initialChain(context.receipt());
        BigInteger pvTotal = BigInteger.ZERO;
        long rowTotal = 0;
        for (int index = 0; index < context.receipt().requiredPages(); index++) {
            PageData page = rawPage(current, slot, context, index, authorizer);
            Set<List<String>> currentKeys = new HashSet<>();
            for (JsonNode row : page.items()) {
                require(currentKeys.add(jointKey(row, selectedDimensions)), "DIMENSION_DUPLICATE_BUCKET");
                pvTotal = pvTotal.add(BigInteger.valueOf(validateRow(row, selectedDimensions, context.summaryPv())));
                rowTotal++;
            }
            // Query pages sort by PV, not by joint key. Compare at most two pages at a time;
            // never accumulate all 5000 keys or rows in a process-resident map.
            for (int previous = 0; previous < index; previous++) {
                PageData earlier = rawPage(current, slot, context, previous, authorizer);
                require(hashes.get(previous).equals(earlier.hash()), "DIMENSION_PAGE_CHANGED");
                for (JsonNode row : earlier.items())
                    require(!currentKeys.contains(jointKey(row, selectedDimensions)), "DIMENSION_DUPLICATE_BUCKET");
            }
            hashes.add(page.hash());
            chain = nextChain(chain, index, page.hash(), page.items().size());
        }
        require(rowTotal == context.receipt().spec().totalRows()
                && pvTotal.equals(BigInteger.valueOf(context.summaryPv())), "DIMENSION_PV_TOTAL_MISMATCH");
        require(chain.equals(context.receipt().chainHash()), "DIMENSION_PAGE_CHAIN_MISMATCH");
        Context refreshed = bind(current, slot, scope, period, selectedDimensions, selectedFilters, authorizer);
        require(same(context, refreshed), "DIMENSION_SOURCE_CHANGED");
        return new VerifiedQuery(refreshed, slot, scope, period, selectedDimensions, selectedFilters, hashes);
    }

    /** Rechecks live source bindings and page hash; does not recursively repeat global verification. */
    public List<JsonNode> readRows(Caller current, VerifiedQuery query, int page, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(query);
        require(page >= 0 && page < query.pageHashes.size(), "DIMENSION_PAGE_INVALID");
        Context context = bind(current, query.sourceSlot, query.scope, query.period, query.dimensions,
                query.filters, authorizer);
        require(query.metadata.equals(context.artifact().metadata())
                && query.snapshot.equals(context.snapshot()) && query.metrics.equals(context.metrics())
                && query.request.equals(context.protocol().request())
                && query.pageCount == context.receipt().spec().pageCount()
                && query.totalRows == context.receipt().spec().totalRows(), "DIMENSION_SOURCE_CHANGED");
        PageData actual = rawPage(current, query.sourceSlot, context, page, authorizer);
        require(query.pageHashes.get(page).equals(actual.hash()), "DIMENSION_PAGE_CHANGED");
        List<JsonNode> rows = new ArrayList<>(actual.items().size());
        Set<List<String>> keys = new HashSet<>();
        for (JsonNode row : actual.items()) {
            require(keys.add(jointKey(row, query.dimensions)), "DIMENSION_DUPLICATE_BUCKET");
            validateRow(row, query.dimensions, context.summaryPv());
            rows.add(row.deepCopy());
        }
        require(query.metadata.equals(runs.inspectArtifact(current, query.metadata.ref().artifactId(), authorizer)),
                "DIMENSION_SOURCE_CHANGED");
        return List.copyOf(rows);
    }

    private Context bind(Caller current, Slot slot, FrozenQueryScope scope, Period period,
                         List<String> dimensions, List<Map<String, Object>> filters, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(current); Objects.requireNonNull(scope); Objects.requireNonNull(period);
        Objects.requireNonNull(authorizer);
        require(slot != null && slot.runToken() != null && slot.childId() != null && slot.artifactId() != null,
                "DIMENSION_SOURCE_MISSING");
        if (!current.equals(slot.runToken().definition().caller())) throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
        ChildRecord child = runs.child(slot.runToken(), slot.childId()).orElseThrow(() -> invalid("DIMENSION_CHILD_MISSING"));
        require(child.state() == ChildState.READY && slot.artifactId().equals(child.artifactId())
                && child.spec().wire() != null
                && StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path()), "DIMENSION_CHILD_NOT_READY");
        StatisticsJobResultProtocol protocol;
        try { protocol = new StatisticsJobResultProtocol(child); }
        catch (IllegalArgumentException failure) { throw invalid("DIMENSION_REQUEST_INVALID"); }
        Map<String, Object> request = protocol.request();
        require("DIMENSION_BREAKDOWN".equals(request.get("queryKind"))
                && period.startDate().equals(request.get("startDate")) && period.endDate().equals(request.get("endDate"))
                && dimensions.equals(dimensions(request.get("dimensions")))
                && filters.equals(filters(request.get("filters"))), "DIMENSION_REQUEST_MISMATCH");
        try { require(scope.equals(FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope"))), "DIMENSION_SCOPE_MISMATCH"); }
        catch (IllegalArgumentException | ClassCastException failure) { throw invalid("DIMENSION_SCOPE_MISMATCH"); }
        Receipt receipt = results.receipt(slot.runToken(), slot.childId()).orElseThrow(() -> invalid("DIMENSION_RECEIPT_MISSING"));
        var spec = receipt.spec();
        require(spec.totalRows() >= 0 && spec.totalRows() <= MAX_BUCKETS
                && spec.pageCount() == (spec.totalRows() + 499) / 500
                && receipt.published() && receipt.complete() && receipt.nextPageIndex() == receipt.requiredPages(),
                "DIMENSION_RESULT_INCOMPLETE");
        Artifact artifact = runs.readArtifact(current, slot.artifactId(), authorizer);
        ArtifactMetadata metadata = artifact.metadata();
        var definition = slot.runToken().definition();
        require(CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(metadata.ref().type())
                && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(metadata.ref().schemaVersion())
                && current.equals(metadata.owner()) && definition.runId().equals(metadata.runId())
                && definition.planId().equals(metadata.planId()) && definition.revision() == metadata.revision()
                && child.spec().childId().equals(metadata.childId()) && child.spec().actionId().equals(metadata.actionId())
                && scope.parentScopeRef().equals(metadata.ref().scopeRef()) && period.periodsRef().equals(metadata.ref().periodsRef())
                && scope.parentScopeRef().equals(spec.scopeRef()) && period.periodsRef().equals(spec.periodsRef())
                && child.jobId().equals(spec.jobId()) && slot.artifactId().equals(spec.artifactId())
                && slot.artifactId().equals(metadata.ref().artifactId()) && child.spec().wire().hash().equals(spec.requestHash())
                && metadata.ref().expiresAt().toEpochMilli() == spec.expiresAtMillis()
                && CampaignRunStore.sha256(artifact.payloadJson()).equals(metadata.ref().payloadHash()),
                "DIMENSION_ARTIFACT_BINDING_MISMATCH");
        JsonNode snapshot = object(receipt.snapshotJson()), metrics = object(receipt.metricsJson());
        require(snapshot.equals(object(metadata.qualityJson()))
                && "DIMENSION_BREAKDOWN".equals(text(snapshot, "aggregationLevel"))
                && "FILTERED_FULL_WINDOW".equals(text(snapshot, "dimensionQualityScope"))
                && "AVAILABLE".equals(text(snapshot, "availability"))
                && "COMPLETE".equals(text(snapshot, "completeness"))
                && bool(snapshot.get("resultComplete"), true) && bool(snapshot.get("truncated"), false)
                && bool(snapshot.get("groupScopeComplete"), false), "DIMENSION_QUERY_INCOMPLETE");
        JsonNode provenance = object(metadata.provenanceJson());
        require("FROZEN_SET".equals(text(provenance, "scopeMode")) && child.jobId().equals(text(provenance, "jobId"))
                && spec.requestHash().equals(text(provenance, "requestHash"))
                && provenance.get("scopeProof") != null && provenance.get("scopeProof").equals(snapshot.get("scopeProof")),
                "DIMENSION_PROVENANCE_MISMATCH");
        JsonNode manifest = object(artifact.payloadJson());
        require(fields(manifest).equals(MANIFEST_FIELDS) && bool(manifest.get("resultComplete"), true)
                && child.jobId().equals(text(manifest, "jobId")) && slot.artifactId().equals(text(manifest, "artifactId"))
                && count(manifest.get("pageCount")) == spec.pageCount()
                && count(manifest.get("receivedPageCount")) == receipt.storedPages()
                && count(manifest.get("totalRows")) == spec.totalRows()
                && receipt.chainHash().equals(text(manifest, "chainHash"))
                && snapshot.equals(manifest.get("meta")) && metrics.equals(manifest.get("metrics")),
                "DIMENSION_MANIFEST_MISMATCH");
        JsonNode summary = metrics.get("requested");
        require(summary != null && summary.isObject() && "requested".equals(text(summary, "window"))
                && count(summary.get("startInclusive")) == period.startInclusive()
                && count(summary.get("endExclusive")) == period.endExclusive(), "DIMENSION_WINDOW_MISMATCH");
        long pv = count(summary.get("pv"));
        count(summary.get("uv")); count(summary.get("uip")); // Approximate distinct counts are never summed or capped at PV.
        require(count(summary.get("ratioDenominator")) == pv, "DIMENSION_DENOMINATOR_MISMATCH");
        JsonNode quality = summary.get("dimensionQuality");
        require(quality != null && quality.isObject() && fields(quality).equals(Set.copyOf(dimensions))
                && quality.equals(snapshot.get("dimensionQuality")), "DIMENSION_QUALITY_MISMATCH");
        return new Context(child, protocol, receipt, artifact, snapshot, metrics, pv);
    }

    private PageData rawPage(Caller current, Slot slot, Context context, int index, ArtifactAuthorizer authorizer) {
        String payload = results.readPage(current, slot.artifactId(), index, authorizer);
        JsonNode decoded = object(payload);
        CampaignStatisticsResultStore.Page page;
        try {
            page = context.protocol().page(new StatisticsJobResultProtocol.Status(context.child().jobId(), "SUCCEEDED",
                    context.receipt().spec().totalRows(), context.receipt().spec().pageCount(),
                    context.receipt().spec().expiresAtMillis(), null), JSON.convertValue(decoded, Object.class), index);
        } catch (IllegalArgumentException failure) { throw invalid("DIMENSION_PAGE_CONTEXT_MISMATCH"); }
        require(context.snapshot().equals(object(page.snapshotJson())) && context.metrics().equals(object(page.metricsJson())),
                "DIMENSION_PAGE_CONTEXT_MISMATCH");
        JsonNode items = decoded.get("items");
        require(items != null && items.isArray() && items.size() <= 500, "DIMENSION_PAGE_INVALID");
        return new PageData(items, CampaignRunStore.sha256(payload));
    }

    private static long validateRow(JsonNode row, List<String> dimensions, long totalPv) {
        require(row != null && row.isObject() && fields(row).equals(ROW_FIELDS), "DIMENSION_BUCKET_INVALID");
        jointKey(row, dimensions);
        long pv = count(row.get("pv"));
        require(pv > 0 && totalPv > 0, "DIMENSION_BUCKET_INVALID");
        count(row.get("uv")); count(row.get("uip"));
        JsonNode ratio = row.get("pvRatio");
        // The producer emits the IEEE-754 double division directly, without decimal-place rounding.
        require(ratio != null && ratio.isNumber() && Double.isFinite(ratio.doubleValue())
                && Double.compare(ratio.doubleValue(), (double) pv / totalPv) == 0, "DIMENSION_RATIO_MISMATCH");
        return pv;
    }

    private static List<String> jointKey(JsonNode row, List<String> dimensions) {
        require(row != null && row.isObject(), "DIMENSION_BUCKET_INVALID");
        JsonNode values = row.get("dimensions");
        require(values != null && values.isObject() && fields(values).equals(Set.copyOf(dimensions)), "DIMENSION_BUCKET_INVALID");
        List<String> key = new ArrayList<>(dimensions.size() * 2);
        for (String dimension : dimensions) {
            JsonNode cell = values.get(dimension);
            require(cell != null && cell.isObject() && fields(cell).equals(CELL_FIELDS), "DIMENSION_VALUE_INVALID");
            String state = text(cell, "state");
            JsonNode value = cell.get("value");
            require(Set.of("KNOWN", "UNKNOWN", "NOT_APPLICABLE").contains(state)
                    && (!"NOT_APPLICABLE".equals(state) || "province".equals(dimension)), "DIMENSION_VALUE_INVALID");
            if ("KNOWN".equals(state)) {
                require(value != null && value.isTextual() && !value.textValue().isEmpty(), "DIMENSION_VALUE_INVALID");
                key.add(state); key.add(value.textValue());
            } else {
                require(value != null && value.isNull(), "DIMENSION_VALUE_INVALID");
                key.add(state); key.add("");
            }
        }
        return List.copyOf(key);
    }

    private static List<String> dimensions(Object value) {
        try {
            List<String> result = DimensionQuery.dimensions(value);
            require(result.size() == 2, "DIMENSION_TWO_DIMENSIONS_REQUIRED");
            return result;
        } catch (IllegalArgumentException invalid) { throw invalid("DIMENSION_REQUEST_INVALID"); }
    }

    private static List<Map<String, Object>> filters(Object value) {
        try {
            if (value instanceof List<?> values) {
                for (Object filter : values)
                    require(filter instanceof Map<?, ?> map && Set.of("dimension", "operator", "values").containsAll(map.keySet()),
                            "DIMENSION_REQUEST_INVALID");
            }
            return DimensionQuery.filters(value).stream().map(Map::copyOf).toList();
        } catch (IllegalArgumentException invalid) { throw invalid("DIMENSION_REQUEST_INVALID"); }
    }

    private static boolean same(Context before, Context after) {
        return before.child().equals(after.child()) && before.receipt().equals(after.receipt())
                && before.artifact().equals(after.artifact()) && before.protocol().request().equals(after.protocol().request());
    }

    private static String initialChain(Receipt receipt) {
        try {
            return CampaignRunStore.sha256(CampaignStatisticsResultStore.SCHEMA_VERSION + ":"
                    + CampaignRunStore.sha256(JSON.writeValueAsString(receipt.spec())));
        } catch (JsonProcessingException invalid) { throw invalid("DIMENSION_RECEIPT_INVALID"); }
    }

    private static String nextChain(String before, int index, String hash, int rows) {
        return CampaignRunStore.sha256(before + ":" + index + ":" + hash + ":" + rows);
    }

    private static JsonNode object(String value) {
        require(value != null, "DIMENSION_JSON_INVALID");
        try {
            JsonNode parsed = JSON.readTree(value);
            require(parsed != null && parsed.isObject(), "DIMENSION_JSON_INVALID");
            return parsed;
        } catch (JsonProcessingException invalid) { throw invalid("DIMENSION_JSON_INVALID"); }
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields;
    }

    private static long count(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0,
                "DIMENSION_COUNT_INVALID");
        return node.longValue();
    }

    private static String text(JsonNode node, String field) {
        require(node != null && node.path(field).isTextual() && !node.path(field).textValue().isBlank(),
                "DIMENSION_CONTEXT_INVALID");
        return node.path(field).textValue();
    }

    private static boolean bool(JsonNode node, boolean expected) {
        return node != null && node.isBoolean() && node.booleanValue() == expected;
    }

    private static void require(boolean valid, String code) { if (!valid) throw invalid(code); }
    private static IllegalStateException invalid(String code) { return new IllegalStateException(code); }
    private record Context(ChildRecord child, StatisticsJobResultProtocol protocol, Receipt receipt, Artifact artifact,
                           JsonNode snapshot, JsonNode metrics, long summaryPv) {}
    private record PageData(JsonNode items, String hash) {}
}
