package com.nageoffer.shortlink.agent.riskprofile.api;

import com.nageoffer.shortlink.agent.common.result.Result;
import com.nageoffer.shortlink.agent.riskprofile.api.dto.RiskProfileBatchRespDTO;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchCoordinator;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZoneId;

@RestController
public class RiskProfileInternalController {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RiskProfileBatchCoordinator coordinator;

    private final Clock clock;

    @Autowired
    public RiskProfileInternalController(RiskProfileBatchCoordinator coordinator) {
        this(coordinator, Clock.system(SHANGHAI));
    }

    public RiskProfileInternalController(RiskProfileBatchCoordinator coordinator, Clock clock) {
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @PostMapping("/internal/short-link-agent/v1/risk/profiles/run-once")
    public Result<RiskProfileBatchRespDTO> runOnce() {
        RiskProfileBatch batch = coordinator.runOnce(clock.instant());
        RiskProfileBatchRespDTO response = RiskProfileBatchRespDTO.from(batch);
        if (batch.status() == RiskProfileBatchStatus.FAILED) {
            return Result.failure(
                    "RISK_PROFILE_BATCH_FAILED",
                    "Risk profile batch failed",
                    response
            );
        }
        if (batch.status() == RiskProfileBatchStatus.PARTIAL_SUCCESS) {
            return Result.success(
                    response,
                    "Risk profile batch completed with partial failures"
            );
        }
        return Result.success(response);
    }
}
