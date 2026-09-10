package com.jupiter.shortlink.analytics.api;

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
        String queryKind) {
    public int boundedPageSize() {
        int n = pageSize == null ? 100 : pageSize;
        if (n < 1 || n > 500) throw new QueryFailure("TOO_LARGE", "pageSize must be 1..500");
        return n;
    }

    public String kind() {
        return queryKind == null ? "METRICS" : queryKind;
    }
}
