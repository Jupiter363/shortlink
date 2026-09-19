package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in callback ownership; no native loop, business parameter authorization or callback retry. */
public final class JdbcCampaignExplorationCallStore implements CampaignExplorationCallStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Limits limits;
    private final JdbcCampaignRunStore runs;
    private final JdbcCampaignStepStore steps;
    private final JdbcExplorationCallbackGate gate;

    public JdbcCampaignExplorationCallStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this(jdbc, transactions, clock, Limits.defaults());
    }

    public JdbcCampaignExplorationCallStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, Limits limits) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED || transactions.isReadOnly())
            throw new IllegalArgumentException("Exploration callbacks require one writable REQUIRED DataSource transaction");
        this.runs = new JdbcCampaignRunStore(jdbc, transactions, clock, limits);
        this.steps = new JdbcCampaignStepStore(jdbc, transactions, clock, limits);
        this.gate = new JdbcExplorationCallbackGate(jdbc);
        requireSchema();
    }

    @Override
    public CallRecord prepare(StepPermit step, CallSpec spec, ModelInvocationRegistry.Approval approval,
                              ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(step); Objects.requireNonNull(spec); Objects.requireNonNull(approval); Objects.requireNonNull(authorizer);
        checkBytes(CampaignExplorationCallStore.encode(spec), limits.definitionBytes());
        return transaction(() -> {
            lockRun(step.runToken(), true);
            requireStep(step);
            validateSource(step, spec, approval, authorizer);
            CallRecord existing = find(step.runToken(), spec.callId(), true).orElse(null);
            if (existing != null && !existing.spec().equals(spec)) fail("EXPLORATION_CALL_CHANGED");
            runs.prepareAction(step.runToken(), new ActionSpec(spec.actionId(), step.stepId(), spec.executor().kind().name(),
                    spec.executor().name(), spec.executor().version(), CampaignExplorationCallStore.encode(spec)));
            if (existing != null) return existing;
            jdbc.update("INSERT INTO campaign_exploration_call (run_id,revision,call_id,action_id,step_id,model_child_id,response_hash,"
                            + "tool_call_id,definition_json,definition_hash,call_state,row_version,attempt_version,callback_active,revoked,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,'PREPARED',0,0,FALSE,FALSE,?,?)",
                    step.runToken().definition().runId(), step.runToken().definition().revision(), spec.callId(), spec.actionId(), spec.stepId(),
                    spec.modelChildId(), spec.responseHash(), spec.toolCallId(), CampaignExplorationCallStore.encode(spec), spec.hash(), clock.millis(), clock.millis());
            return find(step.runToken(), spec.callId(), false).orElseThrow();
        });
    }

    @Override
    public CallPermit beginCall(StepPermit step, String callId, ModelInvocationRegistry.Approval approval,
                                ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(step); Objects.requireNonNull(approval); Objects.requireNonNull(authorizer);
        return transaction(() -> {
            RunToken token = step.runToken();
            lockRun(token, true);
            requireStep(step);
            gate.requireNoActive(token.definition().runId());
            requireNoOtherCallbacks(step);
            CallRecord call = find(token, callId, true).orElseThrow(() -> new IllegalStateException("EXPLORATION_CALL_NOT_FOUND"));
            if (call.state() != CallState.PREPARED || call.callbackActive() || call.revoked() || call.attemptVersion() != 0)
                fail("EXPLORATION_CALL_NOT_PREPARED");
            validateSource(step, call.spec(), approval, authorizer);
            CallPermit permit = new CallPermit(step, callId, call.spec().actionId(), UUID.randomUUID().toString(), 1);
            jdbc.update("UPDATE campaign_exploration_call SET call_state='RUNNING',row_version=row_version+1,attempt_id=?,attempt_version=?,"
                            + "step_attempt_id=?,step_attempt_version=?,dispatch_run_version=?,dispatch_run_token=?,callback_active=TRUE,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND call_id=?",
                    permit.attemptId(), permit.attemptVersion(), step.attemptId(), step.attemptVersion(), token.version(), token.advanceToken(),
                    clock.millis(), token.definition().runId(), token.definition().revision(), callId);
            return permit;
        });
    }

    @Override public Optional<CallRecord> call(RunToken token, String callId) {
        return transaction(() -> { lockRun(token, true); return find(token, callId, false); });
    }

    @Override public boolean mayExecute(CallPermit permit) {
        if (permit == null || permit.step() == null) return false;
        return transaction(() -> {
            try { lockRun(permit.step().runToken(), true); return gate.mayExecute(permit); }
            catch (IllegalStateException | SecurityException fenced) { return false; }
        });
    }

    @Override public void revoke(CallPermit permit, String reason) {
        if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("EXPLORATION_REASON_INVALID");
        transaction(() -> {
            lockRun(permit.step().runToken(), false);
            CallRecord record = requireAttempt(permit);
            if (record.revoked()) return null;
            update(permit, "revoked=TRUE,reason=?,row_version=row_version+1,updated_at=?", reason, clock.millis());
            return null;
        });
    }

    @Override public void recordReturned(CallPermit permit) {
        transaction(() -> {
            lockRun(permit.step().runToken(), true);
            CallRecord record = requireAttempt(permit);
            if (record.state() == CallState.RETURNED && record.returnedAt() != null) return null;
            gate.requireParent(permit, permit.actionId());
            update(permit, "call_state='RETURNED',returned_at=?,row_version=row_version+1,updated_at=?", clock.millis(), clock.millis());
            return null;
        });
    }

    @Override public void callbackExited(CallPermit permit) {
        transaction(() -> {
            lockRun(permit.step().runToken(), false);
            CallRecord record = requireAttempt(permit);
            if (!record.callbackActive()) return null;
            if (record.state() == CallState.RUNNING) {
                String reason = record.reason() == null ? "CALL_RESULT_UNKNOWN" : record.reason();
                update(permit, "call_state='UNRESOLVED',revoked=TRUE,reason=?,callback_active=FALSE,row_version=row_version+1,updated_at=?",
                        reason, clock.millis());
            } else update(permit, "callback_active=FALSE,row_version=row_version+1,updated_at=?", clock.millis());
            return null;
        });
    }

    private void validateSource(StepPermit step, CallSpec spec, ModelInvocationRegistry.Approval approval, ArtifactAuthorizer authorizer) {
        RunToken token = step.runToken();
        Identity expected = CampaignExplorationCallStore.identity(token.definition(), step.stepId(), spec.modelChildId(), spec.toolCallId());
        if (!step.stepId().equals(spec.stepId()) || !expected.callId().equals(spec.callId()) || !expected.actionId().equals(spec.actionId()))
            fail("EXPLORATION_CALL_IDENTITY_MISMATCH");
        var response = runs.readModelResponse(token, spec.modelChildId(), approval, authorizer);
        if (!CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)).equals(spec.responseHash())
                || response.toolCalls().size() != 1) fail("EXPLORATION_MODEL_RESPONSE_MISMATCH");
        var returned = response.toolCalls().get(0);
        if (!returned.id().equals(spec.toolCallId()) || !returned.name().equals(spec.executor().name())
                || !returned.arguments().equals(spec.arguments())) fail("EXPLORATION_TOOL_CALL_CHANGED");
        var frozen = FrozenCampaignRun.read(token.definition());
        PlanSpec.Step planned = frozen.plan().steps().stream().filter(item -> item.stepId().equals(step.stepId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("EXPLORATION_STEP_NOT_FOUND"));
        if (planned.executionMode() != PlanSpec.ExecutionMode.REACT || planned.executor() != null || planned.explorationPolicy() == null
                || !planned.explorationPolicy().allowedExecutors().contains(spec.executor())) fail("EXPLORATION_EXECUTOR_NOT_ALLOWED");
        var source = ModelInvocationRegistry.identity(token.definition(), step.stepId(), approval.invocation().turnIndex());
        if (!source.childId().equals(spec.modelChildId())) fail("EXPLORATION_MODEL_STEP_MISMATCH");
        // Model response verification above rechecks the model's frozen Step/input/policy and current Artifact ACL.
        if (!steps.step(token, step.stepId()).orElseThrow().spec().stepId().equals(spec.stepId()))
            fail("EXPLORATION_STEP_CHANGED");
    }

    private void requireStep(StepPermit step) {
        var token = step.runToken();
        var matches = jdbc.query("SELECT step_status,callback_active,attempt_id,attempt_version,dispatch_run_version,dispatch_run_token "
                        + "FROM campaign_step_ledger WHERE run_id=? AND revision=? AND step_id=? FOR UPDATE",
                (rs, row) -> "RUNNING".equals(rs.getString("step_status")) && rs.getBoolean("callback_active")
                        && Objects.equals(step.attemptId(), rs.getString("attempt_id")) && step.attemptVersion() == rs.getLong("attempt_version")
                        && token.version() == rs.getLong("dispatch_run_version") && token.advanceToken().equals(rs.getString("dispatch_run_token")),
                token.definition().runId(), token.definition().revision(), step.stepId());
        if (matches.size() != 1 || !matches.get(0)) fail("EXPLORATION_STEP_FENCED");
    }

    private void requireNoOtherCallbacks(StepPermit step) {
        var definition = step.runToken().definition();
        Integer childCount = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE",
                Integer.class, definition.runId());
        Integer stepCount = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE run_id=? AND callback_active=TRUE "
                        + "AND (revision<>? OR step_id<>?)", Integer.class, definition.runId(), definition.revision(), step.stepId());
        if ((childCount != null && childCount > 0) || (stepCount != null && stepCount > 0)) fail("CALLBACK_STILL_ACTIVE");
    }

    private CallRecord requireAttempt(CallPermit permit) {
        Objects.requireNonNull(permit); Objects.requireNonNull(permit.step());
        var token = permit.step().runToken();
        CallRecord record = find(token, permit.callId(), true).orElseThrow(() -> new IllegalStateException("EXPLORATION_CALL_NOT_FOUND"));
        var matches = jdbc.query("SELECT step_attempt_id,step_attempt_version,dispatch_run_version,dispatch_run_token FROM campaign_exploration_call "
                        + "WHERE run_id=? AND revision=? AND call_id=?",
                (rs, row) -> Objects.equals(permit.step().attemptId(), rs.getString("step_attempt_id"))
                        && permit.step().attemptVersion() == rs.getLong("step_attempt_version")
                        && token.version() == rs.getLong("dispatch_run_version") && token.advanceToken().equals(rs.getString("dispatch_run_token")),
                token.definition().runId(), token.definition().revision(), permit.callId());
        if (!permit.actionId().equals(record.spec().actionId()) || !permit.step().stepId().equals(record.spec().stepId())
                || !Objects.equals(permit.attemptId(), record.attemptId()) || permit.attemptVersion() != record.attemptVersion()
                || permit.attemptVersion() < 1 || matches.size() != 1 || !matches.get(0)) fail("EXPLORATION_CALL_ATTEMPT_FENCED");
        return record;
    }

    private Optional<CallRecord> find(RunToken token, String callId, boolean lock) {
        return jdbc.query("SELECT * FROM campaign_exploration_call WHERE run_id=? AND revision=? AND call_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> decode(rs), token.definition().runId(), token.definition().revision(), callId).stream().findFirst();
    }

    private CallRecord decode(ResultSet rs) throws SQLException {
        String body = rs.getString("definition_json");
        checkBytes(body, limits.definitionBytes());
        if (!CampaignRunStore.sha256(body).equals(rs.getString("definition_hash"))) fail("EXPLORATION_CALL_CORRUPTED");
        CallSpec spec = CampaignExplorationCallStore.decode(body);
        if (!spec.hash().equals(rs.getString("definition_hash")) || !spec.callId().equals(rs.getString("call_id"))
                || !spec.actionId().equals(rs.getString("action_id")) || !spec.stepId().equals(rs.getString("step_id"))
                || !spec.modelChildId().equals(rs.getString("model_child_id")) || !spec.responseHash().equals(rs.getString("response_hash"))
                || !spec.toolCallId().equals(rs.getString("tool_call_id"))) fail("EXPLORATION_CALL_CORRUPTED");
        long returned = rs.getLong("returned_at");
        Long returnedAt = rs.wasNull() ? null : returned;
        return new CallRecord(spec, CallState.valueOf(rs.getString("call_state")), rs.getLong("row_version"),
                rs.getString("attempt_id"), rs.getLong("attempt_version"), rs.getBoolean("callback_active"), rs.getBoolean("revoked"),
                rs.getString("reason"), returnedAt);
    }

    private void lockRun(RunToken token, boolean current) {
        requireSchema();
        Objects.requireNonNull(token); var definition = token.definition(); var owner = definition.caller();
        var matches = jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,run_status,row_version,advance_token "
                        + "FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> owner.tenantId().equals(rs.getString("tenant_id")) && owner.subject().equals(rs.getString("subject_name"))
                        && owner.authVersion() == rs.getLong("auth_version") && definition.sessionId().equals(rs.getString("session_id"))
                        && definition.planId().equals(rs.getString("plan_id")) && definition.definitionHash().equals(rs.getString("definition_hash"))
                        && (!current || ("ACTIVE".equals(rs.getString("run_status")) && token.version() == rs.getLong("row_version")
                        && token.advanceToken().equals(rs.getString("advance_token")))), definition.runId(), definition.revision());
        if (matches.size() != 1 || !matches.get(0)) fail("EXPLORATION_RUN_FENCED");
    }

    private void update(CallPermit permit, String setClause, Object... values) {
        var token = permit.step().runToken();
        var args = new java.util.ArrayList<Object>(List.of(values));
        args.add(token.definition().runId()); args.add(token.definition().revision()); args.add(permit.callId());
        args.add(permit.actionId()); args.add(permit.attemptId()); args.add(permit.attemptVersion());
        args.add(permit.step().attemptId()); args.add(permit.step().attemptVersion()); args.add(token.version()); args.add(token.advanceToken());
        int changed = jdbc.update("UPDATE campaign_exploration_call SET " + setClause + " WHERE run_id=? AND revision=? AND call_id=? AND action_id=? "
                        + "AND attempt_id=? AND attempt_version=? AND step_attempt_id=? AND step_attempt_version=? AND dispatch_run_version=? AND dispatch_run_token=?",
                args.toArray());
        if (changed != 1) fail("EXPLORATION_CALL_ATTEMPT_FENCED");
    }

    private void requireSchema() { if (!gate.schemaAvailable()) fail("EXPLORATION_CALL_SCHEMA_REQUIRED"); }
    private static void checkBytes(String value, int maximum) {
        if (value == null) throw new IllegalArgumentException("EXPLORATION_CALL_DEFINITION_INVALID");
        long count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) count++;
            else if (c < 0x800) count += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { count += 4; i++; }
            else count += Character.isSurrogate(c) ? 1 : 3;
            if (count > maximum) throw new IllegalArgumentException("EXPLORATION_CALL_DEFINITION_TOO_LARGE");
        }
    }
    private <T> T transaction(Supplier<T> operation) {
        try { return transactions.execute(status -> operation.get()); }
        catch (DataIntegrityViolationException collision) { throw new IllegalStateException("EXPLORATION_CALL_IDENTITY_CONFLICT"); }
    }
    private static void fail(String code) { throw new IllegalStateException(code); }
}
