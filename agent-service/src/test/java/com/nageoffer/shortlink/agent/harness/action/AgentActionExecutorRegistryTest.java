package com.nageoffer.shortlink.agent.harness.action;

import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutor;
import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutorRegistry;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionTypes;
import org.junit.jupiter.api.Test;

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
    void phase02bDoesNotAutoRegisterRiskExecutors() {
        AgentActionExecutorRegistry registry = new AgentActionExecutorRegistry(List.of());

        assertThat(registry.findByType(RiskPolicyActionTypes.DISABLE_SHORT_LINK)).isEmpty();
        assertThat(registry.findByType(RiskPolicyActionTypes.LIMIT_TIME_WINDOW)).isEmpty();
        assertThat(registry.findByType(RiskPolicyActionTypes.BLOCK_IP)).isEmpty();
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
