package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignDimensionEvidence.VerifiedQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Period;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Slot;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete paged observed joint-bucket changes; each summary is one fixed shard cohort. */
public final class DimensionChangePublisher {
    public static final String PAGE_TYPE = "DimensionChangePageArtifact";
    public static final String PAGE_SCHEMA = "campaign.dimension-change-page/v1";
    public static final String TYPE = "DimensionChangeArtifact";
    public static final String SCHEMA = "campaign.dimension-change/v1";
    private static final String IMPLEMENTATION = CampaignRunStore.sha256(
            "dimension-change/v1:joint-buckets:complete-query-zero:observed-only:decimal128:shard-cohort:paged");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final CampaignRunStore runs;
    private final CampaignSelectedScope scopes;
    private final CampaignDimensionEvidence evidence;
    private final ArtifactAuthorizer authorizer;

    public record Definition(String collectionId, String selectedScopeArtifactId, String periodsRef,
                             List<String> dimensions, List<Map<String, Object>> filters) {
        public Definition {
            require(collectionId != null && !collectionId.isBlank() && collectionId.length() <= 256
                    && selectedScopeArtifactId != null && !selectedScopeArtifactId.isBlank()
                    && periodsRef != null && !periodsRef.isBlank(), "DIMENSION_DEFINITION_INVALID");
            require(dimensions != null && dimensions.equals(List.of("province", "device")), "DIMENSION_JOINT_CONTRACT_INVALID");
            dimensions = List.copyOf(dimensions);
            require(filters != null, "DIMENSION_FILTERS_INVALID");
            require(filters.stream().allMatch(filter -> filter != null
                    && java.util.Set.of("dimension", "operator", "values").containsAll(filter.keySet())), "DIMENSION_FILTERS_INVALID");
            filters = DimensionQuery.filters(filters).stream().map(Map::copyOf).toList();
        }
    }
    public record Prepared(ChildSpec child, Approval approval, CampaignStepExecution.LocalCall calculation) {}
    public record Position(int shardIndex, int side, int pageIndex, String previousArtifactId, boolean complete) {}

    public DimensionChangePublisher(CampaignRunStore runs, CampaignSelectedScope scopes,
                                    CampaignDimensionEvidence evidence, ArtifactAuthorizer authorizer) {
        this.runs = Objects.requireNonNull(runs); this.scopes = Objects.requireNonNull(scopes);
        this.evidence = Objects.requireNonNull(evidence); this.authorizer = Objects.requireNonNull(authorizer);
    }

    /** Rebuilds the first unfinished position from authorized, durable LOCAL outputs without recalculation. */
    public Position progress(RunToken token, String stepId, Definition definition) {
        Artifact scope = scopes.inspect(token.definition().caller(), definition.selectedScopeArtifactId());
        require(definition.periodsRef().equals(scope.metadata().ref().periodsRef()), "DIMENSION_PERIODS_MISMATCH");
        JsonNode scopeBody = tree(scope.payloadJson());
        int shards = positionInteger(scopeBody, "shardCount");
        require(shards >= 0, "DIMENSION_PAGE_CHAIN_INVALID");
        int shard = 0, side = 0, pageIndex = 0, ordinal = 0;
        Artifact previous = null;
        JsonNode previousBody = null;
        while (shard < shards) {
            String identity = identity(token, stepId, definition.collectionId(), shard + ":" + side + ":" + pageIndex);
            String childId = "dimension-child-" + identity;
            var child = runs.child(token, childId);
            if (child.isEmpty() || child.get().state() != ChildState.READY)
                return new Position(shard, side, pageIndex,
                        previous == null ? null : previous.metadata().ref().artifactId(), false);
            Artifact current = readPage(token, stepId, "dimension-" + identity, definition);
            require(childId.equals(current.metadata().childId()), "DIMENSION_PAGE_PRODUCER_INVALID");
            require(scope.metadata().ref().scopeRef().equals(current.metadata().ref().scopeRef())
                    && definition.periodsRef().equals(current.metadata().ref().periodsRef()), "DIMENSION_SOURCE_CHANGED");
            JsonNode body = tree(current.payloadJson());
            require(positionInteger(body, "shardIndex") == shard && positionInteger(body, "side") == side
                    && positionInteger(body, "pageIndex") == pageIndex && positionInteger(body, "ordinal") == ordinal
                    && body.path("rows").isArray() && body.path("rows").size() <= 500, "DIMENSION_PAGE_CHAIN_INVALID");
            int baselinePages = positionInteger(body, "baselinePageCount");
            int targetPages = positionInteger(body, "targetPageCount");
            require(baselinePages > 0 && baselinePages <= 10 && targetPages > 0 && targetPages <= 10,
                    "DIMENSION_PAGE_CHAIN_INVALID");
            for (String source : List.of("baseline", "target")) {
                require(body.path(source + "ArtifactId").isTextual() && !body.path(source + "ArtifactId").asText().isBlank()
                        && body.path(source + "PayloadHash").isTextual()
                        && body.path(source + "PayloadHash").asText().matches("[0-9a-f]{64}"), "DIMENSION_SOURCE_CHANGED");
            }
            require(previous == null
                    ? body.path("previousArtifactId").isNull() && body.path("previousPayloadHash").isNull()
                    : previous.metadata().ref().artifactId().equals(body.path("previousArtifactId").textValue())
                        && previous.metadata().ref().payloadHash().equals(body.path("previousPayloadHash").textValue())
                        && !current.metadata().ref().expiresAt().isAfter(previous.metadata().ref().expiresAt()),
                    "DIMENSION_PAGE_CHAIN_INVALID");
            if (previousBody != null && previousBody.path("shardIndex").intValue() == shard) {
                for (String field : List.of("baselineArtifactId", "baselinePayloadHash", "targetArtifactId", "targetPayloadHash",
                        "baselinePageCount", "targetPageCount"))
                    require(body.path(field).equals(previousBody.path(field)), "DIMENSION_SOURCE_CHANGED");
            }
            previous = current; previousBody = body; ordinal = Math.addExact(ordinal, 1);
            pageIndex++;
            if (pageIndex == (side == 0 ? baselinePages : targetPages)) {
                pageIndex = 0;
                if (side == 0) side = 1;
                else { side = 0; shard++; }
            }
        }
        return new Position(shards, 0, 0, previous == null ? null : previous.metadata().ref().artifactId(), true);
    }

    public String finalArtifactId(RunToken token, String stepId, Definition definition) {
        return "dimension-" + identity(token, stepId, definition.collectionId(), "final");
    }

    private static int positionInteger(JsonNode body, String field) {
        JsonNode value = body.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt(), "DIMENSION_PAGE_CHAIN_INVALID");
        return value.intValue();
    }

    public ArtifactRef publishPage(CampaignStepExecution context, RunToken token, Definition definition,
                                   Slot baseline, Slot target, int shardIndex, int side, int pageIndex,
                                   String previousArtifactId) throws Exception {
        context.requireCurrent();
        Prepared prepared = preparePage(token, context.step().stepId(), definition, baseline, target,
                shardIndex, side, pageIndex, previousArtifactId);
        return context.local(prepared.child(), prepared.approval(), authorizer, prepared.calculation()).get("dimensionPage");
    }

    public Prepared preparePage(RunToken token, String stepId, Definition definition, Slot baseline, Slot target,
                                int shardIndex, int side, int pageIndex, String previousArtifactId) {
        require(side == 0 || side == 1, "DIMENSION_SIDE_INVALID");
        Artifact scope = scopes.inspect(token.definition().caller(), definition.selectedScopeArtifactId());
        JsonNode scopeBody = tree(scope.payloadJson());
        require(definition.periodsRef().equals(scope.metadata().ref().periodsRef()), "DIMENSION_PERIODS_MISMATCH");
        var shard = scopes.shard(token.definition().caller(), definition.selectedScopeArtifactId(), shardIndex);
        List<Period> periods = periods(scopeBody);
        VerifiedQuery base = evidence.verify(token.definition().caller(), baseline, shard, periods.get(0),
                definition.dimensions(), definition.filters(), authorizer);
        VerifiedQuery current = evidence.verify(token.definition().caller(), target, shard, periods.get(1),
                definition.dimensions(), definition.filters(), authorizer);
        require(scopeBody.path("gid").asText().equals(base.request().get("gid"))
                && scopeBody.path("gid").asText().equals(current.request().get("gid")), "DIMENSION_GID_MISMATCH");
        require(pageIndex >= 0 && pageIndex < (side == 0 ? pages(base) : pages(current)), "DIMENSION_PAGE_INVALID");
        Artifact previous = previousArtifactId == null ? null : readPage(token, stepId, previousArtifactId, definition);
        int ordinal = previous == null ? 0 : Math.addExact(tree(previous.payloadJson()).path("ordinal").intValue(), 1);
        requireNext(previous, shardIndex, side, pageIndex, base, current);
        Map<String, ArtifactMetadata> inputs = new LinkedHashMap<>();
        inputs.put("selectedScope", scope.metadata()); inputs.put("baseline", base.metadata()); inputs.put("target", current.metadata());
        if (previous != null) inputs.put("previous", previous.metadata());
        String identity = identity(token, stepId, definition.collectionId(), shardIndex + ":" + side + ":" + pageIndex);
        Map<String, CampaignLinkComparability.QueryAssessment> comparability = new LinkedHashMap<>();
        for (var metric : CampaignLinkComparability.Metric.values()) comparability.put(metric.name(),
                CampaignLinkComparability.assessQuery(comparisonPeriod(periods.get(0)), comparisonPeriod(periods.get(1)),
                        observation(base), observation(current), metric));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", PAGE_SCHEMA); payload.put("definition", definition); payload.put("ordinal", ordinal);
        payload.put("shardIndex", shardIndex); payload.put("side", side); payload.put("pageIndex", pageIndex);
        payload.put("baselinePageCount", pages(base)); payload.put("targetPageCount", pages(current));
        payload.put("previousArtifactId", previousArtifactId);
        payload.put("previousPayloadHash", previous == null ? null : previous.metadata().ref().payloadHash());
        payload.put("analysisUnit", "SHARD_COHORT"); payload.put("interpretation", "OBSERVED_ONLY");
        payload.put("baselineArtifactId", base.metadata().ref().artifactId()); payload.put("baselinePayloadHash", base.metadata().ref().payloadHash());
        payload.put("targetArtifactId", current.metadata().ref().artifactId()); payload.put("targetPayloadHash", current.metadata().ref().payloadHash());
        payload.put("cohortMemberCount", shard.linkIds().size());
        payload.put("cohortSummary", Map.of("baseline", base.metrics().get("requested"), "target", current.metrics().get("requested")));
        payload.put("quality", Map.of("baseline", base.snapshot(), "target", current.snapshot()));
        payload.put("comparability", comparability);
        payload.put("limitations", List.of("OBSERVED_ONLY", "CROSS_QUERY_SNAPSHOTS_NOT_A_COMMON_DATABASE_SNAPSHOT",
                "COHORT_UV_UIP_MUST_NOT_BE_SUMMED", "SOURCE_SELECTION_QUALITY_PRESERVED"));
        String parameters = json(Map.of("definition", definition, "shardIndex", shardIndex, "side", side, "pageIndex", pageIndex));
        return prepare(identity, "dimension-change-page", "dimensionPage", PAGE_TYPE, PAGE_SCHEMA,
                scope.metadata().ref().scopeRef(), definition.periodsRef(), inputs, parameters, payload, boundary -> {
                    require(base.metadata().equals(runs.inspectArtifact(token.definition().caller(), baseline.artifactId(), authorizer))
                            && current.metadata().equals(runs.inspectArtifact(token.definition().caller(), target.artifactId(), authorizer)),
                            "DIMENSION_SOURCE_CHANGED");
                }, () -> compareRows(token.definition().caller(), base, current, side, pageIndex,
                        comparability.get("PV").comparability() == CampaignLinkComparability.Comparability.VERIFIED));
    }

    public ArtifactRef finish(CampaignStepExecution context, RunToken token, Definition definition,
                              String headArtifactId) throws Exception {
        context.requireCurrent();
        Prepared prepared = prepareFinal(token, context.step().stepId(), definition, headArtifactId);
        return context.local(prepared.child(), prepared.approval(), authorizer, prepared.calculation()).get("dimensionChanges");
    }

    public Prepared prepareFinal(RunToken token, String stepId, Definition definition, String headArtifactId) {
        Artifact scope = scopes.inspect(token.definition().caller(), definition.selectedScopeArtifactId());
        JsonNode source = tree(scope.payloadJson());
        require(definition.periodsRef().equals(scope.metadata().ref().periodsRef()), "DIMENSION_PERIODS_MISMATCH");
        long members = source.path("memberCount").longValue();
        int shards = source.path("shardCount").intValue();
        require((members == 0) == (headArtifactId == null), "DIMENSION_HEAD_REQUIRED");
        Map<String, ArtifactMetadata> inputs = new LinkedHashMap<>(); inputs.put("selectedScope", scope.metadata());
        long comparisonRows = 0; int pageCount = 0;
        Artifact head = null;
        if (headArtifactId != null) {
            head = readPage(token, stepId, headArtifactId, definition); inputs.put("head", head.metadata());
            JsonNode last = tree(head.payloadJson());
            require(last.path("shardIndex").intValue() == shards - 1 && last.path("side").intValue() == 1
                    && last.path("pageIndex").intValue() == last.path("targetPageCount").intValue() - 1,
                    "DIMENSION_PAGES_INCOMPLETE");
            int expected = last.path("ordinal").intValue();
            Artifact page = head;
            while (page != null) {
                JsonNode body = tree(page.payloadJson());
                require(body.path("ordinal").intValue() == expected-- && body.path("rows").isArray()
                        && body.path("rows").size() <= 500, "DIMENSION_PAGE_CHAIN_INVALID");
                pageCount = Math.addExact(pageCount, 1); comparisonRows = Math.addExact(comparisonRows, body.path("rows").size());
                String previousId = body.path("previousArtifactId").isNull() ? null : body.path("previousArtifactId").asText();
                Artifact previous = previousId == null ? null : readPage(token, stepId, previousId, definition);
                require(previous == null ? expected == -1 && body.path("previousPayloadHash").isNull()
                        : previous.metadata().ref().payloadHash().equals(body.path("previousPayloadHash").asText())
                            && !page.metadata().ref().expiresAt().isAfter(previous.metadata().ref().expiresAt()),
                        "DIMENSION_PAGE_CHAIN_INVALID");
                page = previous;
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA); payload.put("definition", definition);
        payload.put("scopeRef", scope.metadata().ref().scopeRef()); payload.put("periodsRef", definition.periodsRef());
        payload.put("periods", periods(source)); payload.put("memberCount", members); payload.put("coveredCohorts", shards);
        payload.put("pageCount", pageCount); payload.put("comparisonRows", comparisonRows);
        payload.put("headArtifactId", headArtifactId); payload.put("headPayloadHash", head == null ? null : head.metadata().ref().payloadHash());
        payload.put("selectionComplete", source.path("selectionComplete").booleanValue());
        payload.put("emptyReason", source.path("emptyReason").isNull() ? null : source.path("emptyReason").asText());
        payload.put("analysisUnit", "SHARD_COHORT"); payload.put("interpretation", "OBSERVED_ONLY");
        payload.put("evidenceDisposition", members > 0 ? "OBSERVED" : "NO_DECLINES".equals(source.path("emptyReason").asText())
                ? "NOT_APPLICABLE" : "INSUFFICIENT_EVIDENCE");
        payload.put("groupScopeComplete", false);
        return prepare(identity(token, stepId, definition.collectionId(), "final"), "dimension-change-final", "dimensionChanges",
                TYPE, SCHEMA, scope.metadata().ref().scopeRef(), definition.periodsRef(), inputs,
                json(Map.of("definition", definition)), payload, boundary -> {}, null);
    }

    private List<Map<String, Object>> compareRows(Caller caller, VerifiedQuery baseline, VerifiedQuery target,
                                                 int side, int pageIndex, boolean comparablePv) {
        VerifiedQuery selected = side == 0 ? baseline : target, other = side == 0 ? target : baseline;
        List<JsonNode> page = evidence.readRows(caller, selected, pageIndex, authorizer);
        Map<String, JsonNode> matching = new LinkedHashMap<>();
        for (JsonNode row : page) matching.put(key(row), null);
        for (int index = 0; index < pages(other); index++) {
            for (JsonNode row : evidence.readRows(caller, other, index, authorizer))
                if (matching.containsKey(key(row))) matching.put(key(row), row);
        }
        List<Map<String, Object>> result = new ArrayList<>(page.size());
        for (JsonNode row : page) {
            JsonNode counterpart = matching.get(key(row));
            if (side == 1 && counterpart != null) continue;
            JsonNode base = side == 0 ? row : counterpart, current = side == 0 ? counterpart : row;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("dimensions", row.get("dimensions"));
            values.put("baseline", counts(base)); values.put("target", counts(current));
            values.put("presence", base == null ? "TARGET_ONLY" : current == null ? "BASELINE_ONLY" : "BOTH");
            BigInteger basePv = base == null ? BigInteger.ZERO : base.path("pv").bigIntegerValue();
            BigInteger targetPv = current == null ? BigInteger.ZERO : current.path("pv").bigIntegerValue();
            BigInteger delta = targetPv.subtract(basePv);
            values.put("pvDelta", delta);
            values.put("pvRelativeChange", basePv.signum() == 0 || !comparablePv ? null
                    : new BigDecimal(delta).divide(new BigDecimal(basePv), MathContext.DECIMAL128));
            values.put("pvShareChange", ratio(current).subtract(ratio(base)));
            result.add(Collections.unmodifiableMap(values));
        }
        return List.copyOf(result);
    }

    private Artifact readPage(RunToken token, String stepId, String id, Definition definition) {
        Artifact artifact = runs.readArtifact(token.definition().caller(), id, authorizer);
        var meta = artifact.metadata();
        require(PAGE_TYPE.equals(meta.ref().type()) && PAGE_SCHEMA.equals(meta.ref().schemaVersion())
                && token.definition().runId().equals(meta.runId()) && token.definition().revision() == meta.revision(),
                "DIMENSION_PAGE_PRODUCER_INVALID");
        ChildRecord child = runs.child(token, meta.childId()).orElseThrow();
        require(child.spec().mode() == ChildMode.LOCAL && child.state() == ChildState.READY
                && "dimension-change-page".equals(child.spec().localInvocation().contractName())
                && IMPLEMENTATION.equals(child.spec().localInvocation().implementationHash())
                && meta.ref().equals(runs.localOutputs(token, child.spec().childId(), authorizer).get("dimensionPage")),
                "DIMENSION_PAGE_PRODUCER_INVALID");
        JsonNode body = tree(artifact.payloadJson());
        require(tree(json(definition)).equals(body.path("definition")), "DIMENSION_DEFINITION_CHANGED");
        String position = body.path("shardIndex").intValue() + ":" + body.path("side").intValue() + ":" + body.path("pageIndex").intValue();
        require(id.equals("dimension-" + identity(token, stepId, definition.collectionId(), position)), "DIMENSION_PAGE_PRODUCER_INVALID");
        return artifact;
    }

    private static void requireNext(Artifact previous, int shard, int side, int page, VerifiedQuery baseline, VerifiedQuery target) {
        if (previous == null) { require(shard == 0 && side == 0 && page == 0, "DIMENSION_PAGE_CHAIN_INVALID"); return; }
        JsonNode last = tree(previous.payloadJson());
        int lastSide = last.path("side").intValue(), lastPage = last.path("pageIndex").intValue();
        int lastCount = last.path(lastSide == 0 ? "baselinePageCount" : "targetPageCount").intValue();
        int nextShard = last.path("shardIndex").intValue(), nextSide = lastSide, nextPage = lastPage + 1;
        if (nextPage == lastCount) { nextPage = 0; if (lastSide == 0) nextSide = 1; else { nextSide = 0; nextShard++; } }
        require(shard == nextShard && side == nextSide && page == nextPage, "DIMENSION_PAGE_CHAIN_INVALID");
        if (shard == last.path("shardIndex").intValue())
            require(last.path("baselineArtifactId").asText().equals(baseline.metadata().ref().artifactId())
                    && last.path("targetArtifactId").asText().equals(target.metadata().ref().artifactId())
                    && last.path("baselinePayloadHash").asText().equals(baseline.metadata().ref().payloadHash())
                    && last.path("targetPayloadHash").asText().equals(target.metadata().ref().payloadHash())
                    && last.path("baselinePageCount").intValue() == pages(baseline)
                    && last.path("targetPageCount").intValue() == pages(target), "DIMENSION_SOURCE_CHANGED");
    }

    @FunctionalInterface private interface Recheck { void verify(CampaignStepExecution.LocalBoundary boundary); }
    private Prepared prepare(String identity, String contractName, String outputName, String type, String schema,
                             String scopeRef, String periodsRef, Map<String, ArtifactMetadata> inputs,
                             String parameters, Map<String, Object> payload, Recheck recheck,
                             java.util.function.Supplier<List<Map<String, Object>>> rowsSupplier) {
        Instant expiry = inputs.values().stream().map(value -> value.ref().expiresAt()).min(Instant::compareTo).orElseThrow();
        OutputBinding output = new OutputBinding("dimension-" + identity, type, schema, scopeRef, periodsRef);
        InvocationSpec invocation = new InvocationSpec(contractName, "1", IMPLEMENTATION, parameters, inputs, Map.of(outputName, output), expiry);
        Map<String, TypeContract> contracts = new LinkedHashMap<>();
        inputs.forEach((name, metadata) -> contracts.put(name, new TypeContract(metadata.ref().type(), metadata.ref().schemaVersion())));
        String encoded = json(payload);
        Contract contract = new Contract(contractName, "1", IMPLEMENTATION, contracts,
                Map.of(outputName, new TypeContract(type, schema)), value -> value.equals(tree(parameters)),
                values -> validOutput(values.get(outputName), tree(encoded), rowsSupplier != null));
        Approval approval = new LocalCalculationRegistry(List.of(contract)).approve(invocation);
        ChildSpec child = new ChildSpec("dimension-child-" + identity, "dimension-action-" + identity, ChildMode.LOCAL,
                "dimension-request-" + identity, null, invocation);
        return new Prepared(child, approval, boundary -> {
            for (String name : inputs.keySet()) boundary.readInput(name);
            recheck.verify(boundary); boundary.requireCurrent();
            String body = encoded;
            if (rowsSupplier != null) {
                Map<String, Object> calculated = new LinkedHashMap<>(payload);
                calculated.put("rows", rowsSupplier.get());
                boundary.requireCurrent(); body = json(calculated);
            }
            return Map.of(outputName, new ArtifactDraft(output.artifactId(), type, schema, scopeRef, periodsRef,
                    "{\"interpretation\":\"OBSERVED_ONLY\",\"collectionCompleteness\":\"UNVERIFIED\"}",
                    "{\"calculator\":\"dimension-change/v1\",\"analysisUnit\":\"SHARD_COHORT\"}", expiry, body));
        });
    }
    private static boolean validOutput(JsonNode value, JsonNode expected, boolean paged) {
        if (!paged) return value.equals(expected);
        if (!value.isObject() || !value.path("rows").isArray() || value.path("rows").size() > 500) return false;
        var header = ((com.fasterxml.jackson.databind.node.ObjectNode) value).deepCopy();
        JsonNode rows = header.remove("rows");
        if (!header.equals(expected)) return false;
        for (JsonNode row : rows) {
            var fields = new java.util.HashSet<String>(); row.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(java.util.Set.of("dimensions", "baseline", "target", "presence", "pvDelta", "pvRelativeChange", "pvShareChange"))
                    || !row.path("dimensions").isObject() || !row.path("baseline").isObject() || !row.path("target").isObject()
                    || !java.util.Set.of("BOTH", "BASELINE_ONLY", "TARGET_ONLY").contains(row.path("presence").asText())
                    || !row.path("pvDelta").isIntegralNumber() || !row.path("pvShareChange").isNumber()
                    || !(row.path("pvRelativeChange").isNull() || row.path("pvRelativeChange").isNumber())) return false;
        }
        return true;
    }
    private static Map<String, Object> counts(JsonNode row) {
        return row == null ? Map.of("pv", 0, "uv", 0, "uip", 0, "pvRatio", BigDecimal.ZERO)
                : Map.of("pv", row.get("pv"), "uv", row.get("uv"), "uip", row.get("uip"), "pvRatio", row.get("pvRatio"));
    }
    private static int pages(VerifiedQuery query) { return Math.max(1, query.pageCount()); }
    private static CampaignLinkComparability.Period comparisonPeriod(Period value) {
        return new CampaignLinkComparability.Period(value.startInclusive(), value.endExclusive(), value.timeZone());
    }
    private static CampaignLinkComparability.QueryObservation observation(VerifiedQuery query) {
        JsonNode snapshot = query.snapshot();
        Map<String, Object> quality = new LinkedHashMap<>();
        for (String field : List.of("availability", "completeness", "freshness", "provisional",
                "collectionQuality", "missingMetrics", "approximation"))
            if (snapshot.has(field)) quality.put(field, JSON.convertValue(snapshot.get(field), Object.class));
        return new CampaignLinkComparability.QueryObservation(query.metadata().ref().artifactId(),
                tree(query.metadata().provenanceJson()).path("requestHash").asText(), nullableText(snapshot, "snapshotId"),
                nullableLong(snapshot, "snapshotCreatedAt"), nullableLong(snapshot, "effectiveEnd"),
                nullableText(snapshot, "metricVersion"), nullableText(snapshot, "recoveryEpoch"),
                object(snapshot.get("sourceCut")), object(snapshot.get("manifestVersion")), quality);
    }
    private static String nullableText(JsonNode value, String field) {
        if (!value.hasNonNull(field)) return null;
        require(value.path(field).isTextual() && !value.path(field).asText().isBlank(), "DIMENSION_OBSERVATION_INVALID");
        return value.path(field).textValue();
    }
    private static Long nullableLong(JsonNode value, String field) {
        if (!value.hasNonNull(field)) return null;
        require(value.path(field).isIntegralNumber() && value.path(field).canConvertToLong()
                && value.path(field).longValue() >= 0, "DIMENSION_OBSERVATION_INVALID");
        return value.path(field).longValue();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(JsonNode value) {
        if (value == null || value.isNull()) return Map.of();
        require(value.isObject(), "DIMENSION_OBSERVATION_INVALID");
        return JSON.convertValue(value, Map.class);
    }
    private static BigDecimal ratio(JsonNode row) { return row == null ? BigDecimal.ZERO : row.path("pvRatio").decimalValue(); }
    private static String key(JsonNode row) {
        JsonNode province = row.path("dimensions").path("province"), device = row.path("dimensions").path("device");
        return json(java.util.Arrays.asList(province.path("state").textValue(), province.path("value").textValue(),
                device.path("state").textValue(), device.path("value").textValue()));
    }
    private static List<Period> periods(JsonNode scope) {
        List<Period> periods = new ArrayList<>(2);
        for (JsonNode period : scope.path("periods")) periods.add(new Period(period.path("periodsRef").asText(),
                period.path("startDate").asText(), period.path("endDate").asText(), period.path("timeZone").asText()));
        require(periods.size() == 2, "DIMENSION_PERIODS_MISMATCH"); return List.copyOf(periods);
    }
    private static String identity(RunToken token, String step, String collection, String part) {
        return CampaignRunStore.sha256(json(List.of(token.definition().caller(), token.definition().runId(),
                token.definition().planId(), token.definition().revision(), step, collection, part)));
    }
    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DIMENSION_JSON_INVALID", invalid); }
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DIMENSION_JSON_INVALID", invalid); }
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
