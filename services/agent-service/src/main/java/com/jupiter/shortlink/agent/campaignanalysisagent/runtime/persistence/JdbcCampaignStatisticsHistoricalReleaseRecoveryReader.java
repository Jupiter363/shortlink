package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC implementation of the advisory retired-producer release recovery index. */
public final class JdbcCampaignStatisticsHistoricalReleaseRecoveryReader
        implements CampaignStatisticsHistoricalReleaseRecoveryReader {
    private static final int MAX_PENDING_LIMIT = 256;
    private static final String CORRUPTED = "HISTORICAL_RELEASE_BINDING_CORRUPTED";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcCampaignStatisticsHistoricalReleaseRecoveryReader(JdbcTemplate jdbc,
                                                                  TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("Historical release reader requires one shared REQUIRED DataSource transaction");
    }

    @Override
    public List<RetiredReleaseCandidate> pendingRetired(Caller caller, long nowMillis, int limit) {
        validateCaller(caller);
        if (nowMillis < 0) throw new IllegalArgumentException("HISTORICAL_RELEASE_NOW_INVALID");
        if (limit < 1 || limit > MAX_PENDING_LIMIT)
            throw new IllegalArgumentException("HISTORICAL_RELEASE_LIMIT_INVALID");
        return transaction(() -> jdbc.query(
                "SELECT r.binding_id AS release_binding_id,r.tenant_id AS release_tenant_id,"
                        + "r.subject_name AS release_subject_name,r.auth_version AS release_auth_version,"
                        + "r.producer_run_id AS release_run_id,r.revision AS release_revision,"
                        + "r.child_id AS release_child_id,r.job_id AS release_job_id,r.request_id AS release_request_id,"
                        + "r.request_hash AS release_request_hash,r.artifact_id AS release_artifact_id,"
                        + "r.artifact_hash AS release_artifact_hash,r.chain_hash AS release_chain_hash,"
                        + "r.expires_at AS release_expires_at,r.binding_version AS release_binding_version,"
                        + "r.release_state,"
                        + "l.run_id AS ledger_run_id,l.revision AS ledger_revision,l.tenant_id AS ledger_tenant_id,"
                        + "l.subject_name AS ledger_subject_name,l.auth_version AS ledger_auth_version,"
                        + "l.session_id AS ledger_session_id,l.plan_id AS ledger_plan_id,"
                        + "l.definition_hash AS ledger_definition_hash,l.run_status AS ledger_run_status,"
                        + "l.row_version AS ledger_row_version,"
                        + "c.run_id AS child_run_id,c.revision AS child_revision,c.child_id AS ledger_child_id,"
                        + "c.action_id AS child_action_id,c.tenant_id AS child_tenant_id,c.child_mode,c.child_state,"
                        + "c.request_id AS child_request_id,c.wire_hash AS child_wire_hash,"
                        + "c.job_id AS child_job_id,c.artifact_id AS child_artifact_id,"
                        + "c.callback_active AS child_callback_active,c.attempt_purpose AS child_attempt_purpose,"
                        + "b.binding_id AS job_binding_id,b.tenant_id AS binding_tenant_id,"
                        + "b.subject_name AS binding_subject_name,b.auth_version AS binding_auth_version,"
                        + "b.producer_run_id AS binding_run_id,b.producer_revision AS binding_revision,"
                        + "b.producer_child_id AS binding_child_id,b.producer_definition_hash,"
                        + "b.action_id AS binding_action_id,b.job_id AS binding_job_id,"
                        + "b.request_id AS binding_request_id,b.request_hash AS binding_request_hash,"
                        + "b.artifact_id AS binding_artifact_id,b.scope_ref AS binding_scope_ref,"
                        + "b.periods_ref AS binding_periods_ref,b.expires_at AS binding_expires_at,"
                        + "b.binding_version AS job_binding_version,b.cancel_intent,b.local_only,"
                        + "a.artifact_id AS metadata_artifact_id,a.tenant_id AS artifact_tenant_id,"
                        + "a.subject_name AS artifact_subject_name,a.auth_version AS artifact_auth_version,"
                        + "a.run_id AS artifact_run_id,a.plan_id AS artifact_plan_id,a.revision AS artifact_revision,"
                        + "a.action_id AS artifact_action_id,a.child_id AS artifact_child_id,"
                        + "a.artifact_type,a.schema_version,a.scope_ref AS artifact_scope_ref,"
                        + "a.periods_ref AS artifact_periods_ref,a.payload_hash AS artifact_payload_hash,"
                        + "a.expires_at AS artifact_expires_at,"
                        + "p.artifact_id AS payload_artifact_id,"
                        + "q.artifact_id AS receipt_artifact_id,q.spec_json AS receipt_spec_json,"
                        + "q.spec_hash AS receipt_spec_hash,q.chain_hash AS receipt_chain_hash,"
                        + "q.published AS receipt_published,"
                        + "(SELECT COUNT(*) FROM campaign_statistics_consumer cc "
                        + "WHERE cc.binding_id=b.binding_id AND cc.active=TRUE) "
                        + "AS active_consumer_count "
                        + "FROM campaign_statistics_release r "
                        + "JOIN campaign_run_ledger l ON l.run_id=r.producer_run_id AND l.revision=r.revision "
                        + "LEFT JOIN campaign_child_ledger c ON c.run_id=r.producer_run_id AND c.revision=r.revision "
                        + "AND c.child_id=r.child_id "
                        // The release intent has its own durable binding id. The physical
                        // consumer binding is keyed by the producer tuple/job and therefore
                        // must never be joined through r.binding_id.
                        + "LEFT JOIN campaign_statistics_job_binding b ON b.tenant_id=r.tenant_id "
                        + "AND b.subject_name=r.subject_name AND b.auth_version=r.auth_version "
                        + "AND b.producer_run_id=r.producer_run_id AND b.producer_revision=r.revision "
                        + "AND b.producer_child_id=r.child_id AND b.job_id=r.job_id "
                        + "LEFT JOIN campaign_artifact a ON a.artifact_id=r.artifact_id "
                        + "LEFT JOIN campaign_artifact_payload p ON p.artifact_id=r.artifact_id "
                        + "LEFT JOIN campaign_statistics_receipt q ON q.run_id=r.producer_run_id "
                        + "AND q.revision=r.revision AND q.child_id=r.child_id "
                        + "WHERE r.tenant_id=? AND r.subject_name=? AND r.auth_version=? "
                        + "AND r.release_state='REQUESTED' AND r.expires_at>? "
                        + "AND l.run_status='SUPERSEDED' "
                        + "AND c.child_state='READY' AND c.callback_active=FALSE "
                        + "AND b.local_only=TRUE AND b.cancel_intent='NONE' "
                        + "AND NOT EXISTS (SELECT 1 FROM campaign_statistics_consumer ac "
                        + "WHERE ac.binding_id=b.binding_id AND ac.active=TRUE) "
                        + "ORDER BY r.expires_at ASC,r.producer_run_id ASC,r.revision ASC,r.child_id ASC LIMIT ?",
                (rs, row) -> candidate(rs, caller, nowMillis), caller.tenantId(), caller.subject(),
                caller.authVersion(), nowMillis, limit));
    }

    private RetiredReleaseCandidate candidate(ResultSet rs, Caller expected, long nowMillis) throws SQLException {
        String runId = rs.getString("release_run_id");
        int revision = rs.getInt("release_revision");
        String childId = rs.getString("release_child_id");
        String jobId = rs.getString("release_job_id");
        String requestId = rs.getString("release_request_id");
        String requestHash = rs.getString("release_request_hash");
        String artifactId = rs.getString("release_artifact_id");
        String artifactHash = rs.getString("release_artifact_hash");
        String chainHash = rs.getString("release_chain_hash");
        long expiresAt = requiredLong(rs, "release_expires_at");
        long releaseVersion = requiredLong(rs, "release_binding_version");
        long sourceRunVersion = requiredLong(rs, "ledger_row_version");
        int activeConsumers = requiredInt(rs, "active_consumer_count");

        try {
            Caller releaseOwner = new Caller(rs.getString("release_tenant_id"),
                    rs.getString("release_subject_name"), requiredLong(rs, "release_auth_version"));
            Caller ledgerOwner = new Caller(rs.getString("ledger_tenant_id"),
                    rs.getString("ledger_subject_name"), requiredLong(rs, "ledger_auth_version"));
            Caller bindingOwner = new Caller(rs.getString("binding_tenant_id"),
                    rs.getString("binding_subject_name"), requiredLong(rs, "binding_auth_version"));
            Caller artifactOwner = new Caller(rs.getString("artifact_tenant_id"),
                    rs.getString("artifact_subject_name"), requiredLong(rs, "artifact_auth_version"));

            String ledgerRunId = rs.getString("ledger_run_id");
            int ledgerRevision = rs.getInt("ledger_revision");
            String childRunId = rs.getString("child_run_id");
            int childRevision = rs.getInt("child_revision");
            String ledgerChildId = rs.getString("ledger_child_id");
            String bindingRunId = rs.getString("binding_run_id");
            int bindingRevision = rs.getInt("binding_revision");
            String bindingChildId = rs.getString("binding_child_id");
            String metadataRunId = rs.getString("artifact_run_id");
            int metadataRevision = rs.getInt("artifact_revision");
            String metadataChildId = rs.getString("artifact_child_id");
            String receiptArtifactId = rs.getString("receipt_artifact_id");
            String payloadArtifactId = rs.getString("payload_artifact_id");
            String releaseState = rs.getString("release_state");
            String runStatus = rs.getString("ledger_run_status");
            String attemptPurpose = rs.getString("child_attempt_purpose");
            boolean callbackActive = requiredBoolean(rs, "child_callback_active");
            boolean localOnly = requiredBoolean(rs, "local_only");

            require(expected.equals(releaseOwner) && expected.equals(ledgerOwner)
                            && expected.equals(bindingOwner) && expected.equals(artifactOwner)
                            && same(runId, ledgerRunId, childRunId, bindingRunId, metadataRunId)
                            && revision == ledgerRevision && revision == childRevision
                            && revision == bindingRevision && revision == metadataRevision
                            && same(childId, ledgerChildId, bindingChildId, metadataChildId)
                            && Objects.equals(rs.getString("child_action_id"), rs.getString("binding_action_id"))
                            && Objects.equals(rs.getString("child_action_id"), rs.getString("artifact_action_id"))
                            && Objects.equals(rs.getString("ledger_plan_id"), rs.getString("artifact_plan_id"))
                            && expected.tenantId().equals(rs.getString("child_tenant_id"))
                            && "SUPERSEDED".equals(runStatus)
                            && "REQUESTED".equals(releaseState)
                            && "ASYNC".equals(rs.getString("child_mode"))
                            && "READY".equals(rs.getString("child_state"))
                            && !callbackActive
                            && (attemptPurpose == null || "RECONCILE".equals(attemptPurpose)
                                || "RELEASE".equals(attemptPurpose))
                            && Objects.equals(requestId, rs.getString("child_request_id"))
                            && Objects.equals(requestId, rs.getString("binding_request_id"))
                            && Objects.equals(jobId, rs.getString("child_job_id"))
                            && Objects.equals(jobId, rs.getString("binding_job_id"))
                            && Objects.equals(requestHash, rs.getString("child_wire_hash"))
                            && Objects.equals(requestHash, rs.getString("binding_request_hash"))
                            && Objects.equals(artifactId, rs.getString("child_artifact_id"))
                            && Objects.equals(artifactId, rs.getString("binding_artifact_id"))
                            && Objects.equals(artifactId, rs.getString("metadata_artifact_id"))
                            && Objects.equals(artifactId, receiptArtifactId)
                            && Objects.equals(artifactId, payloadArtifactId)
                            && Objects.equals(rs.getString("ledger_definition_hash"), rs.getString("producer_definition_hash"))
                            && validId(runId, 96) && validId(childId, 96) && validId(jobId, 128)
                            && validId(requestId, 96) && validId(artifactId, 96)
                            && validHash(requestHash) && validHash(artifactHash)
                            && validHash(chainHash) && validHash(rs.getString("child_wire_hash"))
                            && validHash(rs.getString("producer_definition_hash"))
                            && validHash(rs.getString("artifact_payload_hash"))
                            && "StatisticsJobPages".equals(rs.getString("artifact_type"))
                            && "statistics-job-pages/v1".equals(rs.getString("schema_version"))
                            && Objects.equals(rs.getString("binding_scope_ref"), rs.getString("artifact_scope_ref"))
                            && Objects.equals(rs.getString("binding_periods_ref"), rs.getString("artifact_periods_ref"))
                            && Objects.equals(artifactHash, rs.getString("artifact_payload_hash"))
                            && Objects.equals(chainHash, rs.getString("receipt_chain_hash"))
                            && requiredLong(rs, "binding_expires_at") == expiresAt
                            && requiredLong(rs, "artifact_expires_at") == expiresAt
                            && releaseVersion == requiredLong(rs, "job_binding_version")
                            && releaseVersion == 1
                            && localOnly
                            && "NONE".equals(rs.getString("cancel_intent"))
                            && requiredInt(rs, "active_consumer_count") == 0
                            && sourceRunVersion >= 0 && expiresAt > nowMillis
                            && requiredBoolean(rs, "receipt_published")
                            && releaseBindingId(expected, jobId).equals(rs.getString("release_binding_id"))
                            && physicalBindingId(expected, jobId).equals(rs.getString("job_binding_id"))
                            && receiptSpecMatches(rs, jobId, requestHash, artifactId,
                                rs.getString("binding_scope_ref"), rs.getString("binding_periods_ref"), expiresAt),
                    CORRUPTED);
            return new RetiredReleaseCandidate(runId, revision, childId, jobId, releaseVersion,
                    expiresAt, sourceRunVersion, activeConsumers, localOnly);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw corrupted();
        }
    }

    private static boolean receiptSpecMatches(ResultSet rs, String jobId, String requestHash,
                                              String artifactId, String scopeRef, String periodsRef,
                                              long expiresAt) throws SQLException {
        String specJson = rs.getString("receipt_spec_json");
        String specHash = rs.getString("receipt_spec_hash");
        if (specJson == null || !validHash(specHash) || !CampaignRunStore.sha256(specJson).equals(specHash))
            return false;
        try {
            JsonNode spec = JSON.readTree(specJson);
            return spec != null && spec.isObject()
                    && Objects.equals(jobId, spec.path("jobId").asText(null))
                    && Objects.equals(requestHash, spec.path("requestHash").asText(null))
                    && Objects.equals(artifactId, spec.path("artifactId").asText(null))
                    && Objects.equals(scopeRef, spec.path("scopeRef").asText(null))
                    && Objects.equals(periodsRef, spec.path("periodsRef").asText(null))
                    && expiresAt == spec.path("expiresAtMillis").asLong(Long.MIN_VALUE);
        } catch (JsonProcessingException malformed) {
            return false;
        }
    }

    private static long requiredLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (!(value instanceof Number number)) throw corrupted();
        return number.longValue();
    }

    private static int requiredInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (!(value instanceof Number number)) throw corrupted();
        long converted = number.longValue();
        if (converted < Integer.MIN_VALUE || converted > Integer.MAX_VALUE) throw corrupted();
        return (int) converted;
    }

    private static boolean requiredBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        if (rs.wasNull()) throw corrupted();
        return value;
    }

    private static boolean same(String expected, String... actual) {
        if (!validId(expected, 128)) return false;
        for (String value : actual) if (!expected.equals(value)) return false;
        return true;
    }

    private static void validateCaller(Caller caller) {
        Objects.requireNonNull(caller, "HISTORICAL_RELEASE_CALLER_REQUIRED");
        if (!validId(caller.tenantId(), 96) || caller.subject() == null || caller.subject().isBlank()
                || caller.subject().length() > 128 || caller.subject().chars().anyMatch(Character::isISOControl)
                || caller.authVersion() < 1)
            throw new IllegalArgumentException("HISTORICAL_RELEASE_CALLER_INVALID");
    }

    private static boolean validId(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
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

    private <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
