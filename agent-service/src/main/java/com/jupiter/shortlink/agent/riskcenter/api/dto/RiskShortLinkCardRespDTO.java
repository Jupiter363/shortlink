package com.jupiter.shortlink.agent.riskcenter.api.dto;

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
        long pv2h,
        long uv2h,
        long pv24h,
        long uv24h,
        long pv7d,
        long uv7d,
        String watchStatus,
        List<String> latestPolicyActions,
        String latestAgentSummary,
        String tenantId,
        Long linkId,
        java.util.Map<String, Object> statsMeta,
        java.util.Map<String, Object> currentPolicy,
        java.util.Map<String, Object> manualReview) {}
