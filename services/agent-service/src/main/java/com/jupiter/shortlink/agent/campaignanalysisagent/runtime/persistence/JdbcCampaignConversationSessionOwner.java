package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Server-side ownership of the client-provided conversation id. Only a trusted entry that has
 * revalidated the current account may bind it. Background recovery may read but never create a
 * binding. This store does not replace the independent current-account authority check.
 */
public final class JdbcCampaignConversationSessionOwner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcCampaignConversationSessionOwner(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("CAMPAIGN_SESSION_REQUIRES_SHARED_TRANSACTION");
    }

    /** Bind after authoritative account verification. A later auth version fences the old epoch. */
    public void bindVerified(AgentPrincipal verified, String sessionId) {
        Caller owner = caller(verified);
        session(sessionId);
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("INSERT INTO campaign_conversation_session_owner "
                            + "(session_id,tenant_id,subject_name,auth_version,bound_at) VALUES (?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE session_id=session_id",
                    sessionId, owner.tenantId(), owner.subject(), owner.authVersion(), clock.millis());
            Row stored = one(sessionId, true);
            if (!owner.tenantId().equals(stored.tenantId()) || !owner.subject().equals(stored.subjectName())
                    || owner.authVersion() < stored.authVersion())
                throw new SecurityException("CAMPAIGN_SESSION_OWNER_CHANGED");
            if (owner.authVersion() > stored.authVersion()) {
                if (jdbc.update("UPDATE campaign_conversation_session_owner SET auth_version=?,bound_at=? "
                                + "WHERE session_id=? AND tenant_id=? AND subject_name=? AND auth_version=?",
                        owner.authVersion(), clock.millis(), sessionId, owner.tenantId(), owner.subject(),
                        stored.authVersion()) != 1)
                    throw new SecurityException("CAMPAIGN_SESSION_OWNER_CHANGED");
            }
        });
    }

    /** Possession of the id is insufficient; an exact immutable Caller must still own it. */
    public void requireOwner(Caller expected, String sessionId) {
        Objects.requireNonNull(expected);
        session(sessionId);
        Row stored = one(sessionId, false);
        if (!expected.tenantId().equals(stored.tenantId())
                || !expected.subject().equals(stored.subjectName())
                || expected.authVersion() != stored.authVersion())
            throw new SecurityException("CAMPAIGN_SESSION_OWNER_CHANGED");
    }

    private Row one(String sessionId, boolean lock) {
        List<Row> rows = jdbc.query("SELECT tenant_id,subject_name,auth_version FROM "
                        + "campaign_conversation_session_owner WHERE session_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3)), sessionId);
        if (rows.size() != 1) throw new SecurityException("CAMPAIGN_SESSION_OWNER_MISSING");
        return rows.get(0);
    }

    private static Caller caller(AgentPrincipal verified) {
        if (verified == null || verified.system())
            throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        return new Caller(verified.tenantId(), verified.username(), verified.authVersion());
    }

    private static void session(String value) {
        if (value == null || value.isBlank() || value.length() > 96
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
            throw new IllegalArgumentException("CAMPAIGN_SESSION_INVALID");
    }

    private record Row(String tenantId, String subjectName, long authVersion) {}
}
