package com.jupiter.shortlink.agent.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.infrastructure.config.DeepSeekProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekSpringAiChatModelTest {

    private final DeepSeekProperties properties = new DeepSeekProperties();
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final DeepSeekSpringAiChatModel model = new DeepSeekSpringAiChatModel(properties, restTemplate);

    DeepSeekSpringAiChatModelTest() {
        properties.setApiKey("test-key");
    }

    @Test
    void explanationDisablesThinkingAndKeepsBudgetWhenPromptOverridesOtherOptions() throws Exception {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.max_tokens").value(2000))
                .andRespond(withSuccess(response("stop", "Stats explain the observed traffic.", null),
                        MediaType.APPLICATION_JSON));

        var response = model.call(new Prompt("Explain the evidence", ChatOptions.builder().temperature(0.2).build()));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("Stats explain the observed traffic.");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
        server.verify();
    }

    @Test
    void thinkingCanBeExplicitlyEnabledWithoutChangingTheBudget() throws Exception {
        properties.setThinkingEnabled(true);
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.thinking.type").value("enabled"))
                .andExpect(jsonPath("$.max_tokens").value(2000))
                .andRespond(withSuccess(response("stop", "Complete answer", null), MediaType.APPLICATION_JSON));

        assertThat(model.call(new Prompt("Explain")).getResult().getOutput().getText())
                .isEqualTo("Complete answer");
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void reasoningWithoutAnswerIsNeverReportedAsSuccess(String content) throws Exception {
        expectResponse("stop", content, null);

        assertThatThrownBy(() -> model.call(new Prompt("Explain")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response has no answer content");
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Partial answer"})
    void outputBudgetExhaustionNeverReturnsAnEmptyOrPartialConclusion(String content) throws Exception {
        expectResponse("length", content, null);

        assertThatThrownBy(() -> model.call(new Prompt("Explain")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response exceeded the output budget");
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"content_filter", "insufficient_system_resource", "untrusted-provider-value"})
    void incompleteProviderResponsesFailWithAFixedMessage(String finishReason) throws Exception {
        expectResponse(finishReason, "untrusted-provider-body", null);

        assertThatThrownBy(() -> model.call(new Prompt("Explain")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response did not complete successfully");
        server.verify();
    }

    @Test
    void validToolCallsMayHaveNoAnswerUntilTheToolHasRun() throws Exception {
        expectResponse("tool_calls", null, validToolCalls());

        var response = model.call(new Prompt("List groups"));

        assertThat(response.getResult().getOutput().getToolCalls()).singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("list_groups"));
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void missingToolIdentityIsRejectedInsteadOfInventingAnId(String id) throws Exception {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", id);
        call.put("type", "function");
        call.put("function", Map.of("name", "list_groups", "arguments", "{}"));
        expectResponse("tool_calls", null, List.of(call));

        assertThatThrownBy(() -> model.call(new Prompt("List groups")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response has invalid tool call identities");
        server.verify();
    }

    @Test
    void duplicateToolIdentitiesRejectTheWholeBatch() throws Exception {
        Object call = validToolCalls().get(0);
        expectResponse("tool_calls", null, List.of(call, call));

        assertThatThrownBy(() -> model.call(new Prompt("List groups")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response has invalid tool call identities");
        server.verify();
    }

    @Test
    void malformedEntryCannotBeSilentlyDroppedFromAnOtherwiseValidBatch() throws Exception {
        expectResponse("tool_calls", null, Arrays.asList(validToolCalls().get(0), null));

        assertThatThrownBy(() -> model.call(new Prompt("List groups")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response has invalid tool call identities");
        server.verify();
    }

    @Test
    void distinctIdentitiesRemainUnchangedForTheNativeBatchGate() throws Exception {
        expectResponse("tool_calls", null, List.of(validToolCalls().get(0),
                Map.of("id", "call-2", "type", "function",
                        "function", Map.of("name", "list_groups", "arguments", "{}"))));

        assertThat(model.call(new Prompt("List groups")).getResult().getOutput().getToolCalls())
                .extracting(org.springframework.ai.chat.messages.AssistantMessage.ToolCall::id)
                .containsExactly("call-1", "call-2");
        server.verify();
    }

    @Test
    void truncatedToolCallsAreNotExecuted() throws Exception {
        expectResponse("length", null, validToolCalls());

        assertThatThrownBy(() -> model.call(new Prompt("List groups")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response exceeded the output budget");
        server.verify();
    }

    @Test
    void toolCompletionWithoutCallsIsNotAnAnswer() throws Exception {
        expectResponse("tool_calls", "Unfinished explanation", List.of());

        assertThatThrownBy(() -> model.call(new Prompt("List groups")))
                .isInstanceOf(LlmChatClientException.class)
                .hasMessage("DeepSeek chat response has inconsistent tool calls");
        server.verify();
    }

    private void expectResponse(String finishReason, String content, List<?> toolCalls) throws Exception {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(response(finishReason, content, toolCalls), MediaType.APPLICATION_JSON));
    }

    private String response(String finishReason, String content, List<?> toolCalls) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        message.put("reasoning_content", "reasoning-must-not-be-returned-or-included-in-errors");
        message.put("tool_calls", toolCalls);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("finish_reason", finishReason);
        choice.put("message", message);
        return new ObjectMapper().writeValueAsString(Map.of(
                "id", "test-response", "model", "deepseek-flash", "choices", List.of(choice)));
    }

    private List<?> validToolCalls() {
        return List.of(Map.of("id", "call-1", "type", "function",
                "function", Map.of("name", "list_groups", "arguments", "{}")));
    }
}
