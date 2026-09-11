package com.jupiter.shortlink.admin.config;

import com.jupiter.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.jupiter.shortlink.admin.common.biz.user.UserTransmitFilter;
import com.jupiter.shortlink.admin.common.biz.user.AdminIngressProperties;
import com.jupiter.shortlink.admin.common.biz.user.AdminAdmissionFilter;
import com.jupiter.shortlink.admin.common.biz.user.RequestBudgetFilter;
import com.jupiter.shortlink.admin.common.biz.user.RequestBudgetProperties;
import com.jupiter.shortlink.admin.account.AccountSessionStore;
import jakarta.servlet.DispatcherType;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminIngressProperties.class)
public class UserConfiguration {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer boundedAdminRedis(AdminIngressProperties ingress) {
        // Bounds synchronous session HGET waiting; no extra executor or duplicate session query.
        return builder -> builder.commandTimeout(ingress.getSessionTimeout())
                .clientOptions(ClientOptions.builder().autoReconnect(true)
                        .requestQueueSize(ingress.getRedisRequestQueueSize())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(SocketOptions.builder()
                        .connectTimeout(ingress.getRedisConnectTimeout()).build()).build());
    }

    @Bean
    public FilterRegistrationBean<AdminAdmissionFilter> adminAdmissionFilter(RequestBudgetProperties properties) {
        var registration = new FilterRegistrationBean<>(new AdminAdmissionFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(-100);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestBudgetFilter> adminBodyBudgetFilter(RequestBudgetProperties properties) {
        var registration = new FilterRegistrationBean<>(new RequestBudgetFilter(properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<UserTransmitFilter> globalUserTransmitFilter(
            com.jupiter.shortlink.admin.common.biz.user.TrustedManagementIdentity identities,
            AccountSessionStore sessions, AdminIngressProperties properties,
            @org.springframework.beans.factory.annotation.Value("${management.server.port:-1}") int managementPort) {
        FilterRegistrationBean<UserTransmitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new UserTransmitFilter(identities, sessions, properties, managementPort));
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AgentInternalToolApiFilter> agentInternalToolApiFilter(
            AgentAdminConfiguration agentAdminConfiguration,
            com.jupiter.shortlink.admin.dao.mapper.UserMapper users,
            @org.springframework.beans.factory.annotation.Value(
                            "${shortlink.agent.system-username:}")
                    String systemUsername) {
        FilterRegistrationBean<AgentInternalToolApiFilter> registration =
                new FilterRegistrationBean<>();
        registration.setFilter(
                new AgentInternalToolApiFilter(agentAdminConfiguration, users, systemUsername));
        registration.addUrlPatterns("/internal/short-link-admin/v1/agent-tools/*");
        registration.setOrder(1);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        return registration;
    }

}
