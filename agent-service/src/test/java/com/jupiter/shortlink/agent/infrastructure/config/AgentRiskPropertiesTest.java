package com.jupiter.shortlink.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

class AgentRiskPropertiesTest {

    @Test
    void defaultRiskPropertiesMatchRiskProfilePolicyPlan() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.getRisk().getHashSalt()).isEmpty();
        assertThat(properties.getRisk().getProfile().getBatchIntervalMinutes()).isEqualTo(120);
        assertThat(properties.getRisk().getProfile().getActiveScanDays()).isEqualTo(7);
        assertThat(properties.getRisk().getProfile().getTopCandidateSize()).isEqualTo(10);
        assertThat(properties.getRisk().getProfile().getFailedRecoveryLimit()).isEqualTo(3);
        assertThat(properties.getRisk().getAutoAction().isLimitRateEnabled()).isTrue();
        assertThat(properties.getRisk().getAutoAction().getLimitRateMinScore()).isEqualTo(80);
        assertThat(properties.getRisk().getAutoAction().getLimitRateLimit()).isEqualTo(60);
        assertThat(properties.getRisk().getAutoAction().getLimitRateWindowSeconds()).isEqualTo(60);
        assertThat(properties.getRisk().getRedis().getKeyPrefix()).isEqualTo("risk");
    }

    @Test
    void productionPropertiesExposeFailedRecoveryLimitEnvironmentOverride() throws IOException {
        List<PropertySource<?>> sources =
                new PropertiesPropertySourceLoader()
                        .load(
                                "agent-application",
                                new ClassPathResource("application-production.properties"));

        assertThat(sources)
                .extracting(
                        source ->
                                source.getProperty(
                                        "short-link.agent.risk.profile.failed-recovery-limit"))
                .contains("${RISK_PROFILE_FAILED_RECOVERY_LIMIT:3}");
    }

    @Test
    void binderResolvesFailedRecoveryLimitFromEnvironmentVariable() throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "test-environment",
                                Map.of("RISK_PROFILE_FAILED_RECOVERY_LIMIT", "9")));
        List<PropertySource<?>> sources =
                new PropertiesPropertySourceLoader()
                        .load(
                                "agent-application",
                                new ClassPathResource("application-production.properties"));
        sources.forEach(environment.getPropertySources()::addLast);

        AgentProperties properties = new AgentProperties();
        Binder.get(environment)
                .bind(
                        "short-link.agent.risk.profile",
                        Bindable.ofInstance(properties.getRisk().getProfile()))
                .orElseThrow(() -> new AssertionError("Agent properties were not bound"));

        assertThat(properties.getRisk().getProfile().getFailedRecoveryLimit()).isEqualTo(9);
    }
}
