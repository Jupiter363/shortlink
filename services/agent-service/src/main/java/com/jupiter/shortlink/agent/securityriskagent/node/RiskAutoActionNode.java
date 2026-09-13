package com.jupiter.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.jupiter.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.jupiter.shortlink.agent.riskpolicy.model.RiskPolicyPayload;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;

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

    public RiskAutoActionNode(
            RiskPolicyService riskPolicyService,
            AgentProperties properties,
            RiskJsonCodec jsonCodec) {
        this.riskPolicyService = riskPolicyService;
        this.properties = properties;
        this.jsonCodec = jsonCodec;
    }

    public static RiskAutoActionNode noop() {
        return new RiskAutoActionNode(null, new AgentProperties());
    }

    public Map<String, Object> apply(OverAllState state) {
        return apply(state, () -> {});
    }

    public Map<String, Object> apply(OverAllState state, Runnable beforeAction) {
        return apply(
                state.value("profileRiskContext", ProfileRiskAnalysisContext.empty()),
                state.value("eventIdsByTarget", Map.of()),
                state.value("traceId", ""),
                AgentPrincipal.fromState(state.value("principal").orElse(null)), beforeAction);
    }

    public Map<String, Object> apply(
            ProfileRiskAnalysisContext context,
            Map<String, String> eventIdsByTarget,
            String traceId) {
        return apply(context, eventIdsByTarget, traceId, null);
    }

    public Map<String, Object> apply(
            ProfileRiskAnalysisContext context,
            Map<String, String> eventIdsByTarget,
            String traceId,
            AgentPrincipal principal) {
        return apply(context, eventIdsByTarget, traceId, principal, () -> {});
    }

    private Map<String, Object> apply(
            ProfileRiskAnalysisContext context,
            Map<String, String> eventIdsByTarget,
            String traceId,
            AgentPrincipal principal,
            Runnable beforeAction) {
        if (context == null || context.isEmpty() || riskPolicyService == null) {
            return Map.of(
                    "activatedPolicies", List.of(),
                    "visitedNodes", List.of(RISK_AUTO_ACTION_NODE));
        }
        List<Map<String, Object>> activatedPolicies =
                context.shortLinkProfiles().stream()
                        .filter(this::canAutoLimitRate)
                        .map(
                                profile -> {
                                    beforeAction.run();
                                    return activateLimitRate(profile, eventIdsByTarget, traceId, principal, beforeAction);
                                })
                        .toList();
        return Map.of(
                "activatedPolicies",
                activatedPolicies,
                "visitedNodes",
                List.of(RISK_AUTO_ACTION_NODE));
    }

    private boolean canAutoLimitRate(ShortLinkRiskProfile profile) {
        if (hasManualRecommendation(profile)) {
            return false;
        }
        return riskPolicyService.canAutoLimitRate(
                profile.riskLevel(), profile.riskScore(), profile.reasonCodes());
    }

    private Map<String, Object> activateLimitRate(
            ShortLinkRiskProfile profile,
            Map<String, String> eventIdsByTarget,
            String traceId,
            AgentPrincipal principal,
            Runnable beforeAction) {
        String eventId =
                eventIdsByTarget == null
                        ? ""
                        : eventIdsByTarget.getOrDefault(targetKey(profile), "");
        String policyId = autoPolicyId(profile, eventId, traceId);
        Map<String, Object> receipt =
                riskPolicyService.autoLimitRate(principal, profile, policyId, policyId, beforeAction);
        Map<String, Object> activated = new LinkedHashMap<>();
        activated.put("policyId", policyId);
        activated.put("commandId", policyId);
        activated.put("status", receipt.get("status"));
        activated.put("action", "LIMIT_RATE");
        activated.put("domain", profile.domain());
        activated.put("shortUri", profile.shortUri());
        activated.put("eventId", eventId);
        return activated;
    }

    private String autoPolicyId(ShortLinkRiskProfile profile, String eventId, String traceId) {
        String sourceKey =
                profile.evidence() != null
                        ? profile.evidence().tenantId()
                                + "|"
                                + profile.evidence().linkId()
                                + "|"
                                + profile.evidence().meta().get("snapshotId")
                                + "|"
                                + profile.evidence().ruleVersion()
                        : eventId == null || eventId.isBlank()
                                ? "trace|" + traceId + "|" + targetKey(profile)
                                : "event|" + eventId;
        String idempotencyKey = RiskPolicyAction.LIMIT_RATE.name() + "|" + sourceKey;
        return "policy-auto-rate-"
                + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
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
