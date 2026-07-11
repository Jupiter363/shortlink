package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RiskPolicyActionPayloadV1(
        RiskPolicyAction action,
        String gid,
        String domain,
        String shortUri,
        String ipHash,
        String timezone,
        List<String> allowedWindows,
        String reason,
        String eventId,
        String batchId,
        LocalDateTime expireTime
) {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Risk policy action payload is invalid";

    public RiskPolicyActionPayloadV1 {
        if (allowedWindows == null) {
            allowedWindows = List.of();
        } else {
            for (String window : allowedWindows) {
                if (window == null) {
                    throw invalidPayload();
                }
            }
            allowedWindows = List.copyOf(allowedWindows);
        }
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (action != null) {
            safe.put("action", action);
        }
        putIfNotNull(safe, "gid", gid);
        putIfNotNull(safe, "domain", domain);
        putIfNotNull(safe, "shortUri", shortUri);
        putIfNotNull(safe, "reason", reason);
        putIfNotNull(safe, "eventId", eventId);
        putIfHasText(safe, "batchId", batchId);
        putIfNotNull(safe, "expireTime", expireTime);
        if (action == RiskPolicyAction.LIMIT_TIME_WINDOW) {
            putIfHasText(safe, "timezone", timezone);
            if (!allowedWindows.isEmpty()) {
                safe.put("allowedWindows", List.copyOf(allowedWindows));
            }
        } else if (action == RiskPolicyAction.BLOCK_IP) {
            putIfHasText(safe, "ipHash", ipHash);
        }
        return Collections.unmodifiableMap(safe);
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putIfHasText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static AgentActionException invalidPayload() {
        return new AgentActionException(INVALID_CODE, INVALID_MESSAGE);
    }
}
