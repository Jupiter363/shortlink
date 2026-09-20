package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import java.util.LinkedHashSet;
import java.util.List;

/** Evidence-backed result for one typed planning requirement. */
public record RequirementAssessment(String requirementId, String goalId,
                                    PlanningAssessment.RequirementKind kind, Verdict verdict,
                                    String criterionRef, String criterionVersion,
                                    String reasonCode, List<String> evidenceArtifactIds,
                                    List<String> limitations) {
    public RequirementAssessment {
        if (requirementId == null || requirementId.isBlank() || goalId == null || goalId.isBlank()
                || kind == null || verdict == null || criterionRef == null || criterionRef.isBlank()
                || criterionVersion == null || criterionVersion.isBlank())
            throw new IllegalArgumentException("REPORT_REQUIREMENT_ASSESSMENT_INVALID");
        if (reasonCode != null && (reasonCode.isBlank() || !reasonCode.matches("[A-Z][A-Z0-9_]{0,95}")))
            throw new IllegalArgumentException("REPORT_REASON_INVALID");
        evidenceArtifactIds = refs(evidenceArtifactIds);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        for (String limitation : limitations) if (limitation == null || limitation.isBlank())
            throw new IllegalArgumentException("REPORT_LIMITATION_INVALID");
    }

    public enum Verdict { MET, NOT_MET, UNKNOWN, NOT_APPLICABLE }

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
