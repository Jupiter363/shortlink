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

    /** Normalize only proven shared queries and inseparable output pairs; this is not an assessment verdict. */
    public PlanningProposal normalizeProposal(Caller caller, PlanningProposal.Request request, PlanningProposal candidate) {
        Objects.requireNonNull(candidate);
        var parsed = inspect(caller, request.inputs());
        var manifest = object(request.inputs().inputValues().get(MANIFEST));
        String session = (String) manifest.get("sessionId"), key = (String) manifest.get("requestKey");
        Built expected = build(caller, session, key, parsed.request(), parsed.expiresAt());
        require(request.question().equals(parsed.request().question()) && request.inputs().equals(expected.inputs())
                && request.goals().equals(expected.goals()) && request.requirements().equals(expected.requirements()));
        candidate = reuseStatisticsProducers(parsed, request, candidate);
        var identity = JdbcCampaignRunIntakeStore.identity(caller, session, key);
        var proof = FrozenCampaignRun.freeze(new PlanSpec(PlanSpec.SCHEMA_VERSION, identity.planId(), 1,
                request.inputs().runId(), request.inputs().inputSetRef(), request.goals(), candidate.steps()), request.inputs(),
                new PlanningAssessment(identity.planId(), 1, catalog().version(), request.requirements(),
                        candidate.coverageBindings(), candidate.gaps())).definition(caller, session);
        // A missing port reference must not repair a changed scope, period, metric, prerequisite or Skill pin.
        validateDefinition(proof);
        var selections = FrozenDeclineSelection.templates(proof);
        Map<String,PlanningAssessment.Requirement> requirements = new HashMap<>();
        request.requirements().forEach(value -> requirements.put(value.requirementId(), value));
        Set<String> gaps = new HashSet<>();
        candidate.gaps().forEach(value -> gaps.add(value.requirementId()));
        Set<String> seen = new HashSet<>();
        List<PlanningAssessment.CoverageBinding> coverage = new ArrayList<>();
        for (var binding : candidate.coverageBindings()) {
            require(seen.add(binding.requirementId()));
            var requirement = requirements.get(binding.requirementId());
            if (requirement == null || !"selected-entities-delivery".equals(requirement.criterionRef())
                    || !"1".equals(requirement.criterionVersion()) || gaps.contains(binding.requirementId())) {
                coverage.add(binding); continue;
            }
            // Do not guess a producer from type alone, replace another source, or repair a missing coverage binding.
            var selected = binding.evidenceOutputs().stream().filter(value -> "selectedEntities".equals(value.output())).toList();
            require(selected.size() == 1 && binding.evidenceOutputs().size() >= 1 && binding.evidenceOutputs().size() <= 2);
            String producer = selected.get(0).stepId();
            var template = selections.get(producer);
            require(template != null && template.step().goalIds().contains(requirement.goalId())
                    && binding.evidenceOutputs().stream().allMatch(value -> producer.equals(value.stepId())
                        && Set.of("selectedEntities", "selectionEvidence").contains(value.output())));
            if (binding.evidenceOutputs().size() == 2) {
                require(binding.evidenceOutputs().stream().filter(value -> "selectionEvidence".equals(value.output())).count() == 1);
                coverage.add(binding);
            } else {
                coverage.add(new PlanningAssessment.CoverageBinding(binding.requirementId(), List.of(selected.get(0),
                        new PlanningAssessment.EvidenceOutput(producer, "selectionEvidence"))));
            }
        }
        // Output-pair completion preserves explicit gaps and the normalized dependency graph. General validation still follows.
        return coverage.equals(candidate.coverageBindings()) ? candidate
                : new PlanningProposal(candidate.schemaVersion(), candidate.steps(), coverage, candidate.gaps());
    }

    private record StatisticsEvidence(QueryInput query, PlanSpec.Step step) {}

    /** A complete DATA binding, including the frozen query hash, is required before reusing any producer. */
    private static List<StatisticsEvidence> statisticsEvidence(Inspected parsed, FrozenInputSet inputs,
            PlanningAssessment assessment, Map<String,PlanSpec.Step> steps, int goalIndex) {
        var goal = parsed.request().goals().get(goalIndex);
        if (!"STATISTICS".equals(goal.method()) || goal.queries().isEmpty()) return List.of();
        String goalId = "goal-" + (goalIndex + 1);
        List<StatisticsEvidence> result = new ArrayList<>();
        for (var query : parsed.queries().stream().filter(value -> value.prefix().startsWith(goalId + "-query-")).toList()) {
            String requirementId = query.prefix().substring(0, query.prefix().length() - 1);
            var expected = new PlanningAssessment.Requirement(requirementId, goalId,
                    PlanningAssessment.RequirementKind.DATA, true, "statistics-query-evidence", "1",
                    Map.of("queryHash", CampaignRunStore.sha256(FrozenCampaignRun.encode(query.request()))));
            if (assessment.requirements().stream().filter(value -> requirementId.equals(value.requirementId())).count() != 1
                    || !assessment.requirements().contains(expected)
                    || assessment.gaps().stream().anyMatch(value -> requirementId.equals(value.requirementId()))) return List.of();
            var bindings = assessment.coverageBindings().stream().filter(value -> requirementId.equals(value.requirementId())).toList();
            if (bindings.size() != 1 || bindings.get(0).evidenceOutputs().size() != 1) return List.of();
            var output = bindings.get(0).evidenceOutputs().get(0);
            var step = steps.get(output.stepId());
            if (!"pages".equals(output.output()) || step == null || !step.goalIds().contains(goalId)
                    || step.stepId() == null || !step.stepId().matches("[A-Za-z0-9][A-Za-z0-9._:-]*")
                    || step.executionMode() != PlanSpec.ExecutionMode.FIXED || !StatisticsJobFixedExecutor.REF.equals(step.executor())
                    || step.explorationPolicy() != null
                    || !step.parameters().isEmpty() || !"statistics-job-pages/v1".equals(step.outputContractRef())
                    || !step.inputBindings().keySet().equals(Set.of("scope", "periods", "query"))
                    || step.inputBindings().entrySet().stream().anyMatch(entry -> {
                        var binding = entry.getValue();
                        return binding == null || binding.source() != PlanBinding.Source.INPUT
                                || !binding.equals(PlanBinding.input(binding.input()))
                                || !FrozenStatisticsJobQuery.INPUTS.get(entry.getKey()).equals(inputs.inputContracts().get(binding.input()));
                    })
                    || !query.scopeRef().equals(input(inputs, step, "scope"))
                    || !query.periodsRef().equals(input(inputs, step, "periods"))
                    || !query.descriptor().equals(input(inputs, step, "query"))) return List.of();
            result.add(new StatisticsEvidence(query, step));
        }
        return result.size() == goal.queries().size() ? List.copyOf(result) : List.of();
    }

    /** All current and prerequisite queries must match, rather than one type-compatible or unbound side branch. */
    private static Map<String,String> sharedStatisticsMapping(Inspected parsed, FrozenInputSet inputs,
            PlanningAssessment assessment, Map<String,PlanSpec.Step> steps, int goalIndex) {
        var goal = parsed.request().goals().get(goalIndex);
        if (goal.dependsOn().isEmpty()) return Map.of();
        var own = statisticsEvidence(parsed, inputs, assessment, steps, goalIndex);
        if (own.isEmpty()) return Map.of();
        List<StatisticsEvidence> upstream = new ArrayList<>();
        for (int prerequisite : goal.dependsOn()) {
            var evidence = statisticsEvidence(parsed, inputs, assessment, steps, prerequisite);
            if (evidence.isEmpty()) return Map.of();
            upstream.addAll(evidence);
        }
        if (upstream.stream().anyMatch(source -> own.stream().noneMatch(value -> sameQuery(source.query(), value.query()))))
            return Map.of();
        Map<String,String> mapping = new LinkedHashMap<>();
        for (var value : own) {
            var producers = upstream.stream().filter(source -> sameQuery(source.query(), value.query()))
                    .map(source -> source.step().stepId()).distinct().toList();
            if (producers.size() != 1) return Map.of();
            String previous = mapping.putIfAbsent(value.step().stepId(), producers.get(0));
            if (previous != null && !previous.equals(producers.get(0))) return Map.of();
        }
        return mapping;
    }

    private static boolean sameQuery(QueryInput left, QueryInput right) {
        return left.scopeRef().equals(right.scopeRef()) && left.periodsRef().equals(right.periodsRef())
                && left.descriptor().equals(right.descriptor()) && left.request().equals(right.request());
    }

    private static Map<String,PlanSpec.Step> orderedSteps(List<PlanSpec.Step> ordered) {
        Map<String,PlanSpec.Step> steps = new LinkedHashMap<>();
        for (var step : ordered) {
            require(!steps.containsKey(step.stepId()) && steps.keySet().containsAll(step.dependsOn())
                    && new HashSet<>(step.dependsOn()).size() == step.dependsOn().size()
                    && new HashSet<>(step.goalIds()).size() == step.goalIds().size());
            steps.put(step.stepId(), step);
        }
        return steps;
    }

    /** Keep upstream step/request identities; never coalesce a Skill, exploration, or partially matching query set. */
    private static PlanningProposal reuseStatisticsProducers(Inspected parsed, PlanningProposal.Request request,
            PlanningProposal original) {
        PlanningProposal candidate = original;
        require(original.coverageBindings().stream().allMatch(binding ->
                new HashSet<>(binding.evidenceOutputs()).size() == binding.evidenceOutputs().size()));
        for (int index = 0; index < parsed.request().goals().size(); index++) {
            var steps = orderedSteps(candidate.steps());
            var assessment = new PlanningAssessment("normalization", 1, CATALOG_VERSION, request.requirements(),
                    candidate.coverageBindings(), candidate.gaps());
            var mapping = sharedStatisticsMapping(parsed, request.inputs(), assessment, steps, index);
            Map<String,String> replacements = new LinkedHashMap<>(mapping);
            replacements.entrySet().removeIf(entry -> entry.getKey().equals(entry.getValue()));
            if (replacements.isEmpty()) continue;
            String goalId = "goal-" + (index + 1);
            var producers = new HashSet<>(mapping.values());
            var order = new ArrayList<>(steps.keySet());
            boolean safe = replacements.entrySet().stream().allMatch(entry -> {
                var duplicate = steps.get(entry.getKey());
                return duplicate.goalIds().equals(List.of(goalId)) && producers.containsAll(duplicate.dependsOn())
                        && order.indexOf(entry.getValue()) < order.indexOf(entry.getKey());
            }) && steps.values().stream().flatMap(step -> step.inputBindings().values().stream())
                    .noneMatch(binding -> binding.source() == PlanBinding.Source.STEP_OUTPUT && replacements.containsKey(binding.stepId()));
            for (var binding : candidate.coverageBindings()) {
                if (binding.evidenceOutputs().stream().noneMatch(output -> replacements.containsKey(output.stepId()))) continue;
                safe &= request.requirements().stream().anyMatch(requirement -> requirement.requirementId().equals(binding.requirementId())
                        && goalId.equals(requirement.goalId()))
                        && binding.evidenceOutputs().stream().allMatch(output -> "pages".equals(output.output()));
            }
            if (!safe) continue;
            List<PlanSpec.Step> reused = new ArrayList<>();
            for (var step : candidate.steps()) {
                if (replacements.containsKey(step.stepId())) continue;
                var goalIds = new LinkedHashSet<>(step.goalIds());
                if (producers.contains(step.stepId())) goalIds.add(goalId);
                reused.add(new PlanSpec.Step(step.stepId(), List.copyOf(goalIds), step.executionMode(), step.executor(),
                        step.explorationPolicy(), step.dependsOn().stream().map(id -> replacements.getOrDefault(id, id)).distinct().toList(),
                        step.inputBindings(), step.parameters(), step.outputContractRef()));
            }
            orderedSteps(reused); // Reject self/forward edges and cycles after redirects as well.
            var coverage = candidate.coverageBindings().stream().map(binding -> new PlanningAssessment.CoverageBinding(
                    binding.requirementId(), binding.evidenceOutputs().stream().map(output -> new PlanningAssessment.EvidenceOutput(
                            replacements.getOrDefault(output.stepId(), output.stepId()), output.output())).distinct().toList())).toList();
            candidate = new PlanningProposal(candidate.schemaVersion(), reused, coverage, candidate.gaps());
        }
        return candidate;
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
        Map<Integer,List<Integer>> evidenceSources = statisticsEvidenceSources(parsed.request());
        Map<Integer,List<Integer>> skillSources = dependencyEvidenceSources(parsed.request());
        var collections = FrozenScopeCollection.resolve(definition);
        var selections = FrozenDeclineSelection.templates(definition);
        var dimensions = FrozenDimensionChange.resolveTemplates(definition, FrozenDimensionChange.REF_V2);
        var statistics = FrozenStatisticsJobQuery.resolve(definition, StatisticsJobFixedExecutor.REF);
        Map<String,PlanSpec.Step> steps = new HashMap<>();
        frozen.plan().steps().forEach(step -> steps.put(step.stepId(), step));
        Map<Integer,Set<String>> sharedStatisticsGoals = new HashMap<>();
        Map<Integer,Set<String>> sharedSkillGoals = new HashMap<>();
        for (int index = 0; index < parsed.request().goals().size(); index++) {
            var mapping = sharedStatisticsMapping(parsed, frozen.inputs(), frozen.assessment(), steps, index);
            if (!mapping.isEmpty() && mapping.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue())))
                sharedStatisticsGoals.put(index, Set.copyOf(mapping.values()));
            String goalId = "goal-" + (index + 1);
            if (parsed.request().goals().get(index).queries().isEmpty() && !skillSources.get(index).isEmpty()
                    && steps.values().stream().anyMatch(step -> step.goalIds().contains(goalId)))
                sharedSkillGoals.put(index, inheritedSkillSteps(parsed, frozen, index, skillSources.get(index),
                        collections, selections, dimensions));
        }
        for (var step : frozen.plan().steps()) {
            require(!step.goalIds().isEmpty());
            for (String goalId : step.goalIds()) {
                require(goalId.matches("goal-[1-9][0-9]*"));
                int index = Integer.parseInt(goalId.substring(5)) - 1;
                require(index < parsed.request().goals().size());
                var sourceGoal = parsed.request().goals().get(index);
                if (sharedSkillGoals.containsKey(index)) {
                    // These are the original producers, whose own goal bindings are still checked below.
                    require(sharedSkillGoals.get(index).contains(step.stepId()));
                    continue;
                }
                boolean inheritedStatistics = sourceGoal.queries().isEmpty() && !evidenceSources.get(index).isEmpty();
                if (statistics.containsKey(step.stepId()) || step.executionMode() == PlanSpec.ExecutionMode.REACT) {
                    var scope = input(frozen.inputs(), step, "scope");
                    var periods = input(frozen.inputs(), step, "periods");
                    var query = input(frozen.inputs(), step, "query");
                    require(parsed.queries().stream().anyMatch(value -> value.scopeRef().equals(scope)
                            && value.periodsRef().equals(periods) && value.descriptor().equals(query)
                            && (value.prefix().startsWith(goalId + "-query-") || inheritedStatistics
                                && evidenceSources.get(index).stream().anyMatch(source -> {
                                    String producerGoal = "goal-" + (source + 1);
                                    return value.prefix().startsWith(producerGoal + "-query-")
                                            && step.goalIds().contains(producerGoal);
                                }))));
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
                // A report-only consumer shares its prerequisite's producer; it does not execute
                // another query after that producer or introduce an impossible self-dependency.
                // Separate shared producers cannot be ancestors of each other. Only the complete,
                // exact DATA coverage proof above permits their report goal to join both results.
                if (inheritedStatistics || sharedStatisticsGoals.getOrDefault(index, Set.of()).contains(step.stepId())) continue;
                var ancestors = ancestors(step, steps, new HashSet<>());
                for (int prerequisite : sourceGoal.dependsOn()) {
                    String requiredGoal = "goal-" + (prerequisite + 1);
                    require(ancestors.stream().map(steps::get).filter(Objects::nonNull)
                            .anyMatch(value -> value.goalIds().contains(requiredGoal))
                            || sharesFrozenStatisticsQuery(parsed, frozen.inputs(), step, requiredGoal));
                }
            }
        }
        lastValidated = new ValidatedDefinition(definition, frozen.inputs(), parsed);
        return parsed;
    }

    /** A report-only consumer needs the complete original chain and exact evidence, not just matching types. */
    private static Set<String> inheritedSkillSteps(Inspected parsed, FrozenCampaignRun frozen, int index,
            List<Integer> sources, Map<String,FrozenScopeCollection.Bound> collections,
            Map<String,FrozenDeclineSelection.Template> selections, Map<String,FrozenDimensionChange.Template> dimensions) {
        String goalId = "goal-" + (index + 1);
        Set<String> producers = new LinkedHashSet<>();
        List<PlanningAssessment.EvidenceOutput> dimensionOutputs = new ArrayList<>();
        for (int source : sources) {
            String sourceId = "goal-" + (source + 1);
            var branch = parsed.dependencies().stream().filter(value -> value.prefix().equals(sourceId + "-"))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("BUSINESS_GOAL_BINDING_CHANGED"));
            var sourceSelected = exactCoverage(frozen.assessment(),
                    requirement(sourceId + "-selected", sourceId, "selected-entities-delivery", Map.of()));
            var sourceDimensions = exactCoverage(frozen.assessment(),
                    requirement(sourceId + "-dimensions", sourceId, "dimension-change-delivery", Map.of()));
            require(sourceSelected.size() == 2 && sourceDimensions.size() == 1);
            var selected = sourceSelected.stream().filter(value -> "selectedEntities".equals(value.output())).toList();
            require(selected.size() == 1);
            var selection = selections.get(selected.get(0).stepId());
            var dimension = dimensions.get(sourceDimensions.get(0).stepId());
            require(selection != null && dimension != null
                    && sourceSelected.contains(new PlanningAssessment.EvidenceOutput(selection.step().stepId(), "selectionEvidence"))
                    && "dimensionChanges".equals(sourceDimensions.get(0).output())
                    && selection.step().stepId().equals(dimension.upstreamStepId())
                    && selection.descriptor().equals(branch.inputs().inputValues().get("selectionDefinition"))
                    && selection.metric().name().equals(branch.request().metric())
                    && dimension.descriptor().equals(branch.inputs().inputValues().get("dimensionDefinition")));
            var collection = collections.get(selection.upstreamStepId());
            require(collection != null && collection.descriptor().equals(branch.inputs().inputValues().get("collectionDefinition")));
            for (var producer : List.of(collection.step(), selection.step(), dimension.step())) {
                require(producer.goalIds().contains(sourceId) && producer.goalIds().contains(goalId));
                producers.add(producer.stepId());
            }
            require(new HashSet<>(exactCoverage(frozen.assessment(), requirement(goalId + "-source-" + (source + 1)
                            + "-selected", goalId, "selected-entities-delivery", Map.of()))).equals(new HashSet<>(sourceSelected))
                    && exactCoverage(frozen.assessment(), requirement(goalId + "-source-" + (source + 1)
                            + "-dimensions", goalId, "dimension-change-delivery", Map.of())).equals(sourceDimensions));
            dimensionOutputs.addAll(sourceDimensions);
        }
        var goal = parsed.request().goals().get(index);
        for (String kind : List.of("analysis", "recommendation")) {
            if (!("analysis".equals(kind) ? goal.needsAnalysis() : goal.needsRecommendation())) continue;
            var expected = new PlanningAssessment.Requirement(goalId + "-" + kind, goalId,
                    PlanningAssessment.RequirementKind.CALCULATION, true,
                    "analysis-" + ("analysis".equals(kind) ? "interpretation" : "recommendation") + "-dimensions", "1", Map.of());
            require(new HashSet<>(exactCoverage(frozen.assessment(), expected)).equals(new HashSet<>(dimensionOutputs)));
        }
        return Set.copyOf(producers);
    }

    private static List<PlanningAssessment.EvidenceOutput> exactCoverage(PlanningAssessment assessment,
            PlanningAssessment.Requirement expected) {
        require(assessment.requirements().stream().filter(value -> expected.requirementId().equals(value.requirementId())).count() == 1
                && assessment.requirements().contains(expected)
                && assessment.gaps().stream().noneMatch(value -> expected.requirementId().equals(value.requirementId())));
        var bindings = assessment.coverageBindings().stream()
                .filter(value -> expected.requirementId().equals(value.requirementId())).toList();
        require(bindings.size() == 1 && !bindings.get(0).evidenceOutputs().isEmpty()
                && new HashSet<>(bindings.get(0).evidenceOutputs()).size() == bindings.get(0).evidenceOutputs().size());
        return bindings.get(0).evidenceOutputs();
    }

    private static Object input(FrozenInputSet inputs, PlanSpec.Step step, String name) {
        var binding = step.inputBindings().get(name);
        require(binding != null && binding.source() == PlanBinding.Source.INPUT);
        return inputs.inputValues().get(binding.input());
    }

    private static boolean sharesFrozenStatisticsQuery(Inspected parsed, FrozenInputSet inputs,
            PlanSpec.Step step, String prerequisiteGoal) {
        if (step.executionMode() != PlanSpec.ExecutionMode.FIXED
                || !StatisticsJobFixedExecutor.REF.equals(step.executor())
                || !step.goalIds().contains(prerequisiteGoal)) return false;
        // The current goal's exact query was checked above. An identical prerequisite query
        // may share this producer; requiring it to be an ancestor would create a self-edge.
        var scope = input(inputs, step, "scope");
        var periods = input(inputs, step, "periods");
        var query = input(inputs, step, "query");
        return parsed.queries().stream().anyMatch(value -> value.prefix().startsWith(prerequisiteGoal + "-query-")
                && value.scopeRef().equals(scope) && value.periodsRef().equals(periods)
                && value.descriptor().equals(query));
    }

    private static Set<String> ancestors(PlanSpec.Step step, Map<String,PlanSpec.Step> steps, Set<String> result) {
        for (String previous : step.dependsOn()) if (result.add(previous)) {
            require(steps.containsKey(previous)); ancestors(steps.get(previous), steps, result);
        }
        return result;
    }

    /** Resolve only explicit, acyclic statistical evidence dependencies; never infer a new query. */
    private static Map<Integer,List<Integer>> statisticsEvidenceSources(CampaignInterpretedRequest request) {
        return evidenceSources(request, false);
    }

    private static Map<Integer,List<Integer>> dependencyEvidenceSources(CampaignInterpretedRequest request) {
        return evidenceSources(request, true);
    }

    private static Map<Integer,List<Integer>> evidenceSources(CampaignInterpretedRequest request, boolean skills) {
        Map<Integer,List<Integer>> resolved = new HashMap<>();
        Set<Integer> visiting = new HashSet<>();
        for (int index = 0; index < request.goals().size(); index++)
            evidenceSources(request, index, resolved, visiting, skills);
        return Map.copyOf(resolved);
    }

    private static List<Integer> evidenceSources(CampaignInterpretedRequest request, int index,
            Map<Integer,List<Integer>> resolved, Set<Integer> visiting, boolean skills) {
        if (resolved.containsKey(index)) return resolved.get(index);
        require(index >= 0 && index < request.goals().size() && visiting.add(index));
        var goal = request.goals().get(index);
        require(new HashSet<>(goal.dependsOn()).size() == goal.dependsOn().size());
        Set<Integer> inherited = new LinkedHashSet<>();
        boolean complete = !goal.dependsOn().isEmpty();
        for (int dependency : goal.dependsOn()) {
            List<Integer> sources = evidenceSources(request, dependency, resolved, visiting, skills);
            complete &= !sources.isEmpty();
            inherited.addAll(sources);
        }
        List<Integer> result = List.of();
        if (skills && "DECLINE_DIMENSIONS".equals(goal.method())) result = List.of(index);
        else if (Set.of("STATISTICS", "EXPLORE").contains(goal.method())) {
            if (!goal.queries().isEmpty()) result = skills ? List.of() : List.of(index);
            else if (complete && (goal.needsAnalysis() || goal.needsRecommendation())) result = List.copyOf(inherited);
        }
        visiting.remove(index);
        resolved.put(index, result);
        return result;
    }

    private Built build(Caller caller, String sessionId, String key, CampaignInterpretedRequest request, Instant expiry) {
        Objects.requireNonNull(expiry);
        require(expiry.isAfter(clock.instant()) && expiry.getNano() % 1_000_000 == 0);
        request = CampaignInterpretedRequest.parse(FrozenCampaignRun.encode(request), request.question());
        Map<Integer,List<Integer>> evidenceSources = statisticsEvidenceSources(request);
        Map<Integer,List<Integer>> skillSources = dependencyEvidenceSources(request);
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
            boolean inheritedStatistics = goal.queries().isEmpty() && !evidenceSources.get(i).isEmpty();
            boolean inheritedSkills = goal.queries().isEmpty() && !skillSources.get(i).isEmpty();
            goals.add(new PlanSpec.Goal(id, goal.question(), true,
                    "Deliver this complete objective with its exact frozen data and limitations. Method=" + goal.method()
                            + "; prerequisite goals=" + prerequisites + (inheritedStatistics
                                ? "; reuse frozen statistics from " + evidenceSources.get(i).stream().map(source -> "goal-" + (source + 1)).toList()
                                    + ". Add this goalId to those producer steps' goalIds and bind their existing pages; do not query again."
                                : inheritedSkills ? "; reuse the complete DECLINE_DIMENSIONS chains from "
                                    + skillSources.get(i).stream().map(source -> "goal-" + (source + 1)).toList()
                                    + ". Keep their original three steps and producer goalIds; add this goalId to all three steps. "
                                    + "Bind each source-selected requirement to its original selection step's selectedEntities AND selectionEvidence, "
                                    + "and each source-dimensions requirement to that chain's dimensionChanges. Bind analysis/recommendation "
                                    + "to ALL those dimensionChanges outputs. Do not create another query, Skill chain, or self-dependency."
                                : "; inputs prefixed " + id + "-." )
                            + (!goal.dependsOn().isEmpty() && "DECLINE_DIMENSIONS".equals(goal.method())
                                ? " This goal requires a new scope_collection -> decline_selection -> dimension_change chain. "
                                    + "Its scope_collection root must explicitly depend on all output-producing steps of prerequisite goals "
                                    + prerequisites + "; emit those prerequisite steps before the collection root in the topologically ordered steps array. "
                                    + "Later Skill steps inherit these edges transitively. Matching gid/dates do not "
                                    + "satisfy dependencies: previous CURRENT_GROUP queries are not this chain's new frozen cohort. "
                                    + "Keep the original prerequisite queries and do not claim that this chain reuses their results."
                                : "")));
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
                    if (goal.ranking() != null) requirements.add(new PlanningAssessment.Requirement(id + "-ranking-" + (q + 1), id,
                            PlanningAssessment.RequirementKind.CALCULATION, true, "statistics-ranking", "1",
                            Map.of("queryHash", CampaignRunStore.sha256(FrozenCampaignRun.encode(query.request())),
                                    "metric", goal.metric(), "order", goal.ranking().order(),
                                    "topN", goal.ranking().topN() == null ? 0 : goal.ranking().topN())));
                }
                requirements.add(requirement(id + "-delivery", id, "statistics-evidence-delivery", Map.of()));
            } else if (inheritedStatistics) {
                // Exact per-query DATA requirements prevent one upstream output from covering
                // missing periods/objects merely because all of them have StatisticsJobPages type.
                for (int source : evidenceSources.get(i)) {
                    var sourceGoal = request.goals().get(source);
                    for (int queryIndex = 0; queryIndex < sourceGoal.queries().size(); queryIndex++) {
                        String sourceId = "goal-" + (source + 1);
                        var query = query(sourceId + "-query-" + (queryIndex + 1) + "-", sourceGoal.queries().get(queryIndex));
                        requirements.add(new PlanningAssessment.Requirement(id + "-source-" + (source + 1) + "-query-" + (queryIndex + 1), id,
                                PlanningAssessment.RequirementKind.DATA, true, "statistics-query-evidence", "1",
                                Map.of("queryHash", CampaignRunStore.sha256(FrozenCampaignRun.encode(query.request())))));
                    }
                }
                requirements.add(requirement(id + "-delivery", id, "statistics-evidence-delivery", Map.of()));
            } else if (inheritedSkills) {
                for (int source : skillSources.get(i)) {
                    requirements.add(requirement(id + "-source-" + (source + 1) + "-selected", id,
                            "selected-entities-delivery", Map.of()));
                    requirements.add(requirement(id + "-source-" + (source + 1) + "-dimensions", id,
                            "dimension-change-delivery", Map.of()));
                }
            } else {
                requirements.add(requirement(id + "-unresolved", id, "unresolved-analysis-delivery", Map.of()));
            }
            if (goal.causal()) requirements.add(new PlanningAssessment.Requirement(id + "-causal", id,
                    PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE, true, "causal-evidence", "1", Map.of()));
            String analysisSuffix = "DECLINE_DIMENSIONS".equals(goal.method()) || inheritedSkills ? "-dimensions"
                    : !evidenceSources.get(i).isEmpty() ? "" : "-unresolved";
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
                if ("statistics-ranking".equals(ref)) return Optional.of(new Criterion(ref, version,
                        PlanningAssessment.RequirementKind.CALCULATION,
                        new Parameters(Set.of("queryHash", "metric", "order", "topN"), Map.of(
                                "queryHash", v -> v instanceof String s && s.matches("[a-f0-9]{64}"),
                                "metric", v -> v instanceof String s && Set.of("PV", "UV", "UIP").contains(s),
                                "order", v -> v instanceof String s && Set.of("ASC", "DESC").contains(s),
                                "topN", v -> v instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= 0 && n.intValue() <= 500)),
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
                        "Collect one frozen statistics query. Bind scope, periods, query with the same goal-query prefix; preserve every statistics-query-evidence requirement. The pages output is StatisticsJobPages evidence. Bind each statistics-ranking requirement to exactly the pages output of its queryHash; the server ranks complete data deterministically using its frozen metric/order/topN, so no separate ranking executor is needed. Bind statistics-evidence-delivery, analysis-interpretation and analysis-recommendation to all relevant pages outputs for their goal. The report layer renders complete data and then synthesizes interpretation/recommendations; no separate delivery or interpretation executor is needed. Coverage records evidence dependencies, not completed analysis; final assessment still requires actual data and validated narrative blocks.", none),
                new PlanningProposal.CapabilityOffer(FrozenScopeCollection.REF,
                        "Collect all authorized links for one goal's frozen collectionDefinition; scopeArtifact feeds decline_selection v2. If the goal has prerequisite goals, put their producer step IDs in this collection root's dependsOn; a new chain may not omit those edges merely because earlier queries have the same group and dates.", none),
                new PlanningProposal.CapabilityOffer(FrozenDeclineSelection.REF_V2,
                        "Select all declining links from scope_collection output. Bind periods and selectionDefinition from the same goal prefix; metric must match its frozen operation. Bind selected-entities-delivery to both selectedEntities and selectionEvidence from this same step; the report layer delivers their validated results.",
                        Map.of("type", "object", "additionalProperties", false, "required", List.of("metric"), "properties",
                                Map.of("metric", Map.of("type", "string", "enum", List.of("PV", "UV", "UIP"))))),
                new PlanningProposal.CapabilityOffer(FrozenDimensionChange.REF_V2,
                        "Measure province/device changes for the selected cohort. selectedEntities and selectionEvidence must come from the same decline_selection step; all descriptors use the same goal prefix. Bind dimension-change-delivery, analysis-interpretation-dimensions and analysis-recommendation-dimensions to dimensionChanges output; the report layer delivers data and synthesizes interpretation. Coverage is not an assessment verdict. Causal proof remains unavailable and requires an explicit gap.", none)),
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
