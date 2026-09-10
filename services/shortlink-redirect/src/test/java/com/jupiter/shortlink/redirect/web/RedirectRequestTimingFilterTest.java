package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class RedirectRequestTimingFilterTest {
    @Test
    void absentOrFalsePropertyDoesNotRegisterAnyTimingFilter() {
        for (String property : new String[] {null, "false", "true"}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                if (property != null) context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("test", Map.of("shortlink.redirect.request-timing-enabled", property)));
                context.register(RedirectRequestTimingFilter.class);
                context.refresh();
                assertEquals("true".equals(property) ? 1 : 0,
                        context.getBeansOfType(RedirectRequestTimingFilter.class).size());
            }
        }
    }

    @Test
    void samplingUsesTheClockOnlyForSelectedSubscribedRequests() {
        AtomicInteger reads = new AtomicInteger();
        var filter = new RedirectRequestTimingFilter(() -> reads.getAndIncrement() * 17L);
        for (int index = 0; index <= 64; index++) {
            var exchange = exchange();
            int before = reads.get();
            Mono<Void> response = filter.filter(exchange,
                    ignored -> Mono.defer(() -> exchange.getResponse().setComplete()));
            assertEquals(before, reads.get(), "construction must not read the clock");
            assertFalse(exchange.getResponse().isCommitted());
            response.block();
            assertTrue(exchange.getResponse().isCommitted());
            if (index == 0 || index == 64) {
                assertEquals("17", exchange.getResponse().getHeaders().getFirst(RedirectRequestTimingFilter.HEADER));
                assertEquals(before + 2, reads.get());
            } else {
                assertNull(exchange.getResponse().getHeaders().getFirst(RedirectRequestTimingFilter.HEADER));
                assertEquals(before, reads.get());
            }
        }
        assertEquals(4, reads.get());
    }

    @Test
    void outerErrorHandlingStillExecutesTheRegisteredBeforeCommitAction() {
        AtomicLong time = new AtomicLong(100);
        var filter = new RedirectRequestTimingFilter(time::get);
        var exchange = exchange();
        filter.filter(exchange, ignored -> Mono.defer(() -> {
                    time.set(375);
                    return Mono.error(new IllegalStateException("failure after subscription"));
                }))
                .onErrorResume(error -> {
                    exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    exchange.getResponse().getHeaders().set(GatewayRequestIds.HEADER, "0123456789abcdef0123456789abcdef");
                    return exchange.getResponse().setComplete();
                }).block();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        assertEquals("275", exchange.getResponse().getHeaders().getFirst(RedirectRequestTimingFilter.HEADER));
        assertEquals("0123456789abcdef0123456789abcdef",
                exchange.getResponse().getHeaders().getFirst(GatewayRequestIds.HEADER));
    }

    @Test
    void negativeDiagnosticElapsedTimeIsClampedWithoutChangingTheResponse() {
        AtomicLong time = new AtomicLong(100);
        var filter = new RedirectRequestTimingFilter(time::get);
        var exchange = exchange();
        filter.filter(exchange, ignored -> {
            time.set(90);
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }).block();
        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        assertEquals("0", exchange.getResponse().getHeaders().getFirst(RedirectRequestTimingFilter.HEADER));
    }

    @Test
    void signedNanoTimeWrapKeepsAShortPositiveElapsedValue() {
        AtomicLong time = new AtomicLong(Long.MAX_VALUE - 10);
        var filter = new RedirectRequestTimingFilter(time::get);
        var exchange = exchange();
        filter.filter(exchange, ignored -> {
            time.set(Long.MIN_VALUE + 9);
            return exchange.getResponse().setComplete();
        }).block();
        assertEquals("20", exchange.getResponse().getHeaders().getFirst(RedirectRequestTimingFilter.HEADER));
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("http://s.example/Ab"));
    }
}
