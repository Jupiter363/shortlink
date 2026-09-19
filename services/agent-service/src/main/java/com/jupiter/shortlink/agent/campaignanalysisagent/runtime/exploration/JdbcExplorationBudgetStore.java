package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in, server-registered run allowance. This is not a scheduler, tenant-fair capacity control,
 * token accounting or a retry budget. Reservations share the transaction that creates their facts.
 */
public final class JdbcExplorationBudgetStore {
    public record Decision(boolean allowed, String reason) {
        private static Decision admitted() { return new Decision(true, ""); }
        private static Decision stopped(String reason) { return new Decision(false, reason); }
    }
    public record Usage(ExplorationBudgetPolicy policy, long modelTurns, long calls, long repairs) { }
    private enum Kind { MODEL, CALL, REPAIR }
    private record Slot(int revision, String stepId, long turnIndex, Kind kind, Long contextBytes, String requestHash) { }
    private record HistoricalTurn(int revision, String stepId, long index, boolean hasCall, boolean repaired) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ExplorationBudgetPolicy policy;

    /** Constructed by the trusted server composition root, never from user/model-selected limits. */
    public JdbcExplorationBudgetStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                      ExplorationBudgetPolicy policy) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.policy = Objects.requireNonNull(policy);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw failure("EXPLORATION_BUDGET_TRANSACTION_INVALID");
    }

    /** Freeze once per run. Upgrade existing durable runs without granting a fresh empty allowance. */
    public Usage initialize(StepPermit step) {
        return tx(() -> { requireCurrent(step); return initializeLocked(step); });
    }

    public Usage usage(StepPermit step) {
        return tx(() -> { requireCurrent(step); return initializeLocked(step); });
    }

    /** Before native checkpoint publication; this freezes policy but reserves no execution slot. */
    public Decision checkContext(StepPermit step, String canonicalRequestJson) {
        long bytes = utf8Bytes(canonicalRequestJson);
        return tx(() -> {
            requireCurrent(step); initializeLocked(step);
            return bytes > policy.maxContextBytes()
                    ? Decision.stopped("EXPLORATION_CONTEXT_BUDGET_EXHAUSTED") : Decision.admitted();
        });
    }

    /** The caller passes the exact canonical request later committed in its MODEL envelope. */
    public Decision reserveModel(StepPermit step, long turnIndex, String canonicalRequestJson) {
        requireOuterTransaction();
        long bytes = utf8Bytes(canonicalRequestJson);
        String hash = CampaignRunStore.sha256(canonicalRequestJson);
        return reserve(step, turnIndex, Kind.MODEL, bytes, hash);
    }

    public Decision reserveCall(StepPermit step, long turnIndex) {
        requireOuterTransaction();
        return reserve(step, turnIndex, Kind.CALL, null, null);
    }

    public Decision reserveRepair(StepPermit step, long turnIndex) {
        requireOuterTransaction();
        return reserve(step, turnIndex, Kind.REPAIR, null, null);
    }

    private Decision reserve(StepPermit step, long turnIndex, Kind kind, Long bytes, String requestHash) {
        if (turnIndex < 1) throw failure("EXPLORATION_BUDGET_SLOT_INVALID");
        return tx(() -> {
            requireCurrent(step);
            Usage usage = initializeLocked(step);
            String runId = step.runToken().definition().runId();
            int revision = step.runToken().definition().revision();
            String slotId = slotId(revision, step.stepId(), turnIndex, kind);
            List<Slot> existing = jdbc.query("SELECT revision,step_id,turn_index,slot_kind,context_bytes,request_hash "
                            + "FROM campaign_exploration_budget_slot WHERE run_id=? AND slot_id=? FOR UPDATE",
                    (rs, row) -> slot(rs), runId, slotId);
            if (existing.size() > 1) throw failure("EXPLORATION_BUDGET_SLOT_CORRUPTED");
            if (!existing.isEmpty()) {
                Slot actual = existing.get(0);
                if (actual.revision() != revision || !actual.stepId().equals(step.stepId())
                        || actual.turnIndex() != turnIndex || actual.kind() != kind)
                    throw failure("EXPLORATION_BUDGET_SLOT_CORRUPTED");
                if (kind == Kind.MODEL && actual.requestHash() != null
                        && (!actual.requestHash().equals(requestHash) || !actual.contextBytes().equals(bytes)))
                    throw failure("EXPLORATION_BUDGET_REQUEST_CHANGED");
                if (kind == Kind.MODEL && bytes > policy.maxContextBytes())
                    return Decision.stopped("EXPLORATION_CONTEXT_BUDGET_EXHAUSTED");
                if (kind == Kind.MODEL && actual.requestHash() == null)
                    jdbc.update("UPDATE campaign_exploration_budget_slot SET context_bytes=?,request_hash=? WHERE run_id=? AND slot_id=?",
                            bytes, requestHash, runId, slotId);
                return Decision.admitted();
            }
            if (kind == Kind.MODEL && bytes > policy.maxContextBytes())
                return Decision.stopped("EXPLORATION_CONTEXT_BUDGET_EXHAUSTED");
            long used = switch (kind) { case MODEL -> usage.modelTurns(); case CALL -> usage.calls(); case REPAIR -> usage.repairs(); };
            long maximum = switch (kind) { case MODEL -> policy.maxModelTurns(); case CALL -> policy.maxCalls(); case REPAIR -> policy.maxRepairs(); };
            if (used >= maximum) return Decision.stopped(switch (kind) {
                case MODEL -> "EXPLORATION_MODEL_TURN_BUDGET_EXHAUSTED";
                case CALL -> "EXPLORATION_CALL_BUDGET_EXHAUSTED";
                case REPAIR -> "EXPLORATION_REPAIR_BUDGET_EXHAUSTED";
            });
            insertSlot(runId, revision, step.stepId(), turnIndex, kind, bytes, requestHash);
            String counter = switch (kind) { case MODEL -> "model_turns"; case CALL -> "capability_calls"; case REPAIR -> "repairs"; };
            if (jdbc.update("UPDATE campaign_exploration_budget SET " + counter + "=" + counter
                            + "+1,row_version=row_version+1,updated_at=? WHERE run_id=? AND policy_hash=?",
                    clock.millis(), runId, policy.hash()) != 1) throw failure("EXPLORATION_BUDGET_CHANGED");
            return Decision.admitted();
        });
    }

    private Usage initializeLocked(StepPermit step) {
        var definition = step.runToken().definition();
        List<Usage> existing = readUsage(step);
        if (!existing.isEmpty()) return existing.get(0);
        // This one-time upgrade reads only old turn references and decisions, never request history.
        List<HistoricalTurn> history = jdbc.query("SELECT revision,step_id,turn_index,call_id,repair_counted "
                        + "FROM campaign_exploration_turn WHERE run_id=? ORDER BY revision,step_id,turn_index FOR UPDATE",
                (rs, row) -> new HistoricalTurn(rs.getInt("revision"), rs.getString("step_id"), rs.getLong("turn_index"),
                        rs.getString("call_id") != null, rs.getBoolean("repair_counted")), definition.runId());
        long modelCount = history.size();
        long callCount = history.stream().filter(HistoricalTurn::hasCall).count();
        long repairCount = history.stream().filter(HistoricalTurn::repaired).count();
        jdbc.update("INSERT INTO campaign_exploration_budget (run_id,owner_hash,policy_ref,policy_version,policy_hash,"
                        + "max_model_turns,max_calls,max_repairs,max_context_bytes,model_turns,capability_calls,repairs,row_version,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
                definition.runId(), ownerHash(step), policy.policyRef(), policy.policyVersion(), policy.hash(),
                policy.maxModelTurns(), policy.maxCalls(), policy.maxRepairs(), policy.maxContextBytes(),
                modelCount, callCount, repairCount, clock.millis(), clock.millis());
        for (HistoricalTurn turn : history) {
            insertSlot(definition.runId(), turn.revision(), turn.stepId(), turn.index(), Kind.MODEL, null, null);
            if (turn.hasCall()) insertSlot(definition.runId(), turn.revision(), turn.stepId(), turn.index(), Kind.CALL, null, null);
            if (turn.repaired()) insertSlot(definition.runId(), turn.revision(), turn.stepId(), turn.index(), Kind.REPAIR, null, null);
        }
        return new Usage(policy, modelCount, callCount, repairCount);
    }

    private List<Usage> readUsage(StepPermit step) {
        return jdbc.query("SELECT owner_hash,policy_ref,policy_version,policy_hash,max_model_turns,max_calls,max_repairs,max_context_bytes,"
                        + "model_turns,capability_calls,repairs FROM campaign_exploration_budget WHERE run_id=? FOR UPDATE",
                (rs, row) -> {
                    var stored = new ExplorationBudgetPolicy(rs.getString("policy_ref"), rs.getString("policy_version"),
                            rs.getLong("max_model_turns"), rs.getLong("max_calls"), rs.getLong("max_repairs"), rs.getLong("max_context_bytes"));
                    if (!ownerHash(step).equals(rs.getString("owner_hash"))) throw new SecurityException("EXPLORATION_BUDGET_OWNER_CHANGED");
                    if (!stored.hash().equals(rs.getString("policy_hash"))) throw failure("EXPLORATION_BUDGET_POLICY_CORRUPTED");
                    if (!policy.equals(stored)) throw failure("EXPLORATION_BUDGET_POLICY_CHANGED");
                    long models = rs.getLong("model_turns"), calls = rs.getLong("capability_calls"), repairs = rs.getLong("repairs");
                    if (models < 0 || calls < 0 || repairs < 0) throw failure("EXPLORATION_BUDGET_USAGE_CORRUPTED");
                    return new Usage(stored, models, calls, repairs);
                }, step.runToken().definition().runId());
    }

    private void insertSlot(String runId, int revision, String stepId, long turnIndex, Kind kind, Long bytes, String hash) {
        jdbc.update("INSERT INTO campaign_exploration_budget_slot (run_id,slot_id,revision,step_id,turn_index,slot_kind,context_bytes,request_hash,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", runId, slotId(revision, stepId, turnIndex, kind), revision, stepId, turnIndex,
                kind.name(), bytes, hash, clock.millis());
    }

    /** Current locking reads remain authoritative inside an existing REPEATABLE_READ transaction. */
    private void requireCurrent(StepPermit step) {
        if (step == null || step.runToken() == null || step.stepId() == null || step.attemptId() == null || step.attemptVersion() < 1)
            throw new SecurityException("EXPLORATION_BUDGET_STEP_FENCED");
        var token = step.runToken(); var definition = token.definition(); var owner = definition.caller();
        var runMatches = jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,run_status,row_version,advance_token "
                        + "FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> owner.tenantId().equals(rs.getString("tenant_id")) && owner.subject().equals(rs.getString("subject_name"))
                        && owner.authVersion() == rs.getLong("auth_version") && definition.sessionId().equals(rs.getString("session_id"))
                        && definition.planId().equals(rs.getString("plan_id")) && definition.definitionHash().equals(rs.getString("definition_hash"))
                        && "ACTIVE".equals(rs.getString("run_status")) && token.version() == rs.getLong("row_version")
                        && Objects.equals(token.advanceToken(), rs.getString("advance_token")), definition.runId(), definition.revision());
        if (runMatches.size() != 1 || !runMatches.get(0)) throw new SecurityException("EXPLORATION_BUDGET_RUN_FENCED");
        var stepMatches = jdbc.query("SELECT step_status,callback_active,attempt_id,attempt_version,dispatch_run_version,dispatch_run_token "
                        + "FROM campaign_step_ledger WHERE run_id=? AND revision=? AND step_id=? FOR UPDATE",
                (rs, row) -> "RUNNING".equals(rs.getString("step_status")) && rs.getBoolean("callback_active")
                        && step.attemptId().equals(rs.getString("attempt_id")) && step.attemptVersion() == rs.getLong("attempt_version")
                        && token.version() == rs.getLong("dispatch_run_version") && Objects.equals(token.advanceToken(), rs.getString("dispatch_run_token")),
                definition.runId(), definition.revision(), step.stepId());
        if (stepMatches.size() != 1 || !stepMatches.get(0)) throw new SecurityException("EXPLORATION_BUDGET_STEP_FENCED");
    }

    private void requireOuterTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource())))
            throw failure("EXPLORATION_BUDGET_REQUIRES_FACT_TRANSACTION");
    }

    private static Slot slot(ResultSet rs) throws SQLException {
        long bytes = rs.getLong("context_bytes"); Long contextBytes = rs.wasNull() ? null : bytes;
        String hash = rs.getString("request_hash");
        Kind kind = Kind.valueOf(rs.getString("slot_kind"));
        if ((contextBytes == null) != (hash == null) || (contextBytes != null && (contextBytes < 0 || kind != Kind.MODEL)))
            throw failure("EXPLORATION_BUDGET_SLOT_CORRUPTED");
        return new Slot(rs.getInt("revision"), rs.getString("step_id"), rs.getLong("turn_index"), kind, contextBytes, hash);
    }
    private static String slotId(int revision, String stepId, long turn, Kind kind) {
        return CampaignRunStore.sha256(revision + ":" + stepId.length() + ":" + stepId + ":" + kind.name() + ":" + turn);
    }
    private static String ownerHash(StepPermit step) {
        var definition = step.runToken().definition(); var owner = definition.caller();
        return CampaignRunStore.sha256(owner.tenantId().length() + ":" + owner.tenantId() + owner.subject().length() + ":" + owner.subject()
                + definition.sessionId().length() + ":" + definition.sessionId());
    }
    private static long utf8Bytes(String text) {
        if (text == null) throw failure("EXPLORATION_BUDGET_REQUEST_REQUIRED");
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value < 0x80) bytes++;
            else if (value < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(value) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4; i++;
            } else {
                if (Character.isSurrogate(value)) throw failure("EXPLORATION_BUDGET_REQUEST_UNICODE_INVALID");
                bytes += 3;
            }
        }
        return bytes;
    }
    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
