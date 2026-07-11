package com.nageoffer.shortlink.agent.infrastructure.config;

import com.nageoffer.shortlink.agent.harness.action.repository.JdbcAgentPendingActionRepository;
import com.nageoffer.shortlink.agent.harness.action.scheduler.AgentPendingActionRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBeanConstructorContractTest {

    @Test
    void pendingActionRecoverySchedulerMarksOnlyItsProductionConstructorAutowired() {
        Constructor<?>[] constructors =
                AgentPendingActionRecoveryScheduler.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(2);
        assertThat(autowiredConstructors(AgentPendingActionRecoveryScheduler.class))
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(JdbcAgentPendingActionRepository.class));
    }

    @Test
    void multiConstructorSpringBeansDeclareExactlyOneAutowiredConstructor() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> violations = scanner
                .findCandidateComponents("com.nageoffer.shortlink.agent")
                .stream()
                .map(definition -> loadClass(definition.getBeanClassName()))
                .filter(type -> type.getDeclaredConstructors().length > 1)
                .filter(type -> autowiredConstructors(type).size() != 1)
                .map(type -> type.getName() + " has "
                        + type.getDeclaredConstructors().length
                        + " constructors and "
                        + autowiredConstructors(type).size()
                        + " @Autowired constructors")
                .sorted()
                .toList();

        assertThat(violations).isEmpty();
    }

    private List<Constructor<?>> autowiredConstructors(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toList();
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Spring bean class could not be loaded: " + className, ex);
        }
    }
}
