package com.nageoffer.shortlink.agent.riskpolicy.repository;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcEffectiveRiskPolicyRepository {

    private static final String SELECT_COLUMNS = """
            id,
            policy_key,
            policy_id,
            policy_version,
            gid,
            action,
            desired_state,
            policy_payload_json,
            redis_value_json,
            effective_time,
            expire_time,
            sync_status,
            last_outbox_id,
            trace_id,
            create_time,
            update_time
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcEffectiveRiskPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<EffectiveRiskPolicy> findByPolicyKey(String policyKey) {
        return queryOne("""
                        select %s
                        from t_agent_risk_policy_effective
                        where policy_key = ?
                        limit 1
                        """.formatted(SELECT_COLUMNS),
                policyKey
        );
    }

    public Optional<EffectiveRiskPolicy> findByPolicyKeyForUpdate(String policyKey) {
        return queryOne("""
                        select %s
                        from t_agent_risk_policy_effective
                        where policy_key = ?
                        limit 1 for update
                        """.formatted(SELECT_COLUMNS),
                policyKey
        );
    }

    public List<EffectiveRiskPolicy> findDueActive(LocalDateTime now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        int safeLimit = Math.max(1, limit);
        return jdbcTemplate.query("""
                        select %s
                        from t_agent_risk_policy_effective
                        where desired_state = ?
                          and expire_time is not null
                          and expire_time <= ?
                        order by expire_time, id
                        limit ?
                        """.formatted(SELECT_COLUMNS),
                this::mapPolicy,
                RiskPolicyDesiredState.ACTIVE.name(),
                Timestamp.valueOf(now),
                safeLimit
        );
    }

    public void upsert(EffectiveRiskPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (updateIfNewer(policy) > 0) {
            return;
        }
        try {
            insert(policy);
        } catch (DuplicateKeyException ex) {
            if (updateIfNewer(policy) > 0) {
                return;
            }
            EffectiveRiskPolicy current = findByPolicyKey(policy.policyKey()).orElse(null);
            if (hasSameIdentity(current, policy)) {
                return;
            }
            throw new IllegalStateException("Effective risk policy version is stale", ex);
        }
    }

    public boolean updateSyncStatusIfVersion(
            String policyKey,
            String policyId,
            long policyVersion,
            RiskPolicySyncStatus syncStatus,
            String outboxId,
            String traceId
    ) {
        Objects.requireNonNull(syncStatus, "syncStatus must not be null");
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_effective
                        set sync_status = ?,
                            trace_id = ?,
                            update_time = CURRENT_TIMESTAMP
                        where policy_key = ?
                          and policy_id = ?
                          and policy_version = ?
                          and last_outbox_id = ?
                        """,
                syncStatus.name(),
                valueOrEmpty(traceId),
                policyKey,
                policyId,
                policyVersion,
                valueOrEmpty(outboxId)
        ) > 0;
    }

    public boolean updateSyncStatusIfStateAndVersion(
            String policyKey,
            String policyId,
            long policyVersion,
            RiskPolicyDesiredState desiredState,
            RiskPolicySyncStatus syncStatus,
            String outboxId,
            String traceId
    ) {
        Objects.requireNonNull(desiredState, "desiredState must not be null");
        Objects.requireNonNull(syncStatus, "syncStatus must not be null");
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_effective
                        set sync_status = ?,
                            trace_id = ?,
                            update_time = CURRENT_TIMESTAMP
                        where policy_key = ?
                          and policy_id = ?
                          and policy_version = ?
                          and desired_state = ?
                          and last_outbox_id = ?
                        """,
                syncStatus.name(),
                valueOrEmpty(traceId),
                policyKey,
                policyId,
                policyVersion,
                desiredState.name(),
                valueOrEmpty(outboxId)
        ) > 0;
    }

    public boolean markExpiredIfVersion(
            String policyKey,
            String policyId,
            long policyVersion,
            String outboxId
    ) {
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_effective
                        set desired_state = ?,
                            sync_status = ?,
                            last_outbox_id = ?,
                            update_time = CURRENT_TIMESTAMP
                        where policy_key = ?
                          and policy_id = ?
                          and policy_version = ?
                          and desired_state = ?
                        """,
                RiskPolicyDesiredState.EXPIRED.name(),
                RiskPolicySyncStatus.PENDING.name(),
                valueOrEmpty(outboxId),
                policyKey,
                policyId,
                policyVersion,
                RiskPolicyDesiredState.ACTIVE.name()
        ) > 0;
    }

    private int updateIfNewer(EffectiveRiskPolicy policy) {
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_effective
                        set policy_id = ?,
                            policy_version = ?,
                            gid = ?,
                            action = ?,
                            desired_state = ?,
                            policy_payload_json = ?,
                            redis_value_json = ?,
                            effective_time = ?,
                            expire_time = ?,
                            sync_status = ?,
                            last_outbox_id = ?,
                            trace_id = ?,
                            update_time = COALESCE(?, CURRENT_TIMESTAMP)
                        where policy_key = ?
                          and policy_version < ?
                        """,
                policy.policyId(),
                policy.policyVersion(),
                policy.gid(),
                policy.action().name(),
                policy.desiredState().name(),
                policy.policyPayloadJson(),
                policy.redisValueJson(),
                Timestamp.valueOf(policy.effectiveTime()),
                timestamp(policy.expireTime()),
                policy.syncStatus().name(),
                policy.lastOutboxId(),
                policy.traceId(),
                timestamp(policy.updateTime()),
                policy.policyKey(),
                policy.policyVersion()
        );
    }

    private boolean hasSameIdentity(
            EffectiveRiskPolicy current,
            EffectiveRiskPolicy candidate
    ) {
        return current != null
                && current.policyVersion() == candidate.policyVersion()
                && current.policyId().equals(candidate.policyId());
    }

    private void insert(EffectiveRiskPolicy policy) {
        jdbcTemplate.update("""
                        insert into t_agent_risk_policy_effective (
                            policy_key,
                            policy_id,
                            policy_version,
                            gid,
                            action,
                            desired_state,
                            policy_payload_json,
                            redis_value_json,
                            effective_time,
                            expire_time,
                            sync_status,
                            last_outbox_id,
                            trace_id,
                            create_time,
                            update_time
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                COALESCE(?, CURRENT_TIMESTAMP), COALESCE(?, CURRENT_TIMESTAMP))
                        """,
                policy.policyKey(),
                policy.policyId(),
                policy.policyVersion(),
                policy.gid(),
                policy.action().name(),
                policy.desiredState().name(),
                policy.policyPayloadJson(),
                policy.redisValueJson(),
                Timestamp.valueOf(policy.effectiveTime()),
                timestamp(policy.expireTime()),
                policy.syncStatus().name(),
                policy.lastOutboxId(),
                policy.traceId(),
                timestamp(policy.createTime()),
                timestamp(policy.updateTime())
        );
    }

    private Optional<EffectiveRiskPolicy> queryOne(String sql, Object... args) {
        List<EffectiveRiskPolicy> policies = jdbcTemplate.query(sql, this::mapPolicy, args);
        return policies.stream().findFirst();
    }

    private EffectiveRiskPolicy mapPolicy(ResultSet rs, int rowNum) throws SQLException {
        return new EffectiveRiskPolicy(
                rs.getObject("id", Long.class),
                rs.getString("policy_key"),
                rs.getString("policy_id"),
                rs.getLong("policy_version"),
                rs.getString("gid"),
                RiskPolicyAction.valueOf(rs.getString("action")),
                RiskPolicyDesiredState.valueOf(rs.getString("desired_state")),
                rs.getString("policy_payload_json"),
                rs.getString("redis_value_json"),
                localDateTime(rs.getTimestamp("effective_time")),
                localDateTime(rs.getTimestamp("expire_time")),
                RiskPolicySyncStatus.valueOf(rs.getString("sync_status")),
                rs.getString("last_outbox_id"),
                rs.getString("trace_id"),
                localDateTime(rs.getTimestamp("create_time")),
                localDateTime(rs.getTimestamp("update_time"))
        );
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
