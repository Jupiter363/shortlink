package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain typed adapter for a trusted replan transport.
 *
 * <p>The transport supplies an already authenticated owner and session.  It never supplies a
 * token: the resolver owns token lookup, and the runtime factory performs a second persisted
 * owner/status/token check immediately before any coordinator work.  This class deliberately has
 * no Spring, HTTP, chat, tool, or scheduler registration.</p>
 */
public final class CampaignReplanTrustedAdapter {
    private final CampaignReplanRuntimeFactory runtimeFactory;
    private final RunTokenResolver tokenResolver;

    public CampaignReplanTrustedAdapter(CampaignReplanRuntimeFactory runtimeFactory,
                                        RunTokenResolver tokenResolver) {
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "REPLAN_RUNTIME_FACTORY_REQUIRED");
        this.tokenResolver = Objects.requireNonNull(tokenResolver, "REPLAN_TOKEN_RESOLVER_REQUIRED");
    }

    /**
     * Resolves the current token and delegates one typed request to a runtime bound to that token.
     * Resolver, binding, stale-token, inactive-run, and capability failures all happen before the
     * coordinator's durable receipt/revision write boundary.
     */
    public CampaignReplanApplicationService.Response execute(Request request) throws Exception {
        Objects.requireNonNull(request, "REPLAN_TRUSTED_REQUEST_REQUIRED");
        Optional<CampaignRunStore.RunToken> resolved = tokenResolver.resolve(
                request.owner(), request.sessionId(), request.runId());
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalStateException("REPLAN_RUN_TOKEN_NOT_FOUND");
        }
        CampaignRunStore.RunToken token = resolved.get();
        verifyResolution(request, token);

        // The factory performs the persisted ACTIVE/current-token check again.  A resolver may
        // race with cancellation or another revision without turning that race into a write.
        CampaignReplanRuntime runtime = runtimeFactory.open(request.owner(), token);
        CampaignReplanApplicationService.Request typed = new CampaignReplanApplicationService.Request(
                request.owner(), token, request.capability(), request.replan(), request.candidate(),
                request.candidateAssessment());
        return runtime.execute(typed);
    }

    private static void verifyResolution(Request request, CampaignRunStore.RunToken token) {
        if (token == null || token.definition() == null || token.definition().caller() == null
                || token.version() < 1 || token.definition().revision() < 1 || token.advanceToken() == null
                || token.advanceToken().isBlank()) {
            throw new SecurityException("REPLAN_RUN_TOKEN_INVALID");
        }
        CampaignRunStore.RunDefinition definition = token.definition();
        if (!request.owner().equals(definition.caller())
                || !request.sessionId().equals(definition.sessionId())
                || !request.runId().equals(definition.runId())) {
            throw new SecurityException("REPLAN_RUN_TOKEN_BINDING_MISMATCH");
        }
    }

    /** Resolver implementation must consult the authoritative run ledger, not model/free text. */
    @FunctionalInterface
    public interface RunTokenResolver {
        Optional<CampaignRunStore.RunToken> resolve(CampaignRunStore.Caller owner,
                                                    String sessionId,
                                                    String runId);
    }

    /** Transport-resolved request; no RunToken is accepted from the caller. */
    public record Request(CampaignRunStore.Caller owner, String sessionId, String runId,
                          CampaignReplanApplicationService.Capability capability,
                          ReplanRequest replan, PlanSpec candidate,
                          PlanningAssessment candidateAssessment) {
        public Request {
            validateOwner(owner);
            requireId(sessionId, "REPLAN_SESSION_REQUIRED");
            requireId(runId, "REPLAN_RUN_ID_REQUIRED");
            Objects.requireNonNull(capability, "REPLAN_CAPABILITY_REQUIRED");
            Objects.requireNonNull(replan, "REPLAN_TYPED_REQUEST_REQUIRED");
            Objects.requireNonNull(candidate, "REPLAN_CANDIDATE_REQUIRED");
            Objects.requireNonNull(candidateAssessment, "REPLAN_ASSESSMENT_REQUIRED");
        }

        private static void validateOwner(CampaignRunStore.Caller owner) {
            Objects.requireNonNull(owner, "REPLAN_OWNER_REQUIRED");
            requireId(owner.tenantId(), "REPLAN_OWNER_INVALID");
            if (owner.subject() == null || owner.subject().isBlank() || owner.subject().length() > 128
                    || owner.authVersion() < 1) {
                throw new IllegalArgumentException("REPLAN_OWNER_INVALID");
            }
        }

        private static void requireId(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 96
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
                throw new IllegalArgumentException(code);
            }
        }
    }
}
