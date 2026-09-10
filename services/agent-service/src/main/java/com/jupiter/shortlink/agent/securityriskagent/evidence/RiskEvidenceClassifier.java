package com.jupiter.shortlink.agent.securityriskagent.evidence;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class RiskEvidenceClassifier {

    public RiskEvidenceStatus classify(
            boolean evidenceRequested,
            List<Map<String, Object>> toolExecutions,
            List<?> riskCards) {
        if (riskCards != null && riskCards.stream().anyMatch(this::hasUsableEvidence)) {
            return RiskEvidenceStatus.AVAILABLE;
        }
        if (!evidenceRequested) {
            return RiskEvidenceStatus.NOT_REQUESTED;
        }
        boolean successfulSource = false;
        boolean failedSource = false;
        if (toolExecutions != null) {
            for (Map<String, Object> execution : toolExecutions) {
                if (execution == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(execution.get("success"))) {
                    successfulSource = true;
                    if (hasUsableEvidence(execution.get("data"))) {
                        return RiskEvidenceStatus.AVAILABLE;
                    }
                } else {
                    failedSource = true;
                }
            }
        }
        if (failedSource) {
            return RiskEvidenceStatus.SOURCE_FAILURE;
        }
        return successfulSource || evidenceRequested
                ? RiskEvidenceStatus.NO_DATA
                : RiskEvidenceStatus.NOT_REQUESTED;
    }

    public boolean hasUsableEvidence(Object data) {
        if (data == null) {
            return false;
        }
        if (data instanceof Map<?, ?> map) {
            if (map.containsKey("meta"))
                return com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence.usable(map);
            if (map.get("evidence") instanceof Map<?, ?> evidence
                    && evidence.get("meta") instanceof Map<?, ?> metadata) {
                return "AVAILABLE".equals(metadata.get("availability"))
                        && "COMPLETE".equals(metadata.get("completeness"))
                        && metadata.get("snapshotId") instanceof String id
                        && !id.isBlank();
            }
            if (map.get("shortLinkProfiles") instanceof Collection<?> profiles)
                return profiles.stream().anyMatch(this::hasUsableEvidence);
            return false;
        }
        if (data instanceof Collection<?> collection) {
            return collection.stream().anyMatch(this::hasUsableEvidence);
        }
        if (data instanceof CharSequence text) {
            return false;
        }
        if (data.getClass().isArray()) {
            return false;
        }
        return false;
    }
}
