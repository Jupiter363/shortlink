package com.nageoffer.shortlink.agent.infrastructure.llm;

public class LlmChatClientException extends IllegalStateException {

    public LlmChatClientException(String message) {
        super(message);
    }

    public LlmChatClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
