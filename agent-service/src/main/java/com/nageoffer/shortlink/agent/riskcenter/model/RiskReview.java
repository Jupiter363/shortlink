package com.nageoffer.shortlink.agent.riskcenter.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;

import java.time.LocalDateTime;

public record RiskReview(
        String reviewId,
        String eventId,
        RiskTargetType targetType,
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        RiskReviewAction reviewAction,
        String reviewer,
        String reviewNote,
        LocalDateTime reviewTime
) {

    public RiskReview {
        reviewId = valueOrEmpty(reviewId);
        eventId = valueOrEmpty(eventId);
        targetType = targetType == null ? RiskTargetType.SHORT_LINK : targetType;
        gid = valueOrEmpty(gid);
        domain = valueOrEmpty(domain);
        shortUri = valueOrEmpty(shortUri);
        fullShortUrl = valueOrEmpty(fullShortUrl);
        reviewAction = reviewAction == null ? RiskReviewAction.IGNORE : reviewAction;
        reviewer = valueOrDefault(reviewer, "unknown");
        reviewNote = valueOrEmpty(reviewNote);
        reviewTime = reviewTime == null ? LocalDateTime.now() : reviewTime;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
