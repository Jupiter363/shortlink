package com.nageoffer.shortlink.admin.controller;

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
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        AgentController controller = new AgentController(remoteService, configuration);
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId("session-1");
        request.setMessage("analyze campaign");
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        Result<Object> expected = Results.success(Map.of("sessionId", "session-1"));

        when(remoteService.chat(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                request
        )).thenReturn(expected);

        Result<Object> actual = controller.chat(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).chat(
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
        AgentController controller = new AgentController(remoteService, configuration);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name"));
        Result<Object> expected = Results.success(Map.of("status", "OK"));

        when(remoteService.health("internal-token", "trusted-user", "1001", "Trusted Name"))
                .thenReturn(expected);

        Result<Object> actual = controller.health();

        assertThat(actual).isSameAs(expected);
        verify(remoteService).health("internal-token", "trusted-user", "1001", "Trusted Name");
    }

    @Test
    void chatRequiresGatewayInjectedUserContext() {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        AgentController controller = new AgentController(remoteService, configuration);
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
}
