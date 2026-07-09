package com.nageoffer.shortlink.agent.riskcenter.api.dto;

import java.util.List;
import java.util.Map;

public record RiskGroupOverviewRespDTO(
        String gid,
        int totalShortLinksScanned,
        int lowRiskCount,
        int mediumRiskCount,
        int highRiskCount,
        int watchingCount,
        int disabledCount,
        double avgRiskScore,
        int maxRiskScore,
        int groupRiskScore,
        String groupRiskLevel,
        List<String> groupReasonCodes,
        List<RiskShortLinkCardRespDTO> topRiskShortLinks,
        List<Map<String, Object>> riskTrend7d,
        String agentSummary
) {
}
