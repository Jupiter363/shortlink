package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.util.LinkedHashSet;
import java.util.List;

/** Ordered, reusable section; a section may serve multiple goals but keeps explicit evidence links. */
public record ReportSection(String sectionId, int order, String title, List<String> goalIds,
                            List<ReportBlock> blocks) {
    public ReportSection {
        if (sectionId == null || sectionId.isBlank() || !sectionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")
                || order < 0 || title == null || title.isBlank())
            throw new IllegalArgumentException("REPORT_SECTION_INVALID");
        goalIds = refs(goalIds, "REPORT_GOAL_REF_INVALID");
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        LinkedHashSet<String> blockIds = new LinkedHashSet<>();
        for (ReportBlock block : blocks) if (!blockIds.add(block.blockId()))
            throw new IllegalArgumentException("REPORT_BLOCK_DUPLICATE");
    }

    private static List<String> refs(List<String> values, String error) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))
                throw new IllegalArgumentException(error);
            result.add(value);
        }
        return List.copyOf(result);
    }
}
