package com.jupiter.shortlink.agent.riskcenter.api.dto;

import java.util.List;
import java.util.Map;

public record RiskGroupOverviewRespDTO(
        String gid,
        long totalShortLinksScanned,
        long lowRiskCount,
        long mediumRiskCount,
        long highRiskCount,
        long watchingCount,
        Long disabledCount,
        double avgRiskScore,
        int maxRiskScore,
        int groupRiskScore,
        String groupRiskLevel,
        List<String> groupReasonCodes,
        List<RiskShortLinkCardRespDTO> topRiskShortLinks,
        List<Map<String, Object>> riskTrend7d,
        String agentSummary,
        Map<String, Object> manualReview) {}
