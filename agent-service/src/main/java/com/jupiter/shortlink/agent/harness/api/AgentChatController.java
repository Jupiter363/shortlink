package com.jupiter.shortlink.agent.harness.api;

import com.jupiter.shortlink.agent.common.result.Result;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunHarness;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunRequest;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentChatController {

    private final AgentRunHarness agentRunHarness;

    public AgentChatController(AgentRunHarness agentRunHarness) {
        this.agentRunHarness = agentRunHarness;
    }

    @PostMapping("/internal/short-link-agent/v1/chat")
    public Result<AgentRunResult> chat(
            @RequestBody AgentChatRequest request,
            @RequestHeader(value = "X-Agent-Username") String trustedUsername,
            @RequestHeader(value = "X-Agent-UserId") String trustedTenantId,
            @RequestHeader(value = "X-Agent-Auth-Version") Long trustedAuthVersion) {
        AgentPrincipal principal =
                new AgentPrincipal(
                        trustedTenantId,
                        trustedUsername,
                        trustedAuthVersion == null ? 0 : trustedAuthVersion,
                        false);
        String username = principal.username();
        AgentRunRequest runRequest =
                new AgentRunRequest(
                        request.sessionId(),
                        request.agentType(),
                        username,
                        request.message(),
                        principal);
        return Result.success(agentRunHarness.run(runRequest));
    }

    public Result<AgentRunResult> chat(AgentChatRequest request, String trustedUsername) {
        throw new IllegalArgumentException("Trusted tenant and authVersion are required");
    }

    public record AgentChatRequest(
            String sessionId, String username, String agentType, String message) {}
}
