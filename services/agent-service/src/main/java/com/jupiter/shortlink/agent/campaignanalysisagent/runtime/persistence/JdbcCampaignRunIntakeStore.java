package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable typed request facts, not authentication, planning or execution authority. Header lookup
 * reads no proposal payload; the trusted admission factory must verify the current principal and
 * profile before definition(). A bounded proposal copy is retained after freezing for exact replay.
 */
public final class JdbcCampaignRunIntakeStore {
    public enum State { PENDING, FROZEN }
    public record Identity(String requestId, String runId, String planId) {}
    public record Header(String requestId, Caller caller, String sessionId, String requestKey,
                         String profileRef, String profileVersion, String runId, String planId,
                         int revision, String definitionHash, String requestHash, State state) {}

    private static final String HEADER_COLUMNS = "request_id,tenant_id,subject_name,auth_version,session_id,request_key,"
            + "profile_ref,profile_version,run_id,plan_id,revision,definition_hash,request_hash,request_state";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final JdbcCampaignRunStore runs;
    private final int maxDefinitionBytes;

    public JdbcCampaignRunIntakeStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                     JdbcCampaignRunStore runs, int maxDefinitionBytes) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        if (maxDefinitionBytes < 1) throw invalid("INTAKE_DEFINITION_LIMIT_INVALID");
        this.maxDefinitionBytes = maxDefinitionBytes;
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || !runs.sharesTransactionDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED || transactions.isReadOnly())
            throw invalid("INTAKE_REQUIRES_SHARED_TRANSACTION");
    }

    /** Assembly check: intake loading and atomic freezing must use the same Run store. */
    public boolean usesRunStore(CampaignRunStore candidate) { return runs == candidate; }

    /** authVersion is checked but excluded from identity: reauthorization cannot bypass same-key conflict. */
    public static Identity identity(Caller caller, String sessionId, String requestKey) {
        caller(caller); id(sessionId, 96); text(requestKey, 256);
        String digest = digest("campaign-intake-identity/v1", caller.tenantId(), caller.subject(), sessionId, requestKey);
        return new Identity("intake-" + digest, "campaign-run-" + digest, "campaign-plan-" + digest);
    }

    /** No JSON parsing, Artifact reads, models or runtime construction occurs during registration. */
    public Header register(Caller caller, String sessionId, String requestKey, String profileRef,
                           String profileVersion, RunDefinition definition) {
        Identity identity = identity(caller, sessionId, requestKey);
        id(profileRef, 128); text(profileVersion, 128);
        validateDefinition(definition, caller, sessionId, identity.runId(), identity.planId());
        String definitionHash = definition.definitionHash();
        Header expected = new Header(identity.requestId(), caller, sessionId, requestKey, profileRef, profileVersion,
                identity.runId(), identity.planId(), 1, definitionHash,
                requestHash(identity.requestId(), caller, sessionId, requestKey, profileRef, profileVersion,
                        identity.runId(), identity.planId(), definitionHash), State.PENDING);
        return tx(() -> {
            // A no-op duplicate-key update obtains the existing row lock without throwing a unique
            // violation inside a REQUIRED transaction. Never overwrite the original request fields.
            jdbc.update("INSERT INTO campaign_run_intake (" + HEADER_COLUMNS + ",proposal_json,created_at,frozen_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,NULL) ON DUPLICATE KEY UPDATE request_id=request_id",
                    expected.requestId(), caller.tenantId(), caller.subject(), caller.authVersion(), sessionId, requestKey,
                    profileRef, profileVersion, expected.runId(), expected.planId(), 1, definitionHash,
                    expected.requestHash(), State.PENDING.name(), definition.definitionJson(), clock.millis());
            Header stored = required(expected.requestId(), true);
            sameRequest(expected, stored);
            return stored;
        });
    }

    /** Short routing metadata only. WorkRef is an identifier, not an authorization token. */
    public Header header(WorkRef reference) {
        Objects.requireNonNull(reference);
        id(reference.workId(), 96); id(reference.runId(), 96);
        return tx(() -> {
            Header stored = required(reference.workId(), false);
            require(stored.runId().equals(reference.runId()), "INTAKE_WORK_REFERENCE_CHANGED");
            return stored;
        });
    }

    /** Payload access is deliberately separate so admission can authorize the header first. */
    public RunDefinition definition(Header expected) {
        validateHeader(expected);
        return tx(() -> {
            Header stored = required(expected.requestId(), true);
            sameRequest(expected, stored);
            return readDefinition(stored);
        });
    }

    /** Receipt and initial Run are committed atomically; FROZEN replay never recreates a Run. */
    public Header freeze(Header expected, RunDefinition definition) {
        validateHeader(expected);
        validateDefinition(definition, expected.caller(), expected.sessionId(), expected.runId(), expected.planId());
        require(expected.definitionHash().equals(definition.definitionHash()), "INTAKE_DEFINITION_CHANGED");
        return tx(() -> {
            Header stored = required(expected.requestId(), true);
            sameRequest(expected, stored);
            require(readDefinition(stored).equals(definition), "INTAKE_DEFINITION_CHANGED");
            if (stored.state() == State.FROZEN) return stored;
            runs.createRun(definition);
            require(jdbc.update("UPDATE campaign_run_intake SET request_state='FROZEN',frozen_at=? "
                            + "WHERE request_id=? AND request_state='PENDING' AND definition_hash=? AND request_hash=?",
                    clock.millis(), stored.requestId(), stored.definitionHash(), stored.requestHash()) == 1,
                    "INTAKE_FREEZE_CONFLICT");
            return required(stored.requestId(), true);
        });
    }

    private RunDefinition readDefinition(Header header) {
        List<String> payloads = jdbc.query("SELECT proposal_json FROM campaign_run_intake WHERE request_id=? AND definition_hash=? FOR UPDATE",
                (rs, row) -> rs.getString(1), header.requestId(), header.definitionHash());
        require(payloads.size() == 1, "INTAKE_DEFINITION_MISSING");
        String body = payloads.get(0);
        bounded(body);
        require(header.definitionHash().equals(CampaignRunStore.sha256(body)), "INTAKE_DEFINITION_CORRUPTED");
        return new RunDefinition(header.caller(), header.sessionId(), header.runId(), header.planId(), header.revision(), body);
    }

    private Header required(String requestId, boolean lock) {
        List<Header> rows = jdbc.query("SELECT " + HEADER_COLUMNS + " FROM campaign_run_intake WHERE request_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> readHeader(rs), requestId);
        require(rows.size() == 1, "INTAKE_REQUEST_NOT_FOUND");
        return rows.get(0);
    }

    private static Header readHeader(ResultSet rs) throws SQLException {
        State state;
        try { state = State.valueOf(rs.getString("request_state")); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw failure("INTAKE_HEADER_CORRUPTED"); }
        Header header = new Header(rs.getString("request_id"), new Caller(rs.getString("tenant_id"), rs.getString("subject_name"),
                rs.getLong("auth_version")), rs.getString("session_id"), rs.getString("request_key"), rs.getString("profile_ref"),
                rs.getString("profile_version"), rs.getString("run_id"), rs.getString("plan_id"), rs.getInt("revision"),
                rs.getString("definition_hash"), rs.getString("request_hash"), state);
        validateHeader(header);
        return header;
    }

    private static void validateHeader(Header header) {
        Objects.requireNonNull(header);
        Identity identity = identity(header.caller(), header.sessionId(), header.requestKey());
        id(header.profileRef(), 128); text(header.profileVersion(), 128);
        require(identity.requestId().equals(header.requestId()) && identity.runId().equals(header.runId())
                        && identity.planId().equals(header.planId()) && header.revision() == 1 && header.state() != null
                        && header.definitionHash() != null && header.definitionHash().matches("[a-f0-9]{64}")
                        && requestHash(header.requestId(), header.caller(), header.sessionId(), header.requestKey(), header.profileRef(),
                            header.profileVersion(), header.runId(), header.planId(), header.definitionHash()).equals(header.requestHash()),
                "INTAKE_HEADER_CORRUPTED");
    }

    private void validateDefinition(RunDefinition definition, Caller caller, String sessionId, String runId, String planId) {
        Objects.requireNonNull(definition);
        require(caller.equals(definition.caller()) && sessionId.equals(definition.sessionId()) && runId.equals(definition.runId())
                && planId.equals(definition.planId()) && definition.revision() == 1, "INTAKE_DEFINITION_IDENTITY_CHANGED");
        bounded(definition.definitionJson());
    }

    private static void sameRequest(Header expected, Header actual) {
        // A pending reader may observe the same request after freezing. No other field may change.
        require(new Header(expected.requestId(), expected.caller(), expected.sessionId(), expected.requestKey(), expected.profileRef(),
                        expected.profileVersion(), expected.runId(), expected.planId(), expected.revision(), expected.definitionHash(),
                        expected.requestHash(), actual.state()).equals(actual)
                        && !(expected.state() == State.FROZEN && actual.state() == State.PENDING), "INTAKE_REQUEST_CHANGED");
    }

    private static String requestHash(String requestId, Caller caller, String session, String key, String profile, String version,
                                      String runId, String planId, String definitionHash) {
        return digest("campaign-intake-request/v1", requestId, caller.tenantId(), caller.subject(), Long.toString(caller.authVersion()),
                session, key, profile, version, runId, planId, "1", definitionHash);
    }

    private static String digest(String... values) {
        StringBuilder content = new StringBuilder();
        for (String value : values) content.append(value.length()).append(':').append(value);
        return CampaignRunStore.sha256(content.toString());
    }

    /** Count UTF-8 without materializing another full payload, and reject ambiguous lone surrogates. */
    private void bounded(String text) {
        if (text == null || text.isBlank()) throw invalid("INTAKE_DEFINITION_REQUIRED");
        long bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value < 0x80) bytes++;
            else if (value < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(value) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                bytes += 4; index++;
            } else {
                if (Character.isSurrogate(value)) throw invalid("INTAKE_DEFINITION_UNICODE_INVALID");
                bytes += 3;
            }
            if (bytes > maxDefinitionBytes) throw invalid("INTAKE_DEFINITION_TOO_LARGE");
        }
    }

    private static void caller(Caller caller) {
        Objects.requireNonNull(caller); id(caller.tenantId(), 96); text(caller.subject(), 128);
        if (caller.authVersion() < 1) throw invalid("INTAKE_CURRENT_AUTH_REQUIRED");
    }
    private static void id(String value, int maximum) {
        text(value, maximum);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) throw invalid("INTAKE_REFERENCE_INVALID");
    }
    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw invalid("INTAKE_FIELD_INVALID");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) index++;
            else if (Character.isSurrogate(character)) throw invalid("INTAKE_FIELD_INVALID");
        }
    }
    private <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
