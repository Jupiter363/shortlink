package com.jupiter.shortlink.admin.remote.dto.resp;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RiskGroupOverviewRespDTO {

    private String gid;

    private long totalShortLinksScanned;

    private long lowRiskCount;

    private long mediumRiskCount;

    private long highRiskCount;

    private long watchingCount;

    private Long disabledCount;
    private String currentPolicyCoverage;

    private double avgRiskScore;

    private int maxRiskScore;

    private int groupRiskScore;

    private String groupRiskLevel;

    private List<String> groupReasonCodes;

    private List<RiskShortLinkCardRespDTO> topRiskShortLinks;

    private List<Map<String, Object>> riskTrend7d;

    private String agentSummary;
    private Map<String, Object> manualReview;
}
