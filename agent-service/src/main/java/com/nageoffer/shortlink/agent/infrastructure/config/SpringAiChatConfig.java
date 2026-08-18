package com.nageoffer.shortlink.agent.infrastructure.config;

import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekSpringAiChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Spring AI entry points used by both graph agents.
 *
 * <p>The model bean is deliberately lazy with respect to the API key: the
 * application can start without a provider secret and retains the previous
 * request-time {@code LlmApiKeyNotConfiguredException} behavior.</p>
 */
@Configuration
public class SpringAiChatConfig {

    @Bean
    public RestTemplate deepSeekRestTemplate(RestTemplateBuilder builder, DeepSeekProperties properties) {
        Duration timeout = Duration.ofMillis(Math.max(1, properties.getTimeoutMs()));
        return builder
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .build();
    }

    @Bean(name = {"agentChatModel", "chatModel"})
    @Primary
    public ChatModel agentChatModel(
            DeepSeekProperties properties,
            RestTemplate deepSeekRestTemplate
    ) {
        return new DeepSeekSpringAiChatModel(properties, deepSeekRestTemplate);
    }

    @Bean(name = {"agentChatClient", "chatClient"})
    @Primary
    public ChatClient agentChatClient(
            ChatModel agentChatModel,
            ToolCallbackProvider agentToolCallbackProvider
    ) {
        return ChatClient.builder(agentChatModel)
                .defaultToolCallbacks(agentToolCallbackProvider)
                .build();
    }

    /**
     * Explanation nodes use a callback-free client so a model response cannot
     * start an implicit tool/ReAct loop. Tool-capable callers use the primary
     * {@code agentChatClient} above and explicitly provide trusted context.
     */
    @Bean(name = "agentExplanationChatClient")
    public ChatClient agentExplanationChatClient(ChatModel agentChatModel) {
        return ChatClient.create(agentChatModel);
    }
}
