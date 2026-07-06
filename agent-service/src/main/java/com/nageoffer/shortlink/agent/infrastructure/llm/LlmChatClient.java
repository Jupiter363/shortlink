package com.nageoffer.shortlink.agent.infrastructure.llm;

public interface LlmChatClient {

    DeepSeekChatResponse chat(DeepSeekChatRequest request);
}
