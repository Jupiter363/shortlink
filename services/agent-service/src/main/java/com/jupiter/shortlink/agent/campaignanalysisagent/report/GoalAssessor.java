package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic public goal assessor. It consumes server observations and a real draft; model
 * text cannot set a goal status and missing delivery for one goal never changes another goal.
 */
public final class GoalAssessor {
    public Result assess(Input input) {
        Objects.requireNonNull(input);
        PlanSpec plan = Objects.requireNonNull(input.plan());
        PlanningAssessment assessment = Objects.requireNonNull(input.planningAssessment());
        ReportDraft draft = Objects.requireNonNull(input.draft());
        if (!plan.planId().equals(assessment.planId()) || plan.revision() != assessment.revision()
                || !plan.planId().equals(draft.planId()) || plan.revision() != draft.planRevision()
                || !plan.runId().equals(draft.runId())) invalid();

        Map<String, PlanSpec.Goal> goals = new LinkedHashMap<>();
        for (PlanSpec.Goal goal : plan.goals()) if (goals.put(goal.goalId(), goal) != null) invalid();
        Map<String, PlanningAssessment.Requirement> requirements = new LinkedHashMap<>();
        for (PlanningAssessment.Requirement requirement : assessment.requirements()) {
            if (!goals.containsKey(requirement.goalId()) || requirements.put(requirement.requirementId(), requirement) != null)
                invalid();
        }
        Map<String, PlanningAssessment.Gap> gaps = new LinkedHashMap<>();
        for (PlanningAssessment.Gap gap : assessment.gaps()) {
            if (!requirements.containsKey(gap.requirementId()) || gaps.put(gap.requirementId(), gap) != null) invalid();
        }
        Map<String, RequirementObservation> observations = input.observations() == null
                ? Map.of() : Map.copyOf(input.observations());

        Map<String, Boolean> requiredFlags = new LinkedHashMap<>();
        requirements.values().forEach(requirement -> requiredFlags.put(requirement.requirementId(), requirement.required()));
        List<GoalAssessment> result = new ArrayList<>();
        for (PlanSpec.Goal goal : plan.goals()) {
            List<RequirementAssessment> assessed = new ArrayList<>();
            for (PlanningAssessment.Requirement requirement : requirements.values()) {
                if (!goal.goalId().equals(requirement.goalId())) continue;
                assessed.add(assessRequirement(requirement, gaps.get(requirement.requirementId()),
                        observations.get(requirement.requirementId()), draft, goal.goalId()));
            }
            result.add(goalAssessment(goal, assessed, requiredFlags, draft));
        }
        return new Result(result);
    }

    private RequirementAssessment assessRequirement(PlanningAssessment.Requirement requirement,
                                                    PlanningAssessment.Gap gap,
                                                    RequirementObservation observation,
                                                    ReportDraft draft, String goalId) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        List<String> limitations = new ArrayList<>();
        String reason = null;
        RequirementAssessment.Verdict verdict;
        if (gap != null) {
            verdict = RequirementAssessment.Verdict.UNKNOWN;
            reason = gapReason(gap.reason());
            limitations.add(gap.explanation());
        } else if (observation == null) {
            verdict = RequirementAssessment.Verdict.UNKNOWN;
            reason = "EVIDENCE_NOT_ASSESSED";
            limitations.add("No server-side observation was recorded for this requirement.");
        } else {
            verdict = observation.verdict();
            refs.addAll(observation.evidenceArtifactIds());
            limitations.addAll(observation.limitations());
            reason = observation.reasonCode();
        }
        if (requirement.kind() == PlanningAssessment.RequirementKind.DELIVERY) {
            boolean typedDelivery = Set.of("statistics-evidence-delivery", "selected-entities-delivery",
                    "dimension-change-delivery", "unresolved-analysis-delivery").contains(requirement.criterionRef());
            boolean matchingDelivery = !typedDelivery || (gap == null && observation != null
                    && observation.verdict() == RequirementAssessment.Verdict.MET
                    && !observation.evidenceArtifactIds().isEmpty()
                    && observation.evidenceArtifactIds().stream().allMatch(id -> draft.sections().stream()
                        .filter(section -> section.goalIds().contains(goalId)).flatMap(section -> section.blocks().stream())
                        .anyMatch(block -> block.isDeliverable() && block.evidenceArtifactIds().contains(id))));
            if (draft.hasDeliverable(goalId) && matchingDelivery) {
                verdict = RequirementAssessment.Verdict.MET;
                reason = null;
                limitations.removeIf(value -> value.equals("No server-side observation was recorded for this requirement."));
            } else {
                verdict = RequirementAssessment.Verdict.NOT_MET;
                reason = "DELIVERY_MISSING";
                limitations.add("No complete data block or authorised full-result entry is present.");
            }
        }
        if (requirement.kind() == PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE
                && verdict == RequirementAssessment.Verdict.MET && !draft.hasCausalAnalysis(goalId)) {
            verdict = RequirementAssessment.Verdict.NOT_MET;
            reason = "ANALYSIS_MISSING";
            limitations.add("A causal requirement needs a data-linked causal method block.");
        }
        return new RequirementAssessment(requirement.requirementId(), requirement.goalId(), requirement.kind(), verdict,
                requirement.criterionRef(), requirement.criterionVersion(), reason, List.copyOf(refs), limitations);
    }

    private GoalAssessment goalAssessment(PlanSpec.Goal goal, List<RequirementAssessment> assessed,
                                          Map<String, Boolean> requiredFlags, ReportDraft draft) {
        List<RequirementAssessment> required = assessed.stream().filter(requirement ->
                Boolean.TRUE.equals(requiredFlags.get(requirement.requirementId()))).toList();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        List<String> limitations = new ArrayList<>();
        for (RequirementAssessment requirement : assessed) {
            refs.addAll(requirement.evidenceArtifactIds());
            limitations.addAll(requirement.limitations());
        }
        // Draft refs are scoped by section. They may include evidence for another goal, so only
        // collect refs from sections explicitly bound to this goal.
        draft.sections().stream().filter(section -> section.goalIds().contains(goal.goalId()))
                .flatMap(section -> section.blocks().stream())
                .forEach(block -> refs.addAll(block.evidenceArtifactIds()));
        List<RequirementAssessment> requiredOnly = required;
        boolean hasEvidence = !refs.isEmpty() || draft.hasDeliverable(goal.goalId());
        boolean allSatisfied = !requiredOnly.isEmpty() && requiredOnly.stream().allMatch(requirement ->
                requirement.verdict() == RequirementAssessment.Verdict.MET
                        || requirement.verdict() == RequirementAssessment.Verdict.NOT_APPLICABLE);
        GoalAssessment.Status status;
        String reason = null;
        if (allSatisfied) status = GoalAssessment.Status.ANSWERED;
        else if (requiredOnly.stream().anyMatch(requirement -> "NEEDS_INPUT".equals(requirement.reasonCode())) && !hasEvidence)
            { status = GoalAssessment.Status.NEEDS_INPUT; reason = "NEEDS_INPUT"; }
        else if (requiredOnly.stream().anyMatch(requirement -> "UNSUPPORTED".equals(requirement.reasonCode())) && !hasEvidence)
            { status = GoalAssessment.Status.UNSUPPORTED; reason = "UNSUPPORTED"; }
        else if (requiredOnly.stream().anyMatch(requirement -> "EVIDENCE_UNAVAILABLE".equals(requirement.reasonCode())
                || "PLANNING_UNRESOLVED".equals(requirement.reasonCode())) && !hasEvidence)
            { status = GoalAssessment.Status.UNAVAILABLE; reason = "EVIDENCE_UNAVAILABLE"; }
        else if (hasEvidence) {
            status = GoalAssessment.Status.PARTIAL;
            reason = firstReason(requiredOnly, "DELIVERY_MISSING", "ANALYSIS_MISSING", "EVIDENCE_NOT_ASSESSED");
            if (reason == null) reason = "REQUIREMENT_INCOMPLETE";
        } else {
            status = GoalAssessment.Status.PENDING;
            reason = "PENDING_EVIDENCE";
        }
        return new GoalAssessment(goal.goalId(), status, reason, List.copyOf(refs), List.copyOf(limitations), assessed);
    }

    private static String firstReason(List<RequirementAssessment> requirements, String... preferred) {
        for (String candidate : preferred) if (requirements.stream().anyMatch(item -> candidate.equals(item.reasonCode()))) return candidate;
        return requirements.stream().map(RequirementAssessment::reasonCode).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static String gapReason(PlanningAssessment.GapReason reason) {
        return switch (reason) {
            case NEEDS_INPUT -> "NEEDS_INPUT";
            case UNSUPPORTED -> "UNSUPPORTED";
            case EVIDENCE_UNAVAILABLE -> "EVIDENCE_UNAVAILABLE";
            case PLANNING_UNRESOLVED -> "PLANNING_UNRESOLVED";
        };
    }

    private static void invalid() { throw new IllegalArgumentException("REPORT_DEFINITION_MISMATCH"); }

    public record Input(PlanSpec plan, PlanningAssessment planningAssessment,
                        Map<String, RequirementObservation> observations, ReportDraft draft) {
        public Input {
            observations = observations == null ? Map.of() : Map.copyOf(observations);
        }
    }

    public record RequirementObservation(RequirementAssessment.Verdict verdict, String reasonCode,
                                         List<String> evidenceArtifactIds, List<String> limitations) {
        public RequirementObservation {
            if (verdict == null) throw new IllegalArgumentException("REPORT_OBSERVATION_INVALID");
            evidenceArtifactIds = refs(evidenceArtifactIds);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            for (String limitation : limitations) if (limitation == null || limitation.isBlank())
                throw new IllegalArgumentException("REPORT_LIMITATION_INVALID");
            if (reasonCode != null && (reasonCode.isBlank() || !reasonCode.matches("[A-Z][A-Z0-9_]{0,95}")))
                throw new IllegalArgumentException("REPORT_REASON_INVALID");
        }

        public RequirementObservation(RequirementAssessment.Verdict verdict, String reasonCode,
                                      List<String> evidenceArtifactIds) {
            this(verdict, reasonCode, evidenceArtifactIds, List.of());
        }

        private static List<String> refs(List<String> values) {
            if (values == null) return List.of();
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))
                    throw new IllegalArgumentException("REPORT_ARTIFACT_REF_INVALID");
                result.add(value);
            }
            return List.copyOf(result);
        }
    }

    public record Result(List<GoalAssessment> goals) {
        public Result { goals = goals == null ? List.of() : List.copyOf(goals); }
    }
}
