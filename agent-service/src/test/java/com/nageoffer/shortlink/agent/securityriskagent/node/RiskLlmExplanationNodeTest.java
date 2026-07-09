package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.securityriskagent.prompt.SecurityRiskPromptBuilder;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskLlmExplanationNodeTest {

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
                List.of(Map.of("data", Map.of("ip", "172.16.1.2", "username", "admin"))),
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
