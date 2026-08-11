package com.nageoffer.shortlink.admin.authority.identity.model;

import java.util.List;

public record AgentIdentityProblem(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        String requestId,
        boolean retryable,
        List<Object> violations
) {
}
