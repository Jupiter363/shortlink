package com.nageoffer.shortlink.agent.tool.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolDescriptor;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Indexes the generated Spring AI callbacks by stable tool name.
 *
 * <p>The list-based constructor and {@link #findByName(String)} are retained as
 * a small source-compatible bridge for existing graph code while nodes migrate
 * to callbacks. In the application context Spring uses the provider constructor
 * and therefore the five {@code @Tool} methods are the canonical implementations.</p>
 */
@Component
public class AgentToolRegistry {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, AgentTool> toolsByName;

    private final Map<String, ToolCallback> callbacksByName;

    private final List<ToolDescriptor> descriptors;

    private final List<ToolCallback> callbacks;

    @Autowired
    public AgentToolRegistry(MethodToolCallbackProvider callbackProvider) {
        RegistrySnapshot snapshot = fromCallbacks(callbackProvider == null
                ? new ToolCallback[0]
                : callbackProvider.getToolCallbacks());
        this.toolsByName = snapshot.toolsByName();
        this.callbacksByName = snapshot.callbacksByName();
        this.descriptors = snapshot.descriptors();
        this.callbacks = snapshot.callbacks();
    }

    /**
     * Compatibility constructor used by focused unit tests and older graph
     * implementations. Production wiring uses the MethodToolCallbackProvider
     * constructor above.
     */
    public AgentToolRegistry(List<AgentTool> tools) {
        RegistrySnapshot snapshot = fromLegacyTools(safeTools(tools));
        this.toolsByName = snapshot.toolsByName();
        this.callbacksByName = snapshot.callbacksByName();
        this.descriptors = snapshot.descriptors();
        this.callbacks = snapshot.callbacks();
    }

    public List<ToolDescriptor> descriptors() {
        return descriptors;
    }

    /** Returns the callback generated for a tool name. */
    public Optional<ToolCallback> findCallbackByName(String name) {
        return Optional.ofNullable(callbacksByName.get(name));
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /**
     * Legacy adapter lookup. Callback-backed adapters preserve the HTTP gateway
     * and trusted context behavior while callers transition to ToolCallback.
     */
    public Optional<AgentTool> findByName(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    private RegistrySnapshot fromCallbacks(ToolCallback[] generatedCallbacks) {
        Map<String, AgentTool> indexedTools = new LinkedHashMap<>();
        Map<String, ToolCallback> indexedCallbacks = new LinkedHashMap<>();
        List<ToolDescriptor> indexedDescriptors = new ArrayList<>();
        List<ToolCallback> callbackList = new ArrayList<>();
        for (ToolCallback callback : generatedCallbacks == null ? new ToolCallback[0] : generatedCallbacks) {
            if (callback == null || callback.getToolDefinition() == null) {
                continue;
            }
            String name = callback.getToolDefinition().name();
            if (indexedCallbacks.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate agent tool name: " + name);
            }
            ToolDescriptor descriptor = descriptor(callback);
            indexedCallbacks.put(name, callback);
            indexedDescriptors.add(descriptor);
            callbackList.add(callback);
            indexedTools.put(name, new CallbackBackedAgentTool(callback, descriptor));
        }
        return new RegistrySnapshot(
                Collections.unmodifiableMap(indexedTools),
                Collections.unmodifiableMap(indexedCallbacks),
                List.copyOf(indexedDescriptors),
                List.copyOf(callbackList)
        );
    }

    private RegistrySnapshot fromLegacyTools(List<AgentTool> tools) {
        Map<String, AgentTool> indexedTools = new LinkedHashMap<>();
        List<ToolDescriptor> indexedDescriptors = new ArrayList<>();
        for (AgentTool tool : tools) {
            ToolDescriptor descriptor = tool.descriptor();
            String toolName = descriptor.name();
            if (indexedTools.containsKey(toolName)) {
                throw new IllegalArgumentException("Duplicate agent tool name: " + toolName);
            }
            indexedTools.put(toolName, tool);
            indexedDescriptors.add(descriptor);
        }
        return new RegistrySnapshot(
                Collections.unmodifiableMap(indexedTools),
                Map.of(),
                List.copyOf(indexedDescriptors),
                List.of()
        );
    }

    private ToolDescriptor descriptor(ToolCallback callback) {
        var definition = callback.getToolDefinition();
        return new ToolDescriptor(
                definition.name(),
                definition.description(),
                parseSchema(definition.inputSchema())
        );
    }

    private Map<String, Object> parseSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(schema, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of("rawSchema", schema);
        }
    }

    private List<AgentTool> safeTools(List<AgentTool> tools) {
        return tools == null ? List.of() : tools;
    }

    private record RegistrySnapshot(
            Map<String, AgentTool> toolsByName,
            Map<String, ToolCallback> callbacksByName,
            List<ToolDescriptor> descriptors,
            List<ToolCallback> callbacks
    ) {
    }

    private static final class CallbackBackedAgentTool implements AgentTool {

        private final ToolCallback callback;

        private final ToolDescriptor descriptor;

        private CallbackBackedAgentTool(ToolCallback callback, ToolDescriptor descriptor) {
            this.callback = callback;
            this.descriptor = descriptor;
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                String input = OBJECT_MAPPER.writeValueAsString(context == null ? Map.of() : context.arguments());
                Map<String, Object> trustedContext = new LinkedHashMap<>();
                if (context != null) {
                    trustedContext.put(ToolContext.SESSION_ID_KEY, context.sessionId());
                    trustedContext.put(ToolContext.USERNAME_KEY, context.username());
                }
                String raw = callback.call(
                        input,
                        new org.springframework.ai.chat.model.ToolContext(trustedContext)
                );
                if (raw == null || raw.isBlank()) {
                    return ToolResult.failure("Agent tool returned an empty response");
                }
                return OBJECT_MAPPER.readValue(raw, ToolResult.class);
            } catch (Exception ex) {
                return ToolResult.failure(ex.getMessage() == null ? "Agent tool callback failed" : ex.getMessage());
            }
        }
    }
}
