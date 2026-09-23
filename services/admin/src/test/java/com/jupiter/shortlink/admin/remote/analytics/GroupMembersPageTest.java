package com.jupiter.shortlink.admin.remote.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.controller.AgentToolInternalController;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.service.GroupService;
import com.jupiter.shortlink.contract.GroupMembersPage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/** Real Admin controller/facade/bounded HTTP client; Command alone is an in-process fixture. */
@Timeout(30)
class GroupMembersPageTest {
    private static final String ADMIN = "/internal/short-link-admin/v1/agent-tools/authorization/group-members-page";
    private static final String COMMAND = "/internal/command/authorization/resolve";
    private static final String VERSION = "a".repeat(64);
    private static final String TOKEN = "test-internal-service-token-length-valid";
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger unexpected = new AtomicInteger();
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private HttpServer server;
    private MockMvc mvc;
    private GroupService groups;

    @BeforeEach
    void setup() throws Exception {
        currentPrincipal();
        reply.set(ok(scope(List.of(1L), null)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(new Call(path, exchange.getRequestMethod(),
                    JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)),
                    exchange.getRequestHeaders().getFirst("x-shortlink-tenant-id"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-username"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-auth-version"),
                    exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            Reply response = reply.get();
            if (!COMMAND.equals(path)) { unexpected.incrementAndGet(); response = new Reply(404, "Unexpected endpoint"); }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        groups = mock(GroupService.class);
        when(groups.count(any())).thenReturn(1L);
        var facade = new AgentAnalyticsFacade(new AnalyticsJsonClient(base, base, TOKEN));
        mvc = MockMvcBuilders.standaloneSetup(new AgentToolInternalController(groups,
                        mock(ShortLinkActualRemoteService.class), facade))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (server != null) server.stop(0);
        assertThat(unexpected).as("No query, selected-members or fallback route").hasValue(0);
    }

    @Test
    void springJacksonReadsBothAuthorityRecordsAndPreservesStrictHttpRequestValidation() throws Exception {
        var springJson = Jackson2ObjectMapperBuilder.json().modulesToInstall(new ParameterNamesModule()).build();
        var request = new GroupMembersPage.Request("g1", null, null);
        var page = new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "alice", 7, "g1", VERSION, null, List.of(1L), null);
        assertAll(
                () -> assertThat(springJson.readValue(springJson.writeValueAsString(request), GroupMembersPage.Request.class)).isEqualTo(request),
                () -> assertThat(springJson.readValue(springJson.writeValueAsString(page), GroupMembersPage.class)).isEqualTo(page));
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var springMvc = MockMvcBuilders.standaloneSetup(new AgentToolInternalController(groups,
                        mock(ShortLinkActualRemoteService.class), new AgentAnalyticsFacade(new AnalyticsJsonClient(base, base, TOKEN))))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(springJson))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = springMvc.perform(post(ADMIN).contentType("application/json").content(springJson.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andReturn().getResponse().getContentAsString();
        assertThat(springJson.treeToValue(springJson.readTree(response).path("data"), GroupMembersPage.class)).isEqualTo(page);
        assertThat(calls).hasSize(1);
        for (String invalid : List.of("{\"gid\":\"g1\",\"linkIds\":[1]}", "{}",
                "{\"gid\":\"g1\",\"afterLinkId\":\"500\",\"ownershipVersion\":\"" + VERSION + "\"}",
                "{\"gid\":\"g1\",\"afterLinkId\":500.0,\"ownershipVersion\":\"" + VERSION + "\"}",
                "{\"gid\":\"g1\",\"afterLinkId\":500}"))
            springMvc.perform(post(ADMIN).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        assertThat(calls).hasSize(1);
        for (String mutation : List.of("unknown", "missing", "decimal", "schema")) {
            var invalid = new LinkedHashMap<>(page.asMap());
            switch (mutation) {
                case "unknown" -> invalid.put("snapshotId", "invented");
                case "missing" -> invalid.remove("nextCursor");
                case "decimal" -> invalid.put("linkIds", List.of(1.0));
                case "schema" -> invalid.put("schemaVersion", "group-members-page/unsupported");
            }
            assertThatThrownBy(() -> springJson.readValue(springJson.writeValueAsString(invalid), GroupMembersPage.class))
                    .isInstanceOf(JsonMappingException.class);
        }
    }

    @Test
    void closedRequestsAndRawMemberIdentityRejectCoercionOrForeignScopeBeforeExposingIds() throws Exception {
        for (String body : List.of(
                "{\"gid\":\"g1\",\"tenantId\":\"1002\"}",
                "{\"gid\":\"g1\",\"fullShortUrl\":\"https://private.example/a\"}",
                "{\"gid\":\"g1\",\"linkIds\":[1]}",
                "{\"gid\":\"g1\",\"afterLinkId\":\"1\",\"ownershipVersion\":\"" + VERSION + "\"}",
                "{\"gid\":\"g1\",\"afterLinkId\":1.0,\"ownershipVersion\":\"" + VERSION + "\"}",
                "{\"gid\":\"g1\",\"afterLinkId\":1}",
                "{\"gid\":\"g1\",\"ownershipVersion\":\"" + VERSION + "\"}"))
            send(body).andExpect(status().isBadRequest());
        assertThat(calls).isEmpty();
        when(groups.count(any())).thenReturn(0L);
        send(Map.of("gid", "g1")).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        when(groups.count(any())).thenReturn(1L);
        UserContext.removeUser();
        send(Map.of("gid", "g1")).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        currentPrincipal();
        assertThat(calls).isEmpty();

        List<Map<String, Object>> invalid = new ArrayList<>();
        var otherTenant = scope(List.of(1L), null); otherTenant.put("tenantId", "1002"); invalid.add(otherTenant);
        var coercedTenant = scope(List.of(1L), null); coercedTenant.put("tenantId", 1001); invalid.add(coercedTenant);
        var otherGroup = scope(List.of(1L), null);
        otherGroup.put("links", List.of(Map.of("linkId", 1L, "gid", "g2"))); invalid.add(otherGroup);
        invalid.add(scope(List.of("1"), null)); invalid.add(scope(List.of(1.0), null));
        invalid.add(scope(List.of(1L, 1L), null)); invalid.add(scope(List.of(2L, 1L), null));
        invalid.add(scope(List.of(1L), 1L)); // A nonterminal page must contain exactly 500 IDs.
        var coercedCursor = scope(LongStream.rangeClosed(1, 500).boxed().toList(), "500"); invalid.add(coercedCursor);
        var missingCursor = scope(List.of(1L), null); missingCursor.remove("nextCursor"); invalid.add(missingCursor);
        var invalidVersion = scope(List.of(1L), null); invalidVersion.put("ownershipVersion", 17L); invalid.add(invalidVersion);
        for (var value : invalid) {
            reply.set(ok(value));
            send(Map.of("gid", "g1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void fiveHundredAndOneMembersUsePinnedTwoPageTraversalAndAnEmptyGroupKeepsExplicitNullCursors() throws Exception {
        reply.set(ok(scope(LongStream.rangeClosed(1, 500).boxed().toList(), 500L)));
        JSONObject first = result(send(Map.of("gid", "g1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0")));
        assertThat(first).containsOnlyKeys("schemaVersion", "tenantId", "subjectId", "authVersion", "gid",
                "ownershipVersion", "afterLinkId", "linkIds", "nextCursor");
        assertThat(first).containsEntry("schemaVersion", GroupMembersPage.SCHEMA).containsEntry("tenantId", "1001")
                .containsEntry("subjectId", "alice").containsEntry("gid", "g1").containsEntry("ownershipVersion", VERSION);
        assertThat(first.containsKey("afterLinkId")).isTrue(); assertThat(first.get("afterLinkId")).isNull();
        assertThat(first.getLong("authVersion")).isEqualTo(7L);
        assertThat(first.getList("linkIds", Long.class)).containsExactlyElementsOf(LongStream.rangeClosed(1, 500).boxed().toList());
        assertThat(first.getLong("nextCursor")).isEqualTo(500L);
        reply.set(ok(scope(List.of(501L), null)));
        JSONObject second = result(send(Map.of("gid", "g1", "afterLinkId", 500L, "ownershipVersion", VERSION))
                .andExpect(jsonPath("$.code").value("0")));
        assertThat(second.getLong("afterLinkId")).isEqualTo(500L);
        assertThat(second.getList("linkIds", Long.class)).containsExactly(501L);
        assertThat(second.containsKey("nextCursor")).isTrue(); assertThat(second.get("nextCursor")).isNull();
        assertThat(calls.get(0).body()).containsOnlyKeys("gid").containsEntry("gid", "g1");
        assertThat(calls.get(1).body()).containsOnlyKeys("gid", "afterLinkId", "ownershipVersion")
                .containsEntry("ownershipVersion", VERSION);
        assertThat(calls.get(1).body().getLong("afterLinkId")).isEqualTo(500L);

        reply.set(ok(scope(List.of(), null)));
        JSONObject empty = result(send(Map.of("gid", "g1")).andExpect(jsonPath("$.code").value("0")));
        assertThat(empty.getList("linkIds", Long.class)).isEmpty();
        assertThat(empty).containsKeys("afterLinkId", "nextCursor");
        assertThat(empty.get("afterLinkId")).isNull(); assertThat(empty.get("nextCursor")).isNull();
        assertThat(calls).hasSize(3).allSatisfy(call -> {
            assertThat(call.path()).isEqualTo(COMMAND); assertThat(call.method()).isEqualTo("POST");
            assertThat(call.tenant()).isEqualTo("1001"); assertThat(call.subject()).isEqualTo("alice");
            assertThat(call.authVersion()).isEqualTo("7"); assertThat(call.token()).isEqualTo(TOKEN);
        });
    }

    @Test
    void conflictForbiddenUnsupportedAndChangedVersionKeepSafeMachineCodesWithoutFallback() throws Exception {
        for (var failure : Map.of(403, "FORBIDDEN", 409, "QUERY_SCOPE_CHANGED", 404, "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE",
                405, "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE", 501, "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE", 503, "REMOTE_UNAVAILABLE").entrySet()) {
            reply.set(new Reply(failure.getKey(), "PRIVATE upstream details"));
            int before = calls.size();
            String body = send(Map.of("gid", "g1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(failure.getValue()))
                    .andExpect(jsonPath("$.message").value("Group member authority unavailable"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("PRIVATE"); assertThat(calls).hasSize(before + 1);
        }
        for (String oldOrInvalid : List.of("not-json", "{\"code\":\"0\",\"data\":{}}")) {
            reply.set(new Reply(200, oldOrInvalid));
            send(Map.of("gid", "g1")).andExpect(jsonPath("$.code").value("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE"));
        }
        var changed = scope(List.of(501L), null); changed.put("ownershipVersion", "b".repeat(64));
        reply.set(ok(changed));
        send(Map.of("gid", "g1", "afterLinkId", 500L, "ownershipVersion", VERSION))
                .andExpect(jsonPath("$.code").value("QUERY_SCOPE_CHANGED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(calls).allSatisfy(call -> assertThat(call.path()).isEqualTo(COMMAND));
    }

    private ResultActions send(Map<String, Object> body) throws Exception { return send(JSON.toJSONString(body)); }
    private ResultActions send(String body) throws Exception { return mvc.perform(post(ADMIN).contentType("application/json").content(body)); }
    private static JSONObject result(ResultActions response) throws Exception {
        return JSON.parseObject(response.andReturn().getResponse().getContentAsString()).getJSONObject("data");
    }
    private static void currentPrincipal() { UserContext.setUser(new UserInfoDTO("1001", "alice", null, 7L)); }
    private static Map<String, Object> scope(List<?> ids, Object nextCursor) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("tenantId", "1001"); scope.put("ownershipVersion", VERSION);
        scope.put("links", ids.stream().map(id -> Map.of("linkId", id, "gid", "g1", "domain", "example.test",
                "shortUri", "123456789", "fullShortUrl", "https://example.test/123456789", "ownershipVersion", 17L)).toList());
        scope.put("nextCursor", nextCursor);
        return scope;
    }
    private static Reply ok(Object body) { return new Reply(200, JSON.toJSONString(body, JSONWriter.Feature.WriteMapNullValue)); }
    private record Reply(int status, String body) {}
    private record Call(String path, String method, JSONObject body, String tenant, String subject, String authVersion, String token) {}
}
