package com.jupiter.shortlink.admin.remote;

import static org.assertj.core.api.Assertions.*;

import com.jupiter.shortlink.admin.common.biz.user.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SpringBootTest(
        classes = CommandRiskRemoteServiceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "shortlink.internal-token=command-component-internal-token-32",
            "spring.cloud.discovery.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
class CommandRiskRemoteServiceTest {
    private static final HttpServer SERVER = start();
    private static volatile String response = "{}", body, path;
    private static volatile int status = 200, requests;
    private static volatile Map<String, List<String>> headers;
    @Autowired CommandRiskRemoteService command;

    @Configuration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = CommandRiskRemoteService.class)
    @Import({ManagementFeignIdentity.class,
            com.jupiter.shortlink.admin.config.AdminFeignTransportConfiguration.class})
    static class Config {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "shortlink.command.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @BeforeEach
    void setup() {
        response = "{}";
        status = 200;
        requests = 0;
        UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Name", 4L));
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    void boundedBatchCarriesCurrentTrustedVersionAndReadsPolicyFacts() {
        response =
                "[{\"resourceKey\":\"1001:7\",\"policyRevision\":8,\"evaluatedAt\":1000,\"validUntil\":2000,\"nextTransitionAt\":1500,\"state\":\"KNOWN_RESTRICTED\",\"disabled\":true,\"timeUnrestricted\":true,\"timezone\":\"UTC\",\"allowedWindows\":[],\"blockedIpHashes\":[]}]";
        var result = command.current(new CommandRiskRemoteService.Current(List.of(7L)));
        assertThat(result.get(0).policyRevision()).isEqualTo(8);
        assertThat(result.get(0).nextTransitionAt()).isEqualTo(1500);
        assertThat(path).isEqualTo("/internal/command/risk/current");
        assertThat(body).contains("\"linkIds\":[7]");
        assertThat(header("x-shortlink-tenant-id")).isEqualTo("1001");
        assertThat(header("x-shortlink-auth-version")).isEqualTo("4");
        assertThat(header("X-Internal-Token")).isEqualTo("command-component-internal-token-32");
        assertThat(header("username")).isNull();
    }

    @Test
    void manualRevocationUsesStableIdentityWithoutStatisticsGate() {
        response =
                "{\"commandId\":\"manual-0001\",\"status\":\"COMMITTED\",\"policyId\":\"policy-1\",\"policyRevision\":9,\"committedAt\":1234}";
        var result =
                command.revoke(new CommandRiskRemoteService.Revoke("manual-0001", 7, "policy-1"));
        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(body)
                .contains("\"commandId\":\"manual-0001\"", "\"linkId\":7")
                .doesNotContain("evidence", "disabled");
        assertThat(requests).isEqualTo(1);
    }

    @Test
    void commandFailurePropagatesOnceForFacadeUnknownHandling() {
        status = 503;
        response = "{}";
        assertThatThrownBy(() -> command.current(new CommandRiskRemoteService.Current(List.of(7L))))
                .isInstanceOf(feign.FeignException.ServiceUnavailable.class);
        assertThat(requests).isEqualTo(1);
    }

    private static String header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().get(0))
                .findFirst()
                .orElse(null);
    }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        requests++;
                        headers = new HashMap<>(exchange.getRequestHeaders());
                        path = exchange.getRequestURI().getPath();
                        body =
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8);
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
