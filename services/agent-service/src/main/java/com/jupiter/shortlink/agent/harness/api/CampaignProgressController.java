package com.jupiter.shortlink.agent.harness.api;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignPublicRequestService;
import com.jupiter.shortlink.agent.common.result.Result;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("campaign-plan-v2")
public final class CampaignProgressController {
    private final CampaignPublicRequestService requests;
    public CampaignProgressController(CampaignPublicRequestService requests) { this.requests=requests; }

    @GetMapping("/internal/short-link-agent/v1/campaign/runs/{runId}/progress")
    public Result<AgentRunResult> progress(@PathVariable String runId,@RequestParam String requestId,
            @RequestParam String sessionId,@RequestHeader("X-Agent-Username") String username,
            @RequestHeader("X-Agent-UserId") String tenantId,@RequestHeader("X-Agent-Auth-Version") long authVersion) {
        return Result.success(requests.progress(new AgentPrincipal(tenantId,username,authVersion,false),sessionId,
                new WorkRef(runId,requestId)));
    }
}
