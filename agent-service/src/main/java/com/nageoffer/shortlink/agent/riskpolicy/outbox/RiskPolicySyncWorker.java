package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class RiskPolicySyncWorker {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcRiskPolicySyncOutboxRepository outboxRepository;
    private final RiskPolicySyncService syncService;
    private final AgentProperties properties;
    private final Clock clock;
    private final Supplier<String> ownerTokenSupplier;

    @Autowired
    public RiskPolicySyncWorker(
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            RiskPolicySyncService syncService,
            AgentProperties properties
    ) {
        this(
                outboxRepository,
                syncService,
                properties,
                Clock.system(SHANGHAI),
                () -> UUID.randomUUID().toString()
        );
    }

    public RiskPolicySyncWorker(
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            RiskPolicySyncService syncService,
            AgentProperties properties,
            Clock clock,
            Supplier<String> ownerTokenSupplier
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.syncService = Objects.requireNonNull(syncService, "syncService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ownerTokenSupplier = Objects.requireNonNull(ownerTokenSupplier, "ownerTokenSupplier must not be null");
    }

    public boolean runNext() {
        AgentProperties.PolicySync config = properties.getRisk().getPolicySync();
        if (config == null || !config.isEnabled()) {
            return false;
        }
        LocalDateTime now = now();
        String ownerToken = ownerTokenSupplier.get();
        RiskPolicySyncOutbox outbox = outboxRepository.claimNext(
                ownerToken,
                now,
                Duration.ofMinutes(Math.max(1, config.getLeaseMinutes())),
                Math.max(1, config.getMaxAttempts())
        ).orElse(null);
        if (outbox == null) {
            return false;
        }
        try {
            syncService.process(outbox, ownerToken, now);
        } catch (RuntimeException ex) {
            LocalDateTime failureTime = now();
            syncService.recordFailure(
                    outbox,
                    ownerToken,
                    Math.max(1, config.getMaxAttempts()),
                    failureTime,
                    failureTime.plusSeconds(retryDelaySeconds(outbox.attemptCount(), config)),
                    stableError(ex)
            );
        }
        return true;
    }

    long retryDelaySeconds(int attemptCount, AgentProperties.PolicySync config) {
        long initial = Math.max(1, config.getRetryInitialSeconds());
        long maximum = Math.max(initial, config.getRetryMaxSeconds());
        int exponent = Math.max(0, Math.min(30, attemptCount - 1));
        long multiplier = 1L << exponent;
        if (initial > maximum / multiplier) {
            return maximum;
        }
        return Math.min(maximum, initial * multiplier);
    }

    private String stableError(RuntimeException ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "Risk policy Redis synchronization failed";
        }
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }
}
