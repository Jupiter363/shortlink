package com.jupiter.shortlink.admin.remote.dto.resp;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RiskGroupOverviewRespDTO {

    private String gid;

    private String profileStatus;

    private Long totalShortLinksScanned;

    private Long lowRiskCount;

    private Long mediumRiskCount;

    private Long highRiskCount;

    private Long watchingCount;

    private Long disabledCount;
    private String currentPolicyCoverage;

    private Double avgRiskScore;

    private Integer maxRiskScore;

    private Integer groupRiskScore;

    private String groupRiskLevel;

    private List<String> groupReasonCodes;

    private List<RiskShortLinkCardRespDTO> topRiskShortLinks;

    private List<Map<String, Object>> riskTrend7d;

    private String agentSummary;
    private Map<String, Object> manualReview;
}
