package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionExecutor;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyActionRegistrationGateTest {

    @Test
    void phase02cExposesExecutorConfigurationWithoutAnnotatingExecutorAsComponent() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);

        var candidates = scanner.findCandidateComponents(
                "com.nageoffer.shortlink.agent.riskpolicy.action"
        );
        assertThat(candidates)
                .extracting(candidate -> candidate.getBeanClassName())
                .contains(RiskPolicyActionConfiguration.class.getName());
        assertThat(candidates).noneMatch(candidate -> isRiskExecutor(candidate.getBeanClassName()));
    }

    private boolean isRiskExecutor(String className) {
        try {
            Class<?> candidateType = Class.forName(Objects.requireNonNull(className));
            return RiskPolicyActionExecutor.class.isAssignableFrom(candidateType);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(ex);
        }
    }
}
