package com.jupiter.shortlink.admin.controller;

import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.remote.AgentRemoteService;
import com.jupiter.shortlink.admin.remote.dto.req.AgentChatReqDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentRemoteService agentRemoteService;

    private final AgentAdminConfiguration agentAdminConfiguration;

    @PostMapping("/api/short-link/admin/v1/agent/chat")
    public Result<Object> chat(@RequestBody AgentChatReqDTO requestParam) {
        String username = requireUsername();
        return agentRemoteService.chat(
                internalToken(),
                username,
                UserContext.getUserId(),
                UserContext.getRealName(),
                UserContext.getAuthVersion(),
                requestParam);
    }

    @GetMapping("/api/short-link/admin/v1/agent/health")
    public Result<Object> health() {
        String username = requireUsername();
        return agentRemoteService.health(
                internalToken(),
                username,
                UserContext.getUserId(),
                UserContext.getRealName(),
                UserContext.getAuthVersion());
    }

    @GetMapping("/api/short-link/admin/v1/agent/campaign/runs/{runId}/progress")
    public Result<Object> campaignProgress(@PathVariable("runId") String runId,
            @RequestParam("sessionId") String sessionId, @RequestParam("requestId") String requestId) {
        return agentRemoteService.campaignProgress(trustedHeaders(), runId, sessionId, requestId);
    }

    @GetMapping("/api/short-link/admin/v1/agent/campaign/reports/{reportId}/revisions/{revision}")
    public Result<Object> campaignReport(@PathVariable("reportId") String reportId,
            @PathVariable("revision") int revision, @RequestParam("sessionId") String sessionId,
            @RequestParam("runId") String runId, @RequestParam("planId") String planId,
            @RequestParam("planRevision") int planRevision) {
        return agentRemoteService.campaignReport(trustedHeaders(), reportId, revision,
                sessionId, runId, planId, planRevision);
    }

    @GetMapping("/api/short-link/admin/v1/agent/campaign/reports/{reportId}/revisions/{revision}/blocks/{blockId}/rows")
    public Result<Object> campaignReportRows(@PathVariable("reportId") String reportId,
            @PathVariable("revision") int revision, @PathVariable("blockId") String blockId,
            @RequestParam("sessionId") String sessionId, @RequestParam("runId") String runId,
            @RequestParam("planId") String planId, @RequestParam("planRevision") int planRevision,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return agentRemoteService.campaignReportRows(trustedHeaders(), reportId, revision,
                blockId, sessionId, runId, planId, planRevision, cursor, size);
    }

    @GetMapping("/api/short-link/admin/v1/agent/campaign/reports/{reportId}/revisions/{revision}/export")
    public Result<Object> campaignReportExport(@PathVariable("reportId") String reportId,
            @PathVariable("revision") int revision, @RequestParam("sessionId") String sessionId,
            @RequestParam("runId") String runId, @RequestParam("planId") String planId,
            @RequestParam("planRevision") int planRevision) {
        return agentRemoteService.campaignReportExport(trustedHeaders(), reportId, revision,
                sessionId, runId, planId, planRevision);
    }

    @GetMapping("/api/short-link/admin/v1/agent/campaign/sessions/{sessionId}/reports")
    public Result<Object> campaignReportHistory(@PathVariable("sessionId") String sessionId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return agentRemoteService.campaignReportHistory(trustedHeaders(), sessionId, cursor, size);
    }

    private Map<String, String> trustedHeaders() {
        String username = requireUsername();
        String userId = UserContext.getUserId();
        Long authVersion = UserContext.getAuthVersion();
        if (!StringUtils.hasText(userId) || authVersion == null || authVersion < 1)
            throw new ClientException("Agent request requires current authenticated account");
        return Map.of("X-Agent-Internal-Token", internalToken(), "X-Agent-Username", username,
                "X-Agent-UserId", userId, "X-Agent-Auth-Version", authVersion.toString());
    }

    private String requireUsername() {
        String username = UserContext.getUsername();
        if (!StringUtils.hasText(username)) {
            throw new ClientException("Agent request requires authenticated user context");
        }
        return username;
    }

    private String internalToken() {
        return Optional.ofNullable(agentAdminConfiguration.getInternalToken()).orElse("");
    }
}
