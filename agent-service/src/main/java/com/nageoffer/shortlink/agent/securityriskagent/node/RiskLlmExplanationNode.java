package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmApiKeyNotConfiguredException;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClientException;
import com.nageoffer.shortlink.agent.securityriskagent.prompt.SecurityRiskPromptBuilder;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RiskLlmExplanationNode {

    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String RISK_SCORING_NODE = "risk_scoring";
    private static final String LLM_EXPLANATION_NODE = "llm_explanation";

    private final LlmChatClient llmChatClient;

    private final SecurityRiskPromptBuilder promptBuilder;

    private final SecurityRiskSanitizer sanitizer;

    public RiskLlmExplanationNode(
            LlmChatClient llmChatClient,
            SecurityRiskPromptBuilder promptBuilder,
            SecurityRiskSanitizer sanitizer
    ) {
        this.llmChatClient = llmChatClient;
        this.promptBuilder = promptBuilder;
        this.sanitizer = sanitizer;
    }

    public Map<String, Object> apply(OverAllState state) {
        return explain(
                state.value("message", ""),
                state.value("toolExecutions", List.of()),
                state.value("riskCards", List.of()),
                state.value("toolWarnings", List.of())
        );
    }

    public Map<String, Object> explain(
            String message,
            List<Map<String, Object>> toolExecutions,
            List<Object> riskCards,
            List<String> toolWarnings
    ) {
        List<String> warnings = sanitizedWarnings(toolWarnings);
        Map<String, Object> llmDataSource = Map.of();
        String answer;
        try {
            DeepSeekChatResponse chatResponse = llmChatClient.chat(new DeepSeekChatRequest(
                    List.of(
                            new DeepSeekChatRequest.Message("system", promptBuilder.systemPrompt()),
                            new DeepSeekChatRequest.Message("user", promptBuilder.userPrompt(message, toolExecutions, riskCards))
                    ),
                    null,
                    null,
                    null
            ));
            answer = sanitizer.sanitizeText(chatResponse.content());
            llmDataSource = Map.of(
                    "type", "llm",
                    "provider", "deepseek",
                    "model", chatResponse.model(),
                    "finishReason", chatResponse.finishReason()
            );
        } catch (LlmApiKeyNotConfiguredException ex) {
            answer = "Security risk agent is ready, but DeepSeek API key is not configured.";
            warnings.add(sanitizer.sanitizeText(ex.getMessage()));
        } catch (LlmChatClientException ex) {
            answer = "DeepSeek API request failed. Please check provider connectivity and configuration.";
            warnings.add(sanitizer.sanitizeText(ex.getMessage()));
        }
        return Map.of(
                "answer", answer,
                "llmDataSource", llmDataSource,
                "warnings", warnings,
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE, LLM_EXPLANATION_NODE)
        );
    }

    private List<String> sanitizedWarnings(List<String> warnings) {
        List<String> sanitized = new ArrayList<>();
        if (warnings == null) {
            return sanitized;
        }
        for (String warning : warnings) {
            sanitized.add(sanitizer.sanitizeText(warning));
        }
        return sanitized;
    }
}
