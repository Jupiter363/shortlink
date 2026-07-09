package com.nageoffer.shortlink.agent.tool.registry;

import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolDescriptor;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    @Test
    void registersUniqueToolNamesAndListsDescriptors() {
        AgentTool analysisTool = tool("campaign.analysis", "Analyze campaign metrics");
        AgentTool reportTool = tool("campaign.report", "Build campaign report");

        AgentToolRegistry registry = new AgentToolRegistry(List.of(analysisTool, reportTool));

        assertThat(registry.descriptors())
                .extracting(ToolDescriptor::name)
                .containsExactly("campaign.analysis", "campaign.report");
    }

    @Test
    void findsToolByName() {
        AgentTool analysisTool = tool("campaign.analysis", "Analyze campaign metrics");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(analysisTool));

        Optional<AgentTool> foundTool = registry.findByName("campaign.analysis");

        assertThat(foundTool).containsSame(analysisTool);
        assertThat(registry.findByName("missing.tool")).isEmpty();
    }

    @Test
    void rejectsDuplicateToolNames() {
        AgentTool firstTool = tool("campaign.analysis", "Analyze campaign metrics");
        AgentTool duplicateTool = tool("campaign.analysis", "Duplicate campaign metrics");

        assertThatThrownBy(() -> new AgentToolRegistry(List.of(firstTool, duplicateTool)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate agent tool name: campaign.analysis");
    }

    private AgentTool tool(String name, String description) {
        return new AgentTool() {
            private final ToolDescriptor descriptor = new ToolDescriptor(name, description, Map.of());

            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return ToolResult.success(Map.of("tool", name));
            }
        };
    }
}
