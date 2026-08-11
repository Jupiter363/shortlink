package com.nageoffer.shortlink.admin.authority.capability.model;

import java.util.List;

public record AgentCapabilityProblem(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        String traceId,
        String requestId,
        boolean retryable,
        List<Object> violations
) {
}
