package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure selector that composes already-authorized facts into stable, renderable modules. */
public final class CampaignReportModuleSelector {
    public CampaignReportModuleResponse select(Request request) {
        Objects.requireNonNull(request, "REPORT_MODULE_REQUEST_REQUIRED");
        java.util.Set<String> knownGoals = request.goalAssessments().stream()
                .map(GoalAssessment::goalId).collect(java.util.stream.Collectors.toSet());
        if (knownGoals.size() != request.goalAssessments().size())
            throw new IllegalArgumentException("REPORT_MODULE_GOAL_DUPLICATE");
        java.util.Set<String> knownBlocks = request.blocks().stream()
                .map(ReportBlock::blockId).collect(java.util.stream.Collectors.toSet());
        if (knownBlocks.size() != request.blocks().size())
            throw new IllegalArgumentException("REPORT_MODULE_BLOCK_DUPLICATE");
        request.blockGoals().forEach((blockId, owners) -> {
            if (!knownBlocks.contains(blockId) || owners.size() != 1
                    || !knownGoals.contains(owners.get(0)))
                throw new IllegalArgumentException(owners.size() > 1
                        ? "REPORT_MODULE_BLOCK_SCOPE_AMBIGUOUS" : "REPORT_MODULE_SCOPE_INVALID");
        });
        Map<String, List<ReportBlock>> blocks = new LinkedHashMap<>();
        for (ReportBlock block : request.blocks()) {
            if (block == null) throw new IllegalArgumentException("REPORT_MODULE_BLOCK_INVALID");
            if (!request.blockGoals().containsKey(block.blockId()))
                throw new IllegalArgumentException("REPORT_MODULE_SCOPE_INVALID");
            List<String> owners = request.goalIdsFor(block);
            if (owners.size() != 1) throw new IllegalArgumentException("REPORT_MODULE_BLOCK_SCOPE_AMBIGUOUS");
            String goalId = owners.get(0);
            blocks.computeIfAbsent(goalId, ignored -> new ArrayList<>()).add(block);
        }
        List<CampaignReportModuleResponse.Module> modules = new ArrayList<>();
        for (GoalAssessment assessment : request.goalAssessments()) {
            List<ReportBlock> owned = blocks.getOrDefault(assessment.goalId(), List.of());
            List<String> evidence = new ArrayList<>(assessment.evidenceArtifactIds());
            List<CampaignReportModuleResponse.RenderableBlock> rendered = new ArrayList<>();
            for (ReportBlock block : owned) {
                evidence.addAll(block.evidenceArtifactIds());
                rendered.add(new CampaignReportModuleResponse.RenderableBlock(block.blockId(), block.kind(), block.title(),
                        block.evidenceArtifactIds(), block.completeResult()));
            }
            GoalAssessment.Status status = assessment.status();
            String reason = assessment.reasonCode();
            // An answer needs both scoped evidence and an actually renderable result block. A
            // GoalAssessor may have seen a draft before the transport projection was assembled;
            // the module boundary must not preserve ANSWERED when that deliverable is absent.
            boolean hasDeliverable = owned.stream().anyMatch(CampaignReportModuleSelector::deliverable);
            if (status == GoalAssessment.Status.ANSWERED && (evidence.isEmpty() || !hasDeliverable)) {
                status = request.executionStatus() == CampaignRunResultProjection.ExecutionStatus.WAITING
                        ? GoalAssessment.Status.PARTIAL
                        : (evidence.isEmpty() ? GoalAssessment.Status.UNAVAILABLE : GoalAssessment.Status.PARTIAL);
                reason = evidence.isEmpty() ? "EVIDENCE_NOT_ASSESSED" : "DELIVERY_NOT_RENDERABLE";
            }
            modules.add(new CampaignReportModuleResponse.Module(assessment.goalId(), status, reason,
                    evidence, rendered, assessment.limitations()));
        }
        modules.sort(Comparator.comparing(CampaignReportModuleResponse.Module::goalId));
        return new CampaignReportModuleResponse(CampaignReportModuleResponse.SCHEMA,
                request.executionStatus(), request.runId(), request.planId(), request.revision(), modules,
                request.limitations());
    }

    private static boolean deliverable(ReportBlock block) {
        return block.completeResult() && switch (block.kind()) {
            case METRIC, CHART, TABLE, RESULT_LINK -> true;
            case ANALYSIS, LIMITATION, RECOMMENDATION -> false;
        };
    }

    public record Request(String runId, String planId, int revision,
                          CampaignRunResultProjection.ExecutionStatus executionStatus,
                          List<GoalAssessment> goalAssessments, List<ReportBlock> blocks,
                          Map<String, List<String>> blockGoals, List<String> limitations) {
        public Request {
            if (CampaignReportModuleResponse.blank(runId) || CampaignReportModuleResponse.blank(planId)
                    || revision < 1 || executionStatus == null)
                throw new IllegalArgumentException("REPORT_MODULE_REQUEST_INVALID");
            goalAssessments = goalAssessments == null ? List.of() : List.copyOf(goalAssessments);
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            blockGoals = blockGoals == null ? Map.of() : copyBlockGoals(blockGoals);
            limitations = CampaignReportModuleResponse.nonblank(limitations);
        }

        public Request(String runId, String planId, int revision,
                       CampaignRunResultProjection.ExecutionStatus executionStatus,
                       List<GoalAssessment> goalAssessments, List<ReportBlock> blocks,
                       Map<String, List<String>> blockGoals) {
            this(runId, planId, revision, executionStatus, goalAssessments, blocks, blockGoals, List.of());
        }

        private List<String> goalIdsFor(ReportBlock block) {
            return blockGoals.getOrDefault(block.blockId(), List.of());
        }

        private static Map<String, List<String>> copyBlockGoals(Map<String, List<String>> values) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(key, CampaignReportModuleResponse.refs(value)));
            return Map.copyOf(copy);
        }
    }
}
