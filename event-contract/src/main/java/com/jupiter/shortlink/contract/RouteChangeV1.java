package com.jupiter.shortlink.contract;

import java.io.Serializable;

/** A refresh hint, never an authoritative route payload. */
public record RouteChangeV1(
        String eventId,
        int schemaVersion,
        long occurredAt,
        String tenantId,
        long linkId,
        long routeVersion,
        String domainNorm,
        String shortUri)
        implements Serializable {
    public RouteChangeV1(
            String eventId,
            int schemaVersion,
            long occurredAt,
            String tenantId,
            long linkId,
            long routeVersion) {
        this(eventId, schemaVersion, occurredAt, tenantId, linkId, routeVersion, null, null);
    }
}
