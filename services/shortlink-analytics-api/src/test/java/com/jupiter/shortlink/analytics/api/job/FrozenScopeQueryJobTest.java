package com.jupiter.shortlink.analytics.api.job;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrozenScopeQueryJobTest {
    private static final String TOKEN = "test-token-long-enough-for-analytics";
    private static final String SELECTED_VERSION = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void fiveHundredAndOneFrozenMembersKeepBothShardsAfterAdditionAndRecoverWithoutInsert() throws Exception {
        var fixture = fixture();
        List<Long> parent = LongStream.rangeClosed(1, 501).boxed().toList();
        Set<Long> current = new HashSet<>(parent);
        doAnswer(call -> {
            QueryRequest query = call.getArgument(0);
            assertThat(query.scope()).isNotNull();
            assertThat(current).containsAll(query.scope().linkIds());
            return new AuthorizationClient.Scope("1", query.scope().linkIds(), SELECTED_VERSION);
        }).when(fixture.auth).authorize(any());
        emptyLinkMetrics(fixture);
        List<QueryJobService.Submit> requests = new ArrayList<>();
        List<String> jobs = new ArrayList<>();
        for (int shard = 0; shard < 2; shard++) {
            var scope = scope(parent, shard);
            var request = new QueryJobService.Submit("frozen-shard-" + shard, query(scope));
            requests.add(request);
            jobs.add(fixture.service.submitFrozen(request).jobId());
        }
        current.add(502L); // Unrelated current-group addition cannot alter the persisted selected scope.
        fixture.service.execute(fixture.service.claim("worker-first"));
        fixture.service.execute(fixture.service.claim("worker-second"));
        List<Long> returned = new ArrayList<>();
        for (int shard = 0; shard < 2; shard++) {
            String id = jobs.get(shard);
            var expected = requests.get(shard).query().scope();
            assertThat(fixture.service.status(id, fixture.identity(0)).state()).isEqualTo("SUCCEEDED");
            var page = fixture.service.page(id, fixture.identity(0));
            var meta = (Map<String, Object>) page.get("meta");
            assertThat(meta.get("scopeProof")).isEqualTo(expected.proof(SELECTED_VERSION));
            assertThat(meta.get("groupScopeComplete")).isEqualTo(false);
            assertThat(((Map<?, ?>) meta.get("collectionQuality")).get("status")).isEqualTo("UNKNOWN");
            for (var item : (List<Map<String, Object>>) page.get("items")) returned.add(((Number) item.get("linkId")).longValue());
            QueryRequest persisted = JSON.readValue(fixture.db.queryForObject(
                    "SELECT request_json FROM analytics_query_job WHERE job_id=?", String.class, id), QueryRequest.class);
            assertThat(persisted.scope()).isEqualTo(expected);
            assertThat(persisted.linkIds()).isEqualTo(expected.linkIds());
        }
        assertThat(returned).containsExactlyElementsOf(parent).doesNotContain(502L);
        // A real H2 INSERT trigger catches even an attempted insert followed by transaction rollback.
        QueryJobServiceTest.RecoveryInsertGuard.attempts.set(0);
        fixture.db.execute("CREATE TRIGGER frozen_no_insert BEFORE INSERT ON analytics_query_job FOR EACH ROW CALL '"
                + QueryJobServiceTest.RecoveryInsertGuard.class.getName() + "'");
        for (int shard = 0; shard < 2; shard++) {
            assertThat(fixture.service.recoverExistingFrozen(requests.get(shard)).jobId()).isEqualTo(jobs.get(shard));
        }
        var original = requests.get(0).query().scope();
        var changed = new FrozenQueryScope(original.schemaVersion(), original.scopeKind(), original.parentScopeRef(),
                original.parentMemberHash(), original.parentMemberCount(), "b".repeat(64), original.shardId(),
                original.shardIndex(), original.shardCount(), original.shardMemberHash(), original.linkIds());
        failure(() -> fixture.service.recoverExistingFrozen(new QueryJobService.Submit("frozen-shard-0", query(changed))), "CONFLICT");
        failure(() -> fixture.service.recoverExistingFrozen(new QueryJobService.Submit("never-created", query(original))), "REPLAY_UNAVAILABLE");
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM analytics_query_job", Integer.class)).isEqualTo(2);
        assertThat(QueryJobServiceTest.RecoveryInsertGuard.attempts).hasValue(0);
    }

    @Test
    void selectedMemberLossAndVersionChangeBlockAdmissionRecoveryReadsAndWorkerPublication() {
        var fixture = fixture();
        var scope = scope(List.of(1L, 2L), 0);
        var request = new QueryJobService.Submit("selected", query(scope));
        doReturn(new AuthorizationClient.Scope("1", scope.linkIds(), SELECTED_VERSION)).when(fixture.auth).authorize(any());
        emptyLinkMetrics(fixture);
        String id = fixture.service.submitFrozen(request).jobId();
        fixture.service.execute(fixture.service.claim("worker"));
        doReturn(new AuthorizationClient.Scope("1", List.of(1L), SELECTED_VERSION)).when(fixture.auth).authorize(any());
        failure(() -> fixture.service.submitFrozen(new QueryJobService.Submit("changed", query(scope))), "QUERY_SCOPE_CHANGED");
        failure(() -> fixture.service.recoverExistingFrozen(request), "QUERY_SCOPE_CHANGED");
        failure(() -> fixture.service.status(id, fixture.identity(0)), "QUERY_SCOPE_CHANGED");
        failure(() -> fixture.service.page(id, fixture.identity(0)), "QUERY_SCOPE_CHANGED");
        failure(() -> fixture.service.cancel(id, fixture.identity(0)), "QUERY_SCOPE_CHANGED");
        doReturn(new AuthorizationClient.Scope("1", scope.linkIds(), "b".repeat(64))).when(fixture.auth).authorize(any());
        failure(() -> fixture.service.status(id, fixture.identity(0)), "QUERY_SCOPE_CHANGED");
        doReturn(new AuthorizationClient.Scope("1", scope.linkIds(), SELECTED_VERSION)).when(fixture.auth).authorize(any());
        String pending = fixture.service.submitFrozen(new QueryJobService.Submit("worker-revocation", query(scope))).jobId();
        var lease = fixture.service.claim("revoked-worker");
        assertThat(lease.query().scope()).isEqualTo(scope);
        clearInvocations(fixture.ch);
        doReturn(new AuthorizationClient.Scope("1", List.of(1L), SELECTED_VERSION)).when(fixture.auth).authorize(any());
        fixture.service.execute(lease);
        verifyNoInteractions(fixture.ch);
        assertThat(fixture.db.queryForObject("SELECT state FROM analytics_query_job WHERE job_id=?", String.class, pending))
                .isNotEqualTo("SUCCEEDED");
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM analytics_query_page WHERE job_id=?", Integer.class, pending)).isZero();
    }

    @Test
    void selectedAuthorizationUsesDedicatedHttpProtocolAndRejectsOldOrMismatchingProofs() throws Exception {
        var scope = scope(List.of(1L, 2L), 0);
        var query = query(scope);
        AtomicReference<Map<String, Object>> response = new AtomicReference<>(selectedResponse(scope));
        AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
        List<String> paths = Collections.synchronizedList(new ArrayList<>());
        List<Map<String, Object>> bodies = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); paths.add(exchange.getRequestURI().getPath());
            bodies.add(JSON.readValue(exchange.getRequestBody(), new TypeReference<>() {}));
            byte[] body = JSON.writeValueAsBytes(response.get());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var settings = new ApiSettings(TOKEN, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://localhost:2", "http://localhost:8123", "default", "", "test");
            var auth = new AuthorizationClient(settings, JSON);
            assertThat(auth.authorize(query).linkIds()).containsExactly(1L, 2L);
            assertThat(bodies.get(0)).isEqualTo(Map.of("tenantId", "1", "subjectId", "alice", "authVersion", 7,
                    "gid", "g1", "linkIds", List.of(1, 2)));
            response.set(Map.of("allowed", true, "tenantId", "1", "linkIds", List.of(1, 2), "ownershipVersion", SELECTED_VERSION));
            failure(() -> auth.authorize(query), "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
            for (int code : List.of(404, 405, 501)) {
                status.set(code);
                failure(() -> auth.authorize(query), "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
            }
            status.set(200);
            var wrongOwner = selectedResponse(scope); wrongOwner.put("subjectId", "other"); response.set(wrongOwner);
            failure(() -> auth.authorize(query), "FORBIDDEN");
            var wrongIds = selectedResponse(scope); wrongIds.put("linkIds", List.of(1, 2, 3)); response.set(wrongIds);
            failure(() -> auth.authorize(query), "QUERY_SCOPE_CHANGED");
            var wrongHash = selectedResponse(scope); wrongHash.put("memberHash", "b".repeat(64)); response.set(wrongHash);
            failure(() -> auth.authorize(query), "QUERY_SCOPE_CHANGED");
            assertThat(paths).hasSize(calls.get()).allMatch(path -> path.equals("/internal/v1/authorization/analytics-selected"));
        } finally { server.stop(0); }
    }

    @Test
    void routesFailClosedAcrossScopeModesAndLegacySerializationStaysUnchanged() throws Exception {
        var fixture = fixture();
        var frozen = new QueryJobService.Submit("frozen", query(scope(List.of(1L, 2L), 0)));
        var legacy = new QueryJobService.Submit("legacy", fixture.request("1"));
        failure(() -> fixture.service.submit(frozen), "INVALID_QUERY");
        failure(() -> fixture.service.recoverExisting(frozen), "INVALID_QUERY");
        failure(() -> fixture.service.submitFrozen(legacy), "INVALID_QUERY");
        failure(() -> fixture.service.recoverExistingFrozen(legacy), "INVALID_QUERY");
        var syncReader = mock(ClickHouseReader.class);
        var synchronous = new AnalyticsQueryService(fixture.db, JSON, fixture.auth, syncReader, fixture.settings);
        failure(() -> synchronous.query(frozen.query()), "INVALID_QUERY");
        verifyNoInteractions(fixture.auth, fixture.ch, syncReader);
        assertThat(JSON.writeValueAsString(legacy.query())).isEqualTo("{\"tenantId\":\"1\",\"subjectId\":\"alice\",\"authVersion\":7,\"gid\":\"g1\",\"linkIds\":[4000000001],\"startInclusive\":300000,\"endExclusive\":600000,\"windows\":null,\"endPolicy\":\"REQUESTED\",\"snapshotId\":null,\"cursor\":null,\"pageSize\":500,\"queryKind\":\"METRICS\"}");
        var jobs = mock(QueryJobService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new QueryJobController(jobs, fixture.settings)).build();
        for (String path : List.of("/internal/analytics/v1/jobs", "/internal/analytics/v1/jobs/recover-existing")) {
            mvc.perform(post(path).header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsBytes(frozen)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        }
        for (String path : List.of("/internal/analytics/v1/jobs/frozen", "/internal/analytics/v1/jobs/frozen/recover-existing")) {
            mvc.perform(post(path).header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsBytes(legacy)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        }
        verifyNoInteractions(jobs);
        var ready = new QueryJobService.Status("original", "QUEUED", 0, 0, 0, null, 123456789);
        when(jobs.submitFrozen(frozen)).thenReturn(ready);
        when(jobs.recoverExistingFrozen(frozen)).thenReturn(ready);
        mvc.perform(post("/internal/analytics/v1/jobs/frozen").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(frozen)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value("original"));
        mvc.perform(post("/internal/analytics/v1/jobs/frozen/recover-existing").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(frozen)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value("original"));
        verify(jobs).submitFrozen(frozen); verify(jobs).recoverExistingFrozen(frozen); verifyNoMoreInteractions(jobs);
    }

    private static QueryJobServiceTest fixture() {
        var fixture = new QueryJobServiceTest(); fixture.setup(); return fixture;
    }

    private static FrozenQueryScope scope(List<Long> parent, int shard) {
        List<Long> ids = parent.subList(shard * 500, Math.min(parent.size(), (shard + 1) * 500));
        String hash = FrozenQueryScope.memberHash(ids);
        return new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-parent",
                FrozenQueryScope.memberHash(parent), parent.size(), "e".repeat(64),
                FrozenQueryScope.shardIdFor("scope-parent", shard, hash), shard, (parent.size() + 499) / 500, hash, ids);
    }

    private static QueryRequest query(FrozenQueryScope scope) {
        return new QueryRequest("1", "alice", 7, "g1", scope.linkIds(), 300000L, 600000L,
                null, "REQUESTED", null, null, 500, "LINK_METRICS", null, null, scope);
    }

    private static LinkedHashMap<String, Object> selectedResponse(FrozenQueryScope scope) {
        return new LinkedHashMap<>(Map.of("schemaVersion", "selected-scope/v1", "allowed", true,
                "tenantId", "1", "subjectId", "alice", "authVersion", 7, "gid", "g1", "linkIds", scope.linkIds(),
                "ownershipVersion", SELECTED_VERSION, "memberHash", scope.shardMemberHash()));
    }

    private static void emptyLinkMetrics(QueryJobServiceTest fixture) {
        doAnswer(call -> {
            Consumer<Map<String, Object>> output = call.getArgument(4);
            output.accept(Map.of("group_row", 1, "pv", 0, "uv", 0, "uip", 0, "denied", 0));
            return null;
        }).when(fixture.ch).query(anyString(), anyString(), anyInt(), anyLong(), any());
    }

    private static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(QueryFailure.class, error -> assertThat(error.code).isEqualTo(code));
    }
}
