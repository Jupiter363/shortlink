package com.jupiter.shortlink.agent.infrastructure.llm;

/**
 * @deprecated Production graph nodes use Spring AI {@code ChatClient}; this
 * interface remains only as a source-compatible seam for legacy callers.
 */
@Deprecated
public interface LlmChatClient {

    DeepSeekChatResponse chat(DeepSeekChatRequest request);
}
