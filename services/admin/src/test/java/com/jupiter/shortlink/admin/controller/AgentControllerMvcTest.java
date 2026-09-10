package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jupiter.shortlink.admin.common.biz.user.*;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.remote.AgentRemoteService;
import com.jupiter.shortlink.admin.remote.dto.req.AgentChatReqDTO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

class AgentControllerMvcTest {
    private static final String SECRET = "agent-mvc-internal-secret-32-bytes";
    private final AgentRemoteService remote = mock(AgentRemoteService.class);
    private final UserMapper users = mock(UserMapper.class);

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    private MockMvc mvc() {
        var config = new AgentAdminConfiguration();
        config.setInternalToken(SECRET);
        var user = new UserDO();
        user.setId(1001L);
        user.setUsername("trusted-user");
        user.setAuthVersion(7L);
        user.setDelFlag(0);
        user.setDisabled(false);
        when(users.selectOne(any())).thenReturn(user);
        return MockMvcBuilders.standaloneSetup(new AgentController(remote, config))
                .addFilters(new UserTransmitFilter(new TrustedManagementIdentity(users), SECRET))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void forwardsOnlyVerifiedTenantUsernameAndVersion() throws Exception {
        var mvc = mvc();
        when(remote.chat(eq(SECRET), eq("trusted-user"), eq("1001"), isNull(), eq(7L), any()))
                .thenReturn(Results.success(Map.of("sessionId", "session-1")));
        mvc.perform(
                        post("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Token", SECRET)
                                .header("x-shortlink-tenant-id", "1001")
                                .header("x-shortlink-username", "trusted-user")
                                .header("x-shortlink-auth-version", "7")
                                .header("username", "attacker")
                                .header("realName", "attacker")
                                .content(
                                        "{\"sessionId\":\"session-1\",\"agentType\":\"security-risk\",\"username\":\"attacker\",\"message\":\"analyze\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        var request = ArgumentCaptor.forClass(AgentChatReqDTO.class);
        verify(remote)
                .chat(
                        eq(SECRET),
                        eq("trusted-user"),
                        eq("1001"),
                        isNull(),
                        eq(7L),
                        request.capture());
        assertThat(request.getValue().getMessage()).isEqualTo("analyze");
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void serviceTokenAndCurrentDatabaseVersionAreRequired() throws Exception {
        var mvc = mvc();
        mvc.perform(
                        post("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("username", "trusted-user")
                                .content("{\"sessionId\":\"s1\",\"message\":\"analyze\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Token", SECRET)
                                .header("x-shortlink-tenant-id", "1001")
                                .header("x-shortlink-username", "trusted-user")
                                .header("x-shortlink-auth-version", "6")
                                .content("{\"sessionId\":\"s1\",\"message\":\"analyze\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(remote);
    }
}
