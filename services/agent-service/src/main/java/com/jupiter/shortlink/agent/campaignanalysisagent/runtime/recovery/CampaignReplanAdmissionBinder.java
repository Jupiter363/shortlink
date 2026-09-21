package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.Objects;

/**
 * Binds a pure E104 admission to the existing typed application request.
 *
 * <p>This is the narrow seam where a trusted runtime may add the server-resolved run token. It
 * performs no persistence, graph compilation, authorization lookup, or execution. The application
 * service and runtime factory still repeat their own fencing checks before any write.</p>
 */
public final class CampaignReplanAdmissionBinder {
    private CampaignReplanAdmissionBinder() {
    }

    /**
     * Creates an execution-shaped request only when the admission, handoff and current token
     * describe the same exact baseline. A caller-provided token or capability is never trusted by
     * this method without those identity checks.
     */
    public static CampaignReplanApplicationService.Request bind(
            CampaignReplanPlanningHandoff.Result handoff,
            CampaignReplanCandidateAdmission.CandidateAdmission admission,
            RunToken currentToken,
            CampaignReplanApplicationService.Capability capability) {
        Objects.requireNonNull(handoff, "REPLAN_BIND_HANDOFF_REQUIRED");
        Objects.requireNonNull(admission, "REPLAN_BIND_ADMISSION_REQUIRED");
        Objects.requireNonNull(currentToken, "REPLAN_BIND_TOKEN_REQUIRED");
        if (capability != CampaignReplanApplicationService.REQUIRED_CAPABILITY) {
            throw new SecurityException("REPLAN_BIND_CAPABILITY_INVALID");
        }
        if (!handoff.hasBaselineIdentity()) {
            throw new SecurityException("REPLAN_BIND_HANDOFF_IDENTITY_MISSING");
        }
        RunDefinition definition = currentToken.definition();
        if (definition == null || definition.caller() == null || currentToken.version() < 1
                || blank(currentToken.advanceToken())) {
            throw new SecurityException("REPLAN_BIND_TOKEN_INVALID");
        }
        Caller owner = handoff.baselineOwner();
        PlanSpec baseline = handoff.request().baseline().plan();
        if (!owner.equals(definition.caller())
                || !handoff.baselineSessionId().equals(definition.sessionId())
                || !baseline.runId().equals(definition.runId())
                || !baseline.planId().equals(definition.planId())
                || baseline.revision() != definition.revision()) {
            throw new SecurityException("REPLAN_BIND_IDENTITY_MISMATCH");
        }
        if (!admission.candidate().planId().equals(baseline.planId())
                || !admission.replanAssessment().accepted()
                || !admission.candidatePlanHash().equals(admission.replanAssessment().candidatePlanHash())) {
            throw new SecurityException("REPLAN_BIND_ADMISSION_MISMATCH");
        }
        return new CampaignReplanApplicationService.Request(owner, currentToken, capability,
                handoff.request(), admission.candidate(), admission.candidateAssessment());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
