package com.nageoffer.shortlink.admin.remote;

import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.remote.dto.req.AgentChatReqDTO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = AgentRemoteServiceFeignTest.FeignTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        }
)
class AgentRemoteServiceFeignTest {

    private static final RecordingHttpServer SERVER = RecordingHttpServer.start();

    @Autowired
    private AgentRemoteService agentRemoteService;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("short-link.agent.admin.remote-url", SERVER::baseUrl);
    }

    @AfterAll
    static void afterAll() {
        SERVER.stop();
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
    }

    @Test
    void chatSendsInternalPathTrustedHeadersAndBodyToAgentService() {
        AgentChatReqDTO request = new AgentChatReqDTO();
        request.setSessionId("session-1");
        request.setAgentType("security-risk");
        request.setMessage("analyze campaign");

        Result<Object> result = agentRemoteService.chat(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                request
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("POST");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/chat");
        assertThat(SERVER.lastRequest.header("X-Agent-Internal-Token")).isEqualTo("internal-token");
        assertThat(SERVER.lastRequest.header("X-Agent-Username")).isEqualTo("trusted-user");
        assertThat(SERVER.lastRequest.header("X-Agent-UserId")).isEqualTo("1001");
        assertThat(SERVER.lastRequest.header("X-Agent-RealName")).isEqualTo("Trusted Name");
        assertThat(SERVER.lastRequest.body())
                .contains("\"sessionId\":\"session-1\"")
                .contains("\"agentType\":\"security-risk\"")
                .contains("\"message\":\"analyze campaign\"")
                .doesNotContain("username");
    }

    @Test
    void healthSendsInternalPathAndTrustedHeadersToAgentService() {
        Result<Object> result = agentRemoteService.health(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("GET");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/health");
        assertThat(SERVER.lastRequest.header("X-Agent-Internal-Token")).isEqualTo("internal-token");
        assertThat(SERVER.lastRequest.header("X-Agent-Username")).isEqualTo("trusted-user");
        assertThat(SERVER.lastRequest.header("X-Agent-UserId")).isEqualTo("1001");
        assertThat(SERVER.lastRequest.header("X-Agent-RealName")).isEqualTo("Trusted Name");
        assertThat(SERVER.lastRequest.body()).isEmpty();
    }

    @Configuration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = AgentRemoteService.class)
    static class FeignTestConfiguration {
    }

    private static class RecordingHttpServer {

        private final HttpServer server;

        private volatile RecordedRequest lastRequest;

        private RecordingHttpServer(HttpServer server) {
            this.server = server;
        }

        private static RecordingHttpServer start() {
            try {
                HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                RecordingHttpServer recordingServer = new RecordingHttpServer(httpServer);
                httpServer.createContext("/", recordingServer::handle);
                httpServer.start();
                return recordingServer;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void reset() {
            lastRequest = null;
        }

        private void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((key, values) ->
                    headers.put(key, values.isEmpty() ? "" : values.get(0)));
            lastRequest = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    headers,
                    new String(requestBody, StandardCharsets.UTF_8)
            );
            byte[] responseBody = """
                    {
                      "code": "0",
                      "message": "success",
                      "data": {
                        "status": "OK",
                        "sessionId": "session-1"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        }
    }

    private record RecordedRequest(
            String method,
            String path,
            Map<String, String> headers,
            String body
    ) {

        private String header(String name) {
            return headers.entrySet().stream()
                    .filter(each -> each.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }
}
