package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Component
public class RiskPolicyExpiryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskPolicyExpiryScheduler.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RiskPolicyExpiryService expiryService;
    private final AgentProperties properties;
    private final Clock clock;

    @Autowired
    public RiskPolicyExpiryScheduler(
            RiskPolicyExpiryService expiryService,
            AgentProperties properties
    ) {
        this(expiryService, properties, Clock.system(SHANGHAI));
    }

    public RiskPolicyExpiryScheduler(
            RiskPolicyExpiryService expiryService,
            AgentProperties properties,
            Clock clock
    ) {
        this.expiryService = Objects.requireNonNull(expiryService, "expiryService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Scheduled(
            fixedDelayString = "${short-link.agent.risk.policy-sync.expiry-interval-millis:60000}"
    )
    public void run() {
        AgentProperties.PolicySync config = properties.getRisk().getPolicySync();
        if (config == null || !config.isEnabled()) {
            return;
        }
        int batchSize = Math.max(1, config.getExpiryBatchSize());
        try {
            expiryService.expireBatch(now(), batchSize);
        } catch (RuntimeException ex) {
            LOGGER.warn("Risk policy expiry batch failed: {}", ex.getClass().getSimpleName());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }
}
