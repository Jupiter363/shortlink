package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec.ExecutorKind;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Approval;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable invocation facts only. Does not schedule a Skill or re-enter the native model loop. */
public final class JdbcCampaignSkillInvocationStore implements CampaignSkillInvocationStore {
    private static final JsonMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CapabilityCatalog catalog;
    private final ArtifactContractRegistry contracts;
    private final Limits limits;
    private final JdbcCampaignRunStore runs;
    private final JdbcCampaignExplorationCallStore calls;
    private final JdbcExplorationCallbackGate gate;
    private final boolean capacityWaits;

    public JdbcCampaignSkillInvocationStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                             CapabilityCatalog catalog, ArtifactContractRegistry contracts) {
        this(jdbc, transactions, clock, catalog, contracts, Limits.defaults());
    }

    public JdbcCampaignSkillInvocationStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                             CapabilityCatalog catalog, ArtifactContractRegistry contracts, Limits limits) {
        this(jdbc, transactions, clock, catalog, contracts, limits, false);
    }

    public JdbcCampaignSkillInvocationStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                             CapabilityCatalog catalog, ArtifactContractRegistry contracts, boolean capacityWaits) {
        this(jdbc, transactions, clock, catalog, contracts, Limits.defaults(), capacityWaits);
    }

    public JdbcCampaignSkillInvocationStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                             CapabilityCatalog catalog, ArtifactContractRegistry contracts, Limits limits,
                                             boolean capacityWaits) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.catalog = Objects.requireNonNull(catalog);
        this.contracts = Objects.requireNonNull(contracts);
        this.limits = Objects.requireNonNull(limits);
        this.capacityWaits = capacityWaits;
        // The existing store also verifies a shared, writable REQUIRED DataSource transaction.
        this.calls = new JdbcCampaignExplorationCallStore(jdbc, transactions, clock, limits);
        this.runs = new JdbcCampaignRunStore(jdbc, transactions, clock, limits);
        this.gate = new JdbcExplorationCallbackGate(jdbc);
        jdbc.query("SELECT call_id FROM campaign_skill_invocation WHERE 1=0", (rs, row) -> rs.getString(1));
        jdbc.query("SELECT child_id FROM campaign_skill_wait_child WHERE 1=0", (rs, row) -> rs.getString(1));
        jdbc.query("SELECT call_id FROM campaign_skill_call_attempt WHERE 1=0", (rs, row) -> rs.getString(1));
        if (capacityWaits) jdbc.query("SELECT dependency_kind,capacity_kind,rejected_attempts,retry_not_before "
                + "FROM campaign_skill_wait_child WHERE 1=0", (rs, row) -> rs.getString(1));
    }

    @Override public InvocationRecord prepare(CallPermit permit, CompletionSpec spec, Approval sourceModel,
                                               ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(spec); Objects.requireNonNull(sourceModel); Objects.requireNonNull(authorizer);
        String body = encode(spec, limits.definitionBytes());
        return transaction(() -> {
            CallRecord call = current(permit);
            gate.requireParent(permit, permit.actionId());
            calls.validateSource(permit.step(), call.spec(), sourceModel, authorizer);
            validateContract(call.spec(), spec);
            Row existing = find(permit.step().runToken(), permit.callId()).orElse(null);
            if (existing != null) {
                require(existing.record().spec().equals(spec) && existing.callHash().equals(call.spec().hash()),
                        "SKILL_COMPLETION_SPEC_CHANGED");
                requireRunning(existing, permit);
                return existing.record();
            }
            RunToken token = permit.step().runToken();
            jdbc.update("INSERT INTO campaign_skill_invocation (run_id,revision,call_id,call_definition_hash,definition_json,definition_hash,"
                            + "invocation_state,row_version,running_attempt_id,running_attempt_version,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,'RUNNING',0,?,?,?,?)",
                    token.definition().runId(), token.definition().revision(), permit.callId(), call.spec().hash(), body,
                    CampaignRunStore.sha256(body), permit.attemptId(), permit.attemptVersion(), clock.millis(), clock.millis());
            return required(token, permit.callId(), call).record();
        });
    }

    @Override public InvocationRecord awaitContinuation(CallPermit permit, Set<String> childIds) {
        Set<String> expected = Set.copyOf(Objects.requireNonNull(childIds));
        require(!expected.isEmpty(), "SKILL_WAIT_CHILDREN_REQUIRED");
        return transaction(() -> {
            CallRecord call = current(permit);
            RunToken token = permit.step().runToken();
            Row row = required(token, permit.callId(), call);
            requireAttempt(row, permit);
            if (waiting(row.record().state())) {
                require(call.state() == CallState.RETURNED && !call.revoked()
                                && waitRows(token, permit.callId(), row.record().rowVersion()).stream()
                                .map(WaitRow::childId).collect(java.util.stream.Collectors.toSet()).equals(expected),
                        "SKILL_WAIT_CHANGED");
                return row.record();
            }
            requireRunning(row, permit);
            gate.requireParent(permit, permit.actionId());
            List<String> actualIds = childIds(token, call.spec().actionId());
            require(actualIds.containsAll(expected), "SKILL_WAIT_CHILD_NOT_OWNED");
            boolean pendingJob = false;
            boolean pendingCapacity = false;
            for (String id : actualIds) {
                ChildRecord child = child(token, call.spec(), id);
                require(!child.callbackActive(), "SKILL_CHILD_NOT_WAITABLE");
                if (deferred(child)) {
                    require(capacityWaits && expected.contains(id), "SKILL_CAPACITY_CHILD_OMITTED");
                    capacityProof(token, child);
                    pendingCapacity = true;
                } else if (child.state() == ChildState.WAITING) {
                    require(child.reason() == null, "SKILL_CHILD_NOT_WAITABLE");
                    require(child.spec().mode() == ChildMode.ASYNC && child.jobId() != null && expected.contains(id),
                            "SKILL_PENDING_CHILD_OMITTED");
                    pendingJob = true;
                } else require(child.state() == ChildState.READY && child.reason() == null, "SKILL_CHILD_NOT_WAITABLE");
            }
            require(pendingJob || pendingCapacity, "SKILL_PENDING_CHILD_REQUIRED");
            State waitState = pendingJob ? State.WAITING : State.DEFERRED;
            long version = row.record().rowVersion() + 1;
            for (String id : expected) {
                ChildRecord child = child(token, call.spec(), id);
                require(child.attemptId() != null && child.attemptVersion() > 0, "SKILL_CHILD_RECEIPT_REQUIRED");
                jdbc.update("INSERT INTO campaign_skill_wait_child (run_id,revision,call_id,wait_version,child_id,spec_hash,request_id,"
                                + "child_mode,job_id,artifact_id,receipt_state,attempt_id,attempt_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        token.definition().runId(), token.definition().revision(), permit.callId(), version, id,
                        childHash(child.spec()), child.spec().requestId(), child.spec().mode().name(), child.jobId(), child.artifactId(),
                        child.state().name(), child.attemptId(), child.attemptVersion());
                if (deferred(child)) {
                    SubmissionDeferral proof = capacityProof(token, child);
                    changed(jdbc.update("UPDATE campaign_skill_wait_child SET dependency_kind='CAPACITY_DUE',capacity_kind=?,"
                                    + "rejected_attempts=?,retry_not_before=? WHERE run_id=? AND revision=? AND call_id=? AND wait_version=? AND child_id=?",
                            proof.kind().name(), proof.rejectedAttempts(), proof.retryNotBeforeMillis(), token.definition().runId(),
                            token.definition().revision(), permit.callId(), version, id));
                }
            }
            changed(jdbc.update("UPDATE campaign_skill_invocation SET invocation_state=?,row_version=row_version+1,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND call_id=? AND invocation_state='RUNNING' AND row_version=? "
                            + "AND running_attempt_id=? AND running_attempt_version=?", waitState.name(), clock.millis(), token.definition().runId(),
                    token.definition().revision(), permit.callId(), row.record().rowVersion(), permit.attemptId(), permit.attemptVersion()));
            calls.recordReturned(permit);
            return required(token, permit.callId(), call).record();
        });
    }

    @Override public CallPermit beginContinuation(StepPermit step, String callId, long expectedInvocationVersion,
                                                  Approval sourceModel, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(step); Objects.requireNonNull(sourceModel); Objects.requireNonNull(authorizer);
        return transaction(() -> {
            RunToken token = step.runToken();
            calls.lockRun(token, true);
            calls.requireStep(step);
            CallRecord call = calls.find(token, callId, true).orElseThrow(() -> failure("EXPLORATION_CALL_NOT_FOUND"));
            Row row = required(token, callId, call);
            require(waiting(row.record().state()) && row.record().rowVersion() == expectedInvocationVersion,
                    "SKILL_INVOCATION_NOT_WAITING");
            require(call.state() == CallState.RETURNED && !call.callbackActive() && !call.revoked() && call.returnedAt() != null
                            && Objects.equals(call.attemptId(), row.attemptId()) && call.attemptVersion() == row.attemptVersion(),
                    "SKILL_CALLBACK_NOT_RETIRED");
            var previousSteps = jdbc.query("SELECT step_attempt_id,step_attempt_version FROM campaign_exploration_call "
                            + "WHERE run_id=? AND revision=? AND call_id=? FOR UPDATE",
                    (rs, ignored) -> !step.attemptId().equals(rs.getString(1)) && step.attemptVersion() > rs.getLong(2),
                    token.definition().runId(), token.definition().revision(), callId);
            require(previousSteps.size() == 1 && previousSteps.get(0), "SKILL_NEW_STEP_ATTEMPT_REQUIRED");
            gate.requireNoActive(token.definition().runId());
            calls.requireNoOtherCallbacks(step);
            calls.validateSource(step, call.spec(), sourceModel, authorizer);
            validateContract(call.spec(), row.record().spec());
            require(dependenciesReady(token, call, row, authorizer), "SKILL_CONTINUATION_NOT_DUE");
            archiveAttempt(token, call);
            CallPermit next = new CallPermit(step, callId, call.spec().actionId(), UUID.randomUUID().toString(),
                    Math.addExact(call.attemptVersion(), 1));
            changed(jdbc.update("UPDATE campaign_exploration_call SET call_state='RUNNING',row_version=row_version+1,attempt_id=?,attempt_version=?,"
                            + "step_attempt_id=?,step_attempt_version=?,dispatch_run_version=?,dispatch_run_token=?,callback_active=TRUE,returned_at=NULL,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND call_id=? AND call_state='RETURNED' AND callback_active=FALSE AND revoked=FALSE "
                            + "AND attempt_id=? AND attempt_version=? AND row_version=?",
                    next.attemptId(), next.attemptVersion(), step.attemptId(), step.attemptVersion(), token.version(), token.advanceToken(),
                    clock.millis(), token.definition().runId(), token.definition().revision(), callId, call.attemptId(), call.attemptVersion(), call.rowVersion()));
            changed(jdbc.update("UPDATE campaign_skill_invocation SET invocation_state='RUNNING',row_version=row_version+1,"
                            + "running_attempt_id=?,running_attempt_version=?,updated_at=? WHERE run_id=? AND revision=? AND call_id=? "
                            + "AND invocation_state=? AND row_version=?", next.attemptId(), next.attemptVersion(), clock.millis(),
                    token.definition().runId(), token.definition().revision(), callId, row.record().state().name(), expectedInvocationVersion));
            return next;
        });
    }

    @Override public boolean continuationReady(RunToken token, String callId, Approval sourceModel,
                                               ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(sourceModel); Objects.requireNonNull(authorizer);
        return transaction(() -> {
            calls.lockRun(token, true);
            CallRecord call = calls.find(token, callId, true).orElseThrow(() -> failure("EXPLORATION_CALL_NOT_FOUND"));
            Row row = required(token, callId, call);
            if (!waiting(row.record().state())) return false;
            require(call.state() == CallState.RETURNED && !call.revoked() && call.returnedAt() != null
                            && Objects.equals(call.attemptId(), row.attemptId()) && call.attemptVersion() == row.attemptVersion(),
                    "SKILL_CALLBACK_NOT_RETIRED");
            validateContinuationSource(token, call.spec(), sourceModel, authorizer);
            validateContract(call.spec(), row.record().spec());
            if (call.callbackActive() || gate.hasActive(token.definition().runId())) return false;
            if (!jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE LIMIT 1 FOR UPDATE",
                    (rs, number) -> rs.getString(1), token.definition().runId()).isEmpty()) return false;
            return dependenciesReady(token, call, row, authorizer);
        });
    }

    private boolean dependenciesReady(RunToken token, CallRecord call, Row row, ArtifactAuthorizer authorizer) {
        List<WaitRow> dependencies = waitRows(token, call.spec().callId(), row.record().rowVersion());
        require(!dependencies.isEmpty(), "SKILL_WAIT_RECEIPTS_REQUIRED");
        boolean due = true;
        Set<String> declared = new java.util.HashSet<>();
        for (WaitRow dependency : dependencies) {
            require(declared.add(dependency.childId()), "SKILL_WAIT_RECEIPT_CHANGED");
            ChildRecord actual = child(token, call.spec(), dependency.childId());
            require(dependency.specHash().equals(childHash(actual.spec())) && dependency.requestId().equals(actual.spec().requestId())
                            && dependency.mode().equals(actual.spec().mode().name())
                            && Objects.equals(dependency.jobId(), actual.jobId())
                            && (dependency.artifactId() == null || dependency.artifactId().equals(actual.artifactId())),
                    "SKILL_WAIT_RECEIPT_CHANGED");
            if (dependency.kind() == DependencyKind.CAPACITY_DUE) {
                require(capacityWaits && dependency.attemptId().equals(actual.attemptId())
                                && dependency.attemptVersion() == actual.attemptVersion(), "SKILL_CAPACITY_PROOF_CHANGED");
                SubmissionDeferral proof = capacityProof(token, actual);
                require(proof.kind() == dependency.capacityKind() && proof.rejectedAttempts() == dependency.rejectedAttempts()
                                && proof.retryNotBeforeMillis() == dependency.retryNotBefore(), "SKILL_CAPACITY_PROOF_CHANGED");
                due &= clock.millis() >= proof.retryNotBeforeMillis();
            } else {
                // Result collection legitimately advances the job's attempt; its job and request
                // identity are fixed. A rejected unadmitted submission never gets this relaxation.
                require(actual.attemptVersion() >= dependency.attemptVersion()
                                && (actual.attemptVersion() != dependency.attemptVersion() || dependency.attemptId().equals(actual.attemptId())),
                        "SKILL_WAIT_RECEIPT_CHANGED");
                if (actual.state() == ChildState.WAITING) {
                    require(actual.spec().mode() == ChildMode.ASYNC && actual.jobId() != null
                                    && !actual.callbackActive() && actual.reason() == null, "SKILL_CHILD_NOT_READY");
                    due = false;
                } else ready(token, call.spec(), actual, authorizer);
            }
        }
        // Every other actual child must be complete. Omitting any new pending/unknown/capacity
        // child from the frozen dependency set cannot authorize a continuation.
        for (String id : childIds(token, call.spec().actionId()))
            if (!declared.contains(id)) ready(token, call.spec(), child(token, call.spec(), id), authorizer);
        return due;
    }

    private void validateContinuationSource(RunToken token, CallSpec call, Approval approval, ArtifactAuthorizer authorizer) {
        Identity identity = CampaignExplorationCallStore.identity(token.definition(), call.stepId(), call.modelChildId(), call.toolCallId());
        require(identity.callId().equals(call.callId()) && identity.actionId().equals(call.actionId()), "EXPLORATION_CALL_IDENTITY_MISMATCH");
        var response = runs.readModelResponse(token, call.modelChildId(), approval, authorizer);
        require(CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)).equals(call.responseHash())
                        && response.toolCalls().size() == 1, "EXPLORATION_MODEL_RESPONSE_MISMATCH");
        var returned = response.toolCalls().get(0);
        require(returned.id().equals(call.toolCallId()) && returned.name().equals(call.executor().name())
                        && returned.arguments().equals(call.arguments()), "EXPLORATION_TOOL_CALL_CHANGED");
        PlanSpec.Step planned = FrozenCampaignRun.read(token.definition()).plan().steps().stream()
                .filter(item -> item.stepId().equals(call.stepId())).findFirst().orElseThrow();
        require(planned.executionMode() == PlanSpec.ExecutionMode.REACT && planned.executor() == null
                        && planned.explorationPolicy() != null && planned.explorationPolicy().allowedExecutors().contains(call.executor()),
                "EXPLORATION_EXECUTOR_NOT_ALLOWED");
        require(ModelInvocationRegistry.identity(token.definition(), call.stepId(), approval.invocation().turnIndex()).childId()
                .equals(call.modelChildId()), "EXPLORATION_MODEL_STEP_MISMATCH");
        validateSourceInputs(token, call, authorizer);
    }

    private SubmissionDeferral capacityProof(RunToken token, ChildRecord child) {
        require(capacityWaits && deferred(child) && !child.callbackActive() && child.spec().mode() == ChildMode.ASYNC
                        && child.jobId() == null && child.artifactId() == null, "SKILL_CAPACITY_PROOF_REQUIRED");
        SubmissionDeferral proof = runs.submissionDeferral(token, child.spec().childId())
                .orElseThrow(() -> failure("SKILL_CAPACITY_PROOF_REQUIRED"));
        require(proof.lastAttemptId().equals(child.attemptId()) && proof.lastAttemptVersion() == child.attemptVersion(),
                "SKILL_CAPACITY_PROOF_CHANGED");
        return proof;
    }

    private static boolean deferred(ChildRecord child) {
        return child.state() == ChildState.PREPARED && child.reason() == UnresolvedReason.QUERY_CAPACITY_EXHAUSTED;
    }

    private boolean waiting(State state) { return state == State.WAITING || (capacityWaits && state == State.DEFERRED); }

    @Override public InvocationRecord completeInvocation(CallPermit permit, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(authorizer);
        return transaction(() -> {
            CallRecord call = current(permit);
            RunToken token = permit.step().runToken();
            Row row = required(token, permit.callId(), call);
            requireAttempt(row, permit);
            require(!call.revoked(), "SKILL_CALLBACK_REVOKED");
            if (row.record().state() != State.COMPLETED) {
                requireRunning(row, permit);
                gate.requireParent(permit, permit.actionId());
            } else require(call.state() == CallState.RETURNED, "SKILL_COMPLETION_CALL_CHANGED");
            Map<String, ArtifactRef> outputs = completionOutputs(token, call, row, authorizer);
            String completionId = completionId(token, call);
            if (row.record().state() == State.COMPLETED) {
                require(completionId.equals(row.record().completionId()) && outputs.equals(row.record().outputs()),
                        "SKILL_COMPLETION_CHANGED");
                return row.record();
            }
            String body = encode(outputs, limits.definitionBytes());
            changed(jdbc.update("UPDATE campaign_skill_invocation SET invocation_state='COMPLETED',row_version=row_version+1,"
                            + "completion_id=?,outputs_json=?,outputs_hash=?,updated_at=? WHERE run_id=? AND revision=? AND call_id=? "
                            + "AND invocation_state='RUNNING' AND row_version=? AND running_attempt_id=? AND running_attempt_version=?",
                    completionId, body, CampaignRunStore.sha256(body), clock.millis(), token.definition().runId(), token.definition().revision(),
                    permit.callId(), row.record().rowVersion(), permit.attemptId(), permit.attemptVersion()));
            calls.recordReturned(permit);
            return required(token, permit.callId(), call).record();
        });
    }

    @Override public InvocationRecord readCompletion(RunToken token, String callId, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(authorizer);
        return transaction(() -> {
            calls.lockRun(token, true);
            CallRecord call = calls.find(token, callId, true).orElseThrow(() -> failure("EXPLORATION_CALL_NOT_FOUND"));
            Row row = required(token, callId, call);
            require(row.record().state() == State.COMPLETED && call.state() == CallState.RETURNED && !call.revoked()
                            && Objects.equals(row.attemptId(), call.attemptId()) && row.attemptVersion() == call.attemptVersion(),
                    "SKILL_COMPLETION_NOT_AVAILABLE");
            Map<String, ArtifactRef> outputs = completionOutputs(token, call, row, authorizer);
            require(completionId(token, call).equals(row.record().completionId()) && outputs.equals(row.record().outputs()),
                    "SKILL_COMPLETION_CHANGED");
            return row.record();
        });
    }

    private Map<String, ArtifactRef> completionOutputs(RunToken token, CallRecord call, Row row, ArtifactAuthorizer authorizer) {
        CapabilityCatalog.Signature signature = validateContract(call.spec(), row.record().spec());
        validateSourceInputs(token, call.spec(), authorizer);
        for (String id : childIds(token, call.spec().actionId())) ready(token, call.spec(), child(token, call.spec(), id), authorizer);
        CompletionSpec spec = row.record().spec();
        ChildRecord finalChild = child(token, call.spec(), spec.finalLocalChildId());
        require(finalChild.spec().mode() == ChildMode.LOCAL && finalChild.spec().localInvocation() != null
                        && finalChild.spec().localInvocation().outputs().equals(spec.outputs()), "SKILL_FINAL_LOCAL_CHANGED");
        Map<String, ArtifactRef> outputs = runs.localOutputs(token, spec.finalLocalChildId(), authorizer);
        require(outputs.keySet().equals(spec.outputs().keySet()), "SKILL_COMPLETION_OUTPUTS_CHANGED");
        for (var entry : outputs.entrySet()) {
            var binding = spec.outputs().get(entry.getKey());
            ArtifactRef output = entry.getValue();
            require(binding.artifactId().equals(output.artifactId()) && binding.type().equals(output.type())
                            && binding.schemaVersion().equals(output.schemaVersion()) && binding.scopeRef().equals(output.scopeRef())
                            && binding.periodsRef().equals(output.periodsRef()), "SKILL_COMPLETION_OUTPUTS_CHANGED");
            var actual = contracts.validateArtifact(signature.outputs().get(entry.getKey()).type(), output.artifactId(),
                    runs, token.definition().caller(), authorizer).metadata();
            require(actual.ref().equals(output), "SKILL_COMPLETION_OUTPUTS_CHANGED");
            producer(token, call.spec(), finalChild, actual);
        }
        return outputs;
    }

    private String completionId(RunToken token, CallRecord call) {
        return "skill-completion-" + CampaignRunStore.sha256(token.definition().definitionHash() + ":" + call.spec().callId());
    }

    @Override public Optional<InvocationRecord> invocation(RunToken token, String callId) {
        return transaction(() -> {
            calls.lockRun(token, true);
            CallRecord call = calls.find(token, callId, true).orElse(null);
            if (call == null) return Optional.empty();
            return find(token, callId).map(row -> { validateCall(row, call); return row.record(); });
        });
    }

    private CallRecord current(CallPermit permit) {
        Objects.requireNonNull(permit); Objects.requireNonNull(permit.step());
        calls.lockRun(permit.step().runToken(), true);
        calls.requireStep(permit.step());
        return calls.requireAttempt(permit);
    }

    private CapabilityCatalog.Signature validateContract(CallSpec call, CompletionSpec spec) {
        var capability = catalog.capability(call.executor()).orElseThrow(() -> failure("SKILL_CAPABILITY_UNREGISTERED"));
        require(call.executor().kind() == ExecutorKind.SKILL && call.executor().equals(capability.executor())
                        && !capability.startsExploration(), "SKILL_CAPABILITY_NOT_ALLOWED");
        var signature = capability.signature();
        require(signature != null && spec.outputContractRef().equals(signature.outputContractRef())
                        && signature.outputs().keySet().containsAll(spec.outputs().keySet()), "SKILL_OUTPUT_CONTRACT_MISMATCH");
        signature.outputs().forEach((name, port) -> {
            require(!port.required() || spec.outputs().containsKey(name), "SKILL_REQUIRED_OUTPUT_MISSING");
            var binding = spec.outputs().get(name);
            if (binding != null) require(port.type().equals(contracts.typeOf(binding.type(), binding.schemaVersion())),
                    "SKILL_OUTPUT_TYPE_MISMATCH");
        });
        return signature;
    }

    private void validateSourceInputs(RunToken token, CallSpec call, ArtifactAuthorizer authorizer) {
        ChildRecord source = currentChild(token, call.modelChildId());
        require(source.spec().mode() == ChildMode.MODEL && source.state() == ChildState.READY && !source.callbackActive()
                        && source.spec().modelInvocation() != null && source.spec().modelInvocation().expiresAt().isAfter(clock.instant()),
                "SKILL_MODEL_SOURCE_INVALID");
        for (ArtifactMetadata input : source.spec().modelInvocation().inputs().values())
            require(input.equals(runs.readArtifact(token.definition().caller(), input.ref().artifactId(), authorizer).metadata()),
                    "SKILL_MODEL_INPUT_CHANGED");
    }

    private List<String> childIds(RunToken token, String actionId) {
        return jdbc.query("SELECT child_id FROM campaign_child_ledger WHERE run_id=? AND revision=? AND action_id=? ORDER BY child_id FOR UPDATE",
                (rs, row) -> rs.getString(1), token.definition().runId(), token.definition().revision(), actionId);
    }

    private ChildRecord child(RunToken token, CallSpec call, String childId) {
        ChildRecord child = currentChild(token, childId);
        require(call.actionId().equals(child.spec().actionId()) && child.spec().mode() != ChildMode.MODEL, "SKILL_CHILD_NOT_OWNED");
        return child;
    }

    private ChildRecord currentChild(RunToken token, String childId) {
        ChildRecord child = runs.child(token, childId).orElseThrow(() -> failure("SKILL_CHILD_MISSING"));
        // Run locking serializes writes, but cannot refresh an outer REPEATABLE_READ snapshot.
        // Fail closed if the public fact reader was served an older receipt than this current read.
        var current = jdbc.query("SELECT action_id,request_id,child_state,job_id,artifact_id,attempt_id,attempt_version,"
                        + "callback_active,unresolved_reason FROM campaign_child_ledger WHERE run_id=? AND revision=? AND child_id=? FOR UPDATE",
                (rs, ignored) -> child.spec().actionId().equals(rs.getString(1)) && child.spec().requestId().equals(rs.getString(2))
                        && child.state().name().equals(rs.getString(3)) && Objects.equals(child.jobId(), rs.getString(4))
                        && Objects.equals(child.artifactId(), rs.getString(5)) && Objects.equals(child.attemptId(), rs.getString(6))
                        && child.attemptVersion() == rs.getLong(7) && child.callbackActive() == rs.getBoolean(8)
                        && Objects.equals(child.reason() == null ? null : child.reason().name(), rs.getString(9)),
                token.definition().runId(), token.definition().revision(), childId);
        require(current.size() == 1 && current.get(0), "SKILL_CHILD_SNAPSHOT_STALE");
        return child;
    }

    private void ready(RunToken token, CallSpec call, ChildRecord child, ArtifactAuthorizer authorizer) {
        require(child.state() == ChildState.READY && !child.callbackActive() && child.reason() == null, "SKILL_CHILD_NOT_READY");
        if (child.spec().mode() == ChildMode.LOCAL) {
            Map<String, ArtifactRef> outputs = runs.localOutputs(token, child.spec().childId(), authorizer);
            require(!outputs.isEmpty(), "SKILL_CHILD_OUTPUTS_MISSING");
            for (ArtifactRef output : outputs.values()) {
                ArtifactMetadata actual = runs.readArtifact(token.definition().caller(), output.artifactId(), authorizer).metadata();
                require(output.equals(actual.ref()), "SKILL_CHILD_OUTPUT_CHANGED");
                producer(token, call, child, actual);
            }
        } else {
            require(child.artifactId() != null, "SKILL_CHILD_OUTPUTS_MISSING");
            producer(token, call, child, runs.readArtifact(token.definition().caller(), child.artifactId(), authorizer).metadata());
        }
    }

    private void producer(RunToken token, CallSpec call, ChildRecord child, ArtifactMetadata metadata) {
        var definition = token.definition();
        require(definition.caller().equals(metadata.owner()) && definition.runId().equals(metadata.runId())
                        && definition.planId().equals(metadata.planId()) && definition.revision() == metadata.revision()
                        && call.actionId().equals(metadata.actionId()) && child.spec().childId().equals(metadata.childId())
                        && call.executor().version().equals(metadata.executorVersion()), "SKILL_CHILD_PRODUCER_MISMATCH");
    }

    private void archiveAttempt(RunToken token, CallRecord call) {
        changed(jdbc.update("INSERT INTO campaign_skill_call_attempt (run_id,revision,call_id,attempt_id,attempt_version,step_attempt_id,"
                        + "step_attempt_version,dispatch_run_version,dispatch_run_token,returned_at,archived_at) "
                        + "SELECT run_id,revision,call_id,attempt_id,attempt_version,step_attempt_id,step_attempt_version,dispatch_run_version,"
                        + "dispatch_run_token,returned_at,? FROM campaign_exploration_call WHERE run_id=? AND revision=? AND call_id=? "
                        + "AND call_state='RETURNED' AND callback_active=FALSE AND revoked=FALSE AND attempt_id=? AND attempt_version=?",
                clock.millis(), token.definition().runId(), token.definition().revision(), call.spec().callId(), call.attemptId(), call.attemptVersion()));
    }

    private record Row(InvocationRecord record, String callHash, String attemptId, long attemptVersion) {}
    private enum DependencyKind { JOB_READY, CAPACITY_DUE }
    private record WaitRow(String childId, String specHash, String requestId, String mode, String jobId,
                           String artifactId, String attemptId, long attemptVersion, String receiptState,
                           DependencyKind kind, CapacityKind capacityKind, int rejectedAttempts, long retryNotBefore) {}

    private List<WaitRow> waitRows(RunToken token, String callId, long version) {
        String fields = capacityWaits ? ",dependency_kind,capacity_kind,rejected_attempts,retry_not_before" : "";
        return jdbc.query("SELECT child_id,spec_hash,request_id,child_mode,job_id,artifact_id,attempt_id,attempt_version,receipt_state" + fields + " "
                        + "FROM campaign_skill_wait_child WHERE run_id=? AND revision=? AND call_id=? AND wait_version=? ORDER BY child_id FOR UPDATE",
                (rs, row) -> readWait(rs), token.definition().runId(), token.definition().revision(), callId, version);
    }

    private WaitRow readWait(ResultSet rs) throws SQLException {
        DependencyKind kind = capacityWaits ? DependencyKind.valueOf(rs.getString("dependency_kind")) : DependencyKind.JOB_READY;
        CapacityKind capacityKind = null;
        int rejected = 0;
        long retryAt = 0;
        String state = rs.getString("receipt_state");
        if (kind == DependencyKind.CAPACITY_DUE) {
            require(capacityWaits && rs.getString("capacity_kind") != null && rs.getObject("rejected_attempts") != null
                            && rs.getObject("retry_not_before") != null && "PREPARED".equals(state)
                            && "ASYNC".equals(rs.getString("child_mode")) && rs.getString("job_id") == null
                            && rs.getString("artifact_id") == null, "SKILL_CAPACITY_PROOF_CORRUPTED");
            capacityKind = CapacityKind.valueOf(rs.getString("capacity_kind"));
            rejected = rs.getInt("rejected_attempts"); retryAt = rs.getLong("retry_not_before");
            require(rejected > 0 && rejected <= rs.getLong("attempt_version") && retryAt > 0, "SKILL_CAPACITY_PROOF_CORRUPTED");
        } else {
            require("READY".equals(state) || ("WAITING".equals(state) && "ASYNC".equals(rs.getString("child_mode"))
                    && rs.getString("job_id") != null), "SKILL_WAIT_RECEIPT_CHANGED");
            if (capacityWaits) require(rs.getString("capacity_kind") == null && rs.getObject("rejected_attempts") == null
                    && rs.getObject("retry_not_before") == null, "SKILL_CAPACITY_PROOF_CORRUPTED");
        }
        return new WaitRow(rs.getString("child_id"), rs.getString("spec_hash"), rs.getString("request_id"),
                rs.getString("child_mode"), rs.getString("job_id"), rs.getString("artifact_id"), rs.getString("attempt_id"),
                rs.getLong("attempt_version"), state, kind, capacityKind, rejected, retryAt);
    }

    private Optional<Row> find(RunToken token, String callId) {
        return jdbc.query("SELECT * FROM campaign_skill_invocation WHERE run_id=? AND revision=? AND call_id=? FOR UPDATE",
                (rs, row) -> decode(rs), token.definition().runId(), token.definition().revision(), callId).stream().findFirst();
    }

    private Row required(RunToken token, String callId, CallRecord call) {
        Row row = find(token, callId).orElseThrow(() -> failure("SKILL_INVOCATION_MISSING"));
        validateCall(row, call);
        return row;
    }

    private void validateCall(Row row, CallRecord call) {
        require(row.callHash().equals(call.spec().hash()), "SKILL_CALL_CHANGED");
    }

    private void requireAttempt(Row row, CallPermit permit) {
        require(row.attemptId().equals(permit.attemptId()) && row.attemptVersion() == permit.attemptVersion(), "SKILL_INVOCATION_ATTEMPT_FENCED");
    }

    private void requireRunning(Row row, CallPermit permit) {
        requireAttempt(row, permit);
        require(row.record().state() == State.RUNNING, "SKILL_INVOCATION_NOT_RUNNING");
    }

    private Row decode(ResultSet rs) throws SQLException {
        String body = rs.getString("definition_json");
        bounded(body, limits.definitionBytes());
        require(CampaignRunStore.sha256(body).equals(rs.getString("definition_hash")), "SKILL_INVOCATION_CORRUPTED");
        CompletionSpec spec;
        Map<String, ArtifactRef> outputs = Map.of();
        try {
            spec = JSON.readValue(body, CompletionSpec.class);
            require(encode(spec, limits.definitionBytes()).equals(body), "SKILL_INVOCATION_CORRUPTED");
            String saved = rs.getString("outputs_json");
            if (saved != null) {
                bounded(saved, limits.definitionBytes());
                require(CampaignRunStore.sha256(saved).equals(rs.getString("outputs_hash")), "SKILL_INVOCATION_CORRUPTED");
                outputs = JSON.readValue(saved, new TypeReference<Map<String, ArtifactRef>>() {});
                require(encode(outputs, limits.definitionBytes()).equals(saved), "SKILL_INVOCATION_CORRUPTED");
            }
        } catch (JsonProcessingException | IllegalArgumentException invalid) { throw failure("SKILL_INVOCATION_CORRUPTED"); }
        State state = State.valueOf(rs.getString("invocation_state"));
        require(capacityWaits || state != State.DEFERRED, "SKILL_CAPACITY_WAIT_DISABLED");
        String completionId = rs.getString("completion_id");
        require((state == State.COMPLETED && completionId != null && !outputs.isEmpty())
                        || (state != State.COMPLETED && completionId == null && outputs.isEmpty() && rs.getString("outputs_json") == null),
                "SKILL_INVOCATION_CORRUPTED");
        return new Row(new InvocationRecord(spec, state, rs.getLong("row_version"), completionId, outputs),
                rs.getString("call_definition_hash"), rs.getString("running_attempt_id"), rs.getLong("running_attempt_version"));
    }

    private String childHash(ChildSpec child) { return CampaignRunStore.sha256(encode(child, limits.definitionBytes())); }

    private static String encode(Object value, int maximum) {
        try { String body = JSON.writeValueAsString(value); bounded(body, maximum); return body; }
        catch (JsonProcessingException invalid) { throw failure("SKILL_INVOCATION_INVALID"); }
    }

    private static void bounded(String value, int maximum) {
        require(value != null && value.getBytes(StandardCharsets.UTF_8).length <= maximum, "SKILL_INVOCATION_TOO_LARGE");
    }
    private <T> T transaction(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
    private static void changed(int changed) { require(changed == 1, "SKILL_INVOCATION_CONFLICT"); }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
