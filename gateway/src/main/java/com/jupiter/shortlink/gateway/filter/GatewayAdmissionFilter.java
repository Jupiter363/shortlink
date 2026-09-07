package com.jupiter.shortlink.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;

import reactor.core.publisher.Mono;

import java.util.concurrent.Semaphore;

/**
 * Bounds session lookups, body readers and upstream requests together; admission queue length is
 * zero.
 */
@Component
public final class GatewayAdmissionFilter implements WebFilter, Ordered {
    private final Semaphore requests;

    public GatewayAdmissionFilter() {
        this(64);
    }

    GatewayAdmissionFilter(int limit) {
        requests = new Semaphore(limit);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(
                () -> {
                    if (!requests.tryAcquire()) {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        return exchange.getResponse().setComplete();
                    }
                    return Mono.defer(() -> chain.filter(exchange))
                            .doFinally(signal -> requests.release());
                });
    }
}
