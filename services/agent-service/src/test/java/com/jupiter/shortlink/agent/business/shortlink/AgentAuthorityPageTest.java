package com.jupiter.shortlink.agent.business.shortlink;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.jupiter.shortlink.contract.GroupMembersPage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class AgentAuthorityPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final String VERSION = "a".repeat(64);

    @Test
    void typedPagesUseOnlyTrustedHeadersAndExactCursorThenPreserveAnAuthorizedEmptyGroup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var first = page(null, LongStream.rangeClosed(1, 500).boxed().toList(), 500L);
            fixture.reply.set(new Reply(200, Map.of("code", "0", "data", first.asMap())));
            assertEquals(first, fixture.client.resolveGroupMembersPage(PRINCIPAL, "g1", null, null));
            var last = page(500L, List.of(501L), null);
            fixture.reply.set(new Reply(200, Map.of("code", "0", "data", last.asMap())));
            assertEquals(last, fixture.client.resolveGroupMembersPage(PRINCIPAL, "g1", 500L, VERSION));
            var empty = page(null, List.of(), null);
            fixture.reply.set(new Reply(200, Map.of("code", "0", "data", empty.asMap())));
            assertEquals(empty, fixture.client.resolveGroupMembersPage(PRINCIPAL, "g1", null, null));
            assertEquals(3, fixture.requests.size());
            assertEquals(Map.of("gid", "g1"), fixture.requests.get(0));
            assertEquals(VERSION, fixture.requests.get(1).get("ownershipVersion"));
            assertEquals(500L, ((Number) fixture.requests.get(1).get("afterLinkId")).longValue());
            assertEquals(java.util.Set.of("gid", "afterLinkId", "ownershipVersion"), fixture.requests.get(1).keySet());
            assertTrue(fixture.paths.stream().allMatch(AgentAuthorityClient.GROUP_MEMBERS_PATH::equals));
            assertTrue(fixture.identities.stream().allMatch(value -> value.equals(List.of("analyst", "1001", "7", "x".repeat(32)))));
        }
    }

    @Test
    void changedOrUntrustedPagesAndUnsupportedServicesFailWithoutFallbackOrEmptySuccess() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (var expected : Map.of(403, "FORBIDDEN", 409, "QUERY_SCOPE_CHANGED", 404, "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE",
                    501, "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE", 503, "REMOTE_UNAVAILABLE").entrySet()) {
                fixture.reply.set(new Reply(expected.getKey(), Map.of("error", "ignored")));
                assertCode(fixture, expected.getValue(), null, null);
            }
            fixture.reply.set(new Reply(200, Map.of("code", "QUERY_SCOPE_CHANGED", "message", "changed")));
            assertCode(fixture, "QUERY_SCOPE_CHANGED", 500L, VERSION);
            for (String mutation : List.of("tenant", "cursor", "string-id", "field", "version")) {
                Map<String, Object> data = new LinkedHashMap<>(page(500L, List.of(501L), null).asMap());
                switch (mutation) {
                    case "tenant" -> data.put("tenantId", "1002");
                    case "cursor" -> data.put("afterLinkId", 400L);
                    case "string-id" -> data.put("linkIds", List.of("501"));
                    case "field" -> data.remove("nextCursor");
                    case "version" -> data.put("ownershipVersion", "b".repeat(64));
                }
                fixture.reply.set(new Reply(200, Map.of("code", "0", "data", data)));
                assertCode(fixture, mutation.equals("version") ? "QUERY_SCOPE_CHANGED" : "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE", 500L, VERSION);
            }
            fixture.reply.set(new Reply(200, "{"));
            assertCode(fixture, "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE", null, null);
            int before = fixture.requests.size();
            assertThrows(AgentAuthorityClient.AuthorityPageException.class, () -> fixture.client.resolveGroupMembersPage(
                    new AgentPrincipal("1001", "analyst", 7, true), "g1", null, null));
            assertThrows(IllegalArgumentException.class, () -> fixture.client.resolveGroupMembersPage(PRINCIPAL, "g1", 500L, null));
            assertEquals(before, fixture.requests.size());
            assertTrue(fixture.paths.stream().allMatch(AgentAuthorityClient.GROUP_MEMBERS_PATH::equals));
        }
    }

    private static void assertCode(Fixture fixture, String code, Long after, String version) {
        int before = fixture.requests.size();
        assertEquals(code, assertThrows(AgentAuthorityClient.AuthorityPageException.class,
                () -> fixture.client.resolveGroupMembersPage(PRINCIPAL, "g1", after, version)).code());
        assertEquals(before + 1, fixture.requests.size(), "One exact request, no fallback or retry");
    }
    private static GroupMembersPage page(Long after, List<Long> ids, Long next) {
        return new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "analyst", 7, "g1", VERSION, after, ids, next);
    }
    private record Reply(int status, Object body) {}
    private static final class Fixture implements AutoCloseable {
        final AtomicReference<Reply> reply = new AtomicReference<>();
        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        final List<List<String>> identities = new ArrayList<>();
        final HttpServer server;
        final AgentAuthorityClient client;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    requests.add(JSON.readValue(exchange.getRequestBody(), new TypeReference<>() {}));
                    paths.add(exchange.getRequestURI().getPath());
                    identities.add(List.of(exchange.getRequestHeaders().getFirst("X-Agent-Username"),
                            exchange.getRequestHeaders().getFirst("X-Agent-UserId"),
                            exchange.getRequestHeaders().getFirst("X-Agent-Auth-Version"),
                            exchange.getRequestHeaders().getFirst("X-Agent-Internal-Token")));
                    Reply result = reply.get();
                    byte[] body = result.body() instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : JSON.writeValueAsBytes(result.body());
                    exchange.sendResponseHeaders(result.status(), body.length);
                    exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            });
            server.start();
            var properties = new AgentProperties();
            properties.getBusiness().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getBusiness().setInternalToken("x".repeat(32));
            client = new AgentAuthorityClient(properties, new BoundedHttpTransport());
        }
        @Override public void close() { server.stop(0); }
    }
}
