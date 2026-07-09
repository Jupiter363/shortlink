package com.nageoffer.shortlink.agent.riskpolicy.repository;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRiskPolicyRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveActive(RiskPolicy policy) {
        int updatedRows = jdbcTemplate.update("""
                        update t_agent_risk_policy
                        set policy_key = ?,
                            action = ?,
                            target_type = ?,
                            gid = ?,
                            domain = ?,
                            short_uri = ?,
                            ip_hash = ?,
                            policy_payload_json = ?,
                            status = ?,
                            effective_time = ?,
                            expire_time = ?,
                            source = ?,
                            trace_id = ?,
                            event_id = ?,
                            update_time = CURRENT_TIMESTAMP
                        where policy_id = ?
                        """,
                policy.policyKey(),
                policy.action().name(),
                policy.targetType().name(),
                policy.gid(),
                policy.domain(),
                policy.shortUri(),
                policy.ipHash(),
                policy.policyPayloadJson(),
                RiskPolicyStatus.ACTIVE.name(),
                Timestamp.valueOf(policy.effectiveTime()),
                timestamp(policy.expireTime()),
                policy.source().name(),
                policy.traceId(),
                policy.eventId(),
                policy.policyId()
        );
        if (updatedRows > 0) {
            return;
        }
        jdbcTemplate.update("""
                        insert into t_agent_risk_policy (
                            policy_id,
                            policy_key,
                            action,
                            target_type,
                            gid,
                            domain,
                            short_uri,
                            ip_hash,
                            policy_payload_json,
                            status,
                            effective_time,
                            expire_time,
                            source,
                            trace_id,
                            event_id
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                policy.policyId(),
                policy.policyKey(),
                policy.action().name(),
                policy.targetType().name(),
                policy.gid(),
                policy.domain(),
                policy.shortUri(),
                policy.ipHash(),
                policy.policyPayloadJson(),
                RiskPolicyStatus.ACTIVE.name(),
                Timestamp.valueOf(policy.effectiveTime()),
                timestamp(policy.expireTime()),
                policy.source().name(),
                policy.traceId(),
                policy.eventId()
        );
    }

    public Optional<RiskPolicy> findByPolicyId(String policyId) {
        List<RiskPolicy> policies = jdbcTemplate.query("""
                        select policy_id,
                               policy_key,
                               action,
                               target_type,
                               gid,
                               domain,
                               short_uri,
                               ip_hash,
                               policy_payload_json,
                               status,
                               effective_time,
                               expire_time,
                               source,
                               trace_id,
                               event_id
                        from t_agent_risk_policy
                        where policy_id = ?
                        """,
                (rs, rowNum) -> mapPolicy(rs),
                policyId
        );
        return policies.stream().findFirst();
    }

    public Optional<RiskPolicy> findActiveByPolicyKey(String policyKey) {
        List<RiskPolicy> policies = jdbcTemplate.query("""
                        select policy_id,
                               policy_key,
                               action,
                               target_type,
                               gid,
                               domain,
                               short_uri,
                               ip_hash,
                               policy_payload_json,
                               status,
                               effective_time,
                               expire_time,
                               source,
                               trace_id,
                               event_id
                        from t_agent_risk_policy
                        where policy_key = ?
                          and status = ?
                        """,
                (rs, rowNum) -> mapPolicy(rs),
                policyKey,
                RiskPolicyStatus.ACTIVE.name()
        );
        return policies.stream().findFirst();
    }

    public void disable(String policyId, String traceId) {
        jdbcTemplate.update("""
                        update t_agent_risk_policy
                        set status = ?,
                            trace_id = ?,
                            update_time = CURRENT_TIMESTAMP
                        where policy_id = ?
                        """,
                RiskPolicyStatus.DISABLED.name(),
                traceId,
                policyId
        );
    }

    public void expire(String policyId, String traceId) {
        jdbcTemplate.update("""
                        update t_agent_risk_policy
                        set status = ?,
                            trace_id = ?,
                            update_time = CURRENT_TIMESTAMP
                        where policy_id = ?
                        """,
                RiskPolicyStatus.EXPIRED.name(),
                traceId,
                policyId
        );
    }

    private RiskPolicy mapPolicy(ResultSet rs) throws SQLException {
        return new RiskPolicy(
                rs.getString("policy_id"),
                rs.getString("policy_key"),
                RiskPolicyAction.valueOf(rs.getString("action")),
                RiskTargetType.valueOf(rs.getString("target_type")),
                rs.getString("gid"),
                rs.getString("domain"),
                rs.getString("short_uri"),
                rs.getString("ip_hash"),
                rs.getString("policy_payload_json"),
                RiskPolicyStatus.valueOf(rs.getString("status")),
                localDateTime(rs.getTimestamp("effective_time")),
                localDateTime(rs.getTimestamp("expire_time")),
                RiskPolicySource.valueOf(rs.getString("source")),
                rs.getString("trace_id"),
                rs.getString("event_id")
        );
    }

    private Timestamp timestamp(LocalDateTime localDateTime) {
        return localDateTime == null ? null : Timestamp.valueOf(localDateTime);
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
