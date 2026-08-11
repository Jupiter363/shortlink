package com.nageoffer.shortlink.admin.authority.capability.model;

import java.time.Instant;
import java.util.List;

public record GroupsListResponse(
        String schemaVersion,
        String requestId,
        Snapshot snapshot,
        List<Group> data,
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

    public record Group(
            String gid,
            String name,
            int sortOrder,
            int shortLinkCount
    ) {
    }
}
