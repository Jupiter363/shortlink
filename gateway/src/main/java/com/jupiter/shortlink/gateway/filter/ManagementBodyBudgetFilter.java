package com.jupiter.shortlink.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * Runs after session authorization, before the first byte can reach Admin. No waiting body queue.
 */
@Component
public final class ManagementBodyBudgetFilter implements GatewayFilter {
    static final int ORDINARY_BYTES = 256 * 1024, BATCH_BYTES = 8 * 1024 * 1024;
    private final Semaphore bytes;

    public ManagementBodyBudgetFilter() {
        this(64 * 1024 * 1024);
    }

    ManagementBodyBudgetFilter(int bytes) {
        this.bytes = new Semaphore(bytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(
                () -> {
                    var request = exchange.getRequest();
                    var headers = request.getHeaders();
                    String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
                    if (headers.getOrEmpty(HttpHeaders.CONTENT_ENCODING).size() > 1
                            || (encoding != null && !encoding.equalsIgnoreCase("identity")))
                        return reject(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                    int max =
                            request.getMethod() == HttpMethod.POST
                                            && request.getURI()
                                                    .getRawPath()
                                                    .equals("/api/short-link/admin/v1/create/batch")
                                    ? BATCH_BYTES
                                    : ORDINARY_BYTES;
                    if (headers.getOrEmpty(HttpHeaders.CONTENT_LENGTH).size() > 1)
                        return reject(exchange, HttpStatus.BAD_REQUEST);
                    try {
                        if (headers.getContentLength() > max)
                            return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE);
                    } catch (IllegalArgumentException invalid) {
                        return reject(exchange, HttpStatus.BAD_REQUEST);
                    }
                    if (!bytes.tryAcquire(max))
                        return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
                    return DataBufferUtils.join(request.getBody(), max)
                            .map(
                                    buffer -> {
                                        try {
                                            byte[] body = new byte[buffer.readableByteCount()];
                                            buffer.read(body);
                                            return body;
                                        } finally {
                                            DataBufferUtils.release(buffer);
                                        }
                                    })
                            .defaultIfEmpty(new byte[0])
                            .timeout(Duration.ofSeconds(5))
                            .onErrorMap(
                                    DataBufferLimitException.class,
                                    error -> new BodyRejected(HttpStatus.PAYLOAD_TOO_LARGE))
                            .onErrorMap(
                                    java.util.concurrent.TimeoutException.class,
                                    error -> new BodyRejected(HttpStatus.REQUEST_TIMEOUT))
                            .flatMap(
                                    body ->
                                            chain.filter(
                                                    exchange.mutate()
                                                            .request(
                                                                    new ServerHttpRequestDecorator(
                                                                            request) {
                                                                        @Override
                                                                        public HttpHeaders
                                                                                getHeaders() {
                                                                            var copy =
                                                                                    new HttpHeaders();
                                                                            copy.putAll(
                                                                                    super
                                                                                            .getHeaders());
                                                                            copy.remove(
                                                                                    HttpHeaders
                                                                                            .TRANSFER_ENCODING);
                                                                            copy.setContentLength(
                                                                                    body.length);
                                                                            return copy;
                                                                        }

                                                                        @Override
                                                                        public Flux<
                                                                                        org
                                                                                                .springframework
                                                                                                .core
                                                                                                .io
                                                                                                .buffer
                                                                                                .DataBuffer>
                                                                                getBody() {
                                                                            return Flux.defer(
                                                                                    () ->
                                                                                            Flux
                                                                                                    .just(
                                                                                                            exchange.getResponse()
                                                                                                                    .bufferFactory()
                                                                                                                    .wrap(
                                                                                                                            body)));
                                                                        }
                                                                    })
                                                            .build()))
                            .onErrorResume(
                                    BodyRejected.class, error -> reject(exchange, error.status))
                            .doFinally(signal -> bytes.release(max));
                });
    }

    private static final class BodyRejected extends RuntimeException {
        private final HttpStatus status;

        private BodyRejected(HttpStatus status) {
            this.status = status;
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        return exchange.getResponse().setComplete();
    }
}
