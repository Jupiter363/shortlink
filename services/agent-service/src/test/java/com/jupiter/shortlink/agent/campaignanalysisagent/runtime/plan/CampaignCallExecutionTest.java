package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A real committed MODEL/CALL boundary; no native runner, model service, or Skill completion claim. */
@Timeout(20)
class CampaignCallExecutionTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String IMPLEMENTATION = "a".repeat(64), MODEL_CONFIGURATION = "b".repeat(64);
    private static final String INPUT = "{\"baseline\":13,\"target\":3}";
    private static final String SCOPE = "scope-frozen", PERIODS = "periods-frozen";
    private static final Set<String> OUTPUTS = Set.of("selectedEntities", "selectionEvidence");
    private static final PlanSpec.ExecutorRef TOOL = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "inspect_statistics", "1");
    private static final PlanSpec.Step STEP = new PlanSpec.Step("explore", List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
            new PlanSpec.ExplorationPolicy("statistics-explore", "1", List.of(TOOL), SCOPE, PERIODS,
                    List.of(new PlanSpec.CriterionUse("supported-evidence", Map.of())), "one-call"),
            List.of(), Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods")), Map.of(), "evidence/v1");

    @Test
    void realCallPublishesSyncAndAtomicLocalEvidenceReusesReceiptsAndFencesForeignOrRevokedWork() throws Exception {
        var f = new Fixture();
        var io = new AtomicInteger(); var computations = new AtomicInteger(); var submissions = new AtomicInteger();
        CallPermit call = f.beginCall();
        try (var context = new CampaignCallExecution(call, f.runs, f.steps, f.calls, f.allowed::get)) {
            ChildSpec sourceSpec = wire("source", call.actionId(), ChildMode.SYNC);
            ChildRecord source = context.child(sourceSpec, boundary -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(sourceSpec.wire(), boundary.request()); boundary.beforeIo(); io.incrementAndGet();
                assertEquals(1, f.activeChildren()); assertTrue(f.calls.mayExecute(call));
                return CampaignStepExecution.ChildResult.ready(new ArtifactDraft("source-artifact", "LINK_METRICS", "link-metrics/v1",
                        SCOPE, PERIODS, "{\"collectionQuality\":\"UNKNOWN\"}", "{}", EXPIRY, INPUT));
            });
            assertEquals(ChildState.READY, source.state()); assertFalse(source.callbackActive());
            assertEquals(source, context.child(sourceSpec, boundary -> { fail("READY SYNC evidence cannot repeat I/O"); return null; }));
            assertEquals(1, io.get());
            ArtifactMetadata input = f.runs.readArtifact(OWNER, "source-artifact", f.authorizer).metadata();
            assertEquals(call.actionId(), input.actionId()); assertEquals(sourceSpec.childId(), input.childId());

            var localInvocation = new InvocationSpec("derive-delta", "1", IMPLEMENTATION, "{\"metric\":\"PV\"}", Map.of("source", input),
                    Map.of("selectedEntities", new OutputBinding("selected-artifact", "SELECTED_ENTITIES", "selected/v1", SCOPE, PERIODS),
                            "selectionEvidence", new OutputBinding("evidence-artifact", "DECLINE_EVIDENCE", "decline/v1", SCOPE, PERIODS)), EXPIRY);
            Approval approval = registry().approve(localInvocation);
            ChildSpec localSpec = new ChildSpec("local-child", call.actionId(), ChildMode.LOCAL, "local-request", null, localInvocation);
            Map<String, ArtifactDraft> drafts = drafts(approval, -10);
            approval.validateOutputs(drafts);
            // Reject the actual second payload INSERT: neither output nor READY may survive.
            String secondId = new ArrayList<>(Map.copyOf(drafts).values()).get(1).artifactId();
            f.jdbc.execute("ALTER TABLE campaign_artifact_payload ADD CONSTRAINT reject_call_second_payload CHECK (artifact_id <> '" + secondId + "')");
            CampaignStepExecution.LocalCall calculate = boundary -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                computations.incrementAndGet(); boundary.requireCurrent();
                Artifact evidence = boundary.readInput("source");
                assertEquals(input, evidence.metadata()); assertEquals(INPUT, evidence.payloadJson());
                var payload = new ObjectMapper().readTree(evidence.payloadJson());
                long delta = payload.path("target").asLong() - payload.path("baseline").asLong();
                return drafts(approval, delta);
            };
            var rejected = assertThrows(IllegalStateException.class, () -> context.local(localSpec, approval, f.authorizer, calculate));
            assertEquals("LEDGER_IDENTITY_CONFLICT", rejected.getMessage());
            assertEquals(0, f.localOutputCount());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE artifact_id IN ('selected-artifact','evidence-artifact')", Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact_payload WHERE artifact_id IN ('selected-artifact','evidence-artifact')", Integer.class));
            ChildRecord unknown = f.runs.child(f.token, localSpec.childId()).orElseThrow();
            assertEquals(ChildState.UNRESOLVED, unknown.state()); assertEquals(UnresolvedReason.LOCAL_RESULT_UNKNOWN, unknown.reason());
            assertFalse(unknown.callbackActive()); assertEquals(0, f.activeChildren()); assertTrue(f.calls.mayExecute(call));
            assertEquals(INPUT, f.runs.readArtifact(OWNER, "source-artifact", f.authorizer).payloadJson());

            f.jdbc.execute("ALTER TABLE campaign_artifact_payload DROP CONSTRAINT reject_call_second_payload");
            Map<String, ArtifactRef> published = context.local(localSpec, approval, f.authorizer, calculate);
            assertEquals(OUTPUTS, published.keySet()); assertEquals(2, f.localOutputCount()); assertEquals(2, computations.get());
            ChildRecord localReady = f.runs.child(f.token, localSpec.childId()).orElseThrow();
            assertEquals(ChildState.READY, localReady.state()); assertEquals(2, localReady.attemptVersion());
            assertNull(localReady.artifactId()); assertNull(localReady.jobId()); assertFalse(localReady.callbackActive());
            assertEquals(localSpec, localReady.spec());
            assertEquals(published, context.local(localSpec, approval, f.authorizer,
                    boundary -> { fail("The complete READY output set must not compute twice"); return null; }));
            assertEquals(2, computations.get()); assertEquals(localReady, f.runs.child(f.token, localSpec.childId()).orElseThrow());
            for (var output : published.entrySet()) {
                Artifact artifact = f.runs.readArtifact(OWNER, output.getValue().artifactId(), f.authorizer);
                assertEquals(call.actionId(), artifact.metadata().actionId()); assertEquals(localSpec.childId(), artifact.metadata().childId());
                assertEquals(TOOL.version(), artifact.metadata().executorVersion()); assertEquals(EXPIRY, output.getValue().expiresAt());
                assertEquals(CampaignRunStore.sha256(drafts.get(output.getKey()).payloadJson()), output.getValue().payloadHash());
                assertEquals(drafts.get(output.getKey()).payloadJson(), artifact.payloadJson());
            }

            ChildSpec asyncSpec = wire("async", call.actionId(), ChildMode.ASYNC);
            ChildRecord pending = context.child(asyncSpec, boundary -> {
                boundary.beforeIo(); submissions.incrementAndGet();
                return CampaignStepExecution.ChildResult.waiting("job-original");
            });
            assertEquals(ChildState.WAITING, pending.state()); assertEquals("job-original", pending.jobId());
            assertFalse(pending.callbackActive()); assertEquals(1, pending.attemptVersion());
            assertEquals(pending, context.child(asyncSpec, boundary -> { fail("WAITING reuses the original job"); return null; }));
            assertEquals(1, submissions.get());

            // A child from a genuinely different action cannot borrow this live CALL's permit.
            f.runs.prepareAction(f.token, new ActionSpec("foreign-action", STEP.stepId(), TOOL.kind().name(), TOOL.name(), TOOL.version(), "{}"));
            ChildSpec foreign = wire("foreign", "foreign-action", ChildMode.SYNC);
            assertThrows(IllegalArgumentException.class, () -> context.child(foreign,
                    boundary -> { io.incrementAndGet(); fail("Foreign action must fail before I/O"); return null; }));
            assertTrue(f.runs.child(f.token, foreign.childId()).isEmpty());
            ChildSpec foreignLocal = new ChildSpec("foreign-local", "foreign-action", ChildMode.LOCAL, "foreign-local-request", null, localInvocation);
            assertThrows(IllegalArgumentException.class, () -> context.local(foreignLocal, approval, f.authorizer,
                    boundary -> { computations.incrementAndGet(); fail("Foreign action must fail before compute"); return null; }));
            assertTrue(f.runs.child(f.token, foreignLocal.childId()).isEmpty());

            f.allowed.set(false);
            ChildSpec revoked = wire("revoked", call.actionId(), ChildMode.SYNC);
            assertThrows(SecurityException.class, () -> context.child(revoked,
                    boundary -> { io.incrementAndGet(); fail("Current authority is required before I/O"); return null; }));
            assertThrows(SecurityException.class, () -> context.local(localSpec, approval, f.authorizer,
                    boundary -> { computations.incrementAndGet(); fail("Current authority is required even for reuse"); return null; }));
            assertTrue(f.runs.child(f.token, revoked.childId()).isEmpty());
            assertEquals(1, io.get()); assertEquals(2, computations.get()); assertEquals(1, submissions.get());
            f.allowed.set(true);
            assertEquals(source, f.runs.child(f.token, sourceSpec.childId()).orElseThrow());
            assertEquals(localReady, f.runs.child(f.token, localSpec.childId()).orElseThrow());
            assertEquals(pending, f.runs.child(f.token, asyncSpec.childId()).orElseThrow());
            assertEquals(0, f.activeChildren());
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call WHERE callback_active=TRUE", Integer.class));
            f.calls.recordReturned(call);
        } finally {
            f.allowed.set(true);
            try { f.calls.callbackExited(call); }
            finally { f.steps.callbackExited(f.step); }
        }
        assertEquals(0, f.activeChildren());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call WHERE callback_active=TRUE", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        assertEquals(CallState.RETURNED, f.calls.call(f.token, call.callId()).orElseThrow().state());
    }

    private static ChildSpec wire(String name, String actionId, ChildMode mode) {
        return new ChildSpec(name + "-child", actionId, mode, name + "-request",
                new WireRequest(mode == ChildMode.ASYNC ? "POST" : "GET", "/fixture/" + name, "{}"));
    }
    private static LocalCalculationRegistry registry() {
        return new LocalCalculationRegistry(List.of(new Contract("derive-delta", "1", IMPLEMENTATION,
                Map.of("source", new TypeContract("LINK_METRICS", "link-metrics/v1")),
                Map.of("selectedEntities", new TypeContract("SELECTED_ENTITIES", "selected/v1"),
                        "selectionEvidence", new TypeContract("DECLINE_EVIDENCE", "decline/v1")),
                parameters -> parameters.size() == 1 && "PV".equals(parameters.path("metric").asText()),
                outputs -> outputs.get("selectedEntities").path("linkIds").isArray()
                        && outputs.get("selectionEvidence").path("delta").isIntegralNumber())));
    }
    private static Map<String, ArtifactDraft> drafts(Approval approval, long delta) {
        return Map.of("selectedEntities", draft(approval, "selectedEntities", "{\"linkIds\":[7]}"),
                "selectionEvidence", draft(approval, "selectionEvidence", "{\"delta\":" + delta + "}"));
    }
    private static ArtifactDraft draft(Approval approval, String output, String payload) {
        var binding = approval.invocation().outputs().get(output);
        return new ArtifactDraft(binding.artifactId(), binding.type(), binding.schemaVersion(), binding.scopeRef(), binding.periodsRef(),
                "{\"collectionQuality\":\"UNKNOWN\"}", "{}", approval.invocation().expiresAt(), payload);
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final RunToken token;
        final StepPermit step;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final ArtifactAuthorizer authorizer = (caller, metadata) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(metadata.owner());
        final ModelInvocationRegistry.Approval modelApproval;
        final ChildSpec modelChild;
        final ModelInvocationRegistry.Response response = new ModelInvocationRegistry.Response("Inspect the frozen evidence.",
                List.of(new ModelInvocationRegistry.ToolCall("statistics-call", TOOL.name(), "{}")));
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:call_execution_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql")).execute(source);
            jdbc = new JdbcTemplate(source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK); steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(jdbc, tx, CLOCK);
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "call-plan", 1, "call-run", "call-inputs",
                    List.of(new PlanSpec.Goal("goal", "Inspect statistics evidence", true, "Evidence-backed analysis")), List.of(STEP));
            var inputs = new FrozenInputSet("call-inputs", "call-run", Map.of(
                    "scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
                    "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true)), Map.of("scope", SCOPE, "periods", PERIODS));
            var frozen = FrozenCampaignRun.freeze(plan, inputs, new PlanningAssessment("call-plan", 1, "fixture/v1", List.of(), List.of(), List.of()));
            token = steps.acquireRun(runs.createRun(frozen.definition(OWNER, "call-session")));
            steps.initialize(token, List.of(new StepSpec(STEP.stepId(), FrozenCampaignRun.encode(STEP), List.of(), OUTPUTS, OUTPUTS)));
            step = steps.beginStep(token, STEP.stepId());
            var identity = ModelInvocationRegistry.identity(token.definition(), STEP.stepId(), 1);
            String request = "{\"schemaVersion\":\"campaign-model-request/v1\",\"messages\":[{\"role\":\"user\",\"text\":\"Inspect statistics\"}],"
                    + "\"tools\":[{\"name\":\"inspect_statistics\",\"description\":\"Inspect approved statistics\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
            var invocation = new ModelInvocationRegistry.InvocationSpec(identity.invocationId(), 1, "scripted-model", "1", MODEL_CONFIGURATION,
                    "statistics-explore", "1", "call-inputs", request, Map.of(), EXPIRY);
            modelApproval = new ModelInvocationRegistry(List.of(new ModelInvocationRegistry.Contract("scripted-model", "1",
                    MODEL_CONFIGURATION, ignored -> true))).approve(invocation);
            var action = new ModelInvocationRegistry.ModelActionSpec(identity.actionId(), STEP.stepId(), invocation.invocationId(),
                    invocation.modelRef(), invocation.modelVersion(), invocation.policyRef(), invocation.policyVersion(), FrozenCampaignRun.encode(STEP));
            modelChild = new ChildSpec(identity.childId(), identity.actionId(), ChildMode.MODEL, identity.requestId(), null, null, invocation);
            runs.prepareModelChild(step, action, modelChild, modelApproval, authorizer);
            var dispatch = runs.beginModelDispatch(step, modelChild.childId(), modelApproval, authorizer);
            try { runs.publishModelResponse(step, dispatch, modelApproval, response, authorizer); }
            finally { runs.callbackExited(dispatch); }
        }
        CallPermit beginCall() {
            assertEquals(ChildState.READY, runs.child(token, modelChild.childId()).orElseThrow().state());
            var identity = CampaignExplorationCallStore.identity(token.definition(), STEP.stepId(), modelChild.childId(), "statistics-call");
            var spec = new CallSpec(identity.callId(), identity.actionId(), STEP.stepId(), modelChild.childId(),
                    CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)), "statistics-call", TOOL, "{}");
            calls.prepare(step, spec, modelApproval, authorizer);
            return calls.beginCall(step, spec.callId(), modelApproval, authorizer);
        }
        int activeChildren() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class); }
        int localOutputCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_local_output", Integer.class); }
    }
}
