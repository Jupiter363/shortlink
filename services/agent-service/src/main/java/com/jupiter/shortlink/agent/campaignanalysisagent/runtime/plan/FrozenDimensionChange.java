package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Artifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore.SelectionPair;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.WireRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Period;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure frozen bindings; fixed producers and native CALLs obtain actual source authority in their runtime adapters. */
public final class FrozenDimensionChange {
    public static final PlanSpec.ExecutorRef REF = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL,
            "dimension_change", "1");
    public static final PlanSpec.ExecutorRef REF_V2 = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL,
            "dimension_change", "2");
    public static final PlanSpec.ExecutorRef REF_V3 = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL,
            "dimension_change", "3");
    public static final String OUTPUT_CONTRACT = "campaign.dimension-change/v1";
    public static final String SCHEMA = "dimension-change-definition/v1";
    public static final String SCHEMA_V2 = "dimension-change-definition/v2";
    public static final String SCHEMA_V3 = "dimension-change-definition/v3";
    public static final Map<String, Port> INPUTS = Map.of(
            "scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
            "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
            "definition", new Port(new TypeRef("DimensionChangeDefinition", 1, Cardinality.ONE), true),
            "selectedEntities", new Port(new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE), true),
            "selectionEvidence", new Port(new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE), true));
    public static final Map<String, Port> INPUTS_V2 = Map.of(
            "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
            "definition", new Port(new TypeRef("DimensionChangeDefinition", 2, Cardinality.ONE), true),
            "selectedEntities", new Port(new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE), true),
            "selectionEvidence", new Port(new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE), true));
    public static final Map<String, Port> INPUTS_V3 = Map.of(
            "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
            "definition", new Port(new TypeRef("DimensionChangeDefinition", 3, Cardinality.ONE), true),
            "selectedEntities", new Port(new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE), true),
            "selectionEvidence", new Port(new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE), true));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonMapper CALL_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> CALL_BINDING_FIELDS = Set.of("source", "input", "stepId", "output", "artifactId");
    private static final Set<String> OUTPUT_INPUTS = Set.of("selectedEntities", "selectionEvidence");
    private static final Set<String> FIELDS = Set.of("schemaVersion", "scopeRef", "periodsRef", "gid",
            "baseline", "target", "dimensions", "filters", "skillPin");
    private static final Set<String> FIELDS_V2 = Set.of("schemaVersion", "periodsRef", "gid",
            "baseline", "target", "dimensions", "filters", "skillPin");
    private static final Set<String> PERIOD_FIELDS = Set.of("periodsRef", "startDate", "endDate", "timeZone");
    private static final Set<String> PIN_FIELDS = Set.of("name", "version", "relativeDirectory", "sha256");
    private static final Set<String> FILTER_FIELDS = Set.of("dimension", "operator", "values");
    private static final List<String> JOINT_DIMENSIONS = List.of("province", "device");

    private FrozenDimensionChange() {}

    /** v2 has no scope reference until its actual successful producer's pair is inspected. */
    public record Template(PlanSpec.Step step, String upstreamStepId, String frozenScopeRef, String periodsRef,
                           String gid, List<Period> periods, List<String> dimensions,
                           List<Map<String, Object>> filters, RunPinnedSkills.SkillPin skillPin,
                           String collectionId, Map<String, Object> descriptor) {
        public Template {
            periods = List.copyOf(periods);
            dimensions = List.copyOf(dimensions);
            filters = filters.stream().map(FrozenDimensionChange::immutableObject).toList();
            descriptor = immutableObject(descriptor);
        }
    }

    public record Bound(PlanSpec.Step step, String upstreamStepId, String scopeRef, String periodsRef,
                        String gid, List<Period> periods, List<String> dimensions,
                        List<Map<String, Object>> filters, RunPinnedSkills.SkillPin skillPin,
                        String collectionId, Map<String, Object> descriptor) {
        public Bound {
            periods = List.copyOf(periods);
            dimensions = List.copyOf(dimensions);
            filters = filters.stream().map(FrozenDimensionChange::immutableObject).toList();
            descriptor = immutableObject(descriptor);
        }
    }

    public record BoundQuery(ChildSpec child, StatisticsJobResultReceiver.Target target,
                             Map<String, Object> request, String periodsRef) {
        public BoundQuery { request = immutableObject(request); }
    }

    /** Frozen data only; actual CALL, source MODEL visibility and current authority remain runtime checks. */
    public record CallBound(PlanSpec.Step step, CallSpec call, String scopeRef, String periodsRef, String gid,
                            String selectedArtifactId, String evidenceArtifactId, List<Period> periods,
                            List<String> dimensions, List<Map<String, Object>> filters, RunPinnedSkills.SkillPin skillPin,
                            String collectionId, Map<String, Object> descriptor) {
        public CallBound {
            periods = List.copyOf(periods);
            dimensions = List.copyOf(dimensions);
            filters = filters.stream().map(FrozenDimensionChange::immutableObject).toList();
            descriptor = immutableObject(descriptor);
        }
    }

    /**
     * The adapter first resolveLocal-checks actual MODEL-visible inputs and obtains the currently
     * authorized sealed pair and original scope. A producing CALL need not finish its outer Step.
     */
    public static CallBound prepareCall(RunDefinition definition, CallSpec call, BoundInputs inputs,
                                        SelectionPair pair, Artifact originalScope) {
        require(inputs != null && inputs.values().keySet().equals(INPUTS_V3.keySet())
                && inputs.artifacts().keySet().equals(OUTPUT_INPUTS));
        CallBound bound = callDefinition(definition, call);
        require(bound.periodsRef().equals(inputs.value("periods"))
                && bound.descriptor().equals(inputs.value("definition"))
                && pair != null && originalScope != null
                && pair.selectedEntities() != null && pair.selectionEvidence() != null && pair.scopeArtifact() != null
                && pair.definition() != null && originalScope.payloadJson() != null
                && pair.selectedEntities().equals(inputs.artifact("selectedEntities").metadata())
                && pair.selectionEvidence().equals(inputs.artifact("selectionEvidence").metadata())
                && pair.scopeArtifact().equals(originalScope.metadata()));
        ArtifactMetadata selected = pair.selectedEntities(), evidence = pair.selectionEvidence(), scope = pair.scopeArtifact();
        require(sameRun(definition, selected) && sameRun(definition, evidence)
                && definition.caller().equals(scope.owner())
                && selected.childId().equals(evidence.childId()) && selected.actionId().equals(evidence.actionId())
                && selected.executorVersion().equals(evidence.executorVersion())
                && bound.selectedArtifactId().equals(selected.ref().artifactId())
                && bound.evidenceArtifactId().equals(evidence.ref().artifactId())
                && !bound.selectedArtifactId().equals(bound.evidenceArtifactId())
                && "SelectedEntitiesArtifact".equals(selected.ref().type())
                && "campaign.selected-entities/v1".equals(selected.ref().schemaVersion())
                && "DeclineEvidenceArtifact".equals(evidence.ref().type())
                && "campaign.decline-evidence/v1".equals(evidence.ref().schemaVersion())
                && "ScopeArtifact".equals(scope.ref().type()) && "campaign-scope/v1".equals(scope.ref().schemaVersion())
                && bound.scopeRef().equals(pair.definition().scopeRef())
                && bound.scopeRef().equals(selected.ref().scopeRef())
                && bound.scopeRef().equals(evidence.ref().scopeRef()) && bound.scopeRef().equals(scope.ref().scopeRef())
                && bound.periodsRef().equals(pair.definition().periodsRef())
                && bound.periodsRef().equals(selected.ref().periodsRef())
                && bound.periodsRef().equals(evidence.ref().periodsRef())
                && bound.periods().equals(pair.periods())
                && pair.definition().scopeArtifactId().equals(scope.ref().artifactId())
                && CampaignRunStore.sha256(originalScope.payloadJson()).equals(scope.ref().payloadHash()));
        JsonNode source;
        try { source = CALL_JSON.readTree(originalScope.payloadJson()); }
        catch (JsonProcessingException invalid) { throw invalid(); }
        require(source != null && source.isObject() && "campaign-scope/v1".equals(source.path("schemaVersion").asText())
                && source.path("scopeRef").isTextual() && bound.scopeRef().equals(source.path("scopeRef").textValue())
                && source.path("gid").isTextual() && bound.gid().equals(source.path("gid").textValue()));
        return bound;
    }

    /** The derived scope is verified by the adapter; it is deliberately not the original policy scope. */
    public static BoundQuery queryCall(RunDefinition definition, CallBound bound, FrozenQueryScope derivedShard,
                                       int periodIndex) {
        require(bound != null && derivedShard != null && periodIndex >= 0 && periodIndex < 2
                && bound.equals(callDefinition(definition, bound.call())));
        Period period = bound.periods().get(periodIndex);
        Map<String, Object> request = new TreeMap<>();
        request.put("gid", bound.gid());
        request.put("startDate", period.startDate());
        request.put("endDate", period.endDate());
        request.put("queryKind", "DIMENSION_BREAKDOWN");
        request.put("dimensions", bound.dimensions());
        request.put("filters", bound.filters());
        request.put("scope", derivedShard.asMap());
        // Args, membership and attempts affect the request hash, never the logical child slot.
        String slot = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                callIdentity("dimension-call-statistics-slot/v3", definition, bound.call()),
                derivedShard.shardIndex(), periodIndex)));
        String requestId = "dimension_stat_" + CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                slot, bound.call().executor(), bound.call().arguments(), bound.descriptor(), request)));
        request.put("requestId", requestId);
        ChildSpec child = new ChildSpec("stats-child-" + slot, bound.call().actionId(), ChildMode.ASYNC, requestId,
                new WireRequest("POST", FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH, FrozenCampaignRun.encode(request)));
        return new BoundQuery(child, new StatisticsJobResultReceiver.Target("stats-result-" + slot,
                derivedShard.parentScopeRef(), period.periodsRef()), request, period.periodsRef());
    }

    private static CallBound callDefinition(RunDefinition definition, CallSpec call) {
        require(definition != null && call != null && REF_V3.equals(call.executor()));
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        List<PlanSpec.Step> matching = frozen.plan().steps().stream()
                .filter(step -> call.stepId().equals(step.stepId())).toList();
        require(matching.size() == 1);
        PlanSpec.Step step = matching.get(0);
        require(step.executionMode() == PlanSpec.ExecutionMode.REACT && step.executor() == null
                && step.explorationPolicy() != null && step.explorationPolicy().allowedExecutors().contains(REF_V3));
        var expected = CampaignExplorationCallStore.identity(definition, step.stepId(), call.modelChildId(), call.toolCallId());
        require(expected.callId().equals(call.callId()) && expected.actionId().equals(call.actionId()));
        Map<String, Object> arguments;
        try { arguments = object(CALL_JSON.readValue(call.arguments(), Object.class)); }
        catch (JsonProcessingException invalid) { throw invalid(); }
        require(arguments.keySet().equals(Set.of("inputBindings", "parameters")));
        Map<String, Object> bindings = object(arguments.get("inputBindings"));
        require(bindings.keySet().equals(INPUTS_V3.keySet()) && object(arguments.get("parameters")).isEmpty());
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> artifactIds = new LinkedHashMap<>();
        for (String name : INPUTS_V3.keySet()) {
            Map<String, Object> binding = object(bindings.get(name));
            require(CALL_BINDING_FIELDS.containsAll(binding.keySet()) && binding.get("stepId") == null
                    && binding.get("output") == null);
            if (OUTPUT_INPUTS.contains(name)) {
                require("ARTIFACT".equals(binding.get("source")) && binding.get("input") == null);
                String id = text(binding.get("artifactId"), 96);
                require(id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"));
                artifactIds.put(name, id);
            } else {
                require("INPUT".equals(binding.get("source")) && binding.get("artifactId") == null);
                String input = text(binding.get("input"), 256);
                require(step.inputBindings().values().stream().anyMatch(outer -> outer != null
                        && outer.source() == PlanBinding.Source.INPUT && input.equals(outer.input())
                        && outer.stepId() == null && outer.output() == null && outer.artifactId() == null));
                Port port = frozen.inputs().inputContracts().get(input);
                require(port != null && INPUTS_V3.get(name).type().equals(port.type()));
                Object value = frozen.inputs().inputValues().get(input);
                require(value != null); values.put(name, value);
            }
        }
        String scopeRef = text(step.explorationPolicy().scopeRef(), 256);
        String periodsRef = text(values.get("periods"), 256);
        require(periodsRef.equals(step.explorationPolicy().periodsRef()));
        Map<String, Object> descriptor = object(values.get("definition"));
        require(descriptor.keySet().equals(FIELDS_V2) && SCHEMA_V3.equals(descriptor.get("schemaVersion"))
                && periodsRef.equals(descriptor.get("periodsRef")));
        String gid = text(descriptor.get("gid"), 64);
        require(gid.matches("[A-Za-z0-9_-]{1,64}"));
        List<String> dimensions;
        try { dimensions = DimensionQuery.dimensions(descriptor.get("dimensions")); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
        require(JOINT_DIMENSIONS.equals(dimensions));
        String collection = "dimension-collection-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(
                callIdentity("dimension-call-collection/v3", definition, call)));
        return new CallBound(step, call, scopeRef, periodsRef, gid, artifactIds.get("selectedEntities"),
                artifactIds.get("selectionEvidence"), List.of(period(descriptor.get("baseline")), period(descriptor.get("target"))),
                dimensions, filters(descriptor.get("filters")), pin(descriptor.get("skillPin"), "3"), collection, descriptor);
    }

    private static boolean sameRun(RunDefinition definition, ArtifactMetadata metadata) {
        return metadata != null && definition.caller().equals(metadata.owner()) && definition.runId().equals(metadata.runId())
                && definition.planId().equals(metadata.planId()) && definition.revision() == metadata.revision();
    }

    private static List<Object> callIdentity(String namespace, RunDefinition definition, CallSpec call) {
        return List.of(identity(namespace, definition, call.stepId()), call.callId(), call.actionId());
    }

    /** All matching definitions are checked without consulting step status, artifacts, scopes or a database. */
    public static Map<String, Bound> resolve(RunDefinition definition) {
        Map<String, Bound> result = new LinkedHashMap<>();
        resolveTemplates(definition, REF).forEach((step, template) -> result.put(step, bound(template, template.frozenScopeRef())));
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, Template> resolveTemplates(RunDefinition definition, PlanSpec.ExecutorRef ref) {
        require(REF.equals(ref) || REF_V2.equals(ref));
        boolean dynamic = REF_V2.equals(ref);
        Map<String, Port> ports = dynamic ? INPUTS_V2 : INPUTS;
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        Map<String, Template> bindings = new LinkedHashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (!ref.equals(step.executor())) continue;
            require(step.executionMode() == PlanSpec.ExecutionMode.FIXED && step.explorationPolicy() == null
                    && OUTPUT_CONTRACT.equals(step.outputContractRef()) && step.parameters().isEmpty()
                    && step.inputBindings().keySet().equals(ports.keySet()));
            String upstream = null;
            Map<String, Object> values = new LinkedHashMap<>();
            for (var input : ports.entrySet()) {
                PlanBinding binding = step.inputBindings().get(input.getKey());
                require(binding != null);
                if (OUTPUT_INPUTS.contains(input.getKey())) {
                    require(binding.source() == PlanBinding.Source.STEP_OUTPUT && binding.input() == null
                            && binding.artifactId() == null && input.getKey().equals(binding.output()));
                    String producer = text(binding.stepId(), 256);
                    require(!producer.equals(step.stepId()) && step.dependsOn().contains(producer)
                            && frozen.plan().steps().stream().filter(candidate -> producer.equals(candidate.stepId())).count() == 1);
                    if (upstream == null) upstream = producer;
                    else require(upstream.equals(producer));
                } else {
                    require(binding.source() == PlanBinding.Source.INPUT && binding.input() != null
                            && binding.stepId() == null && binding.output() == null && binding.artifactId() == null);
                    Port contract = frozen.inputs().inputContracts().get(binding.input());
                    require(contract != null && input.getValue().type().equals(contract.type()));
                    Object value = frozen.inputs().inputValues().get(binding.input());
                    require(value != null);
                    values.put(input.getKey(), value);
                }
            }
            String scopeRef = dynamic ? null : text(values.get("scope"), 256);
            String periodsRef = text(values.get("periods"), 256);
            Map<String, Object> descriptor = object(values.get("definition"));
            require(descriptor.keySet().equals(dynamic ? FIELDS_V2 : FIELDS)
                    && (dynamic ? SCHEMA_V2 : SCHEMA).equals(descriptor.get("schemaVersion"))
                    && (dynamic || scopeRef.equals(descriptor.get("scopeRef"))) && periodsRef.equals(descriptor.get("periodsRef")));
            String gid = text(descriptor.get("gid"), 64);
            require(gid.matches("[A-Za-z0-9_-]{1,64}"));
            List<Period> periods = List.of(period(descriptor.get("baseline")), period(descriptor.get("target")));
            List<String> dimensions;
            try { dimensions = DimensionQuery.dimensions(descriptor.get("dimensions")); }
            catch (IllegalArgumentException invalid) { throw invalid(); }
            require(JOINT_DIMENSIONS.equals(dimensions));
            List<Map<String, Object>> filters = filters(descriptor.get("filters"));
            RunPinnedSkills.SkillPin pin = pin(descriptor.get("skillPin"), ref.version());
            String collection = "dimension-collection-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(
                    identity("dimension-change-collection/v1", definition, step.stepId())));
            Template bound = new Template(step, upstream, scopeRef, periodsRef, gid, periods, dimensions,
                    filters, pin, collection, descriptor);
            require(bindings.putIfAbsent(step.stepId(), bound) == null);
        }
        return Collections.unmodifiableMap(bindings);
    }

    /** Caller supplies the freshly store-verified pair and authorized original scope, never model fields. */
    public static Bound bind(RunDefinition definition, Template template, SelectionPair pair, Artifact originalScope) {
        require(template != null && template.step() != null
                && template.equals(resolveTemplates(definition, template.step().executor()).get(template.step().stepId())));
        require(pair != null && originalScope != null && pair.scopeArtifact().equals(originalScope.metadata())
                && definition.caller().equals(pair.selectedEntities().owner())
                && definition.caller().equals(pair.selectionEvidence().owner())
                && definition.caller().equals(pair.scopeArtifact().owner())
                && definition.runId().equals(pair.selectedEntities().runId())
                && definition.runId().equals(pair.selectionEvidence().runId())
                && definition.planId().equals(pair.selectedEntities().planId())
                && definition.planId().equals(pair.selectionEvidence().planId())
                && definition.revision() == pair.selectedEntities().revision()
                && definition.revision() == pair.selectionEvidence().revision()
                && template.upstreamStepId().equals(pair.producerStepId())
                && template.periodsRef().equals(pair.definition().periodsRef()) && template.periods().equals(pair.periods()));
        String scopeRef = text(pair.definition().scopeRef(), 256);
        require((template.frozenScopeRef() == null || template.frozenScopeRef().equals(scopeRef))
                && scopeRef.equals(pair.selectedEntities().ref().scopeRef())
                && scopeRef.equals(pair.selectionEvidence().ref().scopeRef())
                && scopeRef.equals(pair.scopeArtifact().ref().scopeRef())
                && template.periodsRef().equals(pair.selectedEntities().ref().periodsRef())
                && template.periodsRef().equals(pair.selectionEvidence().ref().periodsRef())
                && pair.definition().scopeArtifactId().equals(pair.scopeArtifact().ref().artifactId())
                && "ScopeArtifact".equals(pair.scopeArtifact().ref().type())
                && "campaign-scope/v1".equals(pair.scopeArtifact().ref().schemaVersion())
                && CampaignRunStore.sha256(originalScope.payloadJson()).equals(pair.scopeArtifact().ref().payloadHash()));
        JsonNode source;
        try { source = JSON.readTree(originalScope.payloadJson()); }
        catch (java.io.IOException invalid) { throw invalid(); }
        require(source != null && source.isObject() && "campaign-scope/v1".equals(source.path("schemaVersion").asText())
                && source.path("scopeRef").isTextual() && scopeRef.equals(source.path("scopeRef").textValue())
                && source.path("gid").isTextual() && template.gid().equals(source.path("gid").textValue()));
        return bound(template, scopeRef);
    }

    private static Bound bound(Template template, String scopeRef) {
        return new Bound(template.step(), template.upstreamStepId(), scopeRef, template.periodsRef(), template.gid(),
                template.periods(), template.dimensions(), template.filters(), template.skillPin(), template.collectionId(), template.descriptor());
    }

    /**
     * The Skill must verify the derived shard against the actual selected artifacts first.
     * Its scope reference intentionally differs from the original group scope bound as INPUT.
     */
    public static BoundQuery query(RunDefinition definition, Bound bound, FrozenQueryScope derivedShard, int periodIndex) {
        require(bound != null && bound.step() != null && derivedShard != null && periodIndex >= 0 && periodIndex < 2);
        require(bound.equals(resolve(definition).get(bound.step().stepId())));
        return buildQuery(definition, bound, derivedShard, periodIndex);
    }

    /** v2 cannot bypass its frozen template by presenting an independently constructed Bound. */
    public static BoundQuery query(RunDefinition definition, Template template, SelectionPair pair, Artifact originalScope,
                                   FrozenQueryScope derivedShard, int periodIndex) {
        require(template != null && template.step() != null && REF_V2.equals(template.step().executor()) && derivedShard != null
                && periodIndex >= 0 && periodIndex < 2);
        return buildQuery(definition, bind(definition, template, pair, originalScope), derivedShard, periodIndex);
    }

    private static BoundQuery buildQuery(RunDefinition definition, Bound bound, FrozenQueryScope derivedShard, int periodIndex) {
        Period period = bound.periods().get(periodIndex);
        Map<String, Object> request = new TreeMap<>();
        request.put("gid", bound.gid());
        request.put("startDate", period.startDate());
        request.put("endDate", period.endDate());
        request.put("queryKind", "DIMENSION_BREAKDOWN");
        request.put("dimensions", bound.dimensions());
        request.put("filters", bound.filters());
        request.put("scope", derivedShard.asMap());
        // Query contents, selected membership and attempts never change the logical child slot.
        // A changed wire collides with the original child and must fail the ledger's immutable check.
        String slot = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                identity("dimension-statistics-slot/v1", definition, bound.step().stepId()),
                derivedShard.shardIndex(), periodIndex)));
        String requestId = "dimension_stat_" + CampaignRunStore.sha256(FrozenCampaignRun.encode(
                List.of(slot, bound.step().executor(), bound.descriptor(), request)));
        request.put("requestId", requestId);
        ChildSpec child = new ChildSpec("stats-child-" + slot, "stats-action-" + slot, ChildMode.ASYNC,
                requestId, new WireRequest("POST", FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH,
                FrozenCampaignRun.encode(request)));
        return new BoundQuery(child, new StatisticsJobResultReceiver.Target("stats-result-" + slot,
                derivedShard.parentScopeRef(), period.periodsRef()), request, period.periodsRef());
    }

    private static List<Object> identity(String namespace, RunDefinition definition, String stepId) {
        return List.of(namespace, definition.caller().tenantId(), definition.caller().subject(),
                definition.caller().authVersion(), definition.sessionId(), definition.runId(),
                definition.planId(), definition.revision(), stepId);
    }

    private static Period period(Object value) {
        Map<String, Object> period = object(value);
        require(period.keySet().equals(PERIOD_FIELDS));
        return new Period(text(period.get("periodsRef"), 256), text(period.get("startDate"), 10),
                text(period.get("endDate"), 10), text(period.get("timeZone"), 64));
    }

    private static RunPinnedSkills.SkillPin pin(Object value, String version) {
        Map<String, Object> pin = object(value);
        require(pin.keySet().equals(PIN_FIELDS) && "dimension-change".equals(pin.get("name"))
                && version.equals(pin.get("version")) && ("dimension-change/" + version).equals(pin.get("relativeDirectory")));
        return new RunPinnedSkills.SkillPin(text(pin.get("name"), 64), text(pin.get("version"), 16),
                text(pin.get("relativeDirectory"), 128), text(pin.get("sha256"), 64));
    }

    private static List<Map<String, Object>> filters(Object value) {
        require(value instanceof List<?>);
        for (Object filter : (List<?>) value) require(FILTER_FIELDS.containsAll(object(filter).keySet()));
        try { return DimensionQuery.filters(value); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
    }

    private static String text(Object value, int maximum) {
        require(value instanceof String);
        String text = (String) value;
        require(!text.isBlank() && text.length() <= maximum && text.equals(text.trim())
                && text.chars().noneMatch(Character::isISOControl));
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?> && ((Map<?, ?>) value).keySet().stream().allMatch(String.class::isInstance));
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> immutableObject(Map<String, Object> source) {
        return object(freeze(source, 0));
    }

    /** Preserve original descriptor values, including an explicit null IS_UNKNOWN values field. */
    private static Object freeze(Object value, int depth) {
        require(depth <= 8);
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return value;
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(freeze(item, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        Map<String, Object> source = object(value), copy = new TreeMap<>();
        source.forEach((key, item) -> copy.put(key, freeze(item, depth + 1)));
        return Collections.unmodifiableMap(copy);
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("FROZEN_DIMENSION_CHANGE_INVALID"); }
}
