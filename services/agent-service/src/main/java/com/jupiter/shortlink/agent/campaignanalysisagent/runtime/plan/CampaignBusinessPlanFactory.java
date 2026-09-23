package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Trusted input assembly for arbitrary goal combinations using only registered business capabilities. */
public final class CampaignBusinessPlanFactory {
    public static final String CATALOG_VERSION = "campaign-business/v1";
    public static final String MANIFEST = "business-request";
    public static final String SCHEMA = "campaign-business-inputs/v1";
    private static final Port MANIFEST_PORT = new Port(new TypeRef("CampaignBusinessRequest", 1, Cardinality.ONE), true);
    // PlanningProposal omits null values when persisting the request. Freeze the manifest in
    // that same form so a normal transport round-trip cannot change its identity.
    private static final ObjectMapper JSON = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final CampaignDependencyAnalysisPlanFactory dependencies;
    private final Clock clock;
    /** One immutable parse per factory; never cache current authority or mutable run/artifact state. */
    private volatile InspectedInputs lastInspected;
    private volatile ValidatedDefinition lastValidated;

    public record QueryInput(String prefix, String scopeRef, String periodsRef, Map<String,Object> descriptor,
                             Map<String,Object> request) {}
    public record DependencyInput(String prefix, FrozenInputSet inputs, CampaignRunStore.RunDefinition definition,
                                  CampaignDependencyAnalysisPlanFactory.Request request) {}
    public record Inspected(CampaignInterpretedRequest request, Instant expiresAt,
                            List<QueryInput> queries, List<DependencyInput> dependencies) {}
    private record Built(FrozenInputSet inputs, List<PlanSpec.Goal> goals,
                         List<PlanningAssessment.Requirement> requirements, Inspected inspected) {}
    private record InspectedInputs(Caller caller, FrozenInputSet inputs, Inspected inspected) {}
    private record ValidatedDefinition(CampaignRunStore.RunDefinition definition, FrozenInputSet inputs, Inspected inspected) {}

    public CampaignBusinessPlanFactory(CampaignDependencyAnalysisPlanFactory dependencies, Clock clock) {
        this.dependencies = Objects.requireNonNull(dependencies);
        this.clock = Objects.requireNonNull(clock);
    }

    public PlanningProposal.Request prepare(Caller caller, String sessionId, String requestKey,
            CampaignInterpretedRequest request, Instant expiresAt) {
        Built built = build(caller, sessionId, requestKey, request, expiresAt);
        return new PlanningProposal.Request(request.question(), built.goals(), built.requirements(),
                built.inputs(), Map.of(), menu());
    }

    /** Rebuild all values from the immutable request; an extra descriptor or changed port is rejected. */
    public Inspected inspect(Caller caller, FrozenInputSet inputs) {
        try {
            var previous = lastInspected;
            if (previous != null && caller.equals(previous.caller()) && inputs.equals(previous.inputs())) {
                require(previous.inspected().expiresAt().isAfter(clock.instant()));
                return previous.inspected();
            }
            Map<String,Object> manifest = object(inputs.inputValues().get(MANIFEST));
            require(manifest.keySet().equals(Set.of("schemaVersion", "sessionId", "requestKey", "request", "expiresAt"))
                    && SCHEMA.equals(manifest.get("schemaVersion")));
            var request = JSON.convertValue(manifest.get("request"), CampaignInterpretedRequest.class);
            Built expected = build(caller, (String) manifest.get("sessionId"), (String) manifest.get("requestKey"),
                    request, Instant.parse((String) manifest.get("expiresAt")));
            require(expected.inputs().equals(inputs));
            lastInspected = new InspectedInputs(caller, inputs, expected.inspected());
            return expected.inspected();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("BUSINESS_FROZEN_INPUTS_INVALID", invalid);
        }
    }

    /** A structurally valid model plan must also preserve the business goal-to-input association. */
    public Inspected validateDefinition(CampaignRunStore.RunDefinition definition) {
        var previous = lastValidated;
        if (previous != null && definition.equals(previous.definition())) {
            // Even an identical frozen definition must keep its original execution deadline.
            inspect(definition.caller(), previous.inputs());
            return previous.inspected();
        }
        var frozen = FrozenCampaignRun.read(definition);
        Inspected parsed = inspect(definition.caller(), frozen.inputs());
        var collections = FrozenScopeCollection.resolve(definition);
        var selections = FrozenDeclineSelection.templates(definition);
        var dimensions = FrozenDimensionChange.resolveTemplates(definition, FrozenDimensionChange.REF_V2);
        var statistics = FrozenStatisticsJobQuery.resolve(definition, StatisticsJobFixedExecutor.REF);
        Map<String,PlanSpec.Step> steps = new HashMap<>();
        frozen.plan().steps().forEach(step -> steps.put(step.stepId(), step));
        for (var step : frozen.plan().steps()) {
            require(!step.goalIds().isEmpty());
            for (String goalId : step.goalIds()) {
                require(goalId.matches("goal-[1-9][0-9]*"));
                int index = Integer.parseInt(goalId.substring(5)) - 1;
                require(index < parsed.request().goals().size());
                var sourceGoal = parsed.request().goals().get(index);
                if (statistics.containsKey(step.stepId()) || step.executionMode() == PlanSpec.ExecutionMode.REACT) {
                    var scope = input(frozen.inputs(), step, "scope");
                    var periods = input(frozen.inputs(), step, "periods");
                    var query = input(frozen.inputs(), step, "query");
                    require(parsed.queries().stream().anyMatch(value -> value.prefix().startsWith(goalId + "-query-")
                            && value.scopeRef().equals(scope) && value.periodsRef().equals(periods) && value.descriptor().equals(query)));
                    if (step.executionMode() == PlanSpec.ExecutionMode.REACT)
                        require(step.explorationPolicy() != null
                                && CampaignExplorationRuntimeFactory.STATISTICS_POLICY.equals(step.explorationPolicy().policyRef())
                                && scope.equals(step.explorationPolicy().scopeRef()) && periods.equals(step.explorationPolicy().periodsRef()));
                } else {
                    var branch = parsed.dependencies().stream().filter(value -> value.prefix().equals(goalId + "-"))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("BUSINESS_GOAL_BINDING_CHANGED"));
                    if (collections.containsKey(step.stepId())) {
                        require(collections.get(step.stepId()).descriptor().equals(branch.inputs().inputValues().get("collectionDefinition")));
                    } else if (selections.containsKey(step.stepId())) {
                        var selection = selections.get(step.stepId());
                        require(selection.descriptor().equals(branch.inputs().inputValues().get("selectionDefinition"))
                                && selection.metric().name().equals(branch.request().metric())
                                && collections.containsKey(selection.upstreamStepId())
                                && collections.get(selection.upstreamStepId()).descriptor().equals(branch.inputs().inputValues().get("collectionDefinition")));
                    } else if (dimensions.containsKey(step.stepId())) {
                        var dimension = dimensions.get(step.stepId());
                        var selection = selections.get(dimension.upstreamStepId());
                        require(dimension.descriptor().equals(branch.inputs().inputValues().get("dimensionDefinition"))
                                && selection != null && selection.descriptor().equals(branch.inputs().inputValues().get("selectionDefinition"))
                                && selection.metric().name().equals(branch.request().metric()));
                    } else require(false);
                }
                var ancestors = ancestors(step, steps, new HashSet<>());
                for (int prerequisite : sourceGoal.dependsOn()) {
                    String requiredGoal = "goal-" + (prerequisite + 1);
                    require(ancestors.stream().map(steps::get).filter(Objects::nonNull)
                            .anyMatch(value -> value.goalIds().contains(requiredGoal)));
                }
            }
        }
        lastValidated = new ValidatedDefinition(definition, frozen.inputs(), parsed);
        return parsed;
    }

    private static Object input(FrozenInputSet inputs, PlanSpec.Step step, String name) {
        var binding = step.inputBindings().get(name);
        require(binding != null && binding.source() == PlanBinding.Source.INPUT);
        return inputs.inputValues().get(binding.input());
    }

    private static Set<String> ancestors(PlanSpec.Step step, Map<String,PlanSpec.Step> steps, Set<String> result) {
        for (String previous : step.dependsOn()) if (result.add(previous)) {
            require(steps.containsKey(previous)); ancestors(steps.get(previous), steps, result);
        }
        return result;
    }

    private Built build(Caller caller, String sessionId, String key, CampaignInterpretedRequest request, Instant expiry) {
        Objects.requireNonNull(expiry);
        require(expiry.isAfter(clock.instant()) && expiry.getNano() % 1_000_000 == 0);
        request = CampaignInterpretedRequest.parse(FrozenCampaignRun.encode(request), request.question());
        var identity = JdbcCampaignRunIntakeStore.identity(caller, sessionId, key);
        Map<String,Port> ports = new LinkedHashMap<>();
        Map<String,Object> values = new LinkedHashMap<>();
        List<PlanSpec.Goal> goals = new ArrayList<>();
        List<PlanningAssessment.Requirement> requirements = new ArrayList<>();
        List<QueryInput> queries = new ArrayList<>();
        List<DependencyInput> dependencyInputs = new ArrayList<>();
        ports.put(MANIFEST, MANIFEST_PORT);
        values.put(MANIFEST, Map.of("schemaVersion", SCHEMA, "sessionId", sessionId, "requestKey", key,
                "request", JSON.convertValue(request, Map.class), "expiresAt", expiry.toString()));
        for (int i = 0; i < request.goals().size(); i++) {
            var goal = request.goals().get(i);
            String id = "goal-" + (i + 1);
            String prerequisites = goal.dependsOn().stream().map(index -> "goal-" + (index + 1)).toList().toString();
            goals.add(new PlanSpec.Goal(id, goal.question(), true,
                    "Deliver this complete objective with its exact frozen data and limitations. Method=" + goal.method()
                            + "; prerequisite goals=" + prerequisites + "; inputs prefixed " + id + "-."));
            if ("DECLINE_DIMENSIONS".equals(goal.method())) {
                require(goal.queries().size() == 2);
                var baseline = goal.queries().get(0); var target = goal.queries().get(1);
                require(baseline.gid().equals(target.gid()) && baseline.fullShortUrl() == null && target.fullShortUrl() == null
                        && "LINK_METRICS".equals(baseline.queryKind()) && "LINK_METRICS".equals(target.queryKind())
                        && baseline.dimensions().isEmpty() && target.dimensions().isEmpty()
                        && baseline.filters().isEmpty() && target.filters().isEmpty());
                var dependency = new CampaignDependencyAnalysisPlanFactory.Request(baseline.gid(),
                        baseline.period().startDate(), baseline.period().endDate(), target.period().startDate(),
                        target.period().endDate(), goal.metric(), List.of("province", "device"), List.of());
                var prepared = dependencies.prepare(caller, sessionId, key, dependency, expiry);
                String prefix = id + "-";
                prepared.frozen().inputs().inputContracts().forEach((name, port) -> ports.put(prefix + name, port));
                prepared.frozen().inputs().inputValues().forEach((name, value) -> values.put(prefix + name, value));
                dependencyInputs.add(new DependencyInput(prefix, prepared.frozen().inputs(), prepared.definition(), dependency));
                requirements.add(requirement(id + "-selected", id, "selected-entities-delivery", Map.of()));
                requirements.add(requirement(id + "-dimensions", id, "dimension-change-delivery", Map.of()));
            } else if (Set.of("STATISTICS", "EXPLORE").contains(goal.method()) && !goal.queries().isEmpty()) {
                for (int q = 0; q < goal.queries().size(); q++) {
                    var query = query(id + "-query-" + (q + 1) + "-", goal.queries().get(q));
                    queries.add(query);
                    FrozenStatisticsJobQuery.INPUTS.forEach((name, port) -> ports.put(query.prefix() + name, port));
                    values.put(query.prefix() + "scope", query.scopeRef());
                    values.put(query.prefix() + "periods", query.periodsRef());
                    values.put(query.prefix() + "query", query.descriptor());
                    requirements.add(new PlanningAssessment.Requirement(id + "-query-" + (q + 1), id,
                            PlanningAssessment.RequirementKind.DATA, true, "statistics-query-evidence", "1",
                            Map.of("queryHash", CampaignRunStore.sha256(FrozenCampaignRun.encode(query.request())))));
                }
                requirements.add(requirement(id + "-delivery", id, "statistics-evidence-delivery", Map.of()));
            } else {
                requirements.add(requirement(id + "-unresolved", id, "unresolved-analysis-delivery", Map.of()));
            }
            if (goal.causal()) requirements.add(new PlanningAssessment.Requirement(id + "-causal", id,
                    PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE, true, "causal-evidence", "1", Map.of()));
            String analysisSuffix = "DECLINE_DIMENSIONS".equals(goal.method()) ? "-dimensions"
                    : Set.of("STATISTICS", "EXPLORE").contains(goal.method()) && !goal.queries().isEmpty() ? "" : "-unresolved";
            if (goal.needsAnalysis()) requirements.add(new PlanningAssessment.Requirement(id + "-analysis", id,
                    PlanningAssessment.RequirementKind.CALCULATION, true, "analysis-interpretation" + analysisSuffix, "1", Map.of()));
            if (goal.needsRecommendation()) requirements.add(new PlanningAssessment.Requirement(id + "-recommendation", id,
                    PlanningAssessment.RequirementKind.CALCULATION, true, "analysis-recommendation" + analysisSuffix, "1", Map.of()));
        }
        String ref = "inputs-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(SCHEMA, identity.runId(), ports, values)));
        var inputs = new FrozenInputSet(ref, identity.runId(), ports, values);
        return new Built(inputs, List.copyOf(goals), List.copyOf(requirements),
                new Inspected(request, expiry, List.copyOf(queries), List.copyOf(dependencyInputs)));
    }

    private static QueryInput query(String prefix, CampaignInterpretedRequest.Query query) {
        String scope = "current-group.v1:" + query.gid();
        String periods = "period.v1:" + query.period().startDate() + ":" + query.period().endDate();
        CampaignStatisticsCurrentInputAuthorizer.parseGroupId(scope);
        CampaignStatisticsCurrentInputAuthorizer.parsePeriod(periods);
        Map<String,Object> descriptor = new LinkedHashMap<>();
        descriptor.put("schemaVersion", FrozenStatisticsJobQuery.SCHEMA);
        descriptor.put("scopeRef", scope); descriptor.put("periodsRef", periods);
        descriptor.put("scopeKind", "CURRENT_GROUP"); descriptor.put("gid", query.gid());
        descriptor.put("startDate", query.period().startDate()); descriptor.put("endDate", query.period().endDate());
        descriptor.put("businessTimezone", "Asia/Shanghai"); descriptor.put("queryKind", query.queryKind());
        if (query.fullShortUrl() != null) descriptor.put("fullShortUrl", query.fullShortUrl());
        if ("DIMENSION_BREAKDOWN".equals(query.queryKind())) {
            descriptor.put("dimensions", query.dimensions()); descriptor.put("filters", query.filters());
        }
        return new QueryInput(prefix, scope, periods, Map.copyOf(descriptor),
                FrozenStatisticsJobQuery.prepareRequest(scope, periods, descriptor));
    }

    public CapabilityCatalog catalog() {
        var dependencyCatalog = CampaignDependencyAnalysisPlanFactory.catalog();
        return new CapabilityCatalog() {
            public String version() { return CATALOG_VERSION; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                return StatisticsJobFixedExecutor.REF.equals(ref) ? Optional.of(StatisticsJobFixedExecutor.capability())
                        : dependencyCatalog.capability(ref);
            }
            public Optional<Policy> policy(String ref, String version) {
                return CampaignExplorationRuntimeFactory.STATISTICS_POLICY.equals(ref) && "1".equals(version)
                        ? Optional.of(CampaignExplorationRuntimeFactory.statisticsPolicy()) : Optional.empty();
            }
            public Optional<Criterion> criterion(String ref, String version) {
                if (!"1".equals(version)) return Optional.empty();
                if ("statistics-query-evidence".equals(ref)) return Optional.of(new Criterion(ref, version,
                        PlanningAssessment.RequirementKind.DATA,
                        new Parameters(Set.of("queryHash"), Map.of(
                                "queryHash", v -> v instanceof String s && s.matches("[a-f0-9]{64}"))),
                        Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE)));
                if ("statistics-evidence-delivery".equals(ref)) return Optional.of(new Criterion(ref, version,
                        PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE)));
                if (Set.of("analysis-interpretation", "analysis-recommendation", "analysis-interpretation-dimensions",
                        "analysis-recommendation-dimensions", "analysis-interpretation-unresolved", "analysis-recommendation-unresolved").contains(ref))
                    return Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.CALCULATION,
                            Parameters.none(), Set.of(ref.endsWith("-dimensions") ? new TypeRef("DimensionChangeArtifact", 1, Cardinality.ONE)
                                    : ref.endsWith("-unresolved") ? new TypeRef("UnavailableBusinessEvidence", 1, Cardinality.ONE)
                                    : StatisticsJobFixedExecutor.OUTPUT_TYPE)));
                if ("unresolved-analysis-delivery".equals(ref) || "causal-evidence".equals(ref))
                    return Optional.of(new Criterion(ref, version, "causal-evidence".equals(ref)
                            ? PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE : PlanningAssessment.RequirementKind.DELIVERY,
                            Parameters.none(), Set.of(new TypeRef("UnavailableBusinessEvidence", 1, Cardinality.ONE))));
                return dependencyCatalog.criterion(ref, version);
            }
        };
    }

    public ArtifactContractRegistry contracts() {
        List<ArtifactContractRegistry.Contract> contracts = new ArrayList<>();
        contracts.add(StatisticsJobFixedExecutor.artifactContract());
        contracts.add(ScopeCollectionFixedExecutor.artifactContract());
        contracts.addAll(DeclineSelectionSkill.artifactContracts().stream()
                .filter(contract -> !"ScopeArtifact".equals(contract.type().name())).toList());
        contracts.addAll(DimensionChangeSkill.artifactContracts());
        return new ArtifactContractRegistry(contracts);
    }

    public PlanningProposal.Menu menu() {
        Map<String,Object> none = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of(), "required", List.of());
        return PlanningProposal.menu(catalog(), List.of(
                new PlanningProposal.CapabilityOffer(StatisticsJobFixedExecutor.REF,
                        "Collect one frozen statistics query. Bind scope, periods, query with the same goal-query prefix; preserve every query requirement. Bind analysis-interpretation/recommendation requirements to actual pages outputs: report synthesis performs interpretation after data arrives; do not declare a planning gap merely because text is produced later.", none),
                new PlanningProposal.CapabilityOffer(FrozenScopeCollection.REF,
                        "Collect all authorized links for one goal's frozen collectionDefinition; scopeArtifact feeds decline_selection v2.", none),
                new PlanningProposal.CapabilityOffer(FrozenDeclineSelection.REF_V2,
                        "Select all declining links from scope_collection output. Bind periods and selectionDefinition from the same goal prefix; metric must match its frozen operation.",
                        Map.of("type", "object", "additionalProperties", false, "required", List.of("metric"), "properties",
                                Map.of("metric", Map.of("type", "string", "enum", List.of("PV", "UV", "UIP"))))),
                new PlanningProposal.CapabilityOffer(FrozenDimensionChange.REF_V2,
                        "Measure province/device changes for the selected cohort. selectedEntities and selectionEvidence must come from the same decline_selection step; all descriptors use the same goal prefix. Bind analysis-interpretation-dimensions and analysis-recommendation-dimensions to dimensionChanges output; report synthesis supplies the later interpretation. Causal proof remains unavailable and requires an explicit gap.", none)),
                List.of(new PlanningProposal.PolicyOffer(CampaignExplorationRuntimeFactory.STATISTICS_POLICY, "1",
                        "Native local exploration over a frozen statistics query, with the same inputs and evidence contract. Use a separate step for each distinct scope/period/query; do not invent causal proof.", none)));
    }

    private static PlanningAssessment.Requirement requirement(String id, String goal, String criterion, Map<String,Object> parameters) {
        return new PlanningAssessment.Requirement(id, goal, PlanningAssessment.RequirementKind.DELIVERY, true, criterion, "1", parameters);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        require(value instanceof Map<?,?>); return (Map<String,Object>) value;
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("BUSINESS_REQUEST_INVALID"); }
}
