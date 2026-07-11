package com.nageoffer.shortlink.agent.harness.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutor;
import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutorRegistry;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionConfiguration;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPort;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionTypes;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActionExecutorRegistryTest {

    private static final AgentActionType DISABLE =
            new AgentActionType("risk.disable-short-link");
    private static final AgentActionType BLOCK_IP = new AgentActionType("risk.block-ip");

    @Test
    void indexesExecutorsByActionTypeAndReturnsEmptyForUnknownType() {
        AgentActionExecutor disable = executor(DISABLE);
        AgentActionExecutor blockIp = executor(BLOCK_IP);

        AgentActionExecutorRegistry registry =
                new AgentActionExecutorRegistry(List.of(disable, blockIp));

        assertThat(registry.findByType(DISABLE)).containsSame(disable);
        assertThat(registry.findByType(BLOCK_IP)).containsSame(blockIp);
        assertThat(registry.findByType(new AgentActionType("campaign.pause-placement"))).isEmpty();
        assertThat(registry.findByType(null)).isEmpty();
    }

    @Test
    void nullListIsTreatedAsEmpty() {
        assertThat(new AgentActionExecutorRegistry(null).findByType(DISABLE)).isEmpty();
    }

    @Test
    void rejectsNullExecutorNullTypeAndDuplicateType() {
        assertThatThrownBy(() -> new AgentActionExecutorRegistry(Arrays.asList(
                executor(DISABLE), null
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentActionExecutorRegistry(List.of(executor(null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentActionExecutorRegistry(List.of(
                executor(DISABLE), executor(DISABLE)
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registersThreeRiskExecutorsAfterPolicyConsistencyIsAvailable() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(RiskPolicyActionPort.class, () -> command -> null);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(
                    RiskPolicyActionConfiguration.class,
                    AgentActionExecutorRegistry.class
            );
            context.refresh();

            AgentActionExecutorRegistry registry =
                    context.getBean(AgentActionExecutorRegistry.class);
            assertThat(context.getBeansOfType(AgentActionExecutor.class)).hasSize(3);
            assertThat(registry.findByType(RiskPolicyActionTypes.DISABLE_SHORT_LINK)).isPresent();
            assertThat(registry.findByType(RiskPolicyActionTypes.LIMIT_TIME_WINDOW)).isPresent();
            assertThat(registry.findByType(RiskPolicyActionTypes.BLOCK_IP)).isPresent();
        }
    }

    private AgentActionExecutor executor(AgentActionType actionType) {
        return new AgentActionExecutor() {
            @Override
            public AgentActionType actionType() {
                return actionType;
            }

            @Override
            public boolean replaySafe() {
                return true;
            }

            @Override
            public AgentActionExecutionResult execute(
                    AgentPendingAction action,
                    AgentActionExecutionContext context
            ) {
                return AgentActionExecutionResult.empty();
            }
        };
    }
}
