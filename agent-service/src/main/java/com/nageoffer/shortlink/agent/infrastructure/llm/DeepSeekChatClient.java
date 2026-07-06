package com.nageoffer.shortlink.agent.infrastructure.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nageoffer.shortlink.agent.infrastructure.config.DeepSeekProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class DeepSeekChatClient implements LlmChatClient {

    private final DeepSeekProperties properties;

    private final RestTemplate restTemplate;

    public DeepSeekChatClient(DeepSeekProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new LlmApiKeyNotConfiguredException("DeepSeek API key not configured");
        }
        DeepSeekApiRequest apiRequest = new DeepSeekApiRequest(
                properties.getModel(),
                request.messages(),
                request.temperature(),
                request.maxTokens() == null ? properties.getMaxOutputTokens() : request.maxTokens(),
                request.responseFormat()
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        DeepSeekApiResponse apiResponse;
        try {
            apiResponse = restTemplate.postForObject(chatCompletionsUrl(), new HttpEntity<>(apiRequest, headers), DeepSeekApiResponse.class);
        } catch (RestClientException ex) {
            throw new LlmChatClientException("DeepSeek chat request failed", ex);
        }
        if (apiResponse == null || apiResponse.choices() == null || apiResponse.choices().isEmpty()) {
            throw new LlmChatClientException("DeepSeek chat response is empty");
        }
        DeepSeekApiChoice choice = apiResponse.choices().get(0);
        DeepSeekApiMessage message = choice.message();
        String content = message == null ? "" : message.content();
        DeepSeekApiUsage usage = apiResponse.usage();
        return new DeepSeekChatResponse(
                apiResponse.id(),
                apiResponse.model(),
                content,
                choice.finishReason(),
                usage == null
                        ? new DeepSeekChatResponse.Usage(0, 0, 0)
                        : new DeepSeekChatResponse.Usage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens())
        );
    }

    private String chatCompletionsUrl() {
        String baseUrl = properties.getBaseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/chat/completions";
    }

    private record DeepSeekApiRequest(
            String model,
            List<DeepSeekChatRequest.Message> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            @JsonProperty("response_format") Map<String, Object> responseFormat
    ) {
    }

    private record DeepSeekApiResponse(
            String id,
            String model,
            List<DeepSeekApiChoice> choices,
            DeepSeekApiUsage usage
    ) {
    }

    private record DeepSeekApiChoice(
            @JsonProperty("finish_reason") String finishReason,
            DeepSeekApiMessage message
    ) {
    }

    private record DeepSeekApiMessage(String role, String content) {
    }

    private record DeepSeekApiUsage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
