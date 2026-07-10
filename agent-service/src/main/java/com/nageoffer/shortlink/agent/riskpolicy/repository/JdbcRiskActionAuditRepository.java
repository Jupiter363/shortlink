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
        saveAudit(UUID.randomUUID().toString(), policy, "MANUAL", executor, reason);
    }

    public int countByPolicyId(String policyId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from t_agent_risk_action_audit where policy_id = ?",
                Integer.class,
                policyId
        );
        return count == null ? 0 : count;
    }

    private void saveAudit(
            String auditId,
            RiskPolicy policy,
            String executorType,
            String executor,
            String reason
    ) {
        if (updateAudit(auditId, policy, executorType, executor, reason) > 0) {
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
                policy.traceId()
            );
        } catch (DuplicateKeyException ex) {
            updateAudit(auditId, policy, executorType, executor, reason);
        }
    }

    private int updateAudit(
            String auditId,
            RiskPolicy policy,
            String executorType,
            String executor,
            String reason
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
                policy.traceId(),
                auditId
        );
    }
}
