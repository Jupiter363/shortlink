package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolDescriptor;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskToolPlanningNodeTest {

    @Test
    void planAndExecuteRunsShortLinkStatsAndAccessRecordsWithTypedArguments() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_short_link_stats",
                ToolResult.success(Map.of("pv", 10))
        );
        CapturingAgentTool recordsTool = new CapturingAgentTool(
                "get_group_access_records",
                ToolResult.success(Map.of("total", 1))
        );
        RiskToolPlanningNode node = new RiskToolPlanningNode(
                new AgentToolRegistry(List.of(statsTool, recordsTool)),
                new SecurityRiskSanitizer()
        );

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 fullShortUrl=http://s.com/a startDate=2026-07-01 endDate=2026-07-07 access current=2 size=5",
                "session-1",
                "zhangsan"
        );

        assertThat(output.get("visitedNodes")).isEqualTo(List.of("intake", "risk_tool_planning"));
        assertThat(output.get("toolWarnings")).isEqualTo(List.of());
        assertThat(output.get("toolExecutions").toString())
                .contains("get_short_link_stats")
                .contains("get_group_access_records");
        assertThat(statsTool.context.username()).isEqualTo("zhangsan");
        assertThat(statsTool.context.arguments())
                .containsEntry("gid", "g1")
                .containsEntry("fullShortUrl", "http://s.com/a")
                .containsEntry("current", 2L)
                .containsEntry("size", 5L);
        assertThat(recordsTool.context.arguments())
                .containsEntry("current", 2L)
                .containsEntry("size", 5L);
    }

    @Test
    void planAndExecuteDoesNotCallToolsWithoutGidAndDateRange() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 10))
        );
        RiskToolPlanningNode node = new RiskToolPlanningNode(
                new AgentToolRegistry(List.of(statsTool)),
                new SecurityRiskSanitizer()
        );

        Map<String, Object> output = node.planAndExecute("risk gid=g1", "session-1", "zhangsan");

        assertThat(output.get("toolExecutions")).isEqualTo(List.of());
        assertThat(statsTool.context).isNull();
    }

    @Test
    void planAndExecuteSanitizesToolFailureMessages() {
        ThrowingAgentTool statsTool = new ThrowingAgentTool(
                "get_group_stats",
                "backend failed ip=192.168.1.10 user=visitor-001 token=abc"
        );
        RiskToolPlanningNode node = new RiskToolPlanningNode(
                new AgentToolRegistry(List.of(statsTool)),
                new SecurityRiskSanitizer()
        );

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(output.get("toolExecutions").toString())
                .contains("192.168.*.*")
                .contains("user=***")
                .contains("token=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("abc");
    }

    private static class CapturingAgentTool implements AgentTool {

        private final String name;

        private final ToolResult result;

        private ToolContext context;

        private CapturingAgentTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, "test tool", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            this.context = context;
            return result;
        }
    }

    private static class ThrowingAgentTool implements AgentTool {

        private final String name;

        private final String message;

        private ThrowingAgentTool(String name, String message) {
            this.name = name;
            this.message = message;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, "throwing test tool", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            throw new IllegalStateException(message);
        }
    }
}
