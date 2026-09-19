package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import java.util.List;
import java.util.Map;

/** Requirement coverage describes a proposed path, not whether the user goal is answered. */
public record PlanningAssessment(String planId, int revision, String capabilityCatalogVersion,
                                 List<Requirement> requirements, List<CoverageBinding> coverageBindings,
                                 List<Gap> gaps) {
    public PlanningAssessment {
        requirements = ImmutablePlanValues.list(requirements);
        coverageBindings = ImmutablePlanValues.list(coverageBindings);
        gaps = ImmutablePlanValues.list(gaps);
    }

    public enum RequirementKind { DATA, CALCULATION, DELIVERY, CAUSAL_EVIDENCE }
    public enum GapReason { NEEDS_INPUT, UNSUPPORTED, PLANNING_UNRESOLVED, EVIDENCE_UNAVAILABLE }

    public record Requirement(String requirementId, String goalId, RequirementKind kind,
                              boolean required, String criterionRef, String criterionVersion,
                              Map<String, Object> parameters) {
        public Requirement {
            parameters = ImmutablePlanValues.json(parameters);
        }
    }

    public record EvidenceOutput(String stepId, String output) { }

    public record CoverageBinding(String requirementId, List<EvidenceOutput> evidenceOutputs) {
        public CoverageBinding {
            evidenceOutputs = ImmutablePlanValues.list(evidenceOutputs);
        }
    }

    public record Gap(String requirementId, GapReason reason, String explanation) { }
}
