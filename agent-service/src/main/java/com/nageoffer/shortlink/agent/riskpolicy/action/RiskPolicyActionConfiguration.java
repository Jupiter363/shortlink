package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class RiskPolicyActionConfiguration {

    @Bean
    public Clock riskPolicyClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    public RiskPolicyActionExecutor disableShortLinkRiskPolicyActionExecutor(
            RiskPolicyActionPort actionPort,
            ObjectMapper objectMapper
    ) {
        return new RiskPolicyActionExecutor(
                RiskPolicyActionTypes.DISABLE_SHORT_LINK,
                actionPort,
                objectMapper
        );
    }

    @Bean
    public RiskPolicyActionExecutor limitTimeWindowRiskPolicyActionExecutor(
            RiskPolicyActionPort actionPort,
            ObjectMapper objectMapper
    ) {
        return new RiskPolicyActionExecutor(
                RiskPolicyActionTypes.LIMIT_TIME_WINDOW,
                actionPort,
                objectMapper
        );
    }

    @Bean
    public RiskPolicyActionExecutor blockIpRiskPolicyActionExecutor(
            RiskPolicyActionPort actionPort,
            ObjectMapper objectMapper
    ) {
        return new RiskPolicyActionExecutor(
                RiskPolicyActionTypes.BLOCK_IP,
                actionPort,
                objectMapper
        );
    }
}
