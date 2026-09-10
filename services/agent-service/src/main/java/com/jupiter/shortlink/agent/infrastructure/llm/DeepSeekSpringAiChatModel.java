package com.jupiter.shortlink.agent.infrastructure.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.infrastructure.config.DeepSeekProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Spring AI ChatModel adapter for the DeepSeek-compatible chat-completions API.
 *
 * <p>The project intentionally keeps the Spring Boot 3.0 dependency line.  The
 * native Spring AI provider implementations are compiled against a newer
 * Spring Web line, so this adapter exposes the stable Spring AI ChatModel
 * contract while retaining the existing RestTemplate transport and timeout
 * configuration.</p>
 */
public final class DeepSeekSpringAiChatModel implements ChatModel {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DeepSeekProperties properties;

    private final RestTemplate restTemplate;

    public DeepSeekSpringAiChatModel(DeepSeekProperties properties, RestTemplate restTemplate) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new LlmApiKeyNotConfiguredException("DeepSeek API key not configured");
        }
        Prompt effectivePrompt = prompt == null ? new Prompt("") : prompt;
        Map<String, Object> request = requestBody(effectivePrompt);
        DeepSeekApiResponse response;
        try {
            response = restTemplate.postForObject(
                    chatCompletionsUrl(),
                    new HttpEntity<>(request, headers()),
                    DeepSeekApiResponse.class
            );
        } catch (LlmApiKeyNotConfiguredException | LlmChatClientException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new LlmChatClientException("DeepSeek chat request failed", ex);
        } catch (RuntimeException ex) {
            throw new LlmChatClientException("DeepSeek chat request failed", ex);
        }
        return toChatResponse(response);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // DeepSeek's non-streaming endpoint is the stable path used by this
        // service.  Exposing it through Flux still satisfies ChatModel's
        // streaming contract and keeps error handling lazy for subscribers.
        return Flux.defer(() -> Flux.just(call(prompt)));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // ChatClient only merges configured ToolCallback instances into
        // ToolCallingChatOptions. Returning that contract here is therefore
        // required for the primary, tool-enabled ChatClient bean.
        return ToolCallingChatOptions.builder()
                .model(properties.getModel())
                .maxTokens(properties.getMaxOutputTokens())
                .build();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private Map<String, Object> requestBody(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null) {
            options = getDefaultOptions();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", textOrDefault(options.getModel(), properties.getModel()));
        body.put("messages", messages(prompt.getInstructions()));
        if (options.getTemperature() != null) {
            body.put("temperature", options.getTemperature());
        }
        Integer maxTokens = options.getMaxTokens();
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (options.getTopP() != null) {
            body.put("top_p", options.getTopP());
        }
        List<Map<String, Object>> tools = tools(options);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            Object toolChoice = toolChoice(options);
            if (toolChoice != null) {
                body.put("tool_choice", toolChoice);
            }
        }
        return body;
    }

    private List<Map<String, Object>> messages(List<Message> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return List.of(Map.of("role", "user", "content", ""));
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : instructions) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    Map<String, Object> toolMessage = new LinkedHashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", response.id());
                    if (StringUtils.hasText(response.name())) {
                        toolMessage.put("name", response.name());
                    }
                    toolMessage.put("content", response.responseData());
                    messages.add(toolMessage);
                }
                continue;
            }
            Map<String, Object> apiMessage = new LinkedHashMap<>();
            apiMessage.put("role", role(message));
            String text = message.getText();
            apiMessage.put("content", text == null ? "" : text);
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", call.id());
                    toolCall.put("type", textOrDefault(call.type(), "function"));
                    toolCall.put("function", function);
                    toolCalls.add(toolCall);
                }
                apiMessage.put("tool_calls", toolCalls);
            }
            messages.add(apiMessage);
        }
        return messages;
    }

    private String role(Message message) {
        MessageType type = message.getMessageType();
        if (type == null) {
            return "user";
        }
        return switch (type) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
            case USER -> "user";
        };
    }

    private List<Map<String, Object>> tools(ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolOptions)
                || toolOptions.getToolCallbacks() == null
                || toolOptions.getToolCallbacks().isEmpty()) {
            return List.of();
        }
        Set<String> selectedNames = toolOptions.getToolNames() == null
                ? Collections.emptySet()
                : toolOptions.getToolNames();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallback callback : toolOptions.getToolCallbacks()) {
            if (callback == null || callback.getToolDefinition() == null) {
                continue;
            }
            ToolDefinition definition = callback.getToolDefinition();
            if (!selectedNames.isEmpty() && !selectedNames.contains(definition.name())) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.put("parameters", schema(definition.inputSchema()));
            tools.add(Map.of("type", "function", "function", function));
        }
        return tools;
    }

    private Object toolChoice(ChatOptions options) {
        // DeepSeekChatOptions exposes toolChoice, while the stable Spring AI
        // interface does not.  Read it reflectively so this adapter remains
        // compatible with any ToolCallingChatOptions implementation.
        try {
            Object choice = options.getClass().getMethod("getToolChoice").invoke(options);
            return choice instanceof String && ((String) choice).isBlank() ? null : choice;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Object schema(String inputSchema) {
        if (!StringUtils.hasText(inputSchema)) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return OBJECT_MAPPER.readValue(inputSchema, Object.class);
        } catch (JsonProcessingException ignored) {
            return Map.of("type", "object", "description", inputSchema);
        }
    }

    private ChatResponse toChatResponse(DeepSeekApiResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new LlmChatClientException("DeepSeek chat response is empty");
        }
        DeepSeekChoice choice = response.choices().get(0);
        if (choice == null || choice.message() == null) {
            throw new LlmChatClientException("DeepSeek chat response is empty");
        }
        DeepSeekMessage message = choice.message();
        AssistantMessage.Builder assistant = AssistantMessage.builder()
                .content(message.content() == null ? "" : message.content());
        List<AssistantMessage.ToolCall> toolCalls = toolCalls(message.toolCalls());
        if (!toolCalls.isEmpty()) {
            assistant.toolCalls(toolCalls);
        }
        AssistantMessage assistantMessage = assistant.build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(choice.finishReason())
                .build();
        Generation generation = new Generation(assistantMessage, generationMetadata);
        DeepSeekUsage usage = response.usage();
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(response.id())
                .model(response.model());
        if (usage != null) {
            metadata.usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens()));
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    private List<AssistantMessage.ToolCall> toolCalls(List<DeepSeekToolCall> apiToolCalls) {
        if (apiToolCalls == null || apiToolCalls.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (DeepSeekToolCall apiToolCall : apiToolCalls) {
            if (apiToolCall == null || apiToolCall.function() == null) {
                continue;
            }
            DeepSeekFunction function = apiToolCall.function();
            toolCalls.add(new AssistantMessage.ToolCall(
                    textOrDefault(apiToolCall.id(), "tool-call"),
                    textOrDefault(apiToolCall.type(), "function"),
                    function.name(),
                    textOrDefault(function.arguments(), "{}")
            ));
        }
        return toolCalls;
    }

    private String chatCompletionsUrl() {
        String baseUrl = properties.getBaseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/chat/completions";
    }

    private String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekApiResponse(
            String id,
            String model,
            List<DeepSeekChoice> choices,
            DeepSeekUsage usage
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekChoice(
            @com.fasterxml.jackson.annotation.JsonProperty("finish_reason") String finishReason,
            DeepSeekMessage message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekMessage(
            String role,
            String content,
            @com.fasterxml.jackson.annotation.JsonProperty("tool_calls") List<DeepSeekToolCall> toolCalls
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekToolCall(
            String id,
            String type,
            DeepSeekFunction function
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekFunction(String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekUsage(
            @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") int promptTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens") int completionTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("total_tokens") int totalTokens
    ) {
    }

}
