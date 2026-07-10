package com.nageoffer.shortlink.agent.harness.action.model;

import java.util.Map;

public record AgentActionProposal(
        String actionId,
        String agentType,
        AgentActionType actionType,
        int payloadVersion,
        AgentActionAuthorizationScope authorizationScope,
        String ownerUsername,
        String gid,
        String targetType,
        String targetKey,
        Map<String, Object> targetRef,
        String title,
        String summary,
        Map<String, Object> payload,
        Map<String, Object> evidence,
        String idempotencyKey,
        String activeSlotKey,
        String proposedBy,
        String traceId,
        String eventId,
        String batchId,
        String sessionId
) {

    public AgentActionProposal {
        targetRef = AgentPendingActionView.immutableMap(targetRef);
        payload = AgentPendingActionView.immutableMap(payload);
        evidence = AgentPendingActionView.immutableMap(evidence);
    }
}
