package com.nageoffer.shortlink.agent.harness.action.executor;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;

public interface AgentActionExecutor {

    AgentActionType actionType();

    boolean replaySafe();

    AgentActionExecutionResult execute(
            AgentPendingAction action,
            AgentActionExecutionContext context
    );
}
