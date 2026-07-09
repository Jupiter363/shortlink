package com.nageoffer.shortlink.agent.riskcenter.api.dto;

public record RiskReviewRespDTO(
        String reviewId,
        String eventId,
        String targetType,
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        String reviewAction,
        String reviewer,
        String reviewNote,
        String reviewTime
) {
}
