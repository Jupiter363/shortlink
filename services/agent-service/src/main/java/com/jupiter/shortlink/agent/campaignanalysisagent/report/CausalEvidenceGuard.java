package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.util.List;
import java.util.Map;

/**
 * Keeps a causal requirement separate from an observational analysis block. A joint distribution
 * or a correlation chart is useful evidence, but it is not a causal method by itself.
 */
public final class CausalEvidenceGuard {
    private CausalEvidenceGuard() {}

    public static boolean supports(ReportBlock block) {
        if (block == null || block.kind() != ReportBlock.Kind.ANALYSIS
                || block.evidenceArtifactIds().isEmpty()) return false;
        Map<String, Object> payload = block.payload();
        if (!"CAUSAL".equals(text(payload.get("claimType")))) return false;
        String method = text(payload.get("method"));
        if (method.isEmpty() || SetLike.correlationOrDistribution(method)) return false;
        Object coverage = payload.get("coverage");
        if (!(coverage instanceof Map<?, ?> values) || values.isEmpty()) return false;
        Object status = values.get("status");
        if (status != null && !"COMPLETE".equals(text(status))) return false;
        Object limitations = payload.get("limitations");
        return limitations instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(item -> !text(item).isEmpty());
    }

    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }

    private static final class SetLike {
        private static boolean correlationOrDistribution(String method) {
            String canonical = method.toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            return canonical.contains("correlation") || canonical.contains("joint_distribution")
                    || canonical.contains("联合分布") || canonical.contains("相关");
        }
    }
}
