package com.nageoffer.shortlink.admin.authority.identity.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenExchangeResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("issued_token_type") String issuedTokenType,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        String scope
) {
}
