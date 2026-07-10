package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmApiKeyNotConfiguredException;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClientException;
import com.nageoffer.shortlink.agent.securityriskagent.evidence.RiskEvidenceClassifier;
import com.nageoffer.shortlink.agent.securityriskagent.evidence.RiskEvidenceStatus;
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
    private static final String EVIDENCE_UNAVAILABLE_ANSWER =
            "Security risk evidence is temporarily unavailable because all requested data sources failed.";
    private static final String EVIDENCE_UNAVAILABLE_WARNING = "Security risk evidence is unavailable";
    private static final String NO_EVIDENCE_ANSWER =
            "No security risk evidence was found for the requested scope.";

    private final LlmChatClient llmChatClient;

    private final SecurityRiskPromptBuilder promptBuilder;

    private final SecurityRiskSanitizer sanitizer;

    private final RiskEvidenceClassifier evidenceClassifier;

    public RiskLlmExplanationNode(
            LlmChatClient llmChatClient,
            SecurityRiskPromptBuilder promptBuilder,
            SecurityRiskSanitizer sanitizer
    ) {
        this.llmChatClient = llmChatClient;
        this.promptBuilder = promptBuilder;
        this.sanitizer = sanitizer;
        this.evidenceClassifier = new RiskEvidenceClassifier();
    }

    public Map<String, Object> apply(OverAllState state) {
        List<Map<String, Object>> toolExecutions = state.value("toolExecutions", List.of());
        List<Object> riskCards = state.value("riskCards", List.of());
        boolean evidenceRequested = state.value("evidenceRequested", false);
        RiskEvidenceStatus evidenceStatus = evidenceClassifier.classify(
                evidenceRequested,
                toolExecutions,
                riskCards
        );
        return explain(
                state.value("message", ""),
                toolExecutions,
                riskCards,
                state.value("toolWarnings", List.of()),
                state.value("analysisInput").isPresent(),
                evidenceStatus
        );
    }

    public Map<String, Object> explain(
            String message,
            List<Map<String, Object>> toolExecutions,
            List<Object> riskCards,
            List<String> toolWarnings
    ) {
        return explain(
                message,
                toolExecutions,
                riskCards,
                toolWarnings,
                false,
                evidenceClassifier.classify(
                        hasRequestedEvidence(toolExecutions, riskCards),
                        toolExecutions,
                        riskCards
                )
        );
    }

    public Map<String, Object> explain(
            String message,
            List<Map<String, Object>> toolExecutions,
            List<Object> riskCards,
            List<String> toolWarnings,
            boolean failFast
    ) {
        return explain(
                message,
                toolExecutions,
                riskCards,
                toolWarnings,
                failFast,
                evidenceClassifier.classify(
                        hasRequestedEvidence(toolExecutions, riskCards),
                        toolExecutions,
                        riskCards
                )
        );
    }

    public Map<String, Object> explain(
            String message,
            List<Map<String, Object>> toolExecutions,
            List<Object> riskCards,
            List<String> toolWarnings,
            boolean failFast,
            boolean evidenceRequested
    ) {
        return explain(
                message,
                toolExecutions,
                riskCards,
                toolWarnings,
                failFast,
                evidenceClassifier.classify(evidenceRequested, toolExecutions, riskCards)
        );
    }

    private Map<String, Object> explain(
            String message,
            List<Map<String, Object>> toolExecutions,
            List<Object> riskCards,
            List<String> toolWarnings,
            boolean failFast,
            RiskEvidenceStatus evidenceStatus
    ) {
        List<String> warnings = sanitizedWarnings(toolWarnings);
        Map<String, Object> llmDataSource = Map.of();
        if (evidenceStatus == RiskEvidenceStatus.SOURCE_FAILURE) {
            warnings.add(EVIDENCE_UNAVAILABLE_WARNING);
            return result(EVIDENCE_UNAVAILABLE_ANSWER, llmDataSource, warnings);
        }
        if (evidenceStatus == RiskEvidenceStatus.NO_DATA) {
            return result(NO_EVIDENCE_ANSWER, llmDataSource, warnings);
        }
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
            if (failFast) {
                throw ex;
            }
            answer = "Security risk agent is ready, but DeepSeek API key is not configured.";
            warnings.add(sanitizer.sanitizeText(ex.getMessage()));
        } catch (LlmChatClientException ex) {
            if (failFast) {
                throw ex;
            }
            answer = "DeepSeek API request failed. Please check provider connectivity and configuration.";
            warnings.add(sanitizer.sanitizeText(ex.getMessage()));
        }
        return result(answer, llmDataSource, warnings);
    }

    private Map<String, Object> result(
            String answer,
            Map<String, Object> llmDataSource,
            List<String> warnings
    ) {
        return Map.of(
                "answer", answer,
                "llmDataSource", llmDataSource,
                "warnings", warnings,
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE, LLM_EXPLANATION_NODE)
        );
    }

    private boolean hasRequestedEvidence(
            List<Map<String, Object>> toolExecutions,
            List<Object> riskCards
    ) {
        return (toolExecutions != null && !toolExecutions.isEmpty())
                || (riskCards != null && !riskCards.isEmpty());
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
