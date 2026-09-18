package com.jupiter.shortlink.analytics.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Milliseconds since epoch; interval is [startInclusive,endExclusive). */
public record QueryRequest(
        String tenantId,
        String subjectId,
        long authVersion,
        String gid,
        List<Long> linkIds,
        Long startInclusive,
        Long endExclusive,
        List<String> windows,
        String endPolicy,
        String snapshotId,
        String cursor,
        Integer pageSize,
        String queryKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> dimensions,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<DimensionFilter> filters) {
    public QueryRequest(String tenantId, String subjectId, long authVersion, String gid,
            List<Long> linkIds, Long startInclusive, Long endExclusive, List<String> windows,
            String endPolicy, String snapshotId, String cursor, Integer pageSize, String queryKind) {
        this(tenantId, subjectId, authVersion, gid, linkIds, startInclusive, endExclusive,
                windows, endPolicy, snapshotId, cursor, pageSize, queryKind, null, null);
    }

    public int boundedPageSize() {
        int n = pageSize == null ? 100 : pageSize;
        if (n < 1 || n > 500) throw new QueryFailure("TOO_LARGE", "pageSize must be 1..500");
        return n;
    }

    public String kind() {
        return queryKind == null ? "METRICS" : queryKind;
    }
}
