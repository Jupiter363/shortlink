package com.jupiter.shortlink.agent.business.shortlink;

import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;

import java.util.Map;

public interface ShortLinkBusinessGateway {

    ToolResult get(String path, ToolContext context, Map<String, Object> queryParams);

    default ToolResult post(String path, ToolContext context, Map<String, Object> request) {
        return ToolResult.failure("Statistics job submission is unavailable");
    }

    /** Backend ledger dispatch only. Unsupported adapters must never use the legacy post fallback. */
    default ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        return new ToolResult(false, Map.of("code", "STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE"),
                "Statistics job submission protocol is unavailable");
    }

    /** Frozen-set dispatch has a dedicated protocol and never falls back to current-group submission. */
    default ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        return new ToolResult(false, Map.of("code", "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"),
                "Frozen statistics protocol is unavailable");
    }

    /** Existing-only recovery of the original frozen request; never creates a replacement job. */
    default ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        return new ToolResult(false, Map.of("code", "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"),
                "Frozen statistics protocol is unavailable");
    }

    /** Release only the original frozen job's remote pages; never fall back to submit or generic POST. */
    default ToolResult releaseStatisticsJobResult(ToolContext context, String jobId,
                                                Map<String, Object> originalFrozenRequest) {
        return new ToolResult(false, Map.of("code", "STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE"),
                "Statistics result release protocol is unavailable");
    }

    /** Current authorization of exactly the specified IDs, including an explicit empty set. */
    default ToolResult authorizeStatisticsScope(ToolContext context, Map<String, Object> request) {
        return new ToolResult(false, Map.of("code", "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"),
                "Frozen statistics authorization protocol is unavailable");
    }

    /** Backend reconciliation only; never fall back to the create-or-find submission API. */
    default ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        return new ToolResult(false, Map.of("code", "RECOVERY_PROTOCOL_UNAVAILABLE"),
                "Statistics job recovery protocol is unavailable");
    }

    /** Backend result reception only. Unsupported adapters must not fall back to legacy get/post. */
    default ToolResult readStatisticsJob(ToolContext context, String jobId) {
        return new ToolResult(false, Map.of("code", "STATISTICS_READ_PROTOCOL_UNAVAILABLE"),
                "Statistics job read protocol is unavailable");
    }

    /** One page of the original job. The current wire protocol fixes size at 500, not a total-result limit. */
    default ToolResult readStatisticsJobPage(ToolContext context, String jobId, int pageIndex, int size) {
        return new ToolResult(false, Map.of("code", "STATISTICS_READ_PROTOCOL_UNAVAILABLE"),
                "Statistics job read protocol is unavailable");
    }
}
