package com.nageoffer.shortlink.agent.infrastructure.llm;

import java.util.List;
import java.util.Map;

public record DeepSeekChatRequest(
        List<Message> messages,
        Double temperature,
        Integer maxTokens,
        Map<String, Object> responseFormat
) {

    public record Message(String role, String content) {
    }
}
