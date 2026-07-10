package com.nageoffer.shortlink.agent.riskprofile.scheduler;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchCoordinator;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Component
public class RiskProfileScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskProfileScheduler.class);

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RiskProfileBatchCoordinator coordinator;

    private final JdbcRiskProfileBatchRepository batchRepository;

    private final AgentProperties agentProperties;

    private final Clock clock;

    @Autowired
    public RiskProfileScheduler(
            RiskProfileBatchCoordinator coordinator,
            JdbcRiskProfileBatchRepository batchRepository,
            AgentProperties agentProperties
    ) {
        this(
                coordinator,
                batchRepository,
                agentProperties,
                Clock.system(SHANGHAI)
        );
    }

    public RiskProfileScheduler(
            RiskProfileBatchCoordinator coordinator,
            JdbcRiskProfileBatchRepository batchRepository,
            AgentProperties agentProperties,
            Clock clock
    ) {
        this.coordinator = coordinator;
        this.batchRepository = batchRepository;
        this.agentProperties = agentProperties;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${short-link.agent.risk.profile.schedule-cron:0 0 0/2 * * *}",
            zone = "Asia/Shanghai"
    )
    public void runRiskProfileBatch() {
        Instant cycleTrigger = clock.instant();
        Instant currentWindowEnd = coordinator.alignWindowEnd(cycleTrigger);
        try {
            recoverHistoricalFailures(currentWindowEnd, cycleTrigger);
        } catch (RuntimeException ex) {
            LOGGER.warn("Risk profile failed-batch recovery scan failed", ex);
        }
        coordinator.runAlignedWindow(currentWindowEnd, clock.instant());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runCurrentWindowOnStartup() {
        runRiskProfileBatch();
    }

    private void recoverHistoricalFailures(Instant currentWindowEnd, Instant selectionTime) {
        int recoveryLimit = Math.max(
                0,
                agentProperties.getRisk().getProfile().getFailedRecoveryLimit()
        );
        if (recoveryLimit == 0) {
            return;
        }
        List<RiskProfileBatch> recoverableBatches = batchRepository.findRecoverableBefore(
                LocalDateTime.ofInstant(currentWindowEnd, SHANGHAI),
                LocalDateTime.ofInstant(selectionTime, SHANGHAI),
                recoveryLimit
        );
        for (RiskProfileBatch recoverableBatch : recoverableBatches) {
            try {
                Instant recoveryTime = clock.instant();
                batchRepository.recordRecoveryAttempt(
                        recoverableBatch.batchId(),
                        LocalDateTime.ofInstant(recoveryTime, SHANGHAI)
                );
                coordinator.retryRecoverableBatch(recoverableBatch, recoveryTime);
            } catch (RuntimeException ex) {
                LOGGER.warn(
                        "Risk profile failed-batch recovery failed for {}",
                        recoverableBatch.batchId(),
                        ex
                );
            }
        }
    }
}
