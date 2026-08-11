package com.nageoffer.shortlink.admin.authority.capability.model;

import java.time.Instant;
import java.util.List;

public record GroupStatsQueryResponse(
        String schemaVersion,
        String requestId,
        Snapshot snapshot,
        Data data,
        List<String> warnings
) {

    public record Snapshot(
            String snapshotId,
            String source,
            Instant observedAt,
            Instant expiresAt,
            String contentHash
    ) {
    }

    public record Data(
            String gid,
            int pv,
            int uv,
            int uip
    ) {
    }
}
