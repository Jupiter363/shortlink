package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskEvent;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RiskEventPersistNode {

    private static final String RISK_EVENT_PERSIST_NODE = "risk_event_persist";

    private final RiskCenterService riskCenterService;
    private final JdbcGroupRiskProfileRepository groupRepository;

    public RiskEventPersistNode(RiskCenterService riskCenterService, JdbcGroupRiskProfileRepository groupRepository) {
        this.riskCenterService = riskCenterService;
        this.groupRepository = groupRepository;
    }

    public static RiskEventPersistNode noop() {
        return new RiskEventPersistNode(null, null);
    }

    public Map<String, Object> apply(OverAllState state) {
        return persist(
                state.value("profileRiskContext", ProfileRiskAnalysisContext.empty()),
                state.value("traceId", ""),
                state.value("sessionId", ""),
                state.value("answer", "")
        );
    }

    public Map<String, Object> persist(
            ProfileRiskAnalysisContext context,
            String traceId,
            String sessionId,
            String agentSummary
    ) {
        if (context == null || context.isEmpty() || riskCenterService == null) {
            return Map.of(
                    "persistedRiskEvents", List.of(),
                    "eventIdsByTarget", Map.of(),
                    "visitedNodes", List.of(RISK_EVENT_PERSIST_NODE)
            );
        }
        Map<String, String> eventIdsByTarget = new LinkedHashMap<>();
        List<Map<String, Object>> persistedEvents = context.shortLinkProfiles().stream()
                .filter(this::shouldPersist)
                .map(profile -> persistProfile(profile, traceId, sessionId, agentSummary, eventIdsByTarget))
                .toList();
        if (groupRepository != null && context.groupProfile() != null && agentSummary != null && !agentSummary.isBlank()) {
            groupRepository.updateAgentSummary(context.groupProfile().batchId(), context.gid(), agentSummary);
        }
        return Map.of(
                "persistedRiskEvents", persistedEvents,
                "eventIdsByTarget", eventIdsByTarget,
                "visitedNodes", List.of(RISK_EVENT_PERSIST_NODE)
        );
    }

    private Map<String, Object> persistProfile(
            ShortLinkRiskProfile profile,
            String traceId,
            String sessionId,
            String agentSummary,
            Map<String, String> eventIdsByTarget
    ) {
        RiskEvent event = riskCenterService.recordSecurityRiskAgentEvent(
                profile,
                traceId,
                sessionId,
                agentSummary
        );
        riskCenterService.upsertSnapshotFromProfile(profile, event.eventId(), traceId);
        String targetKey = targetKey(profile);
        eventIdsByTarget.put(targetKey, event.eventId());
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("eventId", event.eventId());
        persisted.put("targetKey", targetKey);
        persisted.put("domain", profile.domain());
        persisted.put("shortUri", profile.shortUri());
        persisted.put("riskScore", profile.riskScore());
        persisted.put("riskLevel", profile.riskLevel().name());
        return persisted;
    }

    private boolean shouldPersist(ShortLinkRiskProfile profile) {
        return profile.riskLevel() == RiskLevel.HIGH || profile.riskLevel() == RiskLevel.MEDIUM;
    }

    private String targetKey(ShortLinkRiskProfile profile) {
        return profile.domain() + "/" + profile.shortUri();
    }
}
