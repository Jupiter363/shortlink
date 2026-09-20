package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.ReplanReceiptStore;
import java.util.Objects;

/**
 * Typed, trusted application boundary for a campaign replan.
 *
 * <p>This class deliberately sits outside the chat and tool registries.  A caller must present
 * a server-resolved run token, the owner bound to that token, and the explicit replan capability
 * before the coordinator is reached.  The boundary therefore cannot turn a natural-language
 * request or an arbitrary token string into a revision write.  Spring wiring and token lookup
 * remain responsibilities of a future trusted adapter.</p>
 */
public final class CampaignReplanApplicationService {
    public static final Capability REQUIRED_CAPABILITY = Capability.CAMPAIGN_REPLAN;

    private final ReplanCoordinator coordinator;
    private final CapabilityAuthorizer authorizer;

    public CampaignReplanApplicationService(ReplanCoordinator coordinator,
                                             CapabilityAuthorizer authorizer) {
        this.coordinator = Objects.requireNonNull(coordinator, "REPLAN_COORDINATOR_REQUIRED");
        this.authorizer = Objects.requireNonNull(authorizer, "REPLAN_AUTHORIZER_REQUIRED");
    }

    /**
     * Applies one typed replan request after owner/token/capability authorization.
     *
     * <p>APPLIED and IDEMPOTENT are returned by the coordinator and remain visible to callers;
     * a coordinator-level REJECTED assessment is also returned without being converted into a
     * transport success.  Structural and authorization failures throw before any coordinator
     * operation, so they cannot create a receipt as a side effect.</p>
     */
    public Response execute(Request request) throws Exception {
        Objects.requireNonNull(request, "REPLAN_REQUEST_REQUIRED");
        validateBinding(request);
        if (!authorizer.mayExecute(request.owner(), request.runToken(), request.capability())) {
            throw new SecurityException("REPLAN_CAPABILITY_DENIED");
        }
        ReplanCoordinator.Result result = coordinator.replan(request.runToken(), request.replan(),
                request.candidate(), request.candidateAssessment());
        return Response.from(result);
    }

    private static void validateBinding(Request request) {
        CampaignRunStore.Caller owner = request.owner();
        if (owner == null || blank(owner.tenantId()) || blank(owner.subject()) || owner.authVersion() < 1) {
            throw new IllegalArgumentException("REPLAN_OWNER_INVALID");
        }
        CampaignRunStore.RunToken token = request.runToken();
        if (token == null || token.definition() == null || token.definition().caller() == null
                || !owner.equals(token.definition().caller())) {
            throw new IllegalArgumentException("REPLAN_OWNER_TOKEN_MISMATCH");
        }
        if (token.version() < 1 || blank(token.advanceToken())
                || blank(token.definition().runId()) || blank(token.definition().planId())
                || token.definition().revision() < 1) {
            throw new IllegalArgumentException("REPLAN_RUN_TOKEN_INVALID");
        }
        if (request.capability() != REQUIRED_CAPABILITY) {
            throw new IllegalArgumentException("REPLAN_CAPABILITY_INVALID");
        }
        if (request.replan() == null || request.candidate() == null || request.candidateAssessment() == null) {
            throw new IllegalArgumentException("REPLAN_TYPED_PAYLOAD_REQUIRED");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    @FunctionalInterface
    public interface CapabilityAuthorizer {
        /** Recheck current owner, run fencing and capability immediately before coordination. */
        boolean mayExecute(CampaignRunStore.Caller owner, CampaignRunStore.RunToken runToken,
                           Capability capability);
    }

    /** Explicit capability names prevent an untyped tool/chat parameter from authorizing writes. */
    public enum Capability { CAMPAIGN_REPLAN }

    /** Server-typed request; all fields are immutable and bound to one current run token. */
    public record Request(CampaignRunStore.Caller owner, CampaignRunStore.RunToken runToken,
                          Capability capability, ReplanRequest replan, PlanSpec candidate,
                          PlanningAssessment candidateAssessment) {
        public Request {
            // Structural validation is repeated at execute time because records can be retained
            // and passed across an authorization boundary after construction.
            Objects.requireNonNull(capability, "REPLAN_CAPABILITY_REQUIRED");
            Objects.requireNonNull(replan, "REPLAN_TYPED_REQUEST_REQUIRED");
            Objects.requireNonNull(candidate, "REPLAN_CANDIDATE_REQUIRED");
            Objects.requireNonNull(candidateAssessment, "REPLAN_ASSESSMENT_REQUIRED");
        }
    }

    /** Stable application result, retaining coordinator outcomes for idempotency-aware callers. */
    public record Response(ReplanCoordinator.Outcome outcome, String reasonCode,
                           ReplanReceiptStore.Receipt receipt, CampaignRunStore.RunToken run,
                           ReplanRequest.ReplanAssessment assessment) {
        public Response {
            Objects.requireNonNull(outcome, "REPLAN_OUTCOME_REQUIRED");
            if (reasonCode == null || reasonCode.isBlank())
                throw new IllegalArgumentException("REPLAN_REASON_REQUIRED");
        }

        private static Response from(ReplanCoordinator.Result result) {
            Objects.requireNonNull(result, "REPLAN_RESULT_REQUIRED");
            return new Response(result.outcome(), result.reasonCode(), result.receipt(), result.run(),
                    result.assessment());
        }

        public boolean applied() { return outcome == ReplanCoordinator.Outcome.APPLIED; }
        public boolean idempotentReplay() { return outcome == ReplanCoordinator.Outcome.IDEMPOTENT; }
        public boolean rejected() { return outcome == ReplanCoordinator.Outcome.REJECTED; }
    }
}
