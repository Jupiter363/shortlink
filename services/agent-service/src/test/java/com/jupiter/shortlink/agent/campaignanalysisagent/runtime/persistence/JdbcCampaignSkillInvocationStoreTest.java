package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore.CompletionSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignCallExecution;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual MODEL, SKILL CALL, child and local-output stores; no native runner or Skill implementation. */
@Timeout(30)
class JdbcCampaignSkillInvocationStoreTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIGURATION = "b".repeat(64), IMPLEMENTATION = "a".repeat(64);
    private static final String SCOPE = "scope-frozen", PERIODS = "periods-frozen";
    private static final Set<String> OUTPUTS = Set.of("selectedEntities", "selectionEvidence");
    private static final TypeRef SELECTED = new TypeRef("SelectedEntities", 1, Cardinality.ONE);
    private static final TypeRef EVIDENCE = new TypeRef("SelectionEvidence", 1, Cardinality.ONE);
    private static final PlanSpec.ExecutorRef SKILL = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, "decline_selection", "1");
    private static final PlanSpec.Step STEP = new PlanSpec.Step("explore", List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
            new PlanSpec.ExplorationPolicy("statistics-explore", "1", List.of(SKILL), SCOPE, PERIODS,
                    List.of(new PlanSpec.CriterionUse("supported-evidence", Map.of())), "one-skill"),
            List.of(), Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods")), Map.of(), "evidence/v1");

    @Test
    void allOriginalJobsMustBeReadyBeforeOneCompetingContinuationCanPublishAndSealItsTypedLocalOutputs() throws Exception {
        var f = new Fixture();
        var skill = f.skillStore();
        CallPermit continued = null;
        StepPermit resumedStep = null;
        boolean initialExited = false;
        boolean initialStepExited = false;
        try {
            CompletionSpec missingOutput = new CompletionSpec(f.completion().finalLocalChildId(), f.completion().outputContractRef(),
                    Map.of("selectedEntities", f.completion().outputs().get("selectedEntities")));
            assertEquals("SKILL_REQUIRED_OUTPUT_MISSING", assertThrows(IllegalStateException.class,
                    () -> skill.prepare(f.initialCall, missingOutput, f.modelApproval, f.authorizer)).getMessage());
            assertTrue(skill.invocation(f.token, f.callSpec.callId()).isEmpty(), "A missing registered output cannot become the frozen contract");
            var prepared = skill.prepare(f.initialCall, f.completion(), f.modelApproval, f.authorizer);
            assertEquals(CampaignSkillInvocationStore.State.RUNNING, prepared.state());
            assertNull(prepared.completionId()); assertTrue(prepared.outputs().isEmpty());
            assertEquals(prepared, skill.prepare(f.initialCall, f.completion(), f.modelApproval, f.authorizer));
            assertEquals(prepared, skill.invocation(f.token, f.callSpec.callId()).orElseThrow());
            f.submitBoth();
            ChildRecord baselineJob = f.runs.child(f.token, f.asyncSpec("baseline").childId()).orElseThrow();
            ChildRecord targetJob = f.runs.child(f.token, f.asyncSpec("target").childId()).orElseThrow();
            assertThrows(IllegalStateException.class, () -> skill.awaitContinuation(f.initialCall, Set.of(baselineJob.spec().childId())),
                    "Await cannot omit another pending child of this same logical invocation");
            var waiting = skill.awaitContinuation(f.initialCall, Set.of(baselineJob.spec().childId(), targetJob.spec().childId()));
            assertEquals(CampaignSkillInvocationStore.State.WAITING, waiting.state());
            assertTrue(f.calls.call(f.token, f.callSpec.callId()).orElseThrow().callbackActive(), "Await is not callback exit");
            assertThrows(IllegalStateException.class, () -> skill.beginContinuation(f.step, f.callSpec.callId(), waiting.rowVersion(),
                    f.modelApproval, f.authorizer));
            f.calls.callbackExited(f.initialCall); initialExited = true;
            f.steps.settle(f.step, StepStatus.WAITING, Map.of(), "awaiting-skill-inputs", f.authorizer);
            f.steps.callbackExited(f.step); initialStepExited = true;

            RunToken writer = f.steps.acquireRun(f.token);
            var reopened = f.skillStore();
            ArtifactMetadata baseline = f.receive(writer, "baseline", 13);
            assertEquals(StepStatus.WAITING, f.steps.refreshWaiting(writer, STEP.stepId()).status());
            assertThrows(IllegalStateException.class, () -> f.steps.beginStep(writer, STEP.stepId()));
            assertThrows(IllegalStateException.class, () -> reopened.beginContinuation(f.step, f.callSpec.callId(), waiting.rowVersion(),
                    f.modelApproval, f.authorizer));
            assertThrows(IllegalStateException.class, () -> reopened.completeInvocation(f.initialCall, f.authorizer));
            assertEquals(waiting, reopened.invocation(writer, f.callSpec.callId()).orElseThrow());
            assertEquals(1, f.calls.call(writer, f.callSpec.callId()).orElseThrow().attemptVersion());

            ArtifactMetadata target = f.receive(writer, "target", 3);
            for (var original : List.of(baselineJob, targetJob)) {
                ChildRecord ready = f.runs.child(writer, original.spec().childId()).orElseThrow();
                assertEquals(ChildState.READY, ready.state()); assertEquals(original.spec(), ready.spec());
                assertEquals(original.jobId(), ready.jobId()); assertTrue(ready.attemptVersion() > original.attemptVersion(),
                        "Legitimate result reconciliation advances the child attempt without changing the original job");
            }
            assertEquals(StepStatus.READY, f.steps.refreshWaiting(writer, STEP.stepId()).status());
            resumedStep = f.steps.beginStep(writer, STEP.stepId());
            StepPermit current = resumedStep;
            f.allowed.set(false);
            assertThrows(SecurityException.class, () -> reopened.beginContinuation(current, f.callSpec.callId(), waiting.rowVersion(),
                    f.modelApproval, f.authorizer));
            assertEquals(waiting, reopened.invocation(writer, f.callSpec.callId()).orElseThrow());
            f.allowed.set(true);

            // Two independent store instances race the same frozen invocation version and current Step.
            continued = raceContinuation(f, current, waiting.rowVersion());
            assertEquals(f.initialCall.callId(), continued.callId()); assertEquals(f.initialCall.actionId(), continued.actionId());
            assertEquals(2, continued.attemptVersion()); assertNotEquals(f.initialCall.attemptId(), continued.attemptId());
            assertEquals(f.callSpec, f.calls.call(writer, continued.callId()).orElseThrow().spec());
            var runningCall = f.calls.call(writer, continued.callId()).orElseThrow();
            try { f.calls.callbackExited(f.initialCall); }
            catch (IllegalStateException stale) { /* Rejecting an old attempt or acknowledging its archived exit are both safe. */ }
            assertEquals(runningCall, f.calls.call(writer, continued.callId()).orElseThrow());
            assertTrue(f.calls.mayExecute(continued), "A stale attempt's cleanup cannot close the newly admitted callback");
            assertThrows(IllegalStateException.class, () -> f.skillStore().beginContinuation(current, f.callSpec.callId(), waiting.rowVersion(),
                    f.modelApproval, f.authorizer));
            assertEquals(2, f.calls.call(writer, continued.callId()).orElseThrow().attemptVersion());
            assertEquals(2, f.submits.get()); assertEquals(1, f.count("campaign_model_response"));
            CallPermit admitted = continued;
            assertThrows(IllegalStateException.class, () -> reopened.completeInvocation(admitted, f.authorizer),
                    "All source jobs being READY does not replace the registered final output contract");

            var localInvocation = new InvocationSpec("pair-comparison", "1", IMPLEMENTATION, "{\"metric\":\"PV\"}",
                    Map.of("baseline", baseline, "target", target), f.completion().outputs(), EXPIRY);
            Approval localApproval = localRegistry().approve(localInvocation);
            var finalChild = new ChildSpec(f.completion().finalLocalChildId(), admitted.actionId(), ChildMode.LOCAL,
                    "final-local-request", null, localInvocation);
            Map<String, ArtifactRef> outputs;
            try (var context = new CampaignCallExecution(admitted, f.runs, f.steps, f.calls, f.allowed::get)) {
                // Adapters can revisit their original source children without another external submission.
                for (String side : List.of("baseline", "target")) context.child(f.asyncSpec(side), boundary -> {
                    f.submits.incrementAndGet(); fail("The original READY statistics task must be reused"); return null;
                });
                outputs = context.local(finalChild, localApproval, f.authorizer, boundary -> {
                    f.computes.incrementAndGet();
                    long base = tree(boundary.readInput("baseline").payloadJson()).path("pv").asLong();
                    long currentValue = tree(boundary.readInput("target").payloadJson()).path("pv").asLong();
                    return drafts(localApproval, currentValue - base);
                });
                assertEquals(OUTPUTS, outputs.keySet()); assertEquals(1, f.computes.get());
                assertEquals(outputs, context.local(finalChild, localApproval, f.authorizer,
                        boundary -> { fail("Published final LOCAL outputs cannot be recomputed"); return null; }));
            }
            f.allowed.set(false);
            assertThrows(SecurityException.class, () -> reopened.completeInvocation(admitted, f.authorizer));
            assertEquals(CampaignSkillInvocationStore.State.RUNNING, reopened.invocation(writer, admitted.callId()).orElseThrow().state());
            f.allowed.set(true);
            var completed = reopened.completeInvocation(admitted, f.authorizer);
            assertEquals(CampaignSkillInvocationStore.State.COMPLETED, completed.state());
            assertNotNull(completed.completionId()); assertFalse(completed.completionId().isBlank());
            assertEquals(outputs, completed.outputs());
            assertEquals(completed, f.skillStore().completeInvocation(admitted, f.authorizer));
            assertEquals(completed, f.skillStore().invocation(writer, admitted.callId()).orElseThrow());
            assertTrue(f.calls.call(writer, admitted.callId()).orElseThrow().callbackActive(), "Sealing still does not impersonate finally");
            for (var output : outputs.values()) {
                Artifact artifact = f.runs.readArtifact(OWNER, output.artifactId(), f.authorizer);
                assertEquals(admitted.actionId(), artifact.metadata().actionId()); assertEquals(finalChild.childId(), artifact.metadata().childId());
                assertEquals(EXPIRY, output.expiresAt()); assertEquals(output.payloadHash(), CampaignRunStore.sha256(artifact.payloadJson()));
            }
            assertEquals(1, f.computes.get()); assertEquals(2, f.submits.get());
            assertEquals(1, f.count("campaign_model_response")); assertEquals(1, f.count("campaign_exploration_call"));
            assertEquals(1, f.runs.child(writer, finalChild.childId()).orElseThrow().attemptVersion());
        } finally {
            f.allowed.set(true);
            if (continued != null) f.calls.callbackExited(continued);
            if (resumedStep != null) f.steps.callbackExited(resumedStep);
            if (!initialExited) f.calls.callbackExited(f.initialCall);
            if (!initialStepExited) f.steps.callbackExited(f.step);
        }
        f.assertExited();
    }

    @Test
    void failedReturnedWriteRollsBackSkillWaitAndDependencyRegistrationBeforeARealRetry() throws Exception {
        var f = new Fixture();
        try {
            var skill = f.skillStore();
            var prepared = skill.prepare(f.initialCall, f.completion(), f.modelApproval, f.authorizer);
            f.submitBoth();
            var original = f.calls.call(f.token, f.callSpec.callId()).orElseThrow();
            var children = Set.of(f.asyncSpec("baseline").childId(), f.asyncSpec("target").childId());
            f.jdbc.execute("ALTER TABLE campaign_exploration_call ADD CONSTRAINT reject_skill_wait_return CHECK (call_state <> 'RETURNED')");
            assertThrows(IllegalStateException.class, () -> skill.awaitContinuation(f.initialCall, children));
            assertEquals(prepared, f.skillStore().invocation(f.token, f.callSpec.callId()).orElseThrow());
            assertEquals(original, f.calls.call(f.token, f.callSpec.callId()).orElseThrow());
            assertEquals(0, f.count("campaign_skill_wait_child"));
            assertTrue(f.calls.mayExecute(f.initialCall)); assertEquals(2, f.submits.get());
            f.jdbc.execute("ALTER TABLE campaign_exploration_call DROP CONSTRAINT reject_skill_wait_return");
            var waiting = f.skillStore().awaitContinuation(f.initialCall, children);
            assertEquals(CampaignSkillInvocationStore.State.WAITING, waiting.state());
            assertEquals(CallState.RETURNED, f.calls.call(f.token, f.callSpec.callId()).orElseThrow().state());
            assertEquals(2, f.count("campaign_skill_wait_child"));
            assertEquals(2, f.submits.get()); assertEquals(1, f.count("campaign_model_response"));
        } finally {
            f.calls.callbackExited(f.initialCall);
            f.steps.callbackExited(f.step);
        }
        f.assertExited();
    }

    private static CallPermit raceContinuation(Fixture f, StepPermit step, long version) throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> attempt = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { return f.skillStore().beginContinuation(step, f.callSpec.callId(), version, f.modelApproval, f.authorizer); }
                catch (IllegalStateException rejected) { return rejected; }
            };
            var left = executor.submit(attempt); var right = executor.submit(attempt); start.countDown();
            var results = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(CallPermit.class::isInstance).count());
            assertEquals(1, results.stream().filter(IllegalStateException.class::isInstance).count());
            return results.stream().filter(CallPermit.class::isInstance).map(CallPermit.class::cast).findFirst().orElseThrow();
        } finally {
            start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static LocalCalculationRegistry localRegistry() {
        return new LocalCalculationRegistry(List.of(new Contract("pair-comparison", "1", IMPLEMENTATION,
                Map.of("baseline", new TypeContract("LINK_METRICS", "link-metrics/v1"), "target", new TypeContract("LINK_METRICS", "link-metrics/v1")),
                Map.of("selectedEntities", new TypeContract("SELECTED_ENTITIES", "selected/v1"),
                        "selectionEvidence", new TypeContract("DECLINE_EVIDENCE", "decline/v1")),
                parameters -> parameters.size() == 1 && "PV".equals(parameters.path("metric").asText()),
                outputs -> outputs.get("selectedEntities").path("linkIds").isArray() && outputs.get("selectionEvidence").path("delta").isIntegralNumber())));
    }
    private static Map<String, ArtifactDraft> drafts(Approval approval, long delta) {
        var result = new LinkedHashMap<String, ArtifactDraft>();
        for (var entry : approval.invocation().outputs().entrySet()) {
            var binding = entry.getValue();
            result.put(entry.getKey(), new ArtifactDraft(binding.artifactId(), binding.type(), binding.schemaVersion(), SCOPE, PERIODS,
                    "{\"collectionQuality\":\"UNKNOWN\"}", "{}", EXPIRY,
                    "selectedEntities".equals(entry.getKey()) ? "{\"linkIds\":[7]}" : json(Map.of("delta", delta))));
        }
        return Map.copyOf(result);
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final RunToken token;
        final StepPermit step;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final AtomicInteger submits = new AtomicInteger(), computes = new AtomicInteger();
        final ArtifactAuthorizer authorizer = (caller, metadata) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(metadata.owner());
        final ModelInvocationRegistry.Approval modelApproval;
        final ChildSpec modelChild;
        final CallSpec callSpec;
        final CallPermit initialCall;
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:skill_invocation_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql"),
                    new ClassPathResource("sql/migration/V20260920_12__campaign_skill_invocation.sql")).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK); steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(jdbc, tx, CLOCK);
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "skill-plan", 1, "skill-run", "skill-inputs",
                    List.of(new PlanSpec.Goal("goal", "Compare the authorized evidence", true, "Deliver selected entities and evidence")), List.of(STEP));
            var inputs = new FrozenInputSet("skill-inputs", "skill-run", Map.of(
                    "scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
                    "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true)), Map.of("scope", SCOPE, "periods", PERIODS));
            var frozen = FrozenCampaignRun.freeze(plan, inputs, new PlanningAssessment("skill-plan", 1, "fixture/v1", List.of(), List.of(), List.of()));
            token = steps.acquireRun(runs.createRun(frozen.definition(OWNER, "skill-session")));
            steps.initialize(token, List.of(new StepSpec(STEP.stepId(), json(STEP), List.of(), OUTPUTS, OUTPUTS)));
            step = steps.beginStep(token, STEP.stepId());
            var identity = ModelInvocationRegistry.identity(token.definition(), STEP.stepId(), 1);
            String request = "{\"schemaVersion\":\"campaign-model-request/v1\",\"messages\":[{\"role\":\"user\",\"text\":\"Compare statistics\"}],"
                    + "\"tools\":[{\"name\":\"decline_selection\",\"description\":\"Apply approved comparison method\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
            var invocation = new ModelInvocationRegistry.InvocationSpec(identity.invocationId(), 1, "scripted-model", "1", CONFIGURATION,
                    "statistics-explore", "1", "skill-inputs", request, Map.of(), EXPIRY);
            modelApproval = new ModelInvocationRegistry(List.of(new ModelInvocationRegistry.Contract("scripted-model", "1", CONFIGURATION,
                    ignored -> true))).approve(invocation);
            var action = new ModelInvocationRegistry.ModelActionSpec(identity.actionId(), STEP.stepId(), invocation.invocationId(),
                    invocation.modelRef(), invocation.modelVersion(), invocation.policyRef(), invocation.policyVersion(), json(STEP));
            modelChild = new ChildSpec(identity.childId(), identity.actionId(), ChildMode.MODEL, identity.requestId(), null, null, invocation);
            var response = new ModelInvocationRegistry.Response("Use the approved comparison method.",
                    List.of(new ModelInvocationRegistry.ToolCall("skill-call", SKILL.name(), "{}")));
            runs.prepareModelChild(step, action, modelChild, modelApproval, authorizer);
            var dispatch = runs.beginModelDispatch(step, modelChild.childId(), modelApproval, authorizer);
            try { runs.publishModelResponse(step, dispatch, modelApproval, response, authorizer); }
            finally { runs.callbackExited(dispatch); }
            var callIdentity = CampaignExplorationCallStore.identity(token.definition(), STEP.stepId(), modelChild.childId(), "skill-call");
            callSpec = new CallSpec(callIdentity.callId(), callIdentity.actionId(), STEP.stepId(), modelChild.childId(),
                    CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)), "skill-call", SKILL, "{}");
            calls.prepare(step, callSpec, modelApproval, authorizer);
            initialCall = calls.beginCall(step, callSpec.callId(), modelApproval, authorizer);
        }
        ChildSpec asyncSpec(String side) {
            return new ChildSpec(side + "-child", callSpec.actionId(), ChildMode.ASYNC, side + "-request",
                    new WireRequest("POST", "/fixture/statistics", json(Map.of("period", side))));
        }
        void submitBoth() throws Exception {
            try (var context = new CampaignCallExecution(initialCall, runs, steps, calls, allowed::get)) {
                for (String side : List.of("baseline", "target")) {
                    var child = context.child(asyncSpec(side), boundary -> {
                        boundary.beforeIo(); submits.incrementAndGet();
                        return CampaignStepExecution.ChildResult.waiting("job-" + side);
                    });
                    assertEquals(ChildState.WAITING, child.state()); assertFalse(child.callbackActive());
                }
            }
        }
        ArtifactMetadata receive(RunToken writer, String side, long pv) {
            var dispatch = runs.beginReconciliation(writer, asyncSpec(side).childId());
            try { runs.publishReady(dispatch, new ArtifactDraft(side + "-artifact", "LINK_METRICS", "link-metrics/v1", SCOPE, PERIODS,
                    "{\"collectionQuality\":\"UNKNOWN\"}", "{}", EXPIRY, json(Map.of("pv", pv)))); }
            finally { runs.callbackExited(dispatch); }
            return runs.inspectArtifact(OWNER, side + "-artifact", authorizer);
        }
        CompletionSpec completion() {
            return new CompletionSpec("final-local-child", "selected-pair/v1", Map.of(
                    "selectedEntities", new OutputBinding("selected-final", "SELECTED_ENTITIES", "selected/v1", SCOPE, PERIODS),
                    "selectionEvidence", new OutputBinding("evidence-final", "DECLINE_EVIDENCE", "decline/v1", SCOPE, PERIODS)));
        }
        JdbcCampaignSkillInvocationStore skillStore() {
            var catalog = new CapabilityCatalog() {
                public String version() { return "fixture/v1"; }
                public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                    return SKILL.equals(ref) ? Optional.of(new Capability(SKILL,
                            new Signature(Map.of(), "selected-pair/v1", Map.of("selectedEntities", new Port(SELECTED, true),
                                    "selectionEvidence", new Port(EVIDENCE, true)), Parameters.none()), false)) : Optional.empty();
                }
                public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
                public Optional<Criterion> criterion(String ref, String version) { return Optional.empty(); }
            };
            var contracts = new ArtifactContractRegistry(List.of(
                    new ArtifactContractRegistry.Contract(SELECTED, "SELECTED_ENTITIES", "selected/v1",
                            value -> value.path("linkIds").isArray(), (metadata, quality) -> SCOPE.equals(metadata.ref().scopeRef())
                                    && PERIODS.equals(metadata.ref().periodsRef()) && "UNKNOWN".equals(quality.path("collectionQuality").asText())),
                    new ArtifactContractRegistry.Contract(EVIDENCE, "DECLINE_EVIDENCE", "decline/v1",
                            value -> value.path("delta").isIntegralNumber(), (metadata, quality) -> SCOPE.equals(metadata.ref().scopeRef())
                                    && PERIODS.equals(metadata.ref().periodsRef()) && "UNKNOWN".equals(quality.path("collectionQuality").asText()))));
            return new JdbcCampaignSkillInvocationStore(jdbc, tx, CLOCK, catalog, contracts);
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        void assertExited() {
            for (String table : List.of("campaign_child_ledger", "campaign_exploration_call", "campaign_step_ledger"))
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE callback_active=TRUE", Integer.class), table);
        }
    }
}
