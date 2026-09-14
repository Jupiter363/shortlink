package com.jupiter.shortlink.agent.riskcenter.api.dto;

import java.util.List;
import java.util.Map;

public record RiskGroupOverviewRespDTO(
        String gid,
        String profileStatus,
        Long totalShortLinksScanned,
        Long lowRiskCount,
        Long mediumRiskCount,
        Long highRiskCount,
        Long watchingCount,
        Long disabledCount,
        Double avgRiskScore,
        Integer maxRiskScore,
        Integer groupRiskScore,
        String groupRiskLevel,
        List<String> groupReasonCodes,
        List<RiskShortLinkCardRespDTO> topRiskShortLinks,
        List<Map<String, Object>> riskTrend7d,
        String agentSummary,
        Map<String, Object> manualReview) {}
