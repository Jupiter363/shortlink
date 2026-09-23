package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Approval;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.InvocationSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Response;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One durable, server-approved planning call before a business Run exists. This store neither
 * authenticates callers nor interprets candidates. Current authority and candidate validation are
 * supplied by the admitted planning boundary; possession of a Header or Permit is not authority.
 */
public final class JdbcCampaignPlanningStore {
    public enum State { PREPARED, DISPATCHING, READY, UNKNOWN, ACCEPTED, REJECTED }

    public record Header(String requestId, Caller caller, String sessionId, String requestKey,
                         String profileRef, String profileVersion, String runId, String planId,
                         String modelRef, String modelVersion, String configurationHash,
                         String requestHash, Instant expiresAt, State state, boolean callbackActive,
                         String invocationHash, String responseHash, String definitionHash,
                         String intakeRequestId, String reasonCode) {}

    public record Permit(String requestId, String attemptId, String invocationHash) {}

    private record Stored(Header header, String inputHash, String attemptId) {}

    private static final String COLUMNS = "request_id,tenant_id,subject_name,auth_version,session_id,request_key,"
            + "profile_ref,profile_version,run_id,plan_id,model_ref,model_version,configuration_hash,input_hash,"
            + "request_hash,expires_at,request_state,callback_active,attempt_id,invocation_hash,response_hash,"
            + "definition_hash,intake_request_id,reason_code";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final JdbcCampaignRunIntakeStore intake;
    private final int maxRequestBytes;
    private final ModelInvocationRegistry.Limits limits;

    public JdbcCampaignPlanningStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            JdbcCampaignRunIntakeStore intake, int maxRequestBytes, ModelInvocationRegistry.Limits limits) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.intake = Objects.requireNonNull(intake);
        this.limits = Objects.requireNonNull(limits);
        if (maxRequestBytes < 1) throw invalid("PLANNING_REQUEST_LIMIT_INVALID");
        this.maxRequestBytes = maxRequestBytes;
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || !intake.sharesTransactionDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly()) throw invalid("PLANNING_REQUIRES_SHARED_TRANSACTION");
    }

    /** The accepted receipt must route to the exact intake store used by its atomic acceptance. */
    public boolean usesIntakeStore(JdbcCampaignRunIntakeStore candidate) { return intake == candidate; }

    /** No JSON parse, Artifact read, native model construction or business Run creation. */
    public Header register(Caller caller, String sessionId, String requestKey, String profileRef,
            String profileVersion, String modelRef, String modelVersion, String configurationHash,
            String requestJson, Instant expiresAt) {
        var identity = JdbcCampaignRunIntakeStore.identity(caller, sessionId, requestKey);
        text(profileRef, 128); text(profileVersion, 128); text(modelRef, 256); text(modelVersion, 256);
        hash(configurationHash);
        bounded(requestJson, maxRequestBytes, "PLANNING_REQUEST_TOO_LARGE");
        Instant expiry = millis(expiresAt);
        String id = "planning-" + identity.requestId().substring("intake-".length());
        String inputHash = CampaignRunStore.sha256(requestJson);
        Header expected = new Header(id, caller, sessionId, requestKey, profileRef, profileVersion,
                identity.runId(), identity.planId(), modelRef, modelVersion, configurationHash,
                requestHash(id, caller, sessionId, requestKey, profileRef, profileVersion, modelRef,
                        modelVersion, configurationHash, inputHash, expiry), expiry, State.PREPARED,
                false, null, null, null, null, null);
        return tx(() -> {
            intake.guardCommit(caller, sessionId, identity.runId());
            requireCurrent(expected);
            jdbc.update("INSERT INTO campaign_planning_request (request_id,tenant_id,subject_name,auth_version,"
                            + "session_id,request_key,profile_ref,profile_version,run_id,plan_id,model_ref,model_version,"
                            + "configuration_hash,input_hash,request_hash,request_json,expires_at,request_state,"
                            + "callback_active,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',FALSE,?,?) "
                            + "ON DUPLICATE KEY UPDATE request_id=request_id",
                    id, caller.tenantId(), caller.subject(), caller.authVersion(), sessionId, requestKey,
                    profileRef, profileVersion, identity.runId(), identity.planId(), modelRef, modelVersion,
                    configurationHash, inputHash, expected.requestHash(), requestJson, expiry.toEpochMilli(),
                    clock.millis(), clock.millis());
            Stored stored = required(id, true);
            sameRequest(expected, stored.header());
            return stored.header();
        });
    }

    /** Short columns only; WorkRef does not bypass current principal/session authorization. */
    public Header header(WorkRef reference) {
        Objects.requireNonNull(reference);
        text(reference.workId(), 96); text(reference.runId(), 96);
        return tx(() -> {
            Header header = required(reference.workId(), false).header();
            require(header.runId().equals(reference.runId()), "PLANNING_WORK_REFERENCE_CHANGED");
            return header;
        });
    }

    /** Called only after admission and current principal verification by the caller. */
    public String request(Header expected) {
        return tx(() -> {
            Stored stored = matching(expected);
            requireCurrent(stored.header());
            return readRequest(stored);
        });
    }

    /** The first approved invocation and its actual callback are committed before provider I/O. */
    public Permit begin(Header expected, Approval approval) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw failure("PLANNING_REQUIRES_COMMITTED_PREPARATION");
        Objects.requireNonNull(approval);
        InvocationSpec invocation = approval.invocation();
        String encoded = ModelInvocationRegistry.encode(invocation);
        bounded(encoded, limits.invocationBytes(), "PLANNING_INVOCATION_TOO_LARGE");
        return tx(() -> {
            guardCommit(expected);
            Stored stored = matching(expected);
            Header header = stored.header();
            requireCurrent(header);
            readRequest(stored);
            requireInvocation(header, invocation);
            if (header.invocationHash() != null)
                require(header.invocationHash().equals(invocation.hash()), "PLANNING_INVOCATION_CHANGED");
            require(header.state() == State.PREPARED && !header.callbackActive(), "PLANNING_MODEL_ALREADY_STARTED");
            String attempt = UUID.randomUUID().toString();
            require(jdbc.update("UPDATE campaign_planning_request SET request_state='DISPATCHING',callback_active=TRUE,"
                            + "attempt_id=?,invocation_json=?,invocation_hash=?,updated_at=? WHERE request_id=? "
                            + "AND request_state='PREPARED' AND callback_active=FALSE",
                    attempt, encoded, invocation.hash(), clock.millis(), header.requestId()) == 1,
                    "PLANNING_DISPATCH_CONFLICT");
            return new Permit(header.requestId(), attempt, invocation.hash());
        });
    }

    public boolean mayDispatch(Permit permit) {
        return tx(() -> {
            guardCommit(required(permit.requestId(), false).header());
            Stored stored = permitted(permit);
            Header header = stored.header();
            return header.state() == State.DISPATCHING && header.callbackActive()
                    && clock.instant().isBefore(header.expiresAt());
        });
    }

    /** Public text only. READY does not release the callback or authorize candidate acceptance. */
    public void publish(Permit permit, Approval approval, Response response) {
        Objects.requireNonNull(approval); Objects.requireNonNull(response);
        require(response.toolCalls().isEmpty(), "PLANNING_TOOL_CALLS_FORBIDDEN");
        approval.validateResponse(response);
        String encoded = ModelInvocationRegistry.encodeResponse(response);
        bounded(encoded, limits.responseBytes(), "PLANNING_RESPONSE_TOO_LARGE");
        tx(() -> {
            guardCommit(required(permit.requestId(), false).header());
            Stored stored = permitted(permit);
            Header header = stored.header();
            requireCurrent(header);
            requireApproval(stored, approval);
            require(header.callbackActive(), "PLANNING_CALLBACK_NOT_ACTIVE");
            if (header.state() == State.READY) {
                require(readResponse(stored).equals(response), "PLANNING_RESPONSE_CHANGED");
                return null;
            }
            require(header.state() == State.DISPATCHING, "PLANNING_RESULT_NOT_DISPATCHING");
            require(jdbc.update("UPDATE campaign_planning_request SET request_state='READY',response_json=?,response_hash=?,"
                            + "reason_code=NULL,updated_at=? WHERE request_id=? AND request_state='DISPATCHING' AND callback_active=TRUE",
                    encoded, CampaignRunStore.sha256(encoded), clock.millis(), header.requestId()) == 1,
                    "PLANNING_RESPONSE_CONFLICT");
            return null;
        });
    }

    /** A lost acknowledgement after a committed READY response must not erase that response. */
    public void unknown(Permit permit) {
        tx(() -> {
            Stored stored = permitted(permit);
            if (stored.header().state() == State.DISPATCHING) {
                jdbc.update("UPDATE campaign_planning_request SET request_state='UNKNOWN',reason_code='MODEL_RESULT_UNKNOWN',"
                        + "updated_at=? WHERE request_id=?", clock.millis(), permit.requestId());
            }
            return null;
        });
    }

    /** Only the provider worker's actual finally may clear this flag, never Future cancellation. */
    public void callbackExited(Permit permit) {
        tx(() -> {
            Stored stored = permitted(permit);
            if (stored.header().state() == State.DISPATCHING) {
                jdbc.update("UPDATE campaign_planning_request SET request_state='UNKNOWN',reason_code='MODEL_RESULT_UNKNOWN',"
                                + "callback_active=FALSE,updated_at=? WHERE request_id=?",
                        clock.millis(), permit.requestId());
            } else if (stored.header().callbackActive()) {
                jdbc.update("UPDATE campaign_planning_request SET callback_active=FALSE,updated_at=? WHERE request_id=?",
                        clock.millis(), permit.requestId());
            }
            return null;
        });
    }

    /** Rebuild the registered Approval from saved facts; never invoke the native model for replay. */
    public InvocationSpec invocation(Header expected) {
        return tx(() -> {
            Stored stored = readable(expected);
            return readInvocation(stored);
        });
    }

    public Response response(Header expected, Approval approval) {
        Objects.requireNonNull(approval);
        return tx(() -> {
            Stored stored = readable(expected);
            requireApproval(stored, approval);
            Response response = readResponse(stored);
            approval.validateResponse(response);
            return response;
        });
    }

    /** Only trusted, currently authorized validation may call this with its exact typed definition. */
    public JdbcCampaignRunIntakeStore.Header accept(Header expected, RunDefinition definition) {
        Objects.requireNonNull(definition);
        return tx(() -> {
            guardCommit(expected);
            Stored stored = readable(expected);
            Header header = stored.header();
            requireSource(expected, header);
            readRequest(stored); readInvocation(stored); readResponse(stored);
            require(header.caller().equals(definition.caller()) && header.sessionId().equals(definition.sessionId())
                            && header.runId().equals(definition.runId()) && header.planId().equals(definition.planId())
                            && definition.revision() == 1, "PLANNING_DEFINITION_IDENTITY_CHANGED");
            if (header.state() == State.ACCEPTED) {
                require(header.definitionHash().equals(definition.definitionHash()), "PLANNING_DEFINITION_CHANGED");
                var accepted = intake.header(new WorkRef(header.runId(), header.intakeRequestId()));
                require(accepted.definitionHash().equals(definition.definitionHash())
                                && intake.definition(accepted).equals(definition), "PLANNING_ACCEPTANCE_CHANGED");
                return accepted;
            }
            var accepted = intake.register(header.caller(), header.sessionId(), header.requestKey(),
                    header.profileRef(), header.profileVersion(), definition);
            require(jdbc.update("UPDATE campaign_planning_request SET request_state='ACCEPTED',definition_hash=?,"
                            + "intake_request_id=?,updated_at=? WHERE request_id=? AND request_state='READY' AND callback_active=FALSE",
                    definition.definitionHash(), accepted.requestId(), clock.millis(), header.requestId()) == 1,
                    "PLANNING_ACCEPTANCE_CONFLICT");
            return accepted;
        });
    }

    /** Explicit candidate invalidity only; transport failures/unknown results cannot use this path. */
    public void reject(Header expected) {
        tx(() -> {
            Stored stored = matching(expected);
            Header header = stored.header();
            requireCurrent(header); requireSource(expected, header);
            require(!header.callbackActive(), "PLANNING_CALLBACK_STILL_ACTIVE");
            require(header.state() == State.READY || header.state() == State.REJECTED, "PLANNING_REJECTION_REQUIRES_READY");
            readInvocation(stored); readResponse(stored);
            if (header.state() == State.READY)
                jdbc.update("UPDATE campaign_planning_request SET request_state='REJECTED',reason_code='PLANNING_UNRESOLVED',"
                        + "updated_at=? WHERE request_id=?", clock.millis(), header.requestId());
            return null;
        });
    }

    private Stored readable(Header expected) {
        Stored stored = matching(expected);
        Header header = stored.header();
        requireCurrent(header);
        require(!header.callbackActive(), "PLANNING_CALLBACK_STILL_ACTIVE");
        require(header.state() == State.READY || header.state() == State.ACCEPTED, "PLANNING_RESPONSE_NOT_READY");
        if (expected.invocationHash() != null)
            require(expected.invocationHash().equals(header.invocationHash()), "PLANNING_INVOCATION_CHANGED");
        if (expected.responseHash() != null)
            require(expected.responseHash().equals(header.responseHash()), "PLANNING_RESPONSE_CHANGED");
        return stored;
    }

    private void guardCommit(Header header) {
        intake.guardCommit(header.caller(), header.sessionId(), header.runId());
    }

    private Stored matching(Header expected) {
        Objects.requireNonNull(expected);
        Stored stored = required(expected.requestId(), true);
        sameRequest(expected, stored.header());
        return stored;
    }

    private Stored permitted(Permit permit) {
        Objects.requireNonNull(permit);
        Stored stored = required(permit.requestId(), true);
        require(Objects.equals(stored.attemptId(), permit.attemptId()) && permit.attemptId() != null
                        && Objects.equals(stored.header().invocationHash(), permit.invocationHash())
                        && permit.invocationHash() != null, "PLANNING_ATTEMPT_FENCED");
        return stored;
    }

    private String readRequest(Stored stored) {
        String value = payload(stored.header().requestId(), "request_json");
        bounded(value, maxRequestBytes, "PLANNING_REQUEST_TOO_LARGE");
        require(stored.inputHash().equals(CampaignRunStore.sha256(value)), "PLANNING_REQUEST_CORRUPTED");
        return value;
    }

    private InvocationSpec readInvocation(Stored stored) {
        String value = payload(stored.header().requestId(), "invocation_json");
        require(stored.header().invocationHash() != null
                        && stored.header().invocationHash().equals(CampaignRunStore.sha256(value)), "PLANNING_INVOCATION_CORRUPTED");
        InvocationSpec invocation = ModelInvocationRegistry.decode(value, limits);
        require(invocation.hash().equals(stored.header().invocationHash()), "PLANNING_INVOCATION_CORRUPTED");
        requireInvocation(stored.header(), invocation);
        return invocation;
    }

    private Response readResponse(Stored stored) {
        String value = payload(stored.header().requestId(), "response_json");
        require(stored.header().responseHash() != null
                        && stored.header().responseHash().equals(CampaignRunStore.sha256(value)), "PLANNING_RESPONSE_CORRUPTED");
        Response response = ModelInvocationRegistry.decodeResponse(value, limits);
        require(response.toolCalls().isEmpty(), "PLANNING_TOOL_CALLS_FORBIDDEN");
        return response;
    }

    private String payload(String id, String column) {
        // Column names are private literals; no request content becomes SQL.
        List<String> values = jdbc.query("SELECT " + column + " FROM campaign_planning_request WHERE request_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), id);
        require(values.size() == 1 && values.get(0) != null, "PLANNING_PAYLOAD_MISSING");
        return values.get(0);
    }

    private void requireApproval(Stored stored, Approval approval) {
        require(readInvocation(stored).equals(approval.invocation()), "PLANNING_INVOCATION_CHANGED");
    }

    private void requireInvocation(Header header, InvocationSpec invocation) {
        require(header.requestId().equals(invocation.invocationId()) && invocation.turnIndex() == 1
                        && header.modelRef().equals(invocation.modelRef()) && header.modelVersion().equals(invocation.modelVersion())
                        && header.configurationHash().equals(invocation.configurationHash())
                        && header.expiresAt().equals(invocation.expiresAt()), "PLANNING_INVOCATION_CHANGED");
        require(ModelInvocationRegistry.decodeRequest(invocation.requestJson(), limits).tools().isEmpty(),
                "PLANNING_TOOL_CALLS_FORBIDDEN");
    }

    private static void requireSource(Header expected, Header actual) {
        require(expected.invocationHash() != null && expected.invocationHash().equals(actual.invocationHash())
                        && expected.responseHash() != null && expected.responseHash().equals(actual.responseHash()),
                "PLANNING_SOURCE_RESPONSE_CHANGED");
    }

    private Stored required(String id, boolean lock) {
        text(id, 96);
        List<Stored> rows = jdbc.query("SELECT " + COLUMNS + " FROM campaign_planning_request WHERE request_id=?"
                + (lock ? " FOR UPDATE" : ""), (rs, row) -> readHeader(rs), id);
        require(rows.size() == 1, "PLANNING_REQUEST_NOT_FOUND");
        return rows.get(0);
    }

    private static Stored readHeader(ResultSet rs) throws SQLException {
        State state;
        try { state = State.valueOf(rs.getString("request_state")); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw failure("PLANNING_HEADER_CORRUPTED"); }
        Header header = new Header(rs.getString("request_id"), new Caller(rs.getString("tenant_id"),
                rs.getString("subject_name"), rs.getLong("auth_version")), rs.getString("session_id"),
                rs.getString("request_key"), rs.getString("profile_ref"), rs.getString("profile_version"),
                rs.getString("run_id"), rs.getString("plan_id"), rs.getString("model_ref"), rs.getString("model_version"),
                rs.getString("configuration_hash"), rs.getString("request_hash"), Instant.ofEpochMilli(rs.getLong("expires_at")),
                state, rs.getBoolean("callback_active"), rs.getString("invocation_hash"), rs.getString("response_hash"),
                rs.getString("definition_hash"), rs.getString("intake_request_id"), rs.getString("reason_code"));
        String inputHash = rs.getString("input_hash");
        var identity = JdbcCampaignRunIntakeStore.identity(header.caller(), header.sessionId(), header.requestKey());
        hash(inputHash); hash(header.configurationHash());
        text(header.profileRef(), 128); text(header.profileVersion(), 128);
        text(header.modelRef(), 256); text(header.modelVersion(), 256);
        require(header.requestId().equals("planning-" + identity.requestId().substring("intake-".length()))
                        && identity.runId().equals(header.runId()) && identity.planId().equals(header.planId())
                        && requestHash(header.requestId(), header.caller(), header.sessionId(), header.requestKey(), header.profileRef(),
                            header.profileVersion(), header.modelRef(), header.modelVersion(), header.configurationHash(), inputHash,
                            header.expiresAt()).equals(header.requestHash()), "PLANNING_HEADER_CORRUPTED");
        return new Stored(header, inputHash, rs.getString("attempt_id"));
    }

    private static void sameRequest(Header expected, Header actual) {
        require(Objects.equals(expected.requestId(), actual.requestId()) && Objects.equals(expected.caller(), actual.caller())
                        && Objects.equals(expected.sessionId(), actual.sessionId()) && Objects.equals(expected.requestKey(), actual.requestKey())
                        && Objects.equals(expected.profileRef(), actual.profileRef()) && Objects.equals(expected.profileVersion(), actual.profileVersion())
                        && Objects.equals(expected.runId(), actual.runId()) && Objects.equals(expected.planId(), actual.planId())
                        && Objects.equals(expected.modelRef(), actual.modelRef()) && Objects.equals(expected.modelVersion(), actual.modelVersion())
                        && Objects.equals(expected.configurationHash(), actual.configurationHash())
                        && Objects.equals(expected.requestHash(), actual.requestHash()) && Objects.equals(expected.expiresAt(), actual.expiresAt()),
                "PLANNING_REQUEST_CHANGED");
    }

    private static String requestHash(String id, Caller caller, String session, String key, String profile, String version,
            String model, String modelVersion, String configurationHash, String inputHash, Instant expiry) {
        StringBuilder value = new StringBuilder();
        for (String part : List.of("campaign-planning-request/v1", id, caller.tenantId(), caller.subject(),
                Long.toString(caller.authVersion()), session, key, profile, version, model, modelVersion,
                configurationHash, inputHash, Long.toString(expiry.toEpochMilli()))) value.append(part.length()).append(':').append(part);
        return CampaignRunStore.sha256(value.toString());
    }

    private void requireCurrent(Header header) {
        require(clock.instant().isBefore(header.expiresAt()), "PLANNING_REQUEST_EXPIRED");
    }

    private static Instant millis(Instant value) {
        Objects.requireNonNull(value);
        try { return Instant.ofEpochMilli(value.toEpochMilli()); }
        catch (ArithmeticException invalid) { throw invalid("PLANNING_EXPIRY_INVALID"); }
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid("PLANNING_HASH_INVALID");
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw invalid("PLANNING_FIELD_INVALID");
        bounded(value, maximum * 4L, "PLANNING_FIELD_INVALID");
    }

    /** Count UTF-8 before parsing without allocating a duplicate byte array. */
    private static void bounded(String value, long maximum, String code) {
        if (value == null || value.isBlank()) throw invalid("PLANNING_PAYLOAD_REQUIRED");
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x80) bytes++;
            else if (character < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) { bytes += 4; index++; }
            else {
                if (Character.isSurrogate(character)) throw invalid("PLANNING_UNICODE_INVALID");
                bytes += 3;
            }
            if (bytes > maximum) throw invalid(code);
        }
    }

    private <T> T tx(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
