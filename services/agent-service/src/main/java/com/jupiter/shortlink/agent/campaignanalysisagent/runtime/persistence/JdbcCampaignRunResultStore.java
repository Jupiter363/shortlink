package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.ReportBindingVerifier;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextActionKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC durable result binding.  This class is opt-in and has no Spring component registration. */
public final class JdbcCampaignRunResultStore implements CampaignRunResultStore {
    private static final int JSON_LIMIT = 64 * 1024;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ReportBindingVerifier reportVerifier;

    public JdbcCampaignRunResultStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                      ReportBindingVerifier reportVerifier) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reportVerifier = Objects.requireNonNull(reportVerifier, "reportVerifier");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Result binding requires one writable REQUIRED DataSource transaction");
    }

    @Override
    public Binding bind(RunToken token, BindingDraft draft) {
        validateToken(token);
        Objects.requireNonNull(draft, "RUN_RESULT_DRAFT_REQUIRED");
        return transactions.execute(status -> {
            LedgerRow run = lockRun(token);
            validateTokenAgainstRun(token, run);
            validateRunStatus(run.status(), draft.executionStatus());

            Optional<Binding> existing = readBinding(token.definition().runId(), token.definition().revision(), true);
            if (existing.isPresent()) {
                if (!sameFacts(existing.get(), token, draft))
                    throw new IllegalStateException("RUN_RESULT_BINDING_CONFLICT");
                return existing.get();
            }

            if (draft.reportRef() != null && !reportVerifier.mayBind(token.definition(), draft.reportRef()))
                throw new SecurityException("RUN_RESULT_REPORT_NOT_AUTHORIZED");
            if (draft.reportRef() != null && reportAlreadyBound(draft.reportRef(), token))
                throw new IllegalStateException("RUN_RESULT_REPORT_ALREADY_BOUND");

            long now = clock.millis();
            String requiredInputs = encode(draft.nextAction().requiredInputs());
            String limitations = encode(draft.limitations());
            try {
                jdbc.update("INSERT INTO campaign_run_result_binding (run_id,revision,report_id,report_revision,"
                                + "execution_status,next_action_kind,next_action_reason,required_inputs_json,limitations_json,"
                                + "source_row_version,source_advance_token,binding_version,created_at,updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        token.definition().runId(), token.definition().revision(),
                        draft.reportRef() == null ? null : draft.reportRef().reportId(),
                        draft.reportRef() == null ? null : draft.reportRef().revision(),
                        draft.executionStatus().name(), draft.nextAction().kind().name(), draft.nextAction().reasonCode(),
                        requiredInputs, limitations, token.version(), token.advanceToken(), 1L, now, now);
            } catch (DataIntegrityViolationException conflict) {
                throw new IllegalStateException("RUN_RESULT_REPORT_ALREADY_BOUND", conflict);
            }
            return readBinding(token.definition().runId(), token.definition().revision(), false)
                    .orElseThrow(() -> new IllegalStateException("RUN_RESULT_BINDING_NOT_FOUND"));
        });
    }

    @Override
    public Optional<Binding> read(Caller caller, String runId, int revision) {
        validateCaller(caller);
        validateRunId(runId);
        if (revision < 1) throw new IllegalArgumentException("RUN_RESULT_REVISION_INVALID");
        return transactions.execute(status -> jdbc.query(
                "SELECT '" + CampaignRunResultStore.SCHEMA + "' AS schema_version,b.run_id,b.revision,l.plan_id,b.report_id,b.report_revision,"
                        + "b.execution_status,b.next_action_kind,b.next_action_reason,b.required_inputs_json,"
                        + "b.limitations_json,b.source_row_version,b.source_advance_token,b.binding_version,"
                        + "b.created_at,b.updated_at,l.run_status "
                        + "FROM campaign_run_result_binding b JOIN campaign_run_ledger l "
                        + "ON l.run_id=b.run_id AND l.revision=b.revision "
                        + "WHERE b.run_id=? AND b.revision=? AND l.tenant_id=? AND l.subject_name=? AND l.auth_version=?",
                rs -> rs.next() ? Optional.of(readRow(rs, true)) : Optional.empty(), runId, revision,
                caller.tenantId(), caller.subject(), caller.authVersion()));
    }

    private LedgerRow lockRun(RunToken token) {
        return jdbc.query("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,"
                        + "run_status,row_version,advance_token FROM campaign_run_ledger "
                        + "WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, row) -> ledger(rs), token.definition().runId(), token.definition().revision())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("RUN_RESULT_RUN_NOT_FOUND"));
    }

    private Optional<Binding> readBinding(String runId, int revision, boolean lock) {
        return jdbc.query("SELECT '" + CampaignRunResultStore.SCHEMA + "' AS schema_version,b.run_id,b.revision,"
                        + "l.plan_id,b.report_id,b.report_revision,b.execution_status,b.next_action_kind,"
                        + "b.next_action_reason,b.required_inputs_json,b.limitations_json,b.source_row_version,"
                        + "b.source_advance_token,b.binding_version,b.created_at,b.updated_at,l.run_status "
                        + "FROM campaign_run_result_binding b JOIN campaign_run_ledger l "
                        + "ON l.run_id=b.run_id AND l.revision=b.revision WHERE b.run_id=? AND b.revision=?"
                        + (lock ? " FOR UPDATE" : ""),
                rs -> rs.next() ? Optional.of(readRow(rs, true)) : Optional.empty(), runId, revision);
    }

    private static LedgerRow ledger(ResultSet rs) throws SQLException {
        return new LedgerRow(new Caller(rs.getString("tenant_id"), rs.getString("subject_name"),
                rs.getLong("auth_version")), rs.getString("session_id"), rs.getString("plan_id"),
                rs.getString("definition_hash"), parseStatus(rs.getString("run_status")),
                rs.getLong("row_version"), rs.getString("advance_token"));
    }

    private Binding readRow(ResultSet rs, boolean checkRunStatus) throws SQLException {
        String schema = rs.getString("schema_version");
        String runId = rs.getString("run_id");
        int revision = rs.getInt("revision");
        String planId = rs.getString("plan_id");
        ExecutionStatus executionStatus = parseExecutionStatus(rs.getString("execution_status"));
        NextActionKind actionKind = parseActionKind(rs.getString("next_action_kind"));
        List<String> requiredInputs = decode(rs.getString("required_inputs_json"));
        List<String> limitations = decode(rs.getString("limitations_json"));
        NextAction action = new NextAction(actionKind, rs.getString("next_action_reason"), requiredInputs);
        String reportId = rs.getString("report_id");
        int reportRevision = rs.getInt("report_revision");
        boolean reportRevisionNull = rs.wasNull();
        if ((reportId == null) != reportRevisionNull)
            throw new IllegalStateException("RUN_RESULT_REPORT_REF_CORRUPTED");
        ReportRef report = reportId == null ? null : new ReportRef(reportId, reportRevision);
        Binding result = new Binding(schema, runId, planId, revision, executionStatus, report, action, limitations,
                rs.getLong("source_row_version"), rs.getString("source_advance_token"),
                rs.getLong("binding_version"), Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
        if (checkRunStatus) validateRunStatus(parseStatus(rs.getString("run_status")), executionStatus);
        return result;
    }

    private static boolean sameFacts(Binding existing, RunToken token, BindingDraft draft) {
        return existing.runId().equals(token.definition().runId())
                && existing.planId().equals(token.definition().planId())
                && existing.revision() == token.definition().revision()
                && existing.executionStatus() == draft.executionStatus()
                && Objects.equals(existing.reportRef(), draft.reportRef())
                && existing.nextAction().equals(draft.nextAction())
                && existing.limitations().equals(draft.limitations())
                && existing.sourceRowVersion() == token.version()
                && existing.sourceAdvanceToken().equals(token.advanceToken());
    }

    private static void validateToken(RunToken token) {
        Objects.requireNonNull(token, "RUN_RESULT_TOKEN_REQUIRED");
        RunDefinition definition = Objects.requireNonNull(token.definition(), "RUN_RESULT_DEFINITION_REQUIRED");
        validateCaller(definition.caller());
        validateRunId(definition.runId());
        if (definition.sessionId() == null || definition.sessionId().isBlank()
                || definition.planId() == null || definition.planId().isBlank() || definition.revision() < 1
                || definition.definitionJson() == null || definition.definitionJson().isBlank())
            throw new IllegalArgumentException("RUN_RESULT_DEFINITION_INVALID");
        if (token.version() < 0 || token.advanceToken() == null || token.advanceToken().isBlank()
                || token.advanceToken().length() > 36)
            throw new IllegalArgumentException("RUN_RESULT_TOKEN_INVALID");
    }

    private boolean reportAlreadyBound(ReportRef report, RunToken token) {
        return !jdbc.query("SELECT run_id,revision FROM campaign_run_result_binding "
                        + "WHERE report_id=? AND report_revision=? AND NOT (run_id=? AND revision=?)",
                (rs, row) -> rs.getString("run_id"), report.reportId(), report.revision(),
                token.definition().runId(), token.definition().revision()).isEmpty();
    }

    private static void validateTokenAgainstRun(RunToken token, LedgerRow run) {
        RunDefinition definition = token.definition();
        if (!definition.caller().equals(run.caller())) throw new SecurityException("RUN_RESULT_ACCESS_DENIED");
        if (!definition.sessionId().equals(run.sessionId()) || !definition.planId().equals(run.planId())
                || !definition.definitionHash().equals(run.definitionHash()))
            throw new IllegalStateException("RUN_RESULT_DEFINITION_MISMATCH");
        if (token.version() != run.version() || !token.advanceToken().equals(run.advanceToken()))
            throw new IllegalStateException("RUN_RESULT_TOKEN_FENCED");
    }

    private static void validateRunStatus(RunStatus runStatus, ExecutionStatus bindingStatus) {
        if (runStatus == null || bindingStatus == null) throw new IllegalStateException("RUN_RESULT_STATUS_CORRUPTED");
        if (runStatus == RunStatus.ACTIVE
                && (bindingStatus == ExecutionStatus.CANCELLED || bindingStatus == ExecutionStatus.SUPERSEDED))
            throw new IllegalStateException("RUN_RESULT_STATUS_MISMATCH");
        if (runStatus == RunStatus.CANCELLED && bindingStatus != ExecutionStatus.CANCELLED)
            throw new IllegalStateException("RUN_RESULT_STATUS_MISMATCH");
        if (runStatus == RunStatus.SUPERSEDED && bindingStatus != ExecutionStatus.SUPERSEDED)
            throw new IllegalStateException("RUN_RESULT_STATUS_MISMATCH");
    }

    private static RunStatus parseStatus(String value) {
        try { return RunStatus.valueOf(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("RUN_RESULT_RUN_STATUS_CORRUPTED", invalid); }
    }

    private static ExecutionStatus parseExecutionStatus(String value) {
        try { return ExecutionStatus.valueOf(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("RUN_RESULT_STATUS_CORRUPTED", invalid); }
    }

    private static NextActionKind parseActionKind(String value) {
        try { return NextActionKind.valueOf(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("RUN_RESULT_ACTION_CORRUPTED", invalid); }
    }

    private static String encode(List<String> values) {
        try {
            String encoded = JSON.writeValueAsString(values);
            if (encoded.length() > JSON_LIMIT) throw new IllegalArgumentException("RUN_RESULT_JSON_LIMIT_EXCEEDED");
            return encoded;
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("RUN_RESULT_JSON_INVALID", invalid); }
    }

    private static List<String> decode(String encoded) {
        if (encoded == null || encoded.length() > JSON_LIMIT) throw new IllegalStateException("RUN_RESULT_JSON_CORRUPTED");
        try {
            List<String> values = JSON.readValue(encoded, STRING_LIST);
            if (values == null) throw new IllegalStateException("RUN_RESULT_JSON_CORRUPTED");
            if (values.size() > 64 || values.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 512))
                throw new IllegalStateException("RUN_RESULT_JSON_CORRUPTED");
            return List.copyOf(values);
        } catch (IllegalStateException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalStateException("RUN_RESULT_JSON_CORRUPTED", invalid); }
    }

    private static void validateCaller(Caller caller) {
        if (caller == null || caller.tenantId() == null || caller.tenantId().isBlank()
                || caller.subject() == null || caller.subject().isBlank() || caller.authVersion() < 1)
            throw new IllegalArgumentException("RUN_RESULT_CALLER_INVALID");
    }

    private static void validateRunId(String runId) {
        if (runId == null || runId.isBlank() || runId.length() > 96)
            throw new IllegalArgumentException("RUN_RESULT_RUN_ID_INVALID");
    }

    private record LedgerRow(Caller caller, String sessionId, String planId, String definitionHash,
                             RunStatus status, long version, String advanceToken) {}
}
