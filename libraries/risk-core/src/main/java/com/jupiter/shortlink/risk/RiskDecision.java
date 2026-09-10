package com.jupiter.shortlink.risk;

/** Status 200 means local rules permit the request, subject to the optional global quota. */
public record RiskDecision(
        int status, String reason, long policyRevision, RateLimitRule rateLimit) {
    public boolean allowed() {
        return status == 200;
    }
}
