package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed, non-executing handoff from a gated exploration replan signal to the planner contract.
 *
 * <p>The handoff accepts a server-resolved baseline and the immutable, sanitized
 * {@link CampaignReplanCandidateGate.PendingReplan} produced by E99.  It verifies that both
 * describe the same caller, session, run, plan revision and exploration step, then delegates
 * evidence/requirement identity checks to {@link ReplanRequest#create}.  It never receives or
 * retains a {@code RunToken}, definition JSON, candidate plan, receipt, or graph state.</p>
 *
 * <p>This class is intentionally transport-neutral and stateless.  Its result is a planning
 * request plus bounded typed metadata for a later trusted planner; it is not authorization to
 * revise a run.</p>
 */
public final class CampaignReplanPlanningHandoff {
    public static final int MAX_BASELINE_EVIDENCE_IDS = 256;
    public static final int MAX_NEW_EVIDENCE = 64;
    public static final int MAX_UNSATISFIED_REQUIREMENTS = 64;
    public static final int MAX_RATIONALE_CHARACTERS = 2_048;

    /** The only candidate reason that can cross from E99 into this planning boundary. */
    public static final Set<String> ALLOWED_PENDING_REASON_CODES = Set.of("CANDIDATE_REPLAN_REQUESTED");
    /** Gap reasons are server-defined; arbitrary model text is never accepted as a reason code. */
    public static final Set<String> ALLOWED_REQUIREMENT_REASON_CODES = Set.of(
            PlanningAssessment.GapReason.NEEDS_INPUT.name(),
            PlanningAssessment.GapReason.UNSUPPORTED.name(),
            PlanningAssessment.GapReason.PLANNING_UNRESOLVED.name(),
            PlanningAssessment.GapReason.EVIDENCE_UNAVAILABLE.name());
    public static final Set<String> ALLOWED_REASON_CODES = Collections.unmodifiableSet(
            new HashSet<>(union(ALLOWED_PENDING_REASON_CODES, ALLOWED_REQUIREMENT_REASON_CODES)));

    /** Stateless convenience entry point. */
    public static Result create(Request request) {
        Objects.requireNonNull(request, "REPLAN_HANDOFF_REQUEST_REQUIRED");
        verifyPendingBinding(request.pending(), request.baseline());
        validateEvidence(request.newEvidence(), request.baseline().evidenceIds());
        validateRequirements(request.unsatisfiedRequirements(), request.baseline().assessment());
        validateRationale(request.rationale());

        // ReplanRequest is the existing typed contract and performs the final uniqueness,
        // known-gap and not-new checks.  No free-text candidate field is copied into it.
        ReplanRequest replan = ReplanRequest.create(
                request.baseline().plan(), request.baseline().assessment(), request.baseline().evidenceIds(),
                request.newEvidence(), request.unsatisfiedRequirements(), request.rationale());
        return new Result(replan, request.baseline().assessment(), request.pending().stepId(),
                request.pending().candidateHash(), request.pending().reasonCodes(),
                request.baseline().owner(), request.baseline().sessionId());
    }

    /** A native terminal candidate hash identifies the replan signal, never the not-yet-created successor Plan. */
    public static Result fromExploration(Request request) {
        Objects.requireNonNull(request, "REPLAN_HANDOFF_REQUEST_REQUIRED");
        verifyPendingBinding(request.pending(), request.baseline());
        validateEvidence(request.newEvidence(), request.baseline().evidenceIds());
        validateRequirements(request.unsatisfiedRequirements(), request.baseline().assessment());
        validateRationale(request.rationale());
        if (!request.pending().candidateHash().matches("[a-f0-9]{64}"))
            throw new SecurityException("REPLAN_EXPLORATION_SIGNAL_HASH_INVALID");
        var signal = new ReplanRequest.ExplorationSignal(request.pending().stepId(), request.pending().candidateHash());
        var replan = ReplanRequest.fromExploration(request.baseline().plan(), request.baseline().assessment(),
                request.baseline().evidenceIds(), request.newEvidence(), request.unsatisfiedRequirements(), request.rationale(), signal);
        return new Result(replan, request.baseline().assessment(), request.pending().stepId(),
                "exploration-signal:" + signal.candidateHash(), request.pending().reasonCodes(),
                request.baseline().owner(), request.baseline().sessionId());
    }

    /** Instance-shaped alias for callers that prefer an injectable-looking pure boundary. */
    public Result prepare(Request request) {
        return create(request);
    }

    private static void verifyPendingBinding(CampaignReplanCandidateGate.PendingReplan pending,
                                             Baseline baseline) {
        Objects.requireNonNull(pending, "REPLAN_HANDOFF_PENDING_REQUIRED");
        Objects.requireNonNull(baseline, "REPLAN_HANDOFF_BASELINE_REQUIRED");

        Caller owner = baseline.owner();
        CampaignRunHandle handle = pending.token();
        if (handle == null) throw new SecurityException("REPLAN_HANDOFF_HANDLE_REQUIRED");
        if (!pending.owner().equals(owner) || !handle.caller().equals(owner)) {
            throw new SecurityException("REPLAN_HANDOFF_OWNER_MISMATCH");
        }
        if (!baseline.sessionId().equals(handle.sessionId())) {
            throw new SecurityException("REPLAN_HANDOFF_SESSION_MISMATCH");
        }

        PlanSpec plan = baseline.plan();
        PlanningAssessment assessment = baseline.assessment();
        if (!handle.runId().equals(plan.runId())) {
            throw new SecurityException("REPLAN_HANDOFF_RUN_MISMATCH");
        }
        if (!handle.planId().equals(plan.planId()) || !assessment.planId().equals(plan.planId())) {
            throw new SecurityException("REPLAN_HANDOFF_PLAN_MISMATCH");
        }
        if (handle.revision() != plan.revision() || assessment.revision() != plan.revision()) {
            throw new SecurityException("REPLAN_HANDOFF_REVISION_MISMATCH");
        }
        if (handle.status() != RunStatus.ACTIVE) {
            throw new SecurityException("REPLAN_HANDOFF_RUN_NOT_ACTIVE");
        }
        if (!PlanSpec.SCHEMA_VERSION.equals(plan.schemaVersion())
                || !id(plan.planId()) || !id(plan.runId()) || !id(plan.inputSetRef())
                || plan.revision() < 1 || assessment.capabilityCatalogVersion() == null
                || assessment.capabilityCatalogVersion().isBlank()) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_BASELINE_INVALID");
        }
        if (plan.steps().stream().anyMatch(step -> step == null || !id(step.stepId()))) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_BASELINE_INVALID");
        }
        if (plan.steps().stream().noneMatch(step -> step.stepId().equals(pending.stepId()))) {
            throw new SecurityException("REPLAN_HANDOFF_STEP_MISMATCH");
        }

        validateCandidateClaim(pending);
    }

    private static void validateCandidateClaim(CampaignReplanCandidateGate.PendingReplan pending) {
        validateCandidateClaim(pending.stepId(), pending.candidateHash(), pending.reasonCodes());
    }

    private static void validateCandidateClaim(String stepId, String hash, List<String> reasons) {
        if (!id(stepId)) throw new SecurityException("REPLAN_HANDOFF_STEP_INVALID");
        if (!idLike(hash, 512)) throw new SecurityException("REPLAN_HANDOFF_CANDIDATE_HASH_INVALID");
        if (reasons == null || reasons.size() != ALLOWED_PENDING_REASON_CODES.size()
                || new HashSet<>(reasons).size() != reasons.size()
                || !new HashSet<>(reasons).equals(ALLOWED_PENDING_REASON_CODES)) {
            throw new SecurityException("REPLAN_HANDOFF_REASON_CODE_INVALID");
        }
    }

    private static void validateEvidence(List<ReplanRequest.Evidence> values, Set<String> baselineIds) {
        if (values == null || values.size() > MAX_NEW_EVIDENCE) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_EVIDENCE_LIMIT");
        }
        if (baselineIds == null || baselineIds.size() > MAX_BASELINE_EVIDENCE_IDS) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_BASELINE_EVIDENCE_LIMIT");
        }
        for (ReplanRequest.Evidence value : values) {
            if (value == null || !id(value.evidenceId()) || !id(value.artifactId())
                    || value.outputContractRef() == null || !value.outputContractRef().matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,255}")
                    || !id(value.contentHash())) {
                throw new IllegalArgumentException("REPLAN_HANDOFF_EVIDENCE_INVALID");
            }
        }
    }

    private static void validateRequirements(List<ReplanRequest.UnsatisfiedRequirement> values,
                                             PlanningAssessment baseline) {
        if (values == null || values.size() > MAX_UNSATISFIED_REQUIREMENTS) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_REQUIREMENTS_LIMIT");
        }
        for (ReplanRequest.UnsatisfiedRequirement value : values) {
            if (value == null || !id(value.requirementId())
                    || !ALLOWED_REQUIREMENT_REASON_CODES.contains(value.reasonCode())
                    || !boundedText(value.explanation(), MAX_RATIONALE_CHARACTERS)) {
                throw new IllegalArgumentException("REPLAN_HANDOFF_REQUIREMENT_INVALID");
            }
        }
        if (baseline.requirements().size() > MAX_UNSATISFIED_REQUIREMENTS
                || baseline.gaps().size() > MAX_UNSATISFIED_REQUIREMENTS) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_BASELINE_REQUIREMENTS_LIMIT");
        }
    }

    private static void validateRationale(String rationale) {
        if (!boundedText(rationale, MAX_RATIONALE_CHARACTERS)) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_RATIONALE_INVALID");
        }
    }

    private static boolean boundedText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean id(String value) {
        return idLike(value, 128);
    }

    private static boolean idLike(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> result = new TreeSet<>();
        for (Set<String> set : sets) result.addAll(set);
        return result;
    }

    /** Server-resolved baseline identity and frozen planning facts; no execution token is accepted. */
    public record Baseline(Caller owner, String sessionId, PlanSpec plan,
                           PlanningAssessment assessment, Set<String> evidenceIds) {
        public Baseline {
            validateOwner(owner);
            requireId(sessionId, "REPLAN_HANDOFF_SESSION_REQUIRED");
            Objects.requireNonNull(plan, "REPLAN_HANDOFF_PLAN_REQUIRED");
            Objects.requireNonNull(assessment, "REPLAN_HANDOFF_ASSESSMENT_REQUIRED");
            evidenceIds = immutableIds(evidenceIds);
        }
    }

    /** Input supplied by a trusted planner boundary, not by chat/model text. */
    public record Request(CampaignReplanCandidateGate.PendingReplan pending, Baseline baseline,
                          List<ReplanRequest.Evidence> newEvidence,
                          List<ReplanRequest.UnsatisfiedRequirement> unsatisfiedRequirements,
                          String rationale) {
        public Request {
            Objects.requireNonNull(pending, "REPLAN_HANDOFF_PENDING_REQUIRED");
            Objects.requireNonNull(baseline, "REPLAN_HANDOFF_BASELINE_REQUIRED");
            newEvidence = List.copyOf(Objects.requireNonNull(newEvidence, "REPLAN_HANDOFF_EVIDENCE_REQUIRED"));
            unsatisfiedRequirements = List.copyOf(Objects.requireNonNull(unsatisfiedRequirements,
                    "REPLAN_HANDOFF_REQUIREMENTS_REQUIRED"));
        }
    }

    /** Immutable typed output for the next planner stage; it carries no RunToken or definition JSON. */
    public record Result(ReplanRequest request, PlanningAssessment assessment, String stepId,
                         String expectedCandidateHash, List<String> reasonCodes,
                         Caller baselineOwner, String baselineSessionId) {
        /**
         * Compatibility constructor for callers that only have the older sanitized payload.
         * Such a result remains useful for local inspection but is intentionally rejected by
         * the candidate admission boundary because owner/session provenance is unavailable.
         */
        public Result(ReplanRequest request, PlanningAssessment assessment, String stepId,
                      String expectedCandidateHash, List<String> reasonCodes) {
            this(request, assessment, stepId, expectedCandidateHash, reasonCodes, null, null);
        }

        public Result {
            Objects.requireNonNull(request, "REPLAN_HANDOFF_REQUEST_RESULT_REQUIRED");
            Objects.requireNonNull(assessment, "REPLAN_HANDOFF_ASSESSMENT_RESULT_REQUIRED");
            requireId(stepId, "REPLAN_HANDOFF_STEP_RESULT_INVALID");
            reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes,
                    "REPLAN_HANDOFF_REASON_RESULT_REQUIRED"));
            validateCandidateClaim(stepId, expectedCandidateHash, reasonCodes);
            if (!assessment.equals(request.baseline().assessment()))
                throw new IllegalArgumentException("REPLAN_HANDOFF_ASSESSMENT_RESULT_MISMATCH");
            if (request.baseline().plan().steps().stream().noneMatch(step -> step != null
                    && stepId.equals(step.stepId()))) {
                throw new IllegalArgumentException("REPLAN_HANDOFF_STEP_RESULT_MISMATCH");
            }
            if ((baselineOwner == null) != (baselineSessionId == null)) {
                throw new SecurityException("REPLAN_HANDOFF_IDENTITY_INCOMPLETE");
            }
            if (baselineOwner != null) {
                validateOwner(baselineOwner);
                requireId(baselineSessionId, "REPLAN_HANDOFF_SESSION_REQUIRED");
            }
        }

        public boolean hasBaselineIdentity() {
            return baselineOwner != null && baselineSessionId != null;
        }
    }

    private static Set<String> immutableIds(Set<String> values) {
        Objects.requireNonNull(values, "REPLAN_HANDOFF_EVIDENCE_REQUIRED");
        if (values.size() > MAX_BASELINE_EVIDENCE_IDS)
            throw new IllegalArgumentException("REPLAN_HANDOFF_BASELINE_EVIDENCE_LIMIT");
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            if (!id(value)) throw new IllegalArgumentException("REPLAN_HANDOFF_EVIDENCE_INVALID");
            sorted.add(value);
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static void requireId(String value, String code) {
        if (!id(value)) throw new IllegalArgumentException(code);
    }

    private static void validateOwner(Caller owner) {
        Objects.requireNonNull(owner, "REPLAN_HANDOFF_OWNER_REQUIRED");
        if (!id(owner.tenantId()) || owner.subject() == null || owner.subject().isBlank()
                || owner.subject().length() > 128 || owner.subject().chars().anyMatch(Character::isISOControl)
                || owner.authVersion() < 1) {
            throw new IllegalArgumentException("REPLAN_HANDOFF_OWNER_INVALID");
        }
    }
}
