package com.jupiter.shortlink.agent.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.harness.tool.AgentTool;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolDescriptor;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class AgentToolRegistryTest {

    @Test
    void callbackAdapterPreservesTrustedPrincipalWhileRejectingModelIdentityArguments() {
        var gateway =
                org.mockito.Mockito.mock(
                        com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway
                                .class);
        var tool = new com.jupiter.shortlink.agent.tool.shortlink.ListGroupsTool(gateway);
        org.mockito.Mockito.when(
                        gateway.get(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ToolResult.success(List.of()));
        var provider =
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                        .toolObjects(tool)
                        .build();
        var registry = new AgentToolRegistry(provider);
        var principal = com.jupiter.shortlink.agent.StatsTestFixtures.PRINCIPAL;
        var result =
                registry.findByName("list_groups")
                        .orElseThrow()
                        .execute(
                                new ToolContext(
                                        "s1",
                                        principal.username(),
                                        Map.of(
                                                "username",
                                                "mallory",
                                                "tenantId",
                                                "999",
                                                "authVersion",
                                                99),
                                        principal));
        assertThat(result.success()).isTrue();
        var captured = org.mockito.ArgumentCaptor.forClass(ToolContext.class);
        org.mockito.Mockito.verify(gateway)
                .get(
                        org.mockito.ArgumentMatchers.anyString(),
                        captured.capture(),
                        org.mockito.ArgumentMatchers.anyMap());
        assertThat(captured.getValue().principal()).isEqualTo(principal);
        assertThat(captured.getValue().username()).isEqualTo(principal.username());
        assertThat(captured.getValue().arguments())
                .doesNotContainKeys("tenantId", "username", "authVersion");
    }

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
            private final ToolDescriptor descriptor =
                    new ToolDescriptor(name, description, Map.of());

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
