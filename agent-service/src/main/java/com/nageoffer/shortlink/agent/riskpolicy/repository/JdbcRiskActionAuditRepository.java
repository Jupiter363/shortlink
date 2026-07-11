package com.nageoffer.shortlink.agent.riskpolicy.repository;

import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Repository
public class JdbcRiskActionAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskActionAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveActivationAudit(RiskPolicy policy, String executor, String reason) {
        String auditId = "audit-activation-" + UUID.nameUUIDFromBytes(
                ("activation|" + policy.policyId()).getBytes(StandardCharsets.UTF_8)
        );
        saveAudit(auditId, policy, policy.source().name(), executor, reason);
    }

    public void saveDisableAudit(RiskPolicy policy, String executor, String reason) {
        saveDisableAudit(policy, executor, reason, policy.traceId());
    }

    public void saveDisableAudit(
            RiskPolicy policy,
            String executor,
            String reason,
            String traceId
    ) {
        String auditId = "audit-disable-" + UUID.nameUUIDFromBytes(
                ("disable|" + policy.policyId()).getBytes(StandardCharsets.UTF_8)
        );
        saveAudit(auditId, policy, "MANUAL", executor, reason, traceId);
    }

    public int countByPolicyId(String policyId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from t_agent_risk_action_audit where policy_id = ?",
                Integer.class,
                policyId
        );
        return count == null ? 0 : count;
    }

    public void saveOutboxReplayAudit(
            String outboxId,
            String policyId,
            String executor,
            String reason
    ) {
        jdbcTemplate.update("""
                        insert into t_agent_risk_action_audit (
                            audit_id,
                            policy_id,
                            event_id,
                            action,
                            executor_type,
                            executor,
                            reason,
                            evidence_json,
                            trace_id
                        )
                        values (?, ?, ?, 'OUTBOX_REPLAY', 'MANUAL', ?, ?, '{}', '')
                        """,
                "audit-outbox-replay-" + UUID.randomUUID(),
                policyId,
                outboxId,
                executor,
                reason
        );
    }

    private void saveAudit(
            String auditId,
            RiskPolicy policy,
            String executorType,
            String executor,
            String reason
    ) {
        saveAudit(auditId, policy, executorType, executor, reason, policy.traceId());
    }

    private void saveAudit(
            String auditId,
            RiskPolicy policy,
            String executorType,
            String executor,
            String reason,
            String traceId
    ) {
        if (updateAudit(auditId, policy, executorType, executor, reason, traceId) > 0) {
            return;
        }
        try {
            jdbcTemplate.update("""
                        insert into t_agent_risk_action_audit (
                            audit_id,
                            policy_id,
                            event_id,
                            action,
                            executor_type,
                            executor,
                            reason,
                            evidence_json,
                            trace_id
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                auditId,
                policy.policyId(),
                policy.eventId(),
                policy.action().name(),
                executorType,
                executor,
                reason,
                "{}",
                traceId
            );
        } catch (DuplicateKeyException ex) {
            updateAudit(auditId, policy, executorType, executor, reason, traceId);
        }
    }

    private int updateAudit(
            String auditId,
            RiskPolicy policy,
            String executorType,
            String executor,
            String reason,
            String traceId
    ) {
        return jdbcTemplate.update("""
                        update t_agent_risk_action_audit
                        set policy_id = ?,
                            event_id = ?,
                            action = ?,
                            executor_type = ?,
                            executor = ?,
                            reason = ?,
                            evidence_json = ?,
                            trace_id = ?
                        where audit_id = ?
                        """,
                policy.policyId(),
                policy.eventId(),
                policy.action().name(),
                executorType,
                executor,
                reason,
                "{}",
                traceId,
                auditId
        );
    }
}
