package com.nageoffer.shortlink.agent.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    void applicationYamlExposesFailedRecoveryLimitEnvironmentOverride() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "agent-application",
                new ClassPathResource("application.yaml")
        );

        assertThat(sources)
                .extracting(source -> source.getProperty(
                        "short-link.agent.risk.profile.failed-recovery-limit"
                ))
                .contains("${RISK_PROFILE_FAILED_RECOVERY_LIMIT:3}");
    }

    @Test
    void binderResolvesFailedRecoveryLimitFromEnvironmentVariable() throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-environment",
                Map.of("RISK_PROFILE_FAILED_RECOVERY_LIMIT", "9")
        ));
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader().load(
                "agent-application",
                new ClassPathResource("application.yaml")
        );
        yamlSources.forEach(environment.getPropertySources()::addLast);

        AgentProperties properties = Binder.get(environment)
                .bind("short-link.agent", Bindable.of(AgentProperties.class))
                .orElseThrow(() -> new AssertionError("Agent properties were not bound"));

        assertThat(properties.getRisk().getProfile().getFailedRecoveryLimit()).isEqualTo(9);
    }
}
