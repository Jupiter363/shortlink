package com.nageoffer.shortlink.admin.authority.session.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class JdbcAgentTokenRevocationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentTokenRevocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean createIfAbsent(AgentTokenRevocation revocation) {
        try {
            return jdbcTemplate.update("""
                            insert into t_agent_token_revocation (
                                token_jti,
                                session_id,
                                tenant_id,
                                grant_version,
                                reason,
                                revoked_at,
                                expires_at,
                                create_time
                            ) values (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    revocation.tokenId(),
                    revocation.sessionId(),
                    revocation.tenantId(),
                    revocation.grantVersion(),
                    revocation.reason(),
                    Timestamp.from(revocation.revokedAt()),
                    Timestamp.from(revocation.expiresAt()),
                    Timestamp.from(revocation.revokedAt())
            ) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public boolean isRevoked(String tokenId, Instant now) {
        Integer count = jdbcTemplate.queryForObject("""
                        select count(1)
                        from t_agent_token_revocation
                        where token_jti = ?
                          and expires_at > ?
                        """,
                Integer.class,
                tokenId,
                Timestamp.from(now)
        );
        return count != null && count > 0;
    }
}
