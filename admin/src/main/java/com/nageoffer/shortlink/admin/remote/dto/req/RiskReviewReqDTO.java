package com.nageoffer.shortlink.admin.remote.dto.req;

import lombok.Data;

@Data
public class RiskReviewReqDTO {

    private String eventId;

    private String targetType;

    private String gid;

    private String domain;

    private String shortUri;

    private String fullShortUrl;

    private String reviewAction;

    private String reviewer;

    private String reviewNote;
}
