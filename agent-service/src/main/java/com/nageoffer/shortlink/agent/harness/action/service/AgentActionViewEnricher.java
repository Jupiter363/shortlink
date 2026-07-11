package com.nageoffer.shortlink.agent.harness.action.service;

import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;

import java.util.Map;

@FunctionalInterface
public interface AgentActionViewEnricher {

    Map<String, Object> enrich(
            AgentPendingAction action,
            Map<String, Object> safeResult
    );
}
