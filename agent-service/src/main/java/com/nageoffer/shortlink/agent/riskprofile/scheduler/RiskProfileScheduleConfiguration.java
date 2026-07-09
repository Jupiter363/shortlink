package com.nageoffer.shortlink.agent.riskprofile.scheduler;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RiskProfileScheduleConfiguration {

    private static final int DEFAULT_BATCH_INTERVAL_MINUTES = 120;

    private final AgentProperties agentProperties;

    public RiskProfileScheduleConfiguration(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    @Bean("riskProfileScheduleDelayMillis")
    public Long riskProfileScheduleDelayMillis() {
        int minutes = agentProperties.getRisk().getProfile().getBatchIntervalMinutes();
        if (minutes < 1) {
            minutes = DEFAULT_BATCH_INTERVAL_MINUTES;
        }
        return Duration.ofMinutes(minutes).toMillis();
    }
}
