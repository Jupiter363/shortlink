package com.jupiter.shortlink.agent.infrastructure.llm;

import com.jupiter.shortlink.agent.infrastructure.config.DeepSeekProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * @deprecated Compatibility facade for older unit/integration callers.
 * Production wiring uses the standard Spring AI {@code ChatModel} and
 * {@code ChatClient} beans from {@code SpringAiChatConfig}; this class is not
 * a Spring component and is intentionally excluded from dependency injection.
 */
@Deprecated
public final class DeepSeekChatClient implements LlmChatClient {

    private final DeepSeekSpringAiChatModel chatModel;

    public DeepSeekChatClient(DeepSeekProperties properties, RestTemplate restTemplate) {
        this.chatModel = new DeepSeekSpringAiChatModel(properties, restTemplate);
    }

    @Override
    public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request != null && request.messages() != null) {
            for (DeepSeekChatRequest.Message message : request.messages()) {
                if (message == null) {
                    continue;
                }
                String role = message.role() == null ? "user" : message.role().toLowerCase();
                String content = message.content() == null ? "" : message.content();
                messages.add(switch (role) {
                    case "system" -> new SystemMessage(content);
                    case "assistant" -> new AssistantMessage(content);
                    default -> new UserMessage(content);
                });
            }
        }
        ChatResponse response = chatModel.call(new Prompt(messages));
        Generation generation = response == null ? null : response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw new LlmChatClientException("DeepSeek chat response is empty");
        }
        String id = response.getMetadata() == null ? "" : response.getMetadata().getId();
        String model = response.getMetadata() == null ? "" : response.getMetadata().getModel();
        String finishReason = generation.getMetadata() == null
                ? ""
                : generation.getMetadata().getFinishReason();
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            promptTokens = value(response.getMetadata().getUsage().getPromptTokens());
            completionTokens = value(response.getMetadata().getUsage().getCompletionTokens());
            totalTokens = value(response.getMetadata().getUsage().getTotalTokens());
        }
        return new DeepSeekChatResponse(
                id,
                model,
                generation.getOutput().getText(),
                finishReason,
                new DeepSeekChatResponse.Usage(promptTokens, completionTokens, totalTokens)
        );
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
