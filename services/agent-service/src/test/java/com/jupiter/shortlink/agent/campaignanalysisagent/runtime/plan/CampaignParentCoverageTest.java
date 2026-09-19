package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignParentCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    private static final long EXPIRY = CLOCK.millis() + 3_600_000;
    private static final Caller OWNER = new Caller("1", "analyst", 7);
    private static final ArtifactAuthorizer ALLOW = (caller, artifact) -> true;
    private static final String VERSION = "a".repeat(64);
    private static final List<Period> PERIODS = List.of(
            new Period("baseline", "2026-09-01", "2026-09-01", "Asia/Shanghai"),
            new Period("target", "2026-09-02", "2026-09-02", "Asia/Shanghai"));

    @Test
    void durableTwoPeriodCoverageStreamsAllMembersAndRejectsMissingDuplicateForeignParentOrRewrittenPages() throws Exception {
        Fixture f = new Fixture();
        String scopeArtifact = f.publishScope();
        Slot[][] slots = new Slot[2][2];
        for (int shard = 0; shard < 2; shard++) {
            FrozenQueryScope scope = f.scopes.shard(OWNER, scopeArtifact, shard, ALLOW);
            for (int period = 0; period < 2; period++)
                slots[period][shard] = f.publish("valid-" + period + "-" + shard, scope, period, "VALID");
        }
        // Rebuild readers: coverage must come from durable bindings and pages, not fixture memory.
        CampaignRunStore reopenedRuns = new JdbcCampaignRunStore(f.jdbc, f.transactions, CLOCK);
        var coverage = new CampaignParentCoverage(reopenedRuns,
                new JdbcCampaignScopeStore(f.jdbc, f.transactions, CLOCK, reopenedRuns),
                new JdbcCampaignStatisticsResultStore(f.jdbc, f.transactions, CLOCK));
        SlotResolver resolver = (period, shard) -> slots[period][shard];
        List<Gap> gaps = new ArrayList<>();
        AtomicInteger rows = new AtomicInteger(), zeros = new AtomicInteger();
        var compared = new CampaignObservedLinkComparison(coverage).compare(OWNER, scopeArtifact, PERIODS,
                resolver, ALLOW, CampaignLinkComparability.Metric.PV, gaps::add, result -> {
                    rows.incrementAndGet();
                    assertEquals(CampaignLinkComparability.Explanation.OBSERVED_ONLY, result.explanation());
                    assertTrue(result.reasonCodes().contains("BASELINE_COLLECTION_COMPLETENESS_UNVERIFIED"));
                    assertTrue(result.reasonCodes().contains("TARGET_COLLECTION_COMPLETENESS_UNVERIFIED"));
                    assertFalse(result.reasonCodes().contains("METRIC_APPROXIMATE"), "PV uses the actual EXACT metric definition");
                    if (result.linkId() == 1) {
                        zeros.incrementAndGet();
                        assertEquals(0, result.baseline()); assertEquals(0, result.target());
                        assertEquals(BigInteger.ZERO, result.delta()); assertNull(result.rate());
                    } else assertEquals(BigInteger.valueOf(-1), result.delta());
                });
        assertEquals(501, rows.get()); assertEquals(1, zeros.get());
        assertEquals(501, compared.pairedMembers()); assertEquals(500, compared.negativeObservedDifferences());
        Summary complete = compared.coverage();
        assertTrue(complete.coverageComplete()); assertEquals(4, complete.expectedSlots()); assertEquals(4, complete.completedSlots());
        assertEquals(1002, complete.expectedMembers()); assertEquals(1002, complete.completedMembers());
        assertTrue(gaps.isEmpty()); assertEquals(0, complete.gapCount());
        assertEquals(BigInteger.valueOf(1000), complete.periods().get(0).observedSubtotalPv());
        assertEquals(BigInteger.valueOf(500), complete.periods().get(1).observedSubtotalPv());
        assertTrue(complete.periods().stream().allMatch(PeriodCoverage::complete));
        JsonNode summaryJson = JSON.valueToTree(complete);
        assertNull(summaryJson.findValue("uv")); assertNull(summaryJson.findValue("uip"));
        assertNull(summaryJson.findValue("selectionComplete"));

        RuntimeException sinkFailure = new IllegalStateException("durable sink unavailable");
        assertSame(sinkFailure, assertThrows(IllegalStateException.class, () ->
                coverage.check(OWNER, scopeArtifact, PERIODS, resolver, ALLOW, gaps::add,
                        verified -> { throw sinkFailure; })));
        SecurityException authorizationFailure = new SecurityException("authorization revoked");
        assertSame(authorizationFailure, assertThrows(SecurityException.class, () ->
                coverage.check(OWNER, scopeArtifact, PERIODS, resolver,
                        (caller, artifact) -> { throw authorizationFailure; }, gaps::add)));

        FrozenQueryScope first = f.scopes.shard(OWNER, scopeArtifact, 0, ALLOW);
        for (String variant : List.of("MISSING", "DUPLICATE", "PARENT", "CHAIN")) {
            FrozenQueryScope queryScope = "PARENT".equals(variant)
                    ? new FrozenQueryScope(first.schemaVersion(), first.scopeKind(), first.parentScopeRef(),
                            "c".repeat(64), first.parentMemberCount(), first.enumerationVersion(), first.shardId(),
                            first.shardIndex(), first.shardCount(), first.shardMemberHash(), first.linkIds())
                    : first;
            Slot invalid = f.publish("bad-" + variant.toLowerCase(java.util.Locale.ROOT), queryScope, 0, variant);
            if ("CHAIN".equals(variant)) {
                // Even updating the page's own checksum cannot rewrite the independently published chain.
                String original = f.results.readPage(OWNER, invalid.artifactId(), 0, ALLOW);
                JsonNode rewritten = JSON.readTree(original);
                ((ObjectNode) rewritten.path("items").get(1)).put("pv", 3);
                String body = JSON.writeValueAsString(rewritten);
                f.jdbc.update("UPDATE campaign_statistics_page SET payload_json=?,checksum=?,byte_count=? WHERE child_id=?",
                        body, CampaignRunStore.sha256(body), body.getBytes(StandardCharsets.UTF_8).length, invalid.childId());
            }
            gaps.clear();
            Summary partial = coverage.check(OWNER, scopeArtifact, PERIODS,
                    (period, shard) -> period == 0 && shard == 0 ? invalid : resolver.resolve(period, shard), ALLOW, gaps::add);
            assertFalse(partial.coverageComplete(), variant); assertEquals(3, partial.completedSlots(), variant);
            assertEquals(502, partial.completedMembers(), variant); assertEquals(1, gaps.size(), variant);
            assertFalse(partial.periods().get(0).complete()); assertTrue(partial.periods().get(1).complete());
            assertEquals(BigInteger.valueOf(2), partial.periods().get(0).observedSubtotalPv());
            assertEquals(BigInteger.valueOf(500), partial.periods().get(1).observedSubtotalPv());
            assertEquals(switch (variant) {
                case "MISSING" -> "COVERAGE_ROWS_INCOMPLETE";
                case "DUPLICATE" -> "COVERAGE_MEMBER_MISMATCH";
                case "PARENT" -> "COVERAGE_SCOPE_PROOF_MISMATCH";
                default -> "COVERAGE_PAGE_CHAIN_MISMATCH";
            }, gaps.get(0).code());
        }
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final CampaignRunStore runs;
        final CampaignScopeStore scopes;
        final CampaignStatisticsResultStore results;
        final RunToken token;

        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:parent_coverage_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_5__campaign_scope_collection.sql")).execute(source);
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
            scopes = new JdbcCampaignScopeStore(jdbc, transactions, CLOCK, runs);
            results = new JdbcCampaignStatisticsResultStore(jdbc, transactions, CLOCK);
            token = runs.createRun(new RunDefinition(OWNER, "session", "run", "plan", 1, "{}"));
        }

        String publishScope() throws Exception {
            var action = new ActionSpec("enumeration", "enumeration", "TOOL", "group_members", "1", "{}");
            var definition = new CampaignScopeStore.Definition("collection", action, "group-a", Instant.ofEpochMilli(EXPIRY));
            var collection = scopes.prepare(token, definition);
            for (int pageIndex = 0; pageIndex < 2; pageIndex++) {
                Long after = pageIndex == 0 ? null : 500L;
                var request = new GroupMembersPage.Request("group-a", after, pageIndex == 0 ? null : VERSION);
                String childId = "authority-" + pageIndex;
                var child = new ChildSpec(childId, action.actionId(), ChildMode.SYNC, "request-" + childId,
                        new WireRequest("POST", AgentAuthorityClient.GROUP_MEMBERS_PATH, JSON.writeValueAsString(request.asMap())));
                runs.prepareChild(token, child);
                var permit = runs.beginDispatch(token, childId);
                try {
                    List<Long> members = LongStream.rangeClosed(pageIndex == 0 ? 1 : 501, pageIndex == 0 ? 500 : 501).boxed().toList();
                    collection = scopes.commitPage(permit, definition.collectionId(), new GroupMembersPage(GroupMembersPage.SCHEMA,
                            OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), definition.gid(), VERSION,
                            after, members, pageIndex == 0 ? 500L : null));
                } finally { runs.callbackExited(permit); }
            }
            assertEquals(CampaignScopeStore.State.PUBLISHED, collection.state());
            return collection.artifactId();
        }

        Slot publish(String key, FrozenQueryScope scope, int periodIndex, String variant) throws Exception {
            Period period = PERIODS.get(periodIndex);
            String actionId = "action-" + key, childId = "child-" + key, artifactId = "artifact-" + key, jobId = "job-" + key;
            String requestId = "request-" + key;
            runs.prepareAction(token, new ActionSpec(actionId, "step-" + key, "TOOL", "link_metrics", "1", "{}"));
            String requestJson = JSON.writeValueAsString(Map.of("requestId", requestId, "gid", "group-a",
                    "startDate", period.startDate(), "endDate", period.endDate(), "queryKind", "LINK_METRICS", "scope", scope.asMap()));
            var child = new ChildSpec(childId, actionId, ChildMode.ASYNC, requestId,
                    new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, requestJson));
            runs.prepareChild(token, child);
            var submit = runs.beginDispatch(token, childId);
            try { runs.recordWaiting(submit, jobId); } finally { runs.callbackExited(submit); }
            var permit = runs.beginReconciliation(token, childId);
            try {
                List<Map<String, Object>> rows = new ArrayList<>();
                List<Long> ids = new ArrayList<>(scope.linkIds());
                if ("MISSING".equals(variant)) ids.remove(ids.size() - 1);
                if ("DUPLICATE".equals(variant)) ids.set(ids.size() - 1, ids.get(ids.size() - 2));
                long pvSum = 0;
                for (long id : ids) {
                    long pv = id == 1 ? 0 : periodIndex == 0 ? 2 : 1;
                    pvSum += pv;
                    var row = new LinkedHashMap<>(counts(pv, pv == 0 ? 0 : 1, period));
                    row.put("linkId", id); rows.add(row);
                }
                Map<String, Object> metrics = Map.of("requested", counts(pvSum, 1, period));
                Map<String, Object> meta = metadata(jobId, scope, period, rows.size());
                var protocol = new StatisticsJobResultProtocol(runs.child(token, childId).orElseThrow());
                var page = protocol.page(new StatisticsJobResultProtocol.Status(jobId, "SUCCEEDED", rows.size(), 1, EXPIRY, null),
                        Map.of("items", rows, "metrics", metrics, "meta", meta), 0);
                results.initialize(permit, new CampaignStatisticsResultStore.ReceiptSpec(jobId, child.wire().hash(), artifactId,
                        scope.parentScopeRef(), period.periodsRef(), rows.size(), 1, EXPIRY));
                results.append(permit, page); results.publish(permit);
            } finally { runs.callbackExited(permit); }
            return new Slot(token, childId, artifactId);
        }
    }

    private static Map<String, Object> counts(long pv, long independentVisitors, Period period) {
        return Map.of("pv", pv, "uv", independentVisitors, "uip", independentVisitors, "denied", 0,
                "window", "requested", "startInclusive", period.startInclusive(), "endExclusive", period.endExclusive());
    }

    private static Map<String, Object> metadata(String jobId, FrozenQueryScope scope, Period period, int rows) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("snapshotId", jobId); meta.put("queryKind", "LINK_METRICS"); meta.put("gid", "group-a");
        meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1");
        String selectionHash = CampaignRunStore.sha256(jobId);
        meta.put("sourceCut", Map.of("manifestSelectionHash", selectionHash));
        meta.put("manifestVersion", Map.of("selectionHash", selectionHash));
        meta.put("snapshotCreatedAt", CLOCK.millis()); meta.put("snapshotExpiresAt", EXPIRY);
        meta.put("requestedStart", period.startInclusive()); meta.put("requestedEnd", period.endExclusive());
        meta.put("effectiveEnd", period.endExclusive()); meta.put("businessTimezone", period.timeZone());
        meta.put("linkIds", scope.linkIds()); meta.put("scopeProof", scope.proof("b".repeat(64)));
        meta.put("groupScopeComplete", false); meta.put("totalRows", rows);
        meta.put("pageIndex", 0); meta.put("nextPageIndex", null);
        meta.put("aggregationLevel", "LINK_WINDOW"); meta.put("availability", "AVAILABLE");
        meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("provisional", false);
        meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of());
        meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"),
                "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
        return meta;
    }
}
