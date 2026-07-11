package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record RiskActionProposalContext(
        String sourceId,
        String gid,
        String sessionId,
        String proposedBy,
        String traceId,
        Map<String, String> eventIdsByTarget,
        List<ShortLinkRiskProfile> shortLinkProfiles,
        List<RiskSignal> riskSignals,
        List<Map<String, Object>> toolExecutions,
        Optional<RiskManualActionDirective> directive
) {

    public RiskActionProposalContext {
        sourceId = valueOrEmpty(sourceId);
        gid = valueOrEmpty(gid);
        sessionId = valueOrEmpty(sessionId);
        proposedBy = valueOrEmpty(proposedBy);
        traceId = valueOrEmpty(traceId);
        eventIdsByTarget = eventIdsByTarget == null || eventIdsByTarget.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(eventIdsByTarget));
        shortLinkProfiles = shortLinkProfiles == null ? List.of() : List.copyOf(shortLinkProfiles);
        riskSignals = riskSignals == null ? List.of() : List.copyOf(riskSignals);
        toolExecutions = toolExecutions == null ? List.of() : List.copyOf(toolExecutions);
        directive = directive == null ? Optional.empty() : directive;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
