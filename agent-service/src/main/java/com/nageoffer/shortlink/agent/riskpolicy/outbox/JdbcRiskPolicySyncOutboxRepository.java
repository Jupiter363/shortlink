package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.riskcommon.safety.RiskIpSafety;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Repository
public class JdbcRiskPolicySyncOutboxRepository {

    private static final int CLAIM_SCAN_LIMIT = 16;
    private static final int LAST_ERROR_MAX_LENGTH = 2048;
    private static final String LEASE_EXPIRED_ERROR = "Risk policy sync lease expired";
    private static final String ATTEMPTS_EXHAUSTED_ERROR = "Risk policy sync exhausted maximum attempts";
    private static final String REDIS_VALUE_MISMATCH_PREFIX = "REDIS_VALUE_MISMATCH";
    private static final Pattern JDBC_URL_PATTERN = Pattern.compile("(?i)\\bjdbc:[^\\s,;]+(?:;[^\\s,]+)*");
    private static final Pattern URI_USER_INFO_PATTERN = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)[^/\\s:@]+(?::[^/\\s@]*)?@"
    );
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}\\b");
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\bbearer\\s+[a-z0-9._~+/-]+=*"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = assignmentPattern(
            "authorization|token|password|secret|api[_-]?key|access[_-]?token|refresh[_-]?token",
            true
    );
    private static final Pattern IDENTITY_ASSIGNMENT_PATTERN = assignmentPattern(
            "user|username|uid|user[_-]?id|visitor|visitor[_-]?id|account"
                    + "|raw[_-]?user[_-]?id|raw[_-]?visitor[_-]?id",
            false
    );
    private static final Pattern COOKIE_HEADER_PATTERN = Pattern.compile(
            "(?i)(\\b(?:cookie|set-cookie)\\b\\s*[:=]\\s*)[^\\r\\n]+"
    );
    private static final String SELECT_COLUMNS = """
            id,
            outbox_id,
            policy_key,
            policy_id,
            policy_version,
            operation,
            redis_value_json,
            expected_redis_value,
            status,
            attempt_count,
            next_retry_time,
            owner_token,
            lease_until,
            last_error,
            create_time,
            update_time
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskPolicySyncOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean createIfAbsent(RiskPolicySyncOutbox outbox) {
        Objects.requireNonNull(outbox, "outbox must not be null");
        rejectRawIp(outbox.policyKey(), "policyKey");
        rejectRawIp(outbox.redisValueJson(), "redisValueJson");
        rejectRawIp(outbox.expectedRedisValue(), "expectedRedisValue");
        try {
            return jdbcTemplate.update("""
                            insert into t_agent_risk_policy_sync_outbox (
                                outbox_id,
                                policy_key,
                                policy_id,
                                policy_version,
                                operation,
                                redis_value_json,
                                expected_redis_value,
                                status,
                                attempt_count,
                                next_retry_time,
                                owner_token,
                                lease_until,
                                last_error,
                                create_time,
                                update_time
                            )
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    COALESCE(?, CURRENT_TIMESTAMP), COALESCE(?, CURRENT_TIMESTAMP))
                            """,
                    outbox.outboxId(),
                    outbox.policyKey(),
                    outbox.policyId(),
                    outbox.policyVersion(),
                    outbox.operation().name(),
                    outbox.redisValueJson(),
                    outbox.expectedRedisValue(),
                    outbox.status().name(),
                    outbox.attemptCount(),
                    timestamp(outbox.nextRetryTime()),
                    outbox.ownerToken(),
                    timestamp(outbox.leaseUntil()),
                    sanitizeError(outbox.lastError()),
                    timestamp(outbox.createTime()),
                    timestamp(outbox.updateTime())
            ) > 0;
        } catch (DuplicateKeyException ex) {
            return resolveDuplicate(outbox, ex);
        }
    }

    public Optional<RiskPolicySyncOutbox> findByOutboxId(String outboxId) {
        return queryOne("""
                        select %s
                        from t_agent_risk_policy_sync_outbox
                        where outbox_id = ?
                        limit 1
                        """.formatted(SELECT_COLUMNS),
                outboxId
        );
    }

    public Optional<RiskPolicySyncOutbox> findByOutboxIdForUpdate(String outboxId) {
        return queryOne("""
                        select %s
                        from t_agent_risk_policy_sync_outbox
                        where outbox_id = ?
                        limit 1 for update
                        """.formatted(SELECT_COLUMNS),
                outboxId
        );
    }

    public Optional<RiskPolicySyncOutbox> findByPolicyKeyVersionAndOperation(
            String policyKey,
            long policyVersion,
            RiskPolicySyncOperation operation
    ) {
        Objects.requireNonNull(operation, "operation must not be null");
        return queryOne("""
                        select %s
                        from t_agent_risk_policy_sync_outbox
                        where policy_key = ?
                          and policy_version = ?
                          and operation = ?
                        limit 1
                        """.formatted(SELECT_COLUMNS),
                policyKey,
                policyVersion,
                operation.name()
        );
    }

    public Optional<RiskPolicySyncOutbox> claimNext(
            String ownerToken,
            LocalDateTime now,
            Duration lease,
            int maxAttempts
    ) {
        requireText(ownerToken, "ownerToken");
        Objects.requireNonNull(now, "now must not be null");
        requirePositive(lease, "lease");
        int safeMaxAttempts = Math.max(1, maxAttempts);
        recoverExpiredProcessing(now, safeMaxAttempts);
        markExhaustedReadyAsDead(now, safeMaxAttempts);
        LocalDateTime leaseUntil = now.plus(lease);
        List<ClaimCandidate> candidates = jdbcTemplate.query("""
                        select outbox_id, attempt_count
                        from t_agent_risk_policy_sync_outbox
                        where attempt_count < ?
                          and (
                              status = ?
                              or (status = ? and (next_retry_time is null or next_retry_time <= ?))
                          )
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> new ClaimCandidate(
                        rs.getString("outbox_id"),
                        rs.getInt("attempt_count")
                ),
                safeMaxAttempts,
                RiskPolicySyncOutboxStatus.PENDING.name(),
                RiskPolicySyncOutboxStatus.RETRY_WAIT.name(),
                Timestamp.valueOf(now),
                CLAIM_SCAN_LIMIT
        );
        for (ClaimCandidate candidate : candidates) {
            int updated = jdbcTemplate.update("""
                            update t_agent_risk_policy_sync_outbox
                            set status = ?,
                                attempt_count = attempt_count + 1,
                                next_retry_time = null,
                                owner_token = ?,
                                lease_until = ?,
                                update_time = ?
                            where outbox_id = ?
                              and attempt_count = ?
                              and attempt_count < ?
                              and (
                                  status = ?
                                  or (status = ? and (next_retry_time is null or next_retry_time <= ?))
                              )
                            """,
                    RiskPolicySyncOutboxStatus.PROCESSING.name(),
                    ownerToken,
                    Timestamp.valueOf(leaseUntil),
                    Timestamp.valueOf(now),
                    candidate.outboxId(),
                    candidate.attemptCount(),
                    safeMaxAttempts,
                    RiskPolicySyncOutboxStatus.PENDING.name(),
                    RiskPolicySyncOutboxStatus.RETRY_WAIT.name(),
                    Timestamp.valueOf(now)
            );
            if (updated > 0) {
                Optional<RiskPolicySyncOutbox> claimed = findClaimedByOwner(
                        candidate.outboxId(),
                        ownerToken,
                        leaseUntil
                );
                if (claimed.isPresent()) {
                    return claimed;
                }
            }
        }
        return Optional.empty();
    }

    public boolean markSucceeded(String outboxId, String ownerToken, LocalDateTime now) {
        return markTerminal(
                outboxId,
                ownerToken,
                RiskPolicySyncOutboxStatus.SUCCEEDED,
                "",
                now
        );
    }

    public boolean markSkipped(
            String outboxId,
            String ownerToken,
            String reason,
            LocalDateTime now
    ) {
        return markTerminal(
                outboxId,
                ownerToken,
                RiskPolicySyncOutboxStatus.SKIPPED,
                sanitizeError(reason),
                now
        );
    }

    public boolean recordFailure(
            String outboxId,
            String ownerToken,
            int maxAttempts,
            LocalDateTime now,
            LocalDateTime nextRetry,
            String error
    ) {
        requireText(outboxId, "outboxId");
        requireText(ownerToken, "ownerToken");
        Objects.requireNonNull(now, "now must not be null");
        int safeMaxAttempts = Math.max(1, maxAttempts);
        LocalDateTime safeNextRetry = nextRetry == null ? now : nextRetry;
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_sync_outbox
                        set status = case
                                when attempt_count >= ? then ?
                                else ?
                            end,
                            next_retry_time = case
                                when attempt_count >= ? then null
                                else ?
                            end,
                            owner_token = '',
                            lease_until = null,
                            last_error = ?,
                            update_time = ?
                        where outbox_id = ?
                          and cast(owner_token as binary(128)) = cast(? as binary(128))
                          and status = ?
                          and lease_until is not null
                          and lease_until > ?
                        """,
                safeMaxAttempts,
                RiskPolicySyncOutboxStatus.DEAD.name(),
                RiskPolicySyncOutboxStatus.RETRY_WAIT.name(),
                safeMaxAttempts,
                Timestamp.valueOf(safeNextRetry),
                sanitizeError(error),
                Timestamp.valueOf(now),
                outboxId,
                ownerToken,
                RiskPolicySyncOutboxStatus.PROCESSING.name(),
                Timestamp.valueOf(now)
        ) > 0;
    }

    public boolean resetForReplay(String outboxId, LocalDateTime now) {
        requireText(outboxId, "outboxId");
        Objects.requireNonNull(now, "now must not be null");
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_sync_outbox
                        set status = ?,
                            attempt_count = 0,
                            next_retry_time = ?,
                            owner_token = '',
                            lease_until = null,
                            last_error = '',
                            update_time = ?
                        where outbox_id = ?
                          and (
                              status = ?
                              or (status = ? and last_error like ?)
                          )
                        """,
                RiskPolicySyncOutboxStatus.PENDING.name(),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                outboxId,
                RiskPolicySyncOutboxStatus.DEAD.name(),
                RiskPolicySyncOutboxStatus.SKIPPED.name(),
                REDIS_VALUE_MISMATCH_PREFIX + "%"
        ) > 0;
    }

    public int recoverExpiredProcessing(LocalDateTime now, int maxAttempts) {
        Objects.requireNonNull(now, "now must not be null");
        int safeMaxAttempts = Math.max(1, maxAttempts);
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_sync_outbox
                        set status = case
                                when attempt_count >= ? then ?
                                else ?
                            end,
                            next_retry_time = case
                                when attempt_count >= ? then null
                                else ?
                            end,
                            owner_token = '',
                            lease_until = null,
                            last_error = case
                                when last_error is null or last_error = '' then ?
                                else last_error
                            end,
                            update_time = ?
                        where status = ?
                          and (lease_until is null or lease_until <= ?)
                        """,
                safeMaxAttempts,
                RiskPolicySyncOutboxStatus.DEAD.name(),
                RiskPolicySyncOutboxStatus.RETRY_WAIT.name(),
                safeMaxAttempts,
                Timestamp.valueOf(now),
                LEASE_EXPIRED_ERROR,
                Timestamp.valueOf(now),
                RiskPolicySyncOutboxStatus.PROCESSING.name(),
                Timestamp.valueOf(now)
        );
    }

    private boolean markTerminal(
            String outboxId,
            String ownerToken,
            RiskPolicySyncOutboxStatus status,
            String lastError,
            LocalDateTime now
    ) {
        requireText(outboxId, "outboxId");
        requireText(ownerToken, "ownerToken");
        Objects.requireNonNull(now, "now must not be null");
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_sync_outbox
                        set status = ?,
                            next_retry_time = null,
                            owner_token = '',
                            lease_until = null,
                            last_error = ?,
                            update_time = ?
                        where outbox_id = ?
                          and cast(owner_token as binary(128)) = cast(? as binary(128))
                          and status = ?
                          and lease_until is not null
                          and lease_until > ?
                        """,
                status.name(),
                lastError,
                Timestamp.valueOf(now),
                outboxId,
                ownerToken,
                RiskPolicySyncOutboxStatus.PROCESSING.name(),
                Timestamp.valueOf(now)
        ) > 0;
    }

    private int markExhaustedReadyAsDead(LocalDateTime now, int maxAttempts) {
        return jdbcTemplate.update("""
                        update t_agent_risk_policy_sync_outbox
                        set status = ?,
                            next_retry_time = null,
                            owner_token = '',
                            lease_until = null,
                            last_error = case
                                when last_error is null or last_error = '' then ?
                                else last_error
                            end,
                            update_time = ?
                        where status in (?, ?)
                          and attempt_count >= ?
                        """,
                RiskPolicySyncOutboxStatus.DEAD.name(),
                ATTEMPTS_EXHAUSTED_ERROR,
                Timestamp.valueOf(now),
                RiskPolicySyncOutboxStatus.PENDING.name(),
                RiskPolicySyncOutboxStatus.RETRY_WAIT.name(),
                maxAttempts
        );
    }

    private Optional<RiskPolicySyncOutbox> queryOne(String sql, Object... args) {
        List<RiskPolicySyncOutbox> outboxes = jdbcTemplate.query(sql, this::mapOutbox, args);
        return outboxes.stream().findFirst();
    }

    private Optional<RiskPolicySyncOutbox> findClaimedByOwner(
            String outboxId,
            String ownerToken,
            LocalDateTime leaseUntil
    ) {
        return queryOne("""
                        select %s
                        from t_agent_risk_policy_sync_outbox
                        where outbox_id = ?
                          and status = ?
                          and cast(owner_token as binary(128)) = cast(? as binary(128))
                          and lease_until = ?
                        limit 1
                        """.formatted(SELECT_COLUMNS),
                outboxId,
                RiskPolicySyncOutboxStatus.PROCESSING.name(),
                ownerToken,
                Timestamp.valueOf(leaseUntil)
        );
    }

    private boolean resolveDuplicate(
            RiskPolicySyncOutbox candidate,
            DuplicateKeyException duplicate
    ) {
        Optional<RiskPolicySyncOutbox> byOutboxId = findByOutboxId(candidate.outboxId());
        if (byOutboxId.isPresent()) {
            if (hasSameLogicalPayload(byOutboxId.orElseThrow(), candidate)) {
                return false;
            }
            throw new IllegalStateException(
                    "Risk policy sync outboxId conflicts with another event",
                    duplicate
            );
        }
        Optional<RiskPolicySyncOutbox> byOperation = findByPolicyKeyVersionAndOperation(
                candidate.policyKey(),
                candidate.policyVersion(),
                candidate.operation()
        );
        if (byOperation.isPresent()) {
            if (hasSameLogicalPayload(byOperation.orElseThrow(), candidate)) {
                return false;
            }
            throw new IllegalStateException(
                    "Risk policy sync outbox payload conflicts with existing operation",
                    duplicate
            );
        }
        throw duplicate;
    }

    private boolean hasSameLogicalPayload(
            RiskPolicySyncOutbox existing,
            RiskPolicySyncOutbox candidate
    ) {
        return existing.policyKey().equals(candidate.policyKey())
                && existing.policyId().equals(candidate.policyId())
                && existing.policyVersion() == candidate.policyVersion()
                && existing.operation() == candidate.operation()
                && existing.redisValueJson().equals(candidate.redisValueJson())
                && existing.expectedRedisValue().equals(candidate.expectedRedisValue());
    }

    private RiskPolicySyncOutbox mapOutbox(ResultSet rs, int rowNum) throws SQLException {
        return new RiskPolicySyncOutbox(
                rs.getObject("id", Long.class),
                rs.getString("outbox_id"),
                rs.getString("policy_key"),
                rs.getString("policy_id"),
                rs.getLong("policy_version"),
                RiskPolicySyncOperation.valueOf(rs.getString("operation")),
                rs.getString("redis_value_json"),
                rs.getString("expected_redis_value"),
                RiskPolicySyncOutboxStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                localDateTime(rs.getTimestamp("next_retry_time")),
                rs.getString("owner_token"),
                localDateTime(rs.getTimestamp("lease_until")),
                rs.getString("last_error"),
                localDateTime(rs.getTimestamp("create_time")),
                localDateTime(rs.getTimestamp("update_time"))
        );
    }

    private String sanitizeError(String error) {
        String sanitized = RiskIpSafety.sanitizeIpLiterals(valueOrEmpty(error));
        sanitized = URI_USER_INFO_PATTERN.matcher(sanitized).replaceAll("$1***@");
        sanitized = JDBC_URL_PATTERN.matcher(sanitized).replaceAll("jdbc:***");
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll("***");
        sanitized = SENSITIVE_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("Bearer ***");
        sanitized = IDENTITY_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = COOKIE_HEADER_PATTERN.matcher(sanitized).replaceAll("$1***");
        return sanitized.length() <= LAST_ERROR_MAX_LENGTH
                ? sanitized
                : sanitized.substring(0, LAST_ERROR_MAX_LENGTH);
    }

    private static Pattern assignmentPattern(String names, boolean allowBearerPrefix) {
        String valuePrefix = allowBearerPrefix ? "(?:bearer\\s+)?" : "";
        return Pattern.compile(
                "(?i)((?<![a-z0-9_])[\\\"']?(?:" + names
                        + ")[\\\"']?\\s*[:=]\\s*[\\\"']?)"
                        + valuePrefix
                        + "[^\\\"'\\s,;}]+"
        );
    }

    private void rejectRawIp(String value, String fieldName) {
        if (RiskIpSafety.containsRawIpLiteral(value)) {
            throw new IllegalArgumentException(fieldName + " must not contain a raw IP literal");
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private void requirePositive(Duration value, String fieldName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
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

    private record ClaimCandidate(String outboxId, int attemptCount) {
    }
}
