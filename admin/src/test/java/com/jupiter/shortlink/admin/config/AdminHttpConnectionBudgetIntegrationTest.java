package com.jupiter.shortlink.admin.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real embedded Tomcat, raw sockets and a local servlet; no application service or database. */
@Timeout(15)
class AdminHttpConnectionBudgetIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @Import({ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class, HttpConnectionBudget.class})
    static class Fixture {
        @Bean AtomicInteger handledRequests() { return new AtomicInteger(); }

        @Bean ServletRegistrationBean<HttpServlet> probeServlet(AtomicInteger handledRequests) {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    handledRequests.incrementAndGet();
                    if (request.getMethod().equals("POST")) {
                        byte[] body = request.getInputStream().readNBytes(5);
                        if (body.length != 4) throw new IOException("Incomplete fixture request");
                    }
                    byte[] body = "ok".getBytes(StandardCharsets.US_ASCII);
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    response.setContentLength(body.length);
                    response.getOutputStream().write(body);
                }
            }, "/probe");
        }

    }

    static final class Server implements AutoCloseable {
        final AnnotationConfigServletWebServerApplicationContext context =
                new AnnotationConfigServletWebServerApplicationContext();
        final Http11NioProtocol protocol;
        final int port;
        Server(boolean legacy, Map<String, Object> overrides) throws IOException {
            Properties production = new Properties();
            try (var resource = getClass().getResourceAsStream("/application-production.properties")) {
                if (resource == null) throw new IOException("Production properties missing");
                production.load(resource);
            }
            Map<String, Object> serverProperties = new HashMap<>();
            // Load exactly the checked-in server settings, without application credentials or
            // dependencies. No Spring test defaults may silently replace the production budget.
            production.forEach((key, value) -> {
                if (key.toString().startsWith("server.")) serverProperties.put(key.toString(), value);
            });
            if (legacy) serverProperties.remove("server.tomcat.keep-alive-timeout");
            serverProperties.put("server.port", 0);
            serverProperties.put("server.address", "127.0.0.1");
            serverProperties.putAll(overrides);
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("isolated-production-server-budget", serverProperties));
            context.register(Fixture.class);
            context.refresh();
            var webServer = (TomcatWebServer) context.getWebServer();
            port = webServer.getPort();
            protocol = (Http11NioProtocol) webServer.getTomcat().getConnector().getProtocolHandler();
        }

        int handled() { return context.getBean(AtomicInteger.class).get(); }

        Socket open() throws IOException {
            var socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            socket.setSoTimeout(2000);
            return socket;
        }

        @Override public void close() { context.close(); }
    }

    @Test void legacyImplicitTwoSecondKeepAliveClosesTheOriginalSocket() throws Exception {
        try (var server = new Server(true, Map.of()); var socket = server.open()) {
            assertThat(server.protocol.getConnectionTimeout()).isEqualTo(2000);
            assertThat(server.protocol.getKeepAliveTimeout()).isEqualTo(2000);
            get(socket);
            assertThat(response(socket).status()).isEqualTo(200);
            long started = System.nanoTime();
            Thread.sleep(2300);
            // Use the default production NIO polling cadence. Read its FIN with a bounded
            // allowance for the timeout scan; never open a replacement or retry a request.
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isBetween(2000L, 5000L);
            assertThat(server.handled()).isEqualTo(1);
        }
    }

    @Test void explicitFifteenSecondKeepAliveSurvivesTwoPointThreeSecondsOnSameSocket() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(HttpConnectionBudget.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try (var server = new Server(false, Map.of()); var socket = server.open()) {
            int localPort = socket.getLocalPort();
            get(socket);
            assertThat(response(socket).status()).isEqualTo(200);
            Thread.sleep(2300);
            get(socket);
            assertThat(response(socket).status()).isEqualTo(200);
            assertThat(socket.getLocalPort()).isEqualTo(localPort);
            assertThat(server.handled()).isEqualTo(2);
            assertThat(server.protocol.getKeepAliveTimeout()).isEqualTo(15000);
            assertThat(server.protocol.getMaxKeepAliveRequests()).isEqualTo(100);
            assertThat(server.protocol.getConnectionTimeout()).isEqualTo(2000);
            assertThat(server.protocol.getConnectionUploadTimeout()).isEqualTo(5000);
            assertThat(server.protocol.getDisableUploadTimeout()).isFalse();
            assertThat(server.protocol.getMaxConnections()).isEqualTo(256);
            assertThat(server.protocol.getMaxThreads()).isEqualTo(64);
            assertThat(server.protocol.getMinSpareThreads()).isEqualTo(4);
            assertThat(server.protocol.getAcceptCount()).isEqualTo(32);
            assertThat(server.protocol.getMaxHttpRequestHeaderSize()).isEqualTo(16384);
            assertThat(server.protocol.getMaxSwallowSize()).isZero();
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.get(0).getFormattedMessage())
                    .contains("role=application", "connection_timeout_ms=2000",
                            "keep_alive_timeout_ms=15000", "max_keep_alive_requests=100",
                            "upload_timeout_enabled=true", "upload_timeout_ms=5000",
                            "max_connections=256", "max_threads=64", "min_spare_threads=4",
                            "accept_count=32", "max_request_header_bytes=16384", "max_swallow_bytes=0")
                    .doesNotContain("127.0.0.1", "/probe");
            assertThat(logs.list.get(0).getThrowableProxy()).isNull();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void explicitIdleDeadlineStillReclaimsTheSocketWithAShortFixtureBudget() throws Exception {
        try (var server = new Server(false, Map.of("server.tomcat.keep-alive-timeout", "200ms"));
                var socket = server.open()) {
            assertThat(server.protocol.getKeepAliveTimeout()).isEqualTo(200);
            get(socket);
            assertThat(response(socket).status()).isEqualTo(200);
            long started = System.nanoTime();
            Thread.sleep(450);
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isBetween(200L, 3000L);
            assertThat(server.handled()).isEqualTo(1);
        }
    }

    @Test void initialRequestBudgetRemainsTwoSecondsDespiteLongerIdleKeepAlive() throws Exception {
        try (var server = new Server(false, Map.of()); var socket = server.open()) {
            send(socket, "GET /probe HTTP/1.1\r\nHost: fixture");
            socket.setSoTimeout(5000);
            long started = System.nanoTime();
            // An incomplete first request cannot occupy the connection for the 15s idle budget.
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(elapsed).isBetween(1500L, 5000L);
            assertThat(server.handled()).isZero();
        }
    }

    @Test void slowBodyRetainsFiveSecondUploadBudgetInsteadOfTwoSecondHeaderBudget() throws Exception {
        try (var server = new Server(false, Map.of()); var socket = server.open()) {
            send(socket, "POST /probe HTTP/1.1\r\nHost: fixture\r\nContent-Length: 4\r\n\r\nab");
            Thread.sleep(2300);
            send(socket, "cd");
            assertThat(response(socket).status()).isEqualTo(200);
            assertThat(server.handled()).isEqualTo(1);
            assertThat(server.protocol.getConnectionUploadTimeout()).isEqualTo(5000);
        }
    }

    @Test void finiteRequestCountClosesExplicitlyWithoutReconnecting() throws Exception {
        try (var server = new Server(false, Map.of("server.tomcat.max-keep-alive-requests", "2"));
                var socket = server.open()) {
            get(socket);
            assertThat(response(socket).status()).isEqualTo(200);
            get(socket);
            var last = response(socket);
            assertThat(last.status()).isEqualTo(200);
            assertThat(last.headers().get("connection")).isEqualToIgnoringCase("close");
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(server.handled()).isEqualTo(2);
        }
    }

    record Response(int status, Map<String, String> headers) {}

    static void get(Socket socket) throws IOException {
        send(socket, "GET /probe HTTP/1.1\r\nHost: fixture\r\nConnection: keep-alive\r\n\r\n");
    }

    static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    static Response response(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        String first = line(input);
        if (first == null || !first.startsWith("HTTP/1.1 ")) throw new IOException("Missing fixture response");
        int status = Integer.parseInt(first.split(" ")[1]);
        Map<String, String> headers = new HashMap<>();
        for (int n = 0; n < 64; n++) {
            String header = line(input);
            if (header == null) throw new EOFException();
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon <= 0) throw new IOException("Malformed fixture header");
            headers.put(header.substring(0, colon).toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            if (n == 63) throw new IOException("Fixture header count exceeded");
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
        if (length < 0 || length > 16384 || input.readNBytes(length).length != length)
            throw new IOException("Fixture response body incomplete");
        return new Response(status, headers);
    }

    static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int n = 0; n < 16384; n++) {
            int b = input.read();
            if (b == -1) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.US_ASCII);
            if (b == '\n') return bytes.toString(StandardCharsets.US_ASCII).replace("\r", "");
            bytes.write(b);
        }
        throw new IOException("Fixture line budget exceeded");
    }
}
