package com.nageoffer.shortlink.agent.riskanalysis.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RiskAnalysisJobScheduler {

    private final RiskAnalysisJobWorker worker;

    public RiskAnalysisJobScheduler(RiskAnalysisJobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            fixedDelayString = "${short-link.agent.risk.analysis.worker-interval-millis:5000}",
            initialDelayString = "${short-link.agent.risk.analysis.worker-interval-millis:5000}"
    )
    public void runNextJob() {
        worker.runNext();
    }
}
