package com.nageoffer.shortlink.agent.harness.tool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarnessToolContractTest {

    @Test
    void toolContextCopiesArgumentsForHarnessToolCalls() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("gid", "g1");

        ToolContext context = new ToolContext("session-1", "zhangsan", arguments);
        arguments.put("gid", "g2");

        assertThat(context.arguments()).containsEntry("gid", "g1");
        assertThatThrownBy(() -> context.arguments().put("gid", "g3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolDescriptorRequiresStableName() {
        assertThatThrownBy(() -> new ToolDescriptor(" ", "desc", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool descriptor name must not be blank");
    }

    @Test
    void agentToolContractLivesInHarnessToolPackage() {
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("test_tool", "test", Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return ToolResult.success(context.arguments());
            }
        };

        assertThat(tool.descriptor().name()).isEqualTo("test_tool");
        assertThat(tool.execute(new ToolContext("session-1", "zhangsan", Map.of("gid", "g1"))).success())
                .isTrue();
    }
}
