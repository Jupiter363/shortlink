package com.nageoffer.shortlink.agent.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRiskPropertiesTest {

    @Test
    void defaultRiskPropertiesMatchRiskProfilePolicyPlan() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.getRisk().getHashSalt()).isEmpty();
        assertThat(properties.getRisk().getProfile().getBatchIntervalMinutes()).isEqualTo(120);
        assertThat(properties.getRisk().getProfile().getActiveScanDays()).isEqualTo(7);
        assertThat(properties.getRisk().getProfile().getTopCandidateSize()).isEqualTo(10);
        assertThat(properties.getRisk().getAutoAction().isLimitRateEnabled()).isTrue();
        assertThat(properties.getRisk().getAutoAction().getLimitRateMinScore()).isEqualTo(80);
        assertThat(properties.getRisk().getAutoAction().getLimitRateLimit()).isEqualTo(60);
        assertThat(properties.getRisk().getAutoAction().getLimitRateWindowSeconds()).isEqualTo(60);
        assertThat(properties.getRisk().getRedis().getKeyPrefix()).isEqualTo("risk");
    }
}
