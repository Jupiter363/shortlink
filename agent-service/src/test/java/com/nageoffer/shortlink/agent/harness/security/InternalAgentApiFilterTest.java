package com.nageoffer.shortlink.agent.harness.security;

import com.nageoffer.shortlink.agent.harness.api.HealthController;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalAgentApiFilterTest {

    @Test
    void blankInternalTokenAllowsLocalInternalApiRequests() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController())
                .addFilters(new InternalAgentApiFilter(properties))
                .build();

        mockMvc.perform(get("/internal/short-link-agent/v1/health"))
                .andExpect(status().isOk());
    }

    @Test
    void configuredInternalTokenRejectsMissingHeader() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("expected-token");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController())
                .addFilters(new InternalAgentApiFilter(properties))
                .build();

        mockMvc.perform(get("/internal/short-link-agent/v1/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredInternalTokenRejectsMissingHeaderWithContextPath() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("expected-token");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController())
                .addFilters(new InternalAgentApiFilter(properties))
                .build();

        mockMvc.perform(get("/agent-service/internal/short-link-agent/v1/health")
                        .contextPath("/agent-service"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredInternalTokenRejectsWrongHeader() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("expected-token");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController())
                .addFilters(new InternalAgentApiFilter(properties))
                .build();

        mockMvc.perform(get("/internal/short-link-agent/v1/health")
                        .header("X-Agent-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredInternalTokenAllowsMatchingHeader() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("expected-token");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthController())
                .addFilters(new InternalAgentApiFilter(properties))
                .build();

        mockMvc.perform(get("/internal/short-link-agent/v1/health")
                        .header("X-Agent-Internal-Token", "expected-token"))
                .andExpect(status().isOk());
    }
}
