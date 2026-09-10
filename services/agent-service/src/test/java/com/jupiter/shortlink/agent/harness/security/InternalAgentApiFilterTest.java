package com.jupiter.shortlink.agent.harness.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jupiter.shortlink.agent.harness.api.HealthController;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalAgentApiFilterTest {

    @Test
    void blankInternalTokenRejectsInternalApiRequestsByDefault() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("");
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(new HealthController())
                        .addFilters(new InternalAgentApiFilter(properties))
                        .build();

        mockMvc.perform(get("/internal/short-link-agent/v1/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankInternalTokenStillRejectsRequestsWhenDevFlagIsSet() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("");
        properties.getSecurity().setInternalTokenDevMode(true);
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(new HealthController())
                        .addFilters(new InternalAgentApiFilter(properties))
                        .build();

        mockMvc.perform(get("/internal/short-link-agent/v1/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredInternalTokenRejectsMissingHeader() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties
                .getSecurity()
                .setInternalToken(com.jupiter.shortlink.agent.StatsTestFixtures.SECRET);
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(new HealthController())
                        .addFilters(new InternalAgentApiFilter(properties))
                        .build();

        mockMvc.perform(get("/internal/short-link-agent/v1/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredInternalTokenRejectsMissingHeaderWithContextPath() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties
                .getSecurity()
                .setInternalToken(com.jupiter.shortlink.agent.StatsTestFixtures.SECRET);
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(new HealthController())
                        .addFilters(new InternalAgentApiFilter(properties))
                        .build();

        mockMvc.perform(
                        get("/agent-service/internal/short-link-agent/v1/health")
                                .contextPath("/agent-service"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredInternalTokenRejectsWrongHeader() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties
                .getSecurity()
                .setInternalToken(com.jupiter.shortlink.agent.StatsTestFixtures.SECRET);
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(new HealthController())
                        .addFilters(new InternalAgentApiFilter(properties))
                        .build();

        mockMvc.perform(
                        get("/internal/short-link-agent/v1/health")
                                .header("X-Agent-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredInternalTokenAllowsMatchingHeader() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties
                .getSecurity()
                .setInternalToken(com.jupiter.shortlink.agent.StatsTestFixtures.SECRET);
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(new HealthController())
                        .addFilters(new InternalAgentApiFilter(properties))
                        .build();

        mockMvc.perform(
                        get("/internal/short-link-agent/v1/health")
                                .header(
                                        "X-Agent-Internal-Token",
                                        com.jupiter.shortlink.agent.StatsTestFixtures.SECRET))
                .andExpect(status().isOk());
    }
}
