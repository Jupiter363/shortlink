package com.jupiter.shortlink.admin.dto.req.analytics;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jupiter.shortlink.contract.FrozenQueryScope;

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
        String queryKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> dimensions,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Map<String, Object>> filters,
        @JsonInclude(JsonInclude.Include.NON_NULL) FrozenQueryScope scope) {
    public AnalyticsQueryRequest(String tenantId, String subjectId, long authVersion, String gid,
            List<Long> linkIds, long startInclusive, long endExclusive, List<String> windows,
            String endPolicy, String snapshotId, String cursor, int pageSize, String queryKind,
            List<String> dimensions, List<Map<String, Object>> filters) {
        this(tenantId, subjectId, authVersion, gid, linkIds, startInclusive, endExclusive, windows,
                endPolicy, snapshotId, cursor, pageSize, queryKind, dimensions, filters, null);
    }
    public AnalyticsQueryRequest(String tenantId, String subjectId, long authVersion, String gid,
            List<Long> linkIds, long startInclusive, long endExclusive, List<String> windows,
            String endPolicy, String snapshotId, String cursor, int pageSize, String queryKind) {
        this(tenantId, subjectId, authVersion, gid, linkIds, startInclusive, endExclusive, windows,
                endPolicy, snapshotId, cursor, pageSize, queryKind, null, null);
    }
}
