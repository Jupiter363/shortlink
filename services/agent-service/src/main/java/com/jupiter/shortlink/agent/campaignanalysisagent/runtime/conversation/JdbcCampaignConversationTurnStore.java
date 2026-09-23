package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable request context, not report history or an authorization grant. */
public final class JdbcCampaignConversationTurnStore {
    public record Turn(String requestKey, String originalQuestion, String frozenSelectionJson, String mode,
                       WorkRef workRef, String previousRunId, Instant expiresAt, Instant createdAt) {}

    private static final String COLUMNS = "request_key,original_question,frozen_selection_json,runtime_mode,"
            + "run_id,request_id,previous_run_id,expires_at,created_at,tenant_id,subject_name,auth_version,session_id";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcCampaignConversationSessionOwner sessions;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcCampaignConversationTurnStore(JdbcTemplate jdbc, TransactionTemplate transactions,
            JdbcCampaignConversationSessionOwner sessions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.sessions = Objects.requireNonNull(sessions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("CAMPAIGN_TURN_REQUIRES_SHARED_TRANSACTION");
    }

    /** Caller must already be freshly verified and bound through the trusted session entry. */
    public Turn recordVerified(AgentPrincipal verified, String sessionId, String requestKey,
            String originalQuestion, String frozenSelectionJson, String mode, WorkRef reference,
            String previousRunId, Instant expiresAt) {
        Caller caller = authorize(verified, sessionId);
        var identity = JdbcCampaignRunIntakeStore.identity(caller, sessionId, requestKey);
        if (reference == null || !identity.runId().equals(reference.runId())
                || !(identity.requestId().equals(reference.workId())
                    || ("request-" + identity.requestId().substring("intake-".length())).equals(reference.workId())))
            throw new IllegalArgumentException("CAMPAIGN_TURN_REFERENCE_CHANGED");
        text(originalQuestion, 16_000, "CAMPAIGN_TURN_QUESTION_INVALID");
        text(mode, 64, "CAMPAIGN_TURN_MODE_INVALID");
        selection(frozenSelectionJson);
        Objects.requireNonNull(expiresAt, "CAMPAIGN_TURN_EXPIRY_REQUIRED");
        if (!expiresAt.isAfter(clock.instant()))
            throw new IllegalArgumentException("CAMPAIGN_TURN_EXPIRY_INVALID");
        if (reference.runId().equals(previousRunId))
            throw new IllegalArgumentException("CAMPAIGN_TURN_SELF_PREDECESSOR");

        return transactions.execute(ignored -> {
            // Recheck ownership inside the write transaction; another account cannot reuse a session.
            sessions.requireOwner(caller, sessionId);
            if (previousRunId != null) requireRun(caller, sessionId, previousRunId);
            jdbc.update("INSERT INTO campaign_conversation_turn (" + COLUMNS + ") "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",
                    requestKey, originalQuestion, frozenSelectionJson, mode, reference.runId(), reference.workId(),
                    previousRunId, expiresAt.toEpochMilli(), clock.millis(), caller.tenantId(), caller.subject(),
                    caller.authVersion(), sessionId);
            Row row = required(reference, true);
            requireOwner(row, caller, sessionId);
            Turn stored = row.turn();
            if (!stored.requestKey().equals(requestKey) || !stored.originalQuestion().equals(originalQuestion)
                    || !stored.frozenSelectionJson().equals(frozenSelectionJson) || !stored.mode().equals(mode)
                    || !Objects.equals(stored.previousRunId(), previousRunId))
                throw new IllegalStateException("CAMPAIGN_TURN_REQUEST_CONFLICT");
            // Concurrent retries keep the first deadline, never extend or recreate the frozen input.
            return stored;
        });
    }

    public Optional<Turn> findByKey(AgentPrincipal current, String sessionId, String requestKey) {
        Caller caller = authorize(current, sessionId);
        var identity = JdbcCampaignRunIntakeStore.identity(caller, sessionId, requestKey);
        List<Row> rows = select("run_id=?", identity.runId());
        if (rows.isEmpty()) return Optional.empty();
        requireOwner(rows.get(0), caller, sessionId);
        return Optional.of(rows.get(0).turn());
    }

    /** Read-only resume/progress lookup. It cannot create a request or refresh its expiry. */
    public Turn require(AgentPrincipal current, String sessionId, WorkRef reference) {
        Caller caller = authorize(current, sessionId);
        Row row = required(reference, false);
        requireOwner(row, caller, sessionId);
        return row.turn();
    }

    public Turn requireRun(AgentPrincipal current, String sessionId, String runId) {
        Caller caller = authorize(current, sessionId);
        List<Row> rows = select("run_id=?", runId);
        if (rows.size()!=1) throw new SecurityException("CAMPAIGN_TURN_PREDECESSOR_UNAVAILABLE");
        requireOwner(rows.get(0), caller, sessionId);
        return rows.get(0).turn();
    }

    private Caller authorize(AgentPrincipal principal, String sessionId) {
        if (principal == null || principal.system())
            throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        Caller caller = new Caller(principal.tenantId(), principal.username(), principal.authVersion());
        sessions.requireOwner(caller, sessionId);
        return caller;
    }

    private void requireRun(Caller caller, String sessionId, String runId) {
        List<Row> rows = select("run_id=?", runId);
        if (rows.size() != 1) throw new SecurityException("CAMPAIGN_TURN_PREDECESSOR_UNAVAILABLE");
        requireOwner(rows.get(0), caller, sessionId);
    }

    private Row required(WorkRef reference, boolean lock) {
        Objects.requireNonNull(reference, "CAMPAIGN_TURN_REFERENCE_REQUIRED");
        List<Row> rows = select("request_id=? AND run_id=?" + (lock ? " FOR UPDATE" : ""),
                reference.workId(), reference.runId());
        if (rows.size() != 1) throw new SecurityException("CAMPAIGN_TURN_UNAVAILABLE");
        return rows.get(0);
    }

    private List<Row> select(String predicate, Object... args) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_conversation_turn WHERE " + predicate,
                (rs, index) -> read(rs), args);
    }

    private static Row read(ResultSet rs) throws SQLException {
        Turn turn = new Turn(rs.getString("request_key"), rs.getString("original_question"),
                rs.getString("frozen_selection_json"), rs.getString("runtime_mode"),
                new WorkRef(rs.getString("run_id"), rs.getString("request_id")),
                rs.getString("previous_run_id"), Instant.ofEpochMilli(rs.getLong("expires_at")),
                Instant.ofEpochMilli(rs.getLong("created_at")));
        return new Row(turn, new Caller(rs.getString("tenant_id"), rs.getString("subject_name"),
                rs.getLong("auth_version")), rs.getString("session_id"));
    }

    private static void requireOwner(Row row, Caller caller, String sessionId) {
        if (!row.caller().equals(caller) || !row.sessionId().equals(sessionId))
            throw new SecurityException("CAMPAIGN_TURN_OWNER_CHANGED");
    }

    private void selection(String json) {
        text(json, 256_000, "CAMPAIGN_TURN_SELECTION_INVALID");
        try {
            if (!mapper.readTree(json).isObject()) throw new IllegalArgumentException("CAMPAIGN_TURN_SELECTION_INVALID");
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("CAMPAIGN_TURN_SELECTION_INVALID", invalid);
        }
    }

    private static void text(String value, int max, String error) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > max)
            throw new IllegalArgumentException(error);
    }

    private record Row(Turn turn, Caller caller, String sessionId) {}
}
