package com.nageoffer.shortlink.agent.harness.runtime;

import com.nageoffer.shortlink.agent.agent.graph.CampaignAnalysisGraphExecutor;
import com.nageoffer.shortlink.agent.agent.graph.CampaignAnalysisGraphRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentRunHarnessTest {

    @Test
    void runDelegatesToCampaignAnalysisGraphWithTraceId() {
        CapturingCampaignAnalysisGraphExecutor graphExecutor = new CapturingCampaignAnalysisGraphExecutor();
        DefaultAgentRunHarness harness = new DefaultAgentRunHarness(graphExecutor);

        AgentRunResult result = harness.run(new AgentRunRequest(
                "session-1",
                "zhangsan",
                "分析最近7天数据"
        ));

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.traceId()).isEqualTo(graphExecutor.request.traceId());
        assertThat(result.answer()).isEqualTo("分析结果");
        assertThat(graphExecutor.request.traceId()).isNotBlank();
        assertThat(graphExecutor.request.sessionId()).isEqualTo("session-1");
        assertThat(graphExecutor.request.username()).isEqualTo("zhangsan");
        assertThat(graphExecutor.request.message()).isEqualTo("分析最近7天数据");
    }

    private static class CapturingCampaignAnalysisGraphExecutor implements CampaignAnalysisGraphExecutor {

        private CampaignAnalysisGraphRequest request;

        @Override
        public AgentRunResult execute(CampaignAnalysisGraphRequest request) {
            this.request = request;
            return new AgentRunResult(
                    request.sessionId(),
                    request.traceId(),
                    "分析结果",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.of("type", "graph", "name", "campaign-analysis-graph")),
                    List.of()
            );
        }
    }
}
