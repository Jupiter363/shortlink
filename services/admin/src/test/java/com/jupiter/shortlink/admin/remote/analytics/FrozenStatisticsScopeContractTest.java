package com.jupiter.shortlink.admin.remote.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.controller.AgentToolInternalController;
import com.jupiter.shortlink.admin.dto.req.analytics.AnalyticsQueryRequest;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.service.GroupService;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real Controller -> Facade -> bounded HTTP wire; no ordinary group or job fallback. */
class FrozenStatisticsScopeContractTest {
    private static final String ADMIN = "/internal/short-link-admin/v1/agent-tools/statistics/";
    private static final String SELECTED = "/internal/command/authorization/resolve-selected";
    private static final String FROZEN = "/internal/analytics/v1/jobs/frozen";
    private static final String PAGE = "/internal/analytics/v1/jobs/job-1/page";
    private static final String VERSION = "a".repeat(64);
    private static final String MEMBER_HASH = FrozenQueryScope.memberHash(List.of(99L));
    private static final FrozenQueryScope SCOPE = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET",
            "scope-1", MEMBER_HASH, 1, "b".repeat(64), FrozenQueryScope.shardIdFor("scope-1", 0, MEMBER_HASH),
            0, 1, MEMBER_HASH, List.of(99L));
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger unexpected = new AtomicInteger();
    private final AtomicReference<Reply> selectedOverride = new AtomicReference<>();
    private final AtomicReference<Reply> jobReply = new AtomicReference<>();
    private final AtomicReference<Reply> pageReply = new AtomicReference<>();
    private HttpServer server;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        UserContext.setUser(new UserInfoDTO("1001", "alice", null, 7L));
        jobReply.set(success(Map.of("jobId", "job-1", "state", "QUEUED")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JSONObject body = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(new Call(path, body, exchange.getRequestHeaders().getFirst("x-shortlink-tenant-id"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-username"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-auth-version")));
            Reply reply;
            if (SELECTED.equals(path)) {
                reply = selectedOverride.get();
                if (reply == null) reply = new Reply(200, JSON.toJSONString(selected(body.getList("linkIds", Long.class))));
            } else if (FROZEN.equals(path) || (FROZEN + "/recover-existing").equals(path)) reply = jobReply.get();
            else if (PAGE.equals(path)) reply = pageReply.get();
            else { unexpected.incrementAndGet(); reply = new Reply(404, "unexpected route"); }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var facade = new AgentAnalyticsFacade(new AnalyticsJsonClient(base, base, "local-test-service-token-with-enough-length"));
        mvc = MockMvcBuilders.standaloneSetup(new AgentToolInternalController(mock(GroupService.class),
                        mock(ShortLinkActualRemoteService.class), facade))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (server != null) server.stop(0);
        assertThat(unexpected).as("never call old jobs or current-group resolution").hasValue(0);
    }

    @Test
    void submissionAndRecoveryKeepExactFrozenWireIdentityAndNeverUseOldJobs() throws Exception {
        Map<String, Object> request = jobRequest();
        request.put("queryKind", "DIMENSION_BREAKDOWN");
        request.put("dimensions", List.of("province", "device"));
        request.put("filters", List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江"))));
        mvc.perform(post(ADMIN + "frozen-jobs").contentType("application/json").content(JSON.toJSONString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        jobReply.set(success(Map.of("jobId", "job-1", "state", "RUNNING")));
        mvc.perform(post(ADMIN + "frozen-jobs/recover-existing").contentType("application/json").content(JSON.toJSONString(request)))
                .andExpect(jsonPath("$.data.jobId").value("job-1"));
        assertThat(calls).extracting(Call::path).containsExactly(SELECTED, FROZEN, SELECTED, FROZEN + "/recover-existing");
        assertThat(calls.get(1).body()).isEqualTo(calls.get(3).body());
        JSONObject query = calls.get(1).body().getJSONObject("query");
        assertThat(query.getString("tenantId")).isEqualTo("1001");
        assertThat(query.getString("subjectId")).isEqualTo("alice");
        assertThat(query.getLong("authVersion")).isEqualTo(7L);
        assertThat(query.getList("linkIds", Long.class)).containsExactly(99L);
        assertThat(query.getJSONObject("scope")).isEqualTo(JSON.parseObject(JSON.toJSONString(SCOPE.asMap())));
        assertThat(query.getJSONArray("dimensions")).containsExactly("province", "device");
        assertThat(query.getJSONArray("filters").getJSONObject(0).getJSONArray("values")).containsExactly("浙江");
        assertThat(calls.get(0).body()).containsOnlyKeys("gid", "linkIds");
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.tenant()).isEqualTo("1001"); assertThat(call.subject()).isEqualTo("alice");
            assertThat(call.authVersion()).isEqualTo("7");
        });
        // Existing constructor serialization/digest does not gain a null scope field.
        var legacy = new AnalyticsQueryRequest("1001", "alice", 7L, "g1", List.of(99L), 1, 2,
                null, "REQUESTED", null, null, 500, "METRICS");
        assertThat(JSON.parseObject(JSON.toJSONString(legacy))).doesNotContainKey("scope");
        jobReply.set(new Reply(404, "PRIVATE route diagnostics"));
        mvc.perform(post(ADMIN + "frozen-jobs/recover-existing").contentType("application/json").content(JSON.toJSONString(request)))
                .andExpect(jsonPath("$.code").value("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"));
        assertThat(calls).extracting(Call::path).endsWith(SELECTED, FROZEN + "/recover-existing");
    }

    @Test
    void selectedAuthorizationPreservesEmptySetAndRejectsInvalidOrUntrustedInputsBeforeJobs() throws Exception {
        mvc.perform(post(ADMIN + "authorize-scope").contentType("application/json")
                        .content("{\"gid\":\"g1\",\"linkIds\":[]}"))
                .andExpect(jsonPath("$.code").value("0")).andExpect(jsonPath("$.data.linkIds").isEmpty())
                .andExpect(jsonPath("$.data.memberHash").value(FrozenQueryScope.memberHash(List.of())));
        assertThat(calls.get(0).body().getJSONArray("linkIds")).isEmpty();
        int beforeInvalid = calls.size();
        // The old endpoints are closed even with Jackson's normal unknown-field default disabled.
        for (String route : List.of("jobs", "jobs/recover-existing"))
            mvc.perform(post(ADMIN + route).contentType("application/json").content(JSON.toJSONString(jobRequest())))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(ADMIN + "authorize-scope").contentType("application/json")
                        .content("{\"gid\":\"g1\",\"linkIds\":[],\"all\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ADMIN + "authorize-scope").contentType("application/json")
                        .content("{\"gid\":\"g1\",\"linkIds\":[99.5]}"))
                .andExpect(status().isBadRequest());
        var coercedScope = new LinkedHashMap<>(SCOPE.asMap());
        coercedScope.put("linkIds", List.of("99"));
        var coercedRequest = jobRequest(); coercedRequest.put("scope", coercedScope);
        mvc.perform(post(ADMIN + "frozen-jobs").contentType("application/json").content(JSON.toJSONString(coercedRequest)))
                .andExpect(status().isBadRequest());
        var explicitUrl = jobRequest(); explicitUrl.put("fullShortUrl", "https://example.test/123456789");
        mvc.perform(post(ADMIN + "frozen-jobs").contentType("application/json").content(JSON.toJSONString(explicitUrl)))
                .andExpect(status().isBadRequest());
        var invalid = jobRequest(); invalid.remove("scope");
        mvc.perform(post(ADMIN + "frozen-jobs").contentType("application/json").content(JSON.toJSONString(invalid)))
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        assertThat(calls).hasSize(beforeInvalid);
        var denied = selected(List.of(99L)); denied.put("tenantId", "1002");
        selectedOverride.set(new Reply(200, JSON.toJSONString(denied)));
        mvc.perform(post(ADMIN + "frozen-jobs").contentType("application/json").content(JSON.toJSONString(jobRequest())))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        selectedOverride.set(new Reply(200, "{\"allowed\":true,\"links\":[]}"));
        mvc.perform(post(ADMIN + "authorize-scope").contentType("application/json")
                        .content("{\"gid\":\"g1\",\"linkIds\":[]}"))
                .andExpect(jsonPath("$.code").value("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"));
        assertThat(calls).allMatch(call -> SELECTED.equals(call.path()));
    }

    @Test
    void frozenPageReauthorizesOnlyItsProvenMembersAndKeepsPartialUnknownQuality() throws Exception {
        Map<String, Object> proof = SCOPE.proof(VERSION);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("snapshotId", "job-1"); meta.put("pageIndex", 0); meta.put("gid", "g1");
        meta.put("linkIds", List.of(99L)); meta.put("queryKind", "LINK_METRICS");
        meta.put("scopeProof", proof); meta.put("completeness", "PARTIAL");
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        meta.put("groupScopeComplete", true); // Must not promote a shard to the live group.
        pageReply.set(success(Map.of("items", List.of(Map.of("linkId", 99L, "pv", 3L)),
                "metrics", Map.of("requested", Map.of("pv", 3L)), "meta", meta)));
        String body = mvc.perform(get(ADMIN + "jobs/job-1/page"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.meta.groupScopeComplete").value(false))
                .andExpect(jsonPath("$.data.meta.completeness").value("PARTIAL"))
                .andExpect(jsonPath("$.data.meta.collectionQuality.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].fullShortUrl").value("https://example.test/123456789"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JSON.parseObject(body).getJSONObject("data").getJSONObject("meta").getJSONObject("scopeProof"))
                .isEqualTo(JSON.parseObject(JSON.toJSONString(proof)));
        assertThat(calls).extracting(Call::path).containsExactly(PAGE, SELECTED);
        assertThat(calls.get(1).body()).containsEntry("ownershipVersion", VERSION);
        assertThat(calls.get(1).body().getList("linkIds", Long.class)).containsExactly(99L);
        selectedOverride.set(new Reply(403, "PRIVATE ownership detail"));
        mvc.perform(get(ADMIN + "jobs/job-1/page"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Frozen statistics scope unavailable"));
        assertThat(calls).extracting(Call::path).containsExactly(PAGE, SELECTED, PAGE, SELECTED);
    }

    private static Map<String, Object> jobRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", "request-1"); request.put("gid", "g1");
        request.put("startDate", "2026-09-01"); request.put("endDate", "2026-09-02");
        request.put("queryKind", "METRICS"); request.put("scope", SCOPE.asMap());
        return request;
    }

    private static Map<String, Object> selected(List<Long> ids) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "selected-scope/v1"); value.put("allowed", true);
        value.put("tenantId", "1001"); value.put("subjectId", "alice"); value.put("authVersion", 7L);
        value.put("gid", "g1"); value.put("linkIds", ids); value.put("memberHash", FrozenQueryScope.memberHash(ids));
        value.put("ownershipVersion", VERSION);
        value.put("links", ids.stream().map(id -> Map.of("linkId", id, "gid", "g1", "domain", "example.test",
                "shortUri", "123456789", "fullShortUrl", "https://example.test/123456789", "ownershipVersion", 1L)).toList());
        return value;
    }

    private static Reply success(Object data) { return new Reply(200, JSON.toJSONString(Map.of("code", "0", "data", data))); }
    private record Reply(int status, String body) {}
    private record Call(String path, JSONObject body, String tenant, String subject, String authVersion) {}
}
