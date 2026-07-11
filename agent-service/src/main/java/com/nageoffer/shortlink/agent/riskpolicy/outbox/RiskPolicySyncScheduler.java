package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RiskPolicySyncScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskPolicySyncScheduler.class);

    private final RiskPolicySyncWorker worker;
    private final AgentProperties properties;

    public RiskPolicySyncScheduler(RiskPolicySyncWorker worker, AgentProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${short-link.agent.risk.policy-sync.worker-interval-millis:5000}"
    )
    public void run() {
        AgentProperties.PolicySync config = properties.getRisk().getPolicySync();
        if (config == null || !config.isEnabled()) {
            return;
        }
        try {
            worker.runNext();
        } catch (RuntimeException ex) {
            LOGGER.warn("Risk policy sync scheduler iteration failed: {}", ex.getClass().getSimpleName());
        }
    }
}
