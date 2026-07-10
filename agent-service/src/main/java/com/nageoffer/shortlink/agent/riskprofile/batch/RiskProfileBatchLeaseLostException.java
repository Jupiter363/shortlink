package com.nageoffer.shortlink.agent.riskprofile.batch;

public class RiskProfileBatchLeaseLostException extends IllegalStateException {

    public RiskProfileBatchLeaseLostException(String batchId) {
        super("Risk profile batch lease was lost for " + batchId);
    }
}
