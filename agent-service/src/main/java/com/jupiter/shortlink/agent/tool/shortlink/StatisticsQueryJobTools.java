package com.jupiter.shortlink.agent.tool.shortlink;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Each callback makes one bounded request. PENDING is never reported as completed statistics. */
@Component
public class StatisticsQueryJobTools {
    private static final String PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs";
    private final ShortLinkBusinessGateway gateway;

    public StatisticsQueryJobTools(ShortLinkBusinessGateway gateway) {
        this.gateway = gateway;
    }

    @Tool(
            name = "submit_statistics_query_job",
            description =
                    "Submit a bounded asynchronous statistics query for an owned group, up to 180"
                        + " days. Use for ranges over seven days or TOO_LARGE responses. Returns a"
                        + " jobId and PENDING, not statistics. Do not poll in a loop; use a later"
                        + " user request to check the job.")
    public ToolResult submit(
            @ToolParam(description = "Stable request identifier; reuse exactly on retry.")
                    String requestId,
            @ToolParam(description = "Owned group id.") String gid,
            @ToolParam(description = "Inclusive start date yyyy-MM-dd.") String startDate,
            @ToolParam(description = "Inclusive end date yyyy-MM-dd, at most 180 days.")
                    String endDate,
            @ToolParam(description = "METRICS for daily counts or ACCESS_RECORDS for records.")
                    String queryKind,
            @ToolParam(
                            required = false,
                            description = "Optional specific short-link URL within the group.")
                    String fullShortUrl,
            org.springframework.ai.chat.model.ToolContext trusted) {
        if (requestId == null
                || !requestId.matches("[A-Za-z0-9_-]{1,128}")
                || gid == null
                || gid.isBlank()
                || startDate == null
                || endDate == null
                || queryKind == null
                || !Set.of("METRICS", "ACCESS_RECORDS").contains(queryKind))
            return ToolResult.failure("Invalid statistics job arguments");
        Map<String, Object> request =
                new LinkedHashMap<>(
                        Map.of(
                                "requestId",
                                requestId,
                                "gid",
                                gid,
                                "startDate",
                                startDate,
                                "endDate",
                                endDate,
                                "queryKind",
                                queryKind));
        if (fullShortUrl != null && !fullShortUrl.isBlank())
            request.put("fullShortUrl", fullShortUrl);
        return gateway.post(PATH, ToolContext.fromSpringContext(trusted, request), request);
    }

    @Tool(
            name = "get_statistics_query_job",
            description =
                    "Check a previously submitted job once using its exact jobId. PENDING means"
                        + " work remains incomplete; do not claim counts or repeatedly poll during"
                        + " one turn.")
    public ToolResult status(
            @ToolParam(description = "Exact jobId returned by submission.") String jobId,
            org.springframework.ai.chat.model.ToolContext trusted) {
        if (!validJob(jobId)) return ToolResult.failure("Invalid statistics job reference");
        return gateway.get(
                PATH + "/" + jobId, ToolContext.fromSpringContext(trusted, Map.of()), Map.of());
    }

    @Tool(
            name = "get_statistics_query_job_page",
            description =
                    "Read one page only after a query job is SUCCEEDED. Preserve frozen quality"
                        + " metadata and nextPageIndex; partial pages are never a complete report."
                        + " Access is checked again on every page.")
    public ToolResult page(
            @ToolParam(description = "Exact jobId returned by submission.") String jobId,
            @ToolParam(
                            description =
                                    "Zero-based page index returned by nextPageIndex; start with"
                                        + " 0.")
                    Integer pageIndex,
            @ToolParam(required = false, description = "Page size 1..500, defaults to 500.")
                    Integer size,
            org.springframework.ai.chat.model.ToolContext trusted) {
        int boundedSize = size == null ? 500 : size;
        if (!validJob(jobId)
                || pageIndex == null
                || pageIndex < 0
                || boundedSize < 1
                || boundedSize > 500) return ToolResult.failure("Invalid statistics job page");
        Map<String, Object> query = Map.of("pageIndex", pageIndex, "size", boundedSize);
        return gateway.get(
                PATH + "/" + jobId + "/page", ToolContext.fromSpringContext(trusted, query), query);
    }

    private boolean validJob(String jobId) {
        return jobId != null && jobId.matches("[A-Za-z0-9_-]{1,128}");
    }
}
