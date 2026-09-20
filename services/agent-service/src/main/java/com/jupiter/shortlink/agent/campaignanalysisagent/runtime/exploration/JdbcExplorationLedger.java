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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCandidateStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore.InvocationRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcExplorationCallbackGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.io.IOException;
import java.io.OutputStream;
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
    private static final String TURN_COLUMNS = "turn_index,model_child_id,invocation_hash,decision,response_hash,call_id,"
            + "receipt_child_id,artifact_id,job_id,observation_id,pending_projected,consumed,repair_counted";

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

    /** A read-only scheduling hint; beginContinuation must still grant the actual new CALL permit. */
    public record PendingSkillContinuation(String callId, long invocationVersion) {}

    private record Header(Status status, String reason, String input, long turn) {}
    private record Turn(long index, String modelChildId, String invocationHash, String decision,
                        String responseHash, String callId, String receiptChild, String artifactId,
                        String jobId, String observationId, boolean pendingProjected, boolean consumed, boolean repairCounted,
                        String receiptKind, String skillCompletionId, String skillOutputsHash) {}
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
    private final ExplorationBudgetPolicy budgetPolicy;
    private final JdbcExplorationBudgetStore budgets;
    private final ExplorationArtifactProjection artifactProjection;
    private final String projectionConfigurationId;
    private final CampaignSkillInvocationStore skills;
    private final CampaignExplorationCandidateStore candidates;
    private final String candidateInstructions;
    private final ExplorationRepeatPolicy repeatPolicy;
    private final JdbcExplorationProgressStore progress;

    public JdbcExplorationLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            StepPermit step, ModelInvocationRegistry registry, ModelConfiguration configuration,
            Map<String, PlanSpec.ExecutorRef> executors, ArtifactAuthorizer authorizer) {
        this(jdbc, transactions, clock, runs, steps, calls, step, registry, configuration, executors, authorizer,
                ExplorationBudgetPolicy.defaults());
    }

    public JdbcExplorationLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            StepPermit step, ModelInvocationRegistry registry, ModelConfiguration configuration,
            Map<String, PlanSpec.ExecutorRef> executors, ArtifactAuthorizer authorizer,
            ExplorationBudgetPolicy budgetPolicy) {
        this(jdbc, transactions, clock, runs, steps, calls, step, registry, configuration, executors, authorizer,
                budgetPolicy, ExplorationArtifactProjection.references());
    }

    public JdbcExplorationLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            StepPermit step, ModelInvocationRegistry registry, ModelConfiguration configuration,
            Map<String, PlanSpec.ExecutorRef> executors, ArtifactAuthorizer authorizer,
            ExplorationBudgetPolicy budgetPolicy, ExplorationArtifactProjection artifactProjection) {
        this(jdbc, transactions, clock, runs, steps, calls, step, registry, configuration, executors, authorizer,
                budgetPolicy, artifactProjection, null);
    }

    public JdbcExplorationLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            StepPermit step, ModelInvocationRegistry registry, ModelConfiguration configuration,
            Map<String, PlanSpec.ExecutorRef> executors, ArtifactAuthorizer authorizer,
            ExplorationBudgetPolicy budgetPolicy, ExplorationArtifactProjection artifactProjection,
            CampaignSkillInvocationStore skills) {
        this(jdbc, transactions, clock, runs, steps, calls, step, registry, configuration, executors, authorizer,
                budgetPolicy, artifactProjection, skills, null);
    }

    public JdbcExplorationLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            StepPermit step, ModelInvocationRegistry registry, ModelConfiguration configuration,
            Map<String, PlanSpec.ExecutorRef> executors, ArtifactAuthorizer authorizer,
            ExplorationBudgetPolicy budgetPolicy, ExplorationArtifactProjection artifactProjection,
            CampaignSkillInvocationStore skills, CampaignExplorationCandidateStore candidates) {
        this(jdbc, transactions, clock, runs, steps, calls, step, registry, configuration, executors, authorizer,
                budgetPolicy, artifactProjection, skills, candidates, ExplorationRepeatPolicy.disabled());
    }

    public JdbcExplorationLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, CampaignExplorationCallStore calls,
            StepPermit step, ModelInvocationRegistry registry, ModelConfiguration configuration,
            Map<String, PlanSpec.ExecutorRef> executors, ArtifactAuthorizer authorizer,
            ExplorationBudgetPolicy budgetPolicy, ExplorationArtifactProjection artifactProjection,
            CampaignSkillInvocationStore skills, CampaignExplorationCandidateStore candidates,
            ExplorationRepeatPolicy repeatPolicy) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps); this.calls = Objects.requireNonNull(calls);
        this.step = Objects.requireNonNull(step); this.token = step.runToken();
        this.registry = Objects.requireNonNull(registry); this.configuration = Objects.requireNonNull(configuration);
        this.executors = Map.copyOf(executors); this.authorizer = Objects.requireNonNull(authorizer);
        this.budgetPolicy = Objects.requireNonNull(budgetPolicy);
        this.artifactProjection = Objects.requireNonNull(artifactProjection);
        this.skills = skills;
        this.candidates = candidates;
        this.repeatPolicy = Objects.requireNonNull(repeatPolicy);
        this.progress = repeatPolicy.enabled() ? new JdbcExplorationProgressStore(jdbc, transactions, clock, steps) : null;
        this.projectionConfigurationId = Objects.requireNonNull(artifactProjection.configurationId());
        if (projectionConfigurationId.isBlank() || projectionConfigurationId.length() > 512)
            throw failure("EXPLORATION_PROJECTION_CONFIGURATION_INVALID");
        this.budgets = new JdbcExplorationBudgetStore(jdbc, transactions, clock, budgetPolicy);
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
        // Reference-only sessions keep their original identity across this additive deployment.
        // Opt-in projections are pinned so a resume cannot silently replace already observed facts.
        String previousConfigurationHash = artifactProjection == ExplorationArtifactProjection.references()
                ? hash(List.of(configuration, new TreeMap<>(executors), stepJson))
                : hash(List.of(configuration, new TreeMap<>(executors), stepJson, projectionConfigurationId));
        String skillConfigurationHash = skills == null ? previousConfigurationHash
                : hash(List.of("skill-observations/v1", previousConfigurationHash));
        this.candidateInstructions = candidates == null ? null : candidateInstructions();
        String candidateConfigurationHash = candidates == null ? skillConfigurationHash
                : hash(List.of(ExplorationCandidate.SCHEMA_VERSION, "candidate-instructions/v1", skillConfigurationHash,
                        candidateInstructions,
                        candidates.configurationId(token.definition(), step.stepId())));
        this.configurationHash = progress == null ? candidateConfigurationHash
                : hash(List.of("exact-request-repeat/v1", candidateConfigurationHash, repeatPolicy.configurationId()));
        if (skills != null) jdbc.query("SELECT receipt_kind,skill_completion_id,skill_outputs_hash FROM campaign_exploration_turn WHERE 1=0",
                (rs, row) -> rs.getString(1));
        var definition = token.definition(); var owner = definition.caller();
        this.identity = new NativeExplorationAdapter.ExecutionKey(owner.tenantId(), owner.subject(), owner.authVersion(),
                definition.sessionId(), definition.runId(), definition.planId(), definition.revision(), step.stepId(),
                planned.explorationPolicy().policyVersion(), frozen.runnerVersion(), frozen.topologyVersion());
        tx(() -> {
            requireCurrent();
            budgets.initialize(step);
            if (progress != null) progress.initialize(step);
            var existing = headers();
            if (existing.isEmpty()) jdbc.update("INSERT INTO campaign_exploration_session (run_id,revision,step_id,configuration_hash,"
                            + "session_status,reason,current_turn,row_version,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE','',1,0,?,?)",
                    runId(), revision(), step.stepId(), configurationHash, clock.millis(), clock.millis());
            else header();
            return null;
        });
    }

    @Override public NativeExplorationAdapter.ExecutionKey identity() { return identity; }

    /**
     * Read the actual suspended Skill, without refreshing observations or creating a new attempt.
     * READY dependencies do not make the Skill complete. Its registered server continuation owns
     * the wait-contract comparison, source approval and atomic allocation of a new CALL attempt.
     */
    public Optional<PendingSkillContinuation> pendingSkillContinuation() {
        return tx(() -> {
            requireCurrent();
            if (skills == null) return Optional.empty();
            Header header = header();
            if (header.status() != Status.ACTIVE && header.status() != Status.WAITING) return Optional.empty();
            Turn current = turn(header.turn()).orElse(null);
            if (current == null || current.callId() == null || !"TOOL".equals(current.decision())) return Optional.empty();
            CallRecord call = calls.call(token, current.callId()).orElseThrow();
            if (call.spec().executor().kind() != PlanSpec.ExecutorKind.SKILL) return Optional.empty();
            requireSkillCall(current, call);
            if (call.state() != CallState.RETURNED || call.callbackActive() || call.revoked() || gate.hasActive(runId()))
                return Optional.empty();
            if (!jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE LIMIT 1 FOR UPDATE",
                    (rs, row) -> rs.getString(1), runId()).isEmpty()) return Optional.empty();
            InvocationRecord invocation = skills.invocation(token, current.callId()).orElseThrow(
                    () -> failure("EXPLORATION_SKILL_INVOCATION_REQUIRED"));
            if (invocation.state() != CampaignSkillInvocationStore.State.WAITING) return Optional.empty();
            var newerStep = jdbc.query("SELECT step_attempt_id,step_attempt_version FROM campaign_exploration_call "
                            + "WHERE run_id=? AND revision=? AND call_id=? FOR UPDATE",
                    (rs, row) -> !step.attemptId().equals(rs.getString("step_attempt_id"))
                            && step.attemptVersion() > rs.getLong("step_attempt_version"),
                    runId(), revision(), current.callId());
            if (newerStep.size() != 1 || !newerStep.get(0)) return Optional.empty();
            List<ChildRecord> children = capabilityChildren(call.spec().actionId());
            if (children.isEmpty() || children.stream().anyMatch(child -> child.state() != ChildState.READY
                    || child.callbackActive() || child.reason() != null)) return Optional.empty();
            // Validate the saved source, including current rights for every MODEL-visible input.
            Response source = runs.readModelResponse(token, current.modelChildId(), validateInvocation(current), authorizer);
            if (!call.spec().responseHash().equals(CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(source)))
                    || source.toolCalls().size() != 1
                    || !source.toolCalls().get(0).equals(new ToolCall(call.spec().toolCallId(),
                            call.spec().executor().name(), call.spec().arguments())))
                throw failure("EXPLORATION_SKILL_CALL_CHANGED");
            return Optional.of(new PendingSkillContinuation(current.callId(), invocation.rowVersion()));
        });
    }

    @Override public void freezeInput(String input) {
        if (input == null || input.isBlank()) throw failure("EXPLORATION_INPUT_REQUIRED");
        tx(() -> {
            requireCurrent(); Header header = header();
            if (header.input() != null && !header.input().equals(input)) throw failure("EXPLORATION_INPUT_CHANGED");
            if (budgetBlocked(header)) return null;
            if (header.input() == null) {
                // The same registered request bound applies before storing any original prompt.
                try { approve(1, request(initialMessages(input)), currentInputs(List.of())); }
                catch (ContextBudgetExceeded exhausted) { blockContext(); return null; }
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
            List<String> artifacts = new ArrayList<>(jdbc.query("SELECT artifact_id FROM campaign_exploration_turn WHERE run_id=? AND revision=? "
                            + "AND step_id=? AND artifact_id IS NOT NULL ORDER BY turn_index FOR UPDATE",
                    (rs, row) -> rs.getString(1), runId(), revision(), step.stepId()).stream().distinct().toList());
            if (skills != null) for (Turn turn : turns()) if ("SKILL".equals(turn.receiptKind()) && "OBSERVED".equals(turn.decision()))
                requireSkillReceipt(turn).outputs().values().forEach(output -> artifacts.add(output.artifactId()));
            List<String> jobs = header.status() == Status.WAITING
                    ? jdbc.query("SELECT job_id FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? AND turn_index=? FOR UPDATE",
                            (rs, row) -> rs.getString(1), runId(), revision(), step.stepId(), header.turn()) : List.of();
            String job = jobs.isEmpty() ? null : jobs.get(0);
            return new View(header.status(), header.reason(), artifacts.stream().distinct().toList(), job, gate.hasActive(runId()) ? 1 : 0);
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
        List<Message> result = tx(() -> {
            requireCurrent(); refresh(); Header header = header();
            if (budgetBlocked(header)) return null;
            if (header.input() == null) throw failure("EXPLORATION_INPUT_REQUIRED");
            try {
                List<Turn> rows = turns();
                currentInputs(rows);
                Turn current = turn(header.turn()).orElse(null);
                if (noProgress(header)) {
                    Message stopped = terminalCallResponseLocked(header).orElseThrow();
                    InvocationSpec invocation = validateInvocation(current).invocation();
                    ContextMessages messages = new ContextMessages();
                    ModelInvocationRegistry.decodeRequest(invocation.requestJson(), DECODE_LIMITS).messages().forEach(messages::add);
                    Response answer = response(current);
                    messages.add(new Message("assistant", answer.text(), answer.toolCalls(), null, null));
                    messages.add(stopped);
                    return messages.values();
                }
                if (current != null && ("MODEL".equals(current.decision()) || "TOOL".equals(current.decision()))) {
                    InvocationSpec invocation = validateInvocation(current).invocation();
                    return ModelInvocationRegistry.decodeRequest(invocation.requestJson(), DECODE_LIMITS).messages();
                }
                return history(header, rows);
            } catch (ContextBudgetExceeded exhausted) {
                // A projection-size failure must not erase an already committed stop receipt.
                if (noProgress(header)) throw failure("EXPLORATION_CONTEXT_BUDGET_EXHAUSTED");
                blockContext(); return null;
            }
        });
        if (result == null) throw failure("EXPLORATION_CONTEXT_BUDGET_EXHAUSTED");
        return result;
    }

    @Override public Response call(Request actualRequest, Supplier<Response> liveCall) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure("MODEL_CALL_REQUIRES_COMMITTED_PREPARATION");
        Objects.requireNonNull(liveCall);
        Binding binding = tx(() -> {
            try {
            requireCurrent(); refresh(); Header header = header();
            if (budgetBlocked(header)) return null;
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
                if (nextBinding == null) return null;
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
            Binding currentBinding = binding(current);
            sameRequest(ModelInvocationRegistry.decodeRequest(currentBinding.approval().invocation().requestJson(), DECODE_LIMITS), actualRequest);
            // Existing slots are free to replay, including legacy slots whose request trace is
            // filled on first reuse. The store rejects a changed body without charging again.
            var replay = budgets.reserveModel(step, current.index(), currentBinding.approval().invocation().requestJson());
            if (!replay.allowed()) { setStatus(Status.BLOCKED, replay.reason()); return null; }
            return currentBinding;
            } catch (ContextBudgetExceeded exhausted) {
                blockContext(); return null;
            }
        });
        if (binding == null) throw failure("EXPLORATION_BUDGET_EXHAUSTED");
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
            if (budgetBlocked(header)) return null;
            Response response = response(turn);
            if (response.toolCalls().size() != 1) throw failure("EXPLORATION_TOOL_BATCH_INVALID");
            ToolCall returned = response.toolCalls().get(0);
            ToolCall supplied = new ToolCall(input.toolCallId(), input.toolName(), input.arguments());
            if (!returned.equals(supplied) || !response.text().equals(input.assistantText())) throw failure("EXPLORATION_TOOL_CALL_CHANGED");
            PlanSpec.ExecutorRef executor = executors.get(returned.name());
            if (executor == null) throw failure("EXPLORATION_EXECUTOR_NOT_ALLOWED");
            if (noProgress(header)) { terminalCallResponseLocked(header).orElseThrow(); return null; }
            if (header.status() != Status.ACTIVE) throw failure("EXPLORATION_NOT_ACTIVE");
            String fingerprint = progress == null ? null : repeatFingerprint(executor, returned.arguments());
            // Only a new proposal is examined. Reopening the same PREPARED CALL or an accepted
            // asynchronous Skill never enters the exact-repeat gate.
            if (progress != null && turn.callId() == null && repeatPolicy.reusableExecutors().contains(executor)) {
                var prior = progress.observed(step, turn.index(), fingerprint);
                if (prior.isPresent()) {
                    var evidence = repeatEvidence(prior.get(), turn, executor, returned);
                    if (evidence.isPresent()) {
                        progress.stopped(step, turn.index(), fingerprint, turn.modelChildId(),
                                CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)), prior.get(), hash(evidence.get()));
                        setStatus(Status.BLOCKED, "EXPLORATION_NO_PROGRESS");
                        return null;
                    }
                }
            }
            var identity = CampaignExplorationCallStore.identity(token.definition(), step.stepId(), turn.modelChildId(), returned.id());
            var spec = new CallSpec(identity.callId(), identity.actionId(), step.stepId(), turn.modelChildId(),
                    CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)), returned.id(), executor, returned.arguments());
            var admission = budgets.reserveCall(step, turn.index());
            if (!admission.allowed()) { setStatus(Status.BLOCKED, admission.reason()); return null; }
            CallRecord prepared = calls.prepare(step, spec, binding(turn).approval(), authorizer);
            if (prepared.state() != CallState.PREPARED || prepared.revoked() || prepared.callbackActive())
                throw failure("EXPLORATION_CALL_NOT_REPLAYABLE");
            if (turn.callId() != null && !turn.callId().equals(spec.callId())) throw failure("EXPLORATION_CALL_CHANGED");
            updateTurn(turn.index(), "decision='TOOL',call_id=?", spec.callId());
            if (progress != null) progress.admitted(step, turn.index(), fingerprint, turn.modelChildId(), spec.responseHash(), spec.callId());
            return null;
        });
    }

    @Override public Optional<Message> terminalCallResponse() {
        return tx(() -> { requireCurrent(); return terminalCallResponseLocked(header()); });
    }

    private Optional<Message> terminalCallResponseLocked(Header header) {
        if (!noProgress(header)) return Optional.empty();
        if (progress == null) throw failure("EXPLORATION_PROGRESS_RECEIPT_REQUIRED");
        Turn current = turn(header.turn()).orElseThrow();
        var stop = progress.proposal(step, current.index()).orElseThrow(() -> failure("EXPLORATION_PROGRESS_RECEIPT_REQUIRED"));
        Response answer = response(current);
        if (!"MODEL".equals(current.decision()) || current.callId() != null || stop.decision() != JdbcExplorationProgressStore.Decision.STOP
                || !current.modelChildId().equals(stop.modelChildId()) || answer.toolCalls().size() != 1
                || !stop.responseHash().equals(CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(answer))))
            throw failure("EXPLORATION_PROGRESS_PROPOSAL_CHANGED");
        ToolCall proposed = answer.toolCalls().get(0);
        PlanSpec.ExecutorRef executor = executors.get(proposed.name());
        if (executor == null || !repeatPolicy.reusableExecutors().contains(executor)
                || !stop.fingerprint().equals(repeatFingerprint(executor, proposed.arguments())))
            throw failure("EXPLORATION_PROGRESS_PROPOSAL_CHANGED");
        var source = progress.proposal(step, stop.sourceTurnIndex()).orElseThrow();
        if (!Objects.equals(stop.sourceCallId(), source.callId()) || !stop.fingerprint().equals(source.fingerprint()))
            throw failure("EXPLORATION_PROGRESS_SOURCE_CHANGED");
        Map<String, ArtifactRef> outputs = repeatEvidence(source, current, executor, proposed)
                .orElseThrow(() -> failure("EXPLORATION_PROGRESS_EVIDENCE_NOT_VISIBLE"));
        if (!stop.outputsHash().equals(hash(outputs))) throw failure("EXPLORATION_PROGRESS_EVIDENCE_CHANGED");
        var message = new Message("tool", write(Map.of("executed", false, "code", "EXPLORATION_NO_PROGRESS",
                "sourceCallId", source.callId(), "outputs", outputs)), null, proposed.id(), proposed.name());
        encodedSize(message, budgetPolicy.maxContextBytes());
        return Optional.of(message);
    }

    private String repeatFingerprint(PlanSpec.ExecutorRef executor, String arguments) {
        // No turn/callback identity and no expanding MODEL.inputs prefix enters request identity.
        return hash(List.of(configurationHash, repeatPolicy.configurationId(), executor, arguments));
    }

    private Optional<Map<String, ArtifactRef>> repeatEvidence(JdbcExplorationProgressStore.Proposal source, Turn current,
                                                           PlanSpec.ExecutorRef executor, ToolCall proposed) {
        Turn observed = turn(source.turnIndex()).orElseThrow();
        if (source.decision() != JdbcExplorationProgressStore.Decision.ADMITTED || source.turnIndex() >= current.index()
                || !"OBSERVED".equals(observed.decision()) || !Objects.equals(observed.callId(), source.callId())
                || !observed.modelChildId().equals(source.modelChildId())) throw failure("EXPLORATION_PROGRESS_SOURCE_CHANGED");
        CallRecord call = calls.call(token, source.callId()).orElseThrow();
        Response answer = response(observed);
        if (call.state() != CallState.RETURNED || call.callbackActive() || call.revoked()
                || !executor.equals(call.spec().executor()) || !step.stepId().equals(call.spec().stepId())
                || !observed.modelChildId().equals(call.spec().modelChildId()) || !proposed.arguments().equals(call.spec().arguments())
                || !source.responseHash().equals(CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(answer)))
                || !source.responseHash().equals(call.spec().responseHash()) || answer.toolCalls().size() != 1
                || !answer.toolCalls().get(0).equals(new ToolCall(call.spec().toolCallId(), executor.name(), call.spec().arguments()))
                || !source.fingerprint().equals(repeatFingerprint(executor, proposed.arguments())))
            throw failure("EXPLORATION_PROGRESS_SOURCE_CHANGED");
        Map<String, ArtifactMetadata> evidence = new TreeMap<>();
        if ("SKILL".equals(observed.receiptKind())) {
            var completion = requireSkillReceipt(observed);
            for (var entry : completion.outputs().entrySet()) {
                var metadata = runs.readArtifact(token.definition().caller(), entry.getValue().artifactId(), authorizer).metadata();
                if (!metadata.ref().equals(entry.getValue())) throw failure("EXPLORATION_PROGRESS_EVIDENCE_CHANGED");
                evidence.put(entry.getKey(), metadata);
            }
        } else {
            var children = capabilityChildren(call.spec().actionId());
            if (children.size() != 1 || children.get(0).state() != ChildState.READY || children.get(0).callbackActive()
                    || children.get(0).reason() != null) throw failure("EXPLORATION_PROGRESS_SOURCE_INCOMPLETE");
            evidence.put("artifact", requireReceipt(observed));
        }
        if (evidence.isEmpty()) throw failure("EXPLORATION_PROGRESS_SOURCE_INCOMPLETE");
        var visible = validateInvocation(current).invocation().inputs().values();
        if (!visible.containsAll(evidence.values())) return Optional.empty();
        Map<String, ArtifactRef> outputs = new TreeMap<>();
        evidence.forEach((name, metadata) -> outputs.put(name, metadata.ref()));
        return Optional.of(Map.copyOf(outputs));
    }

    private static boolean noProgress(Header header) {
        return header.status() == Status.BLOCKED && "EXPLORATION_NO_PROGRESS".equals(header.reason());
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
            Turn turn = turn(attempt).orElseThrow();
            if (observation.skillCallId() != null) {
                recordSkillObservation(turn, permit, observation);
                return null;
            }
            if (skills != null && calls.call(token, permit.callId()).orElseThrow().spec().executor().kind() == PlanSpec.ExecutorKind.SKILL)
                throw failure("EXPLORATION_SKILL_RECEIPT_REQUIRED");
            if (!calls.mayExecute(permit)) throw failure("EXPLORATION_CALL_FENCED");
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
            requireCurrent(); Header header = header();
            if (budgetBlocked(header)) return false;
            Turn turn = turn(header.turn()).orElseThrow();
            if (response(turn).toolCalls().size() <= 1) throw failure("EXPLORATION_REPAIR_NOT_REQUIRED");
            var admission = budgets.reserveRepair(step, turn.index());
            if (!admission.allowed()) { setStatus(Status.BLOCKED, admission.reason()); return false; }
            if (!turn.repairCounted()) updateTurn(turn.index(), "decision='REPAIR',repair_counted=TRUE", new Object[0]);
            // The durable server profile owns this allowance, not a reconstructed adapter's P0 limit.
            return true;
        });
    }

    @Override public void candidate() {
        tx(() -> {
            requireCurrent(); Turn turn = turn(header().turn()).orElseThrow();
            if (!response(turn).toolCalls().isEmpty() || gate.hasActive(runId())) throw failure("EXPLORATION_CANDIDATE_INVALID");
            updateTurn(turn.index(), "decision='FINAL'", new Object[0]); setStatus(Status.CANDIDATE, "");
            if (candidates != null) {
                var assessment = candidates.assess(step, turn.modelChildId());
                String reason = switch (assessment.verdict()) {
                    case COMPLETE -> null;
                    case NEEDS_INPUT -> "EXPLORATION_NEEDS_INPUT";
                    case REPLAN_REQUESTED -> "EXPLORATION_REPLAN_REQUESTED";
                    case NO_PROGRESS_REPORTED -> "EXPLORATION_NO_PROGRESS_REPORTED";
                    case REJECTED -> "EXPLORATION_CANDIDATE_REJECTED";
                };
                if (reason != null) setStatus(Status.BLOCKED, reason);
            }
            return null;
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
        var admission = budgets.reserveModel(step, index, approval.invocation().requestJson());
        if (!admission.allowed()) { setStatus(Status.BLOCKED, admission.reason()); return null; }
        runs.prepareModelChild(step, action, child, approval, authorizer);
        jdbc.update("INSERT INTO campaign_exploration_turn (run_id,revision,step_id,turn_index,model_child_id,invocation_hash,"
                        + "decision,pending_projected,consumed,native_acknowledged,repair_counted,row_version,created_at,updated_at) VALUES (?,?,?,?,?,?,'MODEL',FALSE,FALSE,FALSE,FALSE,0,?,?)",
                runId(), revision(), step.stepId(), index, child.childId(), approval.invocation().hash(), clock.millis(), clock.millis());
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
        requireContext(request);
        var identity = ModelInvocationRegistry.identity(token.definition(), step.stepId(), index);
        Instant expiry = configuration.expiresAt();
        for (ArtifactMetadata input : inputs.values()) if (input.ref().expiresAt().isBefore(expiry)) expiry = input.ref().expiresAt();
        if (!clock.instant().isBefore(expiry)) throw new SecurityException("MODEL_INVOCATION_EXPIRED");
        var policy = planned.explorationPolicy();
        String encodedRequest = ModelInvocationRegistry.encodeRequest(request);
        if (!budgets.checkContext(step, encodedRequest).allowed()) throw new ContextBudgetExceeded();
        return registry.approve(new InvocationSpec(identity.invocationId(), index, configuration.modelRef(), configuration.modelVersion(),
                configuration.configurationHash(), policy.policyRef(), policy.policyVersion(), frozen.inputs().inputSetRef(),
                encodedRequest, inputs, expiry));
    }

    private Approval validateInvocation(Turn turn) {
        ChildRecord child = runs.child(token, turn.modelChildId()).orElseThrow();
        var identity = ModelInvocationRegistry.identity(token.definition(), step.stepId(), turn.index());
        InvocationSpec invocation = child.spec().modelInvocation();
        if (child.spec().mode() != ChildMode.MODEL || invocation == null
                || !identity.childId().equals(child.spec().childId()) || !identity.actionId().equals(child.spec().actionId())
                || !identity.requestId().equals(child.spec().requestId()) || turn.index() != invocation.turnIndex()
                || !identity.invocationId().equals(invocation.invocationId()) || !turn.invocationHash().equals(invocation.hash()))
            throw failure("EXPLORATION_INVOCATION_CORRUPTED");
        requireEncodedContext(invocation.requestJson());
        Approval approval = registry.approve(invocation);
        if (!clock.instant().isBefore(invocation.expiresAt())) throw new SecurityException("MODEL_INVOCATION_EXPIRED");
        for (ArtifactMetadata expected : invocation.inputs().values()) requireArtifact(expected);
        var request = ModelInvocationRegistry.decodeRequest(invocation.requestJson(), DECODE_LIMITS);
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
        ContextMessages messages = new ContextMessages();
        initialMessages(header.input()).forEach(messages::add);
        for (Turn turn : rows) {
            if (!"OBSERVED".equals(turn.decision()) && !"REPAIR".equals(turn.decision())) break;
            Response response = response(turn);
            messages.add(new Message("assistant", response.text(), response.toolCalls(), null, null));
            if ("REPAIR".equals(turn.decision())) {
                for (ToolCall call : response.toolCalls()) messages.add(new Message("tool", "{\"executed\":false,\"code\":\"BATCH_REJECTED\"}", null, call.id(), call.name()));
            } else {
                if ("SKILL".equals(turn.receiptKind())) {
                    skillHistory(messages, turn, response);
                    continue;
                }
                if (response.toolCalls().size() != 1 || turn.artifactId() == null) throw failure("EXPLORATION_HISTORY_INVALID");
                ArtifactMetadata receipt = requireReceipt(turn);
                Map<String, Object> evidence = projectEvidence(receipt);
                ToolCall call = response.toolCalls().get(0);
                if (turn.pendingProjected()) {
                    if (turn.jobId() == null || turn.observationId() == null) throw failure("EXPLORATION_HISTORY_INVALID");
                    messages.add(new Message("tool", NativeExplorationAdapter.Observation.pending(turn.jobId()).json(), null, call.id(), call.name()));
                    var savedCall = calls.call(token, turn.callId()).orElseThrow();
                    Map<String, Object> ready = new LinkedHashMap<>(Map.of("type", "trusted_action_observation", "status", "READY",
                            "observationId", turn.observationId(), "actionId", savedCall.spec().actionId(),
                            "jobId", turn.jobId(), "artifactId", turn.artifactId()));
                    if (!evidence.isEmpty()) ready.put("evidence", evidence);
                    messages.add(new Message("user", write(ready), null, null, null));
                } else {
                    Map<String, Object> ready = new LinkedHashMap<>(Map.of("status", "READY", "artifactId", turn.artifactId()));
                    if (!evidence.isEmpty()) ready.put("evidence", evidence);
                    String observation = evidence.isEmpty()
                            ? NativeExplorationAdapter.Observation.ready(turn.artifactId()).json() : write(ready);
                    messages.add(new Message("tool", observation, null, call.id(), call.name()));
                }
            }
        }
        return messages.values();
    }

    private Map<String, Object> projectEvidence(ArtifactMetadata receipt) {
        if (artifactProjection == ExplorationArtifactProjection.references()) return Map.of();
        if (!projectionConfigurationId.equals(artifactProjection.configurationId()))
            throw failure("EXPLORATION_PROJECTION_CONFIGURATION_CHANGED");
        Map<String, Object> evidence = Objects.requireNonNull(
                artifactProjection.project(token.definition().caller(), receipt, authorizer));
        // The projector owns its per-artifact bound; the full request still uses ContextMessages.
        // Values are data in the existing tool/user observation, never promoted to system policy.
        requireArtifact(receipt);
        return evidence;
    }

    private List<Message> initialMessages(String input) {
        List<Message> messages = new ArrayList<>();
        String system = configuration.systemPrompt();
        if (candidateInstructions != null) system = system == null ? candidateInstructions : system + "\n\n" + candidateInstructions;
        if (system != null) messages.add(new Message("system", system, null, null, null));
        messages.add(new Message("user", input, null, null, null));
        return messages;
    }

    private String candidateInstructions() {
        var specification = steps.step(token, step.stepId()).orElseThrow().spec();
        var policy = planned.explorationPolicy();
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("outputContractRef", planned.outputContractRef());
        contract.put("allowedOutputPorts", specification.allowedOutputs().stream().sorted().toList());
        contract.put("requiredOutputPorts", specification.requiredOutputs().stream().sorted().toList());
        contract.put("completionCriteria", policy.completionCriteria());
        contract.put("scopeRef", policy.scopeRef());
        contract.put("periodsRef", policy.periodsRef());
        return ExplorationCandidate.instructions() + "\n\nFrozen server completion contract:\n" + write(contract);
    }

    private Request request(List<Message> messages) { return new Request(ModelInvocationRegistry.REQUEST_SCHEMA, messages, configuration.tools()); }
    private void sameRequest(Request expected, Request actual) {
        requireContext(expected);
        if (actual == null || !expected.equals(actual))
            throw failure("MODEL_REQUEST_MISMATCH");
    }

    private static boolean budgetBlocked(Header header) {
        return header.status() == Status.BLOCKED && header.reason() != null
                && header.reason().startsWith("EXPLORATION_") && header.reason().endsWith("_BUDGET_EXHAUSTED");
    }

    private void blockContext() { setStatus(Status.BLOCKED, "EXPLORATION_CONTEXT_BUDGET_EXHAUSTED"); }

    private void requireContext(Request request) { encodedSize(request, budgetPolicy.maxContextBytes()); }

    private void requireEncodedContext(String request) {
        var counter = new ContextCounter(budgetPolicy.maxContextBytes());
        try (var writer = new java.io.OutputStreamWriter(counter, java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(request);
        } catch (IOException invalid) {
            if (counter.exceeded) throw new ContextBudgetExceeded();
            throw failure("EXPLORATION_JSON_INVALID");
        }
    }

    /** Count actual escaped UTF-8 bytes without allocating an encoded request to find its size. */
    private static long encodedSize(Object value, long limit) {
        var counter = new ContextCounter(limit);
        try { JSON.writeValue(counter, value); }
        catch (IOException invalid) {
            if (counter.exceeded) throw new ContextBudgetExceeded();
            throw failure("EXPLORATION_JSON_INVALID");
        }
        return counter.bytes;
    }

    private final class ContextMessages {
        private final List<Message> messages = new ArrayList<>();
        private long bytes = encodedSize(Map.of("schemaVersion", ModelInvocationRegistry.REQUEST_SCHEMA,
                "messages", List.of(), "tools", configuration.tools()), budgetPolicy.maxContextBytes());

        private void add(Message message) {
            long comma = messages.isEmpty() ? 0 : 1;
            long remaining = budgetPolicy.maxContextBytes() - bytes;
            if (remaining < comma) throw new ContextBudgetExceeded();
            long size = encodedSize(message, remaining - comma);
            bytes += comma + size;
            messages.add(message);
        }

        private List<Message> values() { return List.copyOf(messages); }
    }

    private static final class ContextBudgetExceeded extends RuntimeException { }

    private static final class ContextCounter extends OutputStream {
        private final long limit;
        private long bytes;
        private boolean exceeded;
        private ContextCounter(long limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException { count(1); }
        @Override public void write(byte[] values, int offset, int length) throws IOException { count(length); }
        private void count(int length) throws IOException {
            if (length > limit - bytes) { exceeded = true; throw new IOException("EXPLORATION_CONTEXT_BUDGET_EXHAUSTED"); }
            bytes += length;
        }
    }

    private Map<String, ArtifactMetadata> currentInputs(List<Turn> rows) {
        Map<String, ArtifactMetadata> inputs = new TreeMap<>();
        configuration.inputs().forEach((key, value) -> { requireArtifact(value); inputs.put(key, value); });
        for (Turn turn : rows) if (turn.artifactId() != null) {
            ArtifactMetadata metadata = requireReceipt(turn);
            String key = "observation-" + turn.index();
            if (inputs.putIfAbsent(key, metadata) != null) throw failure("EXPLORATION_INPUT_NAME_COLLISION");
        } else if ("SKILL".equals(turn.receiptKind()) && "OBSERVED".equals(turn.decision())) {
            InvocationRecord receipt = requireSkillReceipt(turn);
            for (var entry : receipt.outputs().entrySet()) {
                ArtifactMetadata metadata = runs.readArtifact(token.definition().caller(), entry.getValue().artifactId(), authorizer).metadata();
                if (!metadata.ref().equals(entry.getValue())) throw failure("EXPLORATION_SKILL_OUTPUT_CHANGED");
                String key = "observation-" + turn.index() + "-" + entry.getKey();
                if (inputs.putIfAbsent(key, metadata) != null) throw failure("EXPLORATION_INPUT_NAME_COLLISION");
            }
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

    private void recordSkillObservation(Turn turn, CallPermit permit, NativeExplorationAdapter.Observation observation) {
        if (skills == null || !permit.step().equals(step) || !permit.callId().equals(observation.skillCallId())
                || !permit.callId().equals(turn.callId()) || observation.artifactId() != null || observation.jobId() != null)
            throw failure("EXPLORATION_SKILL_RECEIPT_INVALID");
        CallRecord call = calls.call(token, permit.callId()).orElseThrow();
        requireSkillCall(turn, call);
        // await/complete already record RETURNED in the invocation transaction. This is only
        // observation admission for that same still-active callback, never dispatch admission.
        var exact = jdbc.query("SELECT call_state,callback_active,revoked,action_id,attempt_id,attempt_version,"
                        + "step_attempt_id,step_attempt_version,dispatch_run_version,dispatch_run_token "
                        + "FROM campaign_exploration_call WHERE run_id=? AND revision=? AND call_id=? FOR UPDATE",
                (rs, row) -> "RETURNED".equals(rs.getString("call_state")) && rs.getBoolean("callback_active") && !rs.getBoolean("revoked")
                        && permit.actionId().equals(rs.getString("action_id")) && permit.attemptId().equals(rs.getString("attempt_id"))
                        && permit.attemptVersion() == rs.getLong("attempt_version")
                        && step.attemptId().equals(rs.getString("step_attempt_id")) && step.attemptVersion() == rs.getLong("step_attempt_version")
                        && token.version() == rs.getLong("dispatch_run_version") && token.advanceToken().equals(rs.getString("dispatch_run_token")),
                runId(), revision(), permit.callId());
        if (exact.size() != 1 || !exact.get(0)) throw failure("EXPLORATION_CALL_FENCED");
        InvocationRecord invocation = skills.invocation(token, turn.callId()).orElseThrow(() -> failure("EXPLORATION_SKILL_INVOCATION_REQUIRED"));
        if (observation.skillPending()) {
            if (invocation.state() != CampaignSkillInvocationStore.State.WAITING || "OBSERVED".equals(turn.decision()))
                throw failure("EXPLORATION_SKILL_NOT_WAITING");
            saveSkillWaiting(turn, true);
        } else {
            if (invocation.state() != CampaignSkillInvocationStore.State.COMPLETED) throw failure("EXPLORATION_SKILL_NOT_COMPLETE");
            saveSkillReady(turn, skills.readCompletion(token, turn.callId(), authorizer));
        }
    }

    private void requireSkillCall(Turn turn, CallRecord call) {
        if (skills == null || call.spec().executor().kind() != PlanSpec.ExecutorKind.SKILL
                || !call.spec().callId().equals(turn.callId()) || !call.spec().modelChildId().equals(turn.modelChildId())
                || !call.spec().stepId().equals(step.stepId()) || !call.spec().responseHash().equals(turn.responseHash()))
            throw failure("EXPLORATION_SKILL_CALL_CHANGED");
    }

    private InvocationRecord requireSkillReceipt(Turn turn) {
        if (skills == null || !"SKILL".equals(turn.receiptKind()) || !"OBSERVED".equals(turn.decision())
                || turn.skillCompletionId() == null || turn.skillOutputsHash() == null || turn.callId() == null
                || turn.receiptChild() != null || turn.artifactId() != null || turn.jobId() != null)
            throw failure("EXPLORATION_SKILL_RECEIPT_REQUIRED");
        requireSkillCall(turn, calls.call(token, turn.callId()).orElseThrow());
        InvocationRecord actual = skills.readCompletion(token, turn.callId(), authorizer);
        if (!turn.skillCompletionId().equals(actual.completionId()) || !turn.skillOutputsHash().equals(hash(actual.outputs())))
            throw failure("EXPLORATION_SKILL_RECEIPT_CHANGED");
        return actual;
    }

    private void refreshSkill(Turn turn, CallRecord call) {
        requireSkillCall(turn, call);
        if (call.state() == CallState.PREPARED) return;
        if (call.revoked() || call.state() != CallState.RETURNED) {
            setStatus(Status.BLOCKED, "EXPLORATION_SKILL_RESULT_UNRESOLVED");
            return;
        }
        if ("OBSERVED".equals(turn.decision())) { requireSkillReceipt(turn); return; }
        InvocationRecord invocation = skills.invocation(token, turn.callId()).orElse(null);
        if (invocation == null) { setStatus(Status.BLOCKED, "EXPLORATION_SKILL_INVOCATION_REQUIRED"); return; }
        if (invocation.state() == CampaignSkillInvocationStore.State.WAITING) {
            // READY remote dependencies authorize the server's continuation, not another model turn.
            saveSkillWaiting(turn, false);
        } else if (invocation.state() == CampaignSkillInvocationStore.State.COMPLETED) {
            saveSkillReady(turn, skills.readCompletion(token, turn.callId(), authorizer));
        } else setStatus(Status.BLOCKED, "EXPLORATION_SKILL_RESULT_UNRESOLVED");
    }

    private void saveSkillWaiting(Turn turn, boolean projected) {
        if (turn.receiptChild() != null || turn.artifactId() != null || turn.jobId() != null
                || turn.skillCompletionId() != null || turn.skillOutputsHash() != null)
            throw failure("EXPLORATION_SKILL_RECEIPT_CHANGED");
        if (!"SKILL".equals(turn.receiptKind()) || (projected && !turn.pendingProjected()))
            updateTurn(turn.index(), "receipt_kind='SKILL',pending_projected=?", projected || turn.pendingProjected());
        if (header().status() != Status.WAITING) setStatus(Status.WAITING, "");
    }

    private void saveSkillReady(Turn turn, InvocationRecord invocation) {
        if (turn.receiptChild() != null || turn.artifactId() != null || turn.jobId() != null)
            throw failure("EXPLORATION_SKILL_RECEIPT_CHANGED");
        String outputsHash = hash(invocation.outputs());
        if ((turn.skillCompletionId() != null && !turn.skillCompletionId().equals(invocation.completionId()))
                || (turn.skillOutputsHash() != null && !turn.skillOutputsHash().equals(outputsHash)))
            throw failure("EXPLORATION_SKILL_RECEIPT_CHANGED");
        String observationId = "observation-" + hash(List.of(runId(), revision(), step.stepId(), turn.index(), turn.callId(), invocation.completionId()));
        updateTurn(turn.index(), "decision='OBSERVED',receipt_kind='SKILL',skill_completion_id=?,skill_outputs_hash=?,observation_id=?",
                invocation.completionId(), outputsHash, observationId);
        setStatus(Status.ACTIVE, "");
    }

    private void skillHistory(ContextMessages messages, Turn turn, Response response) {
        if (response.toolCalls().size() != 1 || turn.observationId() == null) throw failure("EXPLORATION_HISTORY_INVALID");
        InvocationRecord receipt = requireSkillReceipt(turn);
        CallRecord savedCall = calls.call(token, turn.callId()).orElseThrow();
        ToolCall tool = response.toolCalls().get(0);
        if (!tool.id().equals(savedCall.spec().toolCallId()) || !tool.name().equals(savedCall.spec().executor().name()))
            throw failure("EXPLORATION_SKILL_CALL_CHANGED");
        Map<String, Object> ready = new LinkedHashMap<>(Map.of("status", "READY", "skillCallId", turn.callId(),
                "completionId", receipt.completionId(), "outputs", receipt.outputs()));
        Map<String, Object> evidence = new TreeMap<>();
        for (var entry : receipt.outputs().entrySet()) {
            ArtifactMetadata metadata = runs.readArtifact(token.definition().caller(), entry.getValue().artifactId(), authorizer).metadata();
            if (!entry.getValue().equals(metadata.ref())) throw failure("EXPLORATION_SKILL_OUTPUT_CHANGED");
            Map<String, Object> projected = projectEvidence(metadata);
            if (!projected.isEmpty()) evidence.put(entry.getKey(), projected);
        }
        if (!evidence.isEmpty()) ready.put("evidence", evidence);
        if (turn.pendingProjected()) {
            messages.add(new Message("tool", NativeExplorationAdapter.Observation.skillPending(turn.callId()).json(), null, tool.id(), tool.name()));
            ready.put("type", "trusted_action_observation");
            ready.put("observationId", turn.observationId());
            ready.put("actionId", savedCall.spec().actionId());
            messages.add(new Message("user", write(ready), null, null, null));
        } else messages.add(new Message("tool", write(ready), null, tool.id(), tool.name()));
    }

    private void refresh() {
        Header header = header();
        if (noProgress(header)) {
            // A stopped session still exposes evidence references; every fresh projection must
            // revalidate the original receipt instead of treating STOP as cached authorization.
            terminalCallResponseLocked(header).orElseThrow();
            return;
        }
        if (header.status() == Status.FAILED || header.status() == Status.CANDIDATE || budgetBlocked(header)) return;
        Turn turn = turn(header.turn()).orElse(null);
        if (turn == null || turn.callId() == null) return;
        CallRecord call = calls.call(token, turn.callId()).orElseThrow();
        if (call.callbackActive()) return;
        if (skills != null && call.spec().executor().kind() == PlanSpec.ExecutorKind.SKILL) {
            refreshSkill(turn, call);
            return;
        }
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
            Turn checked = new Turn(turn.index(), turn.modelChildId(), turn.invocationHash(), "OBSERVED", turn.responseHash(), turn.callId(),
                    child.spec().childId(), child.artifactId(), child.jobId(), observationId, turn.pendingProjected(), turn.consumed(), turn.repairCounted(),
                    "ARTIFACT", null, null);
            requireReceipt(checked);
            updateTurn(turn.index(), "decision='OBSERVED',receipt_child_id=?,artifact_id=?,job_id=?,observation_id=?",
                    child.spec().childId(), child.artifactId(), child.jobId(), observationId);
            setStatus(Status.ACTIVE, "");
        } else if (child.state() == ChildState.WAITING && child.jobId() != null) {
            updateTurn(turn.index(), "receipt_child_id=?,job_id=?", child.spec().childId(), child.jobId()); setStatus(Status.WAITING, "");
        } else throw failure("EXPLORATION_RECEIPT_NOT_READY");
    }

    private List<ChildRecord> capabilityChildren(String actionId) {
        // Do not decode every prior MODEL request just to locate this callback's business receipt.
        return jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? AND revision=? AND action_id=? "
                        + "AND child_mode<>'MODEL' ORDER BY child_id FOR UPDATE", (rs, row) -> rs.getString(1),
                runId(), revision(), actionId).stream().map(id -> runs.child(token, id).orElseThrow()).toList();
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
        return jdbc.query("SELECT " + turnColumns() + " FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? ORDER BY turn_index FOR UPDATE",
                (rs, row) -> readTurn(rs), runId(), revision(), step.stepId());
    }
    private Optional<Turn> turn(long index) {
        return jdbc.query("SELECT " + turnColumns() + " FROM campaign_exploration_turn WHERE run_id=? AND revision=? AND step_id=? AND turn_index=? FOR UPDATE",
                (rs, row) -> readTurn(rs), runId(), revision(), step.stepId(), index).stream().findFirst();
    }
    private Turn readTurn(ResultSet rs) throws SQLException {
        long index = rs.getLong("turn_index");
        var expected = ModelInvocationRegistry.identity(token.definition(), step.stepId(), index);
        String hash = rs.getString("invocation_hash");
        if (!expected.childId().equals(rs.getString("model_child_id")) || hash == null || !hash.matches("[a-f0-9]{64}"))
            throw failure("EXPLORATION_INVOCATION_CORRUPTED");
        return new Turn(index, rs.getString("model_child_id"), hash, rs.getString("decision"), rs.getString("response_hash"),
                rs.getString("call_id"), rs.getString("receipt_child_id"), rs.getString("artifact_id"), rs.getString("job_id"),
                rs.getString("observation_id"), rs.getBoolean("pending_projected"), rs.getBoolean("consumed"), rs.getBoolean("repair_counted"),
                skills == null ? "ARTIFACT" : rs.getString("receipt_kind"),
                skills == null ? null : rs.getString("skill_completion_id"), skills == null ? null : rs.getString("skill_outputs_hash"));
    }
    private String turnColumns() {
        return skills == null ? TURN_COLUMNS : TURN_COLUMNS + ",receipt_kind,skill_completion_id,skill_outputs_hash";
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
    private static String hash(Object value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var output = new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                JSON.writeValue(output, value);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException invalid) {
            throw failure("EXPLORATION_JSON_INVALID");
        }
    }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
