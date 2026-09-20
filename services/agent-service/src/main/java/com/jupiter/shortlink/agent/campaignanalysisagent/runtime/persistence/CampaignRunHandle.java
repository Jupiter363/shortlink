package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import java.util.Objects;

/**
 * Sanitized, read-only identity for one exact campaign run revision.
 *
 * <p>The handle intentionally excludes definition JSON, row version and advance token. Those
 * values are execution credentials or durable payload, not response-routing identity.</p>
 */
public record CampaignRunHandle(Caller caller, String sessionId, String runId, String planId,
                                int revision, RunStatus status) {
    public CampaignRunHandle {
        validateCaller(caller);
        requireId(sessionId, "CAMPAIGN_RUN_HANDLE_SESSION_INVALID");
        requireId(runId, "CAMPAIGN_RUN_HANDLE_RUN_INVALID");
        requireId(planId, "CAMPAIGN_RUN_HANDLE_PLAN_INVALID");
        if (revision < 1) throw new IllegalArgumentException("CAMPAIGN_RUN_HANDLE_REVISION_INVALID");
        Objects.requireNonNull(status, "CAMPAIGN_RUN_HANDLE_STATUS_REQUIRED");
    }

    static void validateCaller(Caller caller) {
        Objects.requireNonNull(caller, "CAMPAIGN_RUN_HANDLE_CALLER_REQUIRED");
        requireId(caller.tenantId(), "CAMPAIGN_RUN_HANDLE_CALLER_INVALID");
        if (caller.subject() == null || caller.subject().isBlank() || caller.subject().length() > 128
                || caller.subject().chars().anyMatch(Character::isISOControl) || caller.authVersion() < 1)
            throw new IllegalArgumentException("CAMPAIGN_RUN_HANDLE_CALLER_INVALID");
    }

    private static void requireId(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 96
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
            throw new IllegalArgumentException(code);
    }
}
