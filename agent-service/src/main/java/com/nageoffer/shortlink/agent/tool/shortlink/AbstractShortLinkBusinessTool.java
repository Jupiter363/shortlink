package com.nageoffer.shortlink.agent.tool.shortlink;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolDescriptor;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

abstract class AbstractShortLinkBusinessTool implements AgentTool {

    protected final ShortLinkBusinessGateway gateway;

    private final ToolDescriptor descriptor;

    protected AbstractShortLinkBusinessTool(
            ShortLinkBusinessGateway gateway,
            String name,
            String description,
            Map<String, Object> inputSchema
    ) {
        this.gateway = gateway;
        this.descriptor = new ToolDescriptor(name, description, inputSchema);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    protected ToolResult get(String path, ToolContext context, Map<String, Object> queryParams) {
        return gateway.get(path, context, queryParams);
    }

    /**
     * Executes the legacy harness method while keeping request identity outside
     * the model-visible tool arguments.  Spring AI supplies the username through
     * its trusted ToolContext and the HTTP gateway forwards it in the internal
     * username header.  The admin endpoint remains the source of truth for gid
     * ownership validation.
     */
    protected ToolResult executeFromSpringContext(
            org.springframework.ai.chat.model.ToolContext springContext,
            Map<String, Object> arguments
    ) {
        ToolContext context = ToolContext.fromSpringContext(springContext, arguments);
        if (context.username() == null || context.username().isBlank()) {
            return ToolResult.failure("Missing trusted tool context username");
        }
        return execute(context);
    }

    protected String requiredText(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    protected ToolResult missing(String name) {
        return ToolResult.failure("Missing required argument: " + name);
    }

    protected Long positiveLong(Map<String, Object> arguments, String name, long defaultValue) {
        Object value = arguments.get(name);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    protected ToolResult invalidPositiveLong(String name) {
        return ToolResult.failure("Argument must be a positive number: " + name);
    }

    protected void putIfText(Map<String, Object> queryParams, String name, Object value) {
        if (value != null && !value.toString().isBlank()) {
            queryParams.put(name, value.toString());
        }
    }

    protected Map<String, Object> arguments(Object... keyValues) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                arguments.put(String.valueOf(keyValues[index]), value);
            }
        }
        return arguments;
    }

    protected Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    protected Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    protected Map<String, Object> integerProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }
}
