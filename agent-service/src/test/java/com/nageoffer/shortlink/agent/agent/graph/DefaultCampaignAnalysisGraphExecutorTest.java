package com.nageoffer.shortlink.agent.agent.graph;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmApiKeyNotConfiguredException;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClientException;
import com.nageoffer.shortlink.agent.tool.core.AgentTool;
import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolDescriptor;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCampaignAnalysisGraphExecutorTest {

    @Test
    void executeRunsListGroupsToolAndAddsToolDataToLlmPrompt() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "已结合分组数据分析",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        CapturingAgentTool listGroupsTool = new CapturingAgentTool(
                "list_groups",
                ToolResult.success(List.of(Map.of("gid", "g1", "name", "营销活动", "shortLinkCount", 3)))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(listGroupsTool))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "查看我的短链分组",
                "trace-1"
        ));

        assertThat(listGroupsTool.context.username()).isEqualTo("zhangsan");
        assertThat(listGroupsTool.context.arguments()).isEmpty();
        assertThat(chatClient.request.messages().get(1).content())
                .contains("Tool execution context")
                .contains("list_groups")
                .contains("营销活动");
        assertThat(result.dataSources().toString())
                .contains("tool")
                .contains("list_groups")
                .contains("success");
    }

    @Test
    void executeBuildsGroupSummaryCardFromListGroupsTool() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "group answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        List<Map<String, Object>> groups = List.of(
                Map.of("gid", "g1", "name", "Marketing", "shortLinkCount", 3),
                Map.of("gid", "g2", "name", "Product", "shortLinkCount", 2)
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("list_groups", ToolResult.success(groups))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "show group",
                "trace-1"
        ));

        Map<String, Object> card = card(result, 0);
        assertThat(card)
                .containsEntry("type", "group_summary")
                .containsEntry("sourceTool", "list_groups");
        assertThat(map(card.get("summary")))
                .containsEntry("groupCount", 2)
                .containsEntry("shortLinkCount", 5L);
        assertThat(card.get("rows")).isEqualTo(groups);
    }

    @Test
    void executePlansMultipleReadToolsForOverviewRequest() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "overview answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        CapturingAgentTool listGroupsTool = new CapturingAgentTool(
                "list_groups",
                ToolResult.success(List.of(Map.of("gid", "g1", "name", "Marketing", "shortLinkCount", 3)))
        );
        CapturingAgentTool pageTool = new CapturingAgentTool(
                "page_short_links",
                ToolResult.success(Map.of(
                        "records", List.of(Map.of("fullShortUrl", "nurl.ink/a", "describe", "Launch")),
                        "total", 1L,
                        "current", 1L,
                        "size", 10L
                ))
        );
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 120, "uv", 40, "uip", 30))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(listGroupsTool, pageTool, statsTool))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "show groups and link list and stats gid=g1 startDate=2026-07-01 endDate=2026-07-07 current=1 size=10",
                "trace-1"
        ));

        assertThat(listGroupsTool.context).as("list_groups should be planned").isNotNull();
        assertThat(pageTool.context).as("page_short_links should be planned").isNotNull();
        assertThat(statsTool.context).as("get_group_stats should be planned").isNotNull();
        assertThat(listGroupsTool.context.arguments()).isEmpty();
        assertThat(pageTool.context.arguments())
                .containsEntry("gid", "g1")
                .containsEntry("current", 1L)
                .containsEntry("size", 10L);
        assertThat(statsTool.context.arguments())
                .containsEntry("gid", "g1")
                .containsEntry("startDate", "2026-07-01")
                .containsEntry("endDate", "2026-07-07");
        assertThat(result.toolCalls())
                .extracting(each -> map(each).get("name"))
                .containsExactly("list_groups", "page_short_links", "get_group_stats");
        assertThat(result.cards())
                .extracting(each -> map(each).get("type"))
                .containsExactly("group_summary", "short_link_page", "stats_summary");
        assertThat(chatClient.request.messages().get(1).content())
                .contains("list_groups")
                .contains("page_short_links")
                .contains("get_group_stats");
    }

    @Test
    void executeExtractsArgumentsWithFullwidthChineseDelimiters() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "stats answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 120, "uv", 40, "uip", 30))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "\u7edf\u8ba1 gid\uff1ag1\uff0c startDate\uff1a2026-07-01\uff1b endDate\uff1a2026-07-07",
                "trace-1"
        ));

        assertThat(statsTool.context).as("get_group_stats should be planned").isNotNull();
        assertThat(statsTool.context.arguments())
                .containsEntry("gid", "g1")
                .containsEntry("startDate", "2026-07-01")
                .containsEntry("endDate", "2026-07-07");
    }

    @Test
    void executeKeepsSingleShortLinkStatsRequestToStatsToolOnly() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "short link stats answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        CapturingAgentTool pageTool = new CapturingAgentTool(
                "page_short_links",
                ToolResult.success(Map.of("records", List.of()))
        );
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_short_link_stats",
                ToolResult.success(Map.of("pv", 12, "uv", 4, "uip", 3))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(pageTool, statsTool))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "stats for short link fullShortUrl=nurl.ink/a gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-1"
        ));

        assertThat(pageTool.context).as("single short link stats should not request a page").isNull();
        assertThat(statsTool.context).as("get_short_link_stats should be planned").isNotNull();
        assertThat(result.toolCalls())
                .extracting(each -> map(each).get("name"))
                .containsExactly("get_short_link_stats");
        assertThat(result.cards())
                .extracting(each -> map(each).get("type"))
                .containsExactly("stats_summary");
    }

    @Test
    void executeKeepsAccessRecordsPageRequestToAccessRecordsToolOnly() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "access records answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        CapturingAgentTool pageTool = new CapturingAgentTool(
                "page_short_links",
                ToolResult.success(Map.of("records", List.of()))
        );
        CapturingAgentTool recordsTool = new CapturingAgentTool(
                "get_group_access_records",
                ToolResult.success(Map.of("records", List.of(Map.of("ip", "127.0.0.1"))))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(pageTool, recordsTool))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "page access records gid=g1 startDate=2026-07-01 endDate=2026-07-07 current=1 size=10",
                "trace-1"
        ));

        assertThat(pageTool.context).as("access records paging should not request short link page").isNull();
        assertThat(recordsTool.context).as("get_group_access_records should be planned").isNotNull();
        assertThat(result.toolCalls())
                .extracting(each -> map(each).get("name"))
                .containsExactly("get_group_access_records");
        assertThat(result.cards())
                .extracting(each -> map(each).get("type"))
                .containsExactly("access_records");
    }

    @Test
    void executeBuildsStatsSummaryCardFromGroupStatsTool() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "stats answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        Map<String, Object> stats = Map.of(
                "pv", 120,
                "uv", 40,
                "uip", 30,
                "daily", List.of(Map.of("date", "2026-07-01", "pv", 20))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("get_group_stats", ToolResult.success(stats))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "stats gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-1"
        ));

        assertThat(result.toolCalls()).hasSize(1);
        Map<String, Object> card = card(result, 0);
        assertThat(card)
                .containsEntry("type", "stats_summary")
                .containsEntry("sourceTool", "get_group_stats");
        assertThat(map(card.get("metrics")))
                .containsEntry("pv", 120)
                .containsEntry("uv", 40)
                .containsEntry("uip", 30);
        assertThat(map(card.get("arguments")))
                .containsEntry("gid", "g1")
                .containsEntry("startDate", "2026-07-01")
                .containsEntry("endDate", "2026-07-07");
        assertThat(card.get("rawData")).isEqualTo(stats);
    }

    @Test
    void executeBuildsTrafficAnomalyCardsFromStatsTool() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "anomaly answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        Map<String, Object> stats = Map.of(
                "pv", 120,
                "uv", 20,
                "uip", 18,
                "topIpStats", List.of(
                        Map.of("ip", "192.168.1.10", "cnt", 50),
                        Map.of("ip", "10.0.0.8", "cnt", 8)
                )
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("get_group_stats", ToolResult.success(stats))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "stats gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-1"
        ));

        List<Map<String, Object>> anomalies = cardsOfType(result, "traffic_anomaly");
        assertThat(anomalies)
                .extracting(each -> map(each.get("summary")).get("reasonCode"))
                .containsExactly("high_repeat_visits", "low_uip_share", "top_ip_concentration");
        assertThat(anomalies)
                .allSatisfy(each -> assertThat(each)
                        .containsEntry("sourceTool", "get_group_stats")
                        .containsEntry("severity", "warning"));
        Map<String, Object> topIpCard = anomalies.get(2);
        assertThat(map(topIpCard.get("evidence")))
                .containsEntry("maskedTopIp", "192.168.*.*");
        assertThat(topIpCard.toString()).doesNotContain("192.168.1.10");
        assertThat(chatClient.request.messages().get(1).content())
                .contains("Derived insight context")
                .contains("traffic_anomaly")
                .contains("high_repeat_visits")
                .doesNotContain("192.168.1.10");
    }

    @Test
    void executeAddsInsightExplanationContractWhenDerivedCardsExist() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "contract answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        Map<String, Object> stats = Map.of(
                "pv", 120,
                "uv", 20,
                "uip", 18,
                "topIpStats", List.of(Map.of("ip", "192.168.1.10", "cnt", 50))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("get_group_stats", ToolResult.success(stats))))
        );

        executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "stats gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-1"
        ));

        assertThat(chatClient.request.messages().get(1).content())
                .contains("Insight explanation contract")
                .contains("Use Derived insight context as the factual source")
                .contains("Do not recalculate, invent, or overwrite card metrics")
                .contains("possibleCauses")
                .contains("riskLevel")
                .contains("evidenceReferences")
                .contains("recommendedActions")
                .contains("not a definitive security conclusion")
                .doesNotContain("192.168.1.10");
    }

    @Test
    void executeSkipsInsightExplanationContractWhenNoDerivedCardsExist() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "stats answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        Map<String, Object> stats = Map.of("pv", 10, "uv", 8, "uip", 7);
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("get_group_stats", ToolResult.success(stats))))
        );

        executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "stats gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-1"
        ));

        assertThat(chatClient.request.messages().get(1).content())
                .contains("Tool execution context")
                .doesNotContain("Insight explanation contract")
                .doesNotContain("Derived insight context");
    }

    @Test
    void executeBuildsPerformanceInsightCardsFromStatsTool() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "insight answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        Map<String, Object> stats = Map.of(
                "pv", 170,
                "uv", 80,
                "uip", 70,
                "daily", List.of(
                        Map.of("date", "2026-07-01", "pv", 20),
                        Map.of("date", "2026-07-02", "pv", 22),
                        Map.of("date", "2026-07-03", "pv", 100)
                ),
                "hourStats", List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 50, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
                "deviceStats", List.of(Map.of("device", "Mobile", "cnt", 90, "ratio", 0.72))
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("get_short_link_stats", ToolResult.success(stats))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "stats for short link fullShortUrl=nurl.ink/a gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-1"
        ));

        List<Map<String, Object>> insights = cardsOfType(result, "performance_insight");
        assertThat(insights)
                .extracting(each -> map(each.get("summary")).get("reasonCode"))
                .containsExactly("daily_pv_spike", "hour_concentration", "profile_concentration");
        assertThat(insights)
                .allSatisfy(each -> assertThat(each)
                        .containsEntry("sourceTool", "get_short_link_stats")
                        .containsEntry("severity", "info"));
        assertThat(map(insights.get(0).get("metrics")))
                .containsEntry("latestPv", 100L)
                .containsEntry("deltaPv", 79L);
        assertThat(map(insights.get(2).get("summary")))
                .containsEntry("dimension", "device");
        assertThat(map(insights.get(2).get("evidence")))
                .containsEntry("label", "Mobile");
        assertThat(chatClient.request.messages().get(1).content())
                .contains("Derived insight context")
                .contains("performance_insight")
                .contains("daily_pv_spike");
    }

    @Test
    void executeBuildsDailyTrendInsightFromLatestDateWhenDailyRowsAreUnordered() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "insight answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        Map<String, Object> stats = Map.of(
                "pv", 163,
                "uv", 90,
                "uip", 70,
                "daily", List.of(
                        Map.of("date", "2026-07-04", "pv", 100),
                        Map.of("date", "2026-07-01", "pv", 20),
                        Map.of("date", "2026-07-03", "pv", 22),
                        Map.of("date", "2026-07-02", "pv", 21)
                )
        );
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("get_short_link_stats", ToolResult.success(stats))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "stats for short link fullShortUrl=nurl.ink/a gid=g1 startDate=2026-07-01 endDate=2026-07-04",
                "trace-1"
        ));

        List<Map<String, Object>> insights = cardsOfType(result, "performance_insight");
        assertThat(insights)
                .extracting(each -> map(each.get("summary")).get("reasonCode"))
                .containsExactly("daily_pv_spike");
        assertThat(map(insights.get(0).get("metrics")))
                .containsEntry("latestPv", 100L)
                .containsEntry("baselinePvAverage", 21.0D)
                .containsEntry("deltaPv", 79L);
        assertThat(map(insights.get(0).get("evidence")))
                .containsEntry("date", "2026-07-04");
    }

    @Test
    void executeBuildsShortLinkPageCardFromPageTool() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "page answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        List<Map<String, Object>> rows = List.of(
                Map.of("fullShortUrl", "nurl.ink/a", "describe", "Launch", "todayPv", 42, "totalPv", 120)
        );
        Map<String, Object> pageData = Map.of("records", rows, "total", 1L, "current", 1L, "size", 10L);
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("page_short_links", ToolResult.success(pageData))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "list link gid=g1 current=1 size=10",
                "trace-1"
        ));

        Map<String, Object> card = card(result, 0);
        assertThat(card)
                .containsEntry("type", "short_link_page")
                .containsEntry("sourceTool", "page_short_links");
        assertThat(map(card.get("summary")))
                .containsEntry("recordCount", 1)
                .containsEntry("total", 1L)
                .containsEntry("current", 1L)
                .containsEntry("size", 10L);
        assertThat(card.get("rows")).isEqualTo(rows);
    }

    @Test
    void executeBuildsAccessRecordsCardFromAccessRecordsTool() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "records answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        List<Map<String, Object>> rows = List.of(
                Map.of("ip", "127.0.0.1", "browser", "Chrome", "network", "WiFi")
        );
        Map<String, Object> pageData = Map.of("records", rows, "total", 1L, "current", 1L, "size", 10L);
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("get_group_access_records", ToolResult.success(pageData))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "access record gid=g1 startDate=2026-07-01 endDate=2026-07-07 current=1 size=10",
                "trace-1"
        ));

        Map<String, Object> card = card(result, 0);
        assertThat(card)
                .containsEntry("type", "access_records")
                .containsEntry("sourceTool", "get_group_access_records");
        assertThat(map(card.get("summary")))
                .containsEntry("recordCount", 1)
                .containsEntry("total", 1L)
                .containsEntry("current", 1L)
                .containsEntry("size", 10L);
        assertThat(card.get("rows")).isEqualTo(rows);
    }

    @Test
    void executeBuildsWarningCardWhenToolFails() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "degraded answer",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new CapturingGraphCheckpointStore(),
                new AgentProperties(),
                new AgentToolRegistry(List.of(new CapturingAgentTool("list_groups", ToolResult.failure("business api unavailable"))))
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "show group",
                "trace-1"
        ));

        assertThat(result.answer()).isEqualTo("degraded answer");
        assertThat(result.warnings()).contains("Tool list_groups failed: business api unavailable");
        Map<String, Object> card = card(result, 0);
        assertThat(card)
                .containsEntry("type", "tool_warning")
                .containsEntry("sourceTool", "list_groups")
                .containsEntry("message", "business api unavailable");
    }

    @Test
    void executeCallsLlmAndReturnsTraceableResult() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "分析结果",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                emptyToolRegistry()
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "分析最近7天数据",
                "trace-1"
        ));

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.traceId()).isEqualTo("trace-1");
        assertThat(result.answer()).isEqualTo("分析结果");
        assertThat(result.dataSources()).hasSize(2);
        assertThat(result.dataSources().get(0).toString()).contains("campaign-analysis-graph");
        assertThat(result.dataSources().get(0).toString()).contains("intake");
        assertThat(result.dataSources().get(0).toString()).contains("llm_analysis");
        assertThat(result.dataSources().get(0).toString()).contains("response_compose");
        assertThat(result.warnings()).isEmpty();
        assertThat(checkpointStore.saved).hasSize(1);
        assertThat(checkpointStore.saved.get(0).threadId()).isEqualTo("session-1");
        assertThat(checkpointStore.saved.get(0).traceId()).isEqualTo("trace-1");
        assertThat(checkpointStore.saved.get(0).graphName()).isEqualTo("campaign-analysis-graph");
        assertThat(checkpointStore.saved.get(0).checkpointJson()).contains("\"answer\":\"分析结果\"");
        assertThat(chatClient.request.messages())
                .extracting(DeepSeekChatRequest.Message::role)
                .containsExactly("system", "user");
        assertThat(chatClient.request.messages().get(1).content()).isEqualTo("分析最近7天数据");
    }

    @Test
    void executeReportsMissingApiKeySeparatelyFromProviderFailure() {
        DefaultCampaignAnalysisGraphExecutor missingKeyExecutor = newExecutor(request -> {
            throw new LlmApiKeyNotConfiguredException("DeepSeek API key not configured");
        });
        DefaultCampaignAnalysisGraphExecutor providerFailureExecutor = newExecutor(request -> {
            throw new LlmChatClientException("DeepSeek chat request failed");
        });
        CampaignAnalysisGraphRequest request = new CampaignAnalysisGraphRequest("session-1", "zhangsan", "hello", "trace-1");

        AgentRunResult missingKeyResult = missingKeyExecutor.execute(request);
        AgentRunResult providerFailureResult = providerFailureExecutor.execute(request);

        assertThat(missingKeyResult.answer()).contains("API key is not configured");
        assertThat(providerFailureResult.answer()).contains("DeepSeek API request failed");
        assertThat(providerFailureResult.answer()).doesNotContain("API key is not configured");
    }

    @Test
    void executeKeepsAgentAnswerWhenCheckpointSaveFails() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(new DeepSeekChatResponse(
                "chat-1",
                "deepseek-v4-flash",
                "分析结果",
                "stop",
                new DeepSeekChatResponse.Usage(10, 20, 30)
        ));
        DefaultCampaignAnalysisGraphExecutor executor = new DefaultCampaignAnalysisGraphExecutor(
                chatClient,
                new FailingGraphCheckpointStore(),
                new AgentProperties(),
                emptyToolRegistry()
        );

        AgentRunResult result = executor.execute(new CampaignAnalysisGraphRequest(
                "session-1",
                "zhangsan",
                "分析最近7天数据",
                "trace-1"
        ));

        assertThat(result.answer()).isEqualTo("分析结果");
        assertThat(result.warnings()).contains("Graph checkpoint save failed");
    }

    private DefaultCampaignAnalysisGraphExecutor newExecutor(LlmChatClient chatClient) {
        return new DefaultCampaignAnalysisGraphExecutor(chatClient, new CapturingGraphCheckpointStore(), new AgentProperties(), emptyToolRegistry());
    }

    private AgentToolRegistry emptyToolRegistry() {
        return new AgentToolRegistry(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> card(AgentRunResult result, int index) {
        return (Map<String, Object>) result.cards().get(index);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private List<Map<String, Object>> cardsOfType(AgentRunResult result, String type) {
        return result.cards().stream()
                .map(this::map)
                .filter(each -> type.equals(each.get("type")))
                .toList();
    }

    private static class CapturingLlmChatClient implements LlmChatClient {

        private final DeepSeekChatResponse response;

        private DeepSeekChatRequest request;

        private CapturingLlmChatClient(DeepSeekChatResponse response) {
            this.response = response;
        }

        @Override
        public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
            this.request = request;
            return response;
        }
    }

    private static class CapturingGraphCheckpointStore implements GraphCheckpointStore {

        private final List<GraphCheckpoint> saved = new ArrayList<>();

        @Override
        public void save(GraphCheckpoint checkpoint) {
            saved.add(checkpoint);
        }

        @Override
        public Optional<GraphCheckpoint> loadLatest(String threadId, String graphName, String graphVersion) {
            return saved.stream()
                    .filter(each -> each.threadId().equals(threadId))
                    .filter(each -> each.graphName().equals(graphName))
                    .filter(each -> each.graphVersion().equals(graphVersion))
                    .findFirst();
        }
    }

    private static class FailingGraphCheckpointStore implements GraphCheckpointStore {

        @Override
        public void save(GraphCheckpoint checkpoint) {
            throw new IllegalStateException("database unavailable");
        }

        @Override
        public Optional<GraphCheckpoint> loadLatest(String threadId, String graphName, String graphVersion) {
            return Optional.empty();
        }
    }

    private static class CapturingAgentTool implements AgentTool {

        private final ToolDescriptor descriptor;

        private final ToolResult result;

        private ToolContext context;

        private CapturingAgentTool(String name, ToolResult result) {
            this.descriptor = new ToolDescriptor(name, "Test tool", Map.of());
            this.result = result;
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ToolResult execute(ToolContext context) {
            this.context = context;
            return result;
        }
    }
}
