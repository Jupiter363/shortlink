package com.jupiter.shortlink.admin.remote.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.controller.AgentToolInternalController;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.service.GroupService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real Admin GET -> facade -> bounded HTTP client, with no submit/recovery fallback. */
class StatisticsJobReadContractTest {
    private static final String ROUTE = "/internal/short-link-admin/v1/agent-tools/statistics/jobs/job-1";
    private static final String JOB_ROUTE = "/internal/analytics/v1/jobs/job-1/";
    private static final String SCOPE_ROUTE = "/internal/command/authorization/resolve";
    private static final String PROTOCOL = "STATISTICS_READ_PROTOCOL_UNAVAILABLE";
    private static final String PRIVATE_DETAIL = "PRIVATE SQL OR TOKEN MUST NOT LEAK";
    private final AtomicReference<Reply> jobReply = new AtomicReference<>();
    private final AtomicReference<Reply> scopeReply = new AtomicReference<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger unexpected = new AtomicInteger();
    private HttpServer server;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        UserContext.setUser(new UserInfoDTO("1001", "alice", null, 7L));
        jobReply.set(success(Map.of("jobId", "job-1", "state", "RUNNING")));
        scopeReply.set(new Reply(200, JSON.toJSONString(Map.of("tenantId", "1001", "links",
                List.of(Map.of("linkId", 99L, "gid", "g1", "domain", "example.test",
                        "shortUri", "a", "fullShortUrl", "example.test/a"))))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(new Call(path, exchange.getRequestMethod(), request,
                    exchange.getRequestHeaders().getFirst("x-shortlink-tenant-id"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-username"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-auth-version")));
            Reply reply;
            if (path.equals(JOB_ROUTE + "status") || path.equals(JOB_ROUTE + "page")) reply = jobReply.get();
            else if (path.equals(SCOPE_ROUTE)) reply = scopeReply.get();
            else {
                unexpected.incrementAndGet();
                reply = new Reply(404, "unexpected path");
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        AnalyticsJsonClient client = new AnalyticsJsonClient(url, url, "local-fixture-internal-token-length-valid");
        mvc = MockMvcBuilders.standaloneSetup(new AgentToolInternalController(mock(GroupService.class),
                        mock(ShortLinkActualRemoteService.class), new AgentAnalyticsFacade(client)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (server != null) server.stop(0);
        assertThat(unexpected).as("no create, recovery, ordinary query or retry fallback").hasValue(0);
    }

    @Test
    void normalStatusAndPageKeepCurrentIdentityAndFrozenPageBinding() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.resultReady").value(false));
        jobReply.set(success(Map.of("jobId", "job-1", "state", "SUCCEEDED", "rowCount", 1)));
        mvc.perform(get(ROUTE)).andExpect(jsonPath("$.data.resultReady").value(true));
        jobReply.set(success(pageData(Map.of("snapshotId", "job-1", "pageIndex", 2,
                "gid", "g1", "linkIds", List.of(99L), "collectionQuality", Map.of("status", "UNKNOWN")))));
        mvc.perform(get(ROUTE + "/page").param("pageIndex", "2").param("size", "500"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.meta.snapshotId").value("job-1"))
                .andExpect(jsonPath("$.data.meta.pageIndex").value(2))
                .andExpect(jsonPath("$.data.meta.collectionQuality.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].pv").value(3_000_000_000L))
                .andExpect(jsonPath("$.data.items[0].fullShortUrl").value("example.test/a"));
        assertThat(calls).hasSize(5);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.tenant()).isEqualTo("1001");
            assertThat(call.subject()).isEqualTo("alice");
            assertThat(call.authVersion()).isEqualTo("7");
        });
        assertThat(calls.get(2).body()).containsEntry("tenantId", "1001")
                .containsEntry("subjectId", "alice").containsEntry("pageIndex", 2).containsEntry("size", 500);
        assertThat(calls.get(2).body().getLong("authVersion")).isEqualTo(7L);
        assertThat(calls.get(3).body().getList("linkIds", Long.class)).containsExactly(99L);
        assertThat(calls.get(4).body()).containsEntry("gid", "g1").doesNotContainKey("linkIds");
    }

    @Test
    void typedErrorsAndTransportFailuresRemainDistinctWithoutLeakingRemoteDetails() throws Exception {
        for (String code : List.of("FORBIDDEN", "SNAPSHOT_EXPIRED", "QUERY_SCOPE_CHANGED", "NOT_READY")) {
            jobReply.set(new Reply(200, JSON.toJSONString(Map.of("code", code, "message", PRIVATE_DETAIL))));
            expectCode(get(ROUTE), code);
            expectCode(get(ROUTE + "/page"), code);
        }
        for (int http : List.of(401, 403, 404, 405, 501, 302, 429, 503)) {
            jobReply.set(new Reply(http, JSON.toJSONString(Map.of("code", "NOT_FOUND", "message", PRIVATE_DETAIL))));
            String expected = http == 401 || http == 403 ? "FORBIDDEN"
                    : List.of(404, 405, 501).contains(http) ? PROTOCOL : "REMOTE_UNAVAILABLE";
            expectCode(get(ROUTE), expected);
        }
        assertThat(calls).hasSize(16);
        server.stop(0);
        server = null;
        expectCode(get(ROUTE), "REMOTE_UNAVAILABLE");
        assertThat(calls).hasSize(16);
    }

    @Test
    void incompatibleEnvelopesAndMismatchedPagesFailClosedWhileNon500PagesNeverCallRemote() throws Exception {
        for (String body : List.of("not-json", "{}", "{\"code\":0,\"data\":{\"jobId\":\"job-1\",\"state\":\"RUNNING\"}}",
                "{\"code\":\"PRIVATE SQL\",\"message\":\"secret\"}",
                JSON.toJSONString(Map.of("code", "0", "data", Map.of("jobId", "another-job", "state", "RUNNING"))),
                JSON.toJSONString(Map.of("code", "0", "data", Map.of("jobId", "job-1", "state", "UNKNOWN"))))) {
            jobReply.set(new Reply(200, body));
            expectCode(get(ROUTE), PROTOCOL);
        }
        for (Map<String, Object> meta : List.<Map<String, Object>>of(
                Map.of("snapshotId", "another-job", "pageIndex", 0),
                Map.of("snapshotId", "job-1", "pageIndex", 1),
                Map.of("snapshotId", "job-1", "pageIndex", 0.5),
                Map.of("snapshotId", "job-1", "pageIndex", "0"))) {
            jobReply.set(success(pageData(meta)));
            expectCode(get(ROUTE + "/page"), PROTOCOL);
        }
        jobReply.set(success(Map.of("meta", Map.of("snapshotId", "job-1", "pageIndex", 0), "items", List.of("invalid row"))));
        expectCode(get(ROUTE + "/page"), PROTOCOL);
        int before = calls.size();
        for (String size : List.of("0", "1", "499", "501"))
            expectCode(get(ROUTE + "/page").param("size", size), "INVALID_QUERY");
        expectCode(get(ROUTE + "/page").param("pageIndex", "-1"), "INVALID_QUERY");
        assertThat(calls).hasSize(before);
    }

    @Test
    void currentScopeReauthorizationKeepsTypedFailuresAndNeverReturnsUnauthorizedResults() throws Exception {
        jobReply.set(success(pageData(Map.of("snapshotId", "job-1", "pageIndex", 0,
                "gid", "g1", "linkIds", List.of(99L)))));
        List<Reply> failures = List.of(
                new Reply(403, PRIVATE_DETAIL),
                new Reply(200, JSON.toJSONString(Map.of("code", "QUERY_SCOPE_CHANGED", "message", PRIVATE_DETAIL))),
                new Reply(503, PRIVATE_DETAIL),
                new Reply(200, JSON.toJSONString(Map.of("tenantId", "1002", "links", List.of()))),
                new Reply(200, JSON.toJSONString(Map.of("tenantId", "1001", "links", List.of()))));
        List<String> codes = List.of("FORBIDDEN", "QUERY_SCOPE_CHANGED", "REMOTE_UNAVAILABLE", "FORBIDDEN", "FORBIDDEN");
        for (int i = 0; i < failures.size(); i++) {
            scopeReply.set(failures.get(i));
            expectCode(get(ROUTE + "/page"), codes.get(i));
        }
        assertThat(calls).hasSize(10);
        assertThat(calls.stream().filter(call -> call.path().equals(SCOPE_ROUTE))).hasSize(5);
    }

    private void expectCode(MockHttpServletRequestBuilder request, String code) throws Exception {
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value("Statistics job read unavailable"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static Reply success(Object data) {
        return new Reply(200, JSON.toJSONString(Map.of("code", "0", "data", data)));
    }

    private static Map<String, Object> pageData(Map<String, Object> meta) {
        return Map.of("meta", meta, "metrics", Map.of(),
                "items", List.of(Map.of("linkId", 99L, "pv", 3_000_000_000L)));
    }

    private record Reply(int status, String body) {}
    private record Call(String path, String method, JSONObject body, String tenant, String subject, String authVersion) {}
}
