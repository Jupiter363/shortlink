package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import com.nageoffer.shortlink.agent.riskprofile.service.ShortLinkRiskProfileService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RiskProfileLeaseGuardContractTest {

    @Test
    void productionBatchAndRepositoryApisDoNotExposeUnfencedProfileWrites() {
        assertThat(publicMethods(RiskProfileBatchService.class, "runOnce"))
                .noneMatch(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[]{Instant.class}));
        assertThat(publicMethods(ShortLinkRiskProfileService.class, "generateProfile"))
                .allMatch(method -> method.getParameterCount() == 4
                        && method.getParameterTypes()[3] == String.class);
        assertRepositoryWriteContract(JdbcShortLinkRiskProfileRepository.class);
        assertRepositoryWriteContract(JdbcGroupRiskProfileRepository.class);
    }

    private void assertRepositoryWriteContract(Class<?> repositoryType) {
        assertThat(publicMethods(repositoryType, "save")).isEmpty();
        assertThat(publicMethods(repositoryType, "saveIfLeaseOwned"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .endsWith(String.class, LocalDateTime.class));
    }

    private Method[] publicMethods(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals(name))
                .toArray(Method[]::new);
    }
}
