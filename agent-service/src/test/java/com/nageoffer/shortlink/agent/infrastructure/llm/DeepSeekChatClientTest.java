package com.nageoffer.shortlink.agent.infrastructure.llm;

import com.nageoffer.shortlink.agent.infrastructure.config.DeepSeekProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class DeepSeekChatClientTest {

    @Test
    void chatPostsOpenAiCompatibleRequestAndMapsResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey("unit-test-key");
        DeepSeekChatClient client = new DeepSeekChatClient(properties, restTemplate);

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer unit-test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("deepseek-v4-flash"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("hello"))
                .andExpect(jsonPath("$.max_tokens").value(2000))
                .andRespond(withSuccess("""
                        {
                          "id": "chatcmpl-test",
                          "model": "deepseek-v4-flash",
                          "choices": [
                            {
                              "finish_reason": "stop",
                              "message": {
                                "role": "assistant",
                                "content": "world"
                              }
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 5,
                            "completion_tokens": 7,
                            "total_tokens": 12
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        DeepSeekChatResponse response = client.chat(new DeepSeekChatRequest(
                List.of(new DeepSeekChatRequest.Message("user", "hello")),
                null,
                null,
                null
        ));

        assertThat(response.id()).isEqualTo("chatcmpl-test");
        assertThat(response.model()).isEqualTo("deepseek-v4-flash");
        assertThat(response.content()).isEqualTo("world");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(5);
        assertThat(response.usage().completionTokens()).isEqualTo(7);
        assertThat(response.usage().totalTokens()).isEqualTo(12);
        server.verify();
    }

    @Test
    void chatFailsFastWhenApiKeyIsMissing() {
        RestTemplate restTemplate = new RestTemplate();
        DeepSeekProperties properties = new DeepSeekProperties();
        DeepSeekChatClient client = new DeepSeekChatClient(properties, restTemplate);

        assertThatThrownBy(() -> client.chat(new DeepSeekChatRequest(
                        List.of(new DeepSeekChatRequest.Message("user", "hello")),
                        null,
                        null,
                        null
                )))
                .isInstanceOf(LlmApiKeyNotConfiguredException.class)
                .hasMessage("DeepSeek API key not configured");
    }

    @Test
    void chatWrapsUpstreamFailureWithoutTreatingItAsMissingKey() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey("unit-test-key");
        DeepSeekChatClient client = new DeepSeekChatClient(properties, restTemplate);

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.chat(new DeepSeekChatRequest(
                        List.of(new DeepSeekChatRequest.Message("user", "hello")),
                        null,
                        null,
                        null
                )))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat request failed");
        server.verify();
    }
}
