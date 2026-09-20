package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore.SelectionPair;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore.InvocationRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDimensionChange.BoundQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDimensionChange.CallBound;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A real dimension Skill CALL over the source MODEL's sealed selection, without an outer Step dependency. */
public final class DimensionChangeCall {
    private static final PlanSpec.ExecutorRef REF = FrozenDimensionChange.REF_V3;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JdbcTemplate jdbc;
    private final RunDefinition definition;
    private final PlanSpec.Step step;
    private final FrozenCampaignRun frozen;
    private final AgentPrincipal current;
    private final Path approvedSkillsRoot;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignExplorationCallStore calls;
    private final CampaignSkillInvocationStore skills;
    private final CampaignDeclineSelectionStore selections;
    private final ShortLinkBusinessGateway gateway;
    private final CapabilityCatalog catalog;
    private final ModelInvocationRegistry models;
    private final ArtifactAuthorizer authorizer;
    private final StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer;
    private final StepBindings bindings;
    private final CampaignSelectedScope scopes;
    private final DimensionChangePublisher publisher;

    private record Arguments(Map<String, PlanBinding> bindings, Map<String, Object> parameters) {}
    private record Source(CallRecord call, ModelInvocationRegistry.Approval model, BoundInputs inputs,
                          CallBound bound, SelectionPair pair, Artifact originalScope, RunPinnedSkills methods) implements AutoCloseable {
        @Override public void close() { inputs.close(); }
    }
    private record IndexedChild(String callId, String childId) {}

    public DimensionChangeCall(JdbcTemplate jdbc, RunDefinition definition, String stepId, AgentPrincipal current,
            Path approvedSkillsRoot, CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            CampaignSkillInvocationStore skills, CampaignDeclineSelectionStore selections, CampaignStatisticsResultStore results,
            ShortLinkBusinessGateway gateway, CapabilityCatalog catalog, ArtifactContractRegistry contracts, ModelInvocationRegistry models,
            StepBindings.CurrentInputAuthorizer inputAuthorizer, ArtifactAuthorizer authorizer,
            StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer) {
        this.jdbc = Objects.requireNonNull(jdbc); this.definition = Objects.requireNonNull(definition);
        this.current = Objects.requireNonNull(current); this.approvedSkillsRoot = Objects.requireNonNull(approvedSkillsRoot);
        this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps);
        this.calls = Objects.requireNonNull(calls); this.skills = Objects.requireNonNull(skills);
        this.selections = Objects.requireNonNull(selections); this.gateway = Objects.requireNonNull(gateway);
        this.catalog = Objects.requireNonNull(catalog); this.models = Objects.requireNonNull(models);
        this.authorizer = Objects.requireNonNull(authorizer); this.queryAuthorizer = Objects.requireNonNull(queryAuthorizer);
        frozen = FrozenCampaignRun.read(definition);
        var matching = frozen.plan().steps().stream().filter(value -> Objects.equals(stepId, value.stepId())).toList();
        require(matching.size() == 1, "DIMENSION_EXPLORATION_STEP_INVALID");
        step = matching.get(0); requirePrincipal(); requireCapability();
        bindings = new StepBindings(contracts, runs, definition.caller(), authorizer, inputAuthorizer, new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step ignored, BoundInputs inputs) { throw failure("DIMENSION_LOCAL_BINDING_REQUIRED"); }
            @Override public void validateOutputs(PlanSpec.Step ignored, BoundInputs inputs,
                    Map<String, ArtifactContractRegistry.BoundArtifact> outputs) { throw failure("DIMENSION_LOCAL_BINDING_REQUIRED"); }
        });
        scopes = new CampaignSelectedScope(runs, selections, authorizer);
        publisher = new DimensionChangePublisher(runs, scopes, new CampaignDimensionEvidence(runs, results), authorizer);
    }

    /** The actual callback owner must release its CALL permit in finally. */
    public InvocationRecord execute(CallPermit permit) throws Exception { return execute(permit, null, () -> {}); }

    public InvocationRecord execute(CallPermit permit, String suppliedArguments, Runnable nativeGuard) throws Exception {
        Objects.requireNonNull(nativeGuard).run();
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "DIMENSION_CALL_REQUIRES_COMMITTED_PREPARATION");
        requireLive(permit);
        RunToken token = permit.step().runToken();
        try (Source source = resolve(token, permit.callId())) {
            if (suppliedArguments != null) require(source.call().spec().arguments().equals(new ModelInvocationRegistry.ToolCall(
                    source.call().spec().toolCallId(), REF.name(), suppliedArguments).arguments()), "DIMENSION_CALL_ARGUMENTS_CHANGED");
            var preparedScope = scopePreparation(token, source);
            var expectedScope = preparedScope.approval().invocation().outputs().get(CampaignSelectedScope.OUTPUT);
            var calculation = calculation(source.bound(), expectedScope.artifactId());
            skills.prepare(permit, publisher.completionSpec(permit, calculation, expectedScope.scopeRef()), source.model(), authorizer);
            try (var context = new CampaignCallExecution(permit, runs, steps, calls, () -> {
                nativeGuard.run(); return authorized(token, source, permit);
            })) {
                ArtifactRef scope = scopes.publish(context, permit, source.bound().selectedArtifactId(),
                        source.bound().evidenceArtifactId(), source.bound().periods());
                require(expectedScope.artifactId().equals(scope.artifactId()) && expectedScope.scopeRef().equals(scope.scopeRef())
                        && scope.equals(readyScope(token, source)), "DIMENSION_SCOPE_CHANGED");
                var result = DimensionChangeSkill.executeDimension(context,
                        () -> publisher.progress(token, source.call().spec(), calculation),
                        shard -> queries(token, source, scope, shard),
                        (query, boundary) -> StatisticsJobFixedExecutor.submit(boundary, definition, current, gateway, query.request(), () -> {
                            requireLive(permit); reauthorizeInputs(token, source); return queryAllowed(token, source, scope, query);
                        }),
                        (position, queries) -> publisher.publishPage(context, permit, calculation,
                                slot(token, queries.get(0)), slot(token, queries.get(1)), position.shardIndex(), position.side(),
                                position.pageIndex(), position.previousArtifactId()),
                        head -> publisher.finish(context, permit, calculation, head), () -> reauthorizeInputs(token, source));
                requireLive(permit); reauthorizeInputs(token, source);
                if (result.status() == CampaignStepStore.StepStatus.WAITING
                        || (result.status() == CampaignStepStore.StepStatus.BLOCKED && "REMOTE_CAPACITY".equals(result.reason()))) {
                    Set<String> dependencies = new HashSet<>(jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? "
                                    + "AND revision=? AND action_id=? AND (child_state='WAITING' OR "
                                    + "(child_state='PREPARED' AND unresolved_reason='QUERY_CAPACITY_EXHAUSTED')) ORDER BY child_id",
                            (rs, row) -> rs.getString(1), definition.runId(), definition.revision(), permit.actionId()));
                    return skills.awaitContinuation(permit, dependencies);
                }
                require(result.status() == CampaignStepStore.StepStatus.SUCCEEDED,
                        result.reason() == null ? "DIMENSION_CALL_UNRESOLVED" : result.reason());
                var position = publisher.progress(token, source.call().spec(), calculation);
                require(position.complete(), "DIMENSION_PAGES_INCOMPLETE");
                var finalOutput = publisher.prepareFinal(permit, calculation, position.previousArtifactId());
                var output = runs.localOutputs(token, finalOutput.child().childId(), authorizer).get("dimensionChanges");
                require(output != null && runs.child(token, finalOutput.child().childId()).orElseThrow().spec().equals(finalOutput.child())
                        && result.outputs().equals(Map.of("dimensionChanges", output.artifactId())), "DIMENSION_OUTPUT_MISMATCH");
                return skills.completeInvocation(permit, authorizer);
            }
        }
    }

    public CallPermit beginContinuation(StepPermit step, String callId, long invocationVersion) {
        require(this.step.stepId().equals(step.stepId()), "DIMENSION_STEP_CHANGED");
        try (Source source = resolve(step.runToken(), callId)) {
            return skills.beginContinuation(step, callId, invocationVersion, source.model(), authorizer);
        }
    }

    public InvocationRecord continueInvocation(StepPermit step, String callId, long invocationVersion) throws Exception {
        CallPermit permit = beginContinuation(step, callId, invocationVersion);
        try { return execute(permit); }
        finally { calls.callbackExited(permit); }
    }

    public Map<String, StatisticsJobResultReceiver.Target> resultTargets(RunToken token) {
        requireToken(token);
        Map<String, StatisticsJobResultReceiver.Target> result = new LinkedHashMap<>();
        for (IndexedChild indexed : indexedChildren(null)) try (Source source = resolve(token, indexed.callId())) {
            ArtifactRef scope = readyScope(token, source);
            BoundQuery query = storedQuery(token, source, scope, indexed.childId());
            if (!queryAllowed(token, source, scope, query)) throw new SecurityException("DIMENSION_QUERY_ACCESS_DENIED");
            require(result.putIfAbsent(indexed.childId(), query.target()) == null, "DIMENSION_TARGET_DUPLICATE");
        }
        return Map.copyOf(result);
    }

    /** A receiver uses its current child reconciliation permit, never the retired parent CALL permit. */
    public boolean reauthorize(RunToken token, String childId) {
        try {
            requireToken(token);
            var indexed = indexedChildren(childId); require(indexed.size() == 1, "DIMENSION_CHILD_NOT_OWNED");
            try (Source source = resolve(token, indexed.get(0).callId())) {
                ArtifactRef scope = readyScope(token, source);
                return queryAllowed(token, source, scope, storedQuery(token, source, scope, childId));
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException denied) { return false; }
    }

    private Source resolve(RunToken token, String callId) {
        requireToken(token); requireCapability();
        CallRecord call = calls.call(token, callId).orElseThrow(() -> failure("DIMENSION_CALL_REQUIRED"));
        require(step.stepId().equals(call.spec().stepId()) && REF.equals(call.spec().executor()), "DIMENSION_CALL_CHANGED");
        var identity = CampaignExplorationCallStore.identity(definition, step.stepId(), call.spec().modelChildId(), call.spec().toolCallId());
        require(identity.callId().equals(callId) && identity.actionId().equals(call.spec().actionId()), "DIMENSION_CALL_CHANGED");
        ChildRecord model = runs.child(token, call.spec().modelChildId()).orElseThrow();
        require(model.state() == ChildState.READY && model.spec().mode() == ChildMode.MODEL
                && model.spec().modelInvocation() != null, "DIMENSION_MODEL_SOURCE_REQUIRED");
        var expected = ModelInvocationRegistry.identity(definition, step.stepId(), model.spec().modelInvocation().turnIndex());
        require(expected.childId().equals(model.spec().childId()), "DIMENSION_MODEL_SOURCE_CHANGED");
        var approval = models.approve(model.spec().modelInvocation());
        requireSourceResponse(token, call, approval);
        runs.prepareAction(token, new ActionSpec(call.spec().actionId(), step.stepId(), REF.kind().name(), REF.name(), REF.version(),
                CampaignExplorationCallStore.encode(call.spec())));
        Arguments arguments = arguments(call.spec().arguments());
        BoundInputs inputs = bindings.resolveLocal(step, frozen.inputs(), DimensionChangeSkill.capability(REF).signature(),
                arguments.bindings(), arguments.parameters(), approval.invocation().inputs());
        try {
            SelectionPair pair = selectionPair(inputs);
            Artifact original = runs.readArtifact(definition.caller(), pair.scopeArtifact().ref().artifactId(), authorizer);
            require(pair.scopeArtifact().equals(original.metadata()), "DIMENSION_SOURCE_MISMATCH");
            CallBound bound = FrozenDimensionChange.prepareCall(definition, call.spec(), inputs, pair, original);
            var methods = new RunPinnedSkills(definition.runId(), approvedSkillsRoot, List.of(bound.skillPin()), Set.of(), List.of(),
                    (run, tool) -> false);
            Source source = new Source(call, approval, inputs, bound, pair, original, methods);
            reauthorizeInputs(token, source); return source;
        } catch (RuntimeException | Error invalid) { inputs.close(); throw invalid; }
    }

    private SelectionPair selectionPair(BoundInputs inputs) {
        var selected = inputs.artifact("selectedEntities"); var evidence = inputs.artifact("selectionEvidence");
        require(selected != null && evidence != null, "DIMENSION_SOURCE_MISMATCH");
        SelectionPair pair = selections.inspectPair(definition.caller(), selected.metadata().ref().artifactId(),
                evidence.metadata().ref().artifactId(), authorizer);
        require(pair.selectedEntities().equals(selected.metadata()) && pair.selectionEvidence().equals(evidence.metadata()),
                "DIMENSION_SOURCE_MISMATCH");
        return pair;
    }

    private void requireSourceResponse(RunToken token, CallRecord call, ModelInvocationRegistry.Approval approval) {
        var response = runs.readModelResponse(token, call.spec().modelChildId(), approval, authorizer);
        require(CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)).equals(call.spec().responseHash())
                && response.toolCalls().size() == 1, "DIMENSION_MODEL_SOURCE_CHANGED");
        var tool = response.toolCalls().get(0);
        require(tool.id().equals(call.spec().toolCallId()) && tool.name().equals(REF.name())
                && tool.arguments().equals(call.spec().arguments()), "DIMENSION_MODEL_SOURCE_CHANGED");
    }

    private void reauthorizeInputs(RunToken token, Source source) {
        requireToken(token); requireCapability(); source.methods().verifyPins();
        bindings.reauthorize(DimensionChangeSkill.capability(REF).signature(), source.inputs());
        requireSourceResponse(token, source.call(), source.model());
        SelectionPair actual = selectionPair(source.inputs());
        require(source.pair().equals(actual), "DIMENSION_SOURCE_MISMATCH");
        Artifact original = runs.readArtifact(definition.caller(), actual.scopeArtifact().ref().artifactId(), authorizer);
        require(actual.scopeArtifact().equals(original.metadata()) && source.originalScope().equals(original)
                && source.bound().equals(FrozenDimensionChange.prepareCall(definition, source.call().spec(), source.inputs(), actual, original)),
                "DIMENSION_SOURCE_MISMATCH");
    }

    private CampaignSelectedScope.Prepared scopePreparation(RunToken token, Source source) {
        return scopes.prepare(token, source.call().spec(), source.bound().selectedArtifactId(),
                source.bound().evidenceArtifactId(), source.bound().periods());
    }

    private ArtifactRef readyScope(RunToken token, Source source) {
        var prepared = scopePreparation(token, source);
        var child = runs.child(token, prepared.child().childId()).orElseThrow(() -> failure("DIMENSION_SCOPE_NOT_READY"));
        require(child.state() == ChildState.READY && child.spec().equals(prepared.child()), "DIMENSION_SCOPE_CHANGED");
        var scope = runs.localOutputs(token, child.spec().childId(), authorizer).get(CampaignSelectedScope.OUTPUT);
        require(scope != null && prepared.approval().invocation().outputs().get(CampaignSelectedScope.OUTPUT).artifactId().equals(scope.artifactId())
                && scope.equals(scopes.inspect(definition.caller(), scope.artifactId()).metadata().ref()), "DIMENSION_SCOPE_CHANGED");
        return scope;
    }

    private static DimensionChangePublisher.Definition calculation(CallBound bound, String scopeArtifactId) {
        return new DimensionChangePublisher.Definition(bound.collectionId(), scopeArtifactId, bound.periodsRef(), bound.dimensions(), bound.filters());
    }

    private List<BoundQuery> queries(RunToken token, Source source, ArtifactRef scope, int shardIndex) {
        reauthorizeInputs(token, source); require(scope.equals(readyScope(token, source)), "DIMENSION_SCOPE_CHANGED");
        FrozenQueryScope shard = scopes.shard(definition.caller(), scope.artifactId(), shardIndex);
        return List.of(FrozenDimensionChange.queryCall(definition, source.bound(), shard, 0),
                FrozenDimensionChange.queryCall(definition, source.bound(), shard, 1));
    }

    private BoundQuery storedQuery(RunToken token, Source source, ArtifactRef scope, String childId) {
        ChildRecord child = runs.child(token, childId).orElseThrow();
        require(child.spec().mode() == ChildMode.ASYNC && child.spec().wire() != null, "DIMENSION_CHILD_CHANGED");
        JsonNode value = object(child.spec().wire().bodyJson()).path("scope"); require(value.isObject(), "DIMENSION_CHILD_CHANGED");
        FrozenQueryScope recorded = FrozenQueryScope.fromMap(JSON.convertValue(value, Map.class));
        FrozenQueryScope actual = scopes.shard(definition.caller(), scope.artifactId(), recorded.shardIndex());
        require(recorded.equals(actual), "DIMENSION_SCOPE_CHANGED");
        return List.of(FrozenDimensionChange.queryCall(definition, source.bound(), actual, 0),
                FrozenDimensionChange.queryCall(definition, source.bound(), actual, 1)).stream()
                .filter(query -> query.child().equals(child.spec())).findFirst().orElseThrow(() -> failure("DIMENSION_CHILD_CHANGED"));
    }

    private boolean queryAllowed(RunToken token, Source source, ArtifactRef scope, BoundQuery query) {
        require(scope.equals(readyScope(token, source)), "DIMENSION_SCOPE_CHANGED");
        var requested = FrozenQueryScope.fromMap((Map<?, ?>) query.request().get("scope"));
        require(requested.equals(scopes.shard(definition.caller(), scope.artifactId(), requested.shardIndex())), "DIMENSION_SCOPE_CHANGED");
        require(query.equals(FrozenDimensionChange.queryCall(definition, source.bound(), requested, 0))
                || query.equals(FrozenDimensionChange.queryCall(definition, source.bound(), requested, 1)), "DIMENSION_QUERY_CHANGED");
        return queryAuthorizer.mayUse(current, scope.scopeRef(), query.periodsRef(), query.request());
    }

    private static CampaignParentCoverage.Slot slot(RunToken token, BoundQuery query) {
        return new CampaignParentCoverage.Slot(token, query.child().childId(), query.target().artifactId());
    }

    private List<IndexedChild> indexedChildren(String childId) {
        List<Object> args = new ArrayList<>(List.of(definition.runId(), definition.revision(), step.stepId(), REF.kind().name(), REF.name(), REF.version()));
        if (childId != null) args.add(childId);
        return jdbc.query("SELECT c.call_id,ch.child_id FROM campaign_exploration_call c "
                        + "JOIN campaign_action_ledger a ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "JOIN campaign_child_ledger ch ON ch.run_id=c.run_id AND ch.revision=c.revision AND ch.action_id=c.action_id "
                        + "WHERE c.run_id=? AND c.revision=? AND c.step_id=? AND a.action_kind='CAPABILITY' AND a.executor_kind=? "
                        + "AND a.executor_name=? AND a.executor_version=? AND ch.child_mode='ASYNC'" + (childId == null ? "" : " AND ch.child_id=?"),
                (rs, row) -> new IndexedChild(rs.getString(1), rs.getString(2)), args.toArray());
    }

    private void requireToken(RunToken token) {
        requirePrincipal();
        if (token == null || !definition.equals(token.definition())) throw new SecurityException("DIMENSION_RUN_CHANGED");
        RunRecord actual = runs.loadRun(definition.caller(), definition.runId()).orElseThrow();
        if (actual.status() != RunStatus.ACTIVE || !actual.token().equals(token)) throw new SecurityException("DIMENSION_RUN_FENCED");
    }
    private void requireLive(CallPermit permit) {
        if (permit == null || permit.step() == null || !step.stepId().equals(permit.step().stepId())) throw new SecurityException("DIMENSION_CALL_FENCED");
        requireToken(permit.step().runToken());
        if (!steps.mayExecute(permit.step()) || !calls.mayExecute(permit)) throw new SecurityException("DIMENSION_CALL_FENCED");
    }
    private boolean authorized(RunToken token, Source source, CallPermit permit) {
        try { requireLive(permit); reauthorizeInputs(token, source); return true; }
        catch (IllegalArgumentException | IllegalStateException | SecurityException denied) { return false; }
    }
    private void requirePrincipal() {
        var owner = definition.caller();
        if (current.system() || !owner.tenantId().equals(current.tenantId()) || !owner.subject().equals(current.username())
                || owner.authVersion() != current.authVersion()) throw new SecurityException("DIMENSION_PRINCIPAL_MISMATCH");
    }
    private void requireCapability() {
        require(step.executionMode() == PlanSpec.ExecutionMode.REACT && step.executor() == null && step.explorationPolicy() != null
                && step.explorationPolicy().allowedExecutors().contains(REF), "DIMENSION_EXPLORATION_NOT_ALLOWED");
        var exploration = step.explorationPolicy();
        var policy = catalog.policy(exploration.policyRef(), exploration.policyVersion()).orElseThrow();
        require(policy.policyRef().equals(exploration.policyRef()) && policy.policyVersion().equals(exploration.policyVersion())
                && policy.allowedExecutors().containsAll(exploration.allowedExecutors())
                && policy.completionCriteria().equals(exploration.completionCriteria())
                && Objects.equals(policy.terminationPolicyRef(), exploration.terminationPolicyRef()), "DIMENSION_POLICY_CHANGED");
        for (PlanSpec.ExecutorRef ref : exploration.allowedExecutors()) {
            var capability = catalog.capability(ref).orElseThrow();
            require(ref.equals(capability.executor()) && !capability.startsExploration(), "DIMENSION_EXPLORATION_RECURSION_REJECTED");
        }
        require(DimensionChangeSkill.capability(REF).equals(catalog.capability(REF).orElseThrow()), "DIMENSION_CAPABILITY_CHANGED");
    }

    private static Arguments arguments(String encoded) {
        JsonNode root = object(encoded); fields(root, Set.of("inputBindings", "parameters"));
        require(root.path("inputBindings").isObject() && root.path("parameters").isObject(), "DIMENSION_ARGUMENTS_INVALID");
        fields(root.get("parameters"), Set.of());
        Map<String, PlanBinding> inputs = new LinkedHashMap<>();
        root.get("inputBindings").fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue(); fields(value, Set.of("source", "input", "artifactId", "stepId", "output"));
            require(value.path("source").isTextual() && !value.hasNonNull("stepId") && !value.hasNonNull("output"), "DIMENSION_BINDING_INVALID");
            PlanBinding binding;
            if ("INPUT".equals(value.get("source").textValue()) && value.path("input").isTextual() && !value.hasNonNull("artifactId"))
                binding = PlanBinding.input(value.get("input").textValue());
            else if ("ARTIFACT".equals(value.get("source").textValue()) && value.path("artifactId").isTextual() && !value.hasNonNull("input"))
                binding = PlanBinding.artifact(value.get("artifactId").textValue());
            else throw failure("DIMENSION_BINDING_INVALID");
            inputs.put(entry.getKey(), binding);
        });
        return new Arguments(Map.copyOf(inputs), Map.of());
    }
    private static JsonNode object(String encoded) {
        try { JsonNode value = JSON.readTree(encoded); require(value != null && value.isObject(), "DIMENSION_ARGUMENTS_INVALID"); return value; }
        catch (JsonProcessingException invalid) { throw failure("DIMENSION_ARGUMENTS_INVALID"); }
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        require(value != null && value.isObject(), "DIMENSION_ARGUMENTS_INVALID");
        value.fieldNames().forEachRemaining(name -> require(allowed.contains(name), "DIMENSION_ARGUMENTS_INVALID"));
    }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalArgumentException failure(String code) { return new IllegalArgumentException(code); }
}
