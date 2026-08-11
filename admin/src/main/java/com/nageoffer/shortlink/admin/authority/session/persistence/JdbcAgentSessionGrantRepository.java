package com.nageoffer.shortlink.admin.authority.session.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrant;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Repository
public class JdbcAgentSessionGrantRepository {

    private static final String SELECT_COLUMNS = """
            id,
            session_id,
            tenant_id,
            owner_user_id,
            owner_username,
            agent_type,
            scopes_json,
            status,
            grant_version,
            latest_jti,
            latest_token_expires_at,
            expires_at,
            create_time,
            update_time
            """;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    public JdbcAgentSessionGrantRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean create(AgentSessionGrant grant) {
        try {
            return jdbcTemplate.update("""
                            insert into t_agent_session_grant (
                                session_id,
                                tenant_id,
                                owner_user_id,
                                owner_username,
                                agent_type,
                                scopes_json,
                                status,
                                grant_version,
                                latest_jti,
                                latest_token_expires_at,
                                expires_at,
                                create_time,
                                update_time
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    grant.sessionId(),
                    grant.tenantId(),
                    grant.ownerUserId(),
                    grant.ownerUsername(),
                    grant.agentType(),
                    writeScopes(grant.scopes()),
                    grant.status().name(),
                    grant.grantVersion(),
                    grant.latestTokenId(),
                    timestamp(grant.latestTokenExpiresAt()),
                    timestamp(grant.expiresAt()),
                    timestamp(grant.createdAt()),
                    timestamp(grant.updatedAt())
            ) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public Optional<AgentSessionGrant> findBySessionId(String sessionId) {
        return queryOne("""
                        select %s
                        from t_agent_session_grant
                        where session_id = ?
                        limit 1
                        """.formatted(SELECT_COLUMNS), sessionId);
    }

    public Optional<AgentSessionGrant> findBySessionIdForUpdate(String sessionId) {
        return queryOne("""
                        select %s
                        from t_agent_session_grant
                        where session_id = ?
                        limit 1 for update
                        """.formatted(SELECT_COLUMNS), sessionId);
    }

    public boolean rotateLatestToken(
            String sessionId,
            long expectedGrantVersion,
            String expectedLatestTokenId,
            String nextTokenId,
            Instant nextTokenExpiresAt,
            Instant now
    ) {
        return jdbcTemplate.update("""
                        update t_agent_session_grant
                        set grant_version = grant_version + 1,
                            latest_jti = ?,
                            latest_token_expires_at = ?,
                            update_time = ?
                        where session_id = ?
                          and status = ?
                          and grant_version = ?
                          and latest_jti = ?
                          and expires_at > ?
                        """,
                nextTokenId,
                timestamp(nextTokenExpiresAt),
                timestamp(now),
                sessionId,
                AgentSessionGrantStatus.ACTIVE.name(),
                expectedGrantVersion,
                expectedLatestTokenId,
                timestamp(now)
        ) == 1;
    }

    public boolean revoke(String sessionId, long expectedGrantVersion, Instant now) {
        return jdbcTemplate.update("""
                        update t_agent_session_grant
                        set status = ?,
                            grant_version = grant_version + 1,
                            latest_jti = '',
                            latest_token_expires_at = null,
                            update_time = ?
                        where session_id = ?
                          and status = ?
                          and grant_version = ?
                        """,
                AgentSessionGrantStatus.REVOKED.name(),
                timestamp(now),
                sessionId,
                AgentSessionGrantStatus.ACTIVE.name(),
                expectedGrantVersion
        ) == 1;
    }

    private Optional<AgentSessionGrant> queryOne(String sql, Object... args) {
        List<AgentSessionGrant> grants = jdbcTemplate.query(sql, this::mapGrant, args);
        return grants.stream().findFirst();
    }

    private AgentSessionGrant mapGrant(ResultSet rs, int rowNum) throws SQLException {
        return new AgentSessionGrant(
                rs.getObject("id", Long.class),
                rs.getString("session_id"),
                rs.getString("tenant_id"),
                rs.getString("owner_user_id"),
                rs.getString("owner_username"),
                rs.getString("agent_type"),
                readScopes(rs.getString("scopes_json")),
                AgentSessionGrantStatus.valueOf(rs.getString("status")),
                rs.getLong("grant_version"),
                rs.getString("latest_jti"),
                instant(rs.getTimestamp("latest_token_expires_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("create_time")),
                instant(rs.getTimestamp("update_time"))
        );
    }

    private String writeScopes(Set<String> scopes) {
        try {
            return objectMapper.writeValueAsString(new TreeSet<>(scopes));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent session grant scopes are not serializable.", exception);
        }
    }

    private Set<String> readScopes(String value) {
        try {
            return Set.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored agent session grant scopes are invalid.", exception);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
