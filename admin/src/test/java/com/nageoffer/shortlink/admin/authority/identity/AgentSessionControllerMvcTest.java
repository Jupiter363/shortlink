package com.nageoffer.shortlink.admin.authority.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.identity.api.AgentIdentityExceptionHandler;
import com.nageoffer.shortlink.admin.authority.identity.api.AgentSessionController;
import com.nageoffer.shortlink.admin.authority.identity.api.AgentTokenRevocationController;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionTokenResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.MtlsRuntimeIdentityVerifier;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantException;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentSessionControllerMvcTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    private static final AgentSessionTokenResponse TOKEN_RESPONSE = new AgentSessionTokenResponse(
            "as-s-controller",
            "campaign-analysis",
            "/api/short-link/agent-runtime/v1/sessions/as-s-controller",
            "eyJ.runtime-token.signature",
            NOW.plusSeconds(300),
            NOW.plusSeconds(8 * 60 * 60),
            1
    );

    @AfterEach
    void clearUserContext() {
        UserContext.removeUser();
    }

    @Test
    void bootstrapUsesAuthenticatedActorAndMatchesContract() throws Exception {
        AgentSessionLifecycleService service = mock(AgentSessionLifecycleService.class);
        when(service.bootstrap(any(), eq("1001"), eq("trusted-user"))).thenReturn(TOKEN_RESPONSE);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        MvcResult result = sessionMockMvc(service).perform(post("/api/short-link/admin/v1/agent/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentType": "campaign-analysis",
                                  "clientContext": {
                                    "locale": "zh-CN",
                                    "timezone": "Asia/Shanghai"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.sessionId").value("as-s-controller"))
                .andExpect(jsonPath("$.agentType").value("campaign-analysis"))
                .andExpect(jsonPath("$.runtimeToken").value("eyJ.runtime-token.signature"))
                .andExpect(jsonPath("$.grantVersion").value(1))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andReturn();

        JsonNode response = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        HashSet<String> fields = new HashSet<>();
        response.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "sessionId",
                "agentType",
                "runtimeUrl",
                "runtimeToken",
                "runtimeTokenExpiresAt",
                "sessionExpiresAt",
                "grantVersion"
        );
        assertThat(response.path("runtimeTokenExpiresAt").isTextual()).isTrue();
        assertThat(response.path("sessionExpiresAt").isTextual()).isTrue();

        verify(service).bootstrap(any(), eq("1001"), eq("trusted-user"));
    }

    @Test
    void bootstrapRejectsIdentityAndUnknownBodyFields() throws Exception {
        AgentSessionLifecycleService service = mock(AgentSessionLifecycleService.class);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        sessionMockMvc(service).perform(post("/api/short-link/admin/v1/agent/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentType": "campaign-analysis",
                                  "clientContext": {
                                    "locale": "zh-CN",
                                    "timezone": "Asia/Shanghai"
                                  },
                                  "ownerUserId": "attacker"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_SESSION_REQUEST_INVALID"));

        verifyNoInteractions(service);
    }

    @Test
    void refreshAndDeleteAcceptOnlyEmptyControlBodies() throws Exception {
        AgentSessionLifecycleService service = mock(AgentSessionLifecycleService.class);
        when(service.refresh(
                eq("as-s-controller"),
                eq("1001"),
                eq("trusted-user"),
                eq(Map.of())
        )).thenReturn(TOKEN_RESPONSE);
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));
        MockMvc mockMvc = sessionMockMvc(service);

        mockMvc.perform(post(
                        "/api/short-link/admin/v1/agent/sessions/as-s-controller/token/refresh"
                ).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sessionId").value("as-s-controller"));

        mockMvc.perform(delete("/api/short-link/admin/v1/agent/sessions/as-s-controller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).revoke(
                "as-s-controller",
                "1001",
                "trusted-user",
                Map.of()
        );
    }

    @Test
    void sessionEndpointsRejectMissingAuthenticatedActor() throws Exception {
        AgentSessionLifecycleService service = mock(AgentSessionLifecycleService.class);

        sessionMockMvc(service).perform(post("/api/short-link/admin/v1/agent/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentType": "campaign-analysis",
                                  "clientContext": {
                                    "locale": "zh-CN",
                                    "timezone": "Asia/Shanghai"
                                  }
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AGENT_SESSION_ACTOR_REQUIRED"));
    }

    @Test
    void ownershipMismatchIsIndistinguishableFromUnknownSession() throws Exception {
        AgentSessionLifecycleService service = mock(AgentSessionLifecycleService.class);
        when(service.refresh(
                eq("as-s-controller"),
                eq("1001"),
                eq("trusted-user"),
                eq(Map.of())
        )).thenThrow(AgentSessionGrantException.forbidden());
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));

        sessionMockMvc(service).perform(post(
                        "/api/short-link/admin/v1/agent/sessions/as-s-controller/token/refresh"
                ).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Agent session grant does not exist."));
    }

    @Test
    void revocationCheckRequiresRuntimeIdentityBeforeReadingStatus() throws Exception {
        AgentSessionLifecycleService service = mock(AgentSessionLifecycleService.class);
        MtlsRuntimeIdentityVerifier verifier = mock(MtlsRuntimeIdentityVerifier.class);
        when(verifier.verify(any(HttpServletRequest.class))).thenReturn("shortlink-agent-runtime");
        when(service.isActive("as-s-controller", 3, "adt-token-3")).thenReturn(false);
        AgentTokenRevocationController controller = new AgentTokenRevocationController(
                service,
                verifier,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentIdentityExceptionHandler())
                .build();

        mockMvc.perform(post("/internal/short-link-admin/v1/agent-identity/revocations/check")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "as-s-controller",
                                  "grantVersion": 3,
                                  "tokenId": "adt-token-3"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.reasonCode").value("TOKEN_REVOKED"))
                .andExpect(jsonPath("$.checkedAt").value("2026-07-17T08:00:00Z"));

        InOrder order = inOrder(verifier, service);
        order.verify(verifier).verify(any(HttpServletRequest.class));
        order.verify(service).isActive("as-s-controller", 3, "adt-token-3");
    }

    private MockMvc sessionMockMvc(AgentSessionLifecycleService service) {
        return MockMvcBuilders.standaloneSetup(new AgentSessionController(service))
                .setControllerAdvice(new AgentIdentityExceptionHandler())
                .build();
    }
}
