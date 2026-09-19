package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessIdentity;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness.Observation;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness.State;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single takeover attempt, without Spring registration, polling, or native callback simulation.
 * Process observations run outside transactions; both ledger phases lock revisions in ascending
 * order. Only a complete matching snapshot with definitive death proofs may release callbacks.
 */
public final class JdbcCampaignRecoveryStore implements CampaignRecoveryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ProcessIdentity localOwner;
    private final ProcessLiveness liveness;
    private final JdbcCampaignRunStore runs;
    private final JdbcCampaignStepStore steps;

    public JdbcCampaignRecoveryStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                    ProcessIdentity localOwner, ProcessLiveness liveness) {
        this(jdbc, transactions, clock, localOwner, liveness, Limits.defaults());
    }

    public JdbcCampaignRecoveryStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                    ProcessIdentity localOwner, ProcessLiveness liveness, Limits limits) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.localOwner = Objects.requireNonNull(localOwner);
        this.liveness = Objects.requireNonNull(liveness);
        Objects.requireNonNull(limits);
        if (localOwner.processDomain().length() > 256)
            throw new IllegalArgumentException("Process domain exceeds ledger identity limit");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Recovery requires one writable REQUIRED DataSource transaction");
        this.runs = new JdbcCampaignRunStore(jdbc, transactions, clock, limits);
        this.steps = new JdbcCampaignStepStore(jdbc, transactions, clock, limits);
    }

    @Override
    public TakeoverResult recover(RunToken expected) {
        Objects.requireNonNull(expected);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("RECOVERY_REQUIRES_TOP_LEVEL");
        try {
            Snapshot snapshot = transaction(() -> readLocked(expected));
            Map<ProcessIdentity, Observation> observations = new LinkedHashMap<>();
            Observation local = observe(localOwner);
            if (local.state() != State.ALIVE) return blocked("LOCAL_PROCESS_NOT_ALIVE");
            observations.put(localOwner, local);
            for (Callback callback : snapshot.callbacks()) {
                if (callback.owner() == null) return blocked("CALLBACK_OWNER_MISSING");
                if (callback.attemptId() == null || callback.writerToken() == null || callback.attemptVersion() < 1)
                    return blocked("CALLBACK_IDENTITY_MISSING");
                Observation proof = observations.computeIfAbsent(callback.owner(), this::observe);
                if (proof.state() != State.DEAD) return blocked(proof.state() == State.ALIVE
                        ? "CALLBACK_OWNER_ALIVE" : "CALLBACK_OWNER_UNKNOWN");
            }
            return transaction(() -> {
                Snapshot current = readLocked(expected);
                if (!snapshot.equals(current)) throw new RecoveryConflict("CALLBACK_SNAPSHOT_CHANGED");
                for (Callback callback : snapshot.callbacks()) {
                    Observation proof = observations.get(callback.owner());
                    clearCallback(expected.definition().runId(), callback);
                    audit(expected.definition().runId(), callback, proof.reasonCode());
                }
                if (current.run().status() != RunStatus.ACTIVE)
                    return new TakeoverResult(Outcome.STOPPED, null, "RUN_" + current.run().status().name(), snapshot.callbacks().size());
                RunToken acquired = steps.acquireRun(expected);
                jdbc.update("INSERT INTO campaign_run_owner (run_id,revision,run_version,advance_token,instance_id,"
                                + "process_domain,pid,started_at_millis,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        acquired.definition().runId(), acquired.definition().revision(), acquired.version(), acquired.advanceToken(),
                        localOwner.instanceId(), localOwner.processDomain(), localOwner.pid(), localOwner.startedAtMillis(), clock.millis());
                return new TakeoverResult(Outcome.ACQUIRED, acquired, "RUN_ACQUIRED", snapshot.callbacks().size());
            });
        } catch (RecoveryConflict conflict) {
            return blocked(conflict.getMessage());
        }
    }

    private Snapshot readLocked(RunToken expected) {
        List<LockedRevision> revisions = jdbc.query("SELECT revision,run_status,row_version,advance_token FROM campaign_run_ledger "
                        + "WHERE run_id=? ORDER BY revision ASC FOR UPDATE",
                (rs, row) -> new LockedRevision(rs.getInt("revision"), rs.getString("run_status"),
                        rs.getLong("row_version"), rs.getString("advance_token")), expected.definition().runId());
        if (revisions.isEmpty()) throw new IllegalStateException("RUN_NOT_FOUND");
        // loadRun validates the current exact tenant/subject/authVersion and the stored definition hash.
        RunRecord current = runs.loadRun(expected.definition().caller(), expected.definition().runId())
                .orElseThrow(() -> new IllegalStateException("RUN_NOT_FOUND"));
        LockedRevision latest = revisions.get(revisions.size() - 1);
        if (!current.token().equals(expected) || current.definition().revision() != latest.revision()
                || current.version() != latest.version() || !current.advanceToken().equals(latest.advanceToken())
                || !current.status().name().equals(latest.status())) throw new RecoveryConflict("RUN_CHANGED");
        List<Callback> callbacks = new ArrayList<>();
        callbacks.addAll(jdbc.query("SELECT c.revision,c.child_id AS callback_id,c.attempt_id,c.attempt_version,"
                        + "c.dispatch_run_version,c.dispatch_run_token,c.child_state AS callback_state,c.child_mode,"
                        + "c.job_id,c.artifact_id,c.unresolved_reason AS callback_reason,c.wire_hash,"
                        + "o.instance_id,o.process_domain,o.pid,o.started_at_millis "
                        + "FROM campaign_child_ledger c LEFT JOIN campaign_run_owner o ON o.run_id=c.run_id AND o.revision=c.revision "
                        + "AND o.run_version=c.dispatch_run_version AND o.advance_token=c.dispatch_run_token "
                        + "WHERE c.run_id=? AND c.callback_active=TRUE",
                (rs, row) -> callback(rs, "CHILD"), expected.definition().runId()));
        callbacks.addAll(jdbc.query("SELECT s.revision,s.step_id AS callback_id,s.attempt_id,s.attempt_version,"
                        + "s.dispatch_run_version,s.dispatch_run_token,s.step_status AS callback_state,s.reason AS callback_reason,"
                        + "s.row_version,o.instance_id,o.process_domain,o.pid,o.started_at_millis "
                        + "FROM campaign_step_ledger s LEFT JOIN campaign_run_owner o ON o.run_id=s.run_id AND o.revision=s.revision "
                        + "AND o.run_version=s.dispatch_run_version AND o.advance_token=s.dispatch_run_token "
                        + "WHERE s.run_id=? AND s.callback_active=TRUE",
                (rs, row) -> callback(rs, "STEP"), expected.definition().runId()));
        callbacks.sort(Comparator.comparingInt(Callback::revision).thenComparing(Callback::kind).thenComparing(Callback::id));
        return new Snapshot(current, List.copyOf(callbacks));
    }

    private Callback callback(ResultSet rs, String kind) throws SQLException {
        String instanceId = rs.getString("instance_id");
        ProcessIdentity owner = null;
        if (instanceId != null) {
            try {
                owner = new ProcessIdentity(instanceId, rs.getString("process_domain"), rs.getLong("pid"),
                        rs.getLong("started_at_millis"));
            } catch (IllegalArgumentException invalid) {
                throw new RecoveryConflict("CALLBACK_OWNER_INVALID");
            }
        }
        boolean child = kind.equals("CHILD");
        return new Callback(kind, rs.getInt("revision"), rs.getString("callback_id"), rs.getString("attempt_id"),
                rs.getLong("attempt_version"), rs.getLong("dispatch_run_version"), rs.getString("dispatch_run_token"),
                rs.getString("callback_state"), rs.getString("callback_reason"),
                child ? rs.getString("child_mode") : null, child ? rs.getString("job_id") : null,
                child ? rs.getString("artifact_id") : null, child ? rs.getString("wire_hash") : null,
                child ? 0 : rs.getLong("row_version"), owner);
    }

    private void clearCallback(String runId, Callback callback) {
        String state = callback.state();
        String reason = callback.reason();
        int changed;
        if (callback.kind().equals("CHILD")) {
            if (state.equals("DISPATCHING")) {
                state = "UNRESOLVED";
                reason = callback.mode().equals("LOCAL") ? "LOCAL_RESULT_UNKNOWN"
                        : callback.mode().equals("SYNC") ? "READ_RESULT_UNKNOWN"
                        : callback.jobId() == null ? "SUBMISSION_UNRESOLVED" : "JOB_RESULT_UNKNOWN";
            }
            changed = jdbc.update("UPDATE campaign_child_ledger SET callback_active=FALSE,child_state=?,unresolved_reason=?,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND child_id=? AND attempt_id=? AND attempt_version=? "
                            + "AND dispatch_run_version=? AND dispatch_run_token=? AND callback_active=TRUE",
                    state, reason, clock.millis(), runId, callback.revision(), callback.id(), callback.attemptId(),
                    callback.attemptVersion(), callback.writerVersion(), callback.writerToken());
        } else {
            if (state.equals("RUNNING")) {
                state = "BLOCKED";
                reason = "STEP_RESULT_UNKNOWN";
            }
            changed = jdbc.update("UPDATE campaign_step_ledger SET callback_active=FALSE,step_status=?,reason=?,"
                            + "row_version=row_version+1,updated_at=? WHERE run_id=? AND revision=? AND step_id=? "
                            + "AND attempt_id=? AND attempt_version=? AND dispatch_run_version=? AND dispatch_run_token=? "
                            + "AND callback_active=TRUE",
                    state, reason, clock.millis(), runId, callback.revision(), callback.id(), callback.attemptId(),
                    callback.attemptVersion(), callback.writerVersion(), callback.writerToken());
        }
        if (changed != 1) throw new RecoveryConflict("CALLBACK_SNAPSHOT_CHANGED");
    }

    private void audit(String runId, Callback callback, String proofCode) {
        jdbc.update("INSERT INTO campaign_callback_recovery (run_id,revision,callback_kind,callback_id,attempt_id,"
                        + "attempt_version,writer_version,writer_token,owner_instance_id,recovery_instance_id,proof_code,recovered_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, callback.revision(), callback.kind(), callback.id(), callback.attemptId(), callback.attemptVersion(),
                callback.writerVersion(), callback.writerToken(), callback.owner().instanceId(), localOwner.instanceId(),
                proofCode, clock.millis());
    }

    private Observation observe(ProcessIdentity identity) {
        try {
            Observation observation = liveness.observe(identity);
            return observation == null ? new Observation(State.UNKNOWN, ProcessLiveness.PROCESS_PROBE_UNSUPPORTED) : observation;
        } catch (RuntimeException unavailable) {
            return new Observation(State.UNKNOWN, ProcessLiveness.PROCESS_PROBE_UNSUPPORTED);
        }
    }

    private <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static TakeoverResult blocked(String reason) { return new TakeoverResult(Outcome.BLOCKED, null, reason, 0); }
    private record Snapshot(RunRecord run, List<Callback> callbacks) {}
    private record LockedRevision(int revision, String status, long version, String advanceToken) {}
    private record Callback(String kind, int revision, String id, String attemptId, long attemptVersion,
                            long writerVersion, String writerToken, String state, String reason, String mode,
                            String jobId, String artifactId, String wireHash, long stepVersion, ProcessIdentity owner) {}
    private static final class RecoveryConflict extends RuntimeException {
        private RecoveryConflict(String reason) { super(reason); }
    }
}
