package com.jupiter.shortlink.admin.dto.req.analytics;

import java.util.List;

/** Wire DTO owned by the Admin boundary, never accepted directly as a user's trusted scope. */
public record AnalyticsQueryRequest(
        String tenantId,
        String subjectId,
        long authVersion,
        String gid,
        List<Long> linkIds,
        long startInclusive,
        long endExclusive,
        List<String> windows,
        String endPolicy,
        String snapshotId,
        String cursor,
        int pageSize,
        String queryKind) {}
