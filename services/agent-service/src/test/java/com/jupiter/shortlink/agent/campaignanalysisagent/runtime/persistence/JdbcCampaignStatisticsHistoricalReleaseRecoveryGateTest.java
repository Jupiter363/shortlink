package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPermit;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsHistoricalReleaseRecoveryReader.RetiredReleaseCandidate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignStatisticsHistoricalReleaseRecoveryGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Caller OWNER = new Caller("1001", "analyst-a", 7);
    private static final Caller FOREIGN = new Caller("2002", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRY = NOW.plusSeconds(3600).toEpochMilli();
    private static final long EXPIRED = NOW.minusSeconds(1).toEpochMilli();
    private static final CampaignRunStore.ArtifactAuthorizer ALLOW = (caller, artifact) -> true;

    @Test
    void revalidatesExactCandidateAndRepeatsWithoutWriting() throws Exception {
        Fixture fixture = fixture();
        Seed seed = seed(fixture, OWNER, "valid", true);
        RetiredReleaseCandidate candidate = fixture.reader().pendingRetired(OWNER, NOW.toEpochMilli(), 1)
                .stream().findFirst().orElseThrow();
        List<Map<String, Object>> before = snapshot(fixture.jdbc());

        Optional<CampaignStatisticsHistoricalReleaseRecoveryGate.Permit> first =
                fixture.gate().revalidate(OWNER, candidate, NOW.toEpochMilli());
        assertTrue(first.isPresent());
        assertEquals(new CampaignStatisticsHistoricalReleaseRecoveryGate.Permit(
                "release-" + CampaignRunStore.sha256(JSON.writeValueAsString(
                        List.of("statistics-release/v1", OWNER.tenantId(), OWNER.subject(), "job-valid"))),
                seed.physicalBindingId(), "run-valid", 1, "child-valid", "job-valid", 1, 1, EXPIRY, true),
                first.orElseThrow());
        assertEquals(first, fixture.gate().admit(OWNER, candidate, NOW.toEpochMilli()));
        assertEquals(before, snapshot(fixture.jdbc()), "Gate revalidation must not write rows");
    }

    @Test
    void rejectsExpiredConsumersCallbacksAndNormalStateChanges() throws Exception {
        Fixture expired = fixture();
        Seed expiredSeed = seed(expired, OWNER, "expired", true);
        RetiredReleaseCandidate expiredCandidate = expired.reader().pendingRetired(OWNER, NOW.toEpochMilli(), 1)
                .get(0);
        expired.jdbc().update("UPDATE campaign_statistics_release SET expires_at=? WHERE binding_id=?",
                EXPIRED, expiredSeed.bindingId());
        assertTrue(expired.gate().revalidate(OWNER, expiredCandidate, NOW.toEpochMilli()).isEmpty());

        Fixture activeConsumer = fixture();
        Seed consumer = seed(activeConsumer, OWNER, "consumer", true);
        RetiredReleaseCandidate consumerCandidate = activeConsumer.reader().pendingRetired(OWNER, NOW.toEpochMilli(), 1)
                .get(0);
        activeConsumer.jdbc().update("INSERT INTO campaign_statistics_consumer "
                        + "(consumer_id,binding_id,run_id,revision,step_id,expectation_json,expectation_hash,active,created_at,retired_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,NULL)",
                "consumer-consumer", consumer.physicalBindingId(), consumer.replacement().definition().runId(),
                consumer.replacement().definition().revision(), "step-consumer", "{}",
                CampaignRunStore.sha256("{}"), true, NOW.toEpochMilli());
        assertTrue(activeConsumer.gate().revalidate(OWNER, consumerCandidate, NOW.toEpochMilli()).isEmpty());

        Fixture callback = fixture();
        Seed callbackSeed = seed(callback, OWNER, "callback", true);
        RetiredReleaseCandidate callbackCandidate = callback.reader().pendingRetired(OWNER, NOW.toEpochMilli(), 1)
                .get(0);
        callback.jdbc().update("UPDATE campaign_child_ledger SET callback_active=TRUE WHERE run_id=? AND revision=? AND child_id=?",
                callbackSeed.run().definition().runId(), callbackSeed.run().definition().revision(), callbackSeed.childId());
        assertTrue(callback.gate().revalidate(OWNER, callbackCandidate, NOW.toEpochMilli()).isEmpty());
    }

    @Test
    void rejectsStaleIdentityAndFailsClosedOnLinkedCorruption() throws Exception {
        Fixture stale = fixture();
        seed(stale, OWNER, "stale", true);
        RetiredReleaseCandidate candidate = stale.reader().pendingRetired(OWNER, NOW.toEpochMilli(), 1).get(0);
        RetiredReleaseCandidate wrongVersion = new RetiredReleaseCandidate(candidate.runId(), candidate.revision(),
                candidate.childId(), candidate.jobId(), candidate.bindingVersion() + 1, candidate.expiresAtMillis(),
                candidate.sourceRunVersion(), 0, true);
        assertTrue(stale.gate().revalidate(OWNER, wrongVersion, NOW.toEpochMilli()).isEmpty());
        RetiredReleaseCandidate wrongRunVersion = new RetiredReleaseCandidate(candidate.runId(), candidate.revision(),
                candidate.childId(), candidate.jobId(), candidate.bindingVersion(), candidate.expiresAtMillis(),
                candidate.sourceRunVersion() + 1, 0, true);
        assertTrue(stale.gate().revalidate(OWNER, wrongRunVersion, NOW.toEpochMilli()).isEmpty());
        assertTrue(stale.gate().revalidate(FOREIGN, candidate, NOW.toEpochMilli()).isEmpty());

        Fixture releaseCorrupted = fixture();
        Seed release = seed(releaseCorrupted, OWNER, "bad-release", true);
        RetiredReleaseCandidate releaseCandidate = releaseCorrupted.reader().pendingRetired(OWNER, NOW.toEpochMilli(), 1).get(0);
        releaseCorrupted.jdbc().update("UPDATE campaign_statistics_release SET request_hash=? WHERE binding_id=?",
                "0".repeat(64), release.bindingId());
        IllegalStateException releaseFailure = assertThrows(IllegalStateException.class,
                () -> releaseCorrupted.gate().revalidate(OWNER, releaseCandidate, NOW.toEpochMilli()));
        assertEquals("HISTORICAL_RELEASE_GATE_CORRUPTED", releaseFailure.getMessage());

        Fixture bindingCorrupted = fixture();
        Seed physical = seed(bindingCorrupted, OWNER, "bad-binding", true);
        RetiredReleaseCandidate physicalCandidate = bindingCorrupted.reader().pendingRetired(OWNER, NOW.toEpochMilli(), 1).get(0);
        bindingCorrupted.jdbc().update("UPDATE campaign_statistics_job_binding SET binding_version=2 WHERE binding_id=?",
                physical.physicalBindingId());
        IllegalStateException bindingFailure = assertThrows(IllegalStateException.class,
                () -> bindingCorrupted.gate().revalidate(OWNER, physicalCandidate, NOW.toEpochMilli()));
        assertEquals("HISTORICAL_RELEASE_GATE_CORRUPTED", bindingFailure.getMessage());
    }

    private static Seed seed(Fixture fixture, Caller owner, String suffix, boolean supersede) throws Exception {
        JdbcCampaignRunStore runs = fixture.runs();
        RunToken run = runs.createRun(definition(owner, suffix, 1));
        String childId = "child-" + suffix;
        String actionId = "action-" + suffix;
        String artifactId = "artifact-" + suffix;
        String jobId = "job-" + suffix;
        String requestId = "request-" + suffix;
        String scopeRef = "scope-" + suffix;
        String periodsRef = "periods-" + suffix;
        FrozenQueryScope scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", scopeRef,
                FrozenQueryScope.memberHash(List.of(1L)), 1, "a".repeat(64),
                FrozenQueryScope.shardIdFor(scopeRef, 0, FrozenQueryScope.memberHash(List.of(1L))), 0, 1,
                FrozenQueryScope.memberHash(List.of(1L)), List.of(1L));
        String body = JSON.writeValueAsString(Map.of("requestId", requestId, "gid", "group-1",
                "startDate", "2026-09-01", "endDate", "2026-09-02", "queryKind", "ACCESS_RECORDS",
                "scope", scope.asMap()));
        CampaignRunStore.WireRequest wire = new CampaignRunStore.WireRequest("POST",
                StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, body);
        runs.prepareAction(run, new CampaignRunStore.ActionSpec(actionId, "step-" + suffix,
                "TOOL", "statistics", "1", "{}"));
        runs.prepareChild(run, new CampaignRunStore.ChildSpec(childId, actionId, ChildMode.ASYNC, requestId, wire));
        DispatchPermit submitted = runs.beginDispatch(run, childId);
        try {
            runs.recordWaiting(submitted, jobId);
        } finally {
            runs.callbackExited(submitted);
        }

        DispatchPermit received = runs.beginReconciliation(run, childId);
        String hash = wire.hash();
        try {
            CampaignStatisticsResultStore results = fixture.results();
            results.initialize(received, new CampaignStatisticsResultStore.ReceiptSpec(jobId, hash, artifactId,
                    scopeRef, periodsRef, 1, 1, EXPIRY));
            StatisticsJobResultProtocol protocol = new StatisticsJobResultProtocol(runs.child(run, childId).orElseThrow());
            StatisticsJobResultProtocol.Status status = new StatisticsJobResultProtocol.Status(jobId, "SUCCEEDED",
                    1, 1, EXPIRY, null);
            results.append(received, protocol.page(status, page(scope, periodsRef, jobId), 0));
            results.publish(received);
        } finally {
            runs.callbackExited(received);
        }
        CampaignStatisticsReleaseStore.Intent intent = fixture.releases().prepare(run, childId, ALLOW);
        String physicalBindingId = fixture.jdbc().queryForObject(
                "SELECT binding_id FROM campaign_statistics_job_binding WHERE producer_run_id=? AND producer_revision=? AND producer_child_id=?",
                String.class, run.definition().runId(), run.definition().revision(), childId);
        RunToken replacement = supersede ? runs.revise(run, 2, definition(owner, suffix, 2).definitionJson()) : run;
        return new Seed(run, replacement, childId, intent.bindingId(), physicalBindingId, hash);
    }

    private static RunDefinition definition(Caller owner, String suffix, int revision) {
        String runId = "run-" + suffix;
        String planId = "plan-" + suffix;
        String inputSetRef = "inputs-" + suffix;
        String stepId = "step-" + suffix;
        PlanSpec.ExecutorRef executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics", "1");
        PlanSpec.Step step = new PlanSpec.Step(stepId, List.of("goal-" + suffix), PlanSpec.ExecutionMode.FIXED,
                executor, null, List.of(), Map.of(), Map.of(), "statistics-job-pages/v1");
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, planId, revision, runId, inputSetRef,
                List.of(new PlanSpec.Goal("goal-" + suffix, "Read statistics pages", true, "pages")), List.of(step));
        FrozenInputSet inputs = new FrozenInputSet(inputSetRef, runId, Map.of(), Map.of());
        PlanningAssessment assessment = new PlanningAssessment(planId, revision, "fixture-catalog/1",
                List.of(new PlanningAssessment.Requirement("requirement-" + suffix, "goal-" + suffix,
                        PlanningAssessment.RequirementKind.DELIVERY, true, "pages", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("requirement-" + suffix,
                        List.of(new PlanningAssessment.EvidenceOutput(stepId, "pages")))), List.of());
        return FrozenCampaignRun.freeze(plan, inputs, assessment).definition(owner, "session-" + suffix);
    }

    private static Map<String, Object> page(FrozenQueryScope scope, String periodsRef, String jobId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryKind", "ACCESS_RECORDS");
        meta.put("gid", "group-1");
        meta.put("linkIds", scope.linkIds());
        meta.put("snapshotId", jobId);
        meta.put("recoveryEpoch", "epoch-1");
        meta.put("metricVersion", "click-v1");
        meta.put("sourceCut", Map.of("manifestSelectionHash", "cut-1"));
        meta.put("manifestVersion", Map.of("selectionHash", "cut-1"));
        meta.put("requestedStart", day("2026-09-01"));
        meta.put("requestedEnd", day("2026-09-03"));
        meta.put("effectiveEnd", day("2026-09-03"));
        meta.put("businessTimezone", "Asia/Shanghai");
        meta.put("snapshotExpiresAt", EXPIRY);
        meta.put("groupScopeComplete", false);
        meta.put("scopeProof", scope.proof("b".repeat(64)));
        meta.put("pageIndex", 0);
        meta.put("nextPageIndex", null);
        meta.put("totalRows", 1);
        meta.put("completeness", "PARTIAL");
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        return Map.of("items", List.of(Map.of("eventId", "event-1", "linkId", 1L)),
                "metrics", Map.of("pv", 1), "meta", meta);
    }

    private static long day(String value) {
        return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    private static List<Map<String, Object>> snapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT binding_id,release_state,binding_version,confirmed_at "
                + "FROM campaign_statistics_release ORDER BY binding_id");
    }

    private static Fixture fixture() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:historical_release_"
                + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                new ClassPathResource("sql/migration/V20260920_2__campaign_statistics_release.sql"),
                new ClassPathResource("sql/migration/V20260920_19__campaign_statistics_consumers.sql"))
                .execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        return new Fixture(jdbc, transactions);
    }

    private record Seed(RunToken run, RunToken replacement, String childId, String bindingId,
                        String physicalBindingId, String requestHash) { }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions) {
        JdbcCampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        CampaignStatisticsResultStore results() { return new JdbcCampaignStatisticsResultStore(jdbc, transactions, CLOCK); }
        CampaignStatisticsReleaseStore releases() { return new JdbcCampaignStatisticsReleaseStore(jdbc, transactions, CLOCK); }
        JdbcCampaignStatisticsHistoricalReleaseRecoveryReader reader() {
            return new JdbcCampaignStatisticsHistoricalReleaseRecoveryReader(jdbc, transactions);
        }
        JdbcCampaignStatisticsHistoricalReleaseRecoveryGate gate() {
            return new JdbcCampaignStatisticsHistoricalReleaseRecoveryGate(jdbc, transactions);
        }
    }
}
