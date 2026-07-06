package com.nageoffer.shortlink.agent.infrastructure.llm;

public record DeepSeekChatResponse(
        String id,
        String model,
        String content,
        String finishReason,
        Usage usage
) {

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
