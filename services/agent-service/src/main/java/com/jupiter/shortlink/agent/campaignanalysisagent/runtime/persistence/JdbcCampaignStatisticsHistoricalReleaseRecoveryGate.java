package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsHistoricalReleaseRecoveryReader.RetiredReleaseCandidate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC second-read gate for a historical release candidate.  This component only locks and
 * verifies durable rows; it never claims a candidate or changes release/consumer state.
 *
 * <p>The lock order follows the shared statistics gate: source run, physical binding, source
 * child, active-consumer rows, then release intent.  Action/artifact/receipt rows are read in the
 * same transaction without additional row locks so this seam does not establish a new cross-store
 * lock cycle.</p>
 */
public final class JdbcCampaignStatisticsHistoricalReleaseRecoveryGate
        implements CampaignStatisticsHistoricalReleaseRecoveryGate {
    private static final String CORRUPTED = "HISTORICAL_RELEASE_GATE_CORRUPTED";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcCampaignStatisticsHistoricalReleaseRecoveryGate(JdbcTemplate jdbc,
                                                                 TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("Historical release gate requires one shared REQUIRED DataSource transaction");
    }

    @Override
    public Optional<Permit> revalidate(Caller caller, RetiredReleaseCandidate candidate, long nowMillis) {
        validateCaller(caller);
        Objects.requireNonNull(candidate, "HISTORICAL_RELEASE_GATE_CANDIDATE_REQUIRED");
        if (nowMillis < 0) throw new IllegalArgumentException("HISTORICAL_RELEASE_GATE_NOW_INVALID");
        // A discovery row with these facts is already stale or malformed.  Do not turn it into a
        // stronger permit before touching the database.
        if (!candidate.localOnly() || candidate.activeConsumerCount() != 0) return Optional.empty();
        return transaction(() -> verify(caller, candidate, nowMillis));
    }

    private Optional<Permit> verify(Caller caller, RetiredReleaseCandidate candidate, long nowMillis) {
        String releaseBindingId = releaseBindingId(caller, candidate.jobId());
        String physicalBindingId = physicalBindingId(caller, candidate.jobId());

        Optional<RunRow> run = row("SELECT tenant_id,subject_name,auth_version,session_id,plan_id,definition_hash,"
                        + "definition_json,run_status,row_version FROM campaign_run_ledger "
                        + "WHERE run_id=? AND revision=? FOR UPDATE",
                (rs, ignored) -> run(rs), candidate.runId(), candidate.revision());
        if (run.isEmpty()) return Optional.empty();
        RunRow source = run.get();
        if (!caller.equals(source.owner())) return Optional.empty();
        require(validHash(source.definitionHash())
                        && source.definitionJson() != null
                        && CampaignRunStore.sha256(source.definitionJson()).equals(source.definitionHash()), CORRUPTED);
        if (!"SUPERSEDED".equals(source.status())) return Optional.empty();
        if (source.rowVersion() != candidate.sourceRunVersion()) return Optional.empty();

        Optional<BindingRow> physical = row("SELECT binding_id,tenant_id,subject_name,auth_version,producer_run_id,"
                        + "producer_revision,producer_child_id,producer_definition_hash,action_id,executor_kind,"
                        + "executor_name,executor_version,output_contract_ref,job_id,request_id,request_hash,artifact_id,"
                        + "scope_ref,periods_ref,expires_at,binding_version,cancel_intent,local_only "
                        + "FROM campaign_statistics_job_binding WHERE binding_id=? FOR UPDATE",
                (rs, ignored) -> binding(rs), physicalBindingId);
        if (physical.isEmpty()) return Optional.empty();
        BindingRow binding = physical.get();
        require(physicalBindingId.equals(binding.bindingId()), CORRUPTED);
        require(caller.equals(binding.owner())
                        && candidate.runId().equals(binding.producerRunId())
                        && candidate.revision() == binding.producerRevision()
                        && candidate.childId().equals(binding.producerChildId())
                        && candidate.jobId().equals(binding.jobId()), CORRUPTED);
        require(validHash(binding.producerDefinitionHash())
                        && Objects.equals(binding.producerDefinitionHash(), source.definitionHash()), CORRUPTED);
        if (!"NONE".equals(binding.cancelIntent()) || !binding.localOnly()
                || binding.expiresAtMillis() <= nowMillis) return Optional.empty();

        Optional<ChildRow> childResult = row("SELECT run_id,revision,child_id,action_id,tenant_id,child_mode,request_id,"
                        + "wire_method,wire_path,wire_hash,wire_body,child_state,job_id,artifact_id,callback_active,"
                        + "attempt_purpose FROM campaign_child_ledger WHERE run_id=? AND revision=? AND child_id=? FOR UPDATE",
                (rs, ignored) -> child(rs), candidate.runId(), candidate.revision(), candidate.childId());
        if (childResult.isEmpty()) return Optional.empty();
        ChildRow child = childResult.get();
        if (!"READY".equals(child.state()) || !"ASYNC".equals(child.mode()) || child.callbackActive())
            return Optional.empty();
        require(caller.tenantId().equals(child.tenantId())
                        && candidate.runId().equals(child.runId())
                        && candidate.revision() == child.revision()
                        && candidate.childId().equals(child.childId())
                        && candidate.jobId().equals(child.jobId()), CORRUPTED);
        if (!validId(child.requestId(), 96) || !validHash(child.wireHash())
                || !StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH.equals(child.wirePath())
                || !"POST".equals(child.wireMethod())
                || !CampaignRunStore.sha256(wireIdentity(child)).equals(child.wireHash()))
            throw corrupted();
        if (child.attemptPurpose() != null && !List.of("RECONCILE", "RELEASE").contains(child.attemptPurpose()))
            throw corrupted();

        List<Boolean> active = jdbc.query("SELECT active FROM campaign_statistics_consumer WHERE binding_id=? FOR UPDATE",
                (rs, ignored) -> requiredBoolean(rs, "active"), physicalBindingId);
        if (active.stream().anyMatch(Boolean::booleanValue)) return Optional.empty();

        Optional<ReleaseRow> releaseResult = row("SELECT binding_id,tenant_id,subject_name,auth_version,producer_run_id,"
                        + "revision,child_id,job_id,request_id,request_hash,artifact_id,artifact_hash,chain_hash,"
                        + "expires_at,binding_version,release_state FROM campaign_statistics_release "
                        + "WHERE binding_id=? FOR UPDATE",
                (rs, ignored) -> release(rs), releaseBindingId);
        if (releaseResult.isEmpty()) return Optional.empty();
        ReleaseRow release = releaseResult.get();
        require(releaseBindingId.equals(release.bindingId()) && caller.equals(release.owner()), CORRUPTED);
        if (!"REQUESTED".equals(release.state())) return Optional.empty();
        if (!candidate.runId().equals(release.runId()) || candidate.revision() != release.revision()
                || !candidate.childId().equals(release.childId()) || !candidate.jobId().equals(release.jobId())
                || candidate.bindingVersion() != release.bindingVersion()
                || candidate.expiresAtMillis() != release.expiresAtMillis()
                || release.expiresAtMillis() <= nowMillis) return Optional.empty();
        require(binding.bindingVersion() == release.bindingVersion()
                        && binding.expiresAtMillis() == release.expiresAtMillis(), CORRUPTED);
        require(caller.equals(release.owner())
                        && Objects.equals(release.requestId(), child.requestId())
                        && Objects.equals(release.requestId(), binding.requestId())
                        && Objects.equals(release.requestHash(), child.wireHash())
                        && Objects.equals(release.requestHash(), binding.requestHash())
                        && Objects.equals(release.artifactId(), child.artifactId())
                        && Objects.equals(release.artifactId(), binding.artifactId())
                        && validId(release.requestId(), 96)
                        && validHash(release.requestHash())
                        && validHash(release.artifactHash())
                        && validHash(release.chainHash()), CORRUPTED);

        Optional<ActionRow> actionResult = row("SELECT run_id,revision,action_id,step_id,executor_kind,executor_name,"
                        + "executor_version,definition_hash,definition_json FROM campaign_action_ledger "
                        + "WHERE run_id=? AND revision=? AND action_id=?",
                (rs, ignored) -> action(rs), candidate.runId(), candidate.revision(), child.actionId());
        if (actionResult.isEmpty()) throw corrupted();
        ActionRow action = actionResult.get();
        require(candidate.runId().equals(action.runId()) && candidate.revision() == action.revision()
                        && Objects.equals(child.actionId(), action.actionId())
                        && validHash(action.definitionHash()) && action.definitionJson() != null
                        && CampaignRunStore.sha256(action.definitionJson()).equals(action.definitionHash())
                        && Objects.equals(child.actionId(), binding.actionId()), CORRUPTED);

        Optional<ArtifactRow> artifactResult = row("SELECT artifact_id,tenant_id,subject_name,auth_version,run_id,plan_id,"
                        + "revision,action_id,child_id,executor_version,artifact_type,schema_version,scope_ref,periods_ref,"
                        + "payload_hash,expires_at FROM campaign_artifact WHERE artifact_id=?",
                (rs, ignored) -> artifact(rs), release.artifactId());
        if (artifactResult.isEmpty()) throw corrupted();
        ArtifactRow artifact = artifactResult.get();
        require(caller.equals(artifact.owner())
                        && validId(source.planId(), 96)
                        && validId(artifact.planId(), 96)
                        && candidate.runId().equals(artifact.runId()) && candidate.revision() == artifact.revision()
                        && candidate.childId().equals(artifact.childId()) && Objects.equals(child.actionId(), artifact.actionId())
                        && Objects.equals(source.planId(), artifact.planId())
                        && Objects.equals(child.actionId(), binding.actionId())
                        && Objects.equals(action.executorVersion(), artifact.executorVersion())
                        && Objects.equals(action.executorKind(), binding.executorKind())
                        && Objects.equals(action.executorName(), binding.executorName())
                        && Objects.equals(action.executorVersion(), binding.executorVersion())
                        && CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(artifact.type())
                        && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(artifact.schemaVersion())
                        && validHash(artifact.payloadHash())
                        && release.artifactHash().equals(artifact.payloadHash())
                        && candidate.expiresAtMillis() == artifact.expiresAtMillis()
                        && candidate.expiresAtMillis() > nowMillis
                        && validText(artifact.scopeRef(), 256) && validText(artifact.periodsRef(), 256)
                        && artifact.scopeRef().equals(binding.scopeRef())
                        && artifact.periodsRef().equals(binding.periodsRef()), CORRUPTED);

        Optional<PayloadRow> payloadResult = row("SELECT artifact_id,payload_json FROM campaign_artifact_payload "
                        + "WHERE artifact_id=?", (rs, ignored) -> payload(rs), release.artifactId());
        if (payloadResult.isEmpty()) throw corrupted();
        PayloadRow payload = payloadResult.get();
        require(release.artifactId().equals(payload.artifactId()) && payload.payloadJson() != null
                        && CampaignRunStore.sha256(payload.payloadJson()).equals(artifact.payloadHash()), CORRUPTED);

        Optional<ReceiptRow> receiptResult = row("SELECT run_id,revision,child_id,artifact_id,spec_json,spec_hash,"
                        + "chain_hash,published FROM campaign_statistics_receipt WHERE run_id=? AND revision=? AND child_id=?",
                (rs, ignored) -> receipt(rs), candidate.runId(), candidate.revision(), candidate.childId());
        if (receiptResult.isEmpty()) throw corrupted();
        ReceiptRow receipt = receiptResult.get();
        require(candidate.runId().equals(receipt.runId()) && candidate.revision() == receipt.revision()
                        && candidate.childId().equals(receipt.childId()) && release.artifactId().equals(receipt.artifactId())
                        && release.chainHash().equals(receipt.chainHash()) && receipt.published()
                        && receiptSpecMatches(receipt, binding, release, candidate.expiresAtMillis()), CORRUPTED);

        return Optional.of(new Permit(releaseBindingId, physicalBindingId, candidate.runId(), candidate.revision(),
                candidate.childId(), candidate.jobId(), candidate.bindingVersion(), candidate.sourceRunVersion(),
                candidate.expiresAtMillis(), true));
    }

    private static RunRow run(ResultSet rs) throws SQLException {
        return new RunRow(new Caller(rs.getString("tenant_id"), rs.getString("subject_name"),
                requiredLong(rs, "auth_version")), rs.getString("session_id"), rs.getString("plan_id"),
                rs.getString("definition_hash"), rs.getString("definition_json"), rs.getString("run_status"),
                requiredLong(rs, "row_version"));
    }

    private static BindingRow binding(ResultSet rs) throws SQLException {
        return new BindingRow(rs.getString("binding_id"), new Caller(rs.getString("tenant_id"),
                rs.getString("subject_name"), requiredLong(rs, "auth_version")), rs.getString("producer_run_id"),
                requiredInt(rs, "producer_revision"), rs.getString("producer_child_id"),
                rs.getString("producer_definition_hash"), rs.getString("action_id"), rs.getString("executor_kind"),
                rs.getString("executor_name"), rs.getString("executor_version"), rs.getString("output_contract_ref"),
                rs.getString("job_id"), rs.getString("request_id"), rs.getString("request_hash"),
                rs.getString("artifact_id"), rs.getString("scope_ref"), rs.getString("periods_ref"),
                requiredLong(rs, "expires_at"), requiredLong(rs, "binding_version"), rs.getString("cancel_intent"),
                requiredBoolean(rs, "local_only"));
    }

    private static ChildRow child(ResultSet rs) throws SQLException {
        return new ChildRow(rs.getString("run_id"), requiredInt(rs, "revision"), rs.getString("child_id"),
                rs.getString("action_id"), rs.getString("tenant_id"), rs.getString("child_mode"),
                rs.getString("request_id"), rs.getString("wire_method"), rs.getString("wire_path"),
                rs.getString("wire_hash"), rs.getString("wire_body"), rs.getString("child_state"),
                rs.getString("job_id"), rs.getString("artifact_id"), requiredBoolean(rs, "callback_active"),
                rs.getString("attempt_purpose"));
    }

    private static ReleaseRow release(ResultSet rs) throws SQLException {
        return new ReleaseRow(rs.getString("binding_id"), new Caller(rs.getString("tenant_id"),
                rs.getString("subject_name"), requiredLong(rs, "auth_version")), rs.getString("producer_run_id"),
                requiredInt(rs, "revision"), rs.getString("child_id"), rs.getString("job_id"),
                rs.getString("request_id"), rs.getString("request_hash"), rs.getString("artifact_id"),
                rs.getString("artifact_hash"), rs.getString("chain_hash"), requiredLong(rs, "expires_at"),
                requiredLong(rs, "binding_version"), rs.getString("release_state"));
    }

    private static ActionRow action(ResultSet rs) throws SQLException {
        return new ActionRow(rs.getString("run_id"), requiredInt(rs, "revision"), rs.getString("action_id"),
                rs.getString("step_id"), rs.getString("executor_kind"), rs.getString("executor_name"),
                rs.getString("executor_version"), rs.getString("definition_hash"), rs.getString("definition_json"));
    }

    private static ArtifactRow artifact(ResultSet rs) throws SQLException {
        return new ArtifactRow(rs.getString("artifact_id"), new Caller(rs.getString("tenant_id"),
                rs.getString("subject_name"), requiredLong(rs, "auth_version")), rs.getString("run_id"),
                rs.getString("plan_id"), requiredInt(rs, "revision"), rs.getString("action_id"),
                rs.getString("child_id"), rs.getString("executor_version"), rs.getString("artifact_type"),
                rs.getString("schema_version"), rs.getString("scope_ref"), rs.getString("periods_ref"),
                rs.getString("payload_hash"), requiredLong(rs, "expires_at"));
    }

    private static PayloadRow payload(ResultSet rs) throws SQLException {
        return new PayloadRow(rs.getString("artifact_id"), rs.getString("payload_json"));
    }

    private static ReceiptRow receipt(ResultSet rs) throws SQLException {
        return new ReceiptRow(rs.getString("run_id"), requiredInt(rs, "revision"), rs.getString("child_id"),
                rs.getString("artifact_id"), rs.getString("spec_json"), rs.getString("spec_hash"),
                rs.getString("chain_hash"), requiredBoolean(rs, "published"));
    }

    private static boolean receiptSpecMatches(ReceiptRow receipt, BindingRow binding, ReleaseRow release,
                                              long expiresAtMillis) {
        if (receipt.specJson() == null || !validHash(receipt.specHash())
                || !CampaignRunStore.sha256(receipt.specJson()).equals(receipt.specHash())) return false;
        try {
            JsonNode spec = JSON.readTree(receipt.specJson());
            return spec != null && spec.isObject()
                    && Objects.equals(text(spec, "jobId"), binding.jobId())
                    && Objects.equals(text(spec, "requestHash"), release.requestHash())
                    && Objects.equals(text(spec, "artifactId"), release.artifactId())
                    && Objects.equals(text(spec, "scopeRef"), binding.scopeRef())
                    && Objects.equals(text(spec, "periodsRef"), binding.periodsRef())
                    && spec.path("expiresAtMillis").isIntegralNumber()
                    && spec.path("expiresAtMillis").longValue() == expiresAtMillis;
        } catch (JsonProcessingException malformed) {
            return false;
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String wireIdentity(ChildRow child) {
        require(child.wireMethod() != null && child.wirePath() != null && child.wireBody() != null, CORRUPTED);
        return child.wireMethod().length() + ":" + child.wireMethod()
                + child.wirePath().length() + ":" + child.wirePath()
                + child.wireBody().length() + ":" + child.wireBody();
    }

    private static long requiredLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (!(value instanceof Number number)) throw corrupted();
        return number.longValue();
    }

    private static int requiredInt(ResultSet rs, String column) throws SQLException {
        long value = requiredLong(rs, column);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw corrupted();
        return (int) value;
    }

    private static boolean requiredBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        if (rs.wasNull()) throw corrupted();
        return value;
    }

    private static void validateCaller(Caller caller) {
        Objects.requireNonNull(caller, "HISTORICAL_RELEASE_GATE_CALLER_REQUIRED");
        if (!validId(caller.tenantId(), 96) || caller.subject() == null || caller.subject().isBlank()
                || caller.subject().length() > 128 || caller.subject().chars().anyMatch(Character::isISOControl)
                || caller.authVersion() < 1)
            throw new IllegalArgumentException("HISTORICAL_RELEASE_GATE_CALLER_INVALID");
    }

    private static boolean validId(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String releaseBindingId(Caller owner, String jobId) {
        try {
            return "release-" + CampaignRunStore.sha256(JSON.writeValueAsString(
                    List.of("statistics-release/v1", owner.tenantId(), owner.subject(), jobId)));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String physicalBindingId(Caller owner, String jobId) {
        return "statistics-binding-" + CampaignRunStore.sha256(owner.tenantId().length() + ":" + owner.tenantId()
                + owner.subject().length() + ":" + owner.subject() + jobId.length() + ":" + jobId);
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw corrupted();
    }

    private static IllegalStateException corrupted() {
        return new IllegalStateException(CORRUPTED);
    }

    private <T> Optional<T> row(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    private <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private record RunRow(Caller owner, String sessionId, String planId, String definitionHash,
                          String definitionJson, String status, long rowVersion) {}

    private record BindingRow(String bindingId, Caller owner, String producerRunId, int producerRevision,
                              String producerChildId, String producerDefinitionHash, String actionId,
                              String executorKind, String executorName, String executorVersion,
                              String outputContractRef, String jobId, String requestId, String requestHash,
                              String artifactId, String scopeRef, String periodsRef, long expiresAtMillis,
                              long bindingVersion, String cancelIntent, boolean localOnly) {}

    private record ChildRow(String runId, int revision, String childId, String actionId, String tenantId,
                            String mode, String requestId, String wireMethod, String wirePath, String wireHash,
                            String wireBody, String state, String jobId, String artifactId,
                            boolean callbackActive, String attemptPurpose) {}

    private record ReleaseRow(String bindingId, Caller owner, String runId, int revision, String childId,
                              String jobId, String requestId, String requestHash, String artifactId,
                              String artifactHash, String chainHash, long expiresAtMillis, long bindingVersion,
                              String state) {}

    private record ActionRow(String runId, int revision, String actionId, String stepId, String executorKind,
                             String executorName, String executorVersion, String definitionHash,
                             String definitionJson) {}

    private record ArtifactRow(String artifactId, Caller owner, String runId, String planId, int revision,
                               String actionId, String childId, String executorVersion, String type,
                               String schemaVersion, String scopeRef, String periodsRef, String payloadHash,
                               long expiresAtMillis) {}

    private record PayloadRow(String artifactId, String payloadJson) {}

    private record ReceiptRow(String runId, int revision, String childId, String artifactId,
                              String specJson, String specHash, String chainHash, boolean published) {}
}
