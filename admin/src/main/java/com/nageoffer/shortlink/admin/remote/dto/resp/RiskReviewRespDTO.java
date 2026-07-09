package com.nageoffer.shortlink.admin.remote.dto.resp;

import lombok.Data;

@Data
public class RiskReviewRespDTO {

    private String reviewId;

    private String eventId;

    private String targetType;

    private String gid;

    private String domain;

    private String shortUri;

    private String fullShortUrl;

    private String reviewAction;

    private String reviewer;

    private String reviewNote;

    private String reviewTime;
}
