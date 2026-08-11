package com.nageoffer.shortlink.admin.authority.identity.model;

public record TokenRevocationCheckRequest(
        String sessionId,
        long grantVersion,
        String tokenId
) {
}
