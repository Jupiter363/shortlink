package com.jupiter.shortlink.agent.riskpolicy.repository;

import com.jupiter.shortlink.agent.riskpolicy.model.RiskPolicy;

import java.util.Optional;

/** Legacy linkage guard only. Agent has no authoritative policy persistence. */
@Deprecated(forRemoval = true)
public final class JdbcRiskPolicyRepository {
    public JdbcRiskPolicyRepository(Object ignored) {}

    public void saveActive(RiskPolicy policy) {
        throw unavailable();
    }

    public Optional<RiskPolicy> findByPolicyId(String id) {
        throw unavailable();
    }

    public Optional<RiskPolicy> findActiveByPolicyKey(String key) {
        throw unavailable();
    }

    public void disable(String id, String trace) {
        throw unavailable();
    }

    public void expire(String id, String trace) {
        throw unavailable();
    }

    private SecurityException unavailable() {
        return new SecurityException("Policy facts belong to the authorized Command API");
    }
}
