package com.jupiter.shortlink.contract;

import java.io.Serializable;

/**
 * Immutable visit identity issued with EventIdentity.bind. Retries preserve every field. UTC
 * milliseconds.
 */
public record ClickEventV1(
        String eventId,
        int schemaVersion,
        long occurredAt,
        String producerInstanceId,
        String tenantId,
        long linkId,
        String gidAtEvent,
        long ownershipVersion,
        String domainNorm,
        String shortUri,
        long routeVersion,
        String uvId,
        String clientIp,
        String userAgent,
        String referer,
        String requestId,
        String traceId,
        int bucketVersion)
        implements Serializable {}
