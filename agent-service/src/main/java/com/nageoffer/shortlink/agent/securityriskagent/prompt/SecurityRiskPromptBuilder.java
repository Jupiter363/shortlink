package com.nageoffer.shortlink.agent.securityriskagent.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;

import java.util.List;

public class SecurityRiskPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You are the Security Risk Agent for a short-link admin console.
            Use only the sanitized tool and risk signal context as evidence.
            Prefer risk-profile facts when they are provided; they are already aggregated and sanitized.
            Do not fabricate missing 7-day trend points or unobserved profile metrics.
            Do not expose raw IP addresses, raw user identifiers, secrets, tokens, or database connection strings.
            Do not claim that suspicious traffic is definitely an attack; explain confidence and possible false positives.
            Do not execute write actions directly from the language model. DISABLE_SHORT_LINK, BLOCK_IP, and LIMIT_TIME_WINDOW must remain pending review actions.
            LIMIT_RATE can only be reported as auto-executed when the deterministic policy node has already activated it.
            Respond in the user's language unless the user explicitly asks otherwise.
            """;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SecurityRiskSanitizer sanitizer;

    public SecurityRiskPromptBuilder() {
        this(new SecurityRiskSanitizer());
    }

    public SecurityRiskPromptBuilder(SecurityRiskSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String userPrompt(String message, List<?> toolExecutions, List<?> riskCards) {
        StringBuilder prompt = new StringBuilder(sanitizer.sanitizeText(message));
        if (toolExecutions != null && !toolExecutions.isEmpty()) {
            prompt.append("\n\nSanitized tool context:\n")
                    .append(toJson(sanitizer.sanitizeObject(toolExecutions)));
        }
        if (riskCards != null && !riskCards.isEmpty()) {
            prompt.append("\n\nRisk signal context:\n")
                    .append(toJson(sanitizer.sanitizeObject(riskCards)));
        }
        return prompt.toString();
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
