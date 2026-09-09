package com.jupiter.shortlink.command.metadata;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Loopback is injected only into the HTTP transport test, beneath the production public-address
 * policy.
 */
class SafeMetadataTransportTest {
    private HttpServer server;
    private ExecutorService executor;
    private SafeMetadataFetcher fetcher;

    @BeforeEach
    void open() throws Exception {
        ((ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.start();
        fetcher = new SafeMetadataFetcher(1, 1024, 1, 1000);
    }

    @AfterEach
    void close() {
        fetcher.close();
        server.stop(0);
        executor.shutdownNow();
    }

    private SafeMetadataFetcher.Response get(String path, int bytes, long millis) throws Exception {
        return fetcher.http(
                URI.create(
                        "http://does-not-resolve.invalid:" + server.getAddress().getPort() + path),
                new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                bytes,
                millis);
    }

    @Test
    void actualConnectionUsesOnlyPinnedAddressAndReadsBoundedHtml() throws Exception {
        server.createContext(
                "/html",
                exchange -> {
                    byte[] body = "<title>pinned</title>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        var response = get("/html", 1024, 1000);
        assertEquals(200, response.status());
        assertEquals("<title>pinned</title>", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void actualHttpResponseCannotExceedByteBudget() throws Exception {
        server.createContext(
                "/large",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, 0);
                    try (var output = exchange.getResponseBody()) {
                        output.write(new byte[65536]);
                    } catch (IOException ignored) {
                    }
                });
        assertThrows(IOException.class, () -> get("/large", 1024, 1000));
    }

    @Test
    void actualHttpDeadlineCancelsSlowHeaders() throws Exception {
        server.createContext(
                "/slow",
                exchange -> {
                    try {
                        Thread.sleep(1000);
                        exchange.sendResponseHeaders(200, 0);
                    } catch (Exception ignored) {
                    } finally {
                        exchange.close();
                    }
                });
        long started = System.nanoTime();
        assertThrows(IOException.class, () -> get("/slow", 1024, 150));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 900);
    }

    @Test
    void deadlineCancellationAfterAcquisitionPreservesRealEndpointFailureAsIoCause() {
        var acquired = new AtomicBoolean();
        interceptConnect((request, scope, chain) -> {
            // Use the real HttpClient5 runtime and manager. Stop exactly between acquiring
            // and connecting, where the deadline task can discard the acquired endpoint.
            scope.execRuntime.acquireEndpoint(scope.exchangeId, scope.route, null, scope.clientContext);
            acquired.set(true);
            long safetyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!scope.execRuntime.isExecutionAborted() || scope.execRuntime.isEndpointAcquired()) {
                assertTrue(System.nanoTime() < safetyDeadline, "deadline must cancel the real request");
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertFalse(scope.execRuntime.isEndpointAcquired());
            scope.execRuntime.connectEndpoint(scope.clientContext);
            throw new AssertionError("Connecting a cancelled endpoint must fail");
        });
        IOException failure = assertThrows(IOException.class, () -> get("/not-connected", 1024, 3000));
        assertTrue(acquired.get(), "the test must exercise acquisition, not a pre-execute cancellation");
        assertEquals("Metadata deadline exceeded", failure.getMessage());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("Endpoint not acquired / already released", failure.getCause().getMessage());
    }

    @Test
    void nonDeadlineIllegalStateFromActualExecutionChainIsNotConverted() {
        var original = new IllegalStateException("independent execution failure");
        interceptConnect((request, scope, chain) -> { throw original; });
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> get("/not-connected", 1024, 3000)));
    }

    private void interceptConnect(ExecChainHandler interceptor) {
        fetcher.close();
        fetcher = new SafeMetadataFetcher(1, 1024, 1, 1000) {
            @Override
            HttpClientBuilder newHttpClientBuilder() {
                return super.newHttpClientBuilder().addExecInterceptorBefore(
                        ChainElement.CONNECT.name(), "test-acquisition-boundary", interceptor);
            }
        };
    }

    @Test
    void redirectIsNotFollowedOrDrained() throws Exception {
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1/private");
                    exchange.sendResponseHeaders(302, 0);
                    try (var output = exchange.getResponseBody()) {
                        for (int i = 0; i < 10; i++) {
                            output.write(new byte[8192]);
                            output.flush();
                            Thread.sleep(100);
                        }
                    } catch (Exception ignored) {
                    }
                });
        long started = System.nanoTime();
        var response = get("/redirect", 1024, 500);
        assertEquals(302, response.status());
        assertEquals(0, response.body().length);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 900);
    }
}
