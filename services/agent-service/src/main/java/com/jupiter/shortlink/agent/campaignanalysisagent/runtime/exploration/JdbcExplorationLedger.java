package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcExplorationCallbackGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in durable facts for the native ReactAgent loop. No runner, scheduler, retry loop or Spring
 * registration. A model slot, its callback and its observation have independent durable receipts.
 */
public final class JdbcExplorationLedger implements DurableExplorationSession {
    private static final JsonMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private static final ModelInvocationRegistry.Limits DECODE_LIMITS =
            new ModelInvocationRegistry.Limits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    /** Trusted server profile. The registry predicate still approves each exact evolving request. */
    public record ModelConfiguration(String modelRef, String modelVersion, String configurationHash,
                                     String systemPrompt, List<ToolDefinition> tools,
                                     Map<String, ArtifactMetadata> inputs, Instant expiresAt) {
        public ModelConfiguration {
            Objects.requireNonNull(modelRef); Objects.requireNonNull(modelVersion); Objects.requireNonNull(configurationHash);
            tools = List.copyOf(tools); inputs = Map.copyOf(inputs); Objects.requireNonNull(expiresAt);
            expiresAt = Instant.ofEpochMilli(expiresAt.toEpochMilli());
            if (systemPrompt != null && systemPrompt.isBlank()) throw failure("EXPLORATION_SYSTEM_PROMPT_INVALID");
        }
    }

    private record Header(Status status, String reason, String input, long turn) {}
    private record Turn(long index, String modelChildId, InvocationSpec invocation, String decision,
                        String responseHash, String callId, String receiptChild, String artifactId,
                        String jobId, String observationId, boolean pendingProjected, boolean consumed, boolean repairCounted) {}
    private record Binding(Turn turn, ModelActionSpec action, ChildSpec child, Approval approval) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignExplorationCallStore calls;
    private final StepPermit step;
    private final RunToken token;
    private final ModelInvocationRegistry registry;
    private final ModelConfiguration configuration;
    private final Map<String, PlanSpec.ExecutorRef> executors;
    private final ArtifactAuthorizer authorizer;
    private final JdbcExplorationCallbackGate gate;
    private final PlanSpec.Step planned;
    private final String stepJson;
    private final FrozenCampaignRun frozen;
    private final String configurationHash;
    private final NativeExplorationAdapter.ExecutionKey identity;

    public JdbcExplorationLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            StepPermit step, ModelInvocationRegistry registry, ModelConfiguration configuration,
            Map<String, PlanSpec.ExecutorRef> executors, ArtifactAuthorizer authorizer) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps); this.calls = Objects.requireNonNull(calls);
        this.step = Objects.requireNonNull(step); this.token = step.runToken();
        this.registry = Objects.requireNonNull(registry); this.configuration = Objects.requireNonNull(configuration);
        this.executors = Map.copyOf(executors); this.authorizer = Objects.requireNonNull(authorizer);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED || transactions.isReadOnly())
            throw failure("EXPLORATION_REQUIRES_WRITABLE_TRANSACTION");
        this.gate = new JdbcExplorationCallbackGate(jdbc);
        this.frozen = FrozenCampaignRun.read(token.definition());
        this.planned = frozen.plan().steps().stream().filter(value -> value.stepId().equals(step.stepId())).findFirst().orElseThrow();
        if (planned.executionMode() != PlanSpec.ExecutionMode.REACT || planned.executor() != null || planned.explorationPolicy() == null)
            throw failure("EXPLORATION_REQUIRES_REACT_STEP");
        this.stepJson = write(planned);
        var toolNames = configuration.tools().stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet());
        if (toolNames.size() != configuration.tools().size() || !toolNames.equals(this.executors.keySet()))
            throw failure("EXPLORATION_TOOL_CONFIGURATION_CHANGED");
        this.executors.forEach((name, executor) -> {
            if (!name.equals(executor.name()) || !planned.explorationPolicy().allowedExecutors().contains(executor))
                throw failure("EXPLORATION_EXECUTOR_NOT_ALLOWED");
        });
        this.configurationHash = CampaignRunStore.sha256(write(List.of(configuration, new TreeMap<>(executors), stepJson)));
        var definition = token.definition(); var owner = definition.caller();
        this.identity = new NativeExplorationAdapter.ExecutionKey(owner.tenantId(), owner.subject(), owner.authVersion(),
                definition.sessionId(), definition.runId(), definition.planId(), definition.revision(), step.stepId(),
                planned.explorationPolicy().policyVersion(), frozen.runnerVersion(), frozen.topologyVersion());
        tx(() -> {
            requireCurrent();
            var existing = headers();
            if (existing.isEmpty()) jdbc.update("INSERT INTO campaign_exploration_session (run_id,revision,step_id,configuration_hash,"
                            + "session_status,reason,current_turn,row_version,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE','',1,0,?,?)",
                    runId(), revision(), step.stepId(), configurationHash, clock.millis(), clock.millis());
            else header();
            return null;
        });
    }

    @Override public NativeExplorationAdapter.ExecutionKey identity() { return identity; }

    @Override public void freezeInput(String input) {
        if (input == null || input.isBlank()) throw failure("EXPLORATION_INPUT_REQUIRED");
        tx(() -> {
            requireCurrent(); Header header = header();
            if (header.input() != null && !header.input().equals(input)) throw failure("EXPLORATION_INPUT_CHANGED");
            if (header.input() == null) {
                // The same registered request bound applies before storing any original prompt.
                approve(1, request(initialMessages(input)), currentInputs(List.of()));
                jdbc.update("UPDATE campaign_exploration_session SET original_input=?,input_hash=?,row_version=row_version+1,updated_at=? "
                                + "WHERE run_id=? AND revision=? AND step_id=?", input, CampaignRunStore.sha256(input), clock.millis(),
                        runId(), revision(), step.stepId());
            }
            return null;
        });
    }

    @Override public View view() {
        return tx(() -> {
            requireCurrent(); refresh(); Header header = header();
            List<String> artifacts = jdbc.query("SELECT artifact_id FROM campaign_exploration_turn WHERE run_id=? AND revision=? "
                            + "AND step_id=? AND artifact_id IS NOT NULL ORDER BY turn_index FOR UPDATE",
                    (rs, row) -> rs.getString(1), runId(), revision(), step.stepId()).stream().distinct().toList();
            List<String> jobs = header.status() == Status.WAITING
                    ? jdbc.query("SELECT job_id FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? AND turn_index=? FOR UPDATE",
                            (rs, row) -> rs.getString(1), runId(), revision(), step.stepId(), header.turn()) : List.of();
            String job = jobs.isEmpty() ? null : jobs.get(0);
            return new View(header.status(), header.reason(), artifacts, job, gate.hasActive(runId()) ? 1 : 0);
        });
    }

    @Override public boolean mayCallModel() {
        return tx(() -> {
            requireCurrent(); refresh();
            // The durable model boundary repeats this admission from inside its own live supplier.
            // Its exact MODEL dispatch permit, not this advisory hook, owns model callback admission.
            return header().status() == Status.ACTIVE && !gate.hasActive(runId());
        });
    }

    @Override public List<Message> canonicalMessages() {
        return tx(() -> {
            requireCurrent(); refresh(); Header header = header();
            if (header.input() == null) throw failure("EXPLORATION_INPUT_REQUIRED");
            List<Turn> rows = turns();
            currentInputs(rows);
            Turn current = turn(header.turn()).orElse(null);
            if (current != null && ("MODEL".equals(current.decision()) || "TOOL".equals(current.decision()))) {
                validateInvocation(current);
                return ModelInvocationRegistry.decodeRequest(current.invocation().requestJson(), DECODE_LIMITS).messages();
            }
            return history(header, rows);
        });
    }

    @Override public Response call(Request actualRequest, Supplier<Response> liveCall) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure("MODEL_CALL_REQUIRES_COMMITTED_PREPARATION");
        Objects.requireNonNull(liveCall);
        Binding binding = tx(() -> {
            requireCurrent(); refresh(); Header header = header();
            if (header.status() != Status.ACTIVE || gate.hasActive(runId())) throw failure("EXPLORATION_NOT_ACTIVE");
            List<Turn> rows = turns();
            Turn current = turn(header.turn()).orElse(null);
            if (current != null && ("OBSERVED".equals(current.decision()) || "REPAIR".equals(current.decision()))) {
                // The old observation is consumed only in the transaction preparing its next MODEL request.
                long next = Math.addExact(header.turn(), 1);
                var expected = request(history(header, rows));
                sameRequest(expected, actualRequest);
                Approval approval = approve(next, expected, currentInputs(rows));
                Binding nextBinding = prepareTurn(next, approval);
                updateTurn(current.index(), "consumed=TRUE", new Object[0]);
                jdbc.update("UPDATE campaign_exploration_session SET current_turn=?,row_version=row_version+1,updated_at=? "
                                + "WHERE run_id=? AND revision=? AND step_id=?", next, clock.millis(), runId(), revision(), step.stepId());
                return nextBinding;
            }
            if (current == null) {
                var expected = request(history(header, rows)); sameRequest(expected, actualRequest);
                return prepareTurn(header.turn(), approve(header.turn(), expected, currentInputs(rows)));
            }
            if (!"MODEL".equals(current.decision()) && !"TOOL".equals(current.decision())) throw failure("EXPLORATION_TURN_CLOSED");
            sameRequest(ModelInvocationRegistry.decodeRequest(current.invocation().requestJson(), DECODE_LIMITS), actualRequest);
            return binding(current);
        });
        Response response = new DurableModelCallBoundary(runs, step, binding.action(), binding.child(), binding.approval(), authorizer)
                .call(actualRequest, liveCall);
        tx(() -> {
            requireCurrent(); Turn actual = turn(binding.turn().index()).orElseThrow();
            String hash = CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response));
            if (actual.responseHash() != null && !actual.responseHash().equals(hash)) throw failure("EXPLORATION_MODEL_RESPONSE_CHANGED");
            if (actual.responseHash() == null) updateTurn(actual.index(), "response_hash=?", hash);
            return null;
        });
        return response;
    }

    @Override public void registerCall(CallInput input) {
        tx(() -> {
            requireCurrent(); Header header = header(); Turn turn = turn(header.turn()).orElseThrow();
            Response response = response(turn);
            if (response.toolCalls().size() != 1) throw failure("EXPLORATION_TOOL_BATCH_INVALID");
            ToolCall returned = response.toolCalls().get(0);
            ToolCall supplied = new ToolCall(input.toolCallId(), input.toolName(), input.arguments());
            if (!returned.equals(supplied) || !response.text().equals(input.assistantText())) throw failure("EXPLORATION_TOOL_CALL_CHANGED");
            PlanSpec.ExecutorRef executor = executors.get(returned.name());
            if (executor == null) throw failure("EXPLORATION_EXECUTOR_NOT_ALLOWED");
            var identity = CampaignExplorationCallStore.identity(token.definition(), step.stepId(), turn.modelChildId(), returned.id());
            var spec = new CallSpec(identity.callId(), identity.actionId(), step.stepId(), turn.modelChildId(),
                    CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)), returned.id(), executor, returned.arguments());
            CallRecord prepared = calls.prepare(step, spec, binding(turn).approval(), authorizer);
            if (prepared.state() != CallState.PREPARED || prepared.revoked() || prepared.callbackActive())
                throw failure("EXPLORATION_CALL_NOT_REPLAYABLE");
            if (turn.callId() != null && !turn.callId().equals(spec.callId())) throw failure("EXPLORATION_CALL_CHANGED");
            updateTurn(turn.index(), "decision='TOOL',call_id=?", spec.callId());
            return null;
        });
    }

    @Override public long beginCallback(String toolCallId, String toolName) {
        return tx(() -> {
            requireCurrent(); Header header = header(); Turn turn = turn(header.turn()).orElseThrow();
            if (header.status() != Status.ACTIVE || !"TOOL".equals(turn.decision()) || turn.callId() == null)
                throw failure("EXPLORATION_CALL_NOT_PREPARED");
            var call = calls.call(token, turn.callId()).orElseThrow();
            if (!call.spec().toolCallId().equals(toolCallId) || !call.spec().executor().name().equals(toolName))
                throw failure("EXPLORATION_CALL_CHANGED");
            CallPermit permit = calls.beginCall(step, turn.callId(), binding(turn).approval(), authorizer);
            updateTurn(turn.index(), "call_attempt_id=?,call_attempt_version=?,call_step_attempt_id=?,call_step_attempt_version=?,"
                            + "call_run_version=?,call_run_token=?", permit.attemptId(), permit.attemptVersion(), step.attemptId(),
                    step.attemptVersion(), token.version(), token.advanceToken());
            return turn.index();
        });
    }

    @Override public CallPermit callPermit(long attempt) {
        return tx(() -> permit(attempt));
    }

    @Override public boolean mayDispatch(long attempt) {
        CallPermit permit = callPermit(attempt);
        return calls.mayExecute(permit);
    }

    @Override public void recordObservation(long attempt, NativeExplorationAdapter.Observation observation) {
        tx(() -> {
            requireCurrent(); CallPermit permit = permit(attempt);
            if (!calls.mayExecute(permit)) throw failure("EXPLORATION_CALL_FENCED");
            Turn turn = turn(attempt).orElseThrow();
            List<ChildRecord> matches = capabilityChildren(permit.actionId()).stream().filter(child -> observation.artifactId() != null
                    ? child.state() == ChildState.READY && observation.artifactId().equals(child.artifactId())
                    : child.state() == ChildState.WAITING && observation.jobId().equals(child.jobId())).toList();
            if (matches.size() != 1) throw failure("EXPLORATION_RECEIPT_NOT_UNIQUE");
            saveReceipt(turn, matches.get(0));
            if (observation.jobId() != null) updateTurn(turn.index(), "pending_projected=TRUE", new Object[0]);
            calls.recordReturned(permit);
            return null;
        });
    }

    @Override public void unresolved(long attempt) {
        CallPermit permit = callPermit(attempt);
        calls.revoke(permit, "EXECUTION_UNRESOLVED");
        // Revocation may race cancellation/revision. Only the exact current writer changes the session.
        if (steps.mayExecute(step)) tx(() -> { requireCurrent(); setStatus(Status.BLOCKED, "EXECUTION_UNRESOLVED"); return null; });
    }

    @Override public void callbackExited(long attempt) { calls.callbackExited(callPermit(attempt)); }

    @Override public boolean rejectBatch(int maximumRepairs) {
        if (maximumRepairs < 0) throw failure("EXPLORATION_REPAIR_ALLOWANCE_INVALID");
        return tx(() -> {
            requireCurrent(); Turn turn = turn(header().turn()).orElseThrow();
            if (response(turn).toolCalls().size() <= 1) throw failure("EXPLORATION_REPAIR_NOT_REQUIRED");
            if (!turn.repairCounted()) updateTurn(turn.index(), "decision='REPAIR',repair_counted=TRUE", new Object[0]);
            // Across all steps and revisions of this run; reopening a session cannot reset repairs.
            int used = jdbc.query("SELECT turn_index FROM campaign_exploration_turn WHERE run_id=? AND repair_counted=TRUE FOR UPDATE",
                    (rs, row) -> rs.getLong(1), runId()).size();
            if (used > maximumRepairs) { setStatus(Status.FAILED, "REPAIR_ALLOWANCE_EXHAUSTED"); return false; }
            return true;
        });
    }

    @Override public void candidate() {
        tx(() -> {
            requireCurrent(); Turn turn = turn(header().turn()).orElseThrow();
            if (!response(turn).toolCalls().isEmpty() || gate.hasActive(runId())) throw failure("EXPLORATION_CANDIDATE_INVALID");
            updateTurn(turn.index(), "decision='FINAL'", new Object[0]); setStatus(Status.CANDIDATE, ""); return null;
        });
    }

    @Override public void fail(String reason) {
        if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,127}")) throw failure("EXPLORATION_REASON_INVALID");
        tx(() -> { requireCurrent(); setStatus(Status.FAILED, reason); return null; });
    }

    // Durable native admission rebuilds messages from the turn ledger, not the P0 resume protocol.
    @Override public Optional<ResumeFacts> readyToResume() { return Optional.empty(); }
    @Override public boolean approveResume(String observationId) { return false; }
    @Override public void acknowledgeResume(String observationId) { throw failure("DURABLE_CANONICAL_RESUME_REQUIRED"); }
    @Override public void releaseResume(String observationId) { }
    @Override public void acknowledgeCanonical() {
        // Deliberately conservative: a durable consumed receipt does not prove native checkpoint publication.
        // Native acknowledgement is diagnostic only and stays false without an exact checkpoint receipt.
    }

    private Binding prepareTurn(long index, Approval approval) {
        var identity = ModelInvocationRegistry.identity(token.definition(), step.stepId(), index);
        var action = action(index);
        var child = new ChildSpec(identity.childId(), identity.actionId(), ChildMode.MODEL, identity.requestId(), null, null, approval.invocation());
        runs.prepareModelChild(step, action, child, approval, authorizer);
        String encoded = ModelInvocationRegistry.encode(approval.invocation());
        jdbc.update("INSERT INTO campaign_exploration_turn (run_id,revision,step_id,turn_index,model_child_id,invocation_json,invocation_hash,"
                        + "decision,pending_projected,consumed,native_acknowledged,repair_counted,row_version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'MODEL',FALSE,FALSE,FALSE,FALSE,0,?,?)",
                runId(), revision(), step.stepId(), index, child.childId(), encoded, approval.invocation().hash(), clock.millis(), clock.millis());
        return new Binding(turn(index).orElseThrow(), action, child, approval);
    }

    private Binding binding(Turn turn) {
        Approval approval = validateInvocation(turn);
        var identity = ModelInvocationRegistry.identity(token.definition(), step.stepId(), turn.index());
        var child = new ChildSpec(identity.childId(), identity.actionId(), ChildMode.MODEL, identity.requestId(), null, null, approval.invocation());
        return new Binding(turn, action(turn.index()), child, approval);
    }

    private ModelActionSpec action(long index) {
        var identity = ModelInvocationRegistry.identity(token.definition(), step.stepId(), index);
        var policy = planned.explorationPolicy();
        return new ModelActionSpec(identity.actionId(), step.stepId(), identity.invocationId(), configuration.modelRef(),
                configuration.modelVersion(), policy.policyRef(), policy.policyVersion(), stepJson);
    }

    private Approval approve(long index, Request request, Map<String, ArtifactMetadata> inputs) {
        var identity = ModelInvocationRegistry.identity(token.definition(), step.stepId(), index);
        Instant expiry = configuration.expiresAt();
        for (ArtifactMetadata input : inputs.values()) if (input.ref().expiresAt().isBefore(expiry)) expiry = input.ref().expiresAt();
        if (!clock.instant().isBefore(expiry)) throw new SecurityException("MODEL_INVOCATION_EXPIRED");
        var policy = planned.explorationPolicy();
        return registry.approve(new InvocationSpec(identity.invocationId(), index, configuration.modelRef(), configuration.modelVersion(),
                configuration.configurationHash(), policy.policyRef(), policy.policyVersion(), frozen.inputs().inputSetRef(),
                ModelInvocationRegistry.encodeRequest(request), inputs, expiry));
    }

    private Approval validateInvocation(Turn turn) {
        Approval approval = registry.approve(turn.invocation());
        if (!clock.instant().isBefore(turn.invocation().expiresAt())) throw new SecurityException("MODEL_INVOCATION_EXPIRED");
        for (ArtifactMetadata expected : turn.invocation().inputs().values()) requireArtifact(expected);
        var request = ModelInvocationRegistry.decodeRequest(turn.invocation().requestJson(), DECODE_LIMITS);
        if (!request.tools().equals(configuration.tools())) throw failure("EXPLORATION_TOOL_CONFIGURATION_CHANGED");
        return approval;
    }

    private Response response(Turn turn) {
        Response response = runs.readModelResponse(token, turn.modelChildId(), validateInvocation(turn), authorizer);
        String hash = CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response));
        if (turn.responseHash() != null && !turn.responseHash().equals(hash)) throw failure("EXPLORATION_MODEL_RESPONSE_CHANGED");
        if (turn.responseHash() == null) updateTurn(turn.index(), "response_hash=?", hash);
        return response;
    }

    private List<Message> history(Header header, List<Turn> rows) {
        if (header.input() == null) throw failure("EXPLORATION_INPUT_REQUIRED");
        List<Message> messages = new ArrayList<>(initialMessages(header.input()));
        for (Turn turn : rows) {
            if (!"OBSERVED".equals(turn.decision()) && !"REPAIR".equals(turn.decision())) break;
            Response response = response(turn);
            messages.add(new Message("assistant", response.text(), response.toolCalls(), null, null));
            if ("REPAIR".equals(turn.decision())) {
                for (ToolCall call : response.toolCalls()) messages.add(new Message("tool", "{\"executed\":false,\"code\":\"BATCH_REJECTED\"}", null, call.id(), call.name()));
            } else {
                if (response.toolCalls().size() != 1 || turn.artifactId() == null) throw failure("EXPLORATION_HISTORY_INVALID");
                requireReceipt(turn);
                ToolCall call = response.toolCalls().get(0);
                if (turn.pendingProjected()) {
                    if (turn.jobId() == null || turn.observationId() == null) throw failure("EXPLORATION_HISTORY_INVALID");
                    messages.add(new Message("tool", NativeExplorationAdapter.Observation.pending(turn.jobId()).json(), null, call.id(), call.name()));
                    var savedCall = calls.call(token, turn.callId()).orElseThrow();
                    messages.add(new Message("user", write(Map.of("type", "trusted_action_observation", "status", "READY",
                            "observationId", turn.observationId(), "actionId", savedCall.spec().actionId(),
                            "jobId", turn.jobId(), "artifactId", turn.artifactId())), null, null, null));
                } else messages.add(new Message("tool", NativeExplorationAdapter.Observation.ready(turn.artifactId()).json(), null, call.id(), call.name()));
            }
        }
        return List.copyOf(messages);
    }

    private List<Message> initialMessages(String input) {
        List<Message> messages = new ArrayList<>();
        if (configuration.systemPrompt() != null) messages.add(new Message("system", configuration.systemPrompt(), null, null, null));
        messages.add(new Message("user", input, null, null, null));
        return messages;
    }

    private Request request(List<Message> messages) { return new Request(ModelInvocationRegistry.REQUEST_SCHEMA, messages, configuration.tools()); }
    private static void sameRequest(Request expected, Request actual) {
        if (actual == null || !ModelInvocationRegistry.encodeRequest(expected).equals(ModelInvocationRegistry.encodeRequest(actual)))
            throw failure("MODEL_REQUEST_MISMATCH");
    }

    private Map<String, ArtifactMetadata> currentInputs(List<Turn> rows) {
        Map<String, ArtifactMetadata> inputs = new TreeMap<>();
        configuration.inputs().forEach((key, value) -> { requireArtifact(value); inputs.put(key, value); });
        for (Turn turn : rows) if (turn.artifactId() != null) {
            ArtifactMetadata metadata = requireReceipt(turn);
            String key = "observation-" + turn.index();
            if (inputs.putIfAbsent(key, metadata) != null) throw failure("EXPLORATION_INPUT_NAME_COLLISION");
        }
        return inputs;
    }

    private void requireArtifact(ArtifactMetadata expected) {
        if (!runs.readArtifact(token.definition().caller(), expected.ref().artifactId(), authorizer).metadata().equals(expected))
            throw new SecurityException("EXPLORATION_ARTIFACT_CHANGED");
    }

    private ArtifactMetadata requireReceipt(Turn turn) {
        if (turn.callId() == null || turn.receiptChild() == null || turn.artifactId() == null) throw failure("EXPLORATION_RECEIPT_REQUIRED");
        var call = calls.call(token, turn.callId()).orElseThrow();
        ChildRecord child = runs.child(token, turn.receiptChild()).orElseThrow();
        if (!child.spec().actionId().equals(call.spec().actionId()) || child.state() != ChildState.READY
                || !turn.artifactId().equals(child.artifactId())) throw failure("EXPLORATION_RECEIPT_CHANGED");
        ArtifactMetadata metadata = runs.readArtifact(token.definition().caller(), turn.artifactId(), authorizer).metadata();
        if (!metadata.runId().equals(runId()) || metadata.revision() != revision() || !metadata.planId().equals(token.definition().planId())
                || !metadata.actionId().equals(call.spec().actionId()) || !metadata.childId().equals(child.spec().childId())
                || !metadata.executorVersion().equals(call.spec().executor().version())) throw failure("EXPLORATION_ARTIFACT_PRODUCER_CHANGED");
        return metadata;
    }

    private void refresh() {
        Header header = header();
        if (header.status() == Status.FAILED || header.status() == Status.CANDIDATE) return;
        Turn turn = turn(header.turn()).orElse(null);
        if (turn == null || turn.callId() == null) return;
        CallRecord call = calls.call(token, turn.callId()).orElseThrow();
        if (call.callbackActive()) return;
        if ("OBSERVED".equals(turn.decision())) { requireReceipt(turn); return; }
        if (call.state() == CallState.PREPARED) return;
        if (turn.receiptChild() != null) {
            ChildRecord child = runs.child(token, turn.receiptChild()).orElseThrow();
            if (!child.spec().actionId().equals(call.spec().actionId())) throw failure("EXPLORATION_RECEIPT_CHANGED");
            if (child.state() == ChildState.READY) { saveReceipt(turn, child); return; }
            if (child.state() == ChildState.WAITING && Objects.equals(turn.jobId(), child.jobId())) { setStatus(Status.WAITING, ""); return; }
            setStatus(Status.BLOCKED, "EXPLORATION_RECEIPT_UNRESOLVED"); return;
        }
        List<ChildRecord> children = capabilityChildren(call.spec().actionId());
        if (children.size() == 1 && !children.get(0).callbackActive()
                && (children.get(0).state() == ChildState.READY || children.get(0).state() == ChildState.WAITING)) {
            saveReceipt(turn, children.get(0)); return;
        }
        // A callback's return is not a receipt; ambiguous multi-child output is never guessed.
        setStatus(Status.BLOCKED, children.size() > 1 ? "EXPLORATION_RECEIPT_AMBIGUOUS" : "CALL_RESULT_UNKNOWN");
    }

    private void saveReceipt(Turn turn, ChildRecord child) {
        var call = calls.call(token, turn.callId()).orElseThrow();
        if (!child.spec().actionId().equals(call.spec().actionId()) || child.spec().mode() == ChildMode.MODEL || child.callbackActive())
            throw failure("EXPLORATION_RECEIPT_INVALID");
        if (turn.receiptChild() != null && !turn.receiptChild().equals(child.spec().childId())) throw failure("EXPLORATION_RECEIPT_CHANGED");
        if (turn.jobId() != null && !turn.jobId().equals(child.jobId())) throw failure("EXPLORATION_JOB_CHANGED");
        if (child.state() == ChildState.READY) {
            if (turn.artifactId() != null && !turn.artifactId().equals(child.artifactId())) throw failure("EXPLORATION_RECEIPT_CHANGED");
            String observationId = "observation-" + CampaignRunStore.sha256(write(List.of(runId(), revision(), step.stepId(), turn.index(), child.spec().childId(), child.artifactId())));
            Turn checked = new Turn(turn.index(), turn.modelChildId(), turn.invocation(), "OBSERVED", turn.responseHash(), turn.callId(),
                    child.spec().childId(), child.artifactId(), child.jobId(), observationId, turn.pendingProjected(), turn.consumed(), turn.repairCounted());
            requireReceipt(checked);
            updateTurn(turn.index(), "decision='OBSERVED',receipt_child_id=?,artifact_id=?,job_id=?,observation_id=?",
                    child.spec().childId(), child.artifactId(), child.jobId(), observationId);
            setStatus(Status.ACTIVE, "");
        } else if (child.state() == ChildState.WAITING && child.jobId() != null) {
            updateTurn(turn.index(), "receipt_child_id=?,job_id=?", child.spec().childId(), child.jobId()); setStatus(Status.WAITING, "");
        } else throw failure("EXPLORATION_RECEIPT_NOT_READY");
    }

    private List<ChildRecord> capabilityChildren(String actionId) {
        return runs.children(token).stream().filter(child -> child.spec().actionId().equals(actionId) && child.spec().mode() != ChildMode.MODEL).toList();
    }

    private CallPermit permit(long index) {
        var matches = jdbc.query("SELECT call_id,call_attempt_id,call_attempt_version,call_step_attempt_id,call_step_attempt_version,"
                        + "call_run_version,call_run_token FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? AND turn_index=? FOR UPDATE",
                (rs, row) -> {
                    if (rs.getString("call_attempt_id") == null) throw failure("EXPLORATION_CALL_ATTEMPT_REQUIRED");
                    var savedToken = new RunToken(token.definition(), rs.getLong("call_run_version"), rs.getString("call_run_token"));
                    var savedStep = new StepPermit(savedToken, step.stepId(), rs.getString("call_step_attempt_id"), rs.getLong("call_step_attempt_version"));
                    var actionIds = jdbc.query("SELECT action_id FROM campaign_exploration_call WHERE run_id=? AND revision=? AND call_id=? FOR UPDATE",
                            (inner, number) -> inner.getString(1), runId(), revision(), rs.getString("call_id"));
                    if (actionIds.size() != 1) throw failure("EXPLORATION_CALL_NOT_FOUND");
                    return new CallPermit(savedStep, rs.getString("call_id"), actionIds.get(0), rs.getString("call_attempt_id"), rs.getLong("call_attempt_version"));
                }, runId(), revision(), step.stepId(), index);
        if (matches.size() != 1) throw failure("EXPLORATION_CALL_ATTEMPT_REQUIRED");
        return matches.get(0);
    }

    private void requireCurrent() {
        if (!steps.mayExecute(step)) throw new SecurityException("EXPLORATION_STEP_FENCED");
        var specification = steps.step(token, step.stepId()).orElseThrow().spec();
        try {
            if (!JSON.readTree(specification.definitionJson()).equals(JSON.readTree(stepJson))) throw failure("EXPLORATION_STEP_CHANGED");
        } catch (JsonProcessingException invalid) { throw failure("EXPLORATION_STEP_CHANGED"); }
    }

    private List<Header> headers() {
        return jdbc.query("SELECT * FROM campaign_exploration_session WHERE run_id=? AND revision=? AND step_id=? FOR UPDATE", (rs, row) -> {
            if (!configurationHash.equals(rs.getString("configuration_hash"))) throw failure("EXPLORATION_CONFIGURATION_CHANGED");
            String input = rs.getString("original_input");
            if (input != null && !CampaignRunStore.sha256(input).equals(rs.getString("input_hash"))) throw failure("EXPLORATION_INPUT_CORRUPTED");
            return new Header(Status.valueOf(rs.getString("session_status")), rs.getString("reason"), input, rs.getLong("current_turn"));
        }, runId(), revision(), step.stepId());
    }
    private Header header() { var rows = headers(); if (rows.size() != 1) throw failure("EXPLORATION_SESSION_NOT_FOUND"); return rows.get(0); }
    private List<Turn> turns() {
        return jdbc.query("SELECT * FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? ORDER BY turn_index FOR UPDATE",
                (rs, row) -> readTurn(rs), runId(), revision(), step.stepId());
    }
    private Optional<Turn> turn(long index) {
        return jdbc.query("SELECT * FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? AND turn_index=? FOR UPDATE",
                (rs, row) -> readTurn(rs), runId(), revision(), step.stepId(), index).stream().findFirst();
    }
    private Turn readTurn(ResultSet rs) throws SQLException {
        String encoded = rs.getString("invocation_json");
        if (!CampaignRunStore.sha256(encoded).equals(rs.getString("invocation_hash"))) throw failure("EXPLORATION_INVOCATION_CORRUPTED");
        InvocationSpec invocation = ModelInvocationRegistry.decode(encoded, DECODE_LIMITS);
        long index = rs.getLong("turn_index");
        var expected = ModelInvocationRegistry.identity(token.definition(), step.stepId(), index);
        if (index != invocation.turnIndex() || !expected.childId().equals(rs.getString("model_child_id"))
                || !expected.invocationId().equals(invocation.invocationId()) || !invocation.hash().equals(rs.getString("invocation_hash")))
            throw failure("EXPLORATION_INVOCATION_CORRUPTED");
        return new Turn(index, rs.getString("model_child_id"), invocation, rs.getString("decision"), rs.getString("response_hash"),
                rs.getString("call_id"), rs.getString("receipt_child_id"), rs.getString("artifact_id"), rs.getString("job_id"),
                rs.getString("observation_id"), rs.getBoolean("pending_projected"), rs.getBoolean("consumed"), rs.getBoolean("repair_counted"));
    }
    private void updateTurn(long index, String changes, Object... values) {
        List<Object> arguments = new ArrayList<>(java.util.Arrays.asList(values));
        arguments.add(clock.millis()); arguments.add(runId()); arguments.add(revision()); arguments.add(step.stepId()); arguments.add(index);
        if (jdbc.update("UPDATE campaign_exploration_turn SET " + changes + ",row_version=row_version+1,updated_at=? "
                        + "WHERE run_id=? AND revision=? AND step_id=? AND turn_index=?", arguments.toArray()) != 1)
            throw failure("EXPLORATION_TURN_CHANGED");
    }
    private void setStatus(Status status, String reason) {
        jdbc.update("UPDATE campaign_exploration_session SET session_status=?,reason=?,row_version=row_version+1,updated_at=? "
                        + "WHERE run_id=? AND revision=? AND step_id=?", status.name(), reason, clock.millis(), runId(), revision(), step.stepId());
    }
    private String runId() { return token.definition().runId(); }
    private int revision() { return token.definition().revision(); }
    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw failure("EXPLORATION_JSON_INVALID"); }
    }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
