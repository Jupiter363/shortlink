package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore.SelectionPair;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDimensionChange.Bound;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDimensionChange.BoundQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fixed dependent Skill in NativePlanGraph; artifacts are resolved only after the producer succeeds. */
public final class DimensionChangeSkill {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeRef OUTPUT = new TypeRef(DimensionChangePublisher.TYPE, 1, Cardinality.ONE);
    private final RunToken token;
    private final AgentPrincipal current;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignDeclineSelectionStore selections;
    private final ShortLinkBusinessGateway gateway;
    private final StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer;
    private final ArtifactAuthorizer artifactAuthorizer;
    private final CampaignSelectedScope scopes;
    private final DimensionChangePublisher publisher;
    private final Map<String, Bound> bindings;
    private final FrozenCampaignRun frozen;
    private final ArtifactContractRegistry sourceContracts;
    private final RunPinnedSkills methods;

    public DimensionChangeSkill(RunToken token, AgentPrincipal current, Path approvedSkillsRoot,
            CampaignRunStore runs, CampaignStepStore steps, CampaignDeclineSelectionStore selections,
            CampaignStatisticsResultStore results, ShortLinkBusinessGateway gateway,
            StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer, ArtifactAuthorizer artifactAuthorizer) {
        this.token = Objects.requireNonNull(token); this.current = Objects.requireNonNull(current);
        var owner = token.definition().caller();
        if (current.system() || !owner.tenantId().equals(current.tenantId()) || !owner.subject().equals(current.username())
                || owner.authVersion() != current.authVersion()) throw new SecurityException("DIMENSION_PRINCIPAL_MISMATCH");
        this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps);
        this.selections = Objects.requireNonNull(selections); this.gateway = Objects.requireNonNull(gateway);
        this.queryAuthorizer = Objects.requireNonNull(queryAuthorizer); this.artifactAuthorizer = Objects.requireNonNull(artifactAuthorizer);
        this.frozen = FrozenCampaignRun.read(token.definition());
        this.bindings = FrozenDimensionChange.resolve(token.definition());
        if (bindings.isEmpty()) throw new IllegalArgumentException("DIMENSION_SKILL_NOT_IN_PLAN");
        List<RunPinnedSkills.SkillPin> pins = bindings.values().stream().map(Bound::skillPin).distinct().toList();
        if (pins.size() != 1) throw new IllegalArgumentException("DIMENSION_METHOD_PIN_CONFLICT");
        this.methods = new RunPinnedSkills(token.definition().runId(), approvedSkillsRoot, pins, Set.of(), List.of(),
                (runId, toolName) -> false);
        this.scopes = new CampaignSelectedScope(runs, selections, artifactAuthorizer);
        this.publisher = new DimensionChangePublisher(runs, scopes, new CampaignDimensionEvidence(runs, results), artifactAuthorizer);
        this.sourceContracts = new ArtifactContractRegistry(DeclineSelectionSkill.artifactContracts());
        // Upstream outputs do not exist yet when this Run is first constructed.
    }

    public static Capability capability() {
        return new Capability(FrozenDimensionChange.REF, new Signature(FrozenDimensionChange.INPUTS,
                FrozenDimensionChange.OUTPUT_CONTRACT, Map.of("dimensionChanges", new Port(OUTPUT, true)),
                new Parameters(Set.of(), Map.of())), false);
    }

    /** Merge with the producer's contracts in the shared driver; do not register duplicate types. */
    public static List<ArtifactContractRegistry.Contract> artifactContracts() {
        return List.of(new ArtifactContractRegistry.Contract(OUTPUT, DimensionChangePublisher.TYPE, DimensionChangePublisher.SCHEMA,
                value -> value.isObject() && DimensionChangePublisher.SCHEMA.equals(value.path("schemaVersion").asText())
                        && "SHARD_COHORT".equals(value.path("analysisUnit").asText())
                        && Set.of("OBSERVED", "NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE").contains(value.path("evidenceDisposition").asText())
                        && value.path("pageCount").isIntegralNumber() && value.path("memberCount").isIntegralNumber(),
                (metadata, quality) -> "OBSERVED_ONLY".equals(quality.path("interpretation").asText())));
    }

    public PersistentPlanDriver.FixedExecutor registration() {
        return new PersistentPlanDriver.FixedExecutor(FrozenDimensionChange.REF, policy(), this::execute);
    }

    private StepBindings.StepPolicy policy() {
        return new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { validateBound(step, inputs); }
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                Bound bound = bound(step); SelectionPair source = validateBound(step, inputs);
                ArtifactRef scope = readyScope(bound, source);
                if (scope == null) throw new IllegalArgumentException("DIMENSION_SCOPE_NOT_READY");
                var definition = definition(bound, scope);
                var position = publisher.progress(token, step.stepId(), definition);
                if (!position.complete()) throw new IllegalArgumentException("DIMENSION_PAGES_INCOMPLETE");
                var prepared = publisher.prepareFinal(token, step.stepId(), definition, position.previousArtifactId());
                var actual = runs.localOutputs(token, prepared.child().childId(), artifactAuthorizer);
                if (!outputs.keySet().equals(Set.of("dimensionChanges"))
                        || !Objects.equals(actual.get("dimensionChanges"), outputs.get("dimensionChanges").metadata().ref())
                        || !runs.child(token, prepared.child().childId()).orElseThrow().spec().equals(prepared.child()))
                    throw new IllegalArgumentException("DIMENSION_OUTPUT_MISMATCH");
            }
        };
    }

    /** No absent artifact IDs are synthesized. An unfinished dependency is ordinary Plan scheduling. */
    private SelectionPair upstream(Bound bound) {
        var producer = steps.step(token, bound.upstreamStepId());
        if (producer.isEmpty() || producer.get().status() != CampaignStepStore.StepStatus.SUCCEEDED) return null;
        SelectionPair[] checked = new SelectionPair[1];
        var resolver = new StepBindings(sourceContracts, runs, token.definition().caller(), artifactAuthorizer,
                (caller, type, value) -> caller.equals(token.definition().caller())
                        && ("ScopeRef".equals(type.name()) ? bound.scopeRef().equals(value)
                        : "PeriodsRef".equals(type.name()) && bound.periodsRef().equals(value)), new StepBindings.StepPolicy() {
                    @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { checked[0] = validateBound(step, inputs); }
                    @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                        throw new IllegalStateException("DIMENSION_SOURCE_READER_ONLY");
                    }
                });
        try (BoundInputs inputs = resolver.resolve(bound.step(), frozen.inputs(), capability().signature(),
                (stepId, name) -> steps.step(token, stepId)
                        .filter(step -> step.status() == CampaignStepStore.StepStatus.SUCCEEDED)
                        .map(step -> step.outputs().get(name)))) {
            resolver.reauthorize(capability().signature(), inputs);
            return Objects.requireNonNull(checked[0]);
        }
    }

    private SelectionPair validateBound(PlanSpec.Step step, BoundInputs inputs) {
        Bound bound = bound(step); requireCurrent();
        if (!bound.scopeRef().equals(inputs.value("scope")) || !bound.periodsRef().equals(inputs.value("periods"))
                || !bound.descriptor().equals(inputs.value("definition"))
                || inputs.artifact("selectedEntities") == null || inputs.artifact("selectionEvidence") == null)
            throw new IllegalArgumentException("DIMENSION_INPUT_MISMATCH");
        SelectionPair pair = selections.inspectPair(token.definition().caller(),
                inputs.artifact("selectedEntities").metadata().ref().artifactId(),
                inputs.artifact("selectionEvidence").metadata().ref().artifactId(), artifactAuthorizer);
        if (!pair.selectedEntities().equals(inputs.artifact("selectedEntities").metadata())
                || !pair.selectionEvidence().equals(inputs.artifact("selectionEvidence").metadata())
                || !bound.scopeRef().equals(pair.definition().scopeRef()) || !bound.periodsRef().equals(pair.definition().periodsRef())
                || !bound.periods().equals(pair.periods())) throw new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH");
        Artifact originalScope = runs.readArtifact(token.definition().caller(), pair.scopeArtifact().ref().artifactId(), artifactAuthorizer);
        try {
            if (!pair.scopeArtifact().equals(originalScope.metadata())
                    || !bound.gid().equals(JSON.readTree(originalScope.payloadJson()).path("gid").asText()))
                throw new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH", invalid); }
        return pair;
    }

    public boolean authorized() {
        try { requireCurrent(); for (Bound bound : bindings.values()) upstream(bound); return true; }
        catch (SecurityException | IllegalArgumentException | IllegalStateException denied) { return false; }
    }

    public Map<String, StatisticsJobResultReceiver.Target> resultTargets() {
        requireCurrent();
        Map<String, StatisticsJobResultReceiver.Target> targets = new LinkedHashMap<>();
        for (Bound bound : bindings.values()) {
            SelectionPair source = upstream(bound); if (source == null) continue;
            ArtifactRef scope = readyScope(bound, source); if (scope == null) continue;
            var position = publisher.progress(token, bound.step().stepId(), definition(bound, scope));
            if (!position.complete()) for (BoundQuery query : queries(bound, scope, position.shardIndex()))
                targets.put(query.child().childId(), query.target());
        }
        return Map.copyOf(targets);
    }

    public void prepareRecovery() {
        requireCurrent();
        for (Bound bound : bindings.values()) {
            var step = steps.step(token, bound.step().stepId());
            if (step.isEmpty() || step.get().status() != CampaignStepStore.StepStatus.BLOCKED
                    || !"STEP_RESULT_UNKNOWN".equals(step.get().reason())) continue;
            SelectionPair source = upstream(bound); if (source == null) continue;
            var scopePreparation = scopePreparation(bound, source);
            var scopeChild = runs.child(token, scopePreparation.child().childId());
            if (scopeChild.isPresent() && scopeChild.get().reason() == UnresolvedReason.LOCAL_RESULT_UNKNOWN) {
                steps.refreshLocalReplay(token, bound.step().stepId(), Map.of(scopeChild.get().spec().childId(), scopePreparation.approval()), artifactAuthorizer);
                continue;
            }
            ArtifactRef scope = readyScope(bound, source); if (scope == null) continue;
            var definition = definition(bound, scope);
            var position = publisher.progress(token, bound.step().stepId(), definition);
            DimensionChangePublisher.Prepared prepared;
            if (position.complete()) prepared = publisher.prepareFinal(token, bound.step().stepId(), definition, position.previousArtifactId());
            else {
                List<BoundQuery> queries = queries(bound, scope, position.shardIndex());
                if (!allReady(queries)) continue;
                prepared = publisher.preparePage(token, bound.step().stepId(), definition, slot(queries.get(0)), slot(queries.get(1)),
                        position.shardIndex(), position.side(), position.pageIndex(), position.previousArtifactId());
            }
            var child = runs.child(token, prepared.child().childId());
            if (child.isPresent() && child.get().reason() == UnresolvedReason.LOCAL_RESULT_UNKNOWN)
                steps.refreshLocalReplay(token, bound.step().stepId(), Map.of(prepared.child().childId(), prepared.approval()), artifactAuthorizer);
        }
    }

    private PersistentPlanDriver.Result execute(CampaignStepExecution context) throws Exception {
        Bound bound = bound(context.step());
        SelectionPair source = validateBound(context.step(), context.inputs());
        ArtifactRef scope = scopes.publish(context, token, source.selectedEntities().ref().artifactId(),
                source.selectionEvidence().ref().artifactId(), bound.periods());
        var definition = definition(bound, scope);
        for (;;) {
            context.requireCurrent(); requireCurrent();
            var position = publisher.progress(token, bound.step().stepId(), definition);
            if (position.complete()) return PersistentPlanDriver.Result.succeeded(Map.of("dimensionChanges",
                    publisher.finish(context, token, definition, position.previousArtifactId()).artifactId()));
            List<BoundQuery> queries = queries(bound, scope, position.shardIndex());
            boolean pending = false, waiting = false, capacity = false;
            for (BoundQuery query : queries) {
                ChildRecord child;
                try { child = context.child(query.child(), boundary -> StatisticsJobFixedExecutor.submit(boundary,
                        token.definition(), current, gateway, query.request(), () -> authorized(bound, scope, query))); }
                catch (StatisticsJobFixedExecutor.SubmissionUnresolved unknown) { pending = true; continue; }
                if (child.state() == ChildState.READY) {
                    if (!query.target().artifactId().equals(child.artifactId())) throw new IllegalArgumentException("DIMENSION_RESULT_BINDING_MISMATCH");
                } else {
                    pending = true; waiting |= child.state() == ChildState.WAITING;
                    capacity |= child.state() == ChildState.PREPARED && child.reason() == UnresolvedReason.QUERY_CAPACITY_EXHAUSTED;
                }
            }
            if (pending) return waiting ? PersistentPlanDriver.Result.waiting()
                    : PersistentPlanDriver.Result.blocked(capacity ? "REMOTE_CAPACITY" : "STEP_RESULT_UNKNOWN");
            publisher.publishPage(context, token, definition, slot(queries.get(0)), slot(queries.get(1)),
                    position.shardIndex(), position.side(), position.pageIndex(), position.previousArtifactId());
        }
    }

    private boolean authorized(Bound bound, ArtifactRef scope, BoundQuery query) {
        requireCurrent(); SelectionPair source = upstream(bound);
        if (source == null || !scope.equals(readyScope(bound, source))) return false;
        var requested = com.jupiter.shortlink.contract.FrozenQueryScope.fromMap((Map<?, ?>) query.request().get("scope"));
        if (!requested.equals(scopes.shard(token.definition().caller(), scope.artifactId(), requested.shardIndex()))) return false;
        return queryAuthorizer.mayUse(current, scope.scopeRef(), query.periodsRef(), query.request());
    }

    private CampaignSelectedScope.Prepared scopePreparation(Bound bound, SelectionPair pair) {
        return scopes.prepare(token, bound.step().stepId(), pair.selectedEntities().ref().artifactId(),
                pair.selectionEvidence().ref().artifactId(), bound.periods());
    }
    private ArtifactRef readyScope(Bound bound, SelectionPair pair) {
        var prepared = scopePreparation(bound, pair);
        var child = runs.child(token, prepared.child().childId());
        if (child.isEmpty() || child.get().state() != ChildState.READY) return null;
        if (!child.get().spec().equals(prepared.child())) throw new IllegalArgumentException("DIMENSION_SCOPE_CHANGED");
        var result = runs.localOutputs(token, child.get().spec().childId(), artifactAuthorizer).get(CampaignSelectedScope.OUTPUT);
        if (result == null || !prepared.approval().invocation().outputs().get(CampaignSelectedScope.OUTPUT).artifactId().equals(result.artifactId())
                || !result.equals(scopes.inspect(token.definition().caller(), result.artifactId()).metadata().ref()))
            throw new IllegalArgumentException("DIMENSION_SCOPE_CHANGED");
        return result;
    }
    private DimensionChangePublisher.Definition definition(Bound bound, ArtifactRef scope) {
        return new DimensionChangePublisher.Definition(bound.collectionId(), scope.artifactId(), bound.periodsRef(), bound.dimensions(), bound.filters());
    }
    private List<BoundQuery> queries(Bound bound, ArtifactRef scope, int shardIndex) {
        var shard = scopes.shard(token.definition().caller(), scope.artifactId(), shardIndex);
        return List.of(FrozenDimensionChange.query(token.definition(), bound, shard, 0), FrozenDimensionChange.query(token.definition(), bound, shard, 1));
    }
    private boolean allReady(List<BoundQuery> queries) {
        return queries.stream().allMatch(query -> runs.child(token, query.child().childId())
                .filter(child -> child.state() == ChildState.READY && query.target().artifactId().equals(child.artifactId())).isPresent());
    }
    private CampaignParentCoverage.Slot slot(BoundQuery query) { return new CampaignParentCoverage.Slot(token, query.child().childId(), query.target().artifactId()); }
    private Bound bound(PlanSpec.Step step) {
        Bound bound = bindings.get(step.stepId());
        if (bound == null || !bound.step().equals(step)) throw new IllegalArgumentException("DIMENSION_STEP_CHANGED");
        return bound;
    }
    private void requireCurrent() {
        methods.verifyPins();
        if (runs.loadRun(token.definition().caller(), token.definition().runId())
                .filter(run -> run.status() == RunStatus.ACTIVE && run.token().equals(token)).isEmpty())
            throw new SecurityException("DIMENSION_RUN_FENCED");
    }
}
