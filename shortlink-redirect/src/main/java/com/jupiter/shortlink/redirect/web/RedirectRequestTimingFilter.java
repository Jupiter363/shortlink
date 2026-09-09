package com.jupiter.shortlink.redirect.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Optional sampled wall time from this filter's subscription entry to response beforeCommit.
 * Includes asynchronous handler waits; excludes queueing before the filter and response transport.
 * This is neither CPU time nor complete Netty request latency. APISIX removes this diagnostic header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
        prefix = "shortlink.redirect", name = "request-timing-enabled", havingValue = "true")
public final class RedirectRequestTimingFilter implements WebFilter {
    static final String HEADER = "X-Shortlink-Handler-Nanos";
    static final int SAMPLE_EVERY = 64;
    private final AtomicLong sequence = new AtomicLong();
    private final LongSupplier nanoTime;

    public RedirectRequestTimingFilter() {
        this(System::nanoTime);
    }

    RedirectRequestTimingFilter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(
                () -> {
                    if ((sequence.getAndIncrement() & (SAMPLE_EVERY - 1)) == 0) {
                        long entered = nanoTime.getAsLong();
                        exchange.getResponse()
                                .beforeCommit(
                                        () -> {
                                            long elapsed = Math.max(0, nanoTime.getAsLong() - entered);
                                            exchange.getResponse()
                                                    .getHeaders()
                                                    .set(HEADER, Long.toString(elapsed));
                                            return Mono.empty();
                                        });
                    }
                    return chain.filter(exchange);
                });
    }
}
