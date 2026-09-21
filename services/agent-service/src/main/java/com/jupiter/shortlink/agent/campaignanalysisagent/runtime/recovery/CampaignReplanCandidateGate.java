package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCandidateStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only admission boundary for a model-requested campaign replan.
 *
 * <p>A {@code REQUEST_REPLAN} candidate is only a durable signal that a trusted planner may
 * consider later.  This class deliberately does not parse the candidate's free-text request,
 * construct a {@code ReplanRequest}, write a receipt/revision, or invoke a graph/tool.  The
 * resolver owns the authoritative ACTIVE-run lookup; this gate verifies the returned token's
 * binding once more before reading the exact candidate assessment.</p>
 *
 * <p>The instance contains no request state and performs no caching.  A caller that eventually
 * wants to execute a replan must build a complete typed request and pass it through
 * {@link CampaignReplanTrustedAdapter}.</p>
 */
public final class CampaignReplanCandidateGate {
    private final CampaignReplanTrustedAdapter.RunTokenResolver tokenResolver;
    private final CampaignExplorationCandidateStore candidates;

    public CampaignReplanCandidateGate(
            CampaignReplanTrustedAdapter.RunTokenResolver tokenResolver,
            CampaignExplorationCandidateStore candidates) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver, "REPLAN_TOKEN_RESOLVER_REQUIRED");
        this.candidates = Objects.requireNonNull(candidates, "REPLAN_CANDIDATE_STORE_REQUIRED");
    }

    /**
     * Resolves one exact, currently active run and returns a minimal pending signal when the
     * authoritative assessment is {@link CampaignExplorationCandidateStore.Verdict#REPLAN_REQUESTED}.
     * Missing or terminal runs, missing assessments, and non-replan verdicts return empty.
     * Dependency exceptions are intentionally propagated; no fallback or latest-run lookup occurs.
     */
    public Optional<PendingReplan> resolve(Request request) {
        Objects.requireNonNull(request, "REPLAN_CANDIDATE_REQUEST_REQUIRED");

        Optional<RunToken> resolved = tokenResolver.resolve(request.owner(), request.sessionId(), request.runId());
        if (resolved == null) {
            throw new IllegalStateException("REPLAN_TOKEN_RESOLUTION_INVALID");
        }
        if (resolved.isEmpty()) return Optional.empty();

        RunToken token = resolved.get();
        verifyTokenBinding(request, token);

        Optional<CampaignExplorationCandidateStore.Assessment> stored =
                candidates.assessment(token, request.stepId());
        if (stored == null || stored.isEmpty()) return Optional.empty();

        CampaignExplorationCandidateStore.Assessment assessment = stored.get();
        if (assessment == null || assessment.verdict() != CampaignExplorationCandidateStore.Verdict.REPLAN_REQUESTED) {
            return Optional.empty();
        }

        return Optional.of(new PendingReplan(request.owner(), handle(token), request.stepId(),
                boundedToken(assessment.candidateHash(), "REPLAN_CANDIDATE_HASH_INVALID"),
                boundedReasonCodes(assessment.reasonCodes())));
    }

    private static CampaignRunHandle handle(RunToken token) {
        RunDefinition definition = token.definition();
        // RunToken is deliberately kept inside this method and the candidate-store read.  A
        // definition JSON and advance token are execution credentials/payload, not a pending
        // signal.  The existing handle is the server-owned, read-only identity projection.
        return new CampaignRunHandle(definition.caller(), definition.sessionId(), definition.runId(),
                definition.planId(), definition.revision(), CampaignRunStore.RunStatus.ACTIVE);
    }

    private static void verifyTokenBinding(Request request, RunToken token) {
        if (token == null || token.definition() == null || token.definition().caller() == null
                || token.version() < 0 || token.advanceToken() == null || token.advanceToken().isBlank()) {
            throw new SecurityException("REPLAN_CANDIDATE_TOKEN_INVALID");
        }
        RunDefinition definition = token.definition();
        if (!request.owner().equals(definition.caller())
                || !request.sessionId().equals(definition.sessionId())
                || !request.runId().equals(definition.runId())) {
            throw new SecurityException("REPLAN_CANDIDATE_TOKEN_BINDING_MISMATCH");
        }
        if (definition.planId() == null || definition.planId().isBlank() || definition.revision() < 1) {
            throw new SecurityException("REPLAN_CANDIDATE_TOKEN_INVALID");
        }
    }

    private static String boundedToken(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
            throw new IllegalStateException(code);
        }
        return value;
    }

    private static List<String> boundedReasonCodes(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 32) {
            throw new IllegalStateException("REPLAN_CANDIDATE_REASON_CODES_INVALID");
        }
        return List.copyOf(values.stream().map(value -> boundedToken(value,
                "REPLAN_CANDIDATE_REASON_CODE_INVALID")).toList());
    }

    private static void validateOwner(Caller owner) {
        Objects.requireNonNull(owner, "REPLAN_OWNER_REQUIRED");
        boundedId(owner.tenantId(), "REPLAN_OWNER_INVALID");
        if (owner.subject() == null || owner.subject().isBlank() || owner.subject().length() > 128
                || owner.subject().chars().anyMatch(Character::isISOControl) || owner.authVersion() < 1) {
            throw new IllegalArgumentException("REPLAN_OWNER_INVALID");
        }
    }

    private static String boundedId(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 96
                || value.chars().anyMatch(Character::isISOControl)
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException(code);
        }
        return value;
    }

    /** Transport-resolved identity and exact exploration step; no token is accepted from callers. */
    public record Request(Caller owner, String sessionId, String runId, String stepId) {
        public Request {
            validateOwner(owner);
            boundedId(sessionId, "REPLAN_SESSION_REQUIRED");
            boundedId(runId, "REPLAN_RUN_ID_REQUIRED");
            boundedId(stepId, "REPLAN_STEP_ID_REQUIRED");
        }
    }

    /**
     * Minimal non-executable signal returned to a future trusted planner.
     *
     * <p>The component named {@code token} is a sanitized {@link CampaignRunHandle}; the exact
     * write token used for the authoritative assessment read never leaves this gate.</p>
     */
    public record PendingReplan(Caller owner, CampaignRunHandle token, String stepId,
                                String candidateHash, List<String> reasonCodes) {
        public PendingReplan {
            validateOwner(owner);
            Objects.requireNonNull(token, "REPLAN_TOKEN_REQUIRED");
            boundedId(stepId, "REPLAN_STEP_ID_REQUIRED");
            boundedToken(candidateHash, "REPLAN_CANDIDATE_HASH_INVALID");
            reasonCodes = boundedReasonCodes(reasonCodes);
        }
    }
}
