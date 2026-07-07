package com.nageoffer.shortlink.admin.controller;

import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.remote.AgentRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.AgentChatReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

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
                requestParam
        );
    }

    @GetMapping("/api/short-link/admin/v1/agent/health")
    public Result<Object> health() {
        String username = requireUsername();
        return agentRemoteService.health(
                internalToken(),
                username,
                UserContext.getUserId(),
                UserContext.getRealName()
        );
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
