package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fixed report draft assembled from server-owned evidence. It is not public until a publisher
 * verifies every reference and computes the final goal assessments.
 */
public record ReportDraft(String schemaVersion, String reportId, int revision, String runId,
                          String planId, int planRevision, List<ReportSection> sections,
                          List<ResultEntry> resultEntries) {
    public static final String SCHEMA = "campaign-report-draft/v1";

    public ReportDraft {
        if (!SCHEMA.equals(schemaVersion) || !ref(reportId) || revision < 1 || !ref(runId)
                || !ref(planId) || planRevision < 1)
            throw new IllegalArgumentException("REPORT_DRAFT_IDENTITY_INVALID");
        sections = sections == null ? List.of() : List.copyOf(sections);
        resultEntries = resultEntries == null ? List.of() : List.copyOf(resultEntries);
        LinkedHashSet<String> sectionIds = new LinkedHashSet<>();
        Set<String> goalIds = new LinkedHashSet<>();
        for (ReportSection section : sections) {
            if (!sectionIds.add(section.sectionId())) throw new IllegalArgumentException("REPORT_SECTION_DUPLICATE");
            goalIds.addAll(section.goalIds());
        }
        LinkedHashSet<String> entryIds = new LinkedHashSet<>();
        for (ResultEntry entry : resultEntries) {
            if (!entryIds.add(entry.entryId())) throw new IllegalArgumentException("REPORT_ENTRY_DUPLICATE");
            if (!goalIds.contains(entry.goalId()) && !sections.isEmpty())
                throw new IllegalArgumentException("REPORT_ENTRY_GOAL_UNBOUND");
        }
    }

    public ReportDraft(String reportId, int revision, String runId, String planId, int planRevision,
                       List<ReportSection> sections, List<ResultEntry> resultEntries) {
        this(SCHEMA, reportId, revision, runId, planId, planRevision, sections, resultEntries);
    }

    public List<ReportBlock> blocks() {
        List<ReportBlock> result = new ArrayList<>();
        for (ReportSection section : sections) result.addAll(section.blocks());
        return List.copyOf(result);
    }

    public boolean hasDeliverable(String goalId) {
        if (goalId == null || goalId.isBlank()) return false;
        for (ReportSection section : sections) {
            if (section.goalIds().contains(goalId) && section.blocks().stream().anyMatch(ReportBlock::isDeliverable))
                return true;
        }
        return resultEntries.stream().anyMatch(entry -> goalId.equals(entry.goalId()) && entry.complete());
    }

    public boolean hasAnalysis(String goalId) {
        return sections.stream().anyMatch(section -> section.goalIds().contains(goalId)
                && section.blocks().stream().anyMatch(block -> block.kind() == ReportBlock.Kind.ANALYSIS));
    }

    public List<String> evidenceArtifactIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ReportBlock block : blocks()) result.addAll(block.evidenceArtifactIds());
        for (ResultEntry entry : resultEntries) result.add(entry.artifactId());
        return List.copyOf(result);
    }

    public record ResultEntry(String entryId, String goalId, String readAction, String artifactId, boolean complete) {
        public ResultEntry {
            if (!ref(entryId) || !ref(goalId) || readAction == null || readAction.isBlank() || !ref(artifactId))
                throw new IllegalArgumentException("REPORT_ENTRY_INVALID");
            if (!complete) throw new IllegalArgumentException("REPORT_ENTRY_INCOMPLETE");
        }
    }

    private static boolean ref(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
}
