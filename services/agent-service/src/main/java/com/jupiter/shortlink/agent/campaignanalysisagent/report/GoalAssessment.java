package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.util.List;

/** Public, deterministic assessment of one user goal; it is independent from step status. */
public record GoalAssessment(String goalId, Status status, String reasonCode,
                             List<String> evidenceArtifactIds, List<String> limitations,
                             List<RequirementAssessment> requirements) {
    public GoalAssessment {
        if (goalId == null || goalId.isBlank() || status == null)
            throw new IllegalArgumentException("REPORT_GOAL_ASSESSMENT_INVALID");
        evidenceArtifactIds = distinctRefs(evidenceArtifactIds);
        limitations = nonblankList(limitations);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        if (reasonCode != null && (reasonCode.isBlank() || !reasonCode.matches("[A-Z][A-Z0-9_]{0,95}")))
            throw new IllegalArgumentException("REPORT_REASON_INVALID");
    }

    public enum Status { PENDING, ANSWERED, PARTIAL, NEEDS_INPUT, UNSUPPORTED, UNAVAILABLE, CANCELLED }

    private static List<String> distinctRefs(List<String> values) {
        if (values == null) return List.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))
                throw new IllegalArgumentException("REPORT_ARTIFACT_REF_INVALID");
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static List<String> nonblankList(List<String> values) {
        if (values == null) return List.of();
        for (String value : values) if (value == null || value.isBlank())
            throw new IllegalArgumentException("REPORT_LIMITATION_INVALID");
        return List.copyOf(values);
    }
}
