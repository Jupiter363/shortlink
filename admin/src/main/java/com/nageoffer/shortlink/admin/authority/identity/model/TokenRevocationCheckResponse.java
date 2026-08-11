package com.nageoffer.shortlink.admin.authority.identity.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenRevocationCheckResponse(
        boolean active,
        String sessionId,
        long grantVersion,
        String tokenId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant checkedAt,
        String reasonCode
) {
}
