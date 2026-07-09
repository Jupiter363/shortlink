package com.nageoffer.shortlink.agent.riskcenter.api.dto;

import java.util.List;

public record RiskShortLinkCardRespDTO(
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        String profileWindowEnd,
        int riskScore,
        String riskLevel,
        List<String> reasonCodes,
        int pv2h,
        int uv2h,
        int pv24h,
        int uv24h,
        int pv7d,
        int uv7d,
        String watchStatus,
        List<String> latestPolicyActions,
        String latestAgentSummary
) {
}
