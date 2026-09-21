package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.CapacityKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC implementation of the bounded, owner-scoped submission-deferral discovery boundary. */
public final class JdbcCampaignSubmissionDeferralReader implements CampaignSubmissionDeferralReader {
    private static final int MAX_PENDING_LIMIT = 256;
    private static final String CORRUPTED = "CAPACITY_PENDING_CORRUPTED";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcCampaignSubmissionDeferralReader(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("Capacity pending reader requires one shared REQUIRED DataSource transaction");
    }

    @Override
    public List<DueCandidate> pendingDue(Caller caller, long nowMillis, int limit) {
        validateCaller(caller);
        if (nowMillis < 0) throw new IllegalArgumentException("CAPACITY_PENDING_NOW_INVALID");
        if (limit < 1 || limit > MAX_PENDING_LIMIT)
            throw new IllegalArgumentException("CAPACITY_PENDING_LIMIT_INVALID");
        return transaction(() -> jdbc.query(
                "SELECT d.run_id AS deferral_run_id,d.revision AS deferral_revision,d.child_id AS deferral_child_id,"
                        + "d.request_id AS deferral_request_id,d.wire_hash AS deferral_wire_hash,"
                        + "d.capacity_kind,d.rejected_attempts,d.retry_not_before,d.last_attempt_id,d.last_attempt_version,"
                        + "l.run_id AS ledger_run_id,l.revision AS ledger_revision,l.tenant_id AS ledger_tenant_id,"
                        + "l.subject_name AS ledger_subject_name,l.auth_version AS ledger_auth_version,l.run_status,l.row_version,"
                        + "c.run_id AS child_run_id,c.revision AS child_revision,c.child_id AS ledger_child_id,"
                        + "c.action_id AS child_action_id,c.tenant_id AS child_tenant_id,c.child_mode,c.request_id AS child_request_id,"
                        + "c.wire_hash AS child_wire_hash,c.child_state,c.job_id,c.artifact_id,c.attempt_id,c.attempt_version,"
                        + "c.attempt_purpose,c.callback_active,c.unresolved_reason,"
                        + "a.run_id AS action_run_id,a.revision AS action_revision,a.action_id AS ledger_action_id,a.step_id AS action_step_id,"
                        + "s.run_id AS step_run_id,s.revision AS step_revision,s.step_id AS ledger_step_id "
                        + "FROM campaign_submission_deferral d "
                        + "JOIN campaign_run_ledger l ON l.run_id=d.run_id AND l.revision=d.revision "
                        + "JOIN campaign_child_ledger c ON c.run_id=d.run_id AND c.revision=d.revision AND c.child_id=d.child_id "
                        + "JOIN campaign_action_ledger a ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id "
                        + "JOIN campaign_step_ledger s ON s.run_id=a.run_id AND s.revision=a.revision AND s.step_id=a.step_id "
                        + "WHERE l.tenant_id=? AND l.subject_name=? AND l.auth_version=? "
                        + "AND l.run_status='ACTIVE' AND d.retry_not_before<=? "
                        + "AND c.child_mode='ASYNC' AND c.child_state='PREPARED' "
                        + "AND c.unresolved_reason='QUERY_CAPACITY_EXHAUSTED' AND c.attempt_purpose='FRESH' "
                        + "AND c.job_id IS NULL AND c.artifact_id IS NULL AND c.callback_active=FALSE "
                        + "ORDER BY d.retry_not_before ASC,d.run_id ASC,d.revision ASC,d.child_id ASC LIMIT ?",
                (rs, row) -> candidate(rs, caller, nowMillis), caller.tenantId(), caller.subject(),
                caller.authVersion(), nowMillis, limit));
    }

    private DueCandidate candidate(ResultSet rs, Caller expected, long nowMillis) throws SQLException {
        String runId = rs.getString("deferral_run_id");
        int revision = rs.getInt("deferral_revision");
        String childId = rs.getString("deferral_child_id");
        String stepId = rs.getString("action_step_id");
        String actionId = rs.getString("child_action_id");
        String requestId = rs.getString("deferral_request_id");
        String wireHash = rs.getString("deferral_wire_hash");
        String childRequestId = rs.getString("child_request_id");
        String childWireHash = rs.getString("child_wire_hash");
        String lastAttemptId = rs.getString("last_attempt_id");
        String childAttemptId = rs.getString("attempt_id");
        long lastAttemptVersion = rs.getLong("last_attempt_version");
        long childAttemptVersion = rs.getLong("attempt_version");
        long retryNotBefore = rs.getLong("retry_not_before");
        int rejectedAttempts = rs.getInt("rejected_attempts");
        long sourceRunVersion = rs.getLong("row_version");
        String capacityKind = rs.getString("capacity_kind");

        try {
            Caller ledgerOwner = new Caller(rs.getString("ledger_tenant_id"),
                    rs.getString("ledger_subject_name"), rs.getLong("ledger_auth_version"));
            String ledgerRunId = rs.getString("ledger_run_id");
            int ledgerRevision = rs.getInt("ledger_revision");
            String childRunId = rs.getString("child_run_id");
            int childRevision = rs.getInt("child_revision");
            String ledgerChildId = rs.getString("ledger_child_id");
            String actionRunId = rs.getString("action_run_id");
            int actionRevision = rs.getInt("action_revision");
            String ledgerActionId = rs.getString("ledger_action_id");
            String stepRunId = rs.getString("step_run_id");
            int stepRevision = rs.getInt("step_revision");
            String ledgerStepId = rs.getString("ledger_step_id");
            String childTenantId = rs.getString("child_tenant_id");

            require(expected.equals(ledgerOwner)
                            && same(runId, ledgerRunId, childRunId, actionRunId, stepRunId)
                            && revision == ledgerRevision && revision == childRevision
                            && revision == actionRevision && revision == stepRevision
                            && same(childId, ledgerChildId)
                            && same(actionId, ledgerActionId)
                            && same(stepId, ledgerStepId)
                            && expected.tenantId().equals(childTenantId)
                            && "ACTIVE".equals(rs.getString("run_status"))
                            && sourceRunVersion >= 0
                            && "ASYNC".equals(rs.getString("child_mode"))
                            && "PREPARED".equals(rs.getString("child_state"))
                            && "FRESH".equals(rs.getString("attempt_purpose"))
                            && "QUERY_CAPACITY_EXHAUSTED".equals(rs.getString("unresolved_reason"))
                            && !rs.getBoolean("callback_active")
                            && rs.getString("job_id") == null && rs.getString("artifact_id") == null
                            && validId(runId) && validId(stepId) && validId(childId) && validId(actionId)
                            && validRequestId(requestId) && requestId.equals(childRequestId)
                            && validHash(wireHash) && wireHash.equals(childWireHash)
                            && validAttemptId(lastAttemptId) && lastAttemptId.equals(childAttemptId)
                            && childAttemptVersion > 0 && lastAttemptVersion == childAttemptVersion
                            && rejectedAttempts > 0 && lastAttemptVersion >= rejectedAttempts
                            && retryNotBefore > 0 && retryNotBefore <= nowMillis,
                    CORRUPTED);
            CapacityKind kind = CapacityKind.valueOf(capacityKind);
            return new DueCandidate(runId, revision, stepId, childId, kind, rejectedAttempts,
                    retryNotBefore, sourceRunVersion);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw corrupted();
        }
    }

    private static boolean same(String expected, String... actual) {
        if (!validId(expected)) return false;
        for (String value : actual) if (!expected.equals(value)) return false;
        return true;
    }

    private static void validateCaller(Caller caller) {
        Objects.requireNonNull(caller, "CAPACITY_PENDING_CALLER_REQUIRED");
        if (!validId(caller.tenantId()) || !validText(caller.subject(), 128) || caller.authVersion() < 1)
            throw new IllegalArgumentException("CAPACITY_PENDING_CALLER_INVALID");
    }

    private static boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 96
                && value.chars().noneMatch(Character::isISOControl)
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,96}");
    }

    private static boolean validAttemptId(String value) {
        return value != null && value.length() <= 36 && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw corrupted();
    }

    private static IllegalStateException corrupted() {
        return new IllegalStateException(CORRUPTED);
    }

    private <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
