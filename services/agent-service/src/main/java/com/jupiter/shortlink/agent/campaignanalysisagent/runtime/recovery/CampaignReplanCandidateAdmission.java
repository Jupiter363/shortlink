package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import java.util.List;
import java.util.Objects;

/**
 * Pure candidate admission between the planning handoff and the durable replan coordinator.
 *
 * <p>The E99 signal and E102 handoff are necessary but do not by themselves prove that a
 * newly supplied candidate is a valid successor of the frozen baseline.  This boundary binds
 * a freshly resolved, sanitized run handle, recomputes {@link ReplanRequest#assess(PlanSpec,
 * PlanningAssessment)}, and only returns an admitted candidate when that assessment is
 * {@code ACCEPTED}.  It deliberately has no receipt, revision, graph, tool, network, or
 * execution-token access.</p>
 */
public final class CampaignReplanCandidateAdmission {
    private static final String HASH = "[a-f0-9]{64}";
    private static final String OPAQUE_CLAIM = "[A-Za-z0-9][A-Za-z0-9_.:-]*";
    private static final int MAX_OPAQUE_CLAIM = 512;

    private CampaignReplanCandidateAdmission() {
        // Pure static boundary; no request state is retained.
    }

    /**
     * Admits one candidate for the exact active run represented by {@code currentHandle}.
     *
     * <p>A claim that is a canonical 64-character lower-case SHA-256 hash is verifiable and
     * must match the hash recomputed by {@code ReplanRequest.assess}.  Other bounded claims are
     * retained only as opaque correlation metadata; they never replace the computed plan hash.
     */
    public static CandidateAdmission admit(
            CampaignReplanPlanningHandoff.Result handoff,
            CampaignReplanPlanningHandoff.Baseline trustedBaseline,
            PlanSpec candidate,
            PlanningAssessment candidateAssessment,
            CampaignRunHandle currentHandle) {
        Objects.requireNonNull(handoff, "REPLAN_ADMISSION_HANDOFF_REQUIRED");
        Objects.requireNonNull(trustedBaseline, "REPLAN_ADMISSION_BASELINE_REQUIRED");
        Objects.requireNonNull(candidate, "REPLAN_ADMISSION_CANDIDATE_REQUIRED");
        Objects.requireNonNull(candidateAssessment, "REPLAN_ADMISSION_ASSESSMENT_REQUIRED");
        Objects.requireNonNull(currentHandle, "REPLAN_ADMISSION_HANDLE_REQUIRED");

        verifyBaselineMatchesHandoff(handoff, trustedBaseline);
        verifyCurrentHandle(trustedBaseline, currentHandle);

        final ReplanRequest.ReplanAssessment assessed;
        try {
            assessed = handoff.request().assess(candidate, candidateAssessment);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("REPLAN_CANDIDATE_INVALID", invalid);
        }
        if (assessed == null) {
            throw new IllegalStateException("REPLAN_CANDIDATE_ASSESSMENT_INVALID");
        }
        if (!assessed.accepted()) {
            throw new IllegalArgumentException("REPLAN_CANDIDATE_NOT_ACCEPTED:" + assessed.reasonCode());
        }

        String expected = requireOpaqueClaim(handoff.expectedCandidateHash());
        String computed = requireCanonicalHash(assessed.candidatePlanHash());
        HashBinding binding = hashBinding(expected, computed);
        return new CandidateAdmission(candidate, candidateAssessment, assessed,
                handoff.stepId(), expected, computed, binding, handoff.reasonCodes());
    }

    private static void verifyBaselineMatchesHandoff(
            CampaignReplanPlanningHandoff.Result handoff,
            CampaignReplanPlanningHandoff.Baseline trustedBaseline) {
        if (!handoff.hasBaselineIdentity()) {
            throw new SecurityException("REPLAN_ADMISSION_HANDOFF_IDENTITY_MISSING");
        }
        ReplanRequest.Baseline requestBaseline = handoff.request().baseline();
        if (!trustedBaseline.owner().equals(handoff.baselineOwner())
                || !trustedBaseline.sessionId().equals(handoff.baselineSessionId())
                || !handoff.assessment().equals(requestBaseline.assessment())
                || !trustedBaseline.plan().equals(requestBaseline.plan())
                || !trustedBaseline.assessment().equals(requestBaseline.assessment())
                || !trustedBaseline.evidenceIds().equals(requestBaseline.evidenceIds())) {
            throw new SecurityException("REPLAN_ADMISSION_BASELINE_MISMATCH");
        }
    }

    private static void verifyCurrentHandle(CampaignReplanPlanningHandoff.Baseline baseline,
                                            CampaignRunHandle handle) {
        if (handle.status() != RunStatus.ACTIVE) {
            throw new SecurityException("REPLAN_ADMISSION_RUN_NOT_ACTIVE");
        }
        if (!baseline.owner().equals(handle.caller())) {
            throw new SecurityException("REPLAN_ADMISSION_OWNER_MISMATCH");
        }
        PlanSpec plan = baseline.plan();
        PlanningAssessment assessment = baseline.assessment();
        if (!PlanSpec.SCHEMA_VERSION.equals(plan.schemaVersion()) || plan.planId() == null
                || plan.runId() == null || plan.inputSetRef() == null || plan.revision() < 1
                || assessment.planId() == null || assessment.revision() < 1) {
            throw new IllegalArgumentException("REPLAN_ADMISSION_BASELINE_INVALID");
        }
        if (!baseline.sessionId().equals(handle.sessionId())) {
            throw new SecurityException("REPLAN_ADMISSION_SESSION_MISMATCH");
        }
        if (!plan.runId().equals(handle.runId())) {
            throw new SecurityException("REPLAN_ADMISSION_RUN_MISMATCH");
        }
        if (!plan.planId().equals(handle.planId())) {
            throw new SecurityException("REPLAN_ADMISSION_PLAN_MISMATCH");
        }
        if (plan.revision() != handle.revision()) {
            throw new SecurityException("REPLAN_ADMISSION_REVISION_MISMATCH");
        }
        if (!assessment.planId().equals(plan.planId())
                || assessment.revision() != plan.revision()) {
            throw new IllegalArgumentException("REPLAN_ADMISSION_BASELINE_INVALID");
        }
    }

    private static HashBinding hashBinding(String expected, String computed) {
        if (expected.matches(HASH)) {
            if (!expected.equals(computed)) {
                throw new SecurityException("REPLAN_ADMISSION_CANDIDATE_HASH_MISMATCH");
            }
            return HashBinding.VERIFIED;
        }
        return HashBinding.OPAQUE_UNVERIFIED;
    }

    private static String requireOpaqueClaim(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_OPAQUE_CLAIM
                || !value.matches(OPAQUE_CLAIM)
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new SecurityException("REPLAN_ADMISSION_CANDIDATE_HASH_INVALID");
        }
        return value;
    }

    private static String requireCanonicalHash(String value) {
        if (value == null || !value.matches(HASH)) {
            throw new IllegalStateException("REPLAN_ADMISSION_COMPUTED_HASH_INVALID");
        }
        return value;
    }

    /** Whether the E99 claim was verifiable as the canonical candidate plan hash. */
    public enum HashBinding { VERIFIED, OPAQUE_UNVERIFIED }

    /**
     * Immutable, non-executable candidate admission.  It contains no run token or definition
     * payload; the computed hash is authoritative for any later coordinator boundary.
     */
    public record CandidateAdmission(
            PlanSpec candidate,
            PlanningAssessment candidateAssessment,
            ReplanRequest.ReplanAssessment replanAssessment,
            String stepId,
            String expectedCandidateHash,
            String candidatePlanHash,
            HashBinding hashBinding,
            List<String> reasonCodes) {
        public CandidateAdmission {
            Objects.requireNonNull(candidate, "REPLAN_ADMISSION_CANDIDATE_REQUIRED");
            Objects.requireNonNull(candidateAssessment, "REPLAN_ADMISSION_ASSESSMENT_REQUIRED");
            Objects.requireNonNull(replanAssessment, "REPLAN_ADMISSION_REPLAN_ASSESSMENT_REQUIRED");
            Objects.requireNonNull(hashBinding, "REPLAN_ADMISSION_HASH_BINDING_REQUIRED");
            if (!replanAssessment.accepted()) {
                throw new IllegalArgumentException("REPLAN_ADMISSION_NOT_ACCEPTED");
            }
            if (!candidate.planId().equals(candidateAssessment.planId())
                    || candidate.revision() != candidateAssessment.revision()
                    || !candidate.planId().equals(replanAssessment.planId())
                    || candidate.revision() != replanAssessment.candidateRevision()) {
                throw new IllegalArgumentException("REPLAN_ADMISSION_CANDIDATE_IDENTITY_MISMATCH");
            }
            String recomputed = requireCanonicalHash(ReplanRequest.planHash(candidate));
            if (!recomputed.equals(candidatePlanHash)
                    || !recomputed.equals(replanAssessment.candidatePlanHash())) {
                throw new SecurityException("REPLAN_ADMISSION_COMPUTED_HASH_MISMATCH");
            }
            String expected = requireOpaqueClaim(expectedCandidateHash);
            HashBinding expectedBinding = expected.matches(HASH)
                    ? HashBinding.VERIFIED : HashBinding.OPAQUE_UNVERIFIED;
            if (hashBinding != expectedBinding) {
                throw new IllegalArgumentException("REPLAN_ADMISSION_HASH_BINDING_INVALID");
            }
            if (expected.matches(HASH) && !expected.equals(recomputed)) {
                throw new SecurityException("REPLAN_ADMISSION_CANDIDATE_HASH_MISMATCH");
            }
            if (stepId == null || stepId.isBlank() || stepId.length() > 128
                    || !stepId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
                throw new IllegalArgumentException("REPLAN_ADMISSION_STEP_INVALID");
            }
            reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes,
                    "REPLAN_ADMISSION_REASON_CODES_REQUIRED"));
            if (!reasonCodes.equals(List.copyOf(CampaignReplanPlanningHandoff.ALLOWED_PENDING_REASON_CODES))) {
                // The handoff already canonicalizes this list; this closes the public record
                // constructor against forged output values as well.
                if (reasonCodes.size() != 1
                        || !CampaignReplanPlanningHandoff.ALLOWED_PENDING_REASON_CODES.contains(reasonCodes.get(0))) {
                    throw new SecurityException("REPLAN_ADMISSION_REASON_CODES_INVALID");
                }
            }
        }

        public boolean hashVerified() {
            return hashBinding == HashBinding.VERIFIED;
        }
    }
}
