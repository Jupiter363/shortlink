package com.jupiter.shortlink.admin.remote.dto.req;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
public class AgentChatReqDTO {

    private String sessionId;

    private String agentType;

    private String message;

    /** Client-generated idempotency key for one campaign-analysis submission. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String requestKey;

    /** Advertisements select a compatible response, never grant access. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.Set<String> clientCapabilities;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Operation operation;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Continuation continuation;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String previousRunId;

    public enum Operation { NEW, CONTINUE, PROGRESS, CANCEL }

    public record Continuation(String runId, String requestId) {}
}
