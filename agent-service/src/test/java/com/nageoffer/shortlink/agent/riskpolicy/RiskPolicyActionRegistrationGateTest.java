package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyActionRegistrationGateTest {

    @Test
    void phase02bDoesNotExposeRiskPolicyExecutorAsComponentCandidate() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);

        assertThat(scanner.findCandidateComponents(
                "com.nageoffer.shortlink.agent.riskpolicy.action"
        )).noneMatch(candidate -> isRiskExecutor(candidate.getBeanClassName()));
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
