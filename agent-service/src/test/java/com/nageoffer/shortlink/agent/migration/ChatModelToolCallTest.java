package com.nageoffer.shortlink.agent.migration;

import com.nageoffer.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import com.nageoffer.shortlink.agent.infrastructure.config.DeepSeekProperties;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekSpringAiChatModel;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolCallbackConfiguration;
import com.nageoffer.shortlink.agent.tool.shortlink.GetGroupAccessRecordsTool;
import com.nageoffer.shortlink.agent.tool.shortlink.GetGroupStatsTool;
import com.nageoffer.shortlink.agent.tool.shortlink.GetShortLinkStatsTool;
import com.nageoffer.shortlink.agent.tool.shortlink.ListGroupsTool;
import com.nageoffer.shortlink.agent.tool.shortlink.PageShortLinksTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatModelToolCallTest {

    @Test
    void productionChatModelSerializesCallbacksAndMapsDeepSeekToolCalls() {
        CapturingGateway gateway = new CapturingGateway();
        MethodToolCallbackProvider provider = provider(gateway);
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://deepseek.test");
        properties.setModel("test-model");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        DeepSeekSpringAiChatModel model = new DeepSeekSpringAiChatModel(properties, restTemplate);

        server.expect(requestTo("https://deepseek.test/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.tools[0].function.name").value("list_groups"))
                .andExpect(jsonPath("$.tools[0].function.parameters.type").value("object"))
                .andRespond(withSuccess("""
                        {
                          "id": "chat-1",
                          "model": "test-model",
                          "choices": [
                            {
                              "finish_reason": "tool_calls",
                              "message": {
                                "role": "assistant",
                                "content": "",
                                "tool_calls": [
                                  {
                                    "id": "call-1",
                                    "type": "function",
                                    "function": {
                                      "name": "list_groups",
                                      "arguments": "{}"
                                    }
                                  }
                                ]
                              }
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 10,
                            "completion_tokens": 2,
                            "total_tokens": 12
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(model.getDefaultOptions()).isInstanceOf(ToolCallingChatOptions.class);
        ChatResponse response = model.call(new Prompt(
                "List my groups",
                ToolCallingChatOptions.builder()
                        .model("test-model")
                        .toolCallbacks(provider.getToolCallbacks())
                        .build()
        ));

        assertThat(response.getResult().getOutput().getToolCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.id()).isEqualTo("call-1");
                    assertThat(call.name()).isEqualTo("list_groups");
                    assertThat(call.arguments()).isEqualTo("{}");
                });
        server.verify();
    }

    @Test
    void chatClientExecutesModelToolCallThroughMethodCallbackAndReturnsFinalAnswer() {
        CapturingGateway gateway = new CapturingGateway();
        MethodToolCallbackProvider provider = provider(gateway);
        ScriptedToolCallingModel model = new ScriptedToolCallingModel();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultToolCallbacks(provider)
                .build();

        String answer = chatClient.prompt("List my groups")
                .toolContext(Map.of("sessionId", "session-001", "username", "alice"))
                .call()
                .content();

        assertThat(answer).isEqualTo("Groups loaded for alice");
        assertThat(model.calls).hasValue(2);
        assertThat(gateway.context.username()).isEqualTo("alice");
        assertThat(gateway.context.sessionId()).isEqualTo("session-001");
    }

    private MethodToolCallbackProvider provider(CapturingGateway gateway) {
        return new AgentToolCallbackConfiguration().agentToolCallbackProvider(
                new ListGroupsTool(gateway),
                new PageShortLinksTool(gateway),
                new GetShortLinkStatsTool(gateway),
                new GetGroupStatsTool(gateway),
                new GetGroupAccessRecordsTool(gateway)
        );
    }

    private static class ScriptedToolCallingModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.getAndIncrement() == 0) {
                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "list_groups",
                        "{}"
                );
                return response(AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build(), "tool_calls");
            }
            return response(new AssistantMessage("Groups loaded for alice"), "stop");
        }

        private ChatResponse response(AssistantMessage message, String finishReason) {
            return new ChatResponse(
                    List.of(new Generation(
                            message,
                            ChatGenerationMetadata.builder().finishReason(finishReason).build()
                    )),
                    ChatResponseMetadata.builder()
                            .id("chat-1")
                            .model("test-model")
                            .usage(new DefaultUsage(1, 1))
                            .build()
            );
        }
    }

    private static class CapturingGateway implements ShortLinkBusinessGateway {

        private ToolContext context;

        @Override
        public ToolResult get(String path, ToolContext context, Map<String, Object> queryParams) {
            this.context = context;
            return ToolResult.success(Map.of("groups", List.of(Map.of("gid", "g1"))));
        }
    }
}
