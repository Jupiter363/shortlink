package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution.ChildResult;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery.Prepared;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.StatisticsJobFixedExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Opt-in typed bridge from a real native CALL to the existing statistics job protocol. It never
 * runs a model, polls a job or registers the production driver. Recovery uses the existing receiver.
 */
public final class StatisticsExplorationTool {
    public static final PlanSpec.ExecutorRef REF = StatisticsJobFixedExecutor.REF;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String ARGUMENT_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["inputBindings","parameters"],
             "properties":{"inputBindings":{"type":"object","additionalProperties":false,
               "required":["scope","periods","query"],"properties":{
                 "scope":{"$ref":"#/$defs/binding"},"periods":{"$ref":"#/$defs/binding"},"query":{"$ref":"#/$defs/binding"}}},
               "parameters":{"type":"object","additionalProperties":false}},
             "$defs":{"binding":{"oneOf":[
               {"type":"object","additionalProperties":false,"required":["source","input"],"properties":{
                 "source":{"const":"INPUT"},"input":{"type":"string","minLength":1},
                 "stepId":{"type":"null"},"output":{"type":"null"},"artifactId":{"type":"null"}}},
               {"type":"object","additionalProperties":false,"required":["source","artifactId"],"properties":{
                 "source":{"const":"ARTIFACT"},"artifactId":{"type":"string","minLength":1},
                 "stepId":{"type":"null"},"output":{"type":"null"},"input":{"type":"null"}}}]}}}
            """;
    private static final ToolDefinition DEFINITION = ToolDefinition.builder().name(REF.name())
            .description("Submit one authorized statistics query using frozen typed inputs; results arrive as durable evidence references.")
            .inputSchema(ARGUMENT_SCHEMA).build();

    private record Arguments(Map<String, PlanBinding> inputs, Map<String, Object> parameters) { }
    private record IndexedCall(String callId, String childId) { }
    private record BoundCall(CallRecord call, ModelInvocationRegistry.Approval model, BoundInputs inputs,
                             Prepared prepared) implements AutoCloseable {
        @Override public void close() { inputs.close(); }
    }

    private final JdbcTemplate jdbc;
    private final RunDefinition definition;
    private final PlanSpec.Step step;
    private final FrozenCampaignRun frozen;
    private final AgentPrincipal current;
    private final ShortLinkBusinessGateway gateway;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignExplorationCallStore calls;
    private final CapabilityCatalog catalog;
    private final ArtifactContractRegistry artifactContracts;
    private final ModelInvocationRegistry models;
    private final ArtifactAuthorizer artifactAuthorizer;
    private final StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer;
    private final StepBindings bindings;

    public StatisticsExplorationTool(JdbcTemplate jdbc, RunDefinition definition, String stepId,
            AgentPrincipal current, ShortLinkBusinessGateway gateway, CampaignRunStore runs,
            CampaignStepStore steps, CampaignExplorationCallStore calls, CapabilityCatalog catalog,
            ArtifactContractRegistry artifactContracts, ModelInvocationRegistry modelRegistry,
            StepBindings.CurrentInputAuthorizer inputAuthorizer, ArtifactAuthorizer artifactAuthorizer,
            StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer) {
        this.jdbc = Objects.requireNonNull(jdbc); this.definition = Objects.requireNonNull(definition);
        this.current = Objects.requireNonNull(current); this.gateway = Objects.requireNonNull(gateway);
        this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps);
        this.calls = Objects.requireNonNull(calls); this.catalog = Objects.requireNonNull(catalog);
        this.artifactContracts = Objects.requireNonNull(artifactContracts); this.models = Objects.requireNonNull(modelRegistry);
        this.artifactAuthorizer = Objects.requireNonNull(artifactAuthorizer); this.queryAuthorizer = Objects.requireNonNull(queryAuthorizer);
        this.frozen = FrozenCampaignRun.read(definition);
        List<PlanSpec.Step> matching = frozen.plan().steps().stream().filter(candidate -> Objects.equals(stepId, candidate.stepId())).toList();
        if (matching.size() != 1) throw rejected("STATISTICS_EXPLORATION_STEP_INVALID");
        this.step = matching.get(0);
        requirePrincipal(); requireCapability();
        this.bindings = new StepBindings(artifactContracts, runs, definition.caller(), artifactAuthorizer,
                Objects.requireNonNull(inputAuthorizer), new StepBindings.StepPolicy() {
                    @Override public void validateInputs(PlanSpec.Step ignored, BoundInputs inputs) {
                        throw rejected("STATISTICS_LOCAL_BINDING_REQUIRED");
                    }
                    @Override public void validateOutputs(PlanSpec.Step ignored, BoundInputs inputs,
                            Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                        throw rejected("STATISTICS_LOCAL_BINDING_REQUIRED");
                    }
                });
    }

    public static CapabilityCatalog.Capability capability() { return StatisticsJobFixedExecutor.capability(); }
    public static ToolDefinition definition() { return DEFINITION; }

    public NativeExplorationAdapter.RegisteredTool registration() {
        ToolCallback callback = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
            @Override public String call(String input) { throw rejected("STATISTICS_CALL_CONTEXT_REQUIRED"); }
            @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                Object value = context == null ? null : context.getContext().get(NativeExplorationAdapter.DISPATCH_SCOPE);
                if (!(value instanceof NativeExplorationAdapter.DispatchScope scope)) throw rejected("STATISTICS_CALL_CONTEXT_REQUIRED");
                return execute(input, scope).json();
            }
        };
        return new NativeExplorationAdapter.RegisteredTool(callback, StatisticsExplorationTool::observation);
    }

    /** Short candidate index only. Every returned target is re-derived from its real CALL and MODEL. */
    public Map<String, StatisticsJobResultReceiver.Target> resultTargets(RunToken token) {
        requireToken(token);
        Map<String, StatisticsJobResultReceiver.Target> targets = new LinkedHashMap<>();
        for (IndexedCall index : indexedCalls(token, null)) {
            try (BoundCall bound = resolve(token, index.callId(), null)) {
                requireStoredChild(token, index.childId(), bound);
                if (targets.putIfAbsent(index.childId(), bound.prepared().target()) != null)
                    throw rejected("STATISTICS_RESULT_TARGET_DUPLICATE");
            }
        }
        return Map.copyOf(targets);
    }

    /**
     * Recovery authorization deliberately does not require the old Step/CALL callback to be live.
     * The receiver/reconciler owns a fresh real child permit and repeats this gate for every I/O.
     */
    public boolean reauthorize(RunToken token, String childId) {
        try {
            requireToken(token);
            List<IndexedCall> matches = indexedCalls(token, childId);
            if (matches.size() != 1) return false;
            try (BoundCall bound = resolve(token, matches.get(0).callId(), null)) {
                requireStoredChild(token, childId, bound);
                return true;
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException denied) { return false; }
    }

    private NativeExplorationAdapter.Observation execute(String arguments, NativeExplorationAdapter.DispatchScope scope) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw rejected("STATISTICS_CALL_REQUIRES_COMMITTED_PREPARATION");
        CallPermit owner = Objects.requireNonNull(scope.callPermit());
        requireLiveOwner(owner);
        try (BoundCall bound = resolve(owner.step().runToken(), owner.callId(), arguments)) {
            requireLive(bound, owner);
            if (!bound.call().spec().actionId().equals(owner.actionId())) throw rejected("STATISTICS_CALL_CHANGED");
            ChildRecord existing = runs.prepareChild(owner.step().runToken(), bound.prepared().child());
            if (existing.state() == ChildState.READY || existing.state() == ChildState.WAITING)
                return project(owner.step().runToken(), existing, bound);
            if (existing.state() != ChildState.PREPARED || existing.attemptVersion() != 0)
                throw rejected(existing.reason() == UnresolvedReason.QUERY_CAPACITY_EXHAUSTED
                        ? "STATISTICS_CAPACITY_CONTINUATION_REQUIRED" : "SUBMISSION_UNRESOLVED");
            DispatchPermit dispatch = runs.beginDispatch(owner.step().runToken(), existing.spec().childId(), owner);
            boolean receiptSaved = false;
            try {
                Runnable beforeIo = () -> {
                    try {
                        scope.dispatch(() -> {
                            requireLive(bound, owner);
                            if (!runs.mayDispatch(dispatch)) throw new SecurityException("STATISTICS_CHILD_FENCED");
                            return null;
                        });
                    } catch (RuntimeException denied) { throw denied; }
                    catch (Exception denied) { throw new SecurityException("STATISTICS_CHILD_FENCED"); }
                };
                ChildResult result = StatisticsJobFixedExecutor.submit(bound.prepared().child().wire(), beforeIo,
                        definition, current, gateway, bound.prepared().request(), () -> authorized(bound, owner));
                if (result.jobId() != null && (!authorized(bound, owner) || !runs.mayDispatch(dispatch))) {
                    runs.recordLateJob(dispatch, result.jobId()); receiptSaved = true;
                    throw new SecurityException("STATISTICS_CHILD_FENCED");
                }
                beforeIo.run(); // The same current rights apply to receipt publication after the ACK.
                if (result.capacityKind() != null) {
                    runs.deferUnadmitted(dispatch, result.capacityKind()); receiptSaved = true;
                    throw rejected("STATISTICS_CAPACITY_CONTINUATION_REQUIRED");
                }
                if (result.jobId() == null || result.artifact() != null) throw rejected("STATISTICS_SUBMISSION_RECEIPT_INVALID");
                runs.recordWaiting(dispatch, result.jobId()); receiptSaved = true;
            } finally {
                try { if (!receiptSaved && runs.mayDispatch(dispatch)) runs.markUnresolved(dispatch); }
                finally { runs.callbackExited(dispatch); }
            }
            requireLive(bound, owner);
            return project(owner.step().runToken(), runs.child(owner.step().runToken(), existing.spec().childId()).orElseThrow(), bound);
        }
    }

    private NativeExplorationAdapter.Observation project(RunToken token, ChildRecord child, BoundCall bound) {
        if (!child.spec().equals(bound.prepared().child()) || child.callbackActive()) throw rejected("STATISTICS_CHILD_CHANGED");
        if (child.state() == ChildState.WAITING && child.jobId() != null) return NativeExplorationAdapter.Observation.pending(child.jobId());
        if (child.state() == ChildState.READY && bound.prepared().target().artifactId().equals(child.artifactId())) {
            var artifact = artifactContracts.validateArtifact(StatisticsJobFixedExecutor.OUTPUT_TYPE, child.artifactId(), runs,
                    token.definition().caller(), artifactAuthorizer);
            var metadata = artifact.metadata();
            if (!metadata.actionId().equals(bound.call().spec().actionId()) || !metadata.childId().equals(child.spec().childId())
                    || !metadata.runId().equals(definition.runId()) || metadata.revision() != definition.revision()
                    || !metadata.planId().equals(definition.planId()) || !metadata.executorVersion().equals(REF.version())
                    || !metadata.ref().scopeRef().equals(bound.prepared().scopeRef())
                    || !metadata.ref().periodsRef().equals(bound.prepared().periodsRef())) throw rejected("STATISTICS_OUTPUT_CHANGED");
            return NativeExplorationAdapter.Observation.ready(child.artifactId());
        }
        throw rejected("STATISTICS_RECEIPT_UNAVAILABLE");
    }

    private BoundCall resolve(RunToken token, String callId, String suppliedArguments) {
        requireToken(token); requireCapability();
        CallRecord call = calls.call(token, callId).orElseThrow(() -> rejected("STATISTICS_CALL_REQUIRED"));
        if (!step.stepId().equals(call.spec().stepId()) || !REF.equals(call.spec().executor())) throw rejected("STATISTICS_CALL_CHANGED");
        var expectedIdentity = CampaignExplorationCallStore.identity(definition, step.stepId(), call.spec().modelChildId(), call.spec().toolCallId());
        if (!expectedIdentity.callId().equals(callId) || !expectedIdentity.actionId().equals(call.spec().actionId())) throw rejected("STATISTICS_CALL_CHANGED");
        if (suppliedArguments != null && !call.spec().arguments().equals(new ModelInvocationRegistry.ToolCall(
                call.spec().toolCallId(), REF.name(), suppliedArguments).arguments())) throw rejected("STATISTICS_CALL_ARGUMENTS_CHANGED");
        ChildRecord modelChild = runs.child(token, call.spec().modelChildId()).orElseThrow(() -> rejected("STATISTICS_MODEL_SOURCE_REQUIRED"));
        if (modelChild.spec().mode() != ChildMode.MODEL || modelChild.state() != ChildState.READY || modelChild.spec().modelInvocation() == null)
            throw rejected("STATISTICS_MODEL_SOURCE_REQUIRED");
        var sourceIdentity = ModelInvocationRegistry.identity(definition, step.stepId(), modelChild.spec().modelInvocation().turnIndex());
        if (!sourceIdentity.childId().equals(modelChild.spec().childId())) throw rejected("STATISTICS_MODEL_SOURCE_CHANGED");
        ModelInvocationRegistry.Approval approval = models.approve(modelChild.spec().modelInvocation());
        requireSourceResponse(token, call, approval);
        // CALL's FK guarantees the Action exists; the existing immutable Action contract is verified here.
        runs.prepareAction(token, new ActionSpec(call.spec().actionId(), step.stepId(), REF.kind().name(), REF.name(), REF.version(),
                CampaignExplorationCallStore.encode(call.spec())));
        Arguments arguments = parseArguments(call.spec().arguments());
        BoundInputs inputs = bindings.resolveLocal(step, frozen.inputs(), capability().signature(), arguments.inputs(),
                arguments.parameters(), approval.invocation().inputs());
        try {
            String scope = reference(inputs.value("scope")), periods = reference(inputs.value("periods"));
            if (!Objects.equals(scope, step.explorationPolicy().scopeRef()) || !Objects.equals(periods, step.explorationPolicy().periodsRef()))
                throw rejected("STATISTICS_EXPLORATION_BOUNDARY_CHANGED");
            Map<String, Object> descriptor = objectValue(inputs.value("query"));
            Prepared prepared = FrozenStatisticsJobQuery.prepareCall(definition, step.stepId(), callId, call.spec().actionId(), REF,
                    scope, periods, descriptor);
            BoundCall bound = new BoundCall(call, approval, inputs, prepared);
            reauthorizeInputs(bound);
            return bound;
        } catch (RuntimeException | Error rejected) { inputs.close(); throw rejected; }
    }

    private void requireSourceResponse(RunToken token, CallRecord call, ModelInvocationRegistry.Approval approval) {
        var response = runs.readModelResponse(token, call.spec().modelChildId(), approval, artifactAuthorizer);
        if (!CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)).equals(call.spec().responseHash())
                || response.toolCalls().size() != 1) throw rejected("STATISTICS_MODEL_SOURCE_CHANGED");
        var tool = response.toolCalls().get(0);
        if (!tool.id().equals(call.spec().toolCallId()) || !tool.name().equals(REF.name()) || !tool.arguments().equals(call.spec().arguments()))
            throw rejected("STATISTICS_MODEL_SOURCE_CHANGED");
    }

    private void requireLive(BoundCall bound, CallPermit owner) {
        requireLiveOwner(owner);
        CallRecord actual = calls.call(owner.step().runToken(), owner.callId()).orElseThrow();
        if (!actual.spec().equals(bound.call().spec())) throw rejected("STATISTICS_CALL_CHANGED");
        requireSourceResponse(owner.step().runToken(), actual, bound.model());
        reauthorizeInputs(bound);
    }
    private void requireLiveOwner(CallPermit owner) {
        if (owner == null || owner.step() == null || !step.stepId().equals(owner.step().stepId())) throw new SecurityException("STATISTICS_CALL_FENCED");
        requireToken(owner.step().runToken());
        if (!steps.mayExecute(owner.step()) || !calls.mayExecute(owner)) throw new SecurityException("STATISTICS_CALL_FENCED");
    }
    private boolean authorized(BoundCall bound, CallPermit owner) {
        try { requireLive(bound, owner); return true; }
        catch (IllegalArgumentException | IllegalStateException | SecurityException denied) { return false; }
    }
    private void reauthorizeInputs(BoundCall bound) {
        requirePrincipal(); requireCapability();
        bindings.reauthorize(capability().signature(), bound.inputs());
        if (!queryAuthorizer.mayUse(current, bound.prepared().scopeRef(), bound.prepared().periodsRef(), bound.prepared().request()))
            throw new SecurityException("STATISTICS_QUERY_ACCESS_DENIED");
    }

    private List<IndexedCall> indexedCalls(RunToken token, String childId) {
        // parent_call_* names the current child attempt and is null for independent reconciliation.
        // This index is not proof of ownership: requireStoredChild verifies the full deterministic
        // ChildSpec reconstructed from the genuine CALL and its frozen MODEL input envelope.
        String filter = childId == null ? "" : " AND ch.child_id=?";
        List<Object> args = new ArrayList<>(List.of(definition.runId(), definition.revision(), step.stepId(), REF.kind().name(), REF.name(), REF.version()));
        if (childId != null) args.add(childId);
        return jdbc.query("SELECT c.call_id,ch.child_id FROM campaign_exploration_call c "
                        + "JOIN campaign_action_ledger a ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "JOIN campaign_child_ledger ch ON ch.run_id=c.run_id AND ch.revision=c.revision AND ch.action_id=c.action_id "
                        + "WHERE c.run_id=? AND c.revision=? AND c.step_id=? AND a.action_kind='CAPABILITY' "
                        + "AND a.executor_kind=? AND a.executor_name=? AND a.executor_version=? AND ch.child_mode='ASYNC'" + filter,
                (rs, row) -> new IndexedCall(rs.getString("call_id"), rs.getString("child_id")), args.toArray());
    }
    private void requireStoredChild(RunToken token, String childId, BoundCall bound) {
        ChildRecord child = runs.child(token, childId).orElseThrow(() -> rejected("STATISTICS_CHILD_REQUIRED"));
        if (!child.spec().equals(bound.prepared().child())) throw rejected("STATISTICS_CHILD_CHANGED");
    }
    private void requireToken(RunToken token) {
        requirePrincipal();
        if (token == null || !definition.equals(token.definition())) throw new SecurityException("STATISTICS_RUN_CHANGED");
        RunRecord actual = runs.loadRun(definition.caller(), definition.runId()).orElseThrow();
        if (actual.status() != RunStatus.ACTIVE || !actual.token().equals(token)) throw new SecurityException("STATISTICS_RUN_FENCED");
    }
    private void requirePrincipal() {
        var owner = definition.caller();
        if (current.system() || !Objects.equals(owner.tenantId(), current.tenantId())
                || !Objects.equals(owner.subject(), current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("STATISTICS_PRINCIPAL_MISMATCH");
    }
    private void requireCapability() {
        if (step.executionMode() != PlanSpec.ExecutionMode.REACT || step.executor() != null || step.explorationPolicy() == null
                || !step.explorationPolicy().allowedExecutors().contains(REF)) throw rejected("STATISTICS_EXPLORATION_NOT_ALLOWED");
        var exploration = step.explorationPolicy();
        var policy = catalog.policy(exploration.policyRef(), exploration.policyVersion()).orElseThrow(() -> rejected("STATISTICS_POLICY_NOT_REGISTERED"));
        if (!policy.policyRef().equals(exploration.policyRef()) || !policy.policyVersion().equals(exploration.policyVersion())
                || !policy.allowedExecutors().containsAll(exploration.allowedExecutors())
                || !policy.completionCriteria().equals(exploration.completionCriteria())
                || !Objects.equals(policy.terminationPolicyRef(), exploration.terminationPolicyRef())) throw rejected("STATISTICS_POLICY_CHANGED");
        for (PlanSpec.ExecutorRef executor : exploration.allowedExecutors()) {
            var capability = catalog.capability(executor).orElseThrow(() -> rejected("STATISTICS_CAPABILITY_NOT_REGISTERED"));
            if (!executor.equals(capability.executor()) || capability.startsExploration()) throw rejected("STATISTICS_EXPLORATION_RECURSION_REJECTED");
        }
        if (!capability().equals(catalog.capability(REF).orElseThrow())) throw rejected("STATISTICS_CAPABILITY_SIGNATURE_CHANGED");
    }

    private static Arguments parseArguments(String encoded) {
        JsonNode root = object(encoded);
        fields(root, Set.of("inputBindings", "parameters"));
        if (!root.path("inputBindings").isObject() || !root.path("parameters").isObject() || !root.path("parameters").isEmpty())
            throw rejected("STATISTICS_TOOL_ARGUMENTS_INVALID");
        Map<String, PlanBinding> bindings = new LinkedHashMap<>();
        root.get("inputBindings").fields().forEachRemaining(entry -> {
            JsonNode binding = entry.getValue();
            fields(binding, Set.of("source", "input", "artifactId", "stepId", "output"));
            if (!binding.path("source").isTextual() || binding.hasNonNull("stepId") || binding.hasNonNull("output"))
                throw rejected("STATISTICS_TOOL_BINDING_INVALID");
            PlanBinding parsed;
            if ("INPUT".equals(binding.get("source").textValue()) && binding.path("input").isTextual() && !binding.hasNonNull("artifactId"))
                parsed = PlanBinding.input(binding.get("input").textValue());
            else if ("ARTIFACT".equals(binding.get("source").textValue()) && binding.path("artifactId").isTextual() && !binding.hasNonNull("input"))
                parsed = PlanBinding.artifact(binding.get("artifactId").textValue());
            else throw rejected("STATISTICS_TOOL_BINDING_INVALID");
            bindings.put(entry.getKey(), parsed);
        });
        return new Arguments(Map.copyOf(bindings), Map.of());
    }
    private static NativeExplorationAdapter.Observation observation(String encoded) {
        JsonNode root = object(encoded);
        if ("PENDING".equals(root.path("status").asText())) {
            fields(root, Set.of("status", "jobId"));
            if (!root.path("jobId").isTextual()) throw rejected("STATISTICS_OBSERVATION_INVALID");
            return NativeExplorationAdapter.Observation.pending(root.get("jobId").textValue());
        }
        if ("READY".equals(root.path("status").asText())) {
            fields(root, Set.of("status", "artifactId"));
            if (!root.path("artifactId").isTextual()) throw rejected("STATISTICS_OBSERVATION_INVALID");
            return NativeExplorationAdapter.Observation.ready(root.get("artifactId").textValue());
        }
        throw rejected("STATISTICS_OBSERVATION_INVALID");
    }
    private static JsonNode object(String encoded) {
        try {
            JsonNode value = JSON.readTree(encoded);
            if (value == null || !value.isObject()) throw rejected("STATISTICS_TOOL_ARGUMENTS_INVALID");
            return value;
        } catch (JsonProcessingException invalid) { throw rejected("STATISTICS_TOOL_ARGUMENTS_INVALID"); }
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject()) throw rejected("STATISTICS_TOOL_ARGUMENTS_INVALID");
        value.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw rejected("STATISTICS_TOOL_ARGUMENTS_INVALID"); });
    }
    private static String reference(Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        if (value instanceof JsonNode node && node.isTextual() && !node.textValue().isBlank()) return node.textValue();
        throw rejected("STATISTICS_TOOL_REFERENCE_INVALID");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Object value) {
        if (value instanceof JsonNode node) {
            if (!node.isObject()) throw rejected("STATISTICS_TOOL_QUERY_INVALID");
            return JSON.convertValue(node, Map.class);
        }
        if (value instanceof Map<?, ?> map && map.keySet().stream().allMatch(String.class::isInstance)) return (Map<String, Object>) map;
        throw rejected("STATISTICS_TOOL_QUERY_INVALID");
    }
    private static IllegalArgumentException rejected(String code) { return new IllegalArgumentException(code); }
}
