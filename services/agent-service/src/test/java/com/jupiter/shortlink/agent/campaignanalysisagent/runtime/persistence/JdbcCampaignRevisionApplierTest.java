package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode.ASYNC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ActionSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.WireRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.ReplanCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol.Status;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver.Target;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignRevisionApplierTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);
    private static final PlanSpec.ExecutorRef EXECUTOR =
            new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics_query_job", "1");
    private static final String CONTRACT = "statistics-job-pages-v1";
    private static final String SCOPE = "scope-frozen";
    private static final String PERIODS = "periods-frozen";
    private static final String STEP = "source";
    private static final String CHILD = "source-child";
    private static final String ACTION = "source-action";
    private static final String JOB = "job-original";
    private static final Target TARGET = new Target("artifact-pages", SCOPE, PERIODS);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void commitsReceiptFrozenRevisionAndEveryAdoptedConsumerTogether() throws Exception {
        Fixture fixture = new Fixture(true);
        ReplanCoordinator.ApplyRequest request = fixture.request(CONTRACT);

        ReplanCoordinator.AppliedRevision applied = fixture.applier.apply(request);

        assertThat(applied.run().definition().revision()).isEqualTo(2);
        assertThat(applied.receipt().decision()).isEqualTo("ACCEPTED");
        assertThat(fixture.runs.loadRun(OWNER, "run-1").orElseThrow().status()).isEqualTo(RunStatus.ACTIVE);
        assertThat(FrozenCampaignRun.read(applied.run().definition()).inputs())
                .isEqualTo(FrozenCampaignRun.read(fixture.base.definition()).inputs());
        assertThat(fixture.count("campaign_run_ledger")).isEqualTo(2);
        assertThat(fixture.count("campaign_replan_receipt")).isEqualTo(1);
        assertThat(fixture.count("campaign_statistics_consumer")).isEqualTo(2);
        String adopted = CampaignStatisticsConsumerStore.consumerId(applied.run().definition(), STEP, fixture.bindingId());
        assertThat(fixture.consumers.resolve(applied.run(), adopted, (run, binding, expected) -> true)
                .consumer().active()).isTrue();
    }

    @Test
    void adoptionFailureRollsBackReceiptRevisionAndConsumer() throws Exception {
        Fixture fixture = new Fixture(false);

        assertThatThrownBy(() -> fixture.applier.apply(fixture.request(CONTRACT)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CONSUMER_NOT_AUTHORIZED");

        assertThat(fixture.count("campaign_run_ledger")).isEqualTo(1);
        assertThat(fixture.count("campaign_replan_receipt")).isZero();
        assertThat(fixture.count("campaign_statistics_consumer")).isEqualTo(1);
        assertThat(fixture.runs.loadRun(OWNER, "run-1").orElseThrow().token()).isEqualTo(fixture.base);
    }

    @Test
    void exactReplayReturnsCommittedRevisionWithoutDuplicatingRows() throws Exception {
        Fixture fixture = new Fixture(true);
        ReplanCoordinator.ApplyRequest request = fixture.request(CONTRACT);
        ReplanCoordinator.AppliedRevision first = fixture.applier.apply(request);

        ReplanCoordinator.AppliedRevision replay = fixture.applier.apply(request);

        assertThat(replay).isEqualTo(first);
        assertThat(fixture.count("campaign_run_ledger")).isEqualTo(2);
        assertThat(fixture.count("campaign_replan_receipt")).isEqualTo(1);
        assertThat(fixture.count("campaign_statistics_consumer")).isEqualTo(2);
    }

    @Test
    void activeBaseCallbackBlocksRevisionBeforeReceipt() throws Exception {
        Fixture fixture = new Fixture(true);
        DispatchPermit callback = fixture.runs.beginReconciliation(fixture.base, CHILD);
        try {
            assertThatThrownBy(() -> fixture.applier.apply(fixture.request(CONTRACT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CALLBACK_STILL_ACTIVE");
            assertThat(fixture.count("campaign_run_ledger")).isEqualTo(1);
            assertThat(fixture.count("campaign_replan_receipt")).isZero();
        } finally {
            fixture.runs.callbackExited(callback);
        }
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final JdbcCampaignRunStore runs;
        final JdbcCampaignStatisticsConsumerStore consumers;
        final JdbcReplanReceiptStore receipts;
        final JdbcCampaignRevisionApplier applier;
        final PlanSpec basePlan;
        final PlanningAssessment baseAssessment;
        final RunToken base;
        final WireRequest wire;

        Fixture(boolean authorizeAdoption) throws Exception {
            var source = new DriverManagerDataSource("jdbc:h2:mem:replan_applier_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_19__campaign_statistics_consumers.sql"),
                    new ClassPathResource("sql/migration/V20260920_21__campaign_replan_receipt.sql"))
                    .execute(source);
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
            consumers = new JdbcCampaignStatisticsConsumerStore(jdbc, transactions, CLOCK, runs);
            receipts = new JdbcReplanReceiptStore(jdbc, transactions, CLOCK);
            applier = new JdbcCampaignRevisionApplier(jdbc, transactions, runs, consumers, receipts,
                    (run, binding, expected) -> authorizeAdoption);

            basePlan = plan(1, CONTRACT, "base");
            baseAssessment = assessment(1);
            FrozenInputSet inputs = new FrozenInputSet("inputs-1", "run-1", Map.of(
                    "scope-input", new CapabilityCatalog.Port(
                            new CapabilityCatalog.TypeRef("ScopeRef", 1, CapabilityCatalog.Cardinality.ONE), true),
                    "period-input", new CapabilityCatalog.Port(
                            new CapabilityCatalog.TypeRef("PeriodsRef", 1, CapabilityCatalog.Cardinality.ONE), true)),
                    Map.of("scope-input", SCOPE, "period-input", PERIODS));
            base = runs.createRun(FrozenCampaignRun.freeze(basePlan, inputs, baseAssessment)
                    .definition(OWNER, "session-1"));

            PlanSpec.Step step = basePlan.steps().get(0);
            runs.prepareAction(base, new ActionSpec(ACTION, STEP, EXECUTOR.kind().name(),
                    EXECUTOR.name(), EXECUTOR.version(), JSON.writeValueAsString(step)));
            String membershipHash = FrozenQueryScope.memberHash(List.of(1L));
            FrozenQueryScope scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", SCOPE,
                    membershipHash, 1, "a".repeat(64), FrozenQueryScope.shardIdFor(SCOPE, 0, membershipHash),
                    0, 1, membershipHash, List.of(1L));
            wire = new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH,
                    JSON.writeValueAsString(Map.of("requestId", "request-original", "gid", "group-a",
                            "startDate", "2026-09-01", "endDate", "2026-09-02",
                            "queryKind", "ACCESS_RECORDS", "scope", scope.asMap())));
            runs.prepareChild(base, new ChildSpec(CHILD, ACTION, ASYNC, "request-original", wire));
            DispatchPermit submission = runs.beginDispatch(base, CHILD);
            try {
                runs.recordWaiting(submission, JOB);
            } finally {
                runs.callbackExited(submission);
            }
            DispatchPermit status = runs.beginReconciliation(base, CHILD);
            try {
                consumers.pin(status, new Status(JOB, "RUNNING", 0, 0,
                        NOW.plusSeconds(3600).toEpochMilli(), null), TARGET);
                runs.recordWaiting(status, JOB);
            } finally {
                runs.callbackExited(status);
            }
        }

        ReplanCoordinator.ApplyRequest request(String outputContract) throws Exception {
            PlanSpec candidate = plan(2, outputContract, "candidate");
            PlanningAssessment candidateAssessment = assessment(2);
            ReplanRequest replan = ReplanRequest.create(basePlan, baseAssessment, Set.of("evidence-old"),
                    List.of(new ReplanRequest.Evidence("evidence-new", "artifact-new",
                            "statistics-pages-v1", "b".repeat(64))), List.of(),
                    "Use newly available evidence for the next revision");
            String hash = ReplanRequest.planHash(candidate);
            return new ReplanCoordinator.ApplyRequest(base, candidate, candidateAssessment,
                    new ReplanCoordinator.PreparedGraph(candidate.planId(), candidate.revision(), hash),
                    JSON.writeValueAsString(replan), receipts);
        }

        String bindingId() {
            return CampaignStatisticsConsumerStore.bindingId(OWNER, JOB);
        }

        int count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }
    }

    private static PlanSpec plan(int revision, String outputContract, String variant) {
        PlanSpec.Step step = new PlanSpec.Step(STEP, List.of("goal-1"), PlanSpec.ExecutionMode.FIXED,
                EXECUTOR, null, List.of(),
                Map.of("scope", PlanBinding.input("scope-input"),
                        "periods", PlanBinding.input("period-input")),
                Map.of("variant", variant), outputContract);
        return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", revision, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal-1", "Read the original statistics job", true,
                        "All retained pages remain available")), List.of(step));
    }

    private static PlanningAssessment assessment(int revision) {
        var requirement = new PlanningAssessment.Requirement("requirement-1", "goal-1",
                PlanningAssessment.RequirementKind.DELIVERY, true, "criterion-1", "1", Map.of());
        return new PlanningAssessment("plan-1", revision, "catalog-1", List.of(requirement),
                List.of(new PlanningAssessment.CoverageBinding("requirement-1",
                        List.of(new PlanningAssessment.EvidenceOutput(STEP, "pages")))), List.of());
    }
}
