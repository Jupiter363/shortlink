package com.nageoffer.shortlink.admin.authority.session.outbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class JdbcAgentAuthorityOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentAuthorityOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean createIfAbsent(AgentAuthorityOutboxEvent event) {
        try {
            return jdbcTemplate.update("""
                            insert into t_agent_authority_outbox (
                                event_id,
                                event_type,
                                aggregate_type,
                                aggregate_id,
                                aggregate_version,
                                tenant_id,
                                payload_json,
                                status,
                                attempt_count,
                                next_attempt_at,
                                owner_token,
                                lease_until,
                                published_at,
                                last_error,
                                occurred_at,
                                create_time,
                                update_time
                            ) values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, '', null, null, '', ?, ?, ?)
                            """,
                    event.eventId(),
                    event.eventType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.aggregateVersion(),
                    event.tenantId(),
                    event.payloadJson(),
                    Timestamp.from(event.occurredAt()),
                    Timestamp.from(event.occurredAt()),
                    Timestamp.from(event.occurredAt()),
                    Timestamp.from(event.occurredAt())
            ) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }
}
