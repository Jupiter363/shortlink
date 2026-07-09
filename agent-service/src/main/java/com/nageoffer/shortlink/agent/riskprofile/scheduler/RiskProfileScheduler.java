package com.nageoffer.shortlink.agent.riskprofile.scheduler;

import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class RiskProfileScheduler {

    private final RiskProfileBatchService batchService;

    private final Clock clock;

    public RiskProfileScheduler(RiskProfileBatchService batchService) {
        this(batchService, Clock.systemDefaultZone());
    }

    public RiskProfileScheduler(RiskProfileBatchService batchService, Clock clock) {
        this.batchService = batchService;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "#{@riskProfileScheduleDelayMillis}",
            initialDelayString = "#{@riskProfileScheduleDelayMillis}"
    )
    public void runRiskProfileBatch() {
        batchService.runOnce(clock.instant());
    }
}
