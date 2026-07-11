package com.nageoffer.shortlink.agent.harness.runtime;

import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;

import java.util.List;

public record AgentRunResult(
        String sessionId,
        String traceId,
        String answer,
        List<Object> cards,
        List<AgentPendingActionView> pendingActions,
        List<Object> toolCalls,
        List<Object> dataSources,
        List<Object> traceEvents,
        List<String> warnings
) {
}
