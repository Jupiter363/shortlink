package com.nageoffer.shortlink.agent.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {

    @Test
    void defaultPropertiesUseDeepSeekV4FlashAndAgentConsole() {
        AgentProperties agentProperties = new AgentProperties();
        DeepSeekProperties deepSeekProperties = new DeepSeekProperties();

        assertThat(agentProperties.getGraph().getName()).isEqualTo("campaign-analysis-graph");
        assertThat(agentProperties.getGraph().isCheckpointEnabled()).isTrue();
        assertThat(agentProperties.getConsole().isEnabled()).isTrue();
        assertThat(agentProperties.getConsole().getBasePath()).isEqualTo("/agent-console");
        assertThat(agentProperties.getBusiness().getBaseUrl()).isEqualTo("http://127.0.0.1:8002");
        assertThat(agentProperties.getBusiness().getInternalToken()).isEmpty();
        assertThat(agentProperties.getSecurity().getInternalToken()).isEmpty();
        assertThat(deepSeekProperties.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(deepSeekProperties.getModel()).isEqualTo("deepseek-v4-flash");
    }
}
