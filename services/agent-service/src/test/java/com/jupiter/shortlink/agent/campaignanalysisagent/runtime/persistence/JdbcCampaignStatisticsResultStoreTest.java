package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class JdbcCampaignStatisticsResultStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRES_AT = NOW.plusSeconds(3600).toEpochMilli();
    private static final String SNAPSHOT = """
            {"snapshotId":"snapshot-fixed","quality":{"status":"PARTIAL","dimensionQuality":{"country":"UNKNOWN"}}}
            """;
    private static final String METRICS = "{\"pv\":501,\"uv\":400}";
    private static final ArtifactAuthorizer ALLOW = (current, artifact) -> true;

    @Test
    void rebuiltStoreContinuesThePersistedCursorAndPublishesOnlyASmallManifestWithOriginalQuality() {
        Fixture fixture = fixture();
        OpenRun open = openRun(fixture);
        CampaignStatisticsResultStore first = fixture.store();
        Page firstPage = page(0, 1, 500, 0);
        try {
            first.initialize(open.permit(), spec(open, 501, 2));
            Receipt partial = first.append(open.permit(), firstPage);
            assertEquals(1, partial.nextPageIndex());
            assertEquals(1, partial.storedPages());
            assertEquals(500, partial.storedRows());
            assertFalse(partial.complete());
            fixture.runs().markUnresolved(open.permit());
        } finally {
            fixture.runs().callbackExited(open.permit());
        }
        CampaignStatisticsResultStore reopened = fixture.store();
        Receipt resumed = reopened.receipt(open.run(), "source").orElseThrow();
        assertEquals(1, resumed.nextPageIndex());
        assertEquals(spec(open, 501, 2), resumed.spec());
        assertEquals(UnresolvedReason.JOB_RESULT_UNKNOWN, fixture.runs().child(open.run(), "source").orElseThrow().reason());
        DispatchPermit next = fixture.runs().beginReconciliation(open.run(), "source");
        assertNotEquals(open.permit().attemptId(), next.attemptId());
        assertTrue(next.attemptVersion() > open.permit().attemptVersion());
        String reorderedSnapshot = """
                {"quality":{"dimensionQuality":{"country":"UNKNOWN"},"status":"PARTIAL"},"snapshotId":"snapshot-fixed"}
                """;
        Page lastPage = new Page(1, null, 1, reorderedSnapshot, "{\"uv\":400,\"pv\":501}", payload(1, 500));
        try {
            assertEquals(resumed, reopened.initialize(next, spec(open, 501, 2)));
            Receipt complete = reopened.append(next, lastPage);
            assertTrue(complete.complete());
            assertEquals(2, complete.nextPageIndex(), "The terminal cursor is the received-page sentinel");
            assertEquals(2, complete.storedPages());
            assertEquals(501, complete.storedRows());
            assertEquals(json(SNAPSHOT), json(complete.snapshotJson()));
            assertEquals(json(METRICS), json(complete.metricsJson()));
            fixture.dataSource().rejectUnboundedPagePayloadRead = true;
            ArtifactRef published;
            try {
                published = reopened.publish(next);
            } finally {
                fixture.dataSource().rejectUnboundedPagePayloadRead = false;
            }
            Artifact manifest = fixture.runs().readArtifact(OWNER, published.artifactId(), ALLOW);
            assertEquals(ARTIFACT_TYPE, published.type());
            assertEquals(SCHEMA_VERSION, published.schemaVersion());
            assertEquals("scope-frozen", published.scopeRef());
            assertEquals("periods-frozen", published.periodsRef());
            assertTrue(manifest.payloadJson().getBytes(StandardCharsets.UTF_8).length < 4096);
            assertFalse(manifest.payloadJson().contains("row-marker-"), "The manifest must not aggregate page rows");
            assertTrue(manifest.payloadJson().contains("PARTIAL"));
            assertTrue(manifest.payloadJson().contains("UNKNOWN"));
            assertEquals(SNAPSHOT, manifest.metadata().qualityJson());
            assertEquals(json(SNAPSHOT), json(manifest.payloadJson()).path("meta"));
            assertEquals(open.child().wire().hash(), json(manifest.metadata().provenanceJson()).path("requestHash").asText());
            assertEquals("PARTIAL", json(reopened.receipt(open.run(), "source").orElseThrow().snapshotJson())
                    .path("quality").path("status").asText());
            assertTrue(reopened.receipt(open.run(), "source").orElseThrow().published());
            assertEquals(ChildState.READY, fixture.runs().child(open.run(), "source").orElseThrow().state());
            assertEquals(firstPage.payloadJson(), reopened.readPage(OWNER, published.artifactId(), 0, ALLOW));
            assertEquals(lastPage.payloadJson(), reopened.readPage(OWNER, published.artifactId(), 1, ALLOW));
            assertEquals(2, fixture.count("campaign_statistics_page"));
        } finally {
            fixture.runs().callbackExited(next);
        }
    }

    @Test
    void pageReplayIsIdempotentButChangedPayloadBoundaryMetadataAndReceiptBindingsAreRejected() {
        Fixture fixture = fixture();
        OpenRun open = openRun(fixture);
        CampaignStatisticsResultStore store = fixture.store();
        ReceiptSpec original = spec(open, 501, 2);
        Page firstPage = page(0, 1, 500, 0);
        try {
            store.initialize(open.permit(), original);
            Receipt saved = store.append(open.permit(), firstPage);
            var before = fixture.databaseState();
            assertEquals(saved, store.append(open.permit(), firstPage));
            assertEquals(before, fixture.databaseState(), "An exact replay must not advance the cursor, counters or hash");
            assertThrows(IllegalStateException.class, () -> store.append(open.permit(), page(0, 1, 500, 10000)));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(), page(0, null, 500, 0)));
            ReceiptSpec changed = new ReceiptSpec(original.jobId(), original.requestHash(), original.artifactId(),
                    "different-scope", original.periodsRef(), original.totalRows(), original.pageCount(), original.expiresAtMillis());
            assertThrows(IllegalStateException.class, () -> store.initialize(open.permit(), changed));
            assertThrows(IllegalStateException.class, () -> store.append(open.permit(), new Page(1, null, 1,
                    SNAPSHOT.replace("snapshot-fixed", "different-snapshot"), METRICS, payload(1, 500))));
            assertThrows(IllegalStateException.class, () -> store.append(open.permit(), new Page(1, null, 1,
                    SNAPSHOT, "{\"pv\":502,\"uv\":400}", payload(1, 500))));
            assertEquals(saved, fixture.store().receipt(open.run(), "source").orElseThrow());
            assertEquals(before, fixture.databaseState());
            assertEquals(1, fixture.count("campaign_statistics_page"));
        } finally {
            fixture.runs().callbackExited(open.permit());
        }
    }

    @Test
    void pageOrderCursorSizeAndTotalsAreEnforcedIncludingTheSingleEmptyPageForZeroRows() {
        Fixture fixture = fixture();
        OpenRun open = openRun(fixture);
        CampaignStatisticsResultStore store = fixture.store();
        try {
            assertThrows(IllegalArgumentException.class, () -> store.initialize(open.permit(), spec(open, -1, 0)));
            assertThrows(IllegalArgumentException.class, () -> store.initialize(open.permit(), spec(open, 501, 1)));
            Receipt empty = store.initialize(open.permit(), spec(open, 501, 2));
            assertThrows(IllegalStateException.class, () -> store.append(open.permit(), page(1, null, 1, 500)));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(), page(-1, 0, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(), page(2, null, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(), page(0, 2, 500, 0)));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(), page(0, 1, 499, 0)));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(),
                    new Page(0, 1, 500, SNAPSHOT, METRICS, payload(499, 0))));
            assertEquals(empty, store.receipt(open.run(), "source").orElseThrow());
            assertEquals(0, fixture.count("campaign_statistics_page"));
            assertThrows(IllegalStateException.class, () -> store.publish(open.permit()));
            store.append(open.permit(), page(0, 1, 500, 0));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(), page(1, null, 2, 500)));
            assertThrows(IllegalArgumentException.class, () -> store.append(open.permit(), page(1, 2, 1, 500)));
            assertFalse(store.receipt(open.run(), "source").orElseThrow().complete());
        } finally {
            fixture.runs().callbackExited(open.permit());
        }

        Fixture zeroFixture = fixture();
        OpenRun zeroRun = openRun(zeroFixture);
        CampaignStatisticsResultStore zeroStore = zeroFixture.store();
        try {
            Receipt zero = zeroStore.initialize(zeroRun.permit(), spec(zeroRun, 0, 0));
            assertEquals(1, zero.requiredPages());
            assertFalse(zero.complete());
            assertThrows(IllegalArgumentException.class, () -> zeroStore.append(zeroRun.permit(), page(0, null, 1, 0)));
            Receipt done = zeroStore.append(zeroRun.permit(), page(0, null, 0, 0));
            assertTrue(done.complete());
            assertEquals(1, done.nextPageIndex());
            assertEquals(1, done.storedPages());
            assertEquals(0, done.storedRows());
            ArtifactRef published = zeroStore.publish(zeroRun.permit());
            assertEquals("{\"items\":[]}", zeroStore.readPage(OWNER, published.artifactId(), 0, ALLOW));
        } finally {
            zeroFixture.runs().callbackExited(zeroRun.permit());
        }
    }

    @Test
    void readsRequirePublishedCurrentAuthorizationExpiryAndChecksumWhileWritesRespectFencingAndByteLimits() {
        Fixture fixture = fixture();
        OpenRun open = openRun(fixture);
        CampaignStatisticsResultStore store = fixture.store();
        Page one = page(0, null, 1, 0);
        try {
            store.initialize(open.permit(), spec(open, 1, 1));
            store.append(open.permit(), one);
            assertThrows(IllegalStateException.class, () -> store.readPage(OWNER, "artifact-result", 0,
                    (current, metadata) -> { fail("Staging must not reach artifact authorization"); return true; }));
            store.publish(open.permit());
        } finally {
            fixture.runs().callbackExited(open.permit());
        }
        for (Caller foreign : List.of(new Caller("tenant-b", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "analyst-b", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1))) {
            assertThrows(SecurityException.class, () -> store.readPage(foreign, "artifact-result", 0,
                    (current, metadata) -> { fail("Foreign identity must fail before authorization"); return true; }));
        }
        AtomicInteger authorizationChecks = new AtomicInteger();
        assertThrows(SecurityException.class, () -> store.readPage(OWNER, "artifact-result", 0, (current, metadata) -> {
            authorizationChecks.incrementAndGet();
            return false;
        }));
        assertEquals(1, authorizationChecks.get());
        assertEquals(one.payloadJson(), store.readPage(OWNER, "artifact-result", 0, ALLOW));
        CampaignStatisticsResultStore atExpiry = fixture.store(Clock.fixed(Instant.ofEpochMilli(EXPIRES_AT), ZoneOffset.UTC));
        assertThrows(SecurityException.class, () -> atExpiry.readPage(OWNER, "artifact-result", 0,
                (current, metadata) -> { fail("Expired evidence must not reach authorization"); return true; }));
        String tampered = payload(1, 9);
        assertEquals(one.payloadJson().getBytes(StandardCharsets.UTF_8).length, tampered.getBytes(StandardCharsets.UTF_8).length);
        fixture.jdbc().update("UPDATE campaign_statistics_page SET payload_json=? WHERE page_index=0", tampered);
        IllegalStateException corrupted = assertThrows(IllegalStateException.class,
                () -> store.readPage(OWNER, "artifact-result", 0, ALLOW));
        assertEquals("STATISTICS_PAGE_CORRUPTED", corrupted.getMessage());

        for (boolean revise : List.of(false, true)) {
            Fixture fenced = fixture();
            OpenRun pending = openRun(fenced);
            CampaignStatisticsResultStore results = fenced.store();
            try {
                results.initialize(pending.permit(), spec(pending, 1, 1));
                results.append(pending.permit(), one);
                if (!revise) {
                    CampaignStatisticsResultStore expired = fenced.store(Clock.fixed(Instant.ofEpochMilli(EXPIRES_AT), ZoneOffset.UTC));
                    var beforeExpiryRejection = fenced.databaseState();
                    assertThrows(SecurityException.class, () -> expired.append(pending.permit(), one));
                    assertThrows(SecurityException.class, () -> expired.publish(pending.permit()));
                    assertEquals(beforeExpiryRejection, fenced.databaseState());
                }
                if (revise) fenced.runs().revise(pending.run(), 2, "{\"revision\":2}");
                else fenced.runs().cancel(pending.run());
                var before = fenced.databaseState();
                assertThrows(IllegalStateException.class, () -> results.append(pending.permit(), one));
                assertThrows(IllegalStateException.class, () -> results.publish(pending.permit()));
                assertEquals(before, fenced.databaseState());
                assertEquals(0, fenced.count("campaign_artifact"));
            } finally {
                fenced.runs().callbackExited(pending.permit());
            }
        }

        Fixture bounded = fixture();
        OpenRun pending = openRun(bounded);
        String multibyte = "{\"items\":[{\"label\":\"" + "省".repeat(24) + "\"}]}";
        int encodedBytes = multibyte.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(multibyte.length() < encodedBytes - 1);
        Page multibytePage = new Page(0, null, 1, "{}", "{}", multibyte);
        try {
            CampaignStatisticsResultStore tooSmall = bounded.store(encodedBytes - 1);
            tooSmall.initialize(pending.permit(), spec(pending, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> tooSmall.append(pending.permit(), multibytePage));
            assertEquals(0, bounded.count("campaign_statistics_page"));
            assertEquals(0, tooSmall.receipt(pending.run(), "source").orElseThrow().storedPages());
            assertTrue(bounded.store(encodedBytes).append(pending.permit(), multibytePage).complete());
        } finally {
            bounded.runs().callbackExited(pending.permit());
        }
    }

    @Test
    void publicationFailureAtArtifactPayloadOrPublishedReceiptRollsBackEveryTableAndLeavesTheChildUnready() {
        for (FailurePoint point : FailurePoint.values()) {
            Fixture fixture = fixture();
            OpenRun open = openRun(fixture);
            CampaignStatisticsResultStore store = fixture.store();
            try {
                store.initialize(open.permit(), spec(open, 1, 1));
                store.append(open.permit(), page(0, null, 1, 0));
                var before = fixture.databaseState();
                fixture.dataSource().failurePoint = point;
                try {
                    assertThrows(DataAccessException.class, () -> store.publish(open.permit()));
                    assertEquals(1, fixture.dataSource().injectedFailures.get());
                } finally {
                    fixture.dataSource().failurePoint = null;
                }
                assertEquals(before, fixture.databaseState(), "Manifest, payload, READY child and receipt publication are one transaction");
                assertEquals(ChildState.DISPATCHING, fixture.runs().child(open.run(), "source").orElseThrow().state());
                assertTrue(fixture.runs().child(open.run(), "source").orElseThrow().callbackActive());
                assertFalse(store.receipt(open.run(), "source").orElseThrow().published());
                assertEquals(0, fixture.count("campaign_artifact"));
                assertEquals(0, fixture.count("campaign_artifact_payload"));
                assertThrows(IllegalStateException.class, () -> store.readPage(OWNER, "artifact-result", 0, ALLOW));
                ArtifactRef published = store.publish(open.permit());
                assertEquals("artifact-result", published.artifactId());
                assertEquals(ChildState.READY, fixture.runs().child(open.run(), "source").orElseThrow().state());
                assertTrue(store.receipt(open.run(), "source").orElseThrow().published());
            } finally {
                fixture.runs().callbackExited(open.permit());
            }
        }
    }

    @Test
    void frozenProvenanceRequiresTheOriginalWireAndMatchingParentProofWhilePreservingPartialQuality() throws Exception {
        List<Long> members = List.of(1L, 2L);
        String hash = FrozenQueryScope.memberHash(members);
        FrozenQueryScope scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-frozen", hash,
                members.size(), "a".repeat(64), FrozenQueryScope.shardIdFor("scope-frozen", 0, hash), 0, 1, hash, members);
        String request = JSON.writeValueAsString(Map.of("requestId", "request-stats", "gid", "group-frozen",
                "startDate", "2026-09-01", "endDate", "2026-09-02", "queryKind", "METRICS", "scope", scope.asMap()));
        for (String variant : List.of("FROZEN", "BAD_PROOF", "BAD_SCOPE", "LEGACY")) {
            Fixture fixture = fixture();
            OpenRun open = "LEGACY".equals(variant) ? openRun(fixture) : openRun(fixture,
                    new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, request));
            CampaignStatisticsResultStore store = fixture.store();
            Map<String, Object> proof = new LinkedHashMap<>(scope.proof("b".repeat(64)));
            if ("BAD_PROOF".equals(variant)) proof.put("parentComplete", true);
            Map<String, Object> snapshot = new LinkedHashMap<>(Map.of("snapshotId", "job-fixed",
                    "quality", Map.of("status", "PARTIAL", "dimensionQuality", Map.of("country", "UNKNOWN")),
                    "groupScopeComplete", false, "linkIds", members, "scopeProof", proof));
            String snapshotJson = JSON.writeValueAsString(snapshot);
            ReceiptSpec receipt = new ReceiptSpec("job-fixed", open.child().wire().hash(), "artifact-result",
                    "BAD_SCOPE".equals(variant) ? "other-parent" : scope.parentScopeRef(), "periods-frozen", 1, 1, EXPIRES_AT);
            try {
                store.initialize(open.permit(), receipt);
                store.append(open.permit(), new Page(0, null, 1, snapshotJson, METRICS, payload(1, 0)));
                if (variant.startsWith("BAD_")) {
                    var before = fixture.databaseState();
                    assertThrows(IllegalArgumentException.class, () -> store.publish(open.permit()), variant);
                    assertEquals(before, fixture.databaseState(), "Invalid proof must publish no artifact or READY child");
                    assertEquals(0, fixture.count("campaign_artifact"));
                    assertFalse(store.receipt(open.run(), "source").orElseThrow().published());
                    continue;
                }
                ArtifactRef reference = store.publish(open.permit());
                Artifact artifact = fixture.runs().readArtifact(OWNER, reference.artifactId(), ALLOW);
                JsonNode provenance = json(artifact.metadata().provenanceJson());
                assertEquals(snapshotJson, artifact.metadata().qualityJson());
                assertEquals(json(snapshotJson), json(artifact.payloadJson()).path("meta"));
                assertEquals("PARTIAL", json(artifact.metadata().qualityJson()).path("quality").path("status").asText());
                assertEquals("UNKNOWN", json(artifact.metadata().qualityJson()).path("quality").path("dimensionQuality")
                        .path("country").asText());
                assertEquals(open.child().wire().hash(), provenance.path("requestHash").asText());
                if ("LEGACY".equals(variant)) {
                    assertEquals("CURRENT_QUERY", provenance.path("scopeMode").asText());
                    assertFalse(provenance.has("scopeProof"), "A returned proof alone cannot upgrade an original query");
                } else {
                    assertEquals("FROZEN_SET", provenance.path("scopeMode").asText());
                    assertFalse(provenance.path("scopeProof").path("parentComplete").booleanValue());
                    assertEquals(JSON.readTree(JSON.writeValueAsString(scope.proof("b".repeat(64)))),
                            provenance.path("scopeProof"));
                    assertEquals(scope.parentScopeRef(), reference.scopeRef());
                }
            } finally {
                fixture.runs().callbackExited(open.permit());
            }
        }
    }

    private static OpenRun openRun(Fixture fixture) {
        return openRun(fixture, new WireRequest("POST", "/analytics/query", "{\"scopeRef\":\"scope-frozen\"}"));
    }

    private static OpenRun openRun(Fixture fixture, WireRequest wire) {
        CampaignRunStore runs = fixture.runs();
        RunToken run = runs.createRun(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1, "{\"scopeRef\":\"scope-frozen\"}"));
        runs.prepareAction(run, new ActionSpec("action-stats", "step-stats", "TOOL", "campaign_stats", "stats/1", "{}"));
        ChildSpec child = new ChildSpec("source", "action-stats", ChildMode.ASYNC, "request-stats", wire);
        runs.prepareChild(run, child);
        DispatchPermit submit = runs.beginDispatch(run, child.childId());
        try {
            runs.recordWaiting(submit, "job-fixed");
        } finally {
            runs.callbackExited(submit);
        }
        return new OpenRun(run, child, runs.beginReconciliation(run, child.childId()));
    }

    private static ReceiptSpec spec(OpenRun open, long totalRows, int pageCount) {
        return new ReceiptSpec("job-fixed", open.child().wire().hash(), "artifact-result", "scope-frozen",
                "periods-frozen", totalRows, pageCount, EXPIRES_AT);
    }

    private static Page page(int pageIndex, Integer nextPageIndex, int rowCount, int firstRow) {
        return new Page(pageIndex, nextPageIndex, rowCount, SNAPSHOT, METRICS, payload(rowCount, firstRow));
    }

    private static String payload(int rows, int firstRow) {
        StringJoiner items = new StringJoiner(",", "{\"items\":[", "]}");
        for (int row = 0; row < rows; row++) {
            items.add("{\"dimension\":\"row-marker-" + (firstRow + row) + "\",\"pv\":1}");
        }
        return items.toString();
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (java.io.IOException invalid) {
            throw new AssertionError("Fixture or persisted JSON is invalid", invalid);
        }
    }

    private static Fixture fixture() {
        DriverManagerDataSource delegate = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_statistics_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        delegate.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql")).execute(delegate);
        GuardedDataSource guarded = new GuardedDataSource(delegate);
        return new Fixture(new JdbcTemplate(guarded),
                new TransactionTemplate(new DataSourceTransactionManager(guarded)), guarded);
    }

    private record OpenRun(RunToken run, ChildSpec child, DispatchPermit permit) {}
    private enum FailurePoint { ARTIFACT_PAYLOAD_INSERT, RECEIPT_PUBLISHED_UPDATE }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, GuardedDataSource dataSource) {
        private CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        private CampaignStatisticsResultStore store() { return store(CLOCK); }
        private CampaignStatisticsResultStore store(Clock clock) {
            return new JdbcCampaignStatisticsResultStore(jdbc, transactions, clock);
        }
        private CampaignStatisticsResultStore store(int pageBytes) {
            return new JdbcCampaignStatisticsResultStore(jdbc, transactions, CLOCK, Limits.defaults(), pageBytes);
        }
        private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

        private Map<String, List<Map<String, String>>> databaseState() {
            Map<String, List<Map<String, String>>> state = new LinkedHashMap<>();
            Map<String, String> orders = Map.of(
                    "campaign_run_ledger", "run_id,revision", "campaign_action_ledger", "run_id,revision,action_id",
                    "campaign_child_ledger", "run_id,revision,child_id", "campaign_artifact", "artifact_id",
                    "campaign_artifact_payload", "artifact_id", "campaign_statistics_receipt", "run_id,revision,child_id",
                    "campaign_statistics_page", "run_id,revision,child_id,page_index");
            orders.forEach((table, order) -> state.put(table, jdbc.query("SELECT * FROM " + table + " ORDER BY " + order,
                    (rs, row) -> {
                        Map<String, String> values = new LinkedHashMap<>();
                        for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                            values.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
                        }
                        return values;
                    })));
            return state;
        }
    }

    private static final class GuardedDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger injectedFailures = new AtomicInteger();
        private volatile FailurePoint failurePoint;
        private volatile boolean rejectUnboundedPagePayloadRead;

        private GuardedDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override
        public Connection getConnection() throws SQLException { return guard(delegate.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return guard(delegate.getConnection(username, password));
        }

        private Connection guard(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args.length > 0
                                && args[0] instanceof String sql) {
                            String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
                            boolean fault = failurePoint == FailurePoint.ARTIFACT_PAYLOAD_INSERT
                                    && normalized.startsWith("insert into campaign_artifact_payload")
                                    || failurePoint == FailurePoint.RECEIPT_PUBLISHED_UPDATE
                                    && normalized.startsWith("update campaign_statistics_receipt") && normalized.contains("published");
                            if (fault) {
                                injectedFailures.incrementAndGet();
                                throw new SQLException("Injected statistics publication failure", "HY000");
                            }
                            if (rejectUnboundedPagePayloadRead && normalized.startsWith("select")
                                    && normalized.contains("campaign_statistics_page")
                                    && (normalized.contains("payload_json") || normalized.startsWith("select *"))
                                    && !normalized.replace(" ", "").contains("page_index=?")) {
                                throw new AssertionError("Manifest publication loaded unbounded page payloads");
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
