package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable model fact, not an executable Step or a public report. No method calls a model. */
public final class JdbcCampaignReportSynthesisStore {
    public record Snapshot(String synthesisId, String evidenceHash, String state, String response,
            boolean callbackActive, Instant expiresAt) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcCampaignReportSynthesisStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || transactions.isReadOnly()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("REPORT_SYNTHESIS_TRANSACTION_REQUIRED");
    }

    public Snapshot prepare(RunToken token, String evidenceHash, Instant expiresAt) {
        if (evidenceHash == null || !evidenceHash.matches("[a-f0-9]{64}") || expiresAt == null
                || !expiresAt.isAfter(clock.instant())) throw new IllegalArgumentException("REPORT_SYNTHESIS_INPUT_INVALID");
        String id = CampaignRunStore.sha256(token.definition().definitionHash() + ":" + evidenceHash);
        return transactions.execute(status -> {
            current(token);
            jdbc.update("INSERT INTO campaign_report_synthesis (synthesis_id,run_id,revision,definition_hash,evidence_hash,"
                            + "synthesis_state,created_at,expires_at) VALUES (?,?,?,?,?,'PREPARED',?,?)"
                            + " ON DUPLICATE KEY UPDATE synthesis_id=synthesis_id", id, token.definition().runId(),
                    token.definition().revision(), token.definition().definitionHash(), evidenceHash, clock.millis(), expiresAt.toEpochMilli());
            return readLocked(token, id, evidenceHash);
        });
    }

    public Snapshot read(RunToken token, Snapshot expected) {
        return transactions.execute(status -> {
            current(token);
            return readLocked(token, expected.synthesisId(), expected.evidenceHash());
        });
    }

    /** Commit admission before entering the actual provider callback. Only PREPARED can dispatch. */
    public String begin(RunToken token, Snapshot expected, String promptHash) {
        if (promptHash == null || !promptHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("REPORT_SYNTHESIS_PROMPT_INVALID");
        return transactions.execute(status -> {
            current(token);
            Snapshot stored = readLocked(token, expected.synthesisId(), expected.evidenceHash());
            if (!"PREPARED".equals(stored.state()) || stored.callbackActive())
                throw new IllegalStateException("REPORT_SYNTHESIS_UNRESOLVED");
            String attempt = UUID.randomUUID().toString();
            if (jdbc.update("UPDATE campaign_report_synthesis SET synthesis_state='DISPATCHING',callback_active=TRUE,"
                            + "attempt_id=?,prompt_hash=? WHERE synthesis_id=? AND synthesis_state='PREPARED' AND callback_active=FALSE",
                    attempt, promptHash, stored.synthesisId()) != 1) throw new IllegalStateException("REPORT_SYNTHESIS_CHANGED");
            return attempt;
        });
    }

    /** Persist a real bounded native response before parsing it. Invalid JSON remains a known response. */
    public void complete(RunToken token, Snapshot expected, String attempt, String response) {
        if (response == null) throw new IllegalArgumentException("REPORT_SYNTHESIS_RESPONSE_INVALID");
        transactions.executeWithoutResult(status -> {
            current(token);
            readLocked(token, expected.synthesisId(), expected.evidenceHash());
            if (jdbc.update("UPDATE campaign_report_synthesis SET synthesis_state='READY',response_json=?,response_hash=?"
                            + " WHERE synthesis_id=? AND attempt_id=? AND synthesis_state='DISPATCHING' AND callback_active=TRUE",
                    response, CampaignRunStore.sha256(response), expected.synthesisId(), attempt) != 1)
                throw new IllegalStateException("REPORT_SYNTHESIS_CHANGED");
        });
    }

    /** A late worker may close its own attempt even after its writer token was fenced. */
    public void unknown(Snapshot expected, String attempt) {
        jdbc.update("UPDATE campaign_report_synthesis SET synthesis_state='UNKNOWN' WHERE synthesis_id=?"
                + " AND attempt_id=? AND synthesis_state='DISPATCHING'", expected.synthesisId(), attempt);
    }

    /** Only the actual callback's finally calls this; request timeout/cancellation must not. */
    public void callbackExited(Snapshot expected, String attempt) {
        jdbc.update("UPDATE campaign_report_synthesis SET callback_active=FALSE WHERE synthesis_id=? AND attempt_id=?",
                expected.synthesisId(), attempt);
    }

    private Snapshot readLocked(RunToken token, String id, String evidenceHash) {
        List<Snapshot> found = jdbc.query("SELECT * FROM campaign_report_synthesis WHERE synthesis_id=? FOR UPDATE", (rs, row) -> {
            if (!token.definition().runId().equals(rs.getString("run_id"))
                    || token.definition().revision() != rs.getInt("revision")
                    || !token.definition().definitionHash().equals(rs.getString("definition_hash"))
                    || !evidenceHash.equals(rs.getString("evidence_hash"))
                    || rs.getLong("expires_at") <= clock.millis()) throw new SecurityException("REPORT_SYNTHESIS_CHANGED");
            String response = rs.getString("response_json");
            if ("READY".equals(rs.getString("synthesis_state")) && (response == null
                    || !CampaignRunStore.sha256(response).equals(rs.getString("response_hash"))))
                throw new SecurityException("REPORT_SYNTHESIS_RESPONSE_CHANGED");
            return new Snapshot(id, evidenceHash, rs.getString("synthesis_state"), response,
                    rs.getBoolean("callback_active"), Instant.ofEpochMilli(rs.getLong("expires_at")));
        }, id);
        if (found.size() != 1) throw new IllegalStateException("REPORT_SYNTHESIS_NOT_FOUND");
        return found.get(0);
    }

    /** Match the complete frozen identity under the normal run -> synthesis lock order. */
    private void current(RunToken token) {
        var definition = token.definition();
        List<Boolean> matches = jdbc.query("SELECT * FROM campaign_run_ledger WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> definition.caller().tenantId().equals(rs.getString("tenant_id"))
                        && definition.caller().subject().equals(rs.getString("subject_name"))
                        && definition.caller().authVersion() == rs.getLong("auth_version")
                        && definition.sessionId().equals(rs.getString("session_id"))
                        && definition.planId().equals(rs.getString("plan_id"))
                        && definition.definitionHash().equals(rs.getString("definition_hash"))
                        && "ACTIVE".equals(rs.getString("run_status"))
                        && token.version() == rs.getLong("row_version")
                        && token.advanceToken().equals(rs.getString("advance_token")), definition.runId(), definition.revision());
        if (matches.size() != 1 || !matches.get(0)) throw new SecurityException("REPORT_SYNTHESIS_TOKEN_FENCED");
    }
}
