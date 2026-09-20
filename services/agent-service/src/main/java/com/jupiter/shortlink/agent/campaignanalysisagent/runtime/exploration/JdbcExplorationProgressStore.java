package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short immutable proposal facts; only the ledger may validate and admit actual source receipts. */
public final class JdbcExplorationProgressStore {
    public record Usage(long stopEvents) {}
    enum Decision { ADMITTED, STOP }
    record Proposal(long turnIndex, String fingerprint, String modelChildId, String responseHash,
                    Decision decision, String callId, Long sourceTurnIndex, String sourceCallId, String outputsHash) {}
    private static final String COLUMNS = "turn_index,fingerprint,model_child_id,response_hash,decision,call_id,"
            + "source_turn_index,source_call_id,outputs_hash";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignStepStore steps;

    public JdbcExplorationProgressStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, CampaignStepStore steps) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.steps = Objects.requireNonNull(steps);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED || transactions.isReadOnly())
            throw failure("EXPLORATION_PROGRESS_TRANSACTION_INVALID");
    }

    public Usage initialize(StepPermit step) {
        return tx(() -> {
            current(step);
            List<Usage> existing = usages(step);
            if (!existing.isEmpty()) return existing.get(0);
            jdbc.update("INSERT INTO campaign_exploration_progress (run_id,owner_hash,stop_events,row_version,created_at,updated_at) "
                            + "VALUES (?,?,0,0,?,?)", step.runToken().definition().runId(), ownerHash(step), clock.millis(), clock.millis());
            return new Usage(0);
        });
    }

    public Usage usage(StepPermit step) { return tx(() -> { current(step); return usages(step).stream().findFirst().orElseThrow(); }); }

    Optional<Proposal> proposal(StepPermit step, long turn) {
        requireFactTransaction(); current(step);
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_exploration_proposal WHERE run_id=? AND revision=? "
                        + "AND step_id=? AND turn_index=? FOR UPDATE", (rs, row) -> read(rs),
                step.runToken().definition().runId(), step.runToken().definition().revision(), step.stepId(), turn).stream().findFirst();
    }

    /** The index selects only this revision's actually observed and retired successful callback. */
    Optional<Proposal> observed(StepPermit step, long beforeTurn, String fingerprint) {
        requireFactTransaction(); current(step); hash(fingerprint);
        String columns = "p." + COLUMNS.replace(",", ",p.");
        return jdbc.query("SELECT " + columns + " FROM campaign_exploration_proposal p "
                        + "JOIN campaign_exploration_turn t ON t.run_id=p.run_id AND t.revision=p.revision AND t.step_id=p.step_id "
                        + "AND t.turn_index=p.turn_index AND t.call_id=p.call_id "
                        + "JOIN campaign_exploration_call c ON c.run_id=p.run_id AND c.revision=p.revision AND c.call_id=p.call_id "
                        + "WHERE p.run_id=? AND p.revision=? AND p.step_id=? AND p.fingerprint=? AND p.decision='ADMITTED' "
                        + "AND p.turn_index<? AND t.decision='OBSERVED' AND c.call_state='RETURNED' "
                        + "AND c.callback_active=FALSE AND c.revoked=FALSE ORDER BY p.turn_index DESC LIMIT 1 FOR UPDATE",
                (rs, row) -> read(rs), step.runToken().definition().runId(), step.runToken().definition().revision(),
                step.stepId(), fingerprint, beforeTurn).stream().findFirst();
    }

    void admitted(StepPermit step, long turn, String fingerprint, String modelChildId, String responseHash, String callId) {
        save(step, new Proposal(turn, fingerprint, modelChildId, responseHash, Decision.ADMITTED, callId, null, null, null));
    }

    void stopped(StepPermit step, long turn, String fingerprint, String modelChildId, String responseHash,
                 Proposal source, String outputsHash) {
        if (source.decision() != Decision.ADMITTED || source.turnIndex() >= turn || !fingerprint.equals(source.fingerprint())
                || !proposal(step, source.turnIndex()).orElseThrow().equals(source)) throw failure("EXPLORATION_PROGRESS_SOURCE_CHANGED");
        save(step, new Proposal(turn, fingerprint, modelChildId, responseHash, Decision.STOP, null,
                source.turnIndex(), source.callId(), outputsHash));
    }

    private void save(StepPermit step, Proposal expected) {
        requireFactTransaction(); current(step);
        hash(expected.fingerprint()); hash(expected.responseHash());
        if (expected.outputsHash() != null) hash(expected.outputsHash());
        var prior = proposal(step, expected.turnIndex());
        if (prior.isPresent()) {
            if (!prior.get().equals(expected)) throw failure("EXPLORATION_PROGRESS_PROPOSAL_CHANGED");
            return;
        }
        String runId = step.runToken().definition().runId();
        jdbc.update("INSERT INTO campaign_exploration_proposal (run_id,revision,step_id," + COLUMNS + ",created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", runId, step.runToken().definition().revision(), step.stepId(),
                expected.turnIndex(), expected.fingerprint(), expected.modelChildId(), expected.responseHash(), expected.decision().name(),
                expected.callId(), expected.sourceTurnIndex(), expected.sourceCallId(), expected.outputsHash(), clock.millis());
        if (expected.decision() == Decision.STOP && jdbc.update("UPDATE campaign_exploration_progress SET stop_events=stop_events+1,"
                        + "row_version=row_version+1,updated_at=? WHERE run_id=? AND owner_hash=?", clock.millis(), runId, ownerHash(step)) != 1)
            throw failure("EXPLORATION_PROGRESS_OWNER_CHANGED");
    }

    private List<Usage> usages(StepPermit step) {
        return jdbc.query("SELECT owner_hash,stop_events FROM campaign_exploration_progress WHERE run_id=? FOR UPDATE", (rs, row) -> {
            if (!ownerHash(step).equals(rs.getString(1))) throw new SecurityException("EXPLORATION_PROGRESS_OWNER_CHANGED");
            long stops = rs.getLong(2);
            if (stops < 0) throw failure("EXPLORATION_PROGRESS_CORRUPTED");
            return new Usage(stops);
        }, step.runToken().definition().runId());
    }

    private void current(StepPermit step) {
        if (step == null || step.runToken() == null || step.stepId() == null || step.attemptId() == null || step.attemptVersion() < 1)
            throw new SecurityException("EXPLORATION_PROGRESS_STEP_FENCED");
        var token = step.runToken(); var definition = token.definition(); var owner = definition.caller();
        // Lock the same database/transaction as the proposal facts, independently of the supplied
        // StepStore implementation. Consistent snapshot reads cannot authorize a revoked writer.
        var runMatches = jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,run_status,row_version,advance_token "
                        + "FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> owner.tenantId().equals(rs.getString("tenant_id")) && owner.subject().equals(rs.getString("subject_name"))
                        && owner.authVersion() == rs.getLong("auth_version") && definition.sessionId().equals(rs.getString("session_id"))
                        && definition.planId().equals(rs.getString("plan_id")) && definition.definitionHash().equals(rs.getString("definition_hash"))
                        && "ACTIVE".equals(rs.getString("run_status")) && token.version() == rs.getLong("row_version")
                        && Objects.equals(token.advanceToken(), rs.getString("advance_token")), definition.runId(), definition.revision());
        if (runMatches.size() != 1 || !runMatches.get(0)) throw new SecurityException("EXPLORATION_PROGRESS_RUN_FENCED");
        var stepMatches = jdbc.query("SELECT step_status,callback_active,attempt_id,attempt_version,dispatch_run_version,dispatch_run_token "
                        + "FROM campaign_step_ledger WHERE run_id=? AND revision=? AND step_id=? FOR UPDATE",
                (rs, row) -> "RUNNING".equals(rs.getString("step_status")) && rs.getBoolean("callback_active")
                        && step.attemptId().equals(rs.getString("attempt_id")) && step.attemptVersion() == rs.getLong("attempt_version")
                        && token.version() == rs.getLong("dispatch_run_version") && Objects.equals(token.advanceToken(), rs.getString("dispatch_run_token")),
                definition.runId(), definition.revision(), step.stepId());
        if (stepMatches.size() != 1 || !stepMatches.get(0) || !steps.mayExecute(step))
            throw new SecurityException("EXPLORATION_PROGRESS_STEP_FENCED");
    }

    private void requireFactTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource())))
            throw failure("EXPLORATION_PROGRESS_REQUIRES_FACT_TRANSACTION");
    }

    private static Proposal read(ResultSet rs) throws SQLException {
        long source = rs.getLong("source_turn_index"); Long sourceTurn = rs.wasNull() ? null : source;
        return new Proposal(rs.getLong("turn_index"), rs.getString("fingerprint"), rs.getString("model_child_id"),
                rs.getString("response_hash"), Decision.valueOf(rs.getString("decision")), rs.getString("call_id"),
                sourceTurn, rs.getString("source_call_id"), rs.getString("outputs_hash"));
    }
    private static String ownerHash(StepPermit step) {
        var definition = step.runToken().definition(); var owner = definition.caller();
        return CampaignRunStore.sha256(owner.tenantId().length() + ":" + owner.tenantId() + owner.subject().length() + ":" + owner.subject()
                + definition.sessionId().length() + ":" + definition.sessionId());
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw failure("EXPLORATION_PROGRESS_HASH_INVALID");
    }
    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
}
