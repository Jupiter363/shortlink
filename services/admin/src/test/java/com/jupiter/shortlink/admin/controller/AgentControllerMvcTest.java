package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jupiter.shortlink.admin.account.AccountSession;
import com.jupiter.shortlink.admin.account.AccountSessionStore;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

class AgentControllerMvcTest {
    private static final String SECRET = "agent-mvc-internal-secret-32-bytes";
    private static final String SESSION_TOKEN = "agent-mvc-session-token-00000001";
    private final AgentRemoteService remote = mock(AgentRemoteService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final AccountSessionStore sessions = mock(AccountSessionStore.class);

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
        when(sessions.find("trusted-user", SESSION_TOKEN))
                .thenReturn(new AccountSession(1001L, "trusted-user", 7L, Long.MAX_VALUE));
        var ingress = new AdminIngressProperties();
        ingress.setAllowedHosts(List.of("admin.example"));
        ingress.setTrustedProxyCidrs(List.of("127.0.0.0/8"));
        return MockMvcBuilders.standaloneSetup(new AgentController(remote, config))
                .addFilters(
                        new UserTransmitFilter(
                                new TrustedManagementIdentity(users), sessions, ingress, 8102))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockHttpServletRequestBuilder ingressPost(String path) {
        return post(path).header("Host", "admin.example")
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                });
    }

    @Test
    void forwardsOnlyVerifiedTenantUsernameAndVersion() throws Exception {
        var mvc = mvc();
        when(remote.chat(eq(SECRET), eq("trusted-user"), eq("1001"), isNull(), eq(7L), any()))
                .thenReturn(Results.success(Map.of("sessionId", "session-1")));
        mvc.perform(
                        ingressPost("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Token", "attacker-internal-token")
                                .header("x-shortlink-tenant-id", "9999")
                                .header("x-shortlink-username", "attacker")
                                .header("x-shortlink-auth-version", "999")
                                .header("username", "trusted-user")
                                .header("token", SESSION_TOKEN)
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
        verify(sessions).find("trusted-user", SESSION_TOKEN);
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void sessionAndCurrentDatabaseVersionAreRequired() throws Exception {
        var mvc = mvc();
        mvc.perform(
                        ingressPost("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("username", "trusted-user")
                                .content("{\"sessionId\":\"s1\",\"message\":\"analyze\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions, users, remote);
        when(sessions.find("trusted-user", SESSION_TOKEN))
                .thenReturn(new AccountSession(1001L, "trusted-user", 6L, Long.MAX_VALUE));
        mvc.perform(
                        ingressPost("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("username", "trusted-user")
                                .header("token", SESSION_TOKEN)
                                .header("x-shortlink-tenant-id", "1001")
                                .header("x-shortlink-username", "trusted-user")
                                .header("x-shortlink-auth-version", "7")
                                .content("{\"sessionId\":\"s1\",\"message\":\"analyze\"}"))
                .andExpect(status().isUnauthorized());
        verify(sessions).find("trusted-user", SESSION_TOKEN);
        verify(users).selectOne(any());
        verifyNoInteractions(remote);
    }

    @Test
    void legacyInternalTokenAndIdentityHeadersCannotReplaceSession() throws Exception {
        var mvc = mvc();
        mvc.perform(
                        ingressPost("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Internal-Token", SECRET)
                                .header("x-shortlink-tenant-id", "1001")
                                .header("x-shortlink-username", "trusted-user")
                                .header("x-shortlink-auth-version", "7")
                                .content("{\"sessionId\":\"s1\",\"message\":\"analyze\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION"));
        verifyNoInteractions(sessions, users, remote);
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void expiredSessionStopsBeforeDatabaseOrAgent() throws Exception {
        var mvc = mvc();
        when(sessions.find("trusted-user", SESSION_TOKEN))
                .thenReturn(new AccountSession(1001L, "trusted-user", 7L, 1L));
        mvc.perform(
                        ingressPost("/api/short-link/admin/v1/agent/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("username", "trusted-user")
                                .header("token", SESSION_TOKEN)
                                .content("{\"sessionId\":\"s1\",\"message\":\"analyze\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION"));
        verify(sessions).find("trusted-user", SESSION_TOKEN);
        verifyNoInteractions(users, remote);
        assertThat(UserContext.getUsername()).isNull();
    }
}
