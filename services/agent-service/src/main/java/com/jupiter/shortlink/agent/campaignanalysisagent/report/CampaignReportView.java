package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** The same immutable presentation for current output, exact history and export. */
public record CampaignReportView(String schemaVersion, CampaignReportPublisher.ReportRef reportRef,
        String runId, String planId, int planRevision, List<Module> modules,
        Map<String, ReportBlock> blocksById, List<GoalAssessment> goalAssessments, List<String> limitations) {
    public static final String SCHEMA = "campaign-report-view/v2";
    public CampaignReportView {
        if (!SCHEMA.equals(schemaVersion) || reportRef == null || runId == null || planId == null || planRevision < 1)
            throw new IllegalArgumentException("REPORT_VIEW_INVALID");
        modules = List.copyOf(modules);
        blocksById = Collections.unmodifiableMap(new LinkedHashMap<>(blocksById));
        goalAssessments = List.copyOf(goalAssessments); limitations = List.copyOf(limitations);
        for (Module module : modules) for (String id : module.blockIds())
            if (!blocksById.containsKey(id)) throw new IllegalArgumentException("REPORT_BLOCK_NOT_FOUND");
    }

    public record Module(String goalId, int order, String title, GoalAssessment.Status status,
            String reasonCode, List<String> blockIds, List<String> limitations) {
        public Module { blockIds = List.copyOf(blockIds); limitations = List.copyOf(limitations); }
    }

    public static CampaignReportView from(CampaignReportReadProjection.Snapshot snapshot) {
        ReportDraft draft = snapshot.draft();
        List<ReportSection> sections = draft.sections().stream().sorted(Comparator.comparingInt(ReportSection::order)).toList();
        Map<String, ReportBlock> blocks = new LinkedHashMap<>();
        for (ReportSection section : sections) for (ReportBlock block : section.blocks()) {
            ReportBlock previous = blocks.putIfAbsent(block.blockId(), block);
            if (previous != null && !previous.equals(block)) throw new IllegalArgumentException("REPORT_BLOCK_CONFLICT");
        }
        List<Module> modules = new ArrayList<>();
        LinkedHashSet<String> limitations = new LinkedHashSet<>();
        // GoalAssessor stores these in the original Plan goal order, independently of lexical IDs.
        for (GoalAssessment goal : snapshot.goalAssessments()) {
            List<ReportSection> owned = sections.stream().filter(section -> section.goalIds().contains(goal.goalId())).toList();
            List<String> blockIds = owned.stream().flatMap(section -> section.blocks().stream())
                    .map(ReportBlock::blockId).distinct().toList();
            modules.add(new Module(goal.goalId(), modules.size(), owned.isEmpty() ? goal.goalId() : owned.get(0).title(),
                    goal.status(), goal.reasonCode(), blockIds, goal.limitations()));
            limitations.addAll(goal.limitations());
        }
        return new CampaignReportView(SCHEMA, snapshot.reportRef(), draft.runId(), draft.planId(), draft.planRevision(),
                modules, blocks, snapshot.goalAssessments(), List.copyOf(limitations));
    }
}
