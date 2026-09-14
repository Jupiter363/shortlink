package com.jupiter.shortlink.admin.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jupiter.shortlink.admin.account.AccountSession;
import com.jupiter.shortlink.admin.account.AccountSessionStore;
import com.jupiter.shortlink.admin.common.biz.user.AdminIngressProperties;
import com.jupiter.shortlink.admin.common.biz.user.TrustedManagementIdentity;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.biz.user.UserTransmitFilter;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.service.UserService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserLogoutMvcTest {
    private static final String PATH = "/api/short-link/admin/v1/user/logout";
    private static final String TOKEN = "logout-mvc-session-token-00000001";
    private final UserService users = mock(UserService.class);
    private final AccountSessionStore sessions = mock(AccountSessionStore.class);
    private final TrustedManagementIdentity identities = mock(TrustedManagementIdentity.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var ingress = new AdminIngressProperties();
        ingress.setAllowedHosts(List.of("admin.example"));
        ingress.setTrustedProxyCidrs(List.of("127.0.0.0/8"));
        when(sessions.find("alice", TOKEN)).thenReturn(new AccountSession(101, "alice", 1, Long.MAX_VALUE));
        when(identities.verify("101", "alice", "1")).thenReturn(new UserInfoDTO("101", "alice", null, 1L));
        mvc = MockMvcBuilders.standaloneSetup(new UserController(users))
                .addFilters(new UserTransmitFilter(identities, sessions, ingress, 8102))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void cleanup() { UserContext.removeUser(); }

    private MockHttpServletRequestBuilder ingress() {
        return delete(PATH).header("Host", "admin.example").with(request -> {
            request.setRemoteAddr("127.0.0.1");
            return request;
        });
    }
    private MockHttpServletRequestBuilder authenticated() {
        return ingress().header("Username", "alice").header("Token", TOKEN);
    }

    @Test void headerOnlyLogoutDeletesExactlyTheVerifiedSession() throws Exception {
        mvc.perform(authenticated().header("x-shortlink-username", "bob").header("userid", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        verify(users).logout("alice", TOKEN);
        verifyNoMoreInteractions(users);
        verify(sessions).find("alice", TOKEN);
        verify(identities).verify("101", "alice", "1");
    }

    @Test void legacyMatchingQueryRemainsCompatible() throws Exception {
        mvc.perform(authenticated().param("username", "alice").param("token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        verify(users).logout("alice", TOKEN);
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest @ValueSource(strings = {"username", "token"})
    void partialLegacyQueryIsRejected(String supplied) throws Exception {
        mvc.perform(authenticated().param(supplied, supplied.equals("username") ? "alice" : TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_ARGUMENT_MISMATCH"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(strings = {"username", "token"})
    void conflictingLegacyQueryCannotLogoutAnotherAccountOrSession(String conflict) throws Exception {
        mvc.perform(authenticated().param("username", conflict.equals("username") ? "bob" : "alice")
                        .param("token", conflict.equals("token") ? "other-session-token-00000000001" : TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_ARGUMENT_MISMATCH"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(strings = {"username", "token"})
    void duplicateLegacyQueryIsRejectedEvenWhenAllValuesMatch(String duplicate) throws Exception {
        mvc.perform(authenticated().param("username", "alice").param("token", TOKEN)
                        .param(duplicate, duplicate.equals("username") ? "alice" : TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_ARGUMENT_MISMATCH"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(strings = {"username", "token", "both"})
    void queryCredentialsCannotReplaceMissingAuthenticationHeaders(String missing) throws Exception {
        var request = ingress().param("username", "alice").param("token", TOKEN);
        if (!missing.equals("username") && !missing.equals("both")) request.header("Username", "alice");
        if (!missing.equals("token") && !missing.equals("both")) request.header("Token", TOKEN);
        mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(users, sessions, identities);
    }

    @ParameterizedTest @ValueSource(strings = {"Username", "Token"})
    void duplicateAuthenticationHeadersRemainRejected(String duplicate) throws Exception {
        mvc.perform(authenticated().header(duplicate, duplicate.equals("Username") ? "alice" : TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST_CONTEXT"));
        verifyNoInteractions(users, sessions, identities);
    }

    @Test void expiredSessionCannotLogout() throws Exception {
        when(sessions.find("alice", TOKEN)).thenReturn(new AccountSession(101, "alice", 1, 1));
        mvc.perform(authenticated()).andExpect(status().isUnauthorized());
        verifyNoInteractions(users, identities);
    }

    @Test void controllerWithoutIngressProofNeverTrustsRawHeadersOrQuery() throws Exception {
        var unfiltered = MockMvcBuilders.standaloneSetup(new UserController(users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        unfiltered.perform(authenticated().param("username", "alice").param("token", TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HTTP_401"));
        verifyNoInteractions(users, sessions, identities);
    }
}
