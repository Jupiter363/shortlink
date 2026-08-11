package com.nageoffer.shortlink.admin.authority.capability.model;

import java.time.OffsetDateTime;

public record GroupStatsQueryRequest(
        String gid,
        TimeRange timeRange
) {

    public record TimeRange(
            OffsetDateTime start,
            OffsetDateTime end,
            String timezone
    ) {
    }
}
