package com.nageoffer.shortlink.admin.authority.session.outbox;

import java.time.Instant;

public record AgentAuthorityOutboxEvent(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        String tenantId,
        String payloadJson,
        Instant occurredAt
) {
}
