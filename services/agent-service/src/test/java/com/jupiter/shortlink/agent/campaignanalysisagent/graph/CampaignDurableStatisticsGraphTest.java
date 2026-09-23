package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStatisticsDurableToolAdapter;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.AgentTool;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolDescriptor;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.jupiter.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CampaignDurableStatisticsGraphTest {
    private static final AgentPrincipal ALICE = new AgentPrincipal("1001", "alice", 7, false);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void rankingUsesStableSubmissionIdentityAndResumesServerWorkReferenceWithoutLegacyTools() {
        var adapter = mock(CampaignStatisticsDurableToolAdapter.class);
        var legacy = legacy("rank_short_links");
        var contexts = new ArrayList<ToolContext>();
        var keys = new ArrayList<String>();
        Map<String, String> work = Map.of("runId", "campaign-run-1", "workId", "intake-1");
        when(adapter.execute(eq("rank_short_links"), any(ToolContext.class), nullable(String.class)))
                .thenAnswer(call -> {
                    ToolContext context = call.getArgument(1);
                    contexts.add(context);
                    keys.add(call.getArgument(2));
                    var continuation = new LinkedHashMap<>(context.arguments());
                    continuation.put("workRef", work);
                    return ToolResult.success(Map.of("type", "ranking", "status", "PENDING", "rows", List.of(),
                            "meta", Map.of("resultComplete", false), "continuation", continuation));
                });
        var graph = graph(adapter, legacy);
        String question = "gid=alpha 最近7天短链排名 metric=pv limit=10";
        String submission = "s".repeat(256);
        var first = graph.execute(request(question, "trace-1", submission));
        var retry = graph.execute(request(question, "trace-2", submission));
        var followup = graph.execute(request("查看分析结果", "trace-3", "submit-2"));

        assertThat(contexts).hasSize(3);
        assertThat(contexts).allSatisfy(context -> {
            assertThat(context.principal()).isEqualTo(ALICE);
            assertThat(context.sessionId()).isEqualTo("durable-graph-session");
        });
        assertThat(keys.get(0)).isEqualTo(keys.get(1)).startsWith("chat-stat-");
        assertThat(keys.get(2)).isNotEqualTo(keys.get(0));
        assertThat(contexts.get(2).arguments()).containsEntry("workRef", work);
        for (var result : List.of(first, retry, followup)) {
            assertThat(result.answer()).contains("尚无完整分析结果");
            assertThat(result.cards()).anySatisfy(card -> assertThat(((Map<?, ?>) card).get("status"))
                    .isEqualTo("PENDING"));
        }
        verify(legacy, never()).execute(any());
    }

    @Test
    void durableComparisonFailureDoesNotFallBackToLegacyGatewayTool() {
        var adapter = mock(CampaignStatisticsDurableToolAdapter.class);
        var legacy = legacy("compare_statistics");
        when(adapter.execute(eq("compare_statistics"), any(ToolContext.class), nullable(String.class)))
                .thenReturn(ToolResult.failure("STATISTICS_DURABLE_ACCESS_DENIED"));
        var result = graph(adapter, legacy).execute(request("gid=alpha 比较今天和昨天访问趋势", "trace-failure", "submit-failure"));
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("STATISTICS_DURABLE_ACCESS_DENIED"));
        verify(adapter).execute(eq("compare_statistics"), any(ToolContext.class), any(String.class));
        verify(legacy, never()).execute(any());
    }

    private static DefaultCampaignAnalysisGraphExecutor graph(CampaignStatisticsDurableToolAdapter adapter, AgentTool legacy) {
        var llm = mock(LlmChatClient.class);
        when(llm.chat(any())).thenReturn(new DeepSeekChatResponse("reply", "test-model", "No statistics available", "stop", null));
        var graph = new DefaultCampaignAnalysisGraphExecutor(llm, mock(GraphCheckpointStore.class),
                new AgentProperties(), new AgentToolRegistry(List.of(legacy)), CLOCK);
        graph.setDurableStatistics(adapter);
        return graph;
    }

    private static AgentTool legacy(String name) {
        var tool = mock(AgentTool.class);
        when(tool.descriptor()).thenReturn(new ToolDescriptor(name, "Legacy statistics", Map.of()));
        return tool;
    }

    private static CampaignAnalysisGraphRequest request(String message, String trace, String key) {
        return new CampaignAnalysisGraphRequest("durable-graph-session", "alice", message, trace, ALICE, key);
    }
}
