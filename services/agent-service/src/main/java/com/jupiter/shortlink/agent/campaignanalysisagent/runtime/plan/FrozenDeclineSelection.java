package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
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
    public static final PlanSpec.ExecutorRef REF_V2 = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL,
            "decline_selection", "2");
    public static final String OUTPUT_CONTRACT = "campaign.decline-selection/v1";
    public static final String SCHEMA = "decline-selection-definition/v1";
    public static final String SCHEMA_V2 = "decline-selection-definition/v2";
    public static final Map<String, Port> INPUTS = Map.of(
            "scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
            "scopeArtifact", new Port(new TypeRef("ScopeArtifact", 1, Cardinality.ONE), true),
            "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
            "definition", new Port(new TypeRef("DeclineSelectionDefinition", 1, Cardinality.ONE), true));
    public static final Map<String, Port> INPUTS_V2 = Map.of(
            "scopeArtifact", new Port(new TypeRef("ScopeArtifact", 1, Cardinality.ONE), true),
            "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
            "definition", new Port(new TypeRef("DeclineSelectionDefinition", 2, Cardinality.ONE), true));
    private static final Set<String> FIELDS = Set.of("schemaVersion", "scopeRef", "periodsRef", "gid",
            "baseline", "target", "skillPin");
    private static final Set<String> FIELDS_V2 = Set.of("schemaVersion", "periodsRef", "gid", "baseline", "target", "skillPin");
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

    /** Static v2 requirements only: neither scope identity nor an upstream artifact is guessed. */
    public record Template(PlanSpec.Step step, String upstreamStepId, String periodsRef, String gid,
                           List<CampaignParentCoverage.Period> periods, CampaignLinkComparability.Metric metric,
                           RunPinnedSkills.SkillPin skillPin, String collectionId, Map<String, Object> descriptor) {
        public Template {
            periods = List.copyOf(periods);
            descriptor = copyDescriptor(descriptor);
        }
    }

    public static Map<String, Template> templates(RunDefinition definition) {
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        Map<String, Template> templates = new LinkedHashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (!REF_V2.equals(step.executor())) continue;
            require(step.executionMode() == PlanSpec.ExecutionMode.FIXED && step.explorationPolicy() == null
                    && OUTPUT_CONTRACT.equals(step.outputContractRef())
                    && step.inputBindings().keySet().equals(INPUTS_V2.keySet())
                    && step.parameters().keySet().equals(Set.of("metric")));
            PlanBinding source = step.inputBindings().get("scopeArtifact");
            require(source != null && source.source() == PlanBinding.Source.STEP_OUTPUT
                    && source.input() == null && source.artifactId() == null && "scopeArtifact".equals(source.output()));
            String upstream = text(source.stepId(), 256);
            require(!upstream.equals(step.stepId()) && step.dependsOn().contains(upstream)
                    && frozen.plan().steps().stream().filter(candidate -> upstream.equals(candidate.stepId())).count() == 1);
            Map<String, Object> values = new LinkedHashMap<>();
            for (String name : List.of("periods", "definition")) {
                PlanBinding binding = step.inputBindings().get(name);
                require(binding != null && binding.source() == PlanBinding.Source.INPUT && binding.input() != null
                        && binding.stepId() == null && binding.output() == null && binding.artifactId() == null);
                Port port = frozen.inputs().inputContracts().get(binding.input());
                require(port != null && INPUTS_V2.get(name).type().equals(port.type()));
                Object value = frozen.inputs().inputValues().get(binding.input());
                require(value != null); values.put(name, value);
            }
            String periodsRef = text(values.get("periods"), 256);
            Map<String, Object> descriptor = object(values.get("definition"));
            require(descriptor.keySet().equals(FIELDS_V2) && SCHEMA_V2.equals(descriptor.get("schemaVersion"))
                    && periodsRef.equals(descriptor.get("periodsRef")));
            String gid = text(descriptor.get("gid"), 64);
            require(gid.matches("[A-Za-z0-9_-]{1,64}"));
            String collection = "decline-collection-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(
                    identity("decline-collection/v1", definition, step.stepId())));
            Template template = new Template(step, upstream, periodsRef, gid,
                    List.of(period(descriptor.get("baseline")), period(descriptor.get("target"))),
                    metric(step.parameters().get("metric")), pin(descriptor.get("skillPin"), "2"), collection, descriptor);
            require(templates.putIfAbsent(step.stepId(), template) == null);
        }
        return Collections.unmodifiableMap(templates);
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
        return buildQuery(definition, bound, shard, periodIndex);
    }

    /** Only the runtime may supply an inspected published scope; this is not a model binding surface. */
    static Bound bindResolved(RunDefinition definition, Template template, ArtifactMetadata source,
                              FrozenCampaignScope.Summary summary) {
        require(template != null && template.equals(templates(definition).get(template.step().stepId()))
                && source != null && source.ref() != null && summary != null);
        require(definition.caller().equals(source.owner()) && definition.runId().equals(source.runId())
                && definition.planId().equals(source.planId()) && definition.revision() == source.revision()
                && "ScopeArtifact".equals(source.ref().type()) && "campaign-scope/v1".equals(source.ref().schemaVersion())
                && "scope-enumeration".equals(source.ref().periodsRef())
                && summary.owner() != null && !summary.owner().system()
                && definition.caller().tenantId().equals(summary.owner().tenantId())
                && definition.caller().subject().equals(summary.owner().username())
                && definition.caller().authVersion() == summary.owner().authVersion()
                && template.gid().equals(summary.gid()) && summary.scopeRef().equals(source.ref().scopeRef()));
        return new Bound(template.step(), summary.scopeRef(), source.ref().artifactId(), template.periodsRef(),
                template.gid(), template.periods(), template.metric(), template.skillPin(), template.collectionId(), template.descriptor());
    }

    static BoundQuery queryResolved(RunDefinition definition, Template template, Bound bound,
                                   ArtifactMetadata source, FrozenCampaignScope.Summary summary,
                                   FrozenQueryScope shard, int periodIndex) {
        require(bound != null && shard != null && periodIndex >= 0 && periodIndex < 2
                && bound.equals(bindResolved(definition, template, source, summary))
                && bound.scopeRef().equals(shard.parentScopeRef()) && summary.memberHash().equals(shard.parentMemberHash())
                && summary.memberCount() == shard.parentMemberCount() && summary.shardCount() == shard.shardCount()
                && summary.enumerationVersion().equals(shard.enumerationVersion()));
        return buildQuery(definition, bound, shard, periodIndex);
    }

    private static BoundQuery buildQuery(RunDefinition definition, Bound bound, FrozenQueryScope shard, int periodIndex) {
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
                slot, bound.step().executor(), bound.descriptor(), bound.metric(), request)));
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
        return pin(value, "1");
    }

    private static RunPinnedSkills.SkillPin pin(Object value, String version) {
        Map<String, Object> pin = object(value);
        require(pin.keySet().equals(PIN_FIELDS) && "decline-selection".equals(pin.get("name"))
                && version.equals(pin.get("version")) && ("decline-selection/" + version).equals(pin.get("relativeDirectory")));
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
        require(source != null && (source.keySet().equals(FIELDS)
                || SCHEMA_V2.equals(source.get("schemaVersion")) && source.keySet().equals(FIELDS_V2)));
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
