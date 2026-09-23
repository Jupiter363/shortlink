package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.Header;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One durable, bounded outcome row per registered intake request. This records the last admitted
 * advance pass, not business Goal completion or proof that a crashed worker has exited.
 */
public final class JdbcCampaignAdvanceOutcomeStore {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String INVALID_PLAN = "INVALID_PLAN";
    public static final String RUNTIME_UNAVAILABLE = "RUNTIME_UNAVAILABLE";
    public static final String ADVANCE_BLOCKED = "ADVANCE_BLOCKED";

    private static final Set<String> REASONS = Set.of(ACCESS_DENIED, INVALID_PLAN,
            RUNTIME_UNAVAILABLE, ADVANCE_BLOCKED);
    private static final String BINDING_COLUMNS = "request_id,tenant_id,subject_name,auth_version,session_id,"
            + "request_key,profile_ref,profile_version,run_id,plan_id,revision,definition_hash,request_hash";

    public record Attempt(Header header, long version) {
        public Attempt {
            Objects.requireNonNull(header);
            if (version < 1) throw new IllegalArgumentException("ADVANCE_VERSION_INVALID");
        }
    }

    public record Outcome(long version, String status, String reason) {
        public Outcome {
            if (version < 1 || status == null || !Set.of(RUNNING, SUCCEEDED, FAILED).contains(status)
                    || (FAILED.equals(status) ? reason == null || !REASONS.contains(reason) : reason != null))
                throw new IllegalArgumentException("ADVANCE_OUTCOME_INVALID");
        }
    }

    private record Binding(String requestId, Caller caller, String sessionId, String requestKey,
                           String profileRef, String profileVersion, String runId, String planId,
                           int revision, String definitionHash, String requestHash) {}

    private record Row(Binding binding, Outcome outcome) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcCampaignAdvanceOutcomeStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("ADVANCE_OUTCOME_REQUIRES_SHARED_TRANSACTION");
    }

    /** The intake row is the authority for all immutable request fields; a new attempt replaces only its own row. */
    public Attempt begin(Header header) {
        Binding expected = binding(header);
        return tx(() -> {
            Binding registered = intake(expected.requestId());
            same(expected, registered);
            Row previous = row(expected.requestId(), true).orElse(null);
            long version;
            if (previous == null) {
                version = 1;
                jdbc.update("INSERT INTO campaign_advance_outcome (" + BINDING_COLUMNS
                                + ",attempt_version,attempt_status,reason_code,updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        expected.requestId(), expected.caller().tenantId(), expected.caller().subject(),
                        expected.caller().authVersion(), expected.sessionId(), expected.requestKey(),
                        expected.profileRef(), expected.profileVersion(), expected.runId(), expected.planId(),
                        expected.revision(), expected.definitionHash(), expected.requestHash(),
                        version, RUNNING, null, clock.millis());
            } else {
                same(expected, previous.binding());
                if (previous.outcome().version() == Long.MAX_VALUE)
                    throw new IllegalStateException("ADVANCE_VERSION_EXHAUSTED");
                version = previous.outcome().version() + 1;
                if (jdbc.update("UPDATE campaign_advance_outcome SET attempt_version=?,attempt_status=?,"
                                + "reason_code=NULL,updated_at=? WHERE request_id=? AND attempt_version=?"
                                + " AND request_hash=? AND definition_hash=?",
                        version, RUNNING, clock.millis(), expected.requestId(), previous.outcome().version(),
                        expected.requestHash(), expected.definitionHash()) != 1)
                    throw new IllegalStateException("ADVANCE_VERSION_CHANGED");
            }
            return new Attempt(header, version);
        });
    }

    /** A pass completing does not mean the business result or any remote job is complete. */
    public void succeeded(Attempt attempt) {
        finish(attempt, SUCCEEDED, null);
    }

    public void failed(Attempt attempt, String reason) {
        if (reason == null || !REASONS.contains(reason))
            throw new IllegalArgumentException("ADVANCE_REASON_INVALID");
        finish(attempt, FAILED, reason);
    }

    /** Empty means no durable attempt outcome, not that work is running or failed. */
    public Optional<Outcome> latest(Header header) {
        Binding expected = binding(header);
        return tx(() -> {
            Optional<Row> stored = row(expected.requestId(), false);
            stored.ifPresent(value -> same(expected, value.binding()));
            return stored.map(Row::outcome);
        });
    }

    private void finish(Attempt attempt, String status, String reason) {
        Objects.requireNonNull(attempt);
        Binding expected = binding(attempt.header());
        tx(() -> {
            Row stored = row(expected.requestId(), true)
                    .orElseThrow(() -> new SecurityException("ADVANCE_OUTCOME_IDENTITY_CHANGED"));
            same(expected, stored.binding());
            if (stored.outcome().version() != attempt.version() || !RUNNING.equals(stored.outcome().status()))
                return null; // A newer attempt or an already terminal pass always wins.
            if (jdbc.update("UPDATE campaign_advance_outcome SET attempt_status=?,reason_code=?,updated_at=?"
                            + " WHERE request_id=? AND attempt_version=? AND attempt_status='RUNNING'"
                            + " AND request_hash=? AND definition_hash=?",
                    status, reason, clock.millis(), expected.requestId(), attempt.version(),
                    expected.requestHash(), expected.definitionHash()) != 1)
                throw new IllegalStateException("ADVANCE_OUTCOME_CHANGED");
            return null;
        });
    }

    private Binding intake(String requestId) {
        List<Binding> matches = jdbc.query("SELECT " + BINDING_COLUMNS
                        + " FROM campaign_run_intake WHERE request_id=? FOR UPDATE",
                (rs, index) -> readBinding(rs), requestId);
        if (matches.size() != 1) throw new SecurityException("ADVANCE_OUTCOME_IDENTITY_CHANGED");
        return matches.get(0);
    }

    private Optional<Row> row(String requestId, boolean lock) {
        List<Row> matches = jdbc.query("SELECT " + BINDING_COLUMNS
                        + ",attempt_version,attempt_status,reason_code FROM campaign_advance_outcome"
                        + " WHERE request_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> new Row(readBinding(rs), new Outcome(rs.getLong("attempt_version"),
                        rs.getString("attempt_status"), rs.getString("reason_code"))), requestId);
        if (matches.size() > 1) throw new IllegalStateException("ADVANCE_OUTCOME_DUPLICATE");
        return matches.stream().findFirst();
    }

    private static Binding readBinding(ResultSet rs) throws SQLException {
        return new Binding(rs.getString("request_id"),
                new Caller(rs.getString("tenant_id"), rs.getString("subject_name"), rs.getLong("auth_version")),
                rs.getString("session_id"), rs.getString("request_key"), rs.getString("profile_ref"),
                rs.getString("profile_version"), rs.getString("run_id"), rs.getString("plan_id"),
                rs.getInt("revision"), rs.getString("definition_hash"), rs.getString("request_hash"));
    }

    private static Binding binding(Header header) {
        Objects.requireNonNull(header);
        Objects.requireNonNull(header.caller());
        var identity = JdbcCampaignRunIntakeStore.identity(header.caller(), header.sessionId(), header.requestKey());
        if (!identity.requestId().equals(header.requestId()) || !identity.runId().equals(header.runId())
                || !identity.planId().equals(header.planId()) || header.revision() != 1
                || header.profileRef() == null || header.profileRef().isBlank()
                || header.profileVersion() == null || header.profileVersion().isBlank()
                || header.definitionHash() == null || !header.definitionHash().matches("[a-f0-9]{64}")
                || header.requestHash() == null || !header.requestHash().matches("[a-f0-9]{64}"))
            throw new SecurityException("ADVANCE_OUTCOME_IDENTITY_CHANGED");
        return new Binding(header.requestId(), header.caller(), header.sessionId(), header.requestKey(),
                header.profileRef(), header.profileVersion(), header.runId(), header.planId(), header.revision(),
                header.definitionHash(), header.requestHash());
    }

    private static void same(Binding expected, Binding actual) {
        if (!expected.equals(actual)) throw new SecurityException("ADVANCE_OUTCOME_IDENTITY_CHANGED");
    }

    private <T> T tx(Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }
}
