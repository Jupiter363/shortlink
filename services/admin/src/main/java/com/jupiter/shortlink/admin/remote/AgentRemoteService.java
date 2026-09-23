package com.jupiter.shortlink.admin.remote;

import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.remote.dto.req.AgentChatReqDTO;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(
        value = "short-link-agent",
        contextId = "agent-chat",
        url = "${short-link.agent.admin.remote-url:}")
public interface AgentRemoteService {

    @PostMapping("/internal/short-link-agent/v1/chat")
    Result<Object> chat(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader(value = "X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @RequestHeader(value = "X-Agent-Auth-Version") Long authVersion,
            @RequestBody AgentChatReqDTO requestParam);

    @GetMapping("/internal/short-link-agent/v1/health")
    Result<Object> health(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader(value = "X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @RequestHeader(value = "X-Agent-Auth-Version") Long authVersion);

    @GetMapping("/internal/short-link-agent/v1/campaign/runs/{runId}/progress")
    Result<Object> campaignProgress(@RequestHeader Map<String, String> headers,
            @PathVariable("runId") String runId, @RequestParam("sessionId") String sessionId,
            @RequestParam("requestId") String requestId);

    @GetMapping("/internal/short-link-agent/v1/campaign/reports/{reportId}/revisions/{revision}")
    Result<Object> campaignReport(@RequestHeader Map<String, String> headers,
            @PathVariable("reportId") String reportId, @PathVariable("revision") int revision,
            @RequestParam("sessionId") String sessionId, @RequestParam("runId") String runId,
            @RequestParam("planId") String planId, @RequestParam("planRevision") int planRevision);

    @GetMapping("/internal/short-link-agent/v1/campaign/reports/{reportId}/revisions/{revision}/blocks/{blockId}/rows")
    Result<Object> campaignReportRows(@RequestHeader Map<String, String> headers,
            @PathVariable("reportId") String reportId, @PathVariable("revision") int revision,
            @PathVariable("blockId") String blockId, @RequestParam("sessionId") String sessionId,
            @RequestParam("runId") String runId, @RequestParam("planId") String planId,
            @RequestParam("planRevision") int planRevision,
            @RequestParam(value = "cursor", required = false) String cursor, @RequestParam("size") int size);

    @GetMapping("/internal/short-link-agent/v1/campaign/reports/{reportId}/revisions/{revision}/export")
    Result<Object> campaignReportExport(@RequestHeader Map<String, String> headers,
            @PathVariable("reportId") String reportId, @PathVariable("revision") int revision,
            @RequestParam("sessionId") String sessionId, @RequestParam("runId") String runId,
            @RequestParam("planId") String planId, @RequestParam("planRevision") int planRevision);

    @GetMapping("/internal/short-link-agent/v1/campaign/sessions/{sessionId}/reports")
    Result<Object> campaignReportHistory(@RequestHeader Map<String, String> headers,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "cursor", required = false) String cursor, @RequestParam("size") int size);
}
