package com.nageoffer.shortlink.agent.harness.runtime;

import java.util.List;

public record AgentRunResult(
        String sessionId,
        String traceId,
        String answer,
        List<Object> cards,
        List<Object> pendingActions,
        List<Object> dataSources,
        List<String> warnings
) {
}
