package com.jupiter.shortlink.agent.riskpolicy.service;

import com.jupiter.shortlink.agent.riskpolicy.model.RiskPolicy;

/** Migration guard: no Redis dependency and no Spring bean. */
@Deprecated(forRemoval = true)
public final class RiskPolicyRedisPublisher {
    public RiskPolicyRedisPublisher(Object ignored) {}

    public RiskPolicyRedisPublisher(Object ignored, java.time.Clock clock) {}

    public boolean publish(RiskPolicy ignored) {
        throw new SecurityException("Policy publication belongs to Command");
    }

    public void revoke(RiskPolicy ignored) {
        throw new SecurityException("Policy revocation belongs to Command");
    }
}
