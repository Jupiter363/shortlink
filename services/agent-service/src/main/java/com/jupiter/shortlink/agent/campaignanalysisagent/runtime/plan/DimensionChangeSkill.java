package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDimensionChange.Template;
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
    private final Map<String, Template> templates;
    private final PlanSpec.ExecutorRef ref;
    private final FrozenCampaignRun frozen;
    private final ArtifactContractRegistry sourceContracts;
    private final RunPinnedSkills methods;

    public DimensionChangeSkill(RunToken token, AgentPrincipal current, Path approvedSkillsRoot,
            CampaignRunStore runs, CampaignStepStore steps, CampaignDeclineSelectionStore selections,
            CampaignStatisticsResultStore results, ShortLinkBusinessGateway gateway,
            StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer, ArtifactAuthorizer artifactAuthorizer) {
        this(token, current, approvedSkillsRoot, runs, steps, selections, results, gateway, queryAuthorizer,
                artifactAuthorizer, FrozenDimensionChange.REF);
    }

    public DimensionChangeSkill(RunToken token, AgentPrincipal current, Path approvedSkillsRoot,
            CampaignRunStore runs, CampaignStepStore steps, CampaignDeclineSelectionStore selections,
            CampaignStatisticsResultStore results, ShortLinkBusinessGateway gateway,
            StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer, ArtifactAuthorizer artifactAuthorizer,
            PlanSpec.ExecutorRef ref) {
        inputs(ref); // Reject unregistered versions before any runtime or source lookup.
        this.ref = ref;
        this.token = Objects.requireNonNull(token); this.current = Objects.requireNonNull(current);
        var owner = token.definition().caller();
        if (current.system() || !owner.tenantId().equals(current.tenantId()) || !owner.subject().equals(current.username())
                || owner.authVersion() != current.authVersion()) throw new SecurityException("DIMENSION_PRINCIPAL_MISMATCH");
        this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps);
        this.selections = Objects.requireNonNull(selections); this.gateway = Objects.requireNonNull(gateway);
        this.queryAuthorizer = Objects.requireNonNull(queryAuthorizer); this.artifactAuthorizer = Objects.requireNonNull(artifactAuthorizer);
        this.frozen = FrozenCampaignRun.read(token.definition());
        this.templates = FrozenDimensionChange.resolveTemplates(token.definition(), ref);
        if (templates.isEmpty()) throw new IllegalArgumentException("DIMENSION_SKILL_NOT_IN_PLAN");
        List<RunPinnedSkills.SkillPin> pins = templates.values().stream().map(Template::skillPin).distinct().toList();
        if (pins.size() != 1) throw new IllegalArgumentException("DIMENSION_METHOD_PIN_CONFLICT");
        this.methods = new RunPinnedSkills(token.definition().runId(), approvedSkillsRoot, pins, Set.of(), List.of(),
                (runId, toolName) -> false);
        this.scopes = new CampaignSelectedScope(runs, selections, artifactAuthorizer);
        this.publisher = new DimensionChangePublisher(runs, scopes, new CampaignDimensionEvidence(runs, results), artifactAuthorizer);
        this.sourceContracts = new ArtifactContractRegistry(DeclineSelectionSkill.artifactContracts());
        // Upstream outputs do not exist yet when this Run is first constructed.
    }

    public static Capability capability() {
        return capability(FrozenDimensionChange.REF);
    }

    public static Capability capability(PlanSpec.ExecutorRef ref) {
        return new Capability(ref, new Signature(inputs(ref),
                FrozenDimensionChange.OUTPUT_CONTRACT, Map.of("dimensionChanges", new Port(OUTPUT, true)),
                new Parameters(Set.of(), Map.of())), false);
    }

    private static Map<String, Port> inputs(PlanSpec.ExecutorRef ref) {
        if (FrozenDimensionChange.REF.equals(ref)) return FrozenDimensionChange.INPUTS;
        if (FrozenDimensionChange.REF_V2.equals(ref)) return FrozenDimensionChange.INPUTS_V2;
        if (FrozenDimensionChange.REF_V3.equals(ref)) return FrozenDimensionChange.INPUTS_V3;
        throw new IllegalArgumentException("DIMENSION_EXECUTOR_UNAVAILABLE");
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
        return new PersistentPlanDriver.FixedExecutor(ref, policy(), this::execute);
    }

    private StepBindings.StepPolicy policy() {
        return new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { validateBound(step, inputs); }
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                Source source = validateBound(step, inputs); Bound bound = source.bound();
                ArtifactRef scope = readyScope(bound, source.pair());
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
    private Source upstream(Template template) {
        var producer = upstreamStep(template);
        if (producer == null || producer.status() != CampaignStepStore.StepStatus.SUCCEEDED) return null;
        Source[] checked = new Source[1];
        var resolver = new StepBindings(sourceContracts, runs, token.definition().caller(), artifactAuthorizer,
                (caller, type, value) -> caller.equals(token.definition().caller())
                         && ("ScopeRef".equals(type.name()) ? Objects.equals(template.frozenScopeRef(), value)
                         : "PeriodsRef".equals(type.name()) && template.periodsRef().equals(value)), new StepBindings.StepPolicy() {
                    @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { checked[0] = validateBound(step, inputs); }
                    @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                        throw new IllegalStateException("DIMENSION_SOURCE_READER_ONLY");
                    }
                });
        try (BoundInputs inputs = resolver.resolve(template.step(), frozen.inputs(), capability(ref).signature(),
                (stepId, name) -> steps.step(token, stepId)
                        .filter(step -> step.status() == CampaignStepStore.StepStatus.SUCCEEDED)
                        .map(step -> step.outputs().get(name)))) {
            resolver.reauthorize(capability(ref).signature(), inputs);
            return Objects.requireNonNull(checked[0]);
        }
    }

    private Source validateBound(PlanSpec.Step step, BoundInputs inputs) {
        Template template = template(step); requireCurrent();
        if ((FrozenDimensionChange.REF.equals(ref) && !template.frozenScopeRef().equals(inputs.value("scope")))
                || !template.periodsRef().equals(inputs.value("periods"))
                || !template.descriptor().equals(inputs.value("definition"))
                || inputs.artifact("selectedEntities") == null || inputs.artifact("selectionEvidence") == null)
            throw new IllegalArgumentException("DIMENSION_INPUT_MISMATCH");
        var producer = upstreamStep(template);
        if (producer == null || producer.status() != CampaignStepStore.StepStatus.SUCCEEDED
                || !inputs.artifact("selectedEntities").metadata().ref().artifactId().equals(producer.outputs().get("selectedEntities"))
                || !inputs.artifact("selectionEvidence").metadata().ref().artifactId().equals(producer.outputs().get("selectionEvidence")))
            throw new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH");
        SelectionPair pair = selections.inspectPair(token.definition().caller(),
                inputs.artifact("selectedEntities").metadata().ref().artifactId(),
                inputs.artifact("selectionEvidence").metadata().ref().artifactId(), artifactAuthorizer);
        if (!pair.selectedEntities().equals(inputs.artifact("selectedEntities").metadata())
                || !pair.selectionEvidence().equals(inputs.artifact("selectionEvidence").metadata()))
            throw new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH");
        Artifact originalScope = runs.readArtifact(token.definition().caller(), pair.scopeArtifact().ref().artifactId(), artifactAuthorizer);
        Bound bound = FrozenDimensionChange.bind(token.definition(), template, pair, originalScope);
        return new Source(bound, pair, originalScope);
    }

    private CampaignStepStore.StepRecord upstreamStep(Template template) {
        var producer = steps.step(token, template.upstreamStepId()).orElse(null);
        if (producer == null) return null;
        var planned = frozen.plan().steps().stream().filter(step -> template.upstreamStepId().equals(step.stepId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH"));
        if (!template.upstreamStepId().equals(producer.spec().stepId())
                || !FrozenCampaignRun.encode(planned).equals(producer.spec().definitionJson()))
            throw new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH");
        return producer;
    }

    public boolean authorized() {
        try { requireCurrent(); for (Template template : templates.values()) upstream(template); return true; }
        catch (SecurityException | IllegalArgumentException | IllegalStateException denied) { return false; }
    }

    public Map<String, StatisticsJobResultReceiver.Target> resultTargets() {
        requireCurrent();
        Map<String, StatisticsJobResultReceiver.Target> targets = new LinkedHashMap<>();
        for (Template template : templates.values()) {
            Source source = upstream(template); if (source == null) continue;
            Bound bound = source.bound();
            ArtifactRef scope = readyScope(bound, source.pair()); if (scope == null) continue;
            var position = publisher.progress(token, bound.step().stepId(), definition(bound, scope));
            if (!position.complete()) for (BoundQuery query : queries(bound, scope, position.shardIndex()))
                targets.put(query.child().childId(), query.target());
        }
        return Map.copyOf(targets);
    }

    public void prepareRecovery() {
        requireCurrent();
        for (Template template : templates.values()) {
            var step = steps.step(token, template.step().stepId());
            if (step.isEmpty() || step.get().status() != CampaignStepStore.StepStatus.BLOCKED
                    || !"STEP_RESULT_UNKNOWN".equals(step.get().reason())) continue;
            Source source = upstream(template); if (source == null) continue;
            Bound bound = source.bound();
            var scopePreparation = scopePreparation(bound, source.pair());
            var scopeChild = runs.child(token, scopePreparation.child().childId());
            if (scopeChild.isPresent() && scopeChild.get().reason() == UnresolvedReason.LOCAL_RESULT_UNKNOWN) {
                steps.refreshLocalReplay(token, bound.step().stepId(), Map.of(scopeChild.get().spec().childId(), scopePreparation.approval()), artifactAuthorizer);
                continue;
            }
            ArtifactRef scope = readyScope(bound, source.pair()); if (scope == null) continue;
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
        Source source = validateBound(context.step(), context.inputs());
        Bound bound = source.bound();
        ArtifactRef scope = scopes.publish(context, token, source.pair().selectedEntities().ref().artifactId(),
                source.pair().selectionEvidence().ref().artifactId(), bound.periods());
        var definition = definition(bound, scope);
        return executeDimension(context, () -> publisher.progress(token, bound.step().stepId(), definition),
                shard -> queries(bound, scope, shard),
                (query, boundary) -> StatisticsJobFixedExecutor.submit(boundary, token.definition(), current, gateway,
                        query.request(), () -> authorized(bound, scope, query)),
                (position, queries) -> publisher.publishPage(context, token, definition, slot(queries.get(0)), slot(queries.get(1)),
                        position.shardIndex(), position.side(), position.pageIndex(), position.previousArtifactId()),
                head -> publisher.finish(context, token, definition, head), this::requireCurrent);
    }

    @FunctionalInterface interface SubmitQuery {
        CampaignStepExecution.ChildResult submit(BoundQuery query, CampaignStepExecution.IoBoundary boundary) throws Exception;
    }
    @FunctionalInterface interface PublishPage {
        void publish(DimensionChangePublisher.Position position, List<BoundQuery> queries) throws Exception;
    }
    @FunctionalInterface interface FinishDimension { ArtifactRef finish(String head) throws Exception; }

    /** The existing bounded page algorithm, shared by actual fixed Steps and actual CALL owners. */
    static PersistentPlanDriver.Result executeDimension(CapabilityExecution context,
            java.util.function.Supplier<DimensionChangePublisher.Position> progress,
            java.util.function.IntFunction<List<BoundQuery>> queriesForShard, SubmitQuery submit,
            PublishPage publish, FinishDimension finish, Runnable current) throws Exception {
        for (;;) {
            context.requireCurrent(); current.run();
            var position = progress.get();
            if (position.complete()) return PersistentPlanDriver.Result.succeeded(Map.of("dimensionChanges",
                    finish.finish(position.previousArtifactId()).artifactId()));
            List<BoundQuery> queries = queriesForShard.apply(position.shardIndex());
            if (queries.size() != 2) throw new IllegalArgumentException("DIMENSION_PERIODS_MISMATCH");
            List<CampaignStatisticsCompositeCall.Request> requests = queries.stream()
                    .map(query -> new CampaignStatisticsCompositeCall.Request(
                            query.child().childId(), query.child().actionId(), query.child().mode(),
                            query.child().requestId(), query.child().wire(),
                            boundary -> submit.submit(query, boundary)))
                    .toList();
            CampaignStatisticsCompositeCall.Outcome outcome = new CampaignStatisticsCompositeCall().execute(
                    requests, request -> context.child(request.spec(), request.call()));
            boolean pending = !outcome.waitingChildIds().isEmpty(), waiting = pending, capacity = false;
            for (CampaignStatisticsCompositeCall.Failure failure : outcome.failures()) {
                if ("SUBMISSION_UNRESOLVED".equals(failure.code())) {
                    pending = true;
                } else if ("QUERY_CAPACITY_EXHAUSTED".equals(failure.code())) {
                    pending = true; capacity = true;
                } else {
                    throw new IllegalArgumentException(failure.code());
                }
            }
            for (ChildRecord child : outcome.children()) {
                if (child.state() == ChildState.READY) {
                    BoundQuery query = queries.stream().filter(candidate -> candidate.child().equals(child.spec()))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("DIMENSION_CHILD_CHANGED"));
                    if (!query.target().artifactId().equals(child.artifactId()))
                        throw new IllegalArgumentException("DIMENSION_RESULT_BINDING_MISMATCH");
                } else {
                    pending = true;
                    waiting |= child.state() == ChildState.WAITING;
                    capacity |= child.state() == ChildState.PREPARED && child.reason() == UnresolvedReason.QUERY_CAPACITY_EXHAUSTED;
                }
            }
            if (pending) return waiting ? PersistentPlanDriver.Result.waiting()
                    : PersistentPlanDriver.Result.blocked(capacity ? "REMOTE_CAPACITY" : "STEP_RESULT_UNKNOWN");
            publish.publish(position, queries);
        }
    }

    private boolean authorized(Bound bound, ArtifactRef scope, BoundQuery query) {
        requireCurrent(); Source source = upstream(template(bound.step()));
        if (source == null || !bound.equals(source.bound()) || !scope.equals(readyScope(bound, source.pair()))) return false;
        var requested = com.jupiter.shortlink.contract.FrozenQueryScope.fromMap((Map<?, ?>) query.request().get("scope"));
        if (!requested.equals(scopes.shard(token.definition().caller(), scope.artifactId(), requested.shardIndex()))) return false;
        if (FrozenDimensionChange.REF_V2.equals(ref)
                && !query.equals(FrozenDimensionChange.query(token.definition(), template(bound.step()), source.pair(), source.originalScope(), requested, 0))
                && !query.equals(FrozenDimensionChange.query(token.definition(), template(bound.step()), source.pair(), source.originalScope(), requested, 1))) return false;
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
        if (FrozenDimensionChange.REF_V2.equals(ref)) {
            Template template = template(bound.step());
            Source source = upstream(template);
            if (source == null || !bound.equals(source.bound()) || !scope.equals(readyScope(bound, source.pair())))
                throw new IllegalArgumentException("DIMENSION_SOURCE_MISMATCH");
            return List.of(FrozenDimensionChange.query(token.definition(), template, source.pair(), source.originalScope(), shard, 0),
                    FrozenDimensionChange.query(token.definition(), template, source.pair(), source.originalScope(), shard, 1));
        }
        return List.of(FrozenDimensionChange.query(token.definition(), bound, shard, 0), FrozenDimensionChange.query(token.definition(), bound, shard, 1));
    }
    private boolean allReady(List<BoundQuery> queries) {
        return queries.stream().allMatch(query -> runs.child(token, query.child().childId())
                .filter(child -> child.state() == ChildState.READY && query.target().artifactId().equals(child.artifactId())).isPresent());
    }
    private CampaignParentCoverage.Slot slot(BoundQuery query) { return new CampaignParentCoverage.Slot(token, query.child().childId(), query.target().artifactId()); }
    private Template template(PlanSpec.Step step) {
        Template template = templates.get(step.stepId());
        if (template == null || !template.step().equals(step)) throw new IllegalArgumentException("DIMENSION_STEP_CHANGED");
        return template;
    }
    private void requireCurrent() {
        methods.verifyPins();
        if (runs.loadRun(token.definition().caller(), token.definition().runId())
                .filter(run -> run.status() == RunStatus.ACTIVE && run.token().equals(token)).isEmpty())
            throw new SecurityException("DIMENSION_RUN_FENCED");
    }
    private record Source(Bound bound, SelectionPair pair, Artifact originalScope) {}
}
