package com.nageoffer.shortlink.agent.harness.tool;

import java.util.Map;

public record ToolDescriptor(
        String name,
        String description,
        Map<String, Object> inputSchema
) {

    public ToolDescriptor {
        name = requireText(name, "name");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tool descriptor " + fieldName + " must not be blank");
        }
        return value;
    }
}
