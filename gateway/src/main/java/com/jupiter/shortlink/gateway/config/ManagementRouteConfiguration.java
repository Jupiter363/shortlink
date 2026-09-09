package com.jupiter.shortlink.gateway.config;

import com.jupiter.shortlink.gateway.filter.ManagementBodyBudgetFilter;
import com.jupiter.shortlink.gateway.filter.TokenValidateGatewayFilterFactory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class ManagementRouteConfiguration {
    @Bean
    RouteLocator managementRoutes(
            RouteLocatorBuilder builder,
            TokenValidateGatewayFilterFactory token,
            GatewaySecurityProperties properties,
            ManagementBodyBudgetFilter bodyBudget) {
        return builder.routes()
                .route(
                        "management-admin",
                        route ->
                                route.path(
                                                "/api/short-link/admin/v1/**",
                                                "/api/short-link/v1/user",
                                                "/api/short-link/v1/user/**")
                                        .filters(
                                                filters ->
                                                        filters.filter(
                                                                        token.apply(new Config()),
                                                                        -100)
                                                                .filter(bodyBudget, -90))
                                        .uri(properties.getAdminUri()))
                .build();
    }
}
