package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable pre-plan interpretation. A lost model outcome is never permission to send again. */
public final class CampaignPublicRequestStore {
    public record Request(WorkRef reference, Caller caller, String sessionId, String requestKey,
            String question, Instant createdAt, Instant expiresAt, String state, String response,
            String targetWorkId, String reasonCode, boolean callbackActive, Instant cancelledAt) {
        public boolean cancelled() { return cancelledAt != null; }
    }
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
        return read(ref, false);
    }

    private Request read(WorkRef ref, boolean lock) {
        List<Request> found = jdbc.query("SELECT * FROM campaign_public_request WHERE request_id=? AND run_id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Request(ref, new Caller(rs.getString("tenant_id"), rs.getString("subject_name"),
                        rs.getLong("auth_version")), rs.getString("session_id"), rs.getString("request_key"),
                        rs.getString("question_text"), Instant.ofEpochMilli(rs.getLong("created_at")),
                        Instant.ofEpochMilli(rs.getLong("expires_at")), rs.getString("request_state"),
                        rs.getString("response_json"), rs.getString("target_work_id"), rs.getString("reason_code"),
                        rs.getBoolean("callback_active"), rs.getObject("cancelled_at") == null ? null
                                : Instant.ofEpochMilli(rs.getLong("cancelled_at"))),
                ref.workId(), ref.runId());
        if (found.size() != 1) throw new IllegalArgumentException("CAMPAIGN_REQUEST_NOT_FOUND");
        return found.get(0);
    }

    /** The original request row serializes cancellation with planning acceptance and Run creation. */
    public void lockUncancelledForCommit(Caller caller, String session, String runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource())))
            throw new IllegalStateException("CAMPAIGN_CANCEL_GUARD_REQUIRES_SHARED_TRANSACTION");
        var refs = jdbc.query("SELECT request_id FROM campaign_public_request WHERE run_id=? FOR UPDATE",
                (rs, row) -> new WorkRef(runId, rs.getString(1)), runId);
        // Typed-only callers predate public interpretation and have no cancellation row.
        if (refs.isEmpty()) return;
        if (refs.size() != 1) throw new IllegalStateException("CAMPAIGN_REQUEST_CHANGED");
        Request request = read(refs.get(0), true);
        requireIdentity(request, caller, session);
        requireUncancelled(request);
    }

    /** Current principal/session authorization belongs to the public service, before acquiring locks. */
    public Request cancel(Caller caller, String session, WorkRef ref, JdbcCampaignRunStore runs) {
        if (!runs.sharesTransactionDataSource(jdbc))
            throw new IllegalArgumentException("CAMPAIGN_CANCEL_REQUIRES_SHARED_TRANSACTION");
        return transactions.execute(status -> {
            Request request = read(ref, true);
            requireIdentity(request, caller, session);
            jdbc.update("UPDATE campaign_public_request SET cancelled_at=COALESCE(cancelled_at,?) WHERE request_id=? AND run_id=?",
                    clock.millis(), ref.workId(), ref.runId());
            // A locking current read closes both initial-freeze and revision-replacement races.
            var tokens = jdbc.query("SELECT * FROM campaign_run_ledger WHERE run_id=? ORDER BY revision DESC LIMIT 1 FOR UPDATE",
                    (rs, row) -> {
                        var definition = new CampaignRunStore.RunDefinition(new Caller(rs.getString("tenant_id"),
                                rs.getString("subject_name"), rs.getLong("auth_version")), rs.getString("session_id"),
                                rs.getString("run_id"), rs.getString("plan_id"), rs.getInt("revision"), rs.getString("definition_json"));
                        if (!caller.equals(definition.caller()) || !session.equals(definition.sessionId())
                                || !JdbcCampaignRunIntakeStore.identity(caller, session, request.requestKey()).planId().equals(definition.planId())
                                || !definition.definitionHash().equals(rs.getString("definition_hash")))
                            throw new SecurityException("CAMPAIGN_CANCEL_ACCESS_DENIED");
                        return new CampaignRunStore.RunRecord(definition, CampaignRunStore.RunStatus.valueOf(rs.getString("run_status")),
                                rs.getLong("row_version"), rs.getString("advance_token"));
                    }, ref.runId());
            if (!tokens.isEmpty() && tokens.get(0).status() == CampaignRunStore.RunStatus.ACTIVE)
                runs.cancel(tokens.get(0).token());
            // An issued callback retains its slot until its own finally. Cancel never clears it.
            return read(ref, true);
        });
    }

    public void requireUncancelled(WorkRef ref) { requireUncancelled(read(ref)); }

    private static void requireUncancelled(Request request) {
        if (request.cancelled()) throw new IllegalStateException("CAMPAIGN_REQUEST_CANCELLED");
    }

    private static void requireIdentity(Request request, Caller caller, String session) {
        if (!request.caller().equals(caller) || !request.sessionId().equals(session))
            throw new SecurityException("CAMPAIGN_REQUEST_ACCESS_DENIED");
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
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CAMPAIGN_INTERPRETATION_REQUIRES_COMMITTED_PREPARATION");
        return transactions.execute(status -> {
            requireUncancelled(read(expected.reference(), true));
            String attempt = UUID.randomUUID().toString();
            int changed = jdbc.update("UPDATE campaign_public_request SET request_state='DISPATCHING',callback_active=TRUE,attempt_id=?,"
                    + "prompt_hash=? WHERE request_id=? AND request_state='PREPARED' AND expires_at>?",
                    attempt, promptHash, expected.reference().workId(), clock.millis());
            if (changed != 1) throw new IllegalStateException("CAMPAIGN_INTERPRETATION_UNRESOLVED");
            return attempt;
        });
    }

    public void complete(Request expected, String attempt, String response) {
        if (response == null || response.length() > 1024 * 1024)
            throw new IllegalArgumentException("CAMPAIGN_INTERPRETATION_RESPONSE_INVALID");
        transactions.executeWithoutResult(status -> {
            requireUncancelled(read(expected.reference(), true));
            if (jdbc.update("UPDATE campaign_public_request SET request_state='READY',response_json=?"
                    + " WHERE request_id=? AND attempt_id=? AND request_state='DISPATCHING' AND expires_at>?",
                    response, expected.reference().workId(), attempt, clock.millis()) != 1)
                throw new IllegalStateException("CAMPAIGN_INTERPRETATION_CHANGED");
        });
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
        transactions.executeWithoutResult(status -> {
            requireUncancelled(read(expected.reference(), true));
            if (jdbc.update("UPDATE campaign_public_request SET request_state='ACCEPTED',target_work_id=?"
                    + " WHERE request_id=? AND request_state='READY' AND callback_active=FALSE AND target_work_id IS NULL",
                    target.workId(), expected.reference().workId()) != 1) {
                Request current = read(expected.reference());
                if (!"ACCEPTED".equals(current.state()) || !target.workId().equals(current.targetWorkId()))
                    throw new IllegalStateException("CAMPAIGN_INTERPRETATION_CHANGED");
            }
        });
    }

    public void needsInput(Request expected, String reason) {
        transactions.executeWithoutResult(status -> {
            requireUncancelled(read(expected.reference(), true));
            jdbc.update("UPDATE campaign_public_request SET request_state='NEEDS_INPUT',reason_code=?"
                    + " WHERE request_id=? AND request_state='READY'", reason, expected.reference().workId());
        });
    }
}
