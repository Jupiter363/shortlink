package com.nageoffer.shortlink.agent.harness.api;

import com.nageoffer.shortlink.agent.common.result.Result;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunHarness;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunRequest;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

@RestController
public class AgentChatController {

    private final AgentRunHarness agentRunHarness;

    public AgentChatController(AgentRunHarness agentRunHarness) {
        this.agentRunHarness = agentRunHarness;
    }

    @PostMapping("/internal/short-link-agent/v1/chat")
    public Result<AgentRunResult> chat(
            @RequestBody AgentChatRequest request,
            @RequestHeader(value = "X-Agent-Username", required = false) String trustedUsername) {
        String username = StringUtils.hasText(trustedUsername) ? trustedUsername : request.username();
        AgentRunRequest runRequest = new AgentRunRequest(
                request.sessionId(),
                username,
                request.message()
        );
        return Result.success(agentRunHarness.run(runRequest));
    }

    public record AgentChatRequest(String sessionId, String username, String message) {
    }
}
