package com.nageoffer.shortlink.agent.harness.action.executor;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentActionExecutorRegistry {

    private final Map<AgentActionType, AgentActionExecutor> executors;

    public AgentActionExecutorRegistry(List<AgentActionExecutor> executors) {
        Map<AgentActionType, AgentActionExecutor> index = new LinkedHashMap<>();
        if (executors != null) {
            for (AgentActionExecutor executor : executors) {
                if (executor == null) {
                    throw new IllegalArgumentException("Agent action executor must not be null");
                }
                AgentActionType actionType = executor.actionType();
                if (actionType == null) {
                    throw new IllegalArgumentException("Agent action executor type must not be null");
                }
                if (index.putIfAbsent(actionType, executor) != null) {
                    throw new IllegalArgumentException("Duplicate agent action executor type");
                }
            }
        }
        this.executors = Map.copyOf(index);
    }

    public Optional<AgentActionExecutor> findByType(AgentActionType actionType) {
        return Optional.ofNullable(actionType).map(executors::get);
    }
}
