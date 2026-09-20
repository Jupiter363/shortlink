package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-owned response protocol facts.  A transport may advertise client capabilities, but it
 * cannot construct these run/protocol facts and use them as evidence of an existing run.
 */
public record CampaignResponseProtocolMetadata(
        CampaignResponseCapabilityGate.RunKind runKind,
        String serverRunProtocol,
        boolean serverEnabled,
        Optional<Identity> identity) {
    public CampaignResponseProtocolMetadata {
        Objects.requireNonNull(runKind, "CAMPAIGN_RESPONSE_RUN_KIND_REQUIRED");
        if (serverRunProtocol == null)
            throw new IllegalArgumentException("CAMPAIGN_RESPONSE_PROTOCOL_REQUIRED");
        if (!serverRunProtocol.isEmpty() && serverRunProtocol.isBlank())
            throw new IllegalArgumentException("CAMPAIGN_RESPONSE_PROTOCOL_INVALID");
        if (!serverRunProtocol.isEmpty()
                && !CampaignResponseCapabilityGate.PROTOCOL_V2.equals(serverRunProtocol))
            throw new IllegalArgumentException("CAMPAIGN_RESPONSE_PROTOCOL_UNKNOWN");
        identity = identity == null ? Optional.empty() : identity;
    }

    /** Confirms that protocol metadata and the resolved read-only handle describe one run. */
    public boolean matches(CampaignRunHandle handle) {
        Objects.requireNonNull(handle, "CAMPAIGN_RESPONSE_HANDLE_REQUIRED");
        return identity.isPresent() && identity.get().matches(handle);
    }

    public record Identity(Caller caller, String sessionId, String runId, String planId, int revision) {
        public Identity {
            validateCaller(caller);
            requireId(sessionId, "CAMPAIGN_RESPONSE_SESSION_INVALID");
            requireId(runId, "CAMPAIGN_RESPONSE_RUN_INVALID");
            requireId(planId, "CAMPAIGN_RESPONSE_PLAN_INVALID");
            if (revision < 1) throw new IllegalArgumentException("CAMPAIGN_RESPONSE_REVISION_INVALID");
        }

        boolean matches(CampaignRunHandle handle) {
            return caller.equals(handle.caller()) && sessionId.equals(handle.sessionId())
                    && runId.equals(handle.runId()) && planId.equals(handle.planId())
                    && revision == handle.revision();
        }

        private static void validateCaller(Caller caller) {
            Objects.requireNonNull(caller, "CAMPAIGN_RESPONSE_CALLER_REQUIRED");
            requireId(caller.tenantId(), "CAMPAIGN_RESPONSE_CALLER_INVALID");
            if (caller.subject() == null || caller.subject().isBlank() || caller.subject().length() > 128
                    || caller.subject().chars().anyMatch(Character::isISOControl) || caller.authVersion() < 1)
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CALLER_INVALID");
        }

        private static void requireId(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 96
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
                throw new IllegalArgumentException(code);
        }
    }
}
