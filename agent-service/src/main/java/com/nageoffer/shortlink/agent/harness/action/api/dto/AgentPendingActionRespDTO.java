package com.nageoffer.shortlink.agent.harness.action.api.dto;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AgentPendingActionRespDTO(
        String actionId,
        String agentType,
        String actionType,
        AgentActionStatus status,
        String gid,
        String targetType,
        Map<String, Object> target,
        String title,
        String summary,
        Map<String, Object> evidenceSummary,
        int attemptCount,
        long version,
        LocalDateTime expireTime,
        String rejectionReason,
        String rejectionReviewAction,
        Map<String, Object> result,
        Map<String, Object> failure
) {

    public AgentPendingActionRespDTO {
        target = immutableMap(target);
        evidenceSummary = immutableMap(evidenceSummary);
        result = immutableMap(result);
        failure = failure == null ? null : immutableMap(failure);
    }

    public static AgentPendingActionRespDTO from(AgentPendingActionView view) {
        Objects.requireNonNull(view, "view must not be null");
        return new AgentPendingActionRespDTO(
                view.actionId(),
                view.agentType(),
                view.actionType(),
                view.status(),
                view.gid(),
                view.targetType(),
                view.target(),
                view.title(),
                view.summary(),
                view.evidenceSummary(),
                view.attemptCount(),
                view.version(),
                view.expireTime(),
                view.rejectionReason(),
                view.rejectionReviewAction(),
                view.result(),
                view.failure()
        );
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
