package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared short-row admission checks. Does not load call arguments, model responses, or Graph state. */
public final class JdbcExplorationCallbackGate {
    private final JdbcTemplate jdbc;
    private volatile boolean foundSchema;

    public JdbcExplorationCallbackGate(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    /** Only an authoritative metadata absence allows legacy operation; SQL errors propagate. */
    public boolean schemaAvailable() {
        if (foundSchema) return true;
        boolean exists = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), null,
                    new String[] {"TABLE"})) {
                while (tables.next())
                    if ("campaign_exploration_call".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
        if (exists) foundSchema = true;
        return exists;
    }

    /** Includes old revisions: a new revision never proves that an old callback has exited. */
    public boolean hasActive(String runId) {
        if (!schemaAvailable()) return false;
        return !jdbc.query("SELECT call_id FROM campaign_exploration_call WHERE run_id=? AND callback_active=TRUE FOR UPDATE",
                (rs, row) -> rs.getString("call_id"), runId).isEmpty();
    }

    public void requireNoActive(String runId) {
        if (hasActive(runId)) throw new IllegalStateException("EXPLORATION_CALLBACK_STILL_ACTIVE");
    }

    /** Classification only; the caller's ordinary Run checks still apply. */
    public boolean isCallAction(RunToken token, String actionId) {
        if (!schemaAvailable()) return false;
        return !jdbc.query("SELECT call_id FROM campaign_exploration_call WHERE run_id=? AND revision=? AND action_id=? FOR UPDATE",
                (rs, row) -> rs.getString("call_id"), token.definition().runId(), token.definition().revision(), actionId).isEmpty();
    }

    /** Called under the caller's existing Run transaction before registering an owned child attempt. */
    public void requireParent(CallPermit permit, String actionId) {
        if (permit == null || !Objects.equals(actionId, permit.actionId()) || !mayExecute(permit))
            throw new IllegalStateException("EXPLORATION_CALL_FENCED");
    }

    public boolean mayExecute(CallPermit permit) {
        if (permit == null || permit.step() == null || permit.step().runToken() == null || !schemaAvailable()) return false;
        var step = permit.step();
        var token = step.runToken();
        var definition = token.definition();
        var owner = definition.caller();
        // Locking reads observe revocation/exit even when the surrounding transaction already has an RR snapshot.
        var activeCalls = jdbc.query("SELECT revision,call_id FROM campaign_exploration_call WHERE run_id=? AND callback_active=TRUE FOR UPDATE",
                (rs, row) -> definition.revision() == rs.getInt("revision") && Objects.equals(permit.callId(), rs.getString("call_id")),
                definition.runId());
        if (activeCalls.size() != 1 || !activeCalls.get(0)) return false;
        var matches = jdbc.query("SELECT c.action_id,c.step_id,c.call_state,c.callback_active,c.revoked,c.attempt_id,c.attempt_version,"
                        + "c.step_attempt_id,c.step_attempt_version,c.dispatch_run_version,c.dispatch_run_token,"
                        + "r.tenant_id,r.subject_name,r.auth_version,r.session_id,r.plan_id,r.definition_hash,r.run_status,"
                        + "r.row_version AS run_version,r.advance_token,s.step_status,s.callback_active AS step_active,"
                        + "s.attempt_id AS actual_step_attempt,s.attempt_version AS actual_step_version,"
                        + "s.dispatch_run_version AS step_run_version,s.dispatch_run_token AS step_run_token "
                        + "FROM campaign_exploration_call c JOIN campaign_run_ledger r ON r.run_id=c.run_id AND r.revision=c.revision "
                        + "JOIN campaign_step_ledger s ON s.run_id=c.run_id AND s.revision=c.revision AND s.step_id=c.step_id "
                        + "WHERE c.run_id=? AND c.revision=? AND c.call_id=? FOR UPDATE",
                (rs, row) -> permit.actionId().equals(rs.getString("action_id")) && step.stepId().equals(rs.getString("step_id"))
                        && "RUNNING".equals(rs.getString("call_state")) && rs.getBoolean("callback_active") && !rs.getBoolean("revoked")
                        && Objects.equals(permit.attemptId(), rs.getString("attempt_id")) && permit.attemptVersion() == rs.getLong("attempt_version")
                        && Objects.equals(step.attemptId(), rs.getString("step_attempt_id")) && step.attemptVersion() == rs.getLong("step_attempt_version")
                        && token.version() == rs.getLong("dispatch_run_version") && token.advanceToken().equals(rs.getString("dispatch_run_token"))
                        && owner.tenantId().equals(rs.getString("tenant_id")) && owner.subject().equals(rs.getString("subject_name"))
                        && owner.authVersion() == rs.getLong("auth_version") && definition.sessionId().equals(rs.getString("session_id"))
                        && definition.planId().equals(rs.getString("plan_id")) && definition.definitionHash().equals(rs.getString("definition_hash"))
                        && "ACTIVE".equals(rs.getString("run_status")) && token.version() == rs.getLong("run_version")
                        && token.advanceToken().equals(rs.getString("advance_token")) && "RUNNING".equals(rs.getString("step_status"))
                        && rs.getBoolean("step_active") && Objects.equals(step.attemptId(), rs.getString("actual_step_attempt"))
                        && step.attemptVersion() == rs.getLong("actual_step_version") && token.version() == rs.getLong("step_run_version")
                        && token.advanceToken().equals(rs.getString("step_run_token")),
                definition.runId(), definition.revision(), permit.callId());
        return matches.size() == 1 && matches.get(0);
    }
}
