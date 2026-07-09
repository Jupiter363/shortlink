package com.nageoffer.shortlink.agent.riskcenter.api.dto;

public record RiskReviewReqDTO(
        String eventId,
        String targetType,
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        String reviewAction,
        String reviewer,
        String reviewNote
) {
}
