package com.nageoffer.shortlink.admin.controller;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionTokenResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenService;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.remote.AgentRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.AgentChatReqDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.Set;

@RestController
public class AgentController {

    private final AgentRemoteService agentRemoteService;

    private final AgentAdminConfiguration agentAdminConfiguration;

    private final AgentIdentityConfiguration agentIdentityConfiguration;

    private final AgentTokenService agentTokenService;

    private final AgentSessionLifecycleService sessionLifecycleService;

    @Autowired
    public AgentController(
            AgentRemoteService agentRemoteService,
            AgentAdminConfiguration agentAdminConfiguration,
            AgentIdentityConfiguration agentIdentityConfiguration,
            AgentTokenService agentTokenService,
            AgentSessionLifecycleService sessionLifecycleService
    ) {
        this.agentRemoteService = agentRemoteService;
        this.agentAdminConfiguration = agentAdminConfiguration;
        this.agentIdentityConfiguration = agentIdentityConfiguration;
        this.agentTokenService = agentTokenService;
        this.sessionLifecycleService = sessionLifecycleService;
    }

    public AgentController(
            AgentRemoteService agentRemoteService,
            AgentAdminConfiguration agentAdminConfiguration,
            AgentIdentityConfiguration agentIdentityConfiguration,
            AgentTokenService agentTokenService
    ) {
        this(
                agentRemoteService,
                agentAdminConfiguration,
                agentIdentityConfiguration,
                agentTokenService,
                null
        );
    }

    @PostMapping("/api/short-link/admin/v1/agent/chat")
    public Result<Object> chat(@RequestBody AgentChatReqDTO requestParam) {
        String username = requireUsername();
        boolean delegationEnabled = agentIdentityConfiguration.getRuntimeAuthMode()
                != AgentIdentityConfiguration.RuntimeAuthMode.LEGACY;
        boolean legacyEnabled = agentIdentityConfiguration.getRuntimeAuthMode()
                != AgentIdentityConfiguration.RuntimeAuthMode.DELEGATION_JWT;
        String authorization = delegationEnabled ? delegationAuthorization(requestParam, username) : null;
        return agentRemoteService.chat(
                authorization,
                legacyEnabled ? internalToken() : null,
                legacyEnabled ? username : null,
                legacyEnabled ? UserContext.getUserId() : null,
                legacyEnabled ? UserContext.getRealName() : null,
                requestParam
        );
    }

    private String delegationAuthorization(AgentChatReqDTO requestParam, String username) {
        if (agentIdentityConfiguration.isSessionGrantEnabled()) {
            if (sessionLifecycleService == null) {
                throw AgentIdentityException.sessionGrantUnavailable();
            }
            AgentSessionTokenResponse sessionToken = sessionLifecycleService.refreshForCompatibilityChat(
                    requestParam.getSessionId(),
                    UserContext.getUserId(),
                    username,
                    requestParam.getAgentType()
            );
            return "Bearer " + sessionToken.runtimeToken();
        }
        return "Bearer " + agentTokenService.issueDelegationToken(
                UserContext.getUserId(),
                username,
                requestParam.getSessionId(),
                Set.of("agent:run", "capability:group:read", "capability:stats:read")
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
