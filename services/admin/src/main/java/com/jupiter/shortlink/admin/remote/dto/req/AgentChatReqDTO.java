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
}
