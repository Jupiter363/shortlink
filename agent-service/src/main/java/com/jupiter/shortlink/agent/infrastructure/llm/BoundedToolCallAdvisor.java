package com.jupiter.shortlink.agent.infrastructure.llm;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring AI 1.1 custom models need explicit tool-loop orchestration; each request has its own
 * finite budget.
 */
public final class BoundedToolCallAdvisor extends ToolCallAdvisor {
    private static final String BUDGET = BoundedToolCallAdvisor.class.getName() + ".rounds";
    private final int maxModelCalls;

    public BoundedToolCallAdvisor(int maxModelCalls) {
        super(ToolCallingManager.builder().build(), BaseAdvisor.HIGHEST_PRECEDENCE + 300);
        if (maxModelCalls < 1 || maxModelCalls > 16)
            throw new IllegalArgumentException("Invalid model-call budget");
        this.maxModelCalls = maxModelCalls;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var context = new LinkedHashMap<>(request.context());
        context.put(BUDGET, new AtomicInteger());
        return super.adviseCall(
                ChatClientRequest.builder().prompt(request.prompt()).context(context).build(),
                chain);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        AtomicInteger rounds = (AtomicInteger) request.context().get(BUDGET);
        if (rounds == null || rounds.incrementAndGet() > maxModelCalls) {
            throw new LlmChatClientException("Model tool-call round budget exhausted");
        }
        return request;
    }

    @Override
    protected ChatClientResponse doAfterCall(ChatClientResponse response, CallAdvisorChain chain) {
        if (response.chatResponse() != null
                && response.chatResponse().getResults().stream()
                                .mapToInt(
                                        generation -> generation.getOutput().getToolCalls().size())
                                .sum()
                        > 8) {
            throw new LlmChatClientException("Model tool-call batch budget exceeded");
        }
        return response;
    }
}
