package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.ProgressSnapshot;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressView.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader.ReceiptProgress;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader.ScopeAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader.Snapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real frozen plans and JDBC execution snapshots; scripted receipt heads exercise only projection policy. */
@Timeout(20)
class CampaignResultReceptionProgressTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "analyst-1", 7);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final ArtifactAuthorizer NO_OUTPUTS = (caller, artifact) -> {
        throw new AssertionError("Unpublished reception must not fabricate an Artifact for authorization");
    };

    @Test
    void perReceiptCountsKeepUnknownZeroAndCompleteStagingDistinctWithoutCompletingTheStep() throws Exception {
        Fixture fixture = fixture(List.of("receiving", "unregistered", "zero", "staged"));
        List<ReceiptProgress> heads = List.of(
                receipt("receiving", "private-scope-first", NOW.plusSeconds(3600), 1, 2, 500, 501),
                receipt("receiving", "private-scope-second", NOW.plusSeconds(3600), 0, 3, 0, 1001),
                receipt("zero", "private-scope-zero", NOW.plusSeconds(3600), 1, 1, 0, 0),
                receipt("staged", "private-scope-staged", NOW.plusSeconds(3600), 2, 2, 501, 501));
        CountingReader reader = new CountingReader(fixture.steps(), heads);
        CampaignStepStore independentRead = mock(CampaignStepStore.class);
        AtomicInteger authorizations = new AtomicInteger();
        ScopeAuthorizer scope = (caller, frozenRun, scopeRef, periodsRef) -> {
            authorizations.incrementAndGet();
            assertEquals(OWNER, caller);
            assertEquals(fixture.token().definition(), frozenRun);
            assertEquals("private-periods-" + scopeRef, periodsRef);
            return heads.stream().anyMatch(head -> head.scopeRef().equals(scopeRef));
        };
        ProgressSnapshot before = fixture.steps().snapshot(OWNER, "run-1");

        CampaignProgressView view = new CampaignProgressService(independentRead, fixture.runs(), NO_OUTPUTS,
                reader, scope, CLOCK).read(OWNER, "run-1");

        assertEquals(1, reader.reads.get());
        verifyNoInteractions(independentRead);
        assertEquals(4, authorizations.get());
        assertEquals(List.of(new ResultReception(1, 2, 500L, 501L, null),
                new ResultReception(0, 3, 0L, 1001L, null)), step(view, "receiving").resultReception());
        assertTrue(step(view, "unregistered").resultReception().isEmpty(), "No registered receipt is not zero work");
        assertEquals(List.of(new ResultReception(1, 1, 0L, 0L, null)), step(view, "zero").resultReception());
        assertEquals(List.of(new ResultReception(2, 2, 501L, 501L, null)), step(view, "staged").resultReception());
        assertEquals(WorkState.WAITING, view.workState());
        assertEquals(DeliveryState.NOT_ASSESSED, view.deliveryState());
        for (StepProgress step : view.steps()) {
            assertEquals(StepStatus.WAITING, step.recordedStatus());
            assertEquals(WorkState.WAITING, step.workState());
            assertTrue(step.availableOutputs().isEmpty());
            assertTrue(step.unavailableOutputs().isEmpty());
        }
        assertThrows(UnsupportedOperationException.class, () -> step(view, "receiving").resultReception().clear());
        assertEquals(before, fixture.steps().snapshot(OWNER, "run-1"));
        assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class));
        assertPublicShapeAndNoPrivateData(view);
    }

    @Test
    void revokedExpiredAndExpiryDuringAuthorizationHideAllCountsAndPrivateBindings() throws Exception {
        Fixture fixture = fixture(List.of("waiting"));
        List<ReceiptProgress> heads = List.of(
                receipt("waiting", "private-denied", NOW.plusSeconds(3600), 1, 2, 500, 501),
                receipt("waiting", "private-security-exception", NOW.plusSeconds(3600), 2, 3, 1000, 1001),
                receipt("waiting", "private-expired", NOW.minusMillis(1), 1, 1, 0, 0),
                receipt("waiting", "private-delayed", NOW.plusSeconds(1), 2, 2, 501, 501));
        CountingReader reader = new CountingReader(fixture.steps(), heads);
        MutableClock clock = new MutableClock(NOW);
        ScopeAuthorizer scope = (caller, frozenRun, scopeRef, periodsRef) -> {
            assertEquals(fixture.token().definition(), frozenRun);
            assertEquals("private-periods-" + scopeRef, periodsRef);
            return switch (scopeRef) {
                case "private-denied" -> false;
                case "private-security-exception" -> throw new SecurityException("PRIVATE_AUTHORITY_DETAIL");
                case "private-delayed" -> {
                    clock.now = NOW.plusSeconds(2);
                    yield true;
                }
                default -> true;
            };
        };
        CampaignProgressView view = new CampaignProgressService(mock(CampaignStepStore.class), fixture.runs(), NO_OUTPUTS,
                reader, scope, clock).read(OWNER, "run-1");

        assertEquals(1, reader.reads.get());
        List<ResultReception> receptions = step(view, "waiting").resultReception();
        assertEquals(List.of("RESULT_ACCESS_DENIED", "RESULT_ACCESS_DENIED", "RESULT_EXPIRED", "RESULT_EXPIRED"),
                receptions.stream().map(ResultReception::reasonCode).toList());
        receptions.forEach(reception -> {
            assertNull(reception.receivedPages()); assertNull(reception.totalPages());
            assertNull(reception.receivedRows()); assertNull(reception.totalRows());
        });
        assertEquals(WorkState.WAITING, view.workState());
        assertEquals(DeliveryState.NOT_ASSESSED, view.deliveryState());
        assertPublicShapeAndNoPrivateData(view);
        assertFalse(JSON.writeValueAsString(view).contains("PRIVATE_AUTHORITY_DETAIL"));
    }

    @Test
    void legacyAndTerminalViewsRemainCompatibleWhileWrongCallerOrUninitializedReceiptAssociationsFailClosed() {
        Fixture fixture = fixture(List.of("waiting"));
        CampaignProgressView legacy = new CampaignProgressService(fixture.steps(), fixture.runs(), NO_OUTPUTS).read(OWNER, "run-1");
        assertTrue(step(legacy, "waiting").resultReception().isEmpty());
        StepProgress oldConstructor = new StepProgress("waiting", List.of("goal"), StepStatus.WAITING,
                WorkState.WAITING, "RESULT_PENDING", List.of(), List.of(), List.of());
        assertTrue(oldConstructor.resultReception().isEmpty());
        List<ResultReception> source = new ArrayList<>(List.of(new ResultReception(1, 1, 0L, 0L, null)));
        StepProgress copied = new StepProgress("waiting", List.of("goal"), StepStatus.WAITING,
                WorkState.WAITING, null, List.of(), List.of(), List.of(), source);
        source.clear();
        assertEquals(1, copied.resultReception().size());

        fixture.runs().cancel(fixture.token());
        var receipt = receipt("waiting", "private-terminal", NOW.plusSeconds(3600), 1, 1, 0, 0);
        CountingReader reader = new CountingReader(fixture.steps(), List.of(receipt));
        CampaignStepStore independentRead = mock(CampaignStepStore.class);
        AtomicInteger authorizations = new AtomicInteger();
        ScopeAuthorizer authorize = (caller, frozenRun, scopeRef, periodsRef) -> { authorizations.incrementAndGet(); return true; };
        CampaignProgressService service = new CampaignProgressService(independentRead, fixture.runs(), NO_OUTPUTS, reader, authorize, CLOCK);
        CampaignProgressView cancelled = service.read(OWNER, "run-1");
        assertEquals(1, reader.reads.get());
        assertEquals(RunStatus.CANCELLED, cancelled.runStatus());
        assertEquals(WorkState.CANCELLED, step(cancelled, "waiting").workState());
        assertEquals(StepStatus.WAITING, step(cancelled, "waiting").recordedStatus());
        assertEquals(List.of(new ResultReception(1, 1, 0L, 0L, null)), step(cancelled, "waiting").resultReception());
        assertEquals(DeliveryState.NOT_ASSESSED, cancelled.deliveryState());

        ProgressSnapshot execution = fixture.steps().snapshot(OWNER, "run-1");
        for (Caller foreign : List.of(new Caller("1002", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "other-analyst", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1))) {
            assertThrows(SecurityException.class, () -> service.read(foreign, "run-1"));
            CampaignResultProgressReader incorrectReader = (caller, runId) -> new Snapshot(execution, List.of(receipt));
            assertThrows(SecurityException.class, () -> new CampaignProgressService(independentRead, fixture.runs(), NO_OUTPUTS,
                    incorrectReader, authorize, CLOCK).read(foreign, "run-1"));
        }
        assertEquals(1, authorizations.get(), "Neither reader nor projection may authorize another principal's receipt");
        for (Snapshot invalid : List.of(
                new Snapshot(execution, List.of(receipt("other-step", "private-orphan", NOW.plusSeconds(3600), 0, 1, 0, 0))),
                new Snapshot(new ProgressSnapshot(execution.run(), List.of()), List.of(receipt)))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new CampaignProgressService(independentRead, fixture.runs(), NO_OUTPUTS,
                            (caller, runId) -> invalid, authorize, CLOCK).read(OWNER, "run-1"));
            assertEquals("PROGRESS_DEFINITION_MISMATCH", failure.getMessage());
        }
        assertEquals(1, authorizations.get(), "Association validation precedes scope authorization");
        verifyNoInteractions(independentRead);
    }

    private static ReceiptProgress receipt(String stepId, String scope, Instant expires, int receivedPages,
                                            int totalPages, long receivedRows, long totalRows) {
        return new ReceiptProgress(stepId, scope, "private-periods-" + scope, expires.toEpochMilli(),
                receivedPages, totalPages, receivedRows, totalRows);
    }

    private static StepProgress step(CampaignProgressView view, String id) {
        return view.steps().stream().filter(step -> step.stepId().equals(id)).findFirst().orElseThrow();
    }

    private static void assertPublicShapeAndNoPrivateData(CampaignProgressView view) throws Exception {
        JsonNode json = JSON.valueToTree(view);
        for (JsonNode step : json.path("steps")) for (JsonNode receipt : step.path("resultReception")) {
            Set<String> fields = new HashSet<>();
            receipt.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("receivedPages", "totalPages", "receivedRows", "totalRows", "reasonCode"), fields);
        }
        String encoded = JSON.writeValueAsString(view);
        for (String forbidden : List.of("private-scope", "private-periods", "private-denied", "private-delayed",
                "private-security", "private-expired", "job-private", "PRIVATE_REQUEST_MARKER",
                "scopeRef", "periodsRef", "jobId", "requestId", "artifactId", "checksum", "expiresAtMillis"))
            assertFalse(encoded.contains(forbidden), forbidden);
    }

    private static Fixture fixture(List<String> stepIds) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:result_reception_progress_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        CampaignRunStore runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
        CampaignStepStore steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
        List<PlanSpec.Step> planSteps = stepIds.stream().map(id -> new PlanSpec.Step(id, List.of("goal"),
                PlanSpec.ExecutionMode.FIXED, new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", "1"), null,
                List.of(), Map.of(), Map.of(), "evidence/v1")).toList();
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Receive campaign statistics", true, "Deliver verified evidence")), planSteps);
        FrozenCampaignRun frozen = FrozenCampaignRun.freeze(plan,
                new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of()),
                new PlanningAssessment("plan-1", 1, "catalog/v1", List.of(), List.of(), List.of()));
        RunToken token = runs.createRun(frozen.definition(OWNER, "session-1"));
        steps.initialize(token, planSteps.stream().map(step -> new StepSpec(step.stepId(), "{}", List.of(), Set.of(), Set.of())).toList());
        for (String id : stepIds) {
            var step = steps.beginStep(token, id);
            try {
                runs.prepareAction(token, new ActionSpec("action-" + id, id, "TOOL", "query", "1", "{}"));
                runs.prepareChild(token, new ChildSpec("child-" + id, "action-" + id, ChildMode.ASYNC,
                        "request-" + id, new WireRequest("POST", "/statistics/jobs", "{\"private\":\"PRIVATE_REQUEST_MARKER\"}")));
                var child = runs.beginDispatch(token, "child-" + id);
                try { runs.recordWaiting(child, "job-private-" + id); }
                finally { runs.callbackExited(child); }
                steps.settle(step, StepStatus.WAITING, Map.of(), null, NO_OUTPUTS);
            } finally { steps.callbackExited(step); }
        }
        return new Fixture(jdbc, runs, steps, token);
    }

    private record Fixture(JdbcTemplate jdbc, CampaignRunStore runs, CampaignStepStore steps, RunToken token) {}

    private static final class CountingReader implements CampaignResultProgressReader {
        private final CampaignStepStore source;
        private final List<ReceiptProgress> receipts;
        private final AtomicInteger reads = new AtomicInteger();
        CountingReader(CampaignStepStore source, List<ReceiptProgress> receipts) { this.source = source; this.receipts = receipts; }
        @Override public Snapshot read(Caller caller, String runId) {
            reads.incrementAndGet();
            return new Snapshot(source.snapshot(caller, runId), receipts);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Fixture uses UTC");
            return this;
        }
        @Override public Instant instant() { return now; }
    }
}
