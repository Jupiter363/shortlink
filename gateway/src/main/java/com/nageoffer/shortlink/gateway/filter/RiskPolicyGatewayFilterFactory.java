package com.nageoffer.shortlink.gateway.filter;

import com.nageoffer.shortlink.gateway.config.Config;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

@Component
public class RiskPolicyGatewayFilterFactory extends AbstractGatewayFilterFactory<Config> {

    public RiskPolicyGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> chain.filter(exchange);
    }
}
