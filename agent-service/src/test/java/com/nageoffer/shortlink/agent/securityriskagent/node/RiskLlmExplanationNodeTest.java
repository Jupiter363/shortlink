package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.securityriskagent.prompt.SecurityRiskPromptBuilder;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RiskLlmExplanationNodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"NO_DATA", "SOURCE_FAILURE"})
    void applyReclassifiesAvailableRiskCardsAfterPlanningRecordedAStaleStatus(String staleStatus) {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient("risk card answer");
        RiskLlmExplanationNode node = new RiskLlmExplanationNode(
                chatClient,
                new SecurityRiskPromptBuilder(new SecurityRiskSanitizer()),
                new SecurityRiskSanitizer()
        );
        OverAllState state = new OverAllState(Map.of(
                "message", "analyze risk",
                "toolExecutions", List.of(Map.of(
                        "name", "get_group_stats",
                        "success", true,
                        "data", Map.of()
                )),
                "riskCards", List.of(Map.of("riskScore", 85)),
                "toolWarnings", List.of(),
                "evidenceRequested", true,
                "evidenceStatus", staleStatus
        ));

        Map<String, Object> output = node.apply(state);

        assertThat(output.get("answer")).isEqualTo("risk card answer");
        assertThat(output.get("llmDataSource").toString()).contains("deepseek-v4-flash");
        assertThat(chatClient.request).isNotNull();
    }

    @Test
    void explainCallsLlmWithSanitizedPromptAndSanitizesAnswer() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient("answer ip=10.0.0.9 user=visitor-002 token=abc");
        RiskLlmExplanationNode node = new RiskLlmExplanationNode(
                chatClient,
                new SecurityRiskPromptBuilder(new SecurityRiskSanitizer()),
                new SecurityRiskSanitizer()
        );

        Map<String, Object> output = node.explain(
                "risk ip=192.168.1.10 user=visitor-001 token=raw",
                List.of(Map.of(
                        "success", true,
                        "data", Map.of("ip", "172.16.1.2", "username", "admin")
                )),
                List.of(Map.of("evidence", Map.of("ip", "8.8.8.8", "uid", "u-001"))),
                List.of("tool warning ip=127.0.0.1 user=ops")
        );

        assertThat(chatClient.request.messages().get(0).content()).contains("Security Risk Agent");
        assertThat(chatClient.request.messages().get(1).content())
                .contains("192.168.*.*")
                .contains("172.16.*.*")
                .contains("8.8.*.*")
                .contains("token=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("172.16.1.2")
                .doesNotContain("8.8.8.8")
                .doesNotContain("visitor-001")
                .doesNotContain("admin")
                .doesNotContain("u-001")
                .doesNotContain("raw");
        assertThat(output.get("answer").toString())
                .contains("10.0.*.*")
                .contains("user=***")
                .contains("token=***")
                .doesNotContain("10.0.0.9")
                .doesNotContain("visitor-002")
                .doesNotContain("abc");
        assertThat(output.get("warnings").toString())
                .contains("127.0.*.*")
                .doesNotContain("127.0.0.1")
                .doesNotContain("ops");
        assertThat(output.get("visitedNodes")).isEqualTo(List.of("intake", "risk_tool_planning", "risk_scoring", "llm_explanation"));
    }

    @Test
    void explainReturnsDeterministicFallbackWhenAllEvidenceToolsFail() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient("must not be used");
        RiskLlmExplanationNode node = new RiskLlmExplanationNode(
                chatClient,
                new SecurityRiskPromptBuilder(new SecurityRiskSanitizer()),
                new SecurityRiskSanitizer()
        );

        Map<String, Object> output = node.explain(
                "analyze risk",
                List.of(Map.of(
                        "name", "get_group_stats",
                        "success", false,
                        "message", "business api unavailable"
                )),
                List.of(),
                List.of("Agent tool get_group_stats failed: business api unavailable")
        );

        assertThat(output.get("answer"))
                .isEqualTo("Security risk evidence is temporarily unavailable because all requested data sources failed.");
        assertThat(output.get("llmDataSource")).isEqualTo(Map.of());
        assertThat(output.get("warnings").toString())
                .contains("Agent tool get_group_stats failed")
                .contains("business api unavailable");
        assertThat(chatClient.request).isNull();
    }

    @Test
    void explainCallsLlmWhenUsableToolEvidenceExistsWithoutRiskCards() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient("tool evidence answer");
        RiskLlmExplanationNode node = new RiskLlmExplanationNode(
                chatClient,
                new SecurityRiskPromptBuilder(new SecurityRiskSanitizer()),
                new SecurityRiskSanitizer()
        );

        Map<String, Object> output = node.explain(
                "analyze risk",
                List.of(Map.of(
                        "name", "get_group_stats",
                        "success", true,
                        "data", Map.of("pv", 10, "uv", 8)
                )),
                List.of(),
                List.of()
        );

        assertThat(output.get("answer")).isEqualTo("tool evidence answer");
        assertThat(output.get("llmDataSource").toString()).contains("deepseek-v4-flash");
        assertThat(chatClient.request).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("emptyEvidenceData")
    void explainDoesNotCallLlmWhenSuccessfulToolExecutionContainsNoUsableEvidence(Object emptyData) {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient("must not be used");
        RiskLlmExplanationNode node = new RiskLlmExplanationNode(
                chatClient,
                new SecurityRiskPromptBuilder(new SecurityRiskSanitizer()),
                new SecurityRiskSanitizer()
        );
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", "get_group_stats");
        execution.put("success", true);
        execution.put("data", emptyData);

        Map<String, Object> output = node.explain(
                "analyze risk",
                List.of(execution),
                List.of(),
                List.of()
        );

        assertThat(output.get("answer"))
                .isEqualTo("No security risk evidence was found for the requested scope.");
        assertThat(output.get("llmDataSource")).isEqualTo(Map.of());
        assertThat(output.get("warnings")).isEqualTo(List.of());
        assertThat(chatClient.request).isNull();
    }

    private static Stream<Arguments> emptyEvidenceData() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(Map.of()),
                Arguments.of(List.of()),
                Arguments.of("   "),
                Arguments.of((Object) new Object[0])
        );
    }

    private static class CapturingLlmChatClient implements LlmChatClient {

        private final String answer;

        private DeepSeekChatRequest request;

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
}
