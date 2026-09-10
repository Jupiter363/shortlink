package com.jupiter.shortlink.admin.account;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback HTTP contract integration, not a deployed application E2E. */
class HttpDefaultGroupCommandClientIntegrationTest {
    private HttpServer server;
    private String baseUrl;
    private final AccountInitialization item =
            new AccountInitialization(
                    42, "alice", "account-default-group:42", "PENDING", null, 0, 0, 0, null);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void retriedRequestPreservesTrustedAccountAndCommandIdentity() {
        var received = new ArrayList<String>();
        server.createContext(
                "/internal/command/accounts/default-group",
                exchange -> {
                    assertEquals("POST", exchange.getRequestMethod());
                    assertEquals(
                            "integration-secret",
                            exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                    received.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] payload = "{\"gid\":\"default42\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, payload.length);
                    exchange.getResponseBody().write(payload);
                    exchange.close();
                });
        var client = new HttpDefaultGroupCommandClient(baseUrl, "integration-secret");
        assertEquals("default42", client.initialize(item));
        assertEquals("default42", client.initialize(item));
        assertEquals(received.get(0), received.get(1));
        var request = JSON.parseObject(received.get(0));
        assertEquals(42L, request.getLongValue("tenantId"));
        assertEquals("alice", request.getString("username"));
        assertEquals("account-default-group:42", request.getString("commandId"));
    }

    @Test
    void redirectsAndOversizedBodiesCannotBeAcceptedAsInitializationSuccess() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext(
                "/internal/command/accounts/default-group",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", baseUrl + "/other");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/other",
                exchange -> {
                    redirected.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        var client = new HttpDefaultGroupCommandClient(baseUrl, "integration-secret");
        assertThrows(IllegalStateException.class, () -> client.initialize(item));
        assertEquals(0, redirected.get());
        server.removeContext("/internal/command/accounts/default-group");
        server.createContext(
                "/internal/command/accounts/default-group",
                exchange -> {
                    byte[] payload = "x".repeat(8193).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, payload.length);
                    exchange.getResponseBody().write(payload);
                    exchange.close();
                });
        assertThrows(IllegalStateException.class, () -> client.initialize(item));
    }

    @Test
    void missingServiceCredentialsFailsBeforeSendingRequest() {
        assertThrows(
                IllegalStateException.class,
                () -> new HttpDefaultGroupCommandClient(baseUrl, "").initialize(item));
    }
}
