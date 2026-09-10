package com.jupiter.shortlink.contract;

import java.io.Serializable;

/** A refresh hint. Authoritative policy must be read from Command. */
public record RiskPolicyChangeV1(
        String eventId,
        int schemaVersion,
        long occurredAt,
        String tenantId,
        String resourceKey,
        String action,
        long policyRevision,
        Long nextTransitionAt)
        implements Serializable {}
