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

    private static final String SELECT_COLUMNS = """
            policy_id,
            policy_key,
            idempotency_key,
            policy_version,
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
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(RiskPolicy policy) {
        validateHistoryIdentity(policy);
        jdbcTemplate.update("""
                        insert into t_agent_risk_policy (
                            policy_id,
                            policy_key,
                            idempotency_key,
                            policy_version,
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
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                policy.policyId(),
                policy.policyKey(),
                policy.idempotencyKey(),
                policy.policyVersion(),
                policy.action().name(),
                policy.targetType().name(),
                policy.gid(),
                policy.domain(),
                policy.shortUri(),
                policy.ipHash(),
                policy.policyPayloadJson(),
                policy.status().name(),
                Timestamp.valueOf(policy.effectiveTime()),
                timestamp(policy.expireTime()),
                policy.source().name(),
                policy.traceId(),
                policy.eventId()
        );
    }

    public Optional<RiskPolicy> findByPolicyId(String policyId) {
        return findOne("policy_id", policyId);
    }

    public Optional<RiskPolicy> findByIdempotencyKey(String idempotencyKey) {
        return findOne("idempotency_key", idempotencyKey);
    }

    public List<RiskPolicy> findByPolicyKeyOrderByVersion(String policyKey) {
        return jdbcTemplate.query("""
                        select %s
                        from t_agent_risk_policy
                        where policy_key = ?
                        order by policy_version desc, id desc
                        """.formatted(SELECT_COLUMNS),
                (rs, rowNum) -> mapPolicy(rs),
                policyKey
        );
    }

    public List<RiskPolicy> findByPolicyKeyOrderByVersionForUpdate(String policyKey) {
        return jdbcTemplate.query("""
                        select %s
                        from t_agent_risk_policy
                        where policy_key = ?
                        order by policy_version desc, id desc
                        for update
                        """.formatted(SELECT_COLUMNS),
                (rs, rowNum) -> mapPolicy(rs),
                policyKey
        );
    }

    /**
     * Transitional read API retained until effective policy slots become authoritative.
     */
    public Optional<RiskPolicy> findActiveByPolicyKey(String policyKey) {
        List<RiskPolicy> policies = jdbcTemplate.query("""
                        select %s
                        from t_agent_risk_policy
                        where policy_key = ?
                          and status = ?
                        order by policy_version desc, id desc
                        """.formatted(SELECT_COLUMNS),
                (rs, rowNum) -> mapPolicy(rs),
                policyKey,
                RiskPolicyStatus.ACTIVE.name()
        );
        return policies.stream().findFirst();
    }

    public boolean markSuperseded(String policyId, String traceId) {
        return updateStatus(policyId, RiskPolicyStatus.SUPERSEDED, traceId);
    }

    public boolean markDisabled(String policyId, String traceId) {
        return updateStatus(policyId, RiskPolicyStatus.DISABLED, traceId);
    }

    public boolean markExpired(String policyId, String traceId) {
        return updateStatus(policyId, RiskPolicyStatus.EXPIRED, traceId);
    }

    private Optional<RiskPolicy> findOne(String column, String value) {
        List<RiskPolicy> policies = jdbcTemplate.query("""
                        select %s
                        from t_agent_risk_policy
                        where %s = ?
                        """.formatted(SELECT_COLUMNS, column),
                (rs, rowNum) -> mapPolicy(rs),
                value
        );
        return policies.stream().findFirst();
    }

    private boolean updateStatus(String policyId, RiskPolicyStatus status, String traceId) {
        return jdbcTemplate.update("""
                        update t_agent_risk_policy
                        set status = ?,
                            trace_id = ?,
                            update_time = CURRENT_TIMESTAMP
                        where policy_id = ?
                          and status = ?
                        """,
                status.name(),
                traceId,
                policyId,
                RiskPolicyStatus.ACTIVE.name()
        ) > 0;
    }

    private void validateHistoryIdentity(RiskPolicy policy) {
        if (policy == null
                || policy.idempotencyKey() == null
                || policy.idempotencyKey().isBlank()
                || policy.policyVersion() <= 0) {
            throw new IllegalArgumentException("Risk policy history identity is invalid");
        }
    }

    private RiskPolicy mapPolicy(ResultSet rs) throws SQLException {
        String policyId = rs.getString("policy_id");
        String idempotencyKey = rs.getString("idempotency_key");
        long policyVersion = rs.getLong("policy_version");
        if (rs.wasNull()) {
            policyVersion = 0L;
        }
        return new RiskPolicy(
                policyId,
                rs.getString("policy_key"),
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? "legacy:" + policyId
                        : idempotencyKey,
                policyVersion,
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
