package com.jupiter.shortlink.analytics.api.job;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class JobClickHouseStreamTest {
    @Test
    void actualHttpPostStreamsJsonRowsAndKeepsSqlOutOfUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var receivedQuery = new AtomicReference<String>();
        server.createContext(
                "/",
                e -> {
                    receivedQuery.set(e.getRequestURI().getRawQuery());
                    assertThat(e.getRequestMethod()).isEqualTo("POST");
                    assertThat(e.getRequestURI().getRawQuery()).doesNotContain("SELECT");
                    assertThat(
                                    new String(
                                            e.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8))
                            .isEqualTo("SELECT 1 FORMAT JSONEachRow");
                    byte[] bytes =
                            "{\"linkId\":4000000001}\n{\"pv\":4294967296}\n"
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, bytes.length);
                    try (var o = e.getResponseBody()) {
                        o.write(bytes);
                    }
                });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var stream = new JobClickHouseStream(settings(url), new ObjectMapper())) {
            List<Map<String, Object>> rows = new ArrayList<>();
            stream.query(
                    url, "SELECT 1", 2, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), rows::add);
            assertThat(rows).hasSize(2);
            assertThat(((Number) rows.get(1).get("pv")).longValue()).isEqualTo(4294967296L);
            assertThat(receivedQuery.get())
                    .contains("&max_memory_usage=1073741824&")
                    .contains("&max_result_rows=3&")
                    .contains("&max_result_bytes=67108864&result_overflow_mode=throw")
                    .contains("&max_bytes_to_read=1073741824&read_overflow_mode=throw&");
            long executionSeconds = Arrays.stream(receivedQuery.get().split("&"))
                    .filter(value -> value.startsWith("max_execution_time="))
                    .mapToLong(value -> Long.parseLong(value.substring("max_execution_time=".length())))
                    .findFirst().orElseThrow();
            assertThat(executionSeconds).isBetween(1L, 5L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void raisedQueryMemoryBudgetStillRejectsRowsBeyondTheRequestedLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var receivedQuery = new AtomicReference<String>();
        server.createContext("/", exchange -> {
            try {
                receivedQuery.set(exchange.getRequestURI().getRawQuery());
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"n\":1}\n{\"n\":2}\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var stream = new JobClickHouseStream(settings(url), new ObjectMapper())) {
            List<Map<String, Object>> rows = new ArrayList<>();
            QueryFailure failure = org.junit.jupiter.api.Assertions.assertThrows(
                    QueryFailure.class,
                    () -> stream.query(url, "SELECT 1", 1,
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5), rows::add));

            assertThat(failure.code).isEqualTo("TOO_LARGE");
            assertThat(failure).hasMessage("Job result row budget exceeded");
            assertThat(rows).containsExactly(Map.of("n", 1));
            assertThat(receivedQuery.get())
                    .contains("&max_memory_usage=1073741824&")
                    .contains("&max_result_rows=2&");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void actualOversizedLineRejectedInsteadOfReadLineUnboundedGrowth() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    byte[] body =
                            ("{\"x\":\"" + "a".repeat(70_000) + "\"}\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, body.length);
                    try (var o = e.getResponseBody()) {
                        o.write(body);
                    }
                });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var stream = new JobClickHouseStream(settings(url), new ObjectMapper())) {
            assertThatThrownBy(
                            () ->
                                    stream.query(
                                            url,
                                            "SELECT 1",
                                            10,
                                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                                            r -> {}))
                    .isInstanceOf(QueryFailure.class)
                    .hasMessageContaining("64 KiB");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void csvFormulaInjectionAndEmbeddedNewlinesAreQuoted() {
        assertThat(QueryJobController.csv("  =1+1")).isEqualTo("\"'  =1+1\"");
        assertThat(QueryJobController.csv("a\"b\nc")).isEqualTo("\"a\"\"b\nc\"");
    }

    @Test
    void clickHouseBudgetFailureIsExplicitAndNotARequestToRetryForever() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    byte[] body =
                            "Code: 241. MEMORY_LIMIT_EXCEEDED".getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(500, body.length);
                    try (var o = e.getResponseBody()) {
                        o.write(body);
                    }
                });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var stream = new JobClickHouseStream(settings(url), new ObjectMapper())) {
            QueryFailure failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            QueryFailure.class,
                            () ->
                                    stream.query(
                                            url,
                                            "SELECT 1",
                                            10,
                                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                                            r -> {}));
            assertThat(failure.code).isEqualTo("TOO_LARGE");
            var plan =
                    new ManifestPlan(
                            "epoch",
                            List.of(
                                    new ManifestPlan.Window(
                                            0,
                                            "build",
                                            1,
                                            "{}",
                                            300000,
                                            "v1",
                                            "v1",
                                            "{\"n\":1,\"digest\":\"7\"}")),
                            List.of(url));
            assertThat(
                            org.junit.jupiter.api.Assertions.assertThrows(
                                            QueryFailure.class,
                                            () ->
                                                    stream.verify(
                                                            plan,
                                                            System.nanoTime()
                                                                    + TimeUnit.SECONDS.toNanos(5)))
                                    .code)
                    .isEqualTo("TOO_LARGE");
        } finally {
            server.stop(0);
        }
    }

    private ApiSettings settings(String url) {
        return new ApiSettings(
                "test-token-long-enough-for-analytics",
                "http://localhost:1",
                "http://localhost:2",
                url,
                "default",
                "",
                "test");
    }
}
