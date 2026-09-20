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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore.InvocationRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDeclineSelection.CallBound;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDeclineSelection.BoundQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual, non-exploring Skill callback. No model loop, native observation or production registration. */
public final class DeclineSelectionCall {
    private static final PlanSpec.ExecutorRef REF = FrozenDeclineSelection.REF;
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
    private final CampaignScopeStore scopes;
    private final CampaignDeclineSelectionStore selections;
    private final ShortLinkBusinessGateway gateway;
    private final CapabilityCatalog catalog;
    private final ModelInvocationRegistry models;
    private final ArtifactAuthorizer authorizer;
    private final StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer;
    private final StepBindings bindings;
    private final DeclineSelectionPublisher publisher;

    private record Arguments(Map<String, PlanBinding> bindings, Map<String, Object> parameters) {}
    private record Source(CallRecord call, ModelInvocationRegistry.Approval model, BoundInputs inputs,
                          CallBound bound, RunPinnedSkills methods) implements AutoCloseable {
        @Override public void close() { inputs.close(); }
    }
    private record IndexedChild(String callId, String childId) {}

    public DeclineSelectionCall(JdbcTemplate jdbc, RunDefinition definition, String stepId, AgentPrincipal current,
            Path approvedSkillsRoot, CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            CampaignSkillInvocationStore skills, CampaignScopeStore scopes, CampaignStatisticsResultStore results,
            CampaignDeclineSelectionStore selections, ShortLinkBusinessGateway gateway, CapabilityCatalog catalog,
            ArtifactContractRegistry contracts, ModelInvocationRegistry models,
            StepBindings.CurrentInputAuthorizer inputAuthorizer, ArtifactAuthorizer authorizer,
            StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer) {
        this.jdbc = Objects.requireNonNull(jdbc); this.definition = Objects.requireNonNull(definition);
        this.current = Objects.requireNonNull(current); this.approvedSkillsRoot = Objects.requireNonNull(approvedSkillsRoot);
        this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps);
        this.calls = Objects.requireNonNull(calls); this.skills = Objects.requireNonNull(skills);
        this.scopes = Objects.requireNonNull(scopes); this.selections = Objects.requireNonNull(selections);
        this.gateway = Objects.requireNonNull(gateway); this.catalog = Objects.requireNonNull(catalog);
        this.models = Objects.requireNonNull(models); this.authorizer = Objects.requireNonNull(authorizer);
        this.queryAuthorizer = Objects.requireNonNull(queryAuthorizer);
        frozen = FrozenCampaignRun.read(definition);
        var matching = frozen.plan().steps().stream().filter(item -> Objects.equals(stepId, item.stepId())).toList();
        require(matching.size() == 1, "DECLINE_EXPLORATION_STEP_INVALID");
        step = matching.get(0); requirePrincipal(); requireCapability();
        bindings = new StepBindings(contracts, runs, definition.caller(), authorizer, inputAuthorizer, new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step ignored, BoundInputs inputs) { throw failure("DECLINE_LOCAL_BINDING_REQUIRED"); }
            @Override public void validateOutputs(PlanSpec.Step ignored, BoundInputs inputs,
                    Map<String, ArtifactContractRegistry.BoundArtifact> outputs) { throw failure("DECLINE_LOCAL_BINDING_REQUIRED"); }
        });
        publisher = new DeclineSelectionPublisher(runs,
                new CampaignObservedLinkComparison(new CampaignParentCoverage(runs, scopes, results)), selections, authorizer);
    }

    /** The real callback owner must call calls.callbackExited in its actual finally block. */
    public InvocationRecord execute(CallPermit permit) throws Exception {
        return execute(permit, null, () -> {});
    }

    /** Native cancellation adds a transient per-operation guard; persisted CALL authority is still mandatory. */
    public InvocationRecord execute(CallPermit permit, String suppliedArguments, Runnable nativeGuard) throws Exception {
        Objects.requireNonNull(nativeGuard).run();
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "DECLINE_CALL_REQUIRES_COMMITTED_PREPARATION");
        requireLive(permit);
        RunToken token = permit.step().runToken();
        try (Source source = resolve(token, permit.callId())) {
            if (suppliedArguments != null) require(source.call().spec().arguments().equals(new ModelInvocationRegistry.ToolCall(
                    source.call().spec().toolCallId(), REF.name(), suppliedArguments).arguments()), "DECLINE_CALL_ARGUMENTS_CHANGED");
            var selection = selectionDefinition(token, source);
            skills.prepare(permit, publisher.completionSpec(permit, selection), source.model(), authorizer);
            var receipt = selections.loadReceipt(token, selection.collectionId(), authorizer).orElse(null);
            try (var context = new CampaignCallExecution(permit, runs, steps, calls, () -> {
                nativeGuard.run(); return authorized(token, source, permit);
            })) {
                var result = DeclineSelectionSkill.executeSelection(context, selection, receipt,
                        shard -> queries(token, source, selection, shard),
                        (query, boundary) -> StatisticsJobFixedExecutor.submit(boundary, definition, current,
                                gateway, query.request(), () -> {
                                    requireLive(permit); reauthorizeInputs(token, source);
                                    return queryAllowed(source, query);
                                }),
                        (shard, queries, previous) -> publisher.publishShard(context, permit, selection, source.bound().periods(),
                                (period, ignored) -> new CampaignParentCoverage.Slot(token, queries.get(period).child().childId(),
                                        queries.get(period).target().artifactId()), shard, previous),
                        head -> publisher.finish(context, permit, head), () -> reauthorizeInputs(token, source));
                requireLive(permit); reauthorizeInputs(token, source);
                if (result.status() == CampaignStepStore.StepStatus.WAITING
                        || (result.status() == CampaignStepStore.StepStatus.BLOCKED && "REMOTE_CAPACITY".equals(result.reason()))) {
                    // These are dependency candidates only. The Skill store checks every owned
                    // child and its durable rejection proof before allowing a resumable return.
                    Set<String> dependencies = new HashSet<>(jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? "
                                    + "AND revision=? AND action_id=? AND (child_state='WAITING' OR "
                                    + "(child_state='PREPARED' AND unresolved_reason='QUERY_CAPACITY_EXHAUSTED')) ORDER BY child_id",
                            (rs, row) -> rs.getString(1), definition.runId(), definition.revision(), permit.actionId()));
                    return skills.awaitContinuation(permit, dependencies);
                }
                require(result.status() == CampaignStepStore.StepStatus.SUCCEEDED, result.reason() == null ? "DECLINE_CALL_UNRESOLVED" : result.reason());
                var sealed = selections.loadReceipt(token, selection.collectionId(), authorizer).orElseThrow();
                require(sealed.sealed() && result.outputs().equals(Map.of("selectedEntities", sealed.selectedArtifactId(),
                        "selectionEvidence", sealed.evidenceArtifactId())), "DECLINE_SELECTION_NOT_SEALED");
                return skills.completeInvocation(permit, authorizer);
            }
        }
    }

    /** Current server binding and authority are checked before the stored wait may admit a new attempt. */
    public CallPermit beginContinuation(StepPermit step, String callId, long invocationVersion) {
        require(this.step.stepId().equals(step.stepId()), "DECLINE_STEP_CHANGED");
        try (Source source = resolve(step.runToken(), callId)) {
            return skills.beginContinuation(step, callId, invocationVersion, source.model(), authorizer);
        }
    }

    /** Server continuation owns the actual callback through every child operation and its finally. */
    public InvocationRecord continueInvocation(StepPermit step, String callId, long invocationVersion) throws Exception {
        CallPermit permit = beginContinuation(step, callId, invocationVersion);
        try { return execute(permit); }
        finally { calls.callbackExited(permit); }
    }

    public Map<String, StatisticsJobResultReceiver.Target> resultTargets(RunToken token) {
        requireToken(token);
        Map<String, StatisticsJobResultReceiver.Target> result = new LinkedHashMap<>();
        for (IndexedChild indexed : indexedChildren(null)) {
            try (Source source = resolve(token, indexed.callId())) {
                BoundQuery query = storedQuery(token, source, indexed.childId());
                if (!queryAllowed(source, query)) throw new SecurityException("DECLINE_QUERY_ACCESS_DENIED");
                require(result.putIfAbsent(indexed.childId(), query.target()) == null, "DECLINE_TARGET_DUPLICATE");
            }
        }
        return Map.copyOf(result);
    }

    /** Result receipt uses its own child permit, never the expired original CALL callback. */
    public boolean reauthorize(RunToken token, String childId) {
        try {
            requireToken(token);
            List<IndexedChild> indexed = indexedChildren(childId);
            require(indexed.size() == 1, "DECLINE_CHILD_NOT_OWNED");
            try (Source source = resolve(token, indexed.get(0).callId())) {
                return queryAllowed(source, storedQuery(token, source, childId));
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException denied) { return false; }
    }

    private Source resolve(RunToken token, String callId) {
        requireToken(token); requireCapability();
        CallRecord call = calls.call(token, callId).orElseThrow(() -> failure("DECLINE_CALL_REQUIRED"));
        require(step.stepId().equals(call.spec().stepId()) && REF.equals(call.spec().executor()), "DECLINE_CALL_CHANGED");
        var identity = CampaignExplorationCallStore.identity(definition, step.stepId(), call.spec().modelChildId(), call.spec().toolCallId());
        require(identity.callId().equals(callId) && identity.actionId().equals(call.spec().actionId()), "DECLINE_CALL_CHANGED");
        ChildRecord model = runs.child(token, call.spec().modelChildId()).orElseThrow();
        require(model.state() == ChildState.READY && model.spec().mode() == ChildMode.MODEL
                && model.spec().modelInvocation() != null, "DECLINE_MODEL_SOURCE_REQUIRED");
        var expected = ModelInvocationRegistry.identity(definition, step.stepId(), model.spec().modelInvocation().turnIndex());
        require(expected.childId().equals(model.spec().childId()), "DECLINE_MODEL_SOURCE_CHANGED");
        var approval = models.approve(model.spec().modelInvocation());
        requireSourceResponse(token, call, approval);
        runs.prepareAction(token, new ActionSpec(call.spec().actionId(), step.stepId(), REF.kind().name(), REF.name(), REF.version(),
                CampaignExplorationCallStore.encode(call.spec())));
        Arguments arguments = arguments(call.spec().arguments());
        BoundInputs inputs = bindings.resolveLocal(step, frozen.inputs(), DeclineSelectionSkill.capability().signature(),
                arguments.bindings(), arguments.parameters(), approval.invocation().inputs());
        try {
            CallBound bound = FrozenDeclineSelection.prepareCall(definition, call.spec(), inputs);
            var methods = new RunPinnedSkills(definition.runId(), approvedSkillsRoot, List.of(bound.skillPin()), Set.of(), List.of(),
                    (run, tool) -> false);
            Source source = new Source(call, approval, inputs, bound, methods);
            reauthorizeInputs(token, source);
            return source;
        } catch (RuntimeException | Error invalid) { inputs.close(); throw invalid; }
    }

    private void requireSourceResponse(RunToken token, CallRecord call, ModelInvocationRegistry.Approval approval) {
        var response = runs.readModelResponse(token, call.spec().modelChildId(), approval, authorizer);
        require(CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)).equals(call.spec().responseHash())
                && response.toolCalls().size() == 1, "DECLINE_MODEL_SOURCE_CHANGED");
        var tool = response.toolCalls().get(0);
        require(tool.id().equals(call.spec().toolCallId()) && tool.name().equals(REF.name())
                && tool.arguments().equals(call.spec().arguments()), "DECLINE_MODEL_SOURCE_CHANGED");
    }

    private void reauthorizeInputs(RunToken token, Source source) {
        requireToken(token); requireCapability(); source.methods().verifyPins();
        bindings.reauthorize(DeclineSelectionSkill.capability().signature(), source.inputs());
        requireSourceResponse(token, source.call(), source.model());
        selectionDefinition(token, source);
    }

    private DeclineSelectionPage.Definition selectionDefinition(RunToken token, Source source) {
        CallBound bound = source.bound();
        var summary = scopes.inspectPublished(definition.caller(), bound.scopeArtifactId(), authorizer);
        Artifact scope = runs.readArtifact(definition.caller(), bound.scopeArtifactId(), authorizer);
        ArtifactMetadata metadata = scope.metadata();
        require(metadata.equals(source.inputs().artifact("scopeArtifact").metadata()) && definition.caller().equals(metadata.owner())
                && definition.runId().equals(metadata.runId()) && definition.planId().equals(metadata.planId())
                && definition.revision() == metadata.revision() && bound.scopeRef().equals(summary.scopeRef())
                && bound.gid().equals(summary.gid()) && current.equals(summary.owner()), "DECLINE_SCOPE_CHANGED");
        JsonNode manifest = object(scope.payloadJson());
        var collection = scopes.load(token, manifest.path("collectionId").asText());
        require(collection.state() == CampaignScopeStore.State.PUBLISHED && bound.scopeArtifactId().equals(collection.artifactId())
                && collection.definition().action().actionId().equals(metadata.actionId())
                && collection.definition().action().executorVersion().equals(metadata.executorVersion())
                && collection.memberCount() == summary.memberCount() && collection.pageCount() == summary.pageCount(), "DECLINE_SCOPE_CHANGED");
        return new DeclineSelectionPage.Definition(bound.collectionId(), bound.scopeArtifactId(), bound.scopeRef(),
                bound.periodsRef(), bound.metric(), summary.shardCount(), summary.memberCount());
    }

    private List<BoundQuery> queries(RunToken token, Source source, DeclineSelectionPage.Definition selection, int shardIndex) {
        reauthorizeInputs(token, source);
        if (selection.memberCount() == 0) return List.of();
        FrozenQueryScope shard = scopes.shard(definition.caller(), source.bound().scopeArtifactId(), shardIndex, authorizer);
        return List.of(FrozenDeclineSelection.queryCall(definition, source.bound(), shard, 0),
                FrozenDeclineSelection.queryCall(definition, source.bound(), shard, 1));
    }

    private BoundQuery storedQuery(RunToken token, Source source, String childId) {
        ChildRecord child = runs.child(token, childId).orElseThrow();
        require(child.spec().mode() == ChildMode.ASYNC && child.spec().wire() != null, "DECLINE_CHILD_CHANGED");
        var value = object(child.spec().wire().bodyJson()).path("scope");
        require(value.isObject(), "DECLINE_CHILD_CHANGED");
        FrozenQueryScope recorded = FrozenQueryScope.fromMap(JSON.convertValue(value, Map.class));
        FrozenQueryScope actual = scopes.shard(definition.caller(), source.bound().scopeArtifactId(), recorded.shardIndex(), authorizer);
        require(recorded.equals(actual), "DECLINE_SCOPE_CHANGED");
        var candidates = List.of(FrozenDeclineSelection.queryCall(definition, source.bound(), actual, 0),
                FrozenDeclineSelection.queryCall(definition, source.bound(), actual, 1));
        return candidates.stream().filter(query -> query.child().equals(child.spec())).findFirst()
                .orElseThrow(() -> failure("DECLINE_CHILD_CHANGED"));
    }

    private boolean queryAllowed(Source source, BoundQuery query) {
        return queryAuthorizer.mayUse(current, source.bound().scopeRef(), query.periodsRef(), query.request());
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
        if (token == null || !definition.equals(token.definition())) throw new SecurityException("DECLINE_RUN_CHANGED");
        RunRecord actual = runs.loadRun(definition.caller(), definition.runId()).orElseThrow();
        if (actual.status() != RunStatus.ACTIVE || !actual.token().equals(token)) throw new SecurityException("DECLINE_RUN_FENCED");
    }
    private void requireLive(CallPermit permit) {
        if (permit == null || permit.step() == null || !step.stepId().equals(permit.step().stepId())) throw new SecurityException("DECLINE_CALL_FENCED");
        requireToken(permit.step().runToken());
        if (!steps.mayExecute(permit.step()) || !calls.mayExecute(permit)) throw new SecurityException("DECLINE_CALL_FENCED");
    }
    private boolean authorized(RunToken token, Source source, CallPermit permit) {
        try { requireLive(permit); reauthorizeInputs(token, source); return true; }
        catch (IllegalArgumentException | IllegalStateException | SecurityException denied) { return false; }
    }
    private void requirePrincipal() {
        var owner = definition.caller();
        if (current.system() || !owner.tenantId().equals(current.tenantId()) || !owner.subject().equals(current.username())
                || owner.authVersion() != current.authVersion()) throw new SecurityException("DECLINE_PRINCIPAL_MISMATCH");
    }
    private void requireCapability() {
        require(step.executionMode() == PlanSpec.ExecutionMode.REACT && step.executor() == null && step.explorationPolicy() != null
                && step.explorationPolicy().allowedExecutors().contains(REF), "DECLINE_EXPLORATION_NOT_ALLOWED");
        var exploration = step.explorationPolicy();
        var policy = catalog.policy(exploration.policyRef(), exploration.policyVersion()).orElseThrow();
        require(policy.policyRef().equals(exploration.policyRef()) && policy.policyVersion().equals(exploration.policyVersion())
                && policy.allowedExecutors().containsAll(exploration.allowedExecutors())
                && policy.completionCriteria().equals(exploration.completionCriteria())
                && Objects.equals(policy.terminationPolicyRef(), exploration.terminationPolicyRef()), "DECLINE_POLICY_CHANGED");
        for (PlanSpec.ExecutorRef ref : exploration.allowedExecutors()) {
            var capability = catalog.capability(ref).orElseThrow();
            require(ref.equals(capability.executor()) && !capability.startsExploration(), "DECLINE_EXPLORATION_RECURSION_REJECTED");
        }
        require(DeclineSelectionSkill.capability().equals(catalog.capability(REF).orElseThrow()), "DECLINE_CAPABILITY_CHANGED");
    }

    private static Arguments arguments(String encoded) {
        JsonNode root = object(encoded);
        fields(root, Set.of("inputBindings", "parameters"));
        require(root.path("inputBindings").isObject() && root.path("parameters").isObject(), "DECLINE_ARGUMENTS_INVALID");
        fields(root.get("parameters"), Set.of("metric"));
        require(root.get("parameters").path("metric").isTextual(), "DECLINE_ARGUMENTS_INVALID");
        Map<String, PlanBinding> inputs = new LinkedHashMap<>();
        root.get("inputBindings").fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue(); fields(value, Set.of("source", "input", "artifactId", "stepId", "output"));
            require(value.path("source").isTextual() && !value.hasNonNull("stepId") && !value.hasNonNull("output"), "DECLINE_BINDING_INVALID");
            PlanBinding binding;
            if ("INPUT".equals(value.get("source").textValue()) && value.path("input").isTextual() && !value.hasNonNull("artifactId"))
                binding = PlanBinding.input(value.get("input").textValue());
            else if ("ARTIFACT".equals(value.get("source").textValue()) && value.path("artifactId").isTextual() && !value.hasNonNull("input"))
                binding = PlanBinding.artifact(value.get("artifactId").textValue());
            else throw failure("DECLINE_BINDING_INVALID");
            inputs.put(entry.getKey(), binding);
        });
        return new Arguments(Map.copyOf(inputs), Map.of("metric", root.get("parameters").get("metric").textValue()));
    }
    private static JsonNode object(String encoded) {
        try { JsonNode value = JSON.readTree(encoded); require(value != null && value.isObject(), "DECLINE_ARGUMENTS_INVALID"); return value; }
        catch (JsonProcessingException invalid) { throw failure("DECLINE_ARGUMENTS_INVALID"); }
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        require(value != null && value.isObject(), "DECLINE_ARGUMENTS_INVALID");
        value.fieldNames().forEachRemaining(name -> require(allowed.contains(name), "DECLINE_ARGUMENTS_INVALID"));
    }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalArgumentException failure(String code) { return new IllegalArgumentException(code); }
}
