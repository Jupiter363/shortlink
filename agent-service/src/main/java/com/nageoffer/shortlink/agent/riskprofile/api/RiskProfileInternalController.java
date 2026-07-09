package com.nageoffer.shortlink.agent.riskprofile.api;

import com.nageoffer.shortlink.agent.common.result.Result;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchResult;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
public class RiskProfileInternalController {

    private final RiskProfileBatchService batchService;

    private final Clock clock;

    public RiskProfileInternalController(RiskProfileBatchService batchService) {
        this(batchService, Clock.systemDefaultZone());
    }

    public RiskProfileInternalController(RiskProfileBatchService batchService, Clock clock) {
        this.batchService = batchService;
        this.clock = clock;
    }

    @PostMapping("/internal/short-link-agent/v1/risk/profiles/run-once")
    public Result<RiskProfileBatchResult> runOnce() {
        return Result.success(batchService.runOnce(clock.instant()));
    }
}
