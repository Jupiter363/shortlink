package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** A small renderable report unit. Payload is data for a generic client, never a hidden prompt. */
public record ReportBlock(String blockId, Kind kind, String title, String text,
                          Map<String, Object> payload, List<String> evidenceArtifactIds,
                          boolean completeResult) {
    public ReportBlock {
        if (blockId == null || blockId.isBlank() || !blockId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")
                || kind == null || title == null || title.isBlank())
            throw new IllegalArgumentException("REPORT_BLOCK_INVALID");
        if ((text == null || text.isBlank()) && (payload == null || payload.isEmpty()))
            throw new IllegalArgumentException("REPORT_BLOCK_EMPTY");
        payload = ReportValues.json(payload);
        evidenceArtifactIds = refs(evidenceArtifactIds);
        if (kind == Kind.RESULT_LINK && !completeResult)
            throw new IllegalArgumentException("REPORT_RESULT_LINK_INCOMPLETE");
    }

    public enum Kind { METRIC, CHART, ANALYSIS, TABLE, LIMITATION, RECOMMENDATION, RESULT_LINK }

    /** Only concrete data blocks or an authorised full-result link can satisfy DELIVERY. */
    public boolean isDeliverable() {
        return completeResult && switch (kind) {
            case METRIC, CHART, TABLE, RESULT_LINK -> true;
            case ANALYSIS, LIMITATION, RECOMMENDATION -> false;
        };
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
