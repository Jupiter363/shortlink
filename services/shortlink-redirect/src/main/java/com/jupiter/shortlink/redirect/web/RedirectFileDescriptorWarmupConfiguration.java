package com.jupiter.shortlink.redirect.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Disabled by default, including all Linux/proc/dev-null access. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shortlink.redirect.fd-warmup", name = "enabled", havingValue = "true")
class RedirectFileDescriptorWarmupConfiguration {
    @Bean
    RedirectFileDescriptorWarmup redirectFileDescriptorWarmup(
            @Value("${shortlink.redirect.fd-warmup.target-slots:4096}") int target) {
        return new RedirectFileDescriptorWarmup(target);
    }

    @Bean
    WebServerFactoryCustomizer<NettyReactiveWebServerFactory> redirectFdWarmupCustomizer(
            RedirectFileDescriptorWarmup warmup) {
        return factory -> factory.addServerCustomizers(http -> {
            // Boot applies this synchronously before constructing the WebServer/binding a port.
            warmup.ensureOnce();
            return http;
        });
    }
}
