package com.jupiter.shortlink.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.dao.entity.GroupDO;
import com.jupiter.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;
import com.jupiter.shortlink.admin.remote.CommandRiskRemoteService;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.remote.analytics.AgentAnalyticsFacade;
import com.jupiter.shortlink.admin.remote.analytics.AnalyticsJsonClient;
import com.jupiter.shortlink.admin.remote.dto.req.*;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.jupiter.shortlink.admin.service.GroupService;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.jupiter.shortlink.contract.FrozenQueryScope;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Agent tools and scheduled profiles both reuse the authorized Analytics API. */
@RestController
public class AgentToolInternalController {
    private final GroupService groupService;
    private final ShortLinkActualRemoteService shortLinkActualRemoteService;
    private final AgentAnalyticsFacade analytics;
    private CommandRiskRemoteService policies;
    @Autowired private com.jupiter.shortlink.admin.remote.analytics.LinkPageAnalyticsService pages;
    @Autowired private com.jupiter.shortlink.admin.remote.analytics.ScheduledRiskProfileScopeService scheduledScopes;

    public AgentToolInternalController(
            GroupService groupService,
            ShortLinkActualRemoteService shortLinkActualRemoteService,
            AgentAnalyticsFacade analytics) {
        this.groupService = groupService;
        this.shortLinkActualRemoteService = shortLinkActualRemoteService;
        this.analytics = analytics;
    }

    @Autowired
    public AgentToolInternalController(
            GroupService groups,
            ShortLinkActualRemoteService links,
            AgentAnalyticsFacade analytics,
            CommandRiskRemoteService policies) {
        this(groups, links, analytics);
        this.policies = policies;
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/policies/current")
    public Result<List<CommandRiskRemoteService.Snapshot>> currentPolicies(
            @RequestBody CommandRiskRemoteService.Current request) {
        requirePrincipal();
        return Results.success(policies.current(request));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/policies/commands/{commandId}")
    public Result<CommandRiskRemoteService.Receipt> policyResult(@PathVariable String commandId) {
        requirePrincipal();
        try {
            return Results.success(policies.result(commandId));
        } catch (feign.FeignException.NotFound absent) {
            return Results.success(null);
        }
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/policies/activate")
    public Result<CommandRiskRemoteService.Receipt> activatePolicy(
            @RequestBody Map<String, Object> request) {
        requirePrincipal();
        return Results.success(policies.activate(request));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/policies/revoke")
    public Result<CommandRiskRemoteService.Receipt> revokePolicy(
            @RequestBody CommandRiskRemoteService.Revoke request) {
        requirePrincipal();
        return Results.success(policies.revoke(request));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/groups")
    public Result<List<ShortLinkGroupRespDTO>> listGroups() {
        requirePrincipal();
        return Results.success(groupService.listGroup());
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/short-links/page")
    public Result<Page<ShortLinkPageRespDTO>> pageShortLinks(ShortLinkPageReqDTO request) {
        requireOwnedGid(request.getGid());
        if (request.getCurrent() < 1 || request.getSize() < 1 || request.getSize() > 500)
            throw new ClientException("Invalid link page budget");
        return pages == null
                ? shortLinkActualRemoteService.pageShortLink(
                        request.getGid(),
                        request.getOrderTag(),
                        request.getCurrent(),
                        request.getSize())
                : pages.page(request);
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/short-link/stats")
    public Result<StatsEnvelope> shortLinkStats(ShortLinkStatsReqDTO request) {
        requireOwnedGid(request.getGid());
        return Results.success(
                analytics.query(
                        request.getGid(),
                        request.getFullShortUrl(),
                        request.getStartDate(),
                        request.getEndDate(),
                        null,
                        null,
                        null,
                        500,
                        "METRICS"));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/group/stats")
    public Result<StatsEnvelope> groupStats(ShortLinkGroupStatsReqDTO request) {
        requireOwnedGid(request.getGid());
        return Results.success(
                analytics.query(
                        request.getGid(),
                        null,
                        request.getStartDate(),
                        request.getEndDate(),
                        null,
                        null,
                        null,
                        500,
                        "METRICS"));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/group/access-records")
    public Result<StatsEnvelope> groupAccessRecords(
            ShortLinkGroupStatsAccessRecordReqDTO request,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String cursor) {
        requireOwnedGid(request.getGid());
        if (request.getCurrent() > 1 && (snapshotId == null || cursor == null))
            throw new ClientException("Continuation requires snapshotId and cursor");
        return Results.success(
                analytics.query(
                        request.getGid(),
                        null,
                        request.getStartDate(),
                        request.getEndDate(),
                        null,
                        snapshotId,
                        cursor,
                        Math.toIntExact(request.getSize()),
                        "ACCESS_RECORDS"));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/statistics/link-metrics")
    public Result<StatsEnvelope> linkMetrics(
            @RequestParam String gid,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "500") int pageSize) {
        requireOwnedGid(gid);
        return Results.success(analytics.query(gid, null, startDate, endDate, null,
                snapshotId, cursor, pageSize, "LINK_METRICS"));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/statistics/dimensions")
    public Result<StatsEnvelope> dimensionBreakdown(@RequestBody DimensionRequest request) {
        requireOwnedGid(request.gid());
        return Results.success(analytics.query(request.gid(), request.fullShortUrl(), request.startDate(),
                request.endDate(), null, request.snapshotId(), request.cursor(),
                request.pageSize() == null ? 500 : request.pageSize(), "DIMENSION_BREAKDOWN", null,
                request.dimensions(), request.filters()));
    }

    public record DimensionRequest(String gid, String fullShortUrl, String startDate, String endDate,
            List<String> dimensions, List<Map<String, Object>> filters, String snapshotId,
            String cursor, Integer pageSize) {}

    @GetMapping("/internal/short-link-admin/v1/agent-tools/risk/active-short-links")
    public Result<StatsEnvelope> riskActiveShortLinks(
            @RequestParam String since,
            @RequestParam String endTime,
            @RequestParam(required = false) String gid,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "500") int pageSize) {
        requirePrincipal();
        if (gid != null) requireOwnedGid(gid);
        return Results.success(
                analytics.query(
                        gid,
                        null,
                        since,
                        endTime,
                        null,
                        snapshotId,
                        cursor,
                        pageSize,
                        "ACTIVE_LINKS"));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats")
    public Result<StatsEnvelope> riskShortLinkWindowStats(
            @RequestParam String gid,
            @RequestParam String fullShortUrl,
            @RequestParam String startTime,
            @RequestParam String endTime) {
        requireOwnedGid(gid);
        return Results.success(
                analytics.query(
                        gid, fullShortUrl, startTime, endTime, null, null, null, 500, "METRICS"));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/risk/short-link-windows")
    public Result<StatsEnvelope> riskShortLinkWindows(
            @RequestParam String gid,
            @RequestParam String fullShortUrl,
            @RequestParam String endTime) {
        requireOwnedGid(gid);
        return Results.success(
                analytics.query(
                        gid,
                        fullShortUrl,
                        null,
                        endTime,
                        List.of("2h", "24h", "7d"),
                        null,
                        null,
                        500,
                        "METRICS"));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/authorization/resolve")
    public Result<Map<String, Object>> resolve(@RequestBody ResolveRequest request) {
        requirePrincipal();
        if (request.gid() != null) requireOwnedGid(request.gid());
        return Results.success(
                analytics.resolve(
                        request.gid(),
                        request.fullShortUrl(),
                        request.linkIds(),
                        request.afterLinkId(),
                        request.ownershipVersion()));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/risk/active-link-query")
    public Result<StatsEnvelope> activeLinkQuery(@RequestBody ActiveLinkQuery request) {
        requirePrincipal();
        if (request.linkIds() == null
                || request.linkIds().isEmpty()
                || request.linkIds().size() > 500)
            throw new ClientException("Active candidate scope must contain 1..500 links");
        return Results.success(
                analytics.query(
                        null,
                        null,
                        request.since(),
                        request.endTime(),
                        null,
                        request.snapshotId(),
                        request.cursor(),
                        500,
                        "ACTIVE_LINKS",
                        request.linkIds()));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/risk/scheduled-scopes")
    public Result<com.jupiter.shortlink.admin.remote.analytics.ScheduledRiskProfileScopeService.ScopePage> scheduledScopes(
            @RequestHeader(value = "X-Agent-Principal-Mode", required = false) String mode,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int pageSize) {
        requirePrincipal();
        return Results.success(scheduledScopes.discover(mode, cursor, pageSize));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/risk/scheduled-scope")
    public Result<com.jupiter.shortlink.admin.remote.analytics.ScheduledRiskProfileScopeService.TenantScope> scheduledScope(
            @RequestHeader(value = "X-Agent-Principal-Mode", required = false) String mode,
            @RequestParam String tenantId, @RequestParam String gid) {
        requirePrincipal();
        return Results.success(scheduledScopes.resolve(mode, tenantId, gid));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/statistics/jobs")
    public Result<Map<String, Object>> submitStatisticsJob(
            @RequestBody StatisticsJobRequest request) {
        requireOwnedGid(request.gid());
        return Results.success(
                analytics.submitJob(
                        request.requestId(),
                        request.gid(),
                        request.fullShortUrl(),
                        request.startDate(),
                        request.endDate(),
                        request.queryKind(), request.dimensions(), request.filters()));
    }

    /** Backend recovery only: an unknown submission must never become a fresh job here. */
    @PostMapping("/internal/short-link-admin/v1/agent-tools/statistics/jobs/recover-existing")
    public Result<Map<String, Object>> recoverStatisticsJob(
            @RequestBody StatisticsJobRequest request) {
        requireOwnedGid(request.gid());
        return Results.success(analytics.recoverExistingJob(request.requestId(), request.gid(),
                request.fullShortUrl(), request.startDate(), request.endDate(), request.queryKind(),
                request.dimensions(), request.filters()));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/statistics/authorize-scope")
    public Result<Map<String, Object>> authorizeStatisticsScope(@RequestBody SelectedScopeRequest request) {
        return Results.success(analytics.authorizeSelectedScope(request.gid(), request.linkIds(),
                request.ownershipVersion()));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/statistics/frozen-jobs")
    public Result<Map<String, Object>> submitFrozenStatisticsJob(@RequestBody FrozenStatisticsJobRequest request) {
        return Results.success(analytics.submitFrozenJob(request.requestId(), request.gid(), null,
                request.startDate(), request.endDate(), request.queryKind(), request.dimensions(), request.filters(), request.scope()));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/statistics/frozen-jobs/recover-existing")
    public Result<Map<String, Object>> recoverFrozenStatisticsJob(@RequestBody FrozenStatisticsJobRequest request) {
        return Results.success(analytics.recoverFrozenJob(request.requestId(), request.gid(), null,
                request.startDate(), request.endDate(), request.queryKind(), request.dimensions(), request.filters(), request.scope()));
    }

    @PostMapping("/internal/short-link-admin/v1/agent-tools/statistics/frozen-jobs/{jobId}/release-result")
    public Result<Map<String, Object>> releaseFrozenStatisticsResult(@PathVariable String jobId,
            @RequestBody FrozenStatisticsJobRequest request) {
        return Results.success(analytics.releaseFrozenJobResult(jobId, request.requestId(), request.gid(), null,
                request.startDate(), request.endDate(), request.queryKind(), request.dimensions(), request.filters(), request.scope()));
    }

    @ExceptionHandler(AnalyticsJsonClient.StatisticsCapacityException.class)
    public Map<String, Object> statisticsCapacity(AnalyticsJsonClient.StatisticsCapacityException exhausted) {
        return Map.of("code", "QUERY_CAPACITY_EXHAUSTED", "message", "Statistics query capacity exhausted",
                "admitted", false, "capacityKind", exhausted.capacityKind());
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/statistics/jobs/{jobId}")
    public Result<Map<String, Object>> statisticsJobStatus(@PathVariable String jobId) {
        requirePrincipal();
        return Results.success(analytics.jobStatus(jobId));
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/statistics/jobs/{jobId}/page")
    public Result<Map<String, Object>> statisticsJobPage(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "500") int size) {
        requirePrincipal();
        return Results.success(analytics.jobPage(jobId, pageIndex, size));
    }

    public record StatisticsJobRequest(
            String requestId,
            String gid,
            String fullShortUrl,
            String startDate,
            String endDate,
            String queryKind, List<String> dimensions, List<Map<String, Object>> filters) {
        public StatisticsJobRequest(String requestId, String gid, String fullShortUrl,
                String startDate, String endDate, String queryKind) {
            this(requestId, gid, fullShortUrl, startDate, endDate, queryKind, null, null);
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unexpected statistics job field");
        }
    }

    public record FrozenStatisticsJobRequest(String requestId, String gid,
            String startDate, String endDate, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters, FrozenQueryScope scope) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static FrozenStatisticsJobRequest from(Map<String, Object> values) {
            closed(values, Set.of("requestId", "gid", "startDate", "endDate", "queryKind",
                    "dimensions", "filters", "scope"));
            Object rawScope = values.get("scope");
            if (rawScope != null && !(rawScope instanceof Map<?, ?>)) throw new IllegalArgumentException("Invalid frozen scope");
            return new FrozenStatisticsJobRequest(text(values.get("requestId")), text(values.get("gid")),
                    text(values.get("startDate")), text(values.get("endDate")),
                    text(values.get("queryKind")), strings(values.get("dimensions")), filterObjects(values.get("filters")),
                    rawScope == null ? null : FrozenQueryScope.fromMap((Map<?, ?>) rawScope));
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unexpected frozen statistics job field");
        }
    }

    public record SelectedScopeRequest(String gid, List<Long> linkIds, String ownershipVersion) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SelectedScopeRequest from(Map<String, Object> values) {
            closed(values, Set.of("gid", "linkIds", "ownershipVersion"));
            if (!(values.get("linkIds") instanceof List<?> ids)) throw new IllegalArgumentException("Explicit members required");
            List<Long> members = ids.stream().map(value -> {
                if (!(value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger))
                    throw new IllegalArgumentException("Member ids must be integers");
                try { return new java.math.BigInteger(value.toString()).longValueExact(); }
                catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid member id"); }
            }).toList();
            return new SelectedScopeRequest(text(values.get("gid")), FrozenQueryScope.validatedMembers(members),
                    text(values.get("ownershipVersion")));
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unexpected selected scope field");
        }
    }

    private static void closed(Map<String, Object> values, Set<String> fields) {
        if (values == null || !fields.containsAll(values.keySet())) throw new IllegalArgumentException("Unexpected field");
    }

    private static String text(Object value) {
        if (value != null && !(value instanceof String)) throw new IllegalArgumentException("Text required");
        return (String) value;
    }

    private static List<String> strings(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Array required");
        return values.stream().map(item -> {
            if (!(item instanceof String text)) throw new IllegalArgumentException("Text required");
            return text;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filterObjects(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Array required");
        return values.stream().map(item -> {
            if (!(item instanceof Map<?, ?> filter) || !Set.of("dimension", "operator", "values").containsAll(filter.keySet()))
                throw new IllegalArgumentException("Invalid filter fields");
            return (Map<String, Object>) filter;
        }).toList();
    }

    private void requireOwnedGid(String gid) {
        requirePrincipal();
        if (gid == null
                || gid.isBlank()
                || groupService.count(
                                Wrappers.lambdaQuery(GroupDO.class)
                                        .eq(GroupDO::getUsername, UserContext.getUsername())
                                        .eq(GroupDO::getGid, gid)
                                        .eq(GroupDO::getDelFlag, 0))
                        != 1) {
            throw new ClientException("Agent request requires an owned active group");
        }
    }

    private void requirePrincipal() {
        if (UserContext.getUserId() == null
                || UserContext.getUsername() == null
                || UserContext.getAuthVersion() == null)
            throw new ClientException("Agent request requires a current trusted principal");
    }

    public record ResolveRequest(
            String gid,
            String fullShortUrl,
            List<Long> linkIds,
            Long afterLinkId,
            String ownershipVersion) {
        public ResolveRequest(String gid, String fullShortUrl, List<Long> linkIds) {
            this(gid, fullShortUrl, linkIds, null, null);
        }
    }

    public record ActiveLinkQuery(
            List<Long> linkIds, String since, String endTime, String snapshotId, String cursor) {}
}
