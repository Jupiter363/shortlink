package com.jupiter.shortlink.admin.config;

import feign.Client;
import feign.FeignException;
import feign.RetryableException;
import feign.hc5.ApacheHttp5Client;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Real loopback TCP only: no database, application service, Docker or external target. */
@Timeout(15)
class AdminFeignTransportIntegrationTest {
    @FeignClient(name = "admin-transport-fixture", url = "${fixture.url}")
    interface Probe {
        @PostMapping(value = "/write", consumes = "text/plain")
        String write(@RequestBody String body);
    }
    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = Probe.class)
    static class FeignFixture {}

    ApplicationContextRunner context(Server server, String... overrides) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                        JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                .withUserConfiguration(AdminFeignTransportConfiguration.class, FeignFixture.class)
                .withPropertyValues("spring.profiles.active=production", "fixture.url=" + server.url(),
                        "spring.cloud.openfeign.client.config.default.connect-timeout=300",
                        "spring.cloud.openfeign.client.config.default.read-timeout=1000",
                        "shortlink.admin.transport.max-total=1",
                        "shortlink.admin.transport.max-per-route=1",
                        "shortlink.admin.transport.lease-timeout-ms=100",
                        "shortlink.admin.transport.validate-after-ms=20")
                .withPropertyValues(overrides);
    }

    @Test void springFeignUsesProductionClientAndReusesHealthyConnection() throws Exception {
        try (var server = new Server(Mode.OK)) {
            context(server).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(Client.class)).isExactlyInstanceOf(ApacheHttp5Client.class);
                var probe = ctx.getBean(Probe.class);
                assertThat(probe.write("one")).isEqualTo("ok");
                assertThat(probe.write("two")).isEqualTo("ok");
                assertThat(server.requests.get()).isEqualTo(2);
                assertThat(server.connections.get()).isEqualTo(1);
                assertThat(ctx.getBean(PoolingHttpClientConnectionManager.class)
                        .getTotalStats().getLeased()).isZero();
            });
        }
    }

    @Test void idleReuseExpiresBeforeServerIdleBudget() throws Exception {
        try (var server = new Server(Mode.OK)) {
            context(server, "shortlink.admin.transport.keep-alive-ms=100",
                    "shortlink.admin.transport.idle-evict-ms=100").run(ctx -> {
                var probe = ctx.getBean(Probe.class);
                assertThat(probe.write("one")).isEqualTo("ok");
                Thread.sleep(160);
                assertThat(probe.write("two")).isEqualTo("ok");
                assertThat(server.requests.get()).isEqualTo(2);
                assertThat(server.connections.get()).isEqualTo(2);
            });
        }
    }

    @Test void serverClosedIdleSocketIsValidatedBeforeReuse() throws Exception {
        try (var server = new Server(Mode.CLOSE_AFTER_RESPONSE)) {
            context(server).run(ctx -> {
                var probe = ctx.getBean(Probe.class);
                assertThat(probe.write("one")).isEqualTo("ok");
                Thread.sleep(100);
                assertThat(probe.write("two")).isEqualTo("ok");
                assertThat(server.requests.get()).isEqualTo(2);
                assertThat(server.connections.get()).isEqualTo(2);
            });
        }
    }

    @Test void serverClosesAfterReceivingPostWithoutAnyAutomaticReplay() throws Exception {
        try (var server = new Server(Mode.CLOSE_BEFORE_RESPONSE)) {
            context(server).run(ctx -> {
                assertThatThrownBy(() -> ctx.getBean(Probe.class).write("once"))
                        .isInstanceOf(RetryableException.class);
                Thread.sleep(150);
                assertThat(server.requests.get()).isEqualTo(1);
                assertThat(server.connections.get()).isEqualTo(1);
            });
        }
    }

    @Test void responseTimeoutDoesNotReplayPost() throws Exception {
        try (var server = new Server(Mode.HOLD)) {
            context(server, "spring.cloud.openfeign.client.config.default.read-timeout=100").run(ctx -> {
                long started = System.nanoTime();
                assertThatThrownBy(() -> ctx.getBean(Probe.class).write("once"))
                        .isInstanceOf(RetryableException.class);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
                Thread.sleep(150);
                assertThat(server.requests.get()).isEqualTo(1);
                assertThat(server.connections.get()).isEqualTo(1);
            });
        }
    }

    @Test void retryAfter503DoesNotReplayPostAtEitherLayer() throws Exception {
        try (var server = new Server(Mode.UNAVAILABLE)) {
            context(server).run(ctx -> {
                Throwable failure = catchThrowable(() -> ctx.getBean(Probe.class).write("once"));
                assertThat(failure).isInstanceOf(FeignException.class);
                assertThat(((FeignException) failure).status()).isEqualTo(503);
                Thread.sleep(150);
                assertThat(server.requests.get()).isEqualTo(1);
            });
        }
    }

    @Test void redirectCannotForwardInternalCredentialsToLocation() throws Exception {
        try (var server = new Server(Mode.REDIRECT)) {
            context(server).run(ctx -> {
                Throwable failure = catchThrowable(() -> ctx.getBean(Probe.class).write("once"));
                assertThat(failure).isInstanceOf(FeignException.class);
                assertThat(((FeignException) failure).status()).isEqualTo(302);
                assertThat(server.requests.get()).isEqualTo(1);
                assertThat(server.connections.get()).isEqualTo(1);
            });
        }
    }

    @Test void boundedPoolLeaseTimeoutDoesNotSendAndReleaseRestoresCapacity() throws Exception {
        try (var server = new Server(Mode.HOLD)) {
            context(server).run(ctx -> {
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var probe = ctx.getBean(Probe.class);
                    Future<String> first = executor.submit(() -> probe.write("first"));
                    assertThat(server.received.await(2, TimeUnit.SECONDS)).isTrue();
                    long started = System.nanoTime();
                    assertThatThrownBy(() -> probe.write("second"))
                            .isInstanceOf(RetryableException.class);
                    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                            .isBetween(50L, 800L);
                    assertThat(server.requests.get()).isEqualTo(1);
                    server.release.countDown();
                    assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
                    assertThat(probe.write("third")).isEqualTo("ok");
                    var stats = ctx.getBean(PoolingHttpClientConnectionManager.class).getTotalStats();
                    assertThat(stats.getLeased()).isZero();
                    assertThat(stats.getPending()).isZero();
                    assertThat(server.requests.get()).isEqualTo(2);
                } finally {
                    server.release.countDown(); executor.shutdownNow();
                    assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
                }
            });
        }
    }

    enum Mode { OK, CLOSE_AFTER_RESPONSE, CLOSE_BEFORE_RESPONSE, HOLD, UNAVAILABLE, REDIRECT }

    static final class Server implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final ExecutorService handlers = Executors.newFixedThreadPool(3);
        final AtomicInteger requests = new AtomicInteger(), connections = new AtomicInteger();
        final CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        final Mode mode;
        final Thread acceptor;
        volatile boolean running = true;
        volatile Throwable unexpected;

        Server(Mode mode) throws IOException {
            this.mode = mode;
            acceptor = new Thread(this::accept, "admin-transport-fixture-accept");
            acceptor.start();
        }

        String url() { return "http://127.0.0.1:" + listener.getLocalPort(); }

        void accept() {
            try {
                while (running) {
                    Socket socket = listener.accept();
                    connections.incrementAndGet(); sockets.add(socket);
                    socket.setSoTimeout(3000);
                    handlers.execute(() -> serve(socket));
                }
            } catch (IOException failure) { if (running) unexpected = failure; }
        }

        void serve(Socket socket) {
            try (socket) {
                var input = new BufferedInputStream(socket.getInputStream());
                while (running) {
                    String first = line(input);
                    if (first == null) return;
                    if (!first.startsWith("POST ")) throw new IOException("Expected fixture POST");
                    int length = 0;
                    for (int n = 0; n < 64; n++) {
                        String header = line(input);
                        if (header == null) throw new EOFException();
                        if (header.isEmpty()) break;
                        if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                            length = Integer.parseInt(header.substring(15).trim());
                        if (n == 63) throw new IOException("Fixture header budget exceeded");
                    }
                    if (length < 0 || length > 65536 || input.readNBytes(length).length != length)
                        throw new IOException("Fixture body budget exceeded");
                    requests.incrementAndGet(); received.countDown();
                    if (mode == Mode.CLOSE_BEFORE_RESPONSE) return;
                    if (mode == Mode.HOLD && !release.await(3, TimeUnit.SECONDS)) return;
                    int status = mode == Mode.UNAVAILABLE ? 503 : mode == Mode.REDIRECT ? 302 : 200;
                    String extra = mode == Mode.UNAVAILABLE ? "Retry-After: 0\r\n"
                            : mode == Mode.REDIRECT ? "Location: " + url() + "/must-not-follow\r\n" : "";
                    byte[] response = ("HTTP/1.1 " + status + " Fixture\r\nContent-Length: 2\r\n"
                            + "Content-Type: text/plain\r\nConnection: keep-alive\r\n"
                            + "Keep-Alive: timeout=60\r\n" + extra + "\r\nok")
                            .getBytes(StandardCharsets.US_ASCII);
                    socket.getOutputStream().write(response); socket.getOutputStream().flush();
                    if (mode == Mode.CLOSE_AFTER_RESPONSE) return;
                }
            } catch (SocketException | EOFException expectedClosure) {
                // Clients may close a timed-out or evicted connection.
            } catch (Exception failure) { if (running) unexpected = failure; }
            finally { sockets.remove(socket); }
        }

        static String line(InputStream input) throws IOException {
            var out = new ByteArrayOutputStream();
            for (int n = 0; n < 16384; n++) {
                int value = input.read();
                if (value == -1) return out.size() == 0 ? null : out.toString(StandardCharsets.US_ASCII);
                if (value == '\n') return out.toString(StandardCharsets.US_ASCII).replace("\r", "");
                out.write(value);
            }
            throw new IOException("Fixture line budget exceeded");
        }

        @Override public void close() throws Exception {
            running = false; release.countDown(); listener.close();
            for (Socket socket : sockets) socket.close();
            handlers.shutdownNow(); acceptor.join(2000);
            assertThat(handlers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(acceptor.isAlive()).isFalse();
            assertThat(unexpected).isNull();
        }
    }
}
