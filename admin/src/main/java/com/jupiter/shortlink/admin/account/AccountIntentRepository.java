package com.jupiter.shortlink.admin.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/** Uses the same ds_0 and Spring transaction as t_user; it is not a Redis outbox. */
@Repository
public class AccountIntentRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<AccountInitialization> INITIALIZATION =
            (rs, row) ->
                    new AccountInitialization(
                            rs.getLong("tenant_id"),
                            rs.getString("username"),
                            rs.getString("command_id"),
                            rs.getString("state"),
                            rs.getString("group_id"),
                            rs.getInt("attempts"),
                            rs.getLong("next_attempt_at"),
                            rs.getLong("claim_version"),
                            rs.getString("last_error"));

    public AccountIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Single physical registry guarantees tenant identity across every t_user shard and Admin
     * instance.
     */
    public long reserveIdentity(String username, long now) {
        GeneratedKeyHolder generated = new GeneratedKeyHolder();
        int inserted =
                jdbc.update(
                        connection -> {
                            var statement =
                                    connection.prepareStatement(
                                            "INSERT INTO t_account_identity (username, created_at)"
                                                + " VALUES (?, ?)",
                                            Statement.RETURN_GENERATED_KEYS);
                            statement.setString(1, username);
                            statement.setLong(2, now);
                            return statement;
                        },
                        generated);
        Number id = generated.getKey();
        if (inserted != 1 || id == null || id.longValue() < 1)
            throw new IllegalStateException("Global account identity allocation failed");
        return id.longValue();
    }

    public void createInitialization(long tenantId, String username, long now) {
        jdbc.update(
                """
INSERT INTO t_account_initialization
(tenant_id, username, command_id, state, attempts, next_attempt_at, lease_until, claim_version, created_at, updated_at)
VALUES (?, ?, ?, 'PENDING', 0, ?, 0, 0, ?, ?)
""",
                tenantId,
                username,
                "account-default-group:" + tenantId,
                now,
                now,
                now);
    }

    public Optional<AccountInitialization> findInitialization(long tenantId) {
        return jdbc
                .query(
                        "SELECT * FROM t_account_initialization WHERE tenant_id = ?",
                        INITIALIZATION,
                        tenantId)
                .stream()
                .findFirst();
    }

    public List<AccountInitialization> findDue(long now, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid scan budget");
        return jdbc.query(
                """
                SELECT * FROM t_account_initialization
                WHERE state IN ('PENDING', 'FAILED') AND next_attempt_at <= ? AND lease_until <= ?
                ORDER BY next_attempt_at, tenant_id LIMIT ?
                """,
                INITIALIZATION,
                now,
                now,
                limit);
    }

    public boolean claim(AccountInitialization item, long now, long leaseUntil) {
        return jdbc.update(
                        """
UPDATE t_account_initialization
SET lease_until = ?, claim_version = claim_version + 1, attempts = attempts + 1, updated_at = ?
WHERE tenant_id = ? AND claim_version = ? AND state IN ('PENDING', 'FAILED')
  AND next_attempt_at <= ? AND lease_until <= ?
""",
                        leaseUntil,
                        now,
                        item.tenantId(),
                        item.claimVersion(),
                        now,
                        now)
                == 1;
    }

    public boolean complete(AccountInitialization item, String gid, long now) {
        return jdbc.update(
                        """
UPDATE t_account_initialization SET state = 'READY', group_id = ?, last_error = NULL, lease_until = 0, updated_at = ?
WHERE tenant_id = ? AND claim_version = ? AND state IN ('PENDING', 'FAILED') AND lease_until > ?
""",
                        gid,
                        now,
                        item.tenantId(),
                        item.claimVersion() + 1,
                        now)
                == 1;
    }

    public void failed(AccountInitialization item, String error, long nextAttemptAt, long now) {
        jdbc.update(
                """
UPDATE t_account_initialization SET state = 'FAILED', last_error = ?, next_attempt_at = ?, lease_until = 0, updated_at = ?
WHERE tenant_id = ? AND claim_version = ? AND state IN ('PENDING', 'FAILED') AND lease_until > ?
""",
                error,
                nextAttemptAt,
                now,
                item.tenantId(),
                item.claimVersion() + 1,
                now);
    }

    public void requestRetry(long tenantId, long now) {
        jdbc.update(
                """
                UPDATE t_account_initialization SET next_attempt_at = ?, updated_at = ?
                WHERE tenant_id = ? AND state IN ('PENDING', 'FAILED') AND lease_until <= ?
                """,
                now,
                now,
                tenantId,
                now);
    }

    public void createSessionCleanup(long tenantId, String username, long authVersion, long now) {
        jdbc.update(
                "INSERT INTO t_session_cleanup_intent (tenant_id, auth_version, username,"
                    + " created_at) VALUES (?, ?, ?, ?)",
                tenantId,
                authVersion,
                username,
                now);
    }

    public List<SessionCleanup> findCleanup(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid cleanup budget");
        return jdbc.query(
                "SELECT tenant_id, auth_version, username FROM t_session_cleanup_intent ORDER BY"
                    + " created_at LIMIT ?",
                (rs, row) -> new SessionCleanup(rs.getLong(1), rs.getLong(2), rs.getString(3)),
                limit);
    }

    public void completeCleanup(SessionCleanup item) {
        jdbc.update(
                "DELETE FROM t_session_cleanup_intent WHERE tenant_id = ? AND auth_version = ?",
                item.tenantId(),
                item.authVersion());
    }

    public record SessionCleanup(long tenantId, long authVersion, String username) {}
}
