package com.nageoffer.shortlink.agent.harness.tool;

import java.util.Map;

public record ToolContext(
        String sessionId,
        String username,
        Map<String, Object> arguments
) {

    public ToolContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
