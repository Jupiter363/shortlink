package com.nageoffer.shortlink.admin.remote.dto.resp;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RiskEventRespDTO {

    private String eventId;

    private String targetType;

    private String gid;

    private String domain;

    private String shortUri;

    private String fullShortUrl;

    private int riskScore;

    private String riskLevel;

    private List<String> reasonCodes;

    private Map<String, Object> evidence;

    private List<String> recommendedActions;

    private String agentSummary;

    private String traceId;

    private String sessionId;

    private String source;

    private String eventTime;
}
