package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.remote.AgentRemoteService;
import com.jupiter.shortlink.admin.remote.dto.req.AgentChatReqDTO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

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
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name", 7L));
        Result<Object> expected = Results.success(Map.of("sessionId", "session-1"));

        when(remoteService.chat(
                        "internal-token", "trusted-user", "1001", "Trusted Name", 7L, request))
                .thenReturn(expected);

        Result<Object> actual = controller.chat(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService)
                .chat("internal-token", "trusted-user", "1001", "Trusted Name", 7L, request);
    }

    @Test
    void healthForwardsTrustedUserContextToAgentService() {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken("internal-token");
        AgentController controller = new AgentController(remoteService, configuration);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name", 7L));
        Result<Object> expected = Results.success(Map.of("status", "OK"));

        when(remoteService.health("internal-token", "trusted-user", "1001", "Trusted Name", 7L))
                .thenReturn(expected);

        Result<Object> actual = controller.health();

        assertThat(actual).isSameAs(expected);
        verify(remoteService).health("internal-token", "trusted-user", "1001", "Trusted Name", 7L);
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

        assertThat(Arrays.stream(AgentChatReqDTO.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("username");
        assertThat(request.getSessionId()).isEqualTo("session-1");
        assertThat(request.getMessage()).isEqualTo("analyze campaign");
    }

    @Test
    void reportReadsForwardExactVersionAndOnlyTrustedCurrentAccountHeaders() {
        AgentRemoteService remote = mock(AgentRemoteService.class);
        var configuration = new AgentAdminConfiguration();
        configuration.setInternalToken("internal-token");
        var controller = new AgentController(remote, configuration);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name", 7L));
        var headers = Map.of("X-Agent-Internal-Token", "internal-token", "X-Agent-Username", "trusted-user",
                "X-Agent-UserId", "1001", "X-Agent-Auth-Version", "7");
        Result<Object> expected = Results.success(Map.of("nextCursor", "page-2"));
        when(remote.campaignReportRows(headers, "report-1", 3, "table-1", "session-1", "run-1", "plan-1",
                2, "page-1", 25)).thenReturn(expected);

        assertThat(controller.campaignReportRows("report-1", 3, "table-1", "session-1", "run-1", "plan-1",
                2, "page-1", 25)).isSameAs(expected);
        verify(remote).campaignReportRows(headers, "report-1", 3, "table-1", "session-1", "run-1", "plan-1",
                2, "page-1", 25);
        controller.campaignProgress("run-1", "session-1", "intake-1");
        verify(remote).campaignProgress(headers, "run-1", "session-1", "intake-1");
    }

    @Test
    void readRoutesRejectMissingAuthVersionBeforeCallingAgent() {
        AgentRemoteService remote = mock(AgentRemoteService.class);
        var controller = new AgentController(remote, new AgentAdminConfiguration());
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted Name", null));
        assertThatThrownBy(() -> controller.campaignReportHistory("session-1", null, 20))
                .isInstanceOf(ClientException.class).hasMessage("Agent request requires current authenticated account");
        verifyNoInteractions(remote);
    }
}
