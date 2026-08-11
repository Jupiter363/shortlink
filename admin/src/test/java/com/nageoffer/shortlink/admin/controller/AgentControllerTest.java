package com.nageoffer.shortlink.admin.controller;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionTokenResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenService;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.remote.AgentRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.AgentChatReqDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void chatForwardsTrustedUserContextToAgentService() {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken("internal-token");
        AgentController controller = controller(remoteService, configuration);
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId("session-1");
        request.setMessage("analyze campaign");
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        Result<Object> expected = Results.success(Map.of("sessionId", "session-1"));

        when(remoteService.chat(
                null,
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                request
        )).thenReturn(expected);

        Result<Object> actual = controller.chat(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).chat(
                null,
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                request
        );
    }

    @Test
    void healthForwardsTrustedUserContextToAgentService() {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken("internal-token");
        AgentController controller = controller(remoteService, configuration);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        Result<Object> expected = Results.success(Map.of("status", "OK"));

        when(remoteService.health("internal-token", "trusted-user", "1001", "Trusted Name"))
                .thenReturn(expected);

        Result<Object> actual = controller.health();

        assertThat(actual).isSameAs(expected);
        verify(remoteService).health("internal-token", "trusted-user", "1001", "Trusted Name");
    }

    @Test
    void delegationJwtModeSendsBearerTokenWithoutLegacyIdentityHeaders() {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration adminConfiguration = new AgentAdminConfiguration();
        AgentIdentityConfiguration identityConfiguration = new AgentIdentityConfiguration();
        identityConfiguration.setRuntimeAuthMode(
                AgentIdentityConfiguration.RuntimeAuthMode.DELEGATION_JWT
        );
        AgentTokenService tokenService = mock(AgentTokenService.class);
        AgentController controller = new AgentController(
                remoteService,
                adminConfiguration,
                identityConfiguration,
                tokenService
        );
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId("session-1");
        request.setMessage("analyze campaign");
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        Result<Object> expected = Results.success(Map.of("sessionId", "session-1"));
        when(tokenService.issueDelegationToken(
                "1001",
                "trusted-user",
                "session-1",
                Set.of("agent:run", "capability:group:read", "capability:stats:read")
        )).thenReturn("delegation-token");
        when(remoteService.chat(
                "Bearer delegation-token",
                null,
                null,
                null,
                null,
                request
        )).thenReturn(expected);

        Result<Object> actual = controller.chat(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).chat(
                "Bearer delegation-token",
                null,
                null,
                null,
                null,
                request
        );
    }

    @Test
    void sessionGrantModeRotatesPersistedTokenForCompatibilityChat() {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentIdentityConfiguration identityConfiguration = new AgentIdentityConfiguration();
        identityConfiguration.setRuntimeAuthMode(
                AgentIdentityConfiguration.RuntimeAuthMode.DELEGATION_JWT
        );
        identityConfiguration.setSessionGrantEnabled(true);
        AgentTokenService tokenService = mock(AgentTokenService.class);
        AgentSessionLifecycleService lifecycleService = mock(AgentSessionLifecycleService.class);
        AgentController controller = new AgentController(
                remoteService,
                new AgentAdminConfiguration(),
                identityConfiguration,
                tokenService,
                lifecycleService
        );
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId("as-s-chat");
        request.setAgentType("campaign-analysis");
        request.setMessage("analyze campaign");
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        AgentSessionTokenResponse sessionToken = new AgentSessionTokenResponse(
                "as-s-chat",
                "campaign-analysis",
                "/api/short-link/agent-runtime/v1/sessions/as-s-chat",
                "persisted-delegation-token",
                Instant.parse("2026-07-17T08:05:00Z"),
                Instant.parse("2026-07-17T16:00:00Z"),
                2
        );
        Result<Object> expected = Results.success(Map.of("sessionId", "as-s-chat"));
        when(lifecycleService.refreshForCompatibilityChat(
                "as-s-chat",
                "1001",
                "trusted-user",
                "campaign-analysis"
        )).thenReturn(sessionToken);
        when(remoteService.chat(
                "Bearer persisted-delegation-token",
                null,
                null,
                null,
                null,
                request
        )).thenReturn(expected);

        assertThat(controller.chat(request)).isSameAs(expected);

        verify(lifecycleService).refreshForCompatibilityChat(
                "as-s-chat",
                "1001",
                "trusted-user",
                "campaign-analysis"
        );
        verify(tokenService, never()).issueDelegationToken(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anySet()
        );
    }

    @Test
    void chatRequiresGatewayInjectedUserContext() {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        AgentController controller = controller(remoteService, configuration);
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId("session-1");
        request.setMessage("analyze campaign");

        assertThatThrownBy(() -> controller.chat(request))
                .isInstanceOf(ClientException.class)
                .hasMessage("Agent request requires authenticated user context");
    }

    @Test
    void chatRequestDoesNotExposeUsernameField() {
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId("session-1");
        request.setMessage("analyze campaign");

        assertThat(Arrays.stream(AgentChatReqDTO.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("username");
        assertThat(request.getSessionId()).isEqualTo("session-1");
        assertThat(request.getMessage()).isEqualTo("analyze campaign");
    }

    private AgentController controller(
            AgentRemoteService remoteService,
            AgentAdminConfiguration configuration
    ) {
        return new AgentController(
                remoteService,
                configuration,
                new AgentIdentityConfiguration(),
                mock(AgentTokenService.class)
        );
    }
}
