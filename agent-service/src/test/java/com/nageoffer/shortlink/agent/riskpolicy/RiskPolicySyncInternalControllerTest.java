package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.harness.action.api.AgentActionExceptionHandler;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.security.InternalAgentApiFilter;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncInternalController;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutboxStatus;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RiskPolicySyncInternalControllerTest {

    private RiskPolicySyncService syncService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        syncService = mock(RiskPolicySyncService.class);
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("internal-token");
        mockMvc = standaloneSetup(new RiskPolicySyncInternalController(syncService))
                .setControllerAdvice(new AgentActionExceptionHandler())
                .addFilters(new InternalAgentApiFilter(properties))
                .build();
    }

    @Test
    void replayUsesTrustedActorAndReturnsOnlyPublicStatus() throws Exception {
        AgentActionActor actor = new AgentActionActor(
                "operator-1", "1001", "Risk Operator", "");
        when(syncService.replay(
                "outbox-1", actor, "retry after redis recovery"))
                .thenReturn(RiskPolicySyncOutboxStatus.PENDING);

        mockMvc.perform(request("outbox-1")
                        .content("{\"reason\":\"retry after redis recovery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outboxId").value("outbox-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(content().string(not(containsString("ownerToken"))))
                .andExpect(content().string(not(containsString("redisValueJson"))))
                .andExpect(content().string(not(containsString("lastError"))));

        verify(syncService).replay(
                "outbox-1", actor, "retry after redis recovery");
    }

    @Test
    void internalTokenAndAllTrustedIdentityHeadersAreRequired() throws Exception {
        mockMvc.perform(post("/internal/short-link-agent/v1/policy-sync/outbox/outbox-1/replay")
                        .contentType("application/json")
                        .content("{\"reason\":\"retry\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/internal/short-link-agent/v1/policy-sync/outbox/outbox-1/replay")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "operator-1")
                        .header("X-Agent-UserId", "1001")
                        .contentType("application/json")
                        .content("{\"reason\":\"retry\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_SCOPE_FORBIDDEN"));
    }

    @Test
    void mapsInvalidNotFoundAndConflictErrorsWithoutInternalDetails() throws Exception {
        AgentActionActor actor = new AgentActionActor(
                "operator-1", "1001", "Risk Operator", "");
        when(syncService.replay("bad", actor, "retry"))
                .thenThrow(new AgentActionException(
                        "ACTION_PAYLOAD_INVALID", "Risk policy sync request is invalid"));
        when(syncService.replay("missing", actor, "retry"))
                .thenThrow(new AgentActionException(
                        "POLICY_SYNC_OUTBOX_NOT_FOUND", "Risk policy sync outbox was not found"));
        when(syncService.replay("active", actor, "retry"))
                .thenThrow(new AgentActionException(
                        "POLICY_SYNC_OUTBOX_NOT_REPLAYABLE", "Risk policy sync outbox cannot be replayed"));

        mockMvc.perform(request("bad").content("{\"reason\":\"retry\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(request("missing").content("{\"reason\":\"retry\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(request("active").content("{\"reason\":\"retry\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("Redis"))))
                .andExpect(content().string(not(containsString("lease"))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String outboxId
    ) {
        return post("/internal/short-link-agent/v1/policy-sync/outbox/{outboxId}/replay", outboxId)
                .header("X-Agent-Internal-Token", "internal-token")
                .header("X-Agent-Username", "operator-1")
                .header("X-Agent-UserId", "1001")
                .header("X-Agent-RealName", "Risk Operator")
                .contentType("application/json");
    }
}
