package com.nageoffer.shortlink.agent.riskcenter.api.dto;

public record RiskEventQueryReqDTO(
        String gid,
        String targetType,
        String domain,
        String shortUri,
        int pageNo,
        int pageSize
) {
}
