package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import java.util.Objects;
import java.util.Optional;

/**
 * Trusted seam for reading response protocol facts from intake/run metadata.  It deliberately does
 * not accept client supplied run kind, server protocol or feature flag values.
 */
@FunctionalInterface
public interface CampaignResponseProtocolMetadataResolver {
    Optional<CampaignResponseProtocolMetadata> resolve(Request request);

    record Request(Caller caller, String sessionId, Optional<RunReference> run) {
        public Request {
            validateCaller(caller);
            requireId(sessionId, "CAMPAIGN_RESPONSE_SESSION_INVALID");
            run = run == null ? Optional.empty() : run;
        }

        public Request(Caller caller, String sessionId) {
            this(caller, sessionId, Optional.empty());
        }

        public CampaignRunHandleResolver.Request handleRequest() {
            RunReference reference = run.orElseThrow(
                    () -> new IllegalArgumentException("CAMPAIGN_DURABLE_RUN_REFERENCE_REQUIRED"));
            return new CampaignRunHandleResolver.Request(caller, sessionId, reference.runId(),
                    reference.revision(), reference.expectedPlanId());
        }

        private static void validateCaller(Caller caller) {
            Objects.requireNonNull(caller, "CAMPAIGN_RESPONSE_CALLER_REQUIRED");
            if (caller.tenantId() == null || caller.tenantId().isBlank() || caller.tenantId().length() > 96
                    || !caller.tenantId().matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")
                    || caller.subject() == null || caller.subject().isBlank() || caller.subject().length() > 128
                    || caller.subject().chars().anyMatch(Character::isISOControl) || caller.authVersion() < 1)
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CALLER_INVALID");
        }

        private static void requireId(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 96
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException(code);
        }
    }

    record RunReference(String runId, int revision, String expectedPlanId) {
        public RunReference {
            requireId(runId, "CAMPAIGN_RESPONSE_RUN_INVALID");
            if (revision < 1) throw new IllegalArgumentException("CAMPAIGN_RESPONSE_REVISION_INVALID");
            requireId(expectedPlanId, "CAMPAIGN_RESPONSE_PLAN_INVALID");
        }

        private static void requireId(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 96
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException(code);
        }
    }
}
