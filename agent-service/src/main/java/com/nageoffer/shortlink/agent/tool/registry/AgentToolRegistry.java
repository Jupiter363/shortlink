package com.nageoffer.shortlink.agent.tool.registry;

import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> toolsByName;
    private final List<ToolDescriptor> descriptors;

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> indexedTools = new LinkedHashMap<>();
        List<ToolDescriptor> indexedDescriptors = new ArrayList<>();
        for (AgentTool tool : safeTools(tools)) {
            ToolDescriptor descriptor = tool.descriptor();
            String toolName = descriptor.name();
            if (indexedTools.containsKey(toolName)) {
                throw new IllegalArgumentException("Duplicate agent tool name: " + toolName);
            }
            indexedTools.put(toolName, tool);
            indexedDescriptors.add(descriptor);
        }
        this.toolsByName = Collections.unmodifiableMap(indexedTools);
        this.descriptors = List.copyOf(indexedDescriptors);
    }

    public List<ToolDescriptor> descriptors() {
        return descriptors;
    }

    public Optional<AgentTool> findByName(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    private List<AgentTool> safeTools(List<AgentTool> tools) {
        return tools == null ? List.of() : tools;
    }
}
