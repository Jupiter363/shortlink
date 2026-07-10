package com.nageoffer.shortlink.agent.securityriskagent.evidence;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class RiskEvidenceClassifier {

    public RiskEvidenceStatus classify(
            boolean evidenceRequested,
            List<Map<String, Object>> toolExecutions,
            List<?> riskCards
    ) {
        if (riskCards != null && !riskCards.isEmpty()) {
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
            return !map.isEmpty();
        }
        if (data instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (data instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (data.getClass().isArray()) {
            return Array.getLength(data) > 0;
        }
        return true;
    }
}
