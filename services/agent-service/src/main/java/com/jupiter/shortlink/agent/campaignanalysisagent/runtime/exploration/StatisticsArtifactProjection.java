package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A data-only view of published statistics evidence. It does not aggregate rows, infer quality,
 * rank a preview, fetch remote data or assign any message role. Original pages remain in the store.
 */
public final class StatisticsArtifactProjection implements ExplorationArtifactProjection {
    public static final String SCHEMA_VERSION = "statistics-artifact-projection/v1";
    private static final String INVALID = "STATISTICS_ARTIFACT_PROJECTION_INVALID";
    private static final String TOO_LARGE = "STATISTICS_ARTIFACT_PROJECTION_TOO_LARGE";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> KINDS = Set.of("METRICS", "LINK_METRICS", "DIMENSION_BREAKDOWN", "ACCESS_RECORDS");
    private static final List<String> DIMENSIONS = List.of("day", "hour", "weekday", "country", "province",
            "device", "os", "browser", "isp", "refererDomain");
    private static final List<String> QUALITY_DIMENSIONS = List.of("day", "hour", "weekday", "country", "province",
            "device", "os", "browser", "isp", "refererDomain", "hourStats", "weekdayStats", "browserStats",
            "osStats", "deviceStats", "countryStats", "localeCnStats", "networkStats", "topRegionShare", "uvTypeStats");
    private static final List<String> APPROXIMATION_METRICS = List.of("pv", "uv", "uip", "denied", "pvRatio",
            "daily.pv", "daily.uv", "daily.uip");
    private final CampaignRunStore runs;
    private final CampaignStatisticsResultStore results;
    private final Limits limits;

    public record Limits(int previewRows, int maxProjectionBytes) {
        public Limits {
            if (previewRows < 0 || previewRows > 500 || maxProjectionBytes <= 0)
                throw new IllegalArgumentException("Invalid statistics projection limits");
        }
        public static Limits defaults() { return new Limits(12, 65_536); }
    }

    public StatisticsArtifactProjection(CampaignRunStore runs, CampaignStatisticsResultStore results) {
        this(runs, results, Limits.defaults());
    }

    public StatisticsArtifactProjection(CampaignRunStore runs, CampaignStatisticsResultStore results, Limits limits) {
        this.runs = Objects.requireNonNull(runs);
        this.results = Objects.requireNonNull(results);
        this.limits = Objects.requireNonNull(limits);
    }

    @Override public String configurationId() {
        return SCHEMA_VERSION + ":rows=" + limits.previewRows() + ":bytes=" + limits.maxProjectionBytes();
    }

    @Override public Map<String, Object> project(Caller current, ArtifactMetadata expected, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(current); Objects.requireNonNull(expected); Objects.requireNonNull(authorizer);
        require(expected.ref() != null);
        if (!CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(expected.ref().type())) return Map.of();
        require(CampaignStatisticsResultStore.SCHEMA_VERSION.equals(expected.ref().schemaVersion()));
        var artifact = runs.readArtifact(current, expected.ref().artifactId(), authorizer);
        require(expected.equals(artifact.metadata())
                && expected.ref().payloadHash().equals(CampaignRunStore.sha256(artifact.payloadJson())));
        ObjectNode manifest = object(artifact.payloadJson());
        require(expected.ref().artifactId().equals(text(manifest.get("artifactId")))
                && bool(manifest.get("resultComplete")));
        long totalRows = integer(manifest.get("totalRows"));
        long pageCount = integer(manifest.get("pageCount"));
        require(pageCount == totalRows / 500 + (totalRows % 500 == 0 ? 0 : 1)
                && integer(manifest.get("receivedPageCount")) == Math.max(1, pageCount));
        require(text(manifest.get("chainHash")).matches("[a-f0-9]{64}"));
        ObjectNode meta = object(manifest.get("meta"));
        ObjectNode metrics = object(manifest.get("metrics"));
        require(meta.equals(object(expected.qualityJson())) && integer(meta.get("totalRows")) == totalRows);
        require(text(manifest.get("jobId")).equals(text(meta.get("snapshotId"))));
        String kind = text(meta.get("queryKind"));
        require(KINDS.contains(kind));

        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("schemaVersion", SCHEMA_VERSION);
        projected.put("artifactId", expected.ref().artifactId());
        projected.put("payloadHash", expected.ref().payloadHash());
        projected.put("queryKind", kind);
        if ("DIMENSION_BREAKDOWN".equals(kind)) projected.put("query", query(meta));
        projected.put("snapshot", snapshotIdentity(meta));
        projected.put("period", period(meta));
        projected.put("scope", scope(expected, meta));
        projected.put("metrics", metrics(kind, metrics, meta));
        projected.put("quality", quality(meta));
        projected.put("result", Map.of("resultComplete", true, "totalRows", totalRows, "pageCount", pageCount));

        // readPage only exposes published pages and rechecks checksum, current ACL and TTL. Even
        // zero-row evidence has a real page 0; never fabricate its contents from the row count.
        ObjectNode page = object(results.readPage(current, expected.ref().artifactId(), 0, authorizer));
        require(metrics.equals(object(page.get("metrics"))));
        ObjectNode pageMeta = object(page.get("meta"));
        require(integer(pageMeta.get("pageIndex")) == 0 && pageMeta.has("nextPageIndex"));
        if (pageCount > 1) require(integer(pageMeta.get("nextPageIndex")) == 1);
        else require(pageMeta.get("nextPageIndex").isNull());
        ObjectNode snapshot = pageMeta.deepCopy();
        snapshot.remove(List.of("pageIndex", "nextPageIndex", "hasMore"));
        require(snapshot.equals(meta));
        JsonNode items = page.get("items");
        require(items != null && items.isArray() && items.size() == Math.min(500L, totalRows));
        List<Object> preview = new ArrayList<>();
        for (int index = 0; index < Math.min(limits.previewRows(), items.size()); index++) {
            preview.add(row(kind, object(items.get(index)), meta));
        }
        Map<String, Object> previewView = new LinkedHashMap<>();
        previewView.put("pageIndex", 0);
        previewView.put("selection", "SOURCE_PAGE_ORDER_NOT_RANKING");
        previewView.put("returnedRows", preview.size());
        previewView.put("omittedRows", totalRows - preview.size());
        previewView.put("previewComplete", totalRows == preview.size());
        previewView.put("rows", List.copyOf(preview));
        projected.put("preview", immutable(previewView));
        Map<String, Object> result = immutable(projected);
        requireSize(result);
        require(expected.equals(runs.inspectArtifact(current, expected.ref().artifactId(), authorizer)));
        return result;
    }

    private static Map<String, Object> period(ObjectNode meta) {
        long start = integer(meta.get("requestedStart"));
        long end = integer(meta.get("requestedEnd"));
        long effectiveEnd = integer(meta.get("effectiveEnd"));
        require(end > start && effectiveEnd == end);
        String timezone = text(meta.get("businessTimezone"));
        return Map.of("requestedStart", start, "requestedEnd", end, "effectiveEnd", effectiveEnd,
                "businessTimezone", timezone);
    }

    private static Map<String, Object> scope(ArtifactMetadata expected, ObjectNode meta) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("scopeRef", expected.ref().scopeRef());
        value.put("periodsRef", expected.ref().periodsRef());
        copyBooleans(meta, value, List.of("groupScopeComplete", "parentComplete"));
        copyStrings(meta, value, List.of("aggregationLevel"));
        if (meta.has("scopeProof")) {
            ObjectNode proof = object(meta.get("scopeProof"));
            Map<String, Object> shard = new LinkedHashMap<>();
            copyIntegers(proof, shard, List.of("shardIndex", "shardCount", "parentMemberCount"));
            copyBooleans(proof, shard, List.of("shardComplete", "parentComplete"));
            value.put("shard", immutable(shard));
        }
        return immutable(value);
    }

    private static Map<String, Object> metrics(String kind, ObjectNode metrics, ObjectNode meta) {
        if ("ACCESS_RECORDS".equals(kind)) {
            require(metrics.isEmpty());
            return Map.of();
        }
        if (!metrics.has("requested")) return Map.of();
        if (metrics.get("requested").isNull()) return Collections.singletonMap("requested", null);
        ObjectNode source = object(metrics.get("requested"));
        Map<String, Object> requested = counters(source);
        copyStrings(source, requested, List.of("window"));
        copyIntegers(source, requested, List.of("startInclusive", "endExclusive", "ratioDenominator"));
        if (source.has("startInclusive")) require(integer(source.get("startInclusive")) == integer(meta.get("requestedStart")));
        if (source.has("endExclusive")) require(integer(source.get("endExclusive")) == integer(meta.get("requestedEnd")));
        if (source.has("dimensionQuality")) requested.put("dimensionQuality", dimensionQuality(object(source.get("dimensionQuality"))));
        // The whole-window UV/UIP are authoritative here. Preview buckets are never summed.
        return Map.of("requested", immutable(requested));
    }

    private static Map<String, Object> query(ObjectNode meta) {
        try {
            List<String> dimensions = DimensionQuery.dimensions(strings(meta.get("dimensions")));
            JsonNode source = meta.get("filters");
            require(source != null && source.isArray() && source.size() <= 8);
            List<Map<String, Object>> filters = new ArrayList<>();
            for (JsonNode value : source) {
                ObjectNode entry = object(value);
                entry.fieldNames().forEachRemaining(key -> require(Set.of("dimension", "operator", "values").contains(key)));
                Map<String, Object> filter = new LinkedHashMap<>();
                filter.put("dimension", text(entry.get("dimension")));
                filter.put("operator", text(entry.get("operator")));
                if (entry.has("values")) filter.put("values", entry.get("values").isNull() ? null : strings(entry.get("values")));
                filters.add(immutable(filter));
            }
            DimensionQuery.filters(filters); // Validate semantics without changing the frozen source representation.
            return Map.of("dimensions", dimensions, "filters", List.copyOf(filters));
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException(INVALID); }
    }

    private static Map<String, Object> snapshotIdentity(ObjectNode meta) {
        Map<String, Object> result = new LinkedHashMap<>();
        copyStrings(meta, result, List.of("snapshotId", "metricVersion", "recoveryEpoch"));
        copyIntegers(meta, result, List.of("snapshotCreatedAt", "snapshotExpiresAt", "generatedAt"));
        return immutable(result);
    }

    private static Map<String, Object> quality(ObjectNode meta) {
        Map<String, Object> value = new LinkedHashMap<>();
        copyStrings(meta, value, List.of("availability", "freshness", "completeness", "metricVersion", "dimensionQualityScope"));
        copyBooleans(meta, value, List.of("provisional"));
        if (meta.has("collectionQuality")) value.put("collectionQuality", qualityEntry(object(meta.get("collectionQuality"))));
        if (meta.has("dimensionQuality")) value.put("dimensionQuality", dimensionQuality(object(meta.get("dimensionQuality"))));
        if (meta.has("missingMetrics")) value.put("missingMetrics", strings(meta.get("missingMetrics")));
        if (meta.has("approximation")) {
            ObjectNode all = object(meta.get("approximation"));
            Map<String, Object> approximation = new LinkedHashMap<>();
            for (String key : APPROXIMATION_METRICS) if (all.has(key)) {
                ObjectNode entry = object(all.get(key));
                Map<String, Object> description = new LinkedHashMap<>();
                copyStrings(entry, description, List.of("type", "algorithm", "version"));
                approximation.put(key, immutable(description));
            }
            value.put("approximation", immutable(approximation));
        }
        return immutable(value);
    }

    private static Map<String, Object> dimensionQuality(ObjectNode source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : QUALITY_DIMENSIONS) if (source.has(key)) result.put(key, qualityEntry(object(source.get(key))));
        return immutable(result);
    }

    private static Map<String, Object> qualityEntry(ObjectNode source) {
        Map<String, Object> result = new LinkedHashMap<>();
        copyStrings(source, result, List.of("status", "semantic", "reason", "historicalBackfill"));
        copyIntegers(source, result, List.of("knownCount", "unknownCount", "eligibleCount", "notApplicableCount", "versionConflictCount"));
        if (source.has("coverage")) result.put("coverage", ratio(source.get("coverage")));
        if (source.has("reasonCounts")) {
            ObjectNode sourceReasons = object(source.get("reasonCounts"));
            Map<String, Object> reasons = new LinkedHashMap<>();
            sourceReasons.fields().forEachRemaining(entry -> reasons.put(entry.getKey(), integer(entry.getValue())));
            result.put("reasonCounts", immutable(reasons));
        }
        return immutable(result);
    }

    private static Map<String, Object> row(String kind, ObjectNode source, ObjectNode meta) {
        Map<String, Object> result;
        if ("ACCESS_RECORDS".equals(kind)) {
            result = new LinkedHashMap<>();
            require(integer(source.get("linkId")) > 0);
            copyIntegers(source, result, List.of("linkId", "occurredAt", "status"));
            copyStrings(source, result, List.of("kind", "browser", "os", "device", "country", "province", "network", "geoStatus", "uvType"));
            // No event/visitor/IP identifiers, URL, referrer, city or individual history fields.
        } else {
            result = counters(source);
            if ("DIMENSION_BREAKDOWN".equals(kind)) {
                ObjectNode cells = object(source.get("dimensions"));
                List<String> dimensions = strings(meta.get("dimensions"));
                require(!dimensions.isEmpty() && dimensions.size() <= 3 && Set.copyOf(dimensions).size() == dimensions.size()
                        && DIMENSIONS.containsAll(dimensions) && cells.size() == dimensions.size());
                Map<String, Object> projected = new LinkedHashMap<>();
                for (String key : dimensions) {
                    ObjectNode cell = object(cells.get(key));
                    String state = text(cell.get("state"));
                    require(Set.of("KNOWN", "UNKNOWN", "NOT_APPLICABLE").contains(state) && cell.has("value"));
                    Object label;
                    if ("KNOWN".equals(state)) label = text(cell.get("value"));
                    else { require(cell.get("value").isNull()); label = null; }
                    Map<String, Object> projectedCell = new LinkedHashMap<>();
                    projectedCell.put("state", state); projectedCell.put("value", label);
                    projected.put(key, immutable(projectedCell));
                }
                result.put("dimensions", immutable(projected));
                require(source.has("pvRatio"));
                result.put("pvRatio", ratio(source.get("pvRatio")));
            } else {
                require(integer(source.get("linkId")) > 0);
                copyIntegers(source, result, List.of("linkId", "startInclusive", "endExclusive"));
                copyStrings(source, result, List.of("day", "window"));
            }
        }
        return immutable(result);
    }

    private static Map<String, Object> counters(ObjectNode source) {
        Map<String, Object> result = new LinkedHashMap<>();
        // A projection is not a quality repair: absent counters stay absent, explicit unknown
        // null stays null, and only actual integer values are accepted as numeric observations.
        copyIntegers(source, result, List.of("pv", "uv", "uip", "denied"));
        return result;
    }

    private static void copyStrings(ObjectNode source, Map<String, Object> target, List<String> keys) {
        for (String key : keys) if (source.has(key)) {
            JsonNode value = source.get(key);
            require(value.isTextual() || value.isNull());
            target.put(key, value.isNull() ? null : value.textValue());
        }
    }

    private static void copyIntegers(ObjectNode source, Map<String, Object> target, List<String> keys) {
        for (String key : keys) if (source.has(key)) target.put(key, source.get(key).isNull() ? null : integer(source.get(key)));
    }

    private static void copyBooleans(ObjectNode source, Map<String, Object> target, List<String> keys) {
        for (String key : keys) if (source.has(key)) target.put(key, source.get(key).isNull() ? null : bool(source.get(key)));
    }

    private static List<String> strings(JsonNode source) {
        require(source != null && source.isArray());
        List<String> values = new ArrayList<>();
        for (JsonNode value : source) values.add(text(value));
        return List.copyOf(values);
    }

    private static Object ratio(JsonNode value) {
        require(value != null);
        if (value.isNull()) return null;
        require(value.isNumber());
        var decimal = value.decimalValue();
        require(decimal.signum() >= 0 && decimal.compareTo(java.math.BigDecimal.ONE) <= 0);
        return decimal;
    }

    private static long integer(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0);
        return value.longValue();
    }

    private static String text(JsonNode value) {
        require(value != null && value.isTextual() && !value.textValue().isBlank());
        return value.textValue();
    }

    private static boolean bool(JsonNode value) {
        require(value != null && value.isBoolean());
        return value.booleanValue();
    }

    private static ObjectNode object(String json) {
        try { return object(JSON.readTree(json)); }
        catch (IOException | IllegalArgumentException invalid) { throw new IllegalStateException(INVALID); }
    }

    private static ObjectNode object(JsonNode value) {
        require(value != null && value.isObject());
        return (ObjectNode) value;
    }

    private static Map<String, Object> immutable(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private void requireSize(Map<String, Object> value) {
        var sink = new ByteBudget(limits.maxProjectionBytes());
        try { JSON.writeValue(sink, value); }
        catch (IOException invalid) { throw new IllegalStateException(sink.exceeded ? TOO_LARGE : INVALID); }
    }

    private static final class ByteBudget extends OutputStream {
        private final long limit;
        private long bytes;
        private boolean exceeded;
        private ByteBudget(long limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException { count(1); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { count(length); }
        private void count(int length) throws IOException {
            if (length > limit - bytes) { exceeded = true; throw new IOException(TOO_LARGE); }
            bytes += length;
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException(INVALID);
    }
}
