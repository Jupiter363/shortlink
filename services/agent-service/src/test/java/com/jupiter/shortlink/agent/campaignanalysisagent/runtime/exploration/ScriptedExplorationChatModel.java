package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/** Local model fixture: the native agent owns tool execution and every model turn is explicit. */
final class ScriptedExplorationChatModel implements ChatModel {
    private final Deque<Function<Prompt, ChatResponse>> turns = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();

    @SafeVarargs
    ScriptedExplorationChatModel(Function<Prompt, ChatResponse>... turns) {
        Arrays.stream(turns).forEach(this::then);
    }

    /** The function may assert the exact messages and return a tool call or a final response. */
    synchronized ScriptedExplorationChatModel then(Function<Prompt, ChatResponse> turn) {
        turns.addLast(Objects.requireNonNull(turn, "turn"));
        return this;
    }

    ScriptedExplorationChatModel thenReturn(ChatResponse response) {
        Objects.requireNonNull(response, "response");
        return then(ignored -> response);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        Function<Prompt, ChatResponse> turn;
        int callNumber;
        synchronized (this) {
            // Keep the observed message list/options independent of later graph projection.
            prompts.add(prompt.copy());
            callNumber = prompts.size();
            turn = turns.pollFirst();
        }
        if (turn == null) {
            throw new AssertionError("Unexpected model call " + callNumber + ": no scripted turn remains");
        }
        // Run user assertions/latches outside the monitor so concurrent-call tests can observe progress.
        ChatResponse response = turn.apply(prompt);
        if (response == null) {
            throw new AssertionError("Scripted model turn " + callNumber + " returned null");
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> Flux.just(call(prompt)));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().model("scripted-exploration-test").build();
    }

    /** Includes unexpected or failed calls, so a swallowed model error cannot hide another turn. */
    synchronized int callCount() {
        return prompts.size();
    }

    synchronized List<Prompt> prompts() {
        return prompts.stream().map(Prompt::copy).toList();
    }

    synchronized int remainingTurns() {
        return turns.size();
    }

    synchronized void assertExhausted() {
        if (!turns.isEmpty()) {
            throw new AssertionError("Unconsumed scripted model turns: " + turns.size());
        }
    }

    static ChatResponse text(String content) {
        return response(new AssistantMessage(content), "stop");
    }

    static ChatResponse toolCalls(AssistantMessage.ToolCall... calls) {
        if (calls.length == 0) {
            throw new IllegalArgumentException("At least one tool call is required");
        }
        return response(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build(),
                "tool_calls");
    }

    static ChatResponse response(AssistantMessage message, String finishReason) {
        return new ChatResponse(
                List.of(new Generation(message,
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().model("scripted-exploration-test")
                        .usage(new DefaultUsage(1, 1)).build());
    }
}
