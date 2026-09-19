package com.jupiter.shortlink.analytics.api;

import java.util.Objects;

/** Checks the selected members at every authorization boundary, including scripted/local adapters. */
public final class FrozenScopeValidation {
    private FrozenScopeValidation() {}

    public static void request(QueryRequest query) {
        if (query == null || query.scope() == null || query.gid() == null || query.gid().isBlank()
                || !query.scope().linkIds().equals(query.linkIds())) {
            throw new QueryFailure("INVALID_QUERY", "Frozen scope must match the exact query members");
        }
    }

    public static void authorized(QueryRequest query, AuthorizationClient.Scope authorized) {
        request(query);
        if (authorized == null || !Objects.equals(query.tenantId(), authorized.tenantId())
                || !query.scope().linkIds().equals(authorized.linkIds())) {
            throw new QueryFailure("QUERY_SCOPE_CHANGED", "Authorized frozen members changed");
        }
        if (authorized.ownershipVersion() == null || !authorized.ownershipVersion().matches("[a-f0-9]{64}")) {
            throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization revision is invalid");
        }
    }
}
