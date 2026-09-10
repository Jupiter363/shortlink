package com.jupiter.shortlink.admin.controller;

import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;
import com.jupiter.shortlink.admin.remote.analytics.AgentAnalyticsFacade;
import com.jupiter.shortlink.admin.remote.dto.req.*;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/**
 * Interactive dashboards and Agent tools share the same currently authorized analytics snapshots.
 */
@RestController(value = "shortLinkStatsControllerByAdmin")
@RequiredArgsConstructor
public class ShortLinkStatsController {
    private final AgentAnalyticsFacade analytics;

    @GetMapping("/api/short-link/admin/v1/stats")
    public Result<StatsEnvelope> shortLinkStats(ShortLinkStatsReqDTO q) {
        return Results.success(
                analytics.query(
                        q.getGid(),
                        q.getFullShortUrl(),
                        q.getStartDate(),
                        q.getEndDate(),
                        null,
                        null,
                        null,
                        500,
                        "METRICS"));
    }

    @GetMapping("/api/short-link/admin/v1/stats/group")
    public Result<StatsEnvelope> groupShortLinkStats(ShortLinkGroupStatsReqDTO q) {
        return Results.success(
                analytics.query(
                        q.getGid(),
                        null,
                        q.getStartDate(),
                        q.getEndDate(),
                        null,
                        null,
                        null,
                        500,
                        "METRICS"));
    }

    @GetMapping("/api/short-link/admin/v1/stats/access-record")
    public Result<StatsEnvelope> shortLinkStatsAccessRecord(
            ShortLinkStatsAccessRecordReqDTO q,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String cursor) {
        requireCursor(q.getCurrent(), snapshotId, cursor);
        return Results.success(
                analytics.query(
                        q.getGid(),
                        q.getFullShortUrl(),
                        q.getStartDate(),
                        q.getEndDate(),
                        null,
                        snapshotId,
                        cursor,
                        Math.toIntExact(q.getSize()),
                        "ACCESS_RECORDS"));
    }

    @GetMapping("/api/short-link/admin/v1/stats/access-record/group")
    public Result<StatsEnvelope> groupShortLinkStatsAccessRecord(
            ShortLinkGroupStatsAccessRecordReqDTO q,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String cursor) {
        requireCursor(q.getCurrent(), snapshotId, cursor);
        return Results.success(
                analytics.query(
                        q.getGid(),
                        null,
                        q.getStartDate(),
                        q.getEndDate(),
                        null,
                        snapshotId,
                        cursor,
                        Math.toIntExact(q.getSize()),
                        "ACCESS_RECORDS"));
    }

    private static void requireCursor(long current, String snapshot, String cursor) {
        if (current < 1 || current > 1 && (snapshot == null || cursor == null))
            throw new ClientException("Continuation requires a fixed snapshotId and cursor");
    }
}
