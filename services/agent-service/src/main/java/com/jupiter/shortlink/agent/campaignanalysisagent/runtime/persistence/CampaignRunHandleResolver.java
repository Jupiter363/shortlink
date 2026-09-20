package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import java.util.Optional;

/** Read-only exact-revision resolver for server-owned response handles. */
@FunctionalInterface
public interface CampaignRunHandleResolver {
    Optional<CampaignRunHandle> resolve(Request request);

    record Request(Caller caller, String sessionId, String runId, int revision, String expectedPlanId) {
        public Request {
            if (caller == null) throw new IllegalArgumentException("CAMPAIGN_RUN_HANDLE_CALLER_REQUIRED");
            CampaignRunHandle.validateCaller(caller);
            if (sessionId == null || sessionId.isBlank() || sessionId.length() > 96
                    || !sessionId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException("CAMPAIGN_RUN_HANDLE_SESSION_INVALID");
            if (runId == null || runId.isBlank() || runId.length() > 96
                    || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException("CAMPAIGN_RUN_HANDLE_RUN_INVALID");
            if (revision < 1) throw new IllegalArgumentException("CAMPAIGN_RUN_HANDLE_REVISION_INVALID");
            if (expectedPlanId == null || expectedPlanId.isBlank() || expectedPlanId.length() > 96
                    || !expectedPlanId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException("CAMPAIGN_RUN_HANDLE_PLAN_INVALID");
        }
    }
}
