package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
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

/** Static frozen bindings only. Dynamic selected artifacts are resolved by the real upstream step at runtime. */
public final class FrozenDimensionChange {
    public static final PlanSpec.ExecutorRef REF = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL,
            "dimension_change", "1");
    public static final String OUTPUT_CONTRACT = "campaign.dimension-change/v1";
    public static final String SCHEMA = "dimension-change-definition/v1";
    public static final Map<String, Port> INPUTS = Map.of(
            "scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
            "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
            "definition", new Port(new TypeRef("DimensionChangeDefinition", 1, Cardinality.ONE), true),
            "selectedEntities", new Port(new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE), true),
            "selectionEvidence", new Port(new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE), true));
    private static final Set<String> OUTPUT_INPUTS = Set.of("selectedEntities", "selectionEvidence");
    private static final Set<String> FIELDS = Set.of("schemaVersion", "scopeRef", "periodsRef", "gid",
            "baseline", "target", "dimensions", "filters", "skillPin");
    private static final Set<String> PERIOD_FIELDS = Set.of("periodsRef", "startDate", "endDate", "timeZone");
    private static final Set<String> PIN_FIELDS = Set.of("name", "version", "relativeDirectory", "sha256");
    private static final Set<String> FILTER_FIELDS = Set.of("dimension", "operator", "values");
    private static final List<String> JOINT_DIMENSIONS = List.of("province", "device");

    private FrozenDimensionChange() {}

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

    /** All matching definitions are checked without consulting step status, artifacts, scopes or a database. */
    public static Map<String, Bound> resolve(RunDefinition definition) {
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        Map<String, Bound> bindings = new LinkedHashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (!REF.equals(step.executor())) continue;
            require(step.executionMode() == PlanSpec.ExecutionMode.FIXED && step.explorationPolicy() == null
                    && OUTPUT_CONTRACT.equals(step.outputContractRef()) && step.parameters().isEmpty()
                    && step.inputBindings().keySet().equals(INPUTS.keySet()));
            String upstream = null;
            Map<String, Object> values = new LinkedHashMap<>();
            for (var input : INPUTS.entrySet()) {
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
            String scopeRef = text(values.get("scope"), 256), periodsRef = text(values.get("periods"), 256);
            Map<String, Object> descriptor = object(values.get("definition"));
            require(descriptor.keySet().equals(FIELDS) && SCHEMA.equals(descriptor.get("schemaVersion"))
                    && scopeRef.equals(descriptor.get("scopeRef")) && periodsRef.equals(descriptor.get("periodsRef")));
            String gid = text(descriptor.get("gid"), 64);
            require(gid.matches("[A-Za-z0-9_-]{1,64}"));
            List<Period> periods = List.of(period(descriptor.get("baseline")), period(descriptor.get("target")));
            List<String> dimensions;
            try { dimensions = DimensionQuery.dimensions(descriptor.get("dimensions")); }
            catch (IllegalArgumentException invalid) { throw invalid(); }
            require(JOINT_DIMENSIONS.equals(dimensions));
            List<Map<String, Object>> filters = filters(descriptor.get("filters"));
            RunPinnedSkills.SkillPin pin = pin(descriptor.get("skillPin"));
            String collection = "dimension-collection-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(
                    identity("dimension-change-collection/v1", definition, step.stepId())));
            Bound bound = new Bound(step, upstream, scopeRef, periodsRef, gid, periods, dimensions,
                    filters, pin, collection, descriptor);
            require(bindings.putIfAbsent(step.stepId(), bound) == null);
        }
        return Collections.unmodifiableMap(bindings);
    }

    /**
     * The Skill must verify the derived shard against the actual selected artifacts first.
     * Its scope reference intentionally differs from the original group scope bound as INPUT.
     */
    public static BoundQuery query(RunDefinition definition, Bound bound, FrozenQueryScope derivedShard, int periodIndex) {
        require(bound != null && bound.step() != null && derivedShard != null && periodIndex >= 0 && periodIndex < 2);
        require(bound.equals(resolve(definition).get(bound.step().stepId())));
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
                List.of(slot, REF, bound.descriptor(), request)));
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

    private static RunPinnedSkills.SkillPin pin(Object value) {
        Map<String, Object> pin = object(value);
        require(pin.keySet().equals(PIN_FIELDS) && "dimension-change".equals(pin.get("name"))
                && "1".equals(pin.get("version")) && "dimension-change/1".equals(pin.get("relativeDirectory")));
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
