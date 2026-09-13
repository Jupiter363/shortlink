package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClickHouseOptionalProofTest {
    @Test
    void stalledBodyUsesTheOptionalDeadlineInsteadOfDefaultTwentySeconds() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        var receivedQuery = new AtomicReference<String>();
        var executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getQuery());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var reader = reader(url);
            var failure = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> assertThrows(QueryFailure.class, () -> reader.queryOptionalProof(url, "SELECT 1", 1)));
            assertEquals("TOO_LARGE", failure.code);
            assertTrue(receivedQuery.get().contains("max_execution_time=1&"));
        } finally {
            release.countDown(); server.stop(0); executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"401,FORBIDDEN", "403,FORBIDDEN", "429,TOO_LARGE", "503,UNAVAILABLE", "200,NOT_READY"})
    void accessDenialsAndMalformedProofResponsesAreNotReportedAsTransient(String status, String code) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "malformed-proof\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(status), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(code, assertThrows(QueryFailure.class, () -> reader(url).queryOptionalProof(url, "SELECT 1", 1)).code);
        } finally {
            server.stop(0);
        }
    }

    private ClickHouseReader reader(String url) {
        return new ClickHouseReader(new ApiSettings("test-history-proof-token-long-enough", "http://localhost:1",
                "http://localhost:2", url, "test", "", "test"), new ObjectMapper());
    }
}
