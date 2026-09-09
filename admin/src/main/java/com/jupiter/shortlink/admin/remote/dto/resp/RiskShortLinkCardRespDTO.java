package com.jupiter.shortlink.admin.remote.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class RiskShortLinkCardRespDTO {

    private String gid;

    private String domain;

    private String shortUri;

    private String fullShortUrl;

    private String profileWindowEnd;

    private int riskScore;

    private String riskLevel;

    private List<String> reasonCodes;

    private long pv2h;

    private long uv2h;

    private long pv24h;

    private long uv24h;

    private long pv7d;

    private long uv7d;

    private String watchStatus;

    private List<String> latestPolicyActions;

    private String latestAgentSummary;
    private String tenantId;
    private Long linkId;
    private java.util.Map<String, Object> statsMeta;
    private java.util.Map<String, Object> currentPolicy;
    private java.util.Map<String, Object> manualReview;
}
