package com.nageoffer.shortlink.agent.riskanalysis.job;

public class RiskAnalysisJobLeaseLostException extends RuntimeException {

    public RiskAnalysisJobLeaseLostException(String jobId) {
        super("Risk analysis job lease was lost for " + jobId);
    }

    public RiskAnalysisJobLeaseLostException(String jobId, Throwable cause) {
        super("Risk analysis job lease was lost for " + jobId, cause);
    }
}
