package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * An immutable, evidence-gated request to replace a plan revision.
 *
 * <p>This is deliberately a planning contract only. It does not authorize a revision or
 * persist one; a caller must persist the request and use the accepted assessment as the
 * optimistic-concurrency proof before constructing a new graph.</p>
 */
public record ReplanRequest(String schemaVersion, Baseline baseline,
                            List<Evidence> newEvidence,
                            List<UnsatisfiedRequirement> unsatisfiedRequirements,
                            String rationale) {
    public static final String SCHEMA_VERSION = "campaign-replan-request/v1";
    private static final String TOKEN = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public ReplanRequest {
        require(SCHEMA_VERSION.equals(schemaVersion), "REPLAN_SCHEMA_INVALID");
        Objects.requireNonNull(baseline, "REPLAN_BASELINE_REQUIRED");
        newEvidence = List.copyOf(Objects.requireNonNull(newEvidence, "REPLAN_EVIDENCE_REQUIRED"));
        unsatisfiedRequirements = List.copyOf(Objects.requireNonNull(unsatisfiedRequirements,
                "REPLAN_REQUIREMENTS_REQUIRED"));
        text(rationale, "REPLAN_RATIONALE_REQUIRED");
        require(!newEvidence.isEmpty() || !unsatisfiedRequirements.isEmpty(), "REPLAN_TRIGGER_MISSING");
        uniqueEvidence(newEvidence);
        uniqueRequirements(unsatisfiedRequirements);
        Set<String> knownRequirements = new HashSet<>();
        baseline.assessment().requirements().forEach(requirement -> knownRequirements.add(requirement.requirementId()));
        Set<String> gaps = new HashSet<>();
        baseline.assessment().gaps().forEach(gap -> gaps.add(gap.requirementId()));
        for (var requirement : unsatisfiedRequirements) {
            require(knownRequirements.contains(requirement.requirementId()), "REPLAN_REQUIREMENT_UNKNOWN");
            require(gaps.contains(requirement.requirementId()), "REPLAN_REQUIREMENT_NOT_UNSATISFIED");
        }
        Set<String> previous = baseline.evidenceIds();
        for (var evidence : newEvidence) {
            require(!previous.contains(evidence.evidenceId()) && !previous.contains(evidence.artifactId()),
                    "REPLAN_EVIDENCE_NOT_NEW");
        }
    }

    /** Builds a request from the trusted current revision and its current assessment. */
    public static ReplanRequest create(PlanSpec plan, PlanningAssessment assessment,
                                       Set<String> evidenceIds, List<Evidence> newEvidence,
                                       List<UnsatisfiedRequirement> unsatisfiedRequirements,
                                       String rationale) {
        return new ReplanRequest(SCHEMA_VERSION, Baseline.of(plan, assessment, evidenceIds),
                newEvidence, unsatisfiedRequirements, rationale);
    }

    public String planId() { return baseline.plan().planId(); }
    public int baseRevision() { return baseline.plan().revision(); }
    public List<PlanSpec.Goal> originalGoals() { return baseline.plan().goals(); }
    public List<PlanningAssessment.Requirement> originalRequirements() {
        return baseline.assessment().requirements();
    }

    /** Stable request hash for a durable ledger; no mutable caller objects are retained. */
    public String requestHash() { return sha256(canonical(this)); }

    /**
     * Assesses a candidate without mutating or authorizing it. Callers must require ACCEPTED
     * before revision persistence. Every failure has a closed, non-sensitive reason code.
     */
    public ReplanAssessment assess(PlanSpec candidate, PlanningAssessment candidateAssessment) {
        if (candidate == null || candidateAssessment == null) {
            return rejected(ReasonCode.CANDIDATE_MISSING, null, null);
        }
        String candidateHash;
        try {
            candidateHash = computePlanHash(candidate);
        } catch (RuntimeException invalid) {
            return rejected(ReasonCode.CANDIDATE_INVALID, null, null);
        }
        PlanSpec current = baseline.plan();
        if (!current.planId().equals(candidate.planId()) || !current.runId().equals(candidate.runId())
                || !current.inputSetRef().equals(candidate.inputSetRef())) {
            return rejected(ReasonCode.PLAN_BINDING_CHANGED, candidate.revision(), candidateHash);
        }
        if (candidate.revision() <= current.revision()) {
            return rejected(ReasonCode.REVISION_NOT_ADVANCED, candidate.revision(), candidateHash);
        }
        if (!current.goals().equals(candidate.goals())) {
            return rejected(ReasonCode.ORIGINAL_GOALS_CHANGED, candidate.revision(), candidateHash);
        }
        if (!originalRequirements().equals(candidateAssessment.requirements())) {
            return rejected(ReasonCode.ORIGINAL_REQUIREMENTS_CHANGED, candidate.revision(), candidateHash);
        }
        if (!candidate.planId().equals(candidateAssessment.planId())
                || candidate.revision() != candidateAssessment.revision()) {
            return rejected(ReasonCode.CANDIDATE_ASSESSMENT_IDENTITY_CHANGED, candidate.revision(), candidateHash);
        }
        if (baseline.planHash().equals(candidateHash)) {
            return rejected(ReasonCode.EQUIVALENT_PLAN, candidate.revision(), candidateHash);
        }
        return new ReplanAssessment(ReplanAssessment.Status.ACCEPTED, ReasonCode.ACCEPTED,
                planId(), baseRevision(), candidate.revision(), baseline.planHash(), candidateHash,
                hash(originalGoals()), hash(originalRequirements()));
    }

    private ReplanAssessment rejected(ReasonCode reason, Integer revision, String candidateHash) {
        return new ReplanAssessment(ReplanAssessment.Status.REJECTED, reason, planId(), baseRevision(),
                revision == null ? 0 : revision, baseline.planHash(), candidateHash,
                hash(originalGoals()), hash(originalRequirements()));
    }

    public record Baseline(PlanSpec plan, PlanningAssessment assessment, Set<String> evidenceIds,
                           String planHash) {
        public Baseline {
            Objects.requireNonNull(plan, "REPLAN_PLAN_REQUIRED");
            Objects.requireNonNull(assessment, "REPLAN_ASSESSMENT_REQUIRED");
            evidenceIds = immutableTokens(evidenceIds, "REPLAN_EVIDENCE_INVALID");
            require(plan.planId().equals(assessment.planId()) && plan.revision() == assessment.revision(),
                    "REPLAN_BASELINE_IDENTITY_CHANGED");
            planHash = requireToken(planHash, "REPLAN_PLAN_HASH_INVALID");
            require(planHash.equals(ReplanRequest.computePlanHash(plan)), "REPLAN_PLAN_HASH_INVALID");
        }

        static Baseline of(PlanSpec plan, PlanningAssessment assessment, Set<String> evidenceIds) {
            Objects.requireNonNull(plan, "REPLAN_PLAN_REQUIRED");
            return new Baseline(plan, assessment, evidenceIds, ReplanRequest.computePlanHash(plan));
        }
    }

    public record Evidence(String evidenceId, String artifactId, String outputContractRef,
                            String contentHash) {
        public Evidence {
            requireToken(evidenceId, "REPLAN_EVIDENCE_INVALID");
            requireToken(artifactId, "REPLAN_EVIDENCE_INVALID");
            requireToken(outputContractRef, "REPLAN_EVIDENCE_INVALID");
            requireToken(contentHash, "REPLAN_EVIDENCE_INVALID");
        }
    }

    public record UnsatisfiedRequirement(String requirementId, String reasonCode, String explanation) {
        public UnsatisfiedRequirement {
            requireToken(requirementId, "REPLAN_REQUIREMENT_INVALID");
            requireToken(reasonCode, "REPLAN_REQUIREMENT_INVALID");
            text(explanation, "REPLAN_REQUIREMENT_INVALID");
        }
    }

    public enum ReasonCode {
        ACCEPTED, CANDIDATE_MISSING, CANDIDATE_INVALID, PLAN_BINDING_CHANGED,
        REVISION_NOT_ADVANCED, ORIGINAL_GOALS_CHANGED, ORIGINAL_REQUIREMENTS_CHANGED,
        CANDIDATE_ASSESSMENT_IDENTITY_CHANGED, EQUIVALENT_PLAN
    }

    public record ReplanAssessment(Status status, ReasonCode reasonCode, String planId,
                                   int baseRevision, int candidateRevision,
                                   String baselinePlanHash, String candidatePlanHash,
                                   String originalGoalsHash, String originalRequirementsHash) {
        public ReplanAssessment {
            Objects.requireNonNull(status); Objects.requireNonNull(reasonCode);
            requireToken(planId, "REPLAN_ASSESSMENT_INVALID");
            require(baseRevision > 0 && candidateRevision >= 0, "REPLAN_ASSESSMENT_INVALID");
            requireToken(baselinePlanHash, "REPLAN_ASSESSMENT_INVALID");
            requireToken(originalGoalsHash, "REPLAN_ASSESSMENT_INVALID");
            requireToken(originalRequirementsHash, "REPLAN_ASSESSMENT_INVALID");
            if (status == Status.ACCEPTED) {
                require(reasonCode == ReasonCode.ACCEPTED && candidateRevision > baseRevision,
                        "REPLAN_ASSESSMENT_INVALID");
                requireToken(candidatePlanHash, "REPLAN_ASSESSMENT_INVALID");
            }
        }

        public boolean accepted() { return status == Status.ACCEPTED; }
        public void requireAccepted() {
            require(accepted(), "REPLAN_NOT_ACCEPTED");
        }

        public enum Status { ACCEPTED, REJECTED }
    }

    private static void uniqueEvidence(List<Evidence> values) {
        Set<String> ids = new HashSet<>(); Set<String> artifacts = new HashSet<>();
        for (var value : values) {
            require(value != null && ids.add(value.evidenceId()) && artifacts.add(value.artifactId()),
                    "REPLAN_EVIDENCE_DUPLICATE");
        }
    }

    private static void uniqueRequirements(List<UnsatisfiedRequirement> values) {
        Set<String> ids = new HashSet<>();
        for (var value : values) require(value != null && ids.add(value.requirementId()), "REPLAN_REQUIREMENT_DUPLICATE");
    }

    private static Set<String> immutableTokens(Set<String> values, String code) {
        require(values != null, code);
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) { require(value != null && value.matches(TOKEN), code); sorted.add(value); }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    /** Plan identity excludes the mutable revision so a revision-only retry is an equivalent no-op. */
    private static String computePlanHash(PlanSpec plan) {
        return sha256(canonical(Map.of("schemaVersion", plan.schemaVersion(), "planId", plan.planId(),
                "runId", plan.runId(), "inputSetRef", plan.inputSetRef(), "goals", plan.goals(),
                "steps", plan.steps())));
    }
    private static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("REPLAN_CANONICALIZATION_FAILED"); }
    }
    private static String hash(Object value) { return sha256(canonical(value)); }
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String requireToken(String value, String code) {
        require(value != null && value.matches(TOKEN), code); return value;
    }
    private static void text(String value, String code) { require(value != null && !value.isBlank(), code); }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
}
