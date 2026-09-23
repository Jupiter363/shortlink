package com.jupiter.shortlink.agent.harness.api;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportView;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignReportDeliveryService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignReportDeliveryService.*;
import com.jupiter.shortlink.agent.common.result.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Read-only transport: owner, capabilities and report payloads are never accepted from clients. */
@RestController
@Profile("campaign-plan-v2")
@RequestMapping("/internal/short-link-agent/v1/campaign")
public final class CampaignReportController {
    private final CampaignReportDeliveryService reports;
    public CampaignReportController(CampaignReportDeliveryService reports) { this.reports = reports; }

    @GetMapping("/reports/{reportId}/revisions/{revision}")
    public Result<CampaignReportView> read(@PathVariable String reportId, @PathVariable int revision,
            @RequestParam String sessionId, @RequestParam String runId, @RequestParam String planId, @RequestParam int planRevision,
            @RequestHeader("X-Agent-Username") String username, @RequestHeader("X-Agent-UserId") String tenant,
            @RequestHeader("X-Agent-Auth-Version") long authVersion) {
        return Result.success(reports.read(caller(tenant, username, authVersion),
                new Reference(sessionId, runId, planId, planRevision), new ReportRef(reportId, revision)));
    }
    @GetMapping("/reports/{reportId}/revisions/{revision}/blocks/{blockId}/rows")
    public Result<RowsPage> rows(@PathVariable String reportId, @PathVariable int revision, @PathVariable String blockId,
            @RequestParam String sessionId, @RequestParam String runId, @RequestParam String planId, @RequestParam int planRevision,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "25") int size,
            @RequestHeader("X-Agent-Username") String username, @RequestHeader("X-Agent-UserId") String tenant,
            @RequestHeader("X-Agent-Auth-Version") long authVersion) {
        return Result.success(reports.rows(caller(tenant, username, authVersion),
                new Reference(sessionId, runId, planId, planRevision), new ReportRef(reportId, revision), blockId, cursor, size));
    }
    @GetMapping("/sessions/{sessionId}/reports")
    public Result<HistoryPage> history(@PathVariable String sessionId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size, @RequestHeader("X-Agent-Username") String username,
            @RequestHeader("X-Agent-UserId") String tenant, @RequestHeader("X-Agent-Auth-Version") long authVersion) {
        return Result.success(reports.history(caller(tenant, username, authVersion), sessionId, cursor, size));
    }
    @GetMapping("/reports/{reportId}/revisions/{revision}/export")
    public Result<Export> export(@PathVariable String reportId, @PathVariable int revision,
            @RequestParam String sessionId, @RequestParam String runId, @RequestParam String planId, @RequestParam int planRevision,
            @RequestHeader("X-Agent-Username") String username, @RequestHeader("X-Agent-UserId") String tenant,
            @RequestHeader("X-Agent-Auth-Version") long authVersion) {
        return Result.success(reports.export(caller(tenant, username, authVersion),
                new Reference(sessionId, runId, planId, planRevision), new ReportRef(reportId, revision)));
    }
    private static Caller caller(String tenant, String username, long version) {
        if (tenant == null || tenant.isBlank() || username == null || username.isBlank() || version < 1)
            throw new SecurityException("REPORT_CALLER_INVALID");
        return new Caller(tenant, username, version);
    }
}
