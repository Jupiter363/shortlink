package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Small, typed render contract for report modules. It intentionally carries no persisted payload,
 * owner, token, capability, or long generated answer.
 */
public record CampaignReportModuleResponse(String schemaVersion,
                                           CampaignRunResultProjection.ExecutionStatus executionStatus,
                                           String runId, String planId, int revision,
                                           List<Module> modules, List<String> limitations) {
    public static final String SCHEMA = "campaign-report-modules/v1";

    public CampaignReportModuleResponse {
        if (!SCHEMA.equals(schemaVersion) || executionStatus == null || blank(runId)
                || blank(planId) || revision < 1) throw new IllegalArgumentException("REPORT_MODULE_RESPONSE_INVALID");
        modules = modules == null ? List.of() : List.copyOf(modules);
        limitations = nonblank(limitations);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Module module : modules) if (module == null || !ids.add(module.goalId()))
            throw new IllegalArgumentException("REPORT_MODULE_DUPLICATE");
    }

    public record Module(String goalId, GoalAssessment.Status status, String reasonCode,
                         List<String> evidenceArtifactIds, List<RenderableBlock> blocks,
                         List<String> limitations) {
        public Module {
            if (blank(goalId) || status == null) throw new IllegalArgumentException("REPORT_MODULE_INVALID");
            evidenceArtifactIds = refs(evidenceArtifactIds);
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            limitations = nonblank(limitations);
            if (reasonCode != null && (reasonCode.isBlank() || !reasonCode.matches("[A-Z][A-Z0-9_]{0,95}")))
                throw new IllegalArgumentException("REPORT_REASON_INVALID");
            if (status == GoalAssessment.Status.ANSWERED
                    && (evidenceArtifactIds.isEmpty() || blocks.stream().noneMatch(RenderableBlock::isDeliverable)))
                throw new IllegalArgumentException("REPORT_ANSWER_DELIVERY_REQUIRED");
        }
    }

    /** Render fields only; ReportBlock payload and free text are deliberately excluded. */
    public record RenderableBlock(String blockId, ReportBlock.Kind kind, String title,
                                  List<String> evidenceArtifactIds, boolean completeResult) {
        public RenderableBlock {
            if (blank(blockId) || kind == null || blank(title))
                throw new IllegalArgumentException("REPORT_RENDER_BLOCK_INVALID");
            evidenceArtifactIds = refs(evidenceArtifactIds);
        }

        public boolean isDeliverable() {
            return completeResult && switch (kind) {
                case METRIC, CHART, TABLE, RESULT_LINK -> true;
                case ANALYSIS, LIMITATION, RECOMMENDATION -> false;
            };
        }
    }

    static List<String> refs(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (blank(value) || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))
                throw new IllegalArgumentException("REPORT_ARTIFACT_REF_INVALID");
            result.add(value);
        }
        return List.copyOf(result);
    }

    static List<String> nonblank(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) if (blank(value)) throw new IllegalArgumentException("REPORT_LIMITATION_INVALID");
        result.addAll(values);
        return List.copyOf(result);
    }

    static boolean blank(String value) { return value == null || value.isBlank(); }
}
