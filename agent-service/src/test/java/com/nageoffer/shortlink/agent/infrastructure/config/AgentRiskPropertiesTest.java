package com.nageoffer.shortlink.agent.infrastructure.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRiskPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(ActionPropertiesConfiguration.class);

    @Test
    void actionMaintenanceDefaultsAreConservative() {
        AgentProperties properties = new AgentProperties();

        assertThat(properties.getAction().getExecutionLeaseSeconds()).isEqualTo(300);
        assertThat(properties.getAction().getRecoveryIntervalMillis()).isEqualTo(60_000L);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void actionMaintenanceBoundsAreInclusive() {
        AgentProperties minimums = new AgentProperties();
        minimums.getAction().setExecutionLeaseSeconds(1);
        minimums.getAction().setRecoveryIntervalMillis(1_000L);
        AgentProperties maximums = new AgentProperties();
        maximums.getAction().setExecutionLeaseSeconds(86_400);
        maximums.getAction().setRecoveryIntervalMillis(3_600_000L);

        assertThat(validator.validate(minimums)).isEmpty();
        assertThat(validator.validate(maximums)).isEmpty();
    }

    @Test
    void actionMaintenanceRejectsNullAndOutOfRangeValues() {
        assertActionViolation(
                action -> action.setExecutionLeaseSeconds(0),
                "action.executionLeaseSeconds"
        );
        assertActionViolation(
                action -> action.setExecutionLeaseSeconds(-1),
                "action.executionLeaseSeconds"
        );
        assertActionViolation(
                action -> action.setExecutionLeaseSeconds(86_401),
                "action.executionLeaseSeconds"
        );
        assertActionViolation(
                action -> action.setRecoveryIntervalMillis(0),
                "action.recoveryIntervalMillis"
        );
        assertActionViolation(
                action -> action.setRecoveryIntervalMillis(-1),
                "action.recoveryIntervalMillis"
        );
        assertActionViolation(
                action -> action.setRecoveryIntervalMillis(999),
                "action.recoveryIntervalMillis"
        );
        assertActionViolation(
                action -> action.setRecoveryIntervalMillis(3_600_001L),
                "action.recoveryIntervalMillis"
        );

        AgentProperties missingAction = new AgentProperties();
        missingAction.setAction(null);
        assertThat(validator.validate(missingAction))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("action");
    }

    @Test
    void invalidActionConfigurationFailsApplicationContextStartup() {
        contextRunner
                .withPropertyValues("short-link.agent.action.execution-lease-seconds=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Could not bind properties");
                });
    }

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

    private void assertActionViolation(
            Consumer<AgentProperties.Action> mutation,
            String propertyPath
    ) {
        AgentProperties properties = new AgentProperties();
        mutation.accept(properties.getAction());
        Set<ConstraintViolation<AgentProperties>> violations = validator.validate(properties);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(propertyPath);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class ActionPropertiesConfiguration {
    }
}
