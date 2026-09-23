package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** An independently durable native replan phase; UNKNOWN and DISPATCHING never grant another model dispatch. */
public final class JdbcCampaignReplanModelStore {
    public enum State { PREPARED, DISPATCHING, READY, UNKNOWN, REJECTED, APPLIED }
    public record Definition(String id, RunToken base, String stepId, String signalHash, String requestHash,
                             String modelRef, String modelVersion, String configurationHash, Instant expiresAt) {}
    public record Header(Definition definition, State state, boolean callbackActive, String invocationHash,
                         String responseHash, String reason) {}
    public record Permit(Definition definition, String attempt, String invocationHash) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Limits limits;

    public JdbcCampaignReplanModelStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, Limits limits) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.limits = Objects.requireNonNull(limits);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource() || transactions.isReadOnly()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED)
            throw new IllegalArgumentException("REPLAN_MODEL_TRANSACTION_REQUIRED");
    }

    public Header register(RunToken base, String stepId, String signalHash, String requestHash,
            String modelRef, String modelVersion, String configurationHash, Instant expiresAt) {
        Objects.requireNonNull(base); Objects.requireNonNull(expiresAt);
        if (!hash(signalHash) || !hash(requestHash) || !hash(configurationHash) || stepId == null
                || !stepId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}") || modelRef == null || modelRef.isBlank()
                || modelVersion == null || modelVersion.isBlank()) throw invalid();
        var run = base.definition(); var caller = run.caller();
        String id = "replan-model-" + CampaignRunStore.sha256(run.runId() + ":" + run.revision() + ":" + signalHash);
        var definition = new Definition(id, base, stepId, signalHash, requestHash, modelRef, modelVersion,
                configurationHash, Instant.ofEpochMilli(expiresAt.toEpochMilli()));
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO campaign_replan_model (model_id,tenant_id,subject_name,auth_version,session_id,run_id,base_revision,"
                    + "step_id,signal_hash,request_hash,model_ref,model_version,configuration_hash,expires_at,model_state,callback_active) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',FALSE) ON DUPLICATE KEY UPDATE model_id=model_id",
                    id, caller.tenantId(), caller.subject(), caller.authVersion(), run.sessionId(), run.runId(), run.revision(),
                    stepId, signalHash, requestHash, modelRef, modelVersion, configurationHash, expiresAt.toEpochMilli());
            return header(definition);
        });
    }

    public Header header(Definition expected) {
        var rows = jdbc.query("SELECT * FROM campaign_replan_model WHERE model_id=?", (rs, row) -> {
            var run = expected.base().definition(); var caller = run.caller();
            if (!caller.tenantId().equals(rs.getString("tenant_id")) || !caller.subject().equals(rs.getString("subject_name"))
                    || caller.authVersion() != rs.getLong("auth_version") || !run.sessionId().equals(rs.getString("session_id"))
                    || !run.runId().equals(rs.getString("run_id")) || run.revision() != rs.getInt("base_revision")
                    || !expected.stepId().equals(rs.getString("step_id")) || !expected.signalHash().equals(rs.getString("signal_hash"))
                    || !expected.requestHash().equals(rs.getString("request_hash")) || !expected.modelRef().equals(rs.getString("model_ref"))
                    || !expected.modelVersion().equals(rs.getString("model_version"))
                    || !expected.configurationHash().equals(rs.getString("configuration_hash"))
                    || expected.expiresAt().toEpochMilli() != rs.getLong("expires_at")) throw invalid();
            return new Header(expected, State.valueOf(rs.getString("model_state")), rs.getBoolean("callback_active"),
                    rs.getString("invocation_hash"), rs.getString("response_hash"), rs.getString("reason_code"));
        }, expected.id());
        if (rows.size() != 1) throw invalid();
        return rows.get(0);
    }

    public Permit begin(Definition expected, Approval approval) {
        var invocation = approval.invocation();
        if (!expected.id().equals(invocation.invocationId()) || invocation.turnIndex() != 1
                || !expected.modelRef().equals(invocation.modelRef()) || !expected.modelVersion().equals(invocation.modelVersion())
                || !expected.configurationHash().equals(invocation.configurationHash()) || !expected.expiresAt().equals(invocation.expiresAt())) throw invalid();
        return transactions.execute(status -> {
            var base = expected.base(); var definition = base.definition(); var caller = definition.caller();
            var live = jdbc.query("SELECT definition_hash,row_version,advance_token,run_status FROM campaign_run_ledger "
                    + "WHERE run_id=? AND revision=? AND tenant_id=? AND subject_name=? AND auth_version=? FOR UPDATE",
                    (rs, row) -> definition.definitionHash().equals(rs.getString(1)) && base.version() == rs.getLong(2)
                        && base.advanceToken().equals(rs.getString(3)) && "ACTIVE".equals(rs.getString(4)),
                    definition.runId(), definition.revision(), caller.tenantId(), caller.subject(), caller.authVersion());
            if (live.size() != 1 || !live.get(0)) throw new SecurityException("REPLAN_MODEL_RUN_FENCED");
            header(expected);
            String attempt = UUID.randomUUID().toString();
            int changed = jdbc.update("UPDATE campaign_replan_model SET model_state='DISPATCHING',callback_active=TRUE,attempt_id=?,"
                    + "invocation_json=?,invocation_hash=? WHERE model_id=? AND model_state='PREPARED' AND callback_active=FALSE AND expires_at>?",
                    attempt, ModelInvocationRegistry.encode(invocation), invocation.hash(), expected.id(), clock.millis());
            if (changed != 1) throw new IllegalStateException("REPLAN_MODEL_UNRESOLVED");
            return new Permit(expected, attempt, invocation.hash());
        });
    }

    public void publish(Permit permit, Approval approval, Response response) {
        if (!permit.invocationHash().equals(approval.invocation().hash())) throw invalid();
        approval.validateResponse(response);
        String encoded = ModelInvocationRegistry.encodeResponse(response);
        ModelInvocationRegistry.decodeResponse(encoded, limits);
        if (jdbc.update("UPDATE campaign_replan_model SET model_state='READY',response_json=?,response_hash=? "
                + "WHERE model_id=? AND attempt_id=? AND invocation_hash=? AND model_state='DISPATCHING' AND callback_active=TRUE AND expires_at>?",
                encoded, CampaignRunStore.sha256(encoded), permit.definition().id(), permit.attempt(), permit.invocationHash(), clock.millis()) != 1)
            throw new IllegalStateException("REPLAN_MODEL_FENCED");
    }

    public InvocationSpec invocation(Header expected) {
        var current = header(expected.definition());
        if (current.state() != State.READY || current.callbackActive()) throw new IllegalStateException("REPLAN_MODEL_NOT_READY");
        String payload = jdbc.queryForObject("SELECT invocation_json FROM campaign_replan_model WHERE model_id=?", String.class, expected.definition().id());
        if (!CampaignRunStore.sha256(payload).equals(current.invocationHash())) throw invalid();
        var invocation = ModelInvocationRegistry.decode(payload, limits);
        var definition = expected.definition();
        if (!definition.id().equals(invocation.invocationId()) || invocation.turnIndex() != 1
                || !definition.modelRef().equals(invocation.modelRef()) || !definition.modelVersion().equals(invocation.modelVersion())
                || !definition.configurationHash().equals(invocation.configurationHash()) || !definition.expiresAt().equals(invocation.expiresAt())) throw invalid();
        return invocation;
    }

    public Response response(Header expected, ModelInvocationRegistry models) {
        var current = header(expected.definition());
        var invocation = invocation(current);
        var payloads = jdbc.query("SELECT invocation_json,response_json FROM campaign_replan_model WHERE model_id=?",
                (rs, row) -> new String[] { rs.getString(1), rs.getString(2) }, expected.definition().id());
        var payload = payloads.get(0);
        if (!CampaignRunStore.sha256(payload[0]).equals(current.invocationHash())
                || !CampaignRunStore.sha256(payload[1]).equals(current.responseHash())) throw invalid();
        var response = ModelInvocationRegistry.decodeResponse(payload[1], limits);
        var approved = models.approve(invocation); approved.validateResponse(response);
        return response;
    }

    public void unknown(Permit permit) {
        jdbc.update("UPDATE campaign_replan_model SET model_state='UNKNOWN',reason_code='MODEL_OUTCOME_UNKNOWN' "
                + "WHERE model_id=? AND attempt_id=? AND model_state='DISPATCHING'", permit.definition().id(), permit.attempt());
    }
    public void callbackExited(Permit permit) {
        jdbc.update("UPDATE campaign_replan_model SET callback_active=FALSE WHERE model_id=? AND attempt_id=?",
                permit.definition().id(), permit.attempt());
    }
    public void finish(Header expected, boolean applied) {
        jdbc.update("UPDATE campaign_replan_model SET model_state=?,reason_code=? WHERE model_id=? AND model_state='READY' "
                + "AND response_hash=? AND callback_active=FALSE", applied ? "APPLIED" : "REJECTED",
                applied ? null : "REPLAN_PROPOSAL_REJECTED", expected.definition().id(), expected.responseHash());
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("REPLAN_MODEL_DEFINITION_CHANGED"); }
}
