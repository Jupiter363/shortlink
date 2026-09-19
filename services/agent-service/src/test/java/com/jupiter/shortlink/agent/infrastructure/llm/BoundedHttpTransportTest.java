package com.jupiter.shortlink.agent.infrastructure.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backend-only hostile response fixtures: no application, model or external dependency is started. */
class BoundedHttpTransportTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void chunkedBodyWithoutContentLengthIsBoundedBeforeJsonDecodeAndReleasesIoPermit(int status)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oversized", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, 0); // chunked, no Content-Length to trust
            try (var output = exchange.getResponseBody()) {
                byte[] chunk = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);
                // No complete JSON object is ever built by the client before its read limit.
                output.write("{\"payload\":\"".getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < 384; i++) output.write(chunk);
                output.write("\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException expectedClientCancellation) {
                // The bounded subscriber is allowed to close before the fixture finishes writing.
            } finally {
                exchange.close();
            }
        });
        server.createContext("/ready", exchange -> {
            byte[] body = "{\"status\":\"READY\",\"pv\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            BoundedHttpTransport transport = new BoundedHttpTransport();
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            // More failures than the transport's concurrent permit count also expose leaks.
            for (int i = 0; i < 17; i++) {
                assertThatThrownBy(() -> transport.exchange("GET", base.resolve("/oversized"), Map.of(), null))
                        .isInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("Business response exceeded byte budget");
            }
            assertThat(transport.exchange("GET", base.resolve("/ready"), Map.of(), null))
                    .containsEntry("status", "READY").containsEntry("pv", 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedJsonDoesNotBecomeAnEmptySuccessfulResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("{\"pv\":".getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/broken");
            assertThatThrownBy(() -> new BoundedHttpTransport().exchange("GET", uri, Map.of(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Business authority is unavailable");
        } finally {
            server.stop(0);
        }
    }
}
