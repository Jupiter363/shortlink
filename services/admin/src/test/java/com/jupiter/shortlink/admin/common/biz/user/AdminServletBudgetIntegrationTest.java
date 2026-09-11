package com.jupiter.shortlink.admin.common.biz.user;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real Servlet async state, NIO sockets and disconnects; no Admin application or database. */
@Timeout(30)
class AdminServletBudgetIntegrationTest {
    private static final ThreadLocal<String> IDENTITY = new ThreadLocal<>();
    private static final String VERIFIED = AdminServletBudgetIntegrationTest.class.getName() + ".verified";

    static final class State {
        final AtomicInteger authentications = new AtomicInteger();
        final AtomicInteger handled = new AtomicInteger();
        final AtomicReference<AsyncContext> pending = new AtomicReference<>();
        final AtomicReference<DispatcherType> dispatch = new AtomicReference<>();
        final AtomicReference<String> identity = new AtomicReference<>();
        final AtomicReference<String> body = new AtomicReference<>();
        final AtomicReference<String> contentLength = new AtomicReference<>();
        final AtomicReference<String> transferEncoding = new AtomicReference<>();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class})
    static class Fixture {
        @Bean State state() { return new State(); }
        @Bean RequestBudgetProperties budget(Environment environment) {
            RequestBudgetProperties properties = AdminAdmissionBudgetTest.properties(
                    environment.getProperty("fixture.requests", Integer.class, 1), 128, 256, 256);
            properties.setBodyReadTimeout(Duration.ofMillis(
                    environment.getProperty("fixture.body-timeout-ms", Long.class, 5000L)));
            return properties;
        }
        @Bean AdminAdmissionFilter admission(RequestBudgetProperties properties) {
            return new AdminAdmissionFilter(properties);
        }
        @Bean RequestBudgetFilter bodyBudget(RequestBudgetProperties properties) {
            return new RequestBudgetFilter(properties);
        }
        @Bean FilterRegistrationBean<AdminAdmissionFilter> admissionRegistration(AdminAdmissionFilter filter) {
            return registration(filter, -100);
        }
        @Bean FilterRegistrationBean<Filter> identityRegistration(State state) {
            return registration((raw, response, chain) -> {
                HttpServletRequest request = (HttpServletRequest) raw;
                if (request.getDispatcherType() == DispatcherType.REQUEST) {
                    state.authentications.incrementAndGet();
                    request.setAttribute(VERIFIED, "verified-session");
                }
                IDENTITY.set((String) request.getAttribute(VERIFIED));
                try { chain.doFilter(request, response); } finally { IDENTITY.remove(); }
            }, 0);
        }
        @Bean FilterRegistrationBean<RequestBudgetFilter> bodyRegistration(RequestBudgetFilter filter) {
            return registration(filter, 2);
        }
        private static <T extends Filter> FilterRegistrationBean<T> registration(T filter, int order) {
            FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(order);
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
            registration.addUrlPatterns("/*");
            return registration;
        }
        @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> slowConnectorBackstop() {
            return factory -> factory.addConnectorCustomizers(connector -> {
                Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
                protocol.setDisableUploadTimeout(false);
                // Deliberately longer than five seconds: prove the Filter's total deadline,
                // rather than accidentally testing the connector's inactivity timeout.
                protocol.setConnectionUploadTimeout(20000);
            });
        }
        @Bean ServletRegistrationBean<HttpServlet> probe(State state) {
            ServletRegistrationBean<HttpServlet> registration = new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    state.handled.incrementAndGet();
                    state.dispatch.set(request.getDispatcherType());
                    state.identity.set(IDENTITY.get());
                    state.body.set(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    state.contentLength.set(request.getHeader("Content-Length"));
                    state.transferEncoding.set(request.getHeader("Transfer-Encoding"));
                    String path = request.getRequestURI();
                    if (path.equals("/restart")) {
                        request.startAsync().dispatch("/hold");
                        return;
                    }
                    if (path.equals("/hold") || "true".equals(request.getParameter("hold"))) {
                        AsyncContext context = request.startAsync();
                        context.setTimeout(0);
                        state.pending.set(context);
                        return;
                    }
                    if (path.equals("/timeout")) {
                        AsyncContext context = request.startAsync();
                        context.setTimeout(100);
                        state.pending.set(context);
                        return;
                    }
                    if (path.equals("/sse")) {
                        AsyncContext context = request.startAsync();
                        context.setTimeout(0);
                        state.pending.set(context);
                        response.setContentType("text/event-stream");
                        response.flushBuffer();
                        context.start(() -> {
                            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            try {
                                while (System.nanoTime() < stop) {
                                    response.getOutputStream().write("data: pulse\n\n".getBytes(StandardCharsets.UTF_8));
                                    response.flushBuffer();
                                    Thread.sleep(20);
                                }
                            } catch (IOException disconnected) {
                                // A real client RST is observed by a real servlet write.
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                try { context.complete(); } catch (IllegalStateException alreadyComplete) { }
                            }
                        });
                        return;
                    }
                    byte[] output = "ok".getBytes(StandardCharsets.US_ASCII);
                    response.setContentLength(output.length);
                    response.getOutputStream().write(output);
                }
            }, "/*");
            registration.setAsyncSupported(true);
            return registration;
        }
    }

    static final class Server implements AutoCloseable {
        final AnnotationConfigServletWebServerApplicationContext context =
                new AnnotationConfigServletWebServerApplicationContext();
        final int port;
        final State state;
        final AdminAdmissionFilter admission;
        final RequestBudgetFilter body;
        Server(Map<String, Object> overrides) {
            Map<String, Object> properties = new HashMap<>(Map.of(
                    "server.port", 0, "server.address", "127.0.0.1",
                    "server.tomcat.max-swallow-size", "0B", "server.tomcat.threads.max", 8,
                    "server.tomcat.threads.min-spare", 1, "server.tomcat.connection-timeout", "20s"));
            properties.putAll(overrides);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("budget-fixture", properties));
            context.register(Fixture.class);
            context.refresh();
            port = context.getWebServer().getPort();
            state = context.getBean(State.class);
            admission = context.getBean(AdminAdmissionFilter.class);
            body = context.getBean(RequestBudgetFilter.class);
        }
        Socket open() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(8000);
            return socket;
        }
        void reclaimed() throws InterruptedException {
            int capacity = context.getBean(RequestBudgetProperties.class).getMaxInFlight();
            await(() -> admission.availableRequests() == capacity && body.availableBodyBytes() == 256, 3000);
        }
        @Override public void close() { context.close(); }
    }

    @Test void realBodyDispatchRestoresContextAndKeepsBothBudgetsUntilAsyncCompletion() throws Exception {
        try (Server server = new Server(Map.of()); Socket active = server.open()) {
            send(active, "POST /hold HTTP/1.1\r\nHost: fixture\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "3\r\nabc\r\n4\r\ndefg\r\n0\r\n\r\n");
            await(() -> server.state.pending.get() != null, 3000);
            assertEquals(1, server.state.authentications.get());
            assertEquals(DispatcherType.ASYNC, server.state.dispatch.get());
            assertEquals("verified-session", server.state.identity.get());
            assertEquals("abcdefg", server.state.body.get());
            assertEquals("7", server.state.contentLength.get());
            assertNull(server.state.transferEncoding.get());
            assertEquals(0, server.admission.availableRequests());
            assertEquals(128, server.body.availableBodyBytes());
            try (Socket rejected = server.open()) {
                send(rejected, "GET /echo HTTP/1.1\r\nHost: fixture\r\n\r\n");
                assertEquals(429, response(rejected));
                assertEquals(1, server.state.authentications.get(), "reject before another session lookup");
            }
            server.state.pending.get().complete();
            server.reclaimed();
        }
    }

    @Test void totalBodyReservationSurvivesAnEmptyBatchAndRejectsAnotherBodyWithoutQueueing() throws Exception {
        try (Server server = new Server(Map.of("fixture.requests", 2)); Socket active = server.open()) {
            send(active, "POST /api/short-link/admin/v1/create/batch?hold=true HTTP/1.1\r\n"
                    + "Host: fixture\r\nContent-Length: 0\r\n\r\n");
            await(() -> server.state.pending.get() != null, 3000);
            assertEquals(1, server.admission.availableRequests());
            assertEquals(0, server.body.availableBodyBytes());
            try (Socket rejected = server.open()) {
                send(rejected, "POST /echo HTTP/1.1\r\nHost: fixture\r\nContent-Length: 1\r\n\r\nx");
                assertEquals(429, response(rejected));
            }
            server.state.pending.get().complete();
            server.reclaimed();
        }
    }

    @Test void fiveSecondDeadlineStopsContinuousDripDespiteTwentySecondConnectorTimeout() throws Exception {
        try (Server server = new Server(Map.of()); Socket slow = server.open()) {
            ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
            try {
                long started = System.nanoTime();
                send(slow, "POST /echo HTTP/1.1\r\nHost: fixture\r\nContent-Length: 64\r\n\r\nx");
                writer.scheduleAtFixedRate(() -> {
                    try { send(slow, "x"); } catch (IOException closed) { }
                }, 300, 300, TimeUnit.MILLISECONDS);
                assertEquals(408, response(slow));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsed >= 4500 && elapsed < 7500, "five-second deadline elapsed=" + elapsed);
                assertEquals(0, server.state.handled.get(), "no prefix reaches the servlet");
                server.reclaimed();
            } finally {
                writer.shutdownNow();
                assertTrue(writer.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test void completelyStalledBodyDoesNotOccupyABlockingReadPastTheDeadline() throws Exception {
        try (Server server = new Server(Map.of("fixture.body-timeout-ms", 200)); Socket slow = server.open()) {
            long started = System.nanoTime();
            send(slow, "POST /echo HTTP/1.1\r\nHost: fixture\r\nContent-Length: 64\r\n\r\n");
            assertEquals(408, response(slow));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed >= 150 && elapsed < 2000, "nonblocking timeout elapsed=" + elapsed);
            assertEquals(0, server.state.handled.get());
            server.reclaimed();
        }
    }

    @Test void realReadDisconnectAndSseWriteDisconnectReclaimPermits() throws Exception {
        try (Server server = new Server(Map.of())) {
            Socket upload = server.open();
            send(upload, "POST /echo HTTP/1.1\r\nHost: fixture\r\nContent-Length: 64\r\n\r\nx");
            await(() -> server.body.availableBodyBytes() == 128, 2000);
            upload.setSoLinger(true, 0);
            upload.close();
            server.reclaimed();
            try (Socket sse = server.open()) {
                send(sse, "GET /sse HTTP/1.1\r\nHost: fixture\r\n\r\n");
                assertEquals(200, headers(sse).status());
                assertEquals(0, server.admission.availableRequests());
                sse.setSoLinger(true, 0);
            }
            server.reclaimed();
        }
    }

    @Test void realAsyncRedispatchRestartAndTimeoutDoNotLeakOrReleaseEarly() throws Exception {
        try (Server server = new Server(Map.of()); Socket active = server.open()) {
            send(active, "POST /restart HTTP/1.1\r\nHost: fixture\r\nContent-Length: 3\r\n\r\nabc");
            await(() -> server.state.pending.get() != null, 3000);
            assertEquals(2, server.state.handled.get());
            assertEquals("verified-session", server.state.identity.get());
            assertEquals(1, server.state.authentications.get());
            assertEquals(0, server.admission.availableRequests());
            server.state.pending.get().complete();
            server.reclaimed();
            try (Socket timeout = server.open()) {
                send(timeout, "GET /timeout HTTP/1.1\r\nHost: fixture\r\n\r\n");
                headers(timeout);
                server.reclaimed();
            }
        }
    }

    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "fixture condition did not become true");
            Thread.sleep(5);
        }
    }
    private static void send(Socket socket, String value) throws IOException {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    private record Head(int status, Map<String, String> headers) {}
    private static int response(Socket socket) throws IOException {
        Head head = headers(socket);
        int length = Integer.parseInt(head.headers().getOrDefault("content-length", "0"));
        if (length > 1024 || socket.getInputStream().readNBytes(length).length != length) throw new EOFException();
        return head.status();
    }
    private static Head headers(Socket socket) throws IOException {
        String first = line(socket.getInputStream());
        if (!first.startsWith("HTTP/1.1 ")) throw new IOException("Missing HTTP response: " + first);
        Map<String, String> values = new HashMap<>();
        for (int count = 0; count < 64; count++) {
            String header = line(socket.getInputStream());
            if (header.isEmpty()) return new Head(Integer.parseInt(first.split(" ")[1]), values);
            int colon = header.indexOf(':');
            if (colon < 1) throw new IOException("Malformed response header");
            values.put(header.substring(0, colon).toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
        }
        throw new IOException("Too many response headers");
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int count = 0; count < 16384; count++) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).replace("\r", "");
            bytes.write(value);
        }
        throw new IOException("Response line too long");
    }
}
