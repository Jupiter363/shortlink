package com.nageoffer.shortlink.admin.remote.dto.resp;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RiskGroupOverviewRespDTO {

    private String gid;

    private int totalShortLinksScanned;

    private int lowRiskCount;

    private int mediumRiskCount;

    private int highRiskCount;

    private int watchingCount;

    private int disabledCount;

    private double avgRiskScore;

    private int maxRiskScore;

    private int groupRiskScore;

    private String groupRiskLevel;

    private List<String> groupReasonCodes;

    private List<RiskShortLinkCardRespDTO> topRiskShortLinks;

    private List<Map<String, Object>> riskTrend7d;

    private String agentSummary;
}
