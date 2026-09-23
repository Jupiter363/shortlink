package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable pre-plan interpretation. A lost model outcome is never permission to send again. */
public final class CampaignPublicRequestStore {
    public record Request(WorkRef reference, Caller caller, String sessionId, String requestKey,
            String question, Instant createdAt, Instant expiresAt, String state, String response,
            String targetWorkId, String reasonCode, boolean callbackActive) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CampaignPublicRequestStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || transactions.isReadOnly()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("PUBLIC_REQUEST_TRANSACTION_REQUIRED");
    }

    public Request register(Caller caller, String session, String requestKey, String question, Instant expiresAt) {
        if (question == null || question.isBlank() || question.length() > 16000)
            throw new IllegalArgumentException("CAMPAIGN_QUESTION_INVALID");
        var identity = JdbcCampaignRunIntakeStore.identity(caller, session, requestKey);
        var ref = new WorkRef(identity.runId(), "request-" + identity.requestId().substring("intake-".length()));
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO campaign_public_request (request_id,run_id,tenant_id,subject_name,auth_version,"
                    + "session_id,request_key,question_text,question_hash,created_at,expires_at,request_state)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'PREPARED') ON DUPLICATE KEY UPDATE request_id=request_id",
                    ref.workId(), ref.runId(), caller.tenantId(), caller.subject(), caller.authVersion(), session,
                    requestKey, question, CampaignRunStore.sha256(question), clock.millis(), expiresAt.toEpochMilli());
            Request stored = read(ref);
            if (!stored.caller().equals(caller) || !stored.sessionId().equals(session)
                    || !stored.requestKey().equals(requestKey) || !stored.question().equals(question))
                throw new IllegalArgumentException("CAMPAIGN_REQUEST_KEY_REUSED");
            return stored;
        });
    }

    public Request read(WorkRef ref) {
        List<Request> found = jdbc.query("SELECT * FROM campaign_public_request WHERE request_id=? AND run_id=?",
                (rs, row) -> new Request(ref, new Caller(rs.getString("tenant_id"), rs.getString("subject_name"),
                        rs.getLong("auth_version")), rs.getString("session_id"), rs.getString("request_key"),
                        rs.getString("question_text"), Instant.ofEpochMilli(rs.getLong("created_at")),
                        Instant.ofEpochMilli(rs.getLong("expires_at")), rs.getString("request_state"),
                        rs.getString("response_json"), rs.getString("target_work_id"), rs.getString("reason_code"),
                        rs.getBoolean("callback_active")),
                ref.workId(), ref.runId());
        if (found.size() != 1) throw new IllegalArgumentException("CAMPAIGN_REQUEST_NOT_FOUND");
        return found.get(0);
    }

    /** Trusted current caller only; identity survives reauthorization and an absent conversation turn. */
    public boolean exists(Caller caller, String session, String requestKey) {
        var identity = JdbcCampaignRunIntakeStore.identity(caller, session, requestKey);
        String requestId = "request-" + identity.requestId().substring("intake-".length());
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_public_request WHERE request_id=? AND run_id=?"
                        + " AND tenant_id=? AND subject_name=? AND session_id=? AND request_key=?",
                Integer.class, requestId, identity.runId(), caller.tenantId(), caller.subject(), session, requestKey);
        return found != null && found > 0;
    }

    /** Repair a crash between committed registration and timer enrollment using short identifiers only. */
    public List<WorkRef> undiscovered(int maximum) {
        if (maximum<1 || maximum>1024) throw new IllegalArgumentException("CAMPAIGN_DISCOVERY_BATCH_INVALID");
        return jdbc.query("SELECT p.run_id,p.request_id FROM campaign_public_request p LEFT JOIN campaign_due_work d"
                + " ON d.work_id=p.request_id WHERE d.work_id IS NULL ORDER BY p.request_id LIMIT ?",
                (rs,row)->new WorkRef(rs.getString(1),rs.getString(2)),maximum);
    }

    public String begin(Request expected, String promptHash) {
        String attempt = UUID.randomUUID().toString();
        int changed = jdbc.update("UPDATE campaign_public_request SET request_state='DISPATCHING',callback_active=TRUE,attempt_id=?,"
                + "prompt_hash=? WHERE request_id=? AND request_state='PREPARED' AND expires_at>?",
                attempt, promptHash, expected.reference().workId(), clock.millis());
        if (changed != 1) throw new IllegalStateException("CAMPAIGN_INTERPRETATION_UNRESOLVED");
        return attempt;
    }

    public void complete(Request expected, String attempt, String response) {
        if (response == null || response.length() > 1024 * 1024)
            throw new IllegalArgumentException("CAMPAIGN_INTERPRETATION_RESPONSE_INVALID");
        if (jdbc.update("UPDATE campaign_public_request SET request_state='READY',response_json=?"
                + " WHERE request_id=? AND attempt_id=? AND request_state='DISPATCHING' AND expires_at>?",
                response, expected.reference().workId(), attempt, clock.millis()) != 1)
            throw new IllegalStateException("CAMPAIGN_INTERPRETATION_CHANGED");
    }

    public void unknown(Request expected, String attempt) {
        jdbc.update("UPDATE campaign_public_request SET request_state='UNKNOWN',reason_code='MODEL_OUTCOME_UNKNOWN'"
                + " WHERE request_id=? AND attempt_id=? AND request_state='DISPATCHING'", expected.reference().workId(), attempt);
    }

    public void callbackExited(Request expected, String attempt) {
        jdbc.update("UPDATE campaign_public_request SET callback_active=FALSE WHERE request_id=? AND attempt_id=?",
                expected.reference().workId(), attempt);
    }

    public void bind(Request expected, WorkRef target) {
        if (!expected.reference().runId().equals(target.runId())) throw new IllegalArgumentException("CAMPAIGN_RUN_CHANGED");
        if (jdbc.update("UPDATE campaign_public_request SET request_state='ACCEPTED',target_work_id=?"
                + " WHERE request_id=? AND request_state='READY' AND callback_active=FALSE AND target_work_id IS NULL",
                target.workId(), expected.reference().workId()) != 1) {
            Request current = read(expected.reference());
            if (!"ACCEPTED".equals(current.state()) || !target.workId().equals(current.targetWorkId()))
                throw new IllegalStateException("CAMPAIGN_INTERPRETATION_CHANGED");
        }
    }

    public void needsInput(Request expected, String reason) {
        jdbc.update("UPDATE campaign_public_request SET request_state='NEEDS_INPUT',reason_code=?"
                + " WHERE request_id=? AND request_state='READY'", reason, expected.reference().workId());
    }
}
