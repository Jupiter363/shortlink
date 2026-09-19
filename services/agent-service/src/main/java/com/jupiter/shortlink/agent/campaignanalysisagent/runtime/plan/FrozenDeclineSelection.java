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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Frozen method inputs and bounded child queries; does not enumerate or authorize scope members. */
public final class FrozenDeclineSelection {
    public static final PlanSpec.ExecutorRef REF = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL,
            "decline_selection", "1");
    public static final String OUTPUT_CONTRACT = "campaign.decline-selection/v1";
    public static final String SCHEMA = "decline-selection-definition/v1";
    public static final Map<String, Port> INPUTS = Map.of(
            "scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
            "scopeArtifact", new Port(new TypeRef("ScopeArtifact", 1, Cardinality.ONE), true),
            "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
            "definition", new Port(new TypeRef("DeclineSelectionDefinition", 1, Cardinality.ONE), true));
    private static final Set<String> FIELDS = Set.of("schemaVersion", "scopeRef", "periodsRef", "gid",
            "baseline", "target", "skillPin");
    private static final Set<String> PERIOD_FIELDS = Set.of("periodsRef", "startDate", "endDate", "timeZone");
    private static final Set<String> PIN_FIELDS = Set.of("name", "version", "relativeDirectory", "sha256");

    private FrozenDeclineSelection() {}

    public record Bound(PlanSpec.Step step, String scopeRef, String scopeArtifactId, String periodsRef,
                        String gid, List<CampaignParentCoverage.Period> periods,
                        CampaignLinkComparability.Metric metric, RunPinnedSkills.SkillPin skillPin,
                        String collectionId, Map<String, Object> descriptor) {
        public Bound {
            periods = List.copyOf(periods);
            descriptor = copyDescriptor(descriptor);
        }
    }

    public record BoundQuery(ChildSpec child, StatisticsJobResultReceiver.Target target,
                             Map<String, Object> request, String periodsRef) {
        public BoundQuery { request = Collections.unmodifiableMap(new TreeMap<>(request)); }
    }

    /** Validate every matching step before any child can perform I/O. Artifact authority stays in the runtime. */
    public static Map<String, Bound> resolve(RunDefinition definition) {
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        Map<String, Bound> bindings = new LinkedHashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (!REF.equals(step.executor())) continue;
            require(step.executionMode() == PlanSpec.ExecutionMode.FIXED && step.explorationPolicy() == null
                    && OUTPUT_CONTRACT.equals(step.outputContractRef())
                    && step.inputBindings().keySet().equals(INPUTS.keySet())
                    && step.parameters().keySet().equals(Set.of("metric")));
            CampaignLinkComparability.Metric metric = metric(step.parameters().get("metric"));
            Map<String, Object> values = new LinkedHashMap<>();
            String artifactId = null;
            for (var input : INPUTS.entrySet()) {
                PlanBinding binding = step.inputBindings().get(input.getKey());
                require(binding != null);
                if ("scopeArtifact".equals(input.getKey())) {
                    require(binding.source() == PlanBinding.Source.ARTIFACT && binding.input() == null
                            && binding.stepId() == null && binding.output() == null);
                    artifactId = text(binding.artifactId(), 256);
                } else {
                    require(binding.source() == PlanBinding.Source.INPUT && binding.input() != null
                            && binding.stepId() == null && binding.output() == null && binding.artifactId() == null);
                    Port port = frozen.inputs().inputContracts().get(binding.input());
                    require(port != null && input.getValue().type().equals(port.type()));
                    Object value = frozen.inputs().inputValues().get(binding.input());
                    require(value != null);
                    values.put(input.getKey(), value);
                }
            }
            String scopeRef = text(values.get("scope"), 256);
            String periodsRef = text(values.get("periods"), 256);
            Map<String, Object> descriptor = object(values.get("definition"));
            require(descriptor.keySet().equals(FIELDS) && SCHEMA.equals(descriptor.get("schemaVersion"))
                    && scopeRef.equals(descriptor.get("scopeRef")) && periodsRef.equals(descriptor.get("periodsRef")));
            String gid = text(descriptor.get("gid"), 128);
            List<CampaignParentCoverage.Period> periods = List.of(period(descriptor.get("baseline")),
                    period(descriptor.get("target")));
            RunPinnedSkills.SkillPin pin = pin(descriptor.get("skillPin"));
            String collectionId = "decline-collection-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(
                    identity("decline-collection/v1", definition, step.stepId())));
            Bound bound = new Bound(step, scopeRef, artifactId, periodsRef, gid, periods, metric, pin,
                    collectionId, descriptor);
            require(bindings.putIfAbsent(step.stepId(), bound) == null);
        }
        return Collections.unmodifiableMap(bindings);
    }

    /** One real child under the parent SKILL; no synthetic TOOL step and no eager expansion of other shards. */
    public static BoundQuery query(RunDefinition definition, Bound bound, FrozenQueryScope shard, int periodIndex) {
        require(bound != null && bound.step() != null && shard != null && periodIndex >= 0 && periodIndex < 2);
        require(bound.equals(resolve(definition).get(bound.step().stepId()))
                && bound.scopeRef().equals(shard.parentScopeRef()));
        CampaignParentCoverage.Period period = bound.periods().get(periodIndex);
        Map<String, Object> request = new TreeMap<>();
        request.put("gid", bound.gid());
        request.put("startDate", period.startDate());
        request.put("endDate", period.endDate());
        request.put("queryKind", "LINK_METRICS");
        request.put("scope", shard.asMap());
        // Identity excludes query contents, method digests and retry attempts. A changed request
        // collides with its existing immutable child instead of quietly creating another job.
        String slot = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                identity("decline-statistics-slot/v1", definition, bound.step().stepId()),
                shard.shardIndex(), periodIndex)));
        String requestId = "decline_stat_" + CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                slot, REF, bound.descriptor(), bound.metric(), request)));
        request.put("requestId", requestId);
        ChildSpec child = new ChildSpec("stats-child-" + slot, "stats-action-" + slot, ChildMode.ASYNC,
                requestId, new WireRequest("POST", FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH,
                FrozenCampaignRun.encode(request)));
        return new BoundQuery(child, new StatisticsJobResultReceiver.Target("stats-result-" + slot,
                bound.scopeRef(), period.periodsRef()), request, period.periodsRef());
    }

    private static List<Object> identity(String namespace, RunDefinition definition, String stepId) {
        return List.of(namespace, definition.caller().tenantId(), definition.caller().subject(),
                definition.caller().authVersion(), definition.sessionId(), definition.runId(),
                definition.planId(), definition.revision(), stepId);
    }

    private static CampaignParentCoverage.Period period(Object value) {
        Map<String, Object> period = object(value);
        require(period.keySet().equals(PERIOD_FIELDS));
        return new CampaignParentCoverage.Period(text(period.get("periodsRef"), 256),
                text(period.get("startDate"), 10), text(period.get("endDate"), 10), text(period.get("timeZone"), 64));
    }

    private static RunPinnedSkills.SkillPin pin(Object value) {
        Map<String, Object> pin = object(value);
        require(pin.keySet().equals(PIN_FIELDS) && "decline-selection".equals(pin.get("name"))
                && "1".equals(pin.get("version")) && "decline-selection/1".equals(pin.get("relativeDirectory")));
        return new RunPinnedSkills.SkillPin(text(pin.get("name"), 64), text(pin.get("version"), 16),
                text(pin.get("relativeDirectory"), 128), text(pin.get("sha256"), 64));
    }

    private static CampaignLinkComparability.Metric metric(Object value) {
        String name = text(value, 3);
        try { return CampaignLinkComparability.Metric.valueOf(name); }
        catch (IllegalArgumentException unsupported) { throw invalid(); }
    }

    /** This descriptor has only strings and three closed, string-valued objects. */
    private static Map<String, Object> copyDescriptor(Map<String, Object> source) {
        require(source != null && source.keySet().equals(FIELDS));
        Map<String, Object> copy = new TreeMap<>();
        for (var entry : source.entrySet()) {
            if (Set.of("baseline", "target", "skillPin").contains(entry.getKey())) {
                Map<String, Object> nested = object(entry.getValue());
                require(nested.keySet().equals("skillPin".equals(entry.getKey()) ? PIN_FIELDS : PERIOD_FIELDS)
                        && nested.values().stream().allMatch(String.class::isInstance));
                copy.put(entry.getKey(), Collections.unmodifiableMap(new TreeMap<>(nested)));
            } else {
                require(entry.getValue() instanceof String);
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
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

    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("FROZEN_DECLINE_SELECTION_INVALID"); }
}
