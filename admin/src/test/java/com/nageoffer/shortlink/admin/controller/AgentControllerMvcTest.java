package com.nageoffer.shortlink.admin.controller;

import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserTransmitFilter;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.remote.AgentRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.AgentChatReqDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerMvcTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void chatUsesGatewayInjectedHeadersAndIgnoresBodyUsername() throws Exception {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken("internal-token");
        MockMvc mockMvc = mockMvc(remoteService, configuration);
        when(remoteService.chat(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                request("session-1", "security-risk", "analyze campaign")
        )).thenReturn(Results.success(Map.of("sessionId", "session-1")));

        mockMvc.perform(post("/api/short-link/admin/v1/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("username", "trusted-user")
                        .header("userId", "1001")
                        .header("realName", "Trusted Name")
                        .content("""
                                {
                                  "sessionId": "session-1",
                                  "agentType": "security-risk",
                                  "username": "spoofed-user",
                                  "message": "analyze campaign"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.sessionId").value("session-1"));

        ArgumentCaptor<AgentChatReqDTO> requestCaptor = ArgumentCaptor.forClass(AgentChatReqDTO.class);
        verify(remoteService).chat(
                eq("internal-token"),
                eq("trusted-user"),
                eq("1001"),
                eq("Trusted Name"),
                requestCaptor.capture()
        );
        assertThat(requestCaptor.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(requestCaptor.getValue().getAgentType()).isEqualTo("security-risk");
        assertThat(requestCaptor.getValue().getMessage()).isEqualTo("analyze campaign");
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void chatRejectsRequestWithoutGatewayInjectedUsername() throws Exception {
        AgentRemoteService remoteService = mock(AgentRemoteService.class);
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        MockMvc mockMvc = mockMvc(remoteService, configuration);

        mockMvc.perform(post("/api/short-link/admin/v1/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "session-1",
                                  "message": "analyze campaign"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"))
                .andExpect(jsonPath("$.message").value("Agent request requires authenticated user context"));
    }

    private MockMvc mockMvc(AgentRemoteService remoteService, AgentAdminConfiguration configuration) {
        return MockMvcBuilders
                .standaloneSetup(new AgentController(remoteService, configuration))
                .addFilters(new UserTransmitFilter())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private AgentChatReqDTO request(String sessionId, String agentType, String message) {
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId(sessionId);
        request.setAgentType(agentType);
        request.setMessage(message);
        return request;
    }
}
