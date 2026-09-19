package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;

/** One proof-based takeover attempt; never polls, dispatches, or treats a timeout as process death. */
public interface CampaignRecoveryStore {
    enum Outcome { ACQUIRED, STOPPED, BLOCKED }

    record TakeoverResult(Outcome outcome, RunToken token, String reason, int recoveredCallbacks) {
        public TakeoverResult {
            if (outcome == null || (outcome == Outcome.ACQUIRED) != (token != null) || recoveredCallbacks < 0)
                throw new IllegalArgumentException("Invalid recovery result");
        }
    }

    /** Exact current trusted owner/auth-version and expected writer are required, including cancelled runs. */
    TakeoverResult recover(RunToken expected);
}
