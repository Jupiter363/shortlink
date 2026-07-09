package com.nageoffer.shortlink.agent.securityriskagent.graph;

import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
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

class DefaultSecurityRiskGraphExecutorTest {

    @Test
    void executeRunsRiskToolsBuildsSanitizedRiskCardsAndSavesCheckpoint() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of(
                        "pv", 100,
                        "uv", 80,
                        "uip", 20,
                        "topIpStats", List.of(Map.of("ip", "192.168.1.10", "cnt", 45)),
                        "hourStats", List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 70, 30)
                ))
        );
        CapturingAgentTool accessRecordsTool = new CapturingAgentTool(
                "get_group_access_records",
                ToolResult.success(Map.of(
                        "records", List.of(Map.of(
                                "ip", "192.168.1.10",
                                "user", "visitor-001",
                                "device", "PC",
                                "browser", "Chrome"
                        )),
                        "total", 1
                ))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool, accessRecordsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-1",
                "zhangsan",
                "analyze security risk gid=g1 startDate=2026-07-01 endDate=2026-07-07 access records current=1 size=5",
                "trace-1"
        ));

        assertThat(result.answer()).isEqualTo("security risk answer");
        assertThat(result.dataSources().toString()).contains("security-risk-graph");
        assertThat(result.toolCalls().toString())
                .contains("get_group_stats")
                .contains("get_group_access_records")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.cards().toString())
                .contains("top_ip_concentration")
                .contains("hour_burst")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.pendingActions().toString())
                .contains("review_security_risk")
                .contains("pending_confirmation");
        assertThat(chatClient.request.messages().get(1).content())
                .contains("Risk signal context")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(statsTool.context.username()).isEqualTo("zhangsan");
        assertThat(statsTool.context.arguments()).containsEntry("gid", "g1");
        assertThat(accessRecordsTool.context.arguments())
                .containsEntry("current", 1L)
                .containsEntry("size", 5L);
        assertThat(checkpointStore.saved).hasSize(1);
        assertThat(checkpointStore.saved.get(0).graphName()).isEqualTo("security-risk-graph");
        assertThat(checkpointStore.saved.get(0).checkpointJson())
                .contains("top_ip_concentration")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
    }

    @Test
    void executeSanitizesUserMessageAndLlmAnswerBeforePromptResponseAndCheckpoint() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient("raw ip 10.0.0.9 user=visitor-009");
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 10, "uv", 10, "topIpStats", List.of()))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-2",
                "zhangsan",
                "check gid=g1 startDate=2026-07-01 endDate=2026-07-07 ip=192.168.1.10 user=visitor-001",
                "trace-2"
        ));

        assertThat(chatClient.request.messages().get(1).content())
                .contains("192.168.*.*")
                .contains("user=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.answer())
                .contains("10.0.*.*")
                .contains("user=***")
                .doesNotContain("10.0.0.9")
                .doesNotContain("visitor-009");
        assertThat(checkpointStore.saved.get(0).checkpointJson())
                .contains("10.0.*.*")
                .doesNotContain("10.0.0.9")
                .doesNotContain("visitor-009");
    }

    @Test
    void executeSanitizesToolFailureMessagesBeforePromptResponseAndCheckpoint() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        ThrowingAgentTool statsTool = new ThrowingAgentTool(
                "get_group_stats",
                "backend failed for ip=192.168.1.10 user=visitor-001"
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-3",
                "zhangsan",
                "check gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-3"
        ));

        assertThat(chatClient.request.messages().get(1).content())
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.toolCalls().toString())
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(checkpointStore.saved.get(0).checkpointJson())
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
    }

    @Test
    void executeDoesNotCreatePendingActionFromStringifiedMediumRiskCardContent() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_short_link_stats",
                ToolResult.success(Map.of(
                        "pv", 120,
                        "uv", 20,
                        "topIpStats", List.of()
                ))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-4",
                "zhangsan",
                "check gid=g1 fullShortUrl=riskLevel=high startDate=2026-07-01 endDate=2026-07-07",
                "trace-4"
        ));

        assertThat(result.cards().toString())
                .contains("high_repeat_visits")
                .contains("riskLevel=medium")
                .contains("fullShortUrl=riskLevel=high");
        assertThat(result.pendingActions()).isEmpty();
    }

    private static class CapturingLlmChatClient implements LlmChatClient {

        private DeepSeekChatRequest request;

        private final String answer;

        private CapturingLlmChatClient() {
            this("security risk answer");
        }

        private CapturingLlmChatClient(String answer) {
            this.answer = answer;
        }

        @Override
        public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
            this.request = request;
            return new DeepSeekChatResponse(
                    "chat-1",
                    "deepseek-v4-flash",
                    answer,
                    "stop",
                    new DeepSeekChatResponse.Usage(10, 20, 30)
            );
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
            return Optional.empty();
        }
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
