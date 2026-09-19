package com.jupiter.shortlink.agent.business.shortlink;

import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;

import java.util.Map;

public interface ShortLinkBusinessGateway {

    ToolResult get(String path, ToolContext context, Map<String, Object> queryParams);

    default ToolResult post(String path, ToolContext context, Map<String, Object> request) {
        return ToolResult.failure("Statistics job submission is unavailable");
    }

    /** Backend reconciliation only; never fall back to the create-or-find submission API. */
    default ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        return new ToolResult(false, Map.of("code", "RECOVERY_PROTOCOL_UNAVAILABLE"),
                "Statistics job recovery protocol is unavailable");
    }
}
