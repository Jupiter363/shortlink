package com.jupiter.shortlink.agent.infrastructure.llm;

public class LlmApiKeyNotConfiguredException extends IllegalStateException {

    public LlmApiKeyNotConfiguredException(String message) {
        super(message);
    }
}
