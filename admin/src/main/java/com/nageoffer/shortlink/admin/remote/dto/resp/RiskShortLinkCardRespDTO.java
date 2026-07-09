package com.nageoffer.shortlink.admin.remote.dto.resp;

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

    private int pv2h;

    private int uv2h;

    private int pv24h;

    private int uv24h;

    private int pv7d;

    private int uv7d;

    private String watchStatus;

    private List<String> latestPolicyActions;

    private String latestAgentSummary;
}
