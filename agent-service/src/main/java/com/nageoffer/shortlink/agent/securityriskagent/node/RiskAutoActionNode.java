package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyPayload;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RiskAutoActionNode {

    private static final String RISK_AUTO_ACTION_NODE = "risk_auto_action";

    private final RiskPolicyService riskPolicyService;
    private final AgentProperties properties;
    private final RiskJsonCodec jsonCodec;

    public RiskAutoActionNode(RiskPolicyService riskPolicyService, AgentProperties properties) {
        this(riskPolicyService, properties, new RiskJsonCodec());
    }

    public RiskAutoActionNode(RiskPolicyService riskPolicyService, AgentProperties properties, RiskJsonCodec jsonCodec) {
        this.riskPolicyService = riskPolicyService;
        this.properties = properties;
        this.jsonCodec = jsonCodec;
    }

    public static RiskAutoActionNode noop() {
        return new RiskAutoActionNode(null, new AgentProperties());
    }

    public Map<String, Object> apply(OverAllState state) {
        return apply(
                state.value("profileRiskContext", ProfileRiskAnalysisContext.empty()),
                state.value("eventIdsByTarget", Map.of()),
                state.value("traceId", "")
        );
    }

    public Map<String, Object> apply(
            ProfileRiskAnalysisContext context,
            Map<String, String> eventIdsByTarget,
            String traceId
    ) {
        if (context == null || context.isEmpty() || riskPolicyService == null) {
            return Map.of(
                    "activatedPolicies", List.of(),
                    "visitedNodes", List.of(RISK_AUTO_ACTION_NODE)
            );
        }
        List<Map<String, Object>> activatedPolicies = context.shortLinkProfiles().stream()
                .filter(this::canAutoLimitRate)
                .map(profile -> activateLimitRate(profile, eventIdsByTarget, traceId))
                .toList();
        return Map.of(
                "activatedPolicies", activatedPolicies,
                "visitedNodes", List.of(RISK_AUTO_ACTION_NODE)
        );
    }

    private boolean canAutoLimitRate(ShortLinkRiskProfile profile) {
        if (hasManualRecommendation(profile)) {
            return false;
        }
        return riskPolicyService.canAutoLimitRate(profile.riskLevel(), profile.riskScore(), profile.reasonCodes());
    }

    private Map<String, Object> activateLimitRate(
            ShortLinkRiskProfile profile,
            Map<String, String> eventIdsByTarget,
            String traceId
    ) {
        String eventId = eventIdsByTarget == null ? "" : eventIdsByTarget.getOrDefault(targetKey(profile), "");
        String idempotencyKey = autoIdempotencyKey(profile, eventId);
        RiskPolicy policy = riskPolicyService.activatePolicy(RiskPolicyActivationCommand.shortLink(
                autoPolicyId(idempotencyKey),
                idempotencyKey,
                RiskPolicyAction.LIMIT_RATE,
                profile.gid(),
                profile.domain(),
                profile.shortUri(),
                policyPayloadJson(profile),
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "auto limit rate for high-confidence risk profile",
                traceId,
                eventId
        ));
        Map<String, Object> activated = new LinkedHashMap<>();
        activated.put("policyId", policy.policyId());
        activated.put("action", policy.action().name());
        activated.put("domain", profile.domain());
        activated.put("shortUri", profile.shortUri());
        activated.put("eventId", eventId);
        return activated;
    }

    private String autoIdempotencyKey(ShortLinkRiskProfile profile, String eventId) {
        if (profile.batchId() != null && !profile.batchId().isBlank()) {
            return String.join(
                    ":",
                    "auto",
                    profile.batchId(),
                    profile.gid(),
                    profile.domain(),
                    profile.shortUri(),
                    RiskPolicyAction.LIMIT_RATE.name()
            );
        } else if (eventId != null && !eventId.isBlank()) {
            return String.join(
                    ":",
                    "auto",
                    eventId,
                    RiskPolicyAction.LIMIT_RATE.name()
            );
        } else {
            throw new IllegalStateException("Risk event id is required for auto action");
        }
    }

    private String autoPolicyId(String idempotencyKey) {
        return "policy-auto-rate-" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
    }

    private String policyPayloadJson(ShortLinkRiskProfile profile) {
        RiskPolicyPayload payload = new RiskPolicyPayload();
        payload.setAction(RiskPolicyAction.LIMIT_RATE);
        payload.setLimit(properties.getRisk().getAutoAction().getLimitRateLimit());
        payload.setWindowSeconds(properties.getRisk().getAutoAction().getLimitRateWindowSeconds());
        payload.setReason("Auto LIMIT_RATE for risk score " + profile.riskScore());
        return jsonCodec.toJson(payload);
    }

    private boolean hasManualRecommendation(ShortLinkRiskProfile profile) {
        return profile.latestPolicyActions().stream()
                .map(this::policyAction)
                .anyMatch(action -> action != null && action.requiresManualReview());
    }

    private RiskPolicyAction policyAction(String value) {
        try {
            return RiskPolicyAction.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String targetKey(ShortLinkRiskProfile profile) {
        return profile.domain() + "/" + profile.shortUri();
    }
}
