package com.nageoffer.shortlink.admin.remote.dto.req;

import lombok.Data;

@Data
public class AgentChatReqDTO {

    private String sessionId;

    private String message;
}
