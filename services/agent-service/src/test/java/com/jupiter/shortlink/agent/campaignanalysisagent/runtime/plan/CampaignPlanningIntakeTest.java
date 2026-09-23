package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

/** Real native planner, durable proposal/acceptance, intake and native fixed graph; no network/model service. */
@Timeout(60)
class CampaignPlanningIntakeTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Instant PLANNING_EXPIRY = NOW.plusSeconds(30);
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final String SESSION = "planning-session", KEY = "planning-main", PROFILE = "fixture-planning";
    private static final String CONFIG = "f".repeat(64), SCOPE_REF = "scope-frozen", PERIOD_REF = "period-frozen";
    private static final TypeRef SCOPE = new TypeRef("ScopeRef", 1, Cardinality.ONE), PERIOD = new TypeRef("PeriodsRef", 1, Cardinality.ONE);
    private static final TypeRef EVIDENCE = new TypeRef("CampaignEvidence", 1, Cardinality.ONE);
    private static final PlanSpec.ExecutorRef QUERY = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "fixture-query", "1");
    private static final PlanSpec.ExecutorRef CONSUME = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, "fixture-consume", "1");
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> OWNER.equals(caller);
    private static final List<PlanSpec.CriterionUse> COMPLETION = List.of(new PlanSpec.CriterionUse("evidence-supported", Map.of()));
    private static final Map<String, Object> NO_PARAMETERS = Map.of("type", "object", "properties", Map.of(),
            "required", List.of(), "additionalProperties", false);
    private static final List<PlanningAssessment.Gap> GAPS = List.of(
            new PlanningAssessment.Gap("causal-delivery", PlanningAssessment.GapReason.PLANNING_UNRESOLVED,
                    "Observed traffic cannot by itself deliver an identified causal explanation."),
            new PlanningAssessment.Gap("causal-required", PlanningAssessment.GapReason.PLANNING_UNRESOLVED,
                    "No intervention or other identification evidence is supplied."));

    @Test
    void durablePlanningResponseSurvivesAtomicAcceptanceFailureThenExecutesItsDependencyPlanOnceAndPreservesRequiredGaps() throws Exception {
        try (var f = new Fixture("FENCED_VALID")) {
            // The same trusted menu can validate a local REACT choice without claiming this
            // test executes that policy; the actual native graph below is Tool -> fixed Skill.
            var exploratory = new PlanSpec.Step("explore", List.of("data-goal"), PlanSpec.ExecutionMode.REACT, null,
                    new PlanSpec.ExplorationPolicy("bounded-explore", "1", List.of(QUERY), SCOPE_REF, PERIOD_REF,
                            COMPLETION, "bounded-fixture"), List.of("query"), Map.of("scope", PlanBinding.input("scope"),
                            "periods", PlanBinding.input("periods"), "upstream", PlanBinding.output("query", "evidence")),
                    Map.of(), "evidence/v1");
            var structural = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(query(Map.of()), exploratory),
                    coverage("explore"), GAPS);
            var materialized = PlanningProposal.parse(structural.encode()).materialize(f.request, f.identity.planId(), 1, f.catalog, f.contracts);
            assertEquals(PlanSpec.ExecutionMode.REACT, materialized.plan().steps().get(1).executionMode());
            assertEquals(List.of("query"), materialized.plan().steps().get(1).dependsOn());
            assertEquals(f.request.goals(), materialized.plan().goals()); assertEquals(f.request.requirements(), materialized.assessment().requirements());

            WorkRef reference = f.register();
            assertEquals("PREPARED", f.intake.planningReceipt(PRINCIPAL, reference).state().name());
            assertEquals(0, f.count("campaign_run_intake")); assertEquals(0, f.count("campaign_run_ledger"));
            assertEquals(0, f.model.calls.get()); assertEquals(0, f.runtimeCalls.get());
            f.jdbc.execute("ALTER TABLE campaign_run_intake ADD CONSTRAINT reject_planning_accept CHECK (request_key <> 'planning-main')");
            Throwable acceptanceFailure = f.failedAdvance(reference);
            assertNotNull(acceptanceFailure);
            var saved = f.planning.header(reference);
            assertEquals("READY", saved.state().name()); assertFalse(saved.callbackActive());
            assertNotNull(saved.invocationHash()); assertNotNull(saved.responseHash()); assertNull(saved.intakeRequestId());
            assertEquals(1, f.model.calls.get()); assertEquals(0, f.runtimeCalls.get()); assertEquals(0, f.submits.get());
            assertEquals(0, f.count("campaign_run_intake")); assertEquals(0, f.count("campaign_run_ledger"));
            var originalResponse = f.planning.response(saved, f.models.approve(f.planning.invocation(saved)));
            assertEquals(f.responseText, originalResponse.text());
            assertTrue(originalResponse.text().startsWith("```json\n"), "Keep the provider receipt unchanged for exact replay");
            f.jdbc.execute("ALTER TABLE campaign_run_intake DROP CONSTRAINT reject_planning_accept");

            f.advance(reference); // Reuses the real READY planning response after the failed acceptance transaction.
            var accepted = f.intake.planningReceipt(PRINCIPAL, reference);
            assertEquals("ACCEPTED", accepted.state().name()); assertEquals(saved.responseHash(), accepted.responseHash());
            assertEquals(saved.invocationHash(), accepted.invocationHash()); assertFalse(accepted.callbackActive());
            assertEquals(1, f.model.calls.get()); assertEquals(1, f.count("campaign_planning_request"));
            assertEquals(1, f.count("campaign_run_intake")); assertEquals(1, f.count("campaign_run_ledger"));
            var typed = f.intake.receipt(PRINCIPAL, new WorkRef(reference.runId(), accepted.intakeRequestId()));
            assertEquals("FROZEN", typed.state().name()); assertEquals(accepted.definitionHash(), typed.definitionHash());
            var frozen = FrozenCampaignRun.read(f.current(reference).definition());
            assertEquals(f.request.goals(), frozen.plan().goals()); assertEquals(f.request.requirements(), frozen.assessment().requirements());
            assertEquals(GAPS, frozen.assessment().gaps());
            assertEquals(List.of("query"), frozen.plan().steps().get(1).dependsOn());
            assertEquals(QUERY, frozen.plan().steps().get(0).executor()); assertEquals(CONSUME, frozen.plan().steps().get(1).executor());
            assertEquals(WAITING, f.steps.step(f.current(reference).token(), "query").orElseThrow().status());
            assertEquals(PENDING, f.steps.step(f.current(reference).token(), "consume").orElseThrow().status());
            assertEquals(1, f.submits.get()); assertEquals(0, f.consumptions.get());
            ChildRecord original = f.runs.child(f.current(reference).token(), "query-child").orElseThrow();
            f.clock.instant = PLANNING_EXPIRY.plusSeconds(1);
            assertTrue(f.clock.instant().isAfter(accepted.expiresAt()));
            f.advance(reference);
            assertEquals(1, f.model.calls.get()); assertEquals(1, f.submits.get()); assertEquals(0, f.consumptions.get());

            var receiver = f.runs.beginReconciliation(f.current(reference).token(), "query-child");
            try { f.runs.publishReady(receiver, f.artifact(reference)); }
            finally { f.runs.callbackExited(receiver); }
            Artifact received = f.runs.readArtifact(OWNER, f.artifact(reference).artifactId(), ALLOW);
            f.advance(reference);
            var completed = f.current(reference).token();
            assertEquals(SUCCEEDED, f.steps.step(completed, "query").orElseThrow().status());
            assertEquals(SUCCEEDED, f.steps.step(completed, "consume").orElseThrow().status());
            assertEquals(Map.of("evidence", received.metadata().ref().artifactId()), f.steps.step(completed, "query").orElseThrow().outputs());
            assertTrue(f.steps.step(completed, "consume").orElseThrow().outputs().isEmpty(), "Read-only Skill must not impersonate the original producer");
            assertEquals(1, f.submits.get()); assertEquals(1, f.consumptions.get()); assertEquals(1, f.model.calls.get());
            assertEquals(original.spec(), f.runs.child(completed, "query-child").orElseThrow().spec());
            assertEquals(original.jobId(), f.runs.child(completed, "query-child").orElseThrow().jobId());
            f.advance(reference);
            assertEquals(1, f.model.calls.get()); assertEquals(1, f.submits.get()); assertEquals(1, f.consumptions.get());
            assertEquals(received, f.runs.readArtifact(OWNER, received.metadata().ref().artifactId(), ALLOW));
            assertEquals(GAPS, FrozenCampaignRun.read(f.current(reference).definition()).assessment().gaps(),
                    "Executing covered data steps cannot erase the frozen unmet causal requirements");
            assertEquals(0, f.gatewayCalls.get()); f.assertExited();
        }
    }

    @Test
    void rejectedCandidatesAndUnknownProviderResultsCreateNoRunOrBusinessCallsAndNeverBlindlyReinvokeThePlanner() throws Exception {
        for (String variant : List.of("REWRITE_GOALS", "DROP_REQUIRED_COVERAGE", "UNKNOWN_EXECUTOR", "HIDDEN_SCOPE", "MODEL_UNKNOWN")) {
            try (var f = new Fixture(variant)) {
                WorkRef reference = f.register();
                String originalRequest = f.planning.request(f.planning.header(reference));
                Throwable first = f.failedAdvance(reference);
                assertFalse(first.toString().contains("PRIVATE_PROVIDER_FAILURE"));
                var rejected = f.planning.header(reference);
                assertEquals("MODEL_UNKNOWN".equals(variant) ? "UNKNOWN" : "REJECTED", rejected.state().name(), variant);
                assertFalse(rejected.callbackActive());
                if ("MODEL_UNKNOWN".equals(variant)) assertNull(rejected.responseHash());
                else assertNotNull(rejected.responseHash(), "A real invalid candidate remains an inspectable response fact");
                assertEquals(1, f.model.calls.get()); assertEquals(0, f.runtimeCalls.get());
                assertEquals(0, f.submits.get()); assertEquals(0, f.consumptions.get()); assertEquals(0, f.gatewayCalls.get());
                assertEquals(0, f.count("campaign_run_intake")); assertEquals(0, f.count("campaign_run_ledger"));
                assertEquals(originalRequest, f.planning.request(rejected));
                assertEquals(f.request.goals(), PlanningProposal.decodeRequest(originalRequest).goals());
                assertEquals(f.request.requirements(), PlanningProposal.decodeRequest(originalRequest).requirements());
                assertEquals(reference, f.register());
                Throwable repeat = f.failedAdvance(reference);
                assertFalse(repeat.toString().contains("PRIVATE_PROVIDER_FAILURE"));
                assertEquals(1, f.model.calls.get(), variant + " must not allocate another model attempt for this slot");
                assertEquals(rejected, f.planning.header(reference));
                assertEquals(0, f.count("campaign_run_intake")); assertEquals(0, f.count("campaign_run_ledger"));
                assertEquals(0, f.runtimeCalls.get()); assertEquals(0, f.submits.get()); f.assertExited();
            }
        }
    }

    @Test
    void domainInvalidStructuredCandidateIsRejectedBeforeAcceptanceAndNeverReissuesItsCompletedModelCall() throws Exception {
        try (var f = new Fixture("VALID")) {
            // This candidate satisfies the generic type/port contract; the profile's existing
            // business rule is an additional acceptance condition, not runtime construction.
            var materialized = PlanningProposal.parse(f.responseText).materialize(f.request,
                    f.identity.planId(), 1, f.catalog, f.contracts);
            f.definitionValidator = definition -> {
                assertEquals(materialized.definition(OWNER, SESSION), definition);
                assertEquals(0, f.count("campaign_run_intake"));
                assertEquals(0, f.count("campaign_run_ledger"));
                assertEquals(0, f.runtimeCalls.get());
                throw new IllegalArgumentException("BUSINESS_GOAL_BINDING_CHANGED");
            };
            WorkRef reference = f.register();
            assertEquals("PLANNING_UNRESOLVED", f.failedAdvance(reference).getMessage());
            var rejected = f.planning.header(reference);
            assertEquals(JdbcCampaignPlanningStore.State.REJECTED, rejected.state());
            assertNotNull(rejected.responseHash()); assertFalse(rejected.callbackActive());
            assertNull(rejected.intakeRequestId()); assertNull(rejected.definitionHash());
            assertEquals(1, f.definitionValidations.get()); assertEquals(1, f.model.calls.get());
            assertEquals(0, f.count("campaign_run_intake")); assertEquals(0, f.count("campaign_run_ledger"));
            assertEquals(0, f.count("campaign_step_ledger")); assertEquals(0, f.count("campaign_child_ledger"));
            assertEquals(0, f.runtimeCalls.get()); assertEquals(0, f.submits.get()); assertEquals(0, f.gatewayCalls.get());

            assertEquals("PLANNING_UNRESOLVED", f.failedAdvance(reference).getMessage());
            assertEquals(rejected, f.planning.header(reference));
            assertEquals(1, f.model.calls.get(), "A rejected durable response does not grant a new model attempt");
            assertEquals(1, f.definitionValidations.get());
            assertEquals(0, f.count("campaign_run_ledger")); f.assertExited();
        }
    }

    @Test
    void typedPendingDefinitionAlsoPassesDomainValidationBeforeCreatingAnyRun() throws Exception {
        try (var f = new Fixture("VALID")) {
            var definition = candidate().materialize(f.request, f.identity.planId(), 1, f.catalog, f.contracts)
                    .definition(OWNER, SESSION);
            f.definitionValidator = actual -> {
                assertEquals(definition, actual);
                assertEquals(0, f.count("campaign_run_ledger"));
                throw new IllegalArgumentException("BUSINESS_GOAL_BINDING_CHANGED");
            };
            var reference = f.intake.register(PRINCIPAL, SESSION, KEY, PROFILE, "1", definition);
            assertEquals("BUSINESS_GOAL_BINDING_CHANGED", f.failedAdvance(reference).getMessage());
            assertEquals(JdbcCampaignRunIntakeStore.State.PENDING, f.intake.receipt(PRINCIPAL, reference).state());
            assertEquals(definition, f.requests.definition(f.requests.header(reference)));
            assertEquals(1, f.definitionValidations.get());
            assertEquals(0, f.count("campaign_run_ledger")); assertEquals(0, f.count("campaign_child_ledger"));
            assertEquals(0, f.model.calls.get()); assertEquals(0, f.runtimeCalls.get()); assertEquals(0, f.submits.get());
            f.assertExited();
        }
    }

    @Test
    void normalizerRepairsSelectedEvidenceDependencyBeforeValidationWithoutChangingTheDurableModelReceipt() throws Exception {
        try (var f = new Fixture("MISSING_DEPENDENCY")) {
            var raw = PlanningProposal.parse(f.responseText);
            assertThrows(IllegalArgumentException.class, () -> raw.materialize(f.request,
                    f.identity.planId(), 1, f.catalog, f.contracts));
            f.proposalNormalizer = (caller, request, candidate) -> {
                assertEquals(OWNER, caller); assertEquals(f.request, request); assertEquals(raw, candidate);
                assertEquals(0, f.count("campaign_run_intake")); assertEquals(0, f.count("campaign_run_ledger"));
                return selectedEvidenceDependency(candidate);
            };
            f.definitionValidator = definition -> assertEquals(List.of("query"),
                    FrozenCampaignRun.read(definition).plan().steps().get(1).dependsOn());
            WorkRef reference = f.register();
            f.advance(reference);
            var accepted = f.planning.header(reference);
            assertEquals(JdbcCampaignPlanningStore.State.ACCEPTED, accepted.state());
            assertEquals(f.responseText, f.planning.response(accepted, f.models.approve(f.planning.invocation(accepted))).text());
            assertEquals(List.of(), PlanningProposal.parse(f.responseText).steps().get(1).dependsOn());
            assertEquals(List.of("query"), FrozenCampaignRun.read(f.current(reference).definition()).plan().steps().get(1).dependsOn());
            assertEquals(1, f.normalizations.get()); assertEquals(1, f.model.calls.get()); assertEquals(1, f.submits.get());
            assertTrue(f.definitionValidations.get() > 0, "Normalized proposals still pass the domain gate");
            f.advance(reference);
            assertEquals(accepted, f.planning.header(reference));
            assertEquals(1, f.normalizations.get(), "An accepted plan resumes its frozen definition");
            assertEquals(1, f.model.calls.get()); assertEquals(1, f.submits.get()); f.assertExited();
        }
    }

    @Test
    void normalizerDoesNotBypassStrictParsingCapabilityValidationOrUnknownModelProtection() throws Exception {
        for (String variant : List.of("REWRITE_GOALS", "UNKNOWN_EXECUTOR", "MODEL_UNKNOWN")) {
            try (var f = new Fixture(variant)) {
                f.proposalNormalizer = (caller, request, candidate) -> selectedEvidenceDependency(candidate);
                var reference = f.register();
                f.failedAdvance(reference);
                var saved = f.planning.header(reference);
                assertEquals("MODEL_UNKNOWN".equals(variant) ? JdbcCampaignPlanningStore.State.UNKNOWN
                        : JdbcCampaignPlanningStore.State.REJECTED, saved.state());
                assertEquals("UNKNOWN_EXECUTOR".equals(variant) ? 1 : 0, f.normalizations.get());
                assertEquals(0, f.definitionValidations.get());
                assertEquals(0, f.count("campaign_run_intake")); assertEquals(0, f.count("campaign_run_ledger"));
                assertEquals(0, f.runtimeCalls.get()); assertEquals(0, f.submits.get());
                f.failedAdvance(reference);
                assertEquals(saved, f.planning.header(reference)); assertEquals(1, f.model.calls.get());
                assertEquals("UNKNOWN_EXECUTOR".equals(variant) ? 1 : 0, f.normalizations.get()); f.assertExited();
            }
        }
    }

    private static PlanningProposal selectedEvidenceDependency(PlanningProposal candidate) {
        var normalized = candidate.steps().stream().map(step -> {
            if (!CONSUME.equals(step.executor()) || !PlanBinding.output("query", "evidence").equals(step.inputBindings().get("upstream")))
                return step;
            var dependencies = new LinkedHashSet<>(step.dependsOn()); dependencies.add("query");
            return new PlanSpec.Step(step.stepId(), step.goalIds(), step.executionMode(), step.executor(), step.explorationPolicy(),
                    List.copyOf(dependencies), step.inputBindings(), step.parameters(), step.outputContractRef());
        }).toList();
        return new PlanningProposal(candidate.schemaVersion(), normalized, candidate.coverageBindings(), candidate.gaps());
    }

    private static PlanSpec.Step query(Map<String, Object> parameters) {
        return new PlanSpec.Step("query", List.of("data-goal"), PlanSpec.ExecutionMode.FIXED, QUERY, null, List.of(),
                Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods")), parameters, "evidence/v1");
    }
    private static PlanSpec.Step consumer() {
        return new PlanSpec.Step("consume", List.of("data-goal"), PlanSpec.ExecutionMode.FIXED, CONSUME, null, List.of("query"),
                Map.of("upstream", PlanBinding.output("query", "evidence")), Map.of(), "read-only/v1");
    }
    private static List<PlanningAssessment.CoverageBinding> coverage(String stepId) {
        return List.of(new PlanningAssessment.CoverageBinding("data-delivery", List.of(new PlanningAssessment.EvidenceOutput(stepId, "evidence"))));
    }
    private static PlanningProposal candidate() {
        return new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(query(Map.of()), consumer()), coverage("query"), GAPS);
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final JdbcCampaignRunStore runs;
        final CampaignStepStore steps;
        final JdbcCampaignRunIntakeStore requests;
        final JdbcCampaignPlanningStore planning;
        final CampaignRunIntake intake;
        final JdbcCampaignRunIntakeStore.Identity identity = JdbcCampaignRunIntakeStore.identity(OWNER, SESSION, KEY);
        final ExecutorService workers = Executors.newSingleThreadExecutor();
        final BlockingQueue<Boolean> consumed = new LinkedBlockingQueue<>();
        final AtomicInteger runtimeCalls = new AtomicInteger(), submits = new AtomicInteger(), consumptions = new AtomicInteger(), gatewayCalls = new AtomicInteger();
        final AtomicInteger definitionValidations = new AtomicInteger();
        CampaignRunIntake.DefinitionValidator definitionValidator = definition -> {};
        final AtomicInteger normalizations = new AtomicInteger();
        CampaignRunIntake.ProposalNormalizer proposalNormalizer = (caller, request, candidate) -> candidate;
        final ModelInvocationRegistry models = new ModelInvocationRegistry(List.of(
                new ModelInvocationRegistry.Contract("scripted-planner", "1", CONFIG, invocation -> true)));
        final ModelInvocationRegistry.Limits modelLimits = ModelInvocationRegistry.Limits.defaults();
        final PlanningProposal.Limits proposalLimits = PlanningProposal.Limits.defaults();
        final CapabilityCatalog catalog = catalog();
        final ArtifactContractRegistry contracts = new ArtifactContractRegistry(List.of(new ArtifactContractRegistry.Contract(EVIDENCE,
                "CAMPAIGN_EVIDENCE", "evidence/v1", value -> value.path("pv").isIntegralNumber(),
                (metadata, quality) -> "UNKNOWN".equals(quality.path("collectionQuality").asText()))));
        final PlanningProposal.Menu menu = PlanningProposal.menu(catalog,
                List.of(new PlanningProposal.CapabilityOffer(QUERY, "Query the frozen period", NO_PARAMETERS),
                        new PlanningProposal.CapabilityOffer(CONSUME, "Inspect the returned evidence", NO_PARAMETERS)),
                List.of(new PlanningProposal.PolicyOffer("bounded-explore", "1", "Explore only the approved evidence", NO_PARAMETERS)));
        final PlanningProposal.Request request;
        final PlanningModel model;
        final String responseText;

        Fixture(String variant) {
            var goals = List.of(new PlanSpec.Goal("data-goal", "Inspect the observed campaign traffic", true, "Deliver the original evidence"),
                    new PlanSpec.Goal("causal-goal", "Explain whether the change was caused by the campaign", true, "Supply an identified causal explanation or retain the gap"));
            var requirements = List.of(
                    new PlanningAssessment.Requirement("data-delivery", "data-goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of()),
                    new PlanningAssessment.Requirement("causal-delivery", "causal-goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of()),
                    new PlanningAssessment.Requirement("causal-required", "causal-goal", PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE, true, "causal-identification", "1", Map.of()));
            var inputs = new FrozenInputSet("inputs-" + CampaignRunStore.sha256(identity.runId()), identity.runId(),
                    Map.of("scope", new Port(SCOPE, true), "periods", new Port(PERIOD, true)),
                    Map.of("scope", SCOPE_REF, "periods", PERIOD_REF));
            request = new PlanningProposal.Request("Inspect the traffic change and assess the limits of a causal explanation.",
                    goals, requirements, inputs, Map.of(), menu);
            String answer = candidate().encode();
            if ("MISSING_DEPENDENCY".equals(variant)) {
                var consume = consumer();
                var detached = new PlanSpec.Step(consume.stepId(), consume.goalIds(), consume.executionMode(), consume.executor(),
                        consume.explorationPolicy(), List.of(), consume.inputBindings(), consume.parameters(), consume.outputContractRef());
                answer = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(query(Map.of()), detached), coverage("query"), GAPS).encode();
            }
            if ("FENCED_VALID".equals(variant)) answer = "```json\n" + answer + "\n```";
            if ("REWRITE_GOALS".equals(variant)) answer = answer.substring(0, answer.length() - 1) + ",\"goals\":[]}";
            if ("DROP_REQUIRED_COVERAGE".equals(variant)) answer = new PlanningProposal(PlanningProposal.SCHEMA_VERSION,
                    candidate().steps(), List.of(), GAPS).encode();
            if ("UNKNOWN_EXECUTOR".equals(variant)) {
                var original = query(Map.of());
                var unknown = new PlanSpec.Step("query", original.goalIds(), PlanSpec.ExecutionMode.FIXED,
                        new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "not-offered", "1"), null, List.of(),
                        original.inputBindings(), Map.of(), "evidence/v1");
                answer = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(unknown, consumer()), coverage("query"), GAPS).encode();
            }
            if ("HIDDEN_SCOPE".equals(variant)) answer = new PlanningProposal(PlanningProposal.SCHEMA_VERSION,
                    List.of(query(Map.of("scopeRef", "unauthorized-scope")), consumer()), coverage("query"), GAPS).encode();
            responseText = answer;
            model = new PlanningModel(responseText, "MODEL_UNKNOWN".equals(variant));
            var source = new DriverManagerDataSource("jdbc:h2:mem:planning_intake_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_17__campaign_run_intake.sql"),
                    new ClassPathResource("sql/migration/V20260920_18__campaign_planning_request.sql")).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, clock); steps = new JdbcCampaignStepStore(jdbc, tx, clock);
            requests = new JdbcCampaignRunIntakeStore(jdbc, tx, clock, runs, proposalLimits.requestBytes());
            planning = new JdbcCampaignPlanningStore(jdbc, tx, clock, requests, proposalLimits.requestBytes(), modelLimits);
            var process = new ProcessIdentity("53bbebd1-70a5-422b-a223-cab944651592", "planning-test-namespace", 11002, NOW.minusSeconds(30).toEpochMilli());
            var recovery = new JdbcCampaignRecoveryStore(jdbc, tx, clock, process,
                    ignored -> new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE));
            var gateway = new ShortLinkBusinessGateway() {
                public ToolResult get(String path, ToolContext context, Map<String, Object> params) { return unexpected(); }
                public ToolResult post(String path, ToolContext context, Map<String, Object> params) { return unexpected(); }
                public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> params) { return unexpected(); }
                private ToolResult unexpected() { gatewayCalls.incrementAndGet(); throw new AssertionError("Known jobs cannot be resubmitted"); }
            };
            var profile = new CampaignRunIntake.Profile(PROFILE, "1", catalog, contracts,
                    (caller, frozen) -> OWNER.equals(caller), ALLOW,
                    (caller, type, value) -> OWNER.equals(caller) && ("ScopeRef".equals(type.name()) ? SCOPE_REF.equals(value)
                            : "PeriodsRef".equals(type.name()) && PERIOD_REF.equals(value)), this::runtime,
                    definition -> { definitionValidations.incrementAndGet(); definitionValidator.validate(definition); },
                    (caller, current, candidate) -> { normalizations.incrementAndGet(); return proposalNormalizer.normalize(caller, current, candidate); });
            var planner = new CampaignRunIntake.PlanningProfile(PROFILE, "1", menu, model, models,
                    "scripted-planner", "1", CONFIG, modelLimits, proposalLimits);
            intake = new CampaignRunIntake(requests, runs, recovery, new StatisticsSubmissionReconciler(runs, gateway), null, null,
                    List.of(profile), (caller, session) -> PRINCIPAL, new ProcessCapacityExecutor.Limits(1, 1, 1, 1),
                    work -> workers.execute(() -> { try { work.run(); } finally { consumed.add(Boolean.TRUE); } }), planning, List.of(planner));
        }
        private CapabilityCatalog catalog() {
            var evidence = Map.of("evidence", new Port(EVIDENCE, true));
            var scoped = Map.of("scope", new Port(SCOPE, true), "periods", new Port(PERIOD, true));
            return new CapabilityCatalog() {
                public String version() { return "planning-catalog/v1"; }
                public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                    if (QUERY.equals(ref)) return Optional.of(new Capability(ref, new Signature(scoped, "evidence/v1", evidence, Parameters.none()), false));
                    if (CONSUME.equals(ref)) return Optional.of(new Capability(ref, new Signature(Map.of("upstream", new Port(EVIDENCE, true)),
                            "read-only/v1", Map.of(), Parameters.none()), false));
                    return Optional.empty();
                }
                public Optional<Policy> policy(String ref, String version) {
                    return "bounded-explore".equals(ref) && "1".equals(version) ? Optional.of(new Policy(ref, version,
                            new Signature(Map.of("scope", new Port(SCOPE, true), "periods", new Port(PERIOD, true),
                                    "upstream", new Port(EVIDENCE, true)), "evidence/v1", evidence, Parameters.none()),
                            Set.of(QUERY), COMPLETION, "bounded-fixture")) : Optional.empty();
                }
                public Optional<Criterion> criterion(String ref, String version) {
                    return "delivery".equals(ref) && "1".equals(version) ? Optional.of(new Criterion(ref, version,
                            PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(EVIDENCE))) : Optional.empty();
                }
            };
        }
        CampaignRecoveryCoordinator.Runtime runtime(CampaignRunIntake.RuntimeContext context) throws Exception {
            runtimeCalls.incrementAndGet(); assertTrue(context.scope().activeCount() > 0); assertFalse(context.scope().isClosed());
            String runId = context.token().definition().runId();
            var queryPolicy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs bound) {
                    assertEquals(SCOPE_REF, bound.value("scope")); assertEquals(PERIOD_REF, bound.value("periods"));
                }
                public void validateOutputs(PlanSpec.Step step, BoundInputs bound, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                    assertEquals(Set.of("evidence"), outputs.keySet());
                }
            };
            var readPolicy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs bound) { assertEquals(13, bound.artifact("upstream").payload().path("pv").intValue()); }
                public void validateOutputs(PlanSpec.Step step, BoundInputs bound, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) { assertTrue(outputs.isEmpty()); }
            };
            var query = new PersistentPlanDriver.FixedExecutor(QUERY, queryPolicy, execution -> {
                var child = new ChildSpec("query-child", "query-action", ChildMode.ASYNC, runId + "-request",
                        new WireRequest("POST", "/internal/short-link-admin/v1/agent-tools/statistics/jobs", "{}"));
                ChildRecord result = execution.child(child, boundary -> {
                    boundary.beforeIo(); submits.incrementAndGet(); return CampaignStepExecution.ChildResult.waiting(runId + "-job");
                });
                return result.state() == ChildState.READY ? PersistentPlanDriver.Result.succeeded(Map.of("evidence", result.artifactId())) : PersistentPlanDriver.Result.waiting();
            });
            var consume = new PersistentPlanDriver.FixedExecutor(CONSUME, readPolicy, execution -> {
                execution.requireCurrent(); consumptions.incrementAndGet();
                assertEquals(SUCCEEDED, steps.step(context.token(), "query").orElseThrow().status());
                assertTrue(execution.inputs().artifact("upstream").metadata().qualityJson().contains("UNKNOWN"));
                return PersistentPlanDriver.Result.succeeded(Map.of());
            });
            var driver = new PersistentPlanDriver(context.token(), runs, steps, catalog, contracts, List.of(query, consume),
                    context.runAuthorizer(), context.artifactAuthorizer(), context.inputAuthorizer(), List.of(), null, context.scope());
            return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()));
        }
        WorkRef register() { return intake.registerPlanning(PRINCIPAL, SESSION, KEY, PROFILE, "1", request, PLANNING_EXPIRY); }
        RunRecord current(WorkRef ref) { return runs.loadRun(OWNER, ref.runId()).orElseThrow(); }
        ArtifactDraft artifact(WorkRef ref) {
            return new ArtifactDraft(ref.runId() + "-evidence", "CAMPAIGN_EVIDENCE", "evidence/v1", SCOPE_REF, PERIOD_REF,
                    "{\"collectionQuality\":\"UNKNOWN\"}", "{\"snapshotId\":\"original\"}", EXPIRY, "{\"pv\":13}");
        }
        void advance(WorkRef ref) throws Exception { intake.submit(PRINCIPAL, ref).get(12, TimeUnit.SECONDS); awaitWorker(); }
        Throwable failedAdvance(WorkRef ref) throws Exception {
            var future = intake.submit(PRINCIPAL, ref);
            var error = assertThrows(ExecutionException.class, () -> future.get(12, TimeUnit.SECONDS));
            awaitWorker(); return error.getCause();
        }
        void awaitWorker() throws InterruptedException { assertEquals(Boolean.TRUE, consumed.poll(8, TimeUnit.SECONDS)); }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        void assertExited() {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_planning_request WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, intake.snapshot().activeAdvances()); assertEquals(0, intake.snapshot().models());
            assertEquals(0, intake.snapshot().largePayloads()); assertEquals(0, intake.snapshot().queued());
        }
        public void close() throws InterruptedException { intake.close(); workers.shutdown(); assertTrue(workers.awaitTermination(8, TimeUnit.SECONDS)); }
    }

    private static final class MutableClock extends Clock {
        volatile Instant instant = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
    }

    private static final class PlanningModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final String response;
        final boolean unknown;
        PlanningModel(String response, boolean unknown) { this.response = response; this.unknown = unknown; }
        public ChatResponse call(Prompt prompt) {
            assertEquals(1, calls.incrementAndGet(), "Each planning identity has at most one actual model dispatch");
            String supplied = prompt.getInstructions().toString();
            assertTrue(supplied.contains(PlanningProposal.SCHEMA_VERSION)); assertTrue(supplied.contains("outputContractRef"));
            assertTrue(supplied.contains("causal-required")); assertTrue(supplied.contains("causal-delivery"));
            assertTrue(supplied.contains("bounded-explore")); assertTrue(supplied.contains("FIXED")); assertTrue(supplied.contains("REACT"));
            if (unknown) throw new IllegalStateException("PRIVATE_PROVIDER_FAILURE");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
        public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("scripted-planner").build(); }
    }
}
