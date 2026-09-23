package com.jupiter.shortlink.agent.harness.api;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignSessionRecoveryService;
import com.jupiter.shortlink.agent.common.result.Result;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("campaign-plan-v2")
public final class CampaignSessionRecoveryController {
    private final CampaignSessionRecoveryService sessions;
    public CampaignSessionRecoveryController(CampaignSessionRecoveryService sessions) { this.sessions = sessions; }

    @GetMapping("/internal/short-link-agent/v1/campaign/sessions")
    public Result<CampaignSessionRecoveryService.SessionsPage> sessions(
            @RequestParam(value="cursor", required=false) String cursor,
            @RequestParam(value="size", defaultValue="20") int size,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader("X-Agent-UserId") String tenantId,
            @RequestHeader("X-Agent-Auth-Version") long authVersion) {
        return Result.success(sessions.sessions(new AgentPrincipal(tenantId, username, authVersion, false), cursor, size));
    }

    @GetMapping("/internal/short-link-agent/v1/campaign/workspace")
    public Result<CampaignSessionRecoveryService.Page> recover(
            @RequestParam(value="sessionId", required=false) String sessionId,
            @RequestParam(value="cursor", required=false) String cursor,
            @RequestParam(value="size", defaultValue="20") int size,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader("X-Agent-UserId") String tenantId,
            @RequestHeader("X-Agent-Auth-Version") long authVersion) {
        return Result.success(sessions.recover(new AgentPrincipal(tenantId, username, authVersion, false), sessionId, cursor, size));
    }
}
