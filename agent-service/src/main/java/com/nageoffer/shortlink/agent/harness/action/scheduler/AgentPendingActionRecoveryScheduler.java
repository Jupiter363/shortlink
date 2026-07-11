package com.nageoffer.shortlink.agent.harness.action.scheduler;

import com.nageoffer.shortlink.agent.harness.action.repository.JdbcAgentPendingActionRepository;
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
public class AgentPendingActionRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AgentPendingActionRecoveryScheduler.class);

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcAgentPendingActionRepository repository;

    private final Clock clock;

    @Autowired
    public AgentPendingActionRecoveryScheduler(JdbcAgentPendingActionRepository repository) {
        this(repository, Clock.system(SHANGHAI));
    }

    public AgentPendingActionRecoveryScheduler(
            JdbcAgentPendingActionRepository repository,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Scheduled(fixedDelayString = "${short-link.agent.action.recovery-interval-millis:60000}")
    public void maintain() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SHANGHAI);
        try {
            repository.expireDue(now);
        } catch (RuntimeException ex) {
            logSanitized("Pending action expiration maintenance failed", ex);
        }
        try {
            repository.recoverExpiredExecutions(now);
        } catch (RuntimeException ex) {
            logSanitized("Pending action execution lease maintenance failed", ex);
        }
    }

    private void logSanitized(String message, RuntimeException ex) {
        LOGGER.warn("{} ({})", message, ex.getClass().getSimpleName());
    }
}
