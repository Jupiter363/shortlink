package com.jupiter.shortlink.agent.securityriskagent.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.harness.tool.AgentTool;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolDescriptor;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class RiskToolPlanningNodeTest {

    @Test
    void planAndExecuteRunsShortLinkStatsAndAccessRecordsWithTypedArguments() {
        CapturingAgentTool statsTool =
                new CapturingAgentTool(
                        "get_short_link_stats", ToolResult.success(Map.of("pv", 10)));
        CapturingAgentTool recordsTool =
                new CapturingAgentTool(
                        "get_group_access_records", ToolResult.success(Map.of("total", 1)));
        RiskToolPlanningNode node =
                new RiskToolPlanningNode(
                        new AgentToolRegistry(List.of(statsTool, recordsTool)),
                        new SecurityRiskSanitizer());

        Map<String, Object> output =
                node.planAndExecute(
                        "risk gid=g1 fullShortUrl=http://s.com/a startDate=2026-07-01"
                            + " endDate=2026-07-07 access current=2 size=5",
                        "session-1",
                        "zhangsan");

        assertThat(output.get("visitedNodes")).isEqualTo(List.of("intake", "risk_tool_planning"));
        assertThat(output.get("toolWarnings")).isEqualTo(List.of());
        assertThat(output.get("evidenceStatus")).isEqualTo("AVAILABLE");
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
        CapturingAgentTool statsTool =
                new CapturingAgentTool("get_group_stats", ToolResult.success(Map.of("pv", 10)));
        RiskToolPlanningNode node =
                new RiskToolPlanningNode(
                        new AgentToolRegistry(List.of(statsTool)), new SecurityRiskSanitizer());

        Map<String, Object> output = node.planAndExecute("risk gid=g1", "session-1", "zhangsan");

        assertThat(output.get("toolExecutions")).isEqualTo(List.of());
        assertThat(output.get("evidenceRequested")).isEqualTo(false);
        assertThat(output.get("evidenceStatus")).isEqualTo("NOT_REQUESTED");
        assertThat(statsTool.context).isNull();
    }

    @Test
    void planAndExecuteRecordsFailedExecutionWhenPlannedToolIsNotRegistered() {
        RiskToolPlanningNode node =
                new RiskToolPlanningNode(
                        new AgentToolRegistry(List.of()), new SecurityRiskSanitizer());

        Map<String, Object> output =
                node.planAndExecute(
                        "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                        "session-1",
                        "zhangsan");

        assertThat((List<?>) output.get("toolExecutions"))
                .singleElement()
                .satisfies(
                        execution -> {
                            Map<?, ?> executionMap = (Map<?, ?>) execution;
                            assertThat(executionMap.get("name")).isEqualTo("get_group_stats");
                            assertThat(executionMap.get("success")).isEqualTo(false);
                            assertThat(executionMap.get("message"))
                                    .isEqualTo("Agent tool is not registered");
                        });
        assertThat(output.get("toolWarnings").toString())
                .contains("Agent tool get_group_stats failed")
                .contains("Agent tool is not registered");
        assertThat(output.get("evidenceStatus")).isEqualTo("SOURCE_FAILURE");
    }

    @ParameterizedTest
    @MethodSource("emptyToolData")
    void planAndExecutePreservesSuccessfulEmptyToolDataAsNoDataEvidence(Object emptyData) {
        CapturingAgentTool statsTool =
                new CapturingAgentTool("get_group_stats", ToolResult.success(emptyData));
        RiskToolPlanningNode node =
                new RiskToolPlanningNode(
                        new AgentToolRegistry(List.of(statsTool)), new SecurityRiskSanitizer());

        Map<String, Object> output =
                node.planAndExecute(
                        "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                        "session-1",
                        "zhangsan");

        assertThat((List<?>) output.get("toolExecutions"))
                .singleElement()
                .satisfies(
                        execution -> {
                            Map<?, ?> executionMap = (Map<?, ?>) execution;
                            assertThat(executionMap.get("name")).isEqualTo("get_group_stats");
                            assertThat(executionMap.get("success")).isEqualTo(true);
                            assertThat(executionMap.get("data")).isEqualTo(emptyData);
                            assertThat(executionMap.containsKey("message")).isFalse();
                        });
        assertThat(output.get("toolWarnings")).isEqualTo(List.of());
        assertThat(output.get("evidenceStatus")).isEqualTo("NO_DATA");
    }

    @Test
    void planAndExecuteSanitizesToolFailureMessages() {
        ThrowingAgentTool statsTool =
                new ThrowingAgentTool(
                        "get_group_stats",
                        "backend failed ip=192.168.1.10 user=visitor-001 token=abc");
        RiskToolPlanningNode node =
                new RiskToolPlanningNode(
                        new AgentToolRegistry(List.of(statsTool)), new SecurityRiskSanitizer());

        Map<String, Object> output =
                node.planAndExecute(
                        "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                        "session-1",
                        "zhangsan");

        assertThat(output.get("toolExecutions").toString())
                .contains("192.168.*.*")
                .contains("user=***")
                .contains("token=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("abc");
        assertThat(output.get("toolWarnings").toString())
                .contains("Agent tool get_group_stats failed")
                .contains("192.168.*.*")
                .contains("user=***")
                .contains("token=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("abc");
    }

    @ParameterizedTest
    @MethodSource("namedGroupDates")
    void authorizedNamedGroupWithoutProfileReadsStatisticsAndBoundedFirstRecordPage(
            String date, String expectedStart, String expectedEnd, boolean rollingDay) {
        CapturingAgentTool stats = new CapturingAgentTool("get_group_stats", ToolResult.success(Map.of("pv", 3)));
        CapturingAgentTool records = new CapturingAgentTool("get_group_access_records", ToolResult.success(Map.of("items", List.of())));
        RiskToolPlanningNode node = new RiskToolPlanningNode(new AgentToolRegistry(List.of(stats, records)),
                new SecurityRiskSanitizer(), Clock.fixed(Instant.parse("2026-09-14T08:15:00Z"), ZoneOffset.UTC));

        Map<String, Object> output = node.apply(new OverAllState(Map.of(
                "message", "诊断分组‘UAT统计维度样本’" + date + "的访问明细，只读说明地区和ISP current=2 size=10000",
                "sessionId", "named-scope", "username", "zhangsan",
                "principal", StatsTestFixtures.PRINCIPAL.toState(),
                "profileScopeStatus", "NO_PROFILE", "authorizedStatisticsGid", "uat-group")));

        assertThat(stats.context.arguments()).containsEntry("gid", "uat-group")
                .containsEntry("startDate", expectedStart).containsEntry("endDate", expectedEnd);
        assertThat(stats.context.principal()).isEqualTo(StatsTestFixtures.PRINCIPAL);
        assertThat(records.context.arguments()).containsEntry("current", 1L).containsEntry("size", 10L)
                .doesNotContainKeys("snapshotId", "cursor");
        assertThat(output.get("statisticsEvidenceRequested")).isEqualTo(true);
        assertThat(output.get("evidenceStatus")).isEqualTo("AVAILABLE");
        assertThat(output.get("statisticsScopeNotice").toString()).contains(expectedStart, expectedEnd);
        assertThat(output.get("statisticsScopeNotice").toString().contains("并非精确滚动24小时"))
                .isEqualTo(rollingDay);
    }

    @Test
    void unresolvedGroupCannotReuseAStaleResolvedScopeOrQueryExplicitArguments() {
        CapturingAgentTool stats = new CapturingAgentTool("get_group_stats", ToolResult.success(Map.of("pv", 3)));
        RiskToolPlanningNode node = new RiskToolPlanningNode(new AgentToolRegistry(List.of(stats)), new SecurityRiskSanitizer());
        Map<String, Object> output = node.apply(new OverAllState(Map.of(
                "message", "未知分组 gid=other startDate=2026-09-13 endDate=2026-09-13",
                "profileScopeStatus", "MISSING", "authorizedStatisticsGid", "stale-group")));
        assertThat(stats.context).isNull();
        assertThat(output.get("toolExecutions")).isEqualTo(List.of());
        assertThat(output.get("statisticsEvidenceRequested")).isEqualTo(false);
    }

    @Test
    void structuredBatchNeverAddsAnOnlineStatisticsQuery() {
        CapturingAgentTool stats = new CapturingAgentTool("get_group_stats", ToolResult.success(Map.of("pv", 3)));
        RiskToolPlanningNode node = new RiskToolPlanningNode(new AgentToolRegistry(List.of(stats)), new SecurityRiskSanitizer());
        Map<String, Object> output = node.apply(new OverAllState(Map.of(
                "message", "gid=g1 startDate=2026-09-13 endDate=2026-09-13",
                "analysisInput", Map.of("batchId", "risk-profile:fixture"),
                "authorizedStatisticsGid", "g1", "profileScopeStatus", "EXPLICIT")));
        assertThat(stats.context).isNull();
        assertThat(output.get("toolExecutions")).isEqualTo(List.of());
        assertThat(output.get("statisticsEvidenceRequested")).isEqualTo(false);
    }

    @ParameterizedTest
    @MethodSource("invalidNamedGroupDates")
    void invalidOrUnboundedDatesDoNotCallStatistics(String date) {
        CapturingAgentTool stats = new CapturingAgentTool("get_group_stats", ToolResult.success(Map.of("pv", 3)));
        RiskToolPlanningNode node = new RiskToolPlanningNode(new AgentToolRegistry(List.of(stats)), new SecurityRiskSanitizer());
        Map<String, Object> output = node.apply(new OverAllState(Map.of(
                "message", "分组‘样本’" + date, "profileScopeStatus", "NO_PROFILE", "authorizedStatisticsGid", "g1")));
        assertThat(stats.context).isNull();
        assertThat(output.get("statisticsEvidenceRequested")).isEqualTo(false);
        assertThat(output.get("toolWarnings").toString()).contains("统计日期无效");
    }

    private static Stream<Arguments> namedGroupDates() {
        return Stream.of(
                Arguments.of("2026-09-13", "2026-09-13", "2026-09-13", false),
                Arguments.of("最近24小时", "2026-09-13", "2026-09-14", true),
                Arguments.of("昨日", "2026-09-13", "2026-09-13", false),
                Arguments.of("今天", "2026-09-14", "2026-09-14", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"截至2026-09-13", "从2026-09-13之后", "2026-09-13以来",
            "startDate=2026-09-13", "endDate=2026-09-13"})
    void openOrHalfSpecifiedDatesNeverBecomeSingleDayQueries(String date) {
        CapturingAgentTool stats = new CapturingAgentTool("get_group_stats", ToolResult.success(Map.of("pv", 3)));
        RiskToolPlanningNode node = new RiskToolPlanningNode(new AgentToolRegistry(List.of(stats)), new SecurityRiskSanitizer());
        Map<String, Object> output = node.apply(new OverAllState(Map.of(
                "message", "分组‘样本’ " + date + " 的访问",
                "profileScopeStatus", "NO_PROFILE", "authorizedStatisticsGid", "g1")));
        assertThat(stats.context).isNull();
        assertThat(output.get("statisticsEvidenceRequested")).isEqualTo(false);
        assertThat(output.get("toolWarnings").toString()).contains("单边边界");
    }

    private static Stream<Arguments> invalidNamedGroupDates() {
        return Stream.of(Arguments.of("2026-02-30"), Arguments.of("2026-09-14至2026-09-13"),
                Arguments.of("2025-01-01至2026-09-13"));
    }

    private static Stream<Arguments> emptyToolData() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(Map.of()),
                Arguments.of(List.of()),
                Arguments.of("   "),
                Arguments.of((Object) new Object[0]));
    }

    private static class CapturingAgentTool implements AgentTool {

        private final String name;

        private final ToolResult result;

        private ToolContext context;

        private CapturingAgentTool(String name, ToolResult result) {
            this.name = name;
            this.result =
                    name.endsWith("_stats")
                                    && result.success()
                                    && result.data() instanceof Map<?, ?> stats
                                    && !stats.isEmpty()
                            ? ToolResult.success(
                                    com.jupiter.shortlink.agent.StatsTestFixtures.envelope(
                                            (Map<String, Object>) stats))
                            : result;
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
