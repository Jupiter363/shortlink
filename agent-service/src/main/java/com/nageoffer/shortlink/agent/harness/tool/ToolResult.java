package com.nageoffer.shortlink.agent.harness.tool;

public record ToolResult(
        boolean success,
        Object data,
        String message
) {

    public static ToolResult success(Object data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(false, null, message);
    }
}
