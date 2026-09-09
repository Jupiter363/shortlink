package com.jupiter.shortlink.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

class ManagementBodyBudgetFilterTest {
    private static final String ORDINARY = "/api/short-link/admin/v1/create",
            BATCH = ORDINARY + "/batch";
    private static final DefaultDataBufferFactory BUFFERS = new DefaultDataBufferFactory();

    @Test
    void declaredOversizeAndGzipAreRejectedWithoutReadingBody() {
        var reads = new AtomicInteger();
        var filter = new ManagementBodyBudgetFilter();
        var request =
                MockServerHttpRequest.post(ORDINARY)
                        .header("Content-Length", "262145")
                        .body(
                                Flux.defer(
                                        () -> {
                                            reads.incrementAndGet();
                                            return Flux.empty();
                                        }));
        var exchange = MockServerWebExchange.from(request);
        filter.filter(
                        exchange,
                        e -> {
                            fail("oversize reached upstream");
                            return Mono.empty();
                        })
                .block();
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
        assertEquals(0, reads.get());
        var compressed =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post(ORDINARY)
                                .header("Content-Encoding", "gzip")
                                .build());
        filter.filter(
                        compressed,
                        e -> {
                            fail("gzip reached upstream");
                            return Mono.empty();
                        })
                .block();
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, compressed.getResponse().getStatusCode());
    }

    @Test
    void chunkedActualBytesAreCountedBeforeAnyUpstreamForward() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post(ORDINARY)
                                .header("Transfer-Encoding", "chunked")
                                .body(
                                        Flux.just(
                                                BUFFERS.wrap(new byte[131072]),
                                                BUFFERS.wrap(new byte[131073]))));
        new ManagementBodyBudgetFilter()
                .filter(
                        exchange,
                        e -> {
                            fail("partial body reached upstream");
                            return Mono.empty();
                        })
                .block();
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
    }

    @Test
    void batchGetsItsOwnBudgetAndForwardedLengthMatchesActualBytes() {
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post(BATCH)
                                .header("Transfer-Encoding", "chunked")
                                .body(Flux.just(BUFFERS.wrap(new byte[300000]))));
        var size = new AtomicInteger();
        new ManagementBodyBudgetFilter()
                .filter(
                        exchange,
                        e -> {
                            assertEquals(300000, e.getRequest().getHeaders().getContentLength());
                            assertFalse(
                                    e.getRequest().getHeaders().containsKey("Transfer-Encoding"));
                            return DataBufferUtils.join(e.getRequest().getBody())
                                    .doOnNext(
                                            buffer -> {
                                                size.set(buffer.readableByteCount());
                                                DataBufferUtils.release(buffer);
                                            })
                                    .then();
                        })
                .block();
        assertEquals(300000, size.get());
    }

    @Test
    void saturatedBodyBudgetDoesNotQueueAndCancellationReleasesReservation() {
        var filter = new ManagementBodyBudgetFilter(ManagementBodyBudgetFilter.ORDINARY_BYTES);
        var first =
                MockServerWebExchange.from(MockServerHttpRequest.post(ORDINARY).body(Flux.never()));
        var active = filter.filter(first, e -> Mono.empty()).subscribe();
        var rejected = MockServerWebExchange.from(MockServerHttpRequest.post(ORDINARY).build());
        filter.filter(rejected, e -> Mono.empty()).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());
        active.dispose();
        var next = MockServerWebExchange.from(MockServerHttpRequest.post(ORDINARY).build());
        filter.filter(next, e -> Mono.empty()).block();
        assertNull(next.getResponse().getStatusCode());
    }

    @Test
    void slowBodyExpiresAndReleasesBudget() {
        var filter = new ManagementBodyBudgetFilter(ManagementBodyBudgetFilter.ORDINARY_BYTES);
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post(ORDINARY).body(Flux.never()));
        StepVerifier.withVirtualTime(() -> filter.filter(exchange, e -> Mono.empty()))
                .thenAwait(java.time.Duration.ofSeconds(6))
                .verifyComplete();
        assertEquals(HttpStatus.REQUEST_TIMEOUT, exchange.getResponse().getStatusCode());
    }

    @Test
    void admissionBoundsSessionsAndUpstreamTogether() {
        var filter = new GatewayAdmissionFilter(1);
        var first = MockServerWebExchange.from(MockServerHttpRequest.get(ORDINARY).build());
        var active = filter.filter(first, e -> Mono.never()).subscribe();
        var second = MockServerWebExchange.from(MockServerHttpRequest.get(ORDINARY).build());
        filter.filter(second, e -> Mono.empty()).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
        active.dispose();
        var third = MockServerWebExchange.from(MockServerHttpRequest.get(ORDINARY).build());
        filter.filter(third, e -> Mono.empty()).block();
        assertNull(third.getResponse().getStatusCode());
    }
}
