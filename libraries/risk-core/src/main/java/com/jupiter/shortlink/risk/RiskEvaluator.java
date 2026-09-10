package com.jupiter.shortlink.risk;

import java.time.Instant;
import java.time.ZoneId;

/** Pure evaluation: no database, network, servlet, Redis or Kafka dependency. */
public final class RiskEvaluator {
    public RiskDecision evaluate(
            PolicySnapshot policy, String resourceKey, String ipHash, long now) {
        if (policy == null
                || !policy.resourceKey().equals(resourceKey)
                || !policy.authoritativeAt(now)) {
            return new RiskDecision(
                    503, "POLICY_UNKNOWN", policy == null ? 0 : policy.policyRevision(), null);
        }
        if (policy.disabled()) return deny(policy, "LINK_DISABLED");
        if (policy.blockedIpHashes().contains(ipHash)) return deny(policy, "IP_BLOCKED");
        if (!policy.timeUnrestricted()) {
            int second =
                    Instant.ofEpochMilli(now)
                            .atZone(ZoneId.of(policy.timezone()))
                            .toLocalTime()
                            .toSecondOfDay();
            if (policy.allowedWindows().stream().noneMatch(window -> window.contains(second))) {
                return deny(policy, "OUTSIDE_ALLOWED_WINDOW");
            }
        }
        return new RiskDecision(200, "ALLOWED", policy.policyRevision(), policy.rateLimit());
    }

    private RiskDecision deny(PolicySnapshot policy, String reason) {
        return new RiskDecision(403, reason, policy.policyRevision(), null);
    }
}
