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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCampaignAnalysisGraphExecutorTest {

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
                new AgentProperties()
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
                new AgentProperties()
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
        return new DefaultCampaignAnalysisGraphExecutor(chatClient, new CapturingGraphCheckpointStore(), new AgentProperties());
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
}
