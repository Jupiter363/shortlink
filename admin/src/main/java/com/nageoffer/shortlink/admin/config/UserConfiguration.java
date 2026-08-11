package com.nageoffer.shortlink.admin.config;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenService;
import com.nageoffer.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.nageoffer.shortlink.admin.common.biz.user.UserFlowRiskControlFilter;
import com.nageoffer.shortlink.admin.common.biz.user.UserTransmitFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

@Configuration
public class UserConfiguration {

    @Bean
    public FilterRegistrationBean<UserTransmitFilter> globalUserTransmitFilter() {
        FilterRegistrationBean<UserTransmitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new UserTransmitFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AgentInternalToolApiFilter> agentInternalToolApiFilter(
            AgentAdminConfiguration agentAdminConfiguration,
            AgentIdentityConfiguration agentIdentityConfiguration,
            AgentTokenService agentTokenService,
            AgentSessionLifecycleService sessionLifecycleService) {
        FilterRegistrationBean<AgentInternalToolApiFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AgentInternalToolApiFilter(
                agentAdminConfiguration,
                agentIdentityConfiguration,
                agentTokenService,
                sessionLifecycleService
        ));
        registration.addUrlPatterns(
                "/internal/short-link-admin/v1/agent-tools/*",
                "/internal/short-link-admin/v1/agent-capabilities/*"
        );
        registration.setOrder(1);
        return registration;
    }

    @Bean("agentCapabilityClock")
    public Clock agentCapabilityClock() {
        return Clock.systemUTC();
    }

    @Bean("agentIdentityClock")
    public Clock agentIdentityClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "short-link.flow-limit.enable", havingValue = "true")
    public FilterRegistrationBean<UserFlowRiskControlFilter> globalUserFlowRiskControlFilter(
            StringRedisTemplate stringRedisTemplate,
            UserFlowRiskControlConfiguration userFlowRiskControlConfiguration) {
        FilterRegistrationBean<UserFlowRiskControlFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new UserFlowRiskControlFilter(stringRedisTemplate, userFlowRiskControlConfiguration));
        registration.addUrlPatterns("/*");
        registration.setOrder(10);
        return registration;
    }
}
