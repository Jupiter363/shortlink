package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;

/** Server configuration; a model or request cannot increase an existing run's frozen allowance. */
public record ExplorationBudgetPolicy(String policyRef, String policyVersion, long maxModelTurns,
                                      long maxCalls, long maxRepairs, long maxContextBytes) {
    public ExplorationBudgetPolicy {
        if (policyRef == null || !policyRef.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                || policyVersion == null || !policyVersion.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
                || maxModelTurns < 1 || maxCalls < 1 || maxRepairs < 0 || maxContextBytes < 1)
            throw new IllegalArgumentException("EXPLORATION_BUDGET_POLICY_INVALID");
    }

    public static ExplorationBudgetPolicy defaults() {
        return new ExplorationBudgetPolicy("campaign-exploration-budget", "1", 4096, 4096, 32, 1024 * 1024);
    }

    public String hash() {
        return CampaignRunStore.sha256(policyRef.length() + ":" + policyRef + policyVersion.length() + ":" + policyVersion
                + ":" + maxModelTurns + ":" + maxCalls + ":" + maxRepairs + ":" + maxContextBytes);
    }
}
