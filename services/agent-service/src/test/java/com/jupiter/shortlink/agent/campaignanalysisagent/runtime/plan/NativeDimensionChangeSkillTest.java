package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.SkillScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore.InvocationRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Flux;

/** One actual REACT Step invokes two real Skills; only the external model and statistics replies are scripted. */
@Timeout(60)
class NativeDimensionChangeSkillTest {
    private static final String RUN = "native-dimension-run", STEP = "explore", PAIR = "period-pair", PROMPT = "Find observed declines and inspect province/device changes";
    private static final String SELECT_CALL = "select-first", DIMENSION_CALL = "drill-selected", CONFIG = "c".repeat(64);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), false);
    private static final TypeRef CHANGE_TYPE = new TypeRef("DimensionChangeArtifact", 1, Cardinality.ONE);
    private static final List<String> DIMENSIONS = List.of("province", "device");
    private enum Mode { DECLINES, EMPTY, REVOKED }

    @Test
    void oneNativeReactStepConsumesItsOwnCompletedSelectionThenResumesDimensionSkillAndPublishesTypedObservedEvidence() throws Exception {
        verifyCompletedChain(new Fixture(Mode.DECLINES));
    }

    @Test
    void productionFactoryComposesBothSkillsWithOriginalJobsAndTheSameAdmittedScope() throws Exception {
        verifyCompletedChain(new Fixture(Mode.DECLINES, true));
    }

    private void verifyCompletedChain(Fixture f) throws Exception {
        assertTrue(f.steps.steps(f.token).isEmpty());
        assertEquals(1, f.runtime().graph().advance().advancedSteps());
        assertEquals(StepStatus.WAITING, f.step().status(), () -> f.step().reason()); assertEquals(1, f.model.count.get());
        assertEquals(2, f.gateway.submits); assertEquals(0, f.gateway.submitted("DIMENSION_BREAKDOWN"));
        f.receiveAndResume("decline_selection");
        assertEquals(StepStatus.WAITING, f.step().status()); assertEquals(2, f.model.count.get());
        assertTrue(f.model.selectionConsumedWhileStepRunning);
        InvocationRecord selected = f.completion("decline_selection");
        assertEquals(Set.of("selectedEntities", "selectionEvidence"), selected.outputs().keySet());
        assertEquals(4, f.gateway.submits); assertEquals(2, f.gateway.submitted("DIMENSION_BREAKDOWN"));
        CallRecord dimensionCall = f.call("dimension_change");
        assertEquals(CampaignSkillInvocationStore.State.WAITING, f.skills.invocation(f.token, dimensionCall.spec().callId()).orElseThrow().state());
        var derivedMetadata = f.artifactOfType(CampaignSelectedScope.TYPE);
        Artifact derived = f.runs.readArtifact(OWNER, derivedMetadata.ref().artifactId(), f.auth);
        JsonNode derivedBody = tree(derived.payloadJson());
        assertEquals(1, derivedBody.path("memberCount").asLong()); assertEquals(1, derivedBody.path("shardCount").asInt());
        assertEquals(f.scopeRef, derivedBody.path("sourceScopeRef").asText());
        assertEquals(VERSION, derivedBody.path("enumerationVersion").asText()); assertFalse(derivedBody.path("groupScopeComplete").asBoolean());
        assertNotEquals(f.scopeRef, derivedMetadata.ref().scopeRef());
        ChildRecord derivedChild = f.runs.child(f.token, derivedMetadata.childId()).orElseThrow();
        assertEquals(ChildState.READY, derivedChild.state()); assertEquals(1, derivedChild.attemptVersion());
        var dimensionRequests = f.children().stream().filter(child -> child.spec().mode() == ChildMode.ASYNC
                && child.spec().actionId().equals(dimensionCall.spec().actionId())).toList();
        assertEquals(2, dimensionRequests.size());
        for (ChildRecord child : dimensionRequests) {
            var wire = tree(child.spec().wire().bodyJson());
            assertEquals(DIMENSIONS, JSON.convertValue(wire.path("dimensions"), List.class));
            assertEquals(List.of(1), JSON.convertValue(wire.path("scope").path("linkIds"), List.class));
            assertEquals(derivedMetadata.ref().scopeRef(), wire.path("scope").path("parentScopeRef").asText());
        }
        var waitingAgain = f.runtime(); waitingAgain.driver().refreshWaiting();
        assertEquals(0, waitingAgain.graph().advance().advancedSteps()); assertEquals(2, f.model.count.get());
        f.receiveAndResume("dimension_change");
        assertEquals(StepStatus.SUCCEEDED, f.step().status()); assertEquals(Set.of("dimensionChanges"), f.step().outputs().keySet());
        assertEquals(3, f.model.count.get()); assertEquals(4, f.gateway.submits); assertEquals(4, f.gateway.pageReads);
        assertEquals(0, f.gateway.recoveries); assertEquals(2, f.callsCount());
        assertEquals(derivedChild, f.runs.child(f.token, derivedMetadata.childId()).orElseThrow());
        assertEquals(derived, f.runs.readArtifact(OWNER, derivedMetadata.ref().artifactId(), f.auth));
        Artifact output = f.runs.readArtifact(OWNER, f.step().outputs().get("dimensionChanges"), f.auth);
        JsonNode report = tree(output.payloadJson());
        assertEquals("SHARD_COHORT", report.path("analysisUnit").asText()); assertEquals("OBSERVED_ONLY", report.path("interpretation").asText());
        assertEquals("OBSERVED", report.path("evidenceDisposition").asText()); assertFalse(report.path("groupScopeComplete").asBoolean());
        assertEquals(2, report.path("comparisonRows").asLong()); assertEquals(1, report.path("memberCount").asLong());
        assertEquals(dimensionCall.spec().actionId(), output.metadata().actionId());
        assertEquals(STEP, dimensionCall.spec().stepId()); assertEquals(Instant.ofEpochMilli(EXPIRY), output.metadata().ref().expiresAt());
        assertEquals(f.completion("dimension_change").outputs().get("dimensionChanges"), output.metadata().ref());
        assertEquals(CampaignExplorationCandidateStore.Verdict.COMPLETE, f.candidates.assessment(f.token, STEP).orElseThrow().verdict());
        assertTrue(f.children().stream().filter(child -> child.spec().mode() == ChildMode.LOCAL)
                .allMatch(child -> child.state() == ChildState.READY && child.attemptVersion() == 1));
        for (ChildRecord original : dimensionRequests) {
            ChildRecord ready = f.runs.child(f.token, original.spec().childId()).orElseThrow();
            assertEquals(original.spec(), ready.spec()); assertEquals(original.jobId(), ready.jobId());
        }
        f.reopen(); var restored = f.runtime(); restored.driver().refreshWaiting();
        assertEquals(0, restored.graph().advance().advancedSteps());
        assertEquals(output, f.runs.readArtifact(OWNER, output.metadata().ref().artifactId(), f.auth));
        assertEquals(3, f.model.count.get()); assertEquals(4, f.gateway.submits); assertEquals(4, f.gateway.pageReads); f.exited();
    }

    @Test
    void genuineEmptySelectionNeedsNoDimensionQueryAndRevokedSourceCannotDispatchDimensionWork() throws Exception {
        for (Mode mode : List.of(Mode.EMPTY, Mode.REVOKED)) {
            var f = new Fixture(mode);
            assertEquals(1, f.runtime().graph().advance().advancedSteps());
            f.receiveAndResume("decline_selection");
            assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);
            assertEquals(0, f.gateway.submitted("DIMENSION_BREAKDOWN"));
            if (mode == Mode.EMPTY) {
                assertEquals(StepStatus.SUCCEEDED, f.step().status()); assertEquals(3, f.model.count.get());
                Artifact result = f.runs.readArtifact(OWNER, f.step().outputs().get("dimensionChanges"), f.auth);
                JsonNode body = tree(result.payloadJson());
                assertEquals("NOT_APPLICABLE", body.path("evidenceDisposition").asText());
                assertEquals("NO_DECLINES", body.path("emptyReason").asText());
                assertEquals(0, body.path("memberCount").asLong()); assertEquals(0, body.path("pageCount").asInt());
                assertTrue(body.path("selectionComplete").asBoolean()); assertTrue(body.path("headArtifactId").isNull());
                assertEquals(CampaignExplorationCandidateStore.Verdict.COMPLETE, f.candidates.assessment(f.token, STEP).orElseThrow().verdict());
                f.reopen(); assertEquals(0, f.runtime().graph().advance().advancedSteps());
                assertEquals(3, f.model.count.get());
            } else {
                assertFalse(f.allowed.get()); assertNotEquals(StepStatus.SUCCEEDED, f.step().status()); assertTrue(f.step().outputs().isEmpty());
                assertEquals(2, f.model.count.get()); assertEquals(0, f.artifacts(DimensionChangePublisher.TYPE));
                assertEquals(0, f.artifacts(CampaignSelectedScope.TYPE));
                assertThrows(SecurityException.class, () -> f.runs.readArtifact(OWNER, f.selectedMetadata.ref().artifactId(), f.auth));
                assertThrows(SecurityException.class, f::runtime);
                assertEquals(2, f.model.count.get()); assertEquals(2, f.gateway.submits);
            }
            f.exited();
        }
    }

    private record Runtime(PersistentPlanDriver driver, NativePlanGraph graph) {}
    private static final class Fixture {
        final CampaignParentCoverageTest.Fixture base = new CampaignParentCoverageTest.Fixture();
        final CampaignRunStore runs = base.runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final CampaignSkillInvocationStore skills;
        final CampaignDeclineSelectionStore selections;
        final CampaignExplorationCandidateStore candidates;
        final ArtifactContractRegistry contracts;
        final CapabilityCatalog catalog;
        final ModelInvocationRegistry models = new ModelInvocationRegistry(List.of(new ModelInvocationRegistry.Contract("scripted-model", "1", CONFIG, ignored -> true)));
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final ArtifactAuthorizer auth = (caller, artifact) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(artifact.owner());
        final Mode mode;
        final boolean productionFactory;
        final Gateway gateway = new Gateway(this);
        final Model model = new Model(this);
        final Path approvedRoot;
        final String scopeRef, scopeArtifact, declineArguments;
        ArtifactMetadata selectedMetadata;
        RunToken token;
        Fixture(Mode mode) throws Exception { this(mode, false); }
        Fixture(Mode mode, boolean productionFactory) throws Exception {
            this.mode = mode;
            this.productionFactory = productionFactory;
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_3__campaign_submission_deferral.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql"),
                    new ClassPathResource("sql/migration/V20260920_10__campaign_exploration_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_11__campaign_exploration_budget.sql"),
                    new ClassPathResource("sql/migration/V20260920_12__campaign_skill_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_13__campaign_skill_observation.sql"),
                    new ClassPathResource("sql/migration/V20260920_14__campaign_exploration_candidate.sql")).execute(base.jdbc.getDataSource());
            steps = new JdbcCampaignStepStore(base.jdbc, base.transactions, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(base.jdbc, base.transactions, CLOCK);
            selections = new JdbcCampaignDeclineSelectionStore(base.jdbc, base.transactions, CLOCK, runs);
            approvedRoot = new ClassPathResource("campaign-skills").getFile().toPath();
            scopeRef = FrozenCampaignScope.freeze(PRINCIPAL, "group-a", List.of(new FrozenCampaignScope.AuthorityPage(
                    PRINCIPAL, "group-a", null, VERSION, List.of(1L, 2L), null))).scopeRef();
            var decline = descriptor("decline-selection", "1"); decline.put("scopeRef", scopeRef);
            var dimension = descriptor("dimension-change", "3"); dimension.put("dimensions", DIMENSIONS); dimension.put("filters", List.of());
            Map<String, Port> ports = Map.of("scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
                    "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
                    "selectionDefinition", new Port(new TypeRef("DeclineSelectionDefinition", 1, Cardinality.ONE), true),
                    "dimensionDefinition", new Port(new TypeRef("DimensionChangeDefinition", 3, Cardinality.ONE), true));
            var uses = List.of(new PlanSpec.CriterionUse("dimension-coverage", Map.of()));
            var policy = new PlanSpec.ExplorationPolicy("combined-explore", "1", List.of(FrozenDeclineSelection.REF, FrozenDimensionChange.REF_V3),
                    scopeRef, PAIR, uses, "two-approved-methods");
            Map<String, Port> policyPorts = new LinkedHashMap<>(ports);
            Map<String, PlanBinding> stepInputs = new LinkedHashMap<>(Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods"),
                    "selectionDefinition", PlanBinding.input("selectionDefinition"), "dimensionDefinition", PlanBinding.input("dimensionDefinition")));
            if (productionFactory) {
                policyPorts.put("scopeArtifact", new Port(new TypeRef("ScopeArtifact", 1, Cardinality.ONE), true));
                stepInputs.put("scopeArtifact", PlanBinding.artifact("scope-artifact-" + CampaignRunStore.sha256("native-dimension-scope")));
            }
            var planned = new PlanSpec.Step(STEP, List.of("goal"), PlanSpec.ExecutionMode.REACT, null, policy, List.of(),
                    stepInputs,
                    Map.of(), FrozenDimensionChange.OUTPUT_CONTRACT);
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "native-dimension-plan", 1, RUN, "native-dimension-inputs",
                    List.of(new PlanSpec.Goal("goal", "Examine observed changes for declined members", true, "Deliver scoped dimensional evidence with quality")), List.of(planned));
            var inputs = new FrozenInputSet(plan.inputSetRef(), RUN, ports, Map.of("scope", scopeRef, "periods", PAIR,
                    "selectionDefinition", decline, "dimensionDefinition", dimension));
            var assessment = new PlanningAssessment(plan.planId(), 1, "combined-catalog/v1", List.of(new PlanningAssessment.Requirement(
                    "delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY, true, "deliver-dimensions", "1", Map.of())),
                    List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput(STEP, "dimensionChanges")))), List.of());
            token = steps.acquireRun(runs.createRun(FrozenCampaignRun.freeze(plan, inputs, assessment).definition(OWNER, "native-dimension-session")));
            scopeArtifact = publishScope();
            var registrations = new ArrayList<>(DeclineSelectionSkill.artifactContracts()); registrations.addAll(DimensionChangeSkill.artifactContracts());
            contracts = new ArtifactContractRegistry(registrations);
            catalog = new CapabilityCatalog() {
                public String version() { return "combined-catalog/v1"; }
                public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                    if (FrozenDeclineSelection.REF.equals(ref)) return Optional.of(DeclineSelectionSkill.capability());
                    return FrozenDimensionChange.REF_V3.equals(ref) ? Optional.of(DimensionChangeSkill.capability(ref)) : Optional.empty();
                }
                public Optional<Policy> policy(String ref, String version) { return "combined-explore".equals(ref) && "1".equals(version)
                        ? Optional.of(new Policy(ref, version, new Signature(policyPorts, FrozenDimensionChange.OUTPUT_CONTRACT,
                                Map.of("dimensionChanges", new Port(CHANGE_TYPE, true)), Parameters.none()),
                                Set.of(FrozenDeclineSelection.REF, FrozenDimensionChange.REF_V3), uses, policy.terminationPolicyRef())) : Optional.empty(); }
                public Optional<Criterion> criterion(String ref, String version) { return Optional.of(new Criterion(ref, version,
                        PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(CHANGE_TYPE))); }
            };
            skills = new JdbcCampaignSkillInvocationStore(base.jdbc, base.transactions, CLOCK, catalog, contracts);
            var reader = new DimensionChangeResultReader(runs, selections);
            var criteria = new CompletionCriterionRegistry(List.of(DimensionChangeCompletionCriteria.coverage(
                    "combined-explore", "1", "dimension-coverage", reader, auth)));
            candidates = new JdbcCampaignExplorationCandidateStore(base.jdbc, base.transactions, CLOCK, runs, steps, models, catalog, contracts,
                    criteria, auth, new DimensionChangeArtifactBoundary(reader));
            declineArguments = FrozenCampaignRun.encode(Map.of("inputBindings", Map.of("scope", PlanBinding.input("scope"),
                    "scopeArtifact", PlanBinding.artifact(scopeArtifact), "periods", PlanBinding.input("periods"),
                    "definition", PlanBinding.input("selectionDefinition")), "parameters", Map.of("metric", "PV")));
        }
        private Map<String, Object> descriptor(String name, String version) throws Exception {
            var value = new LinkedHashMap<String, Object>(); value.put("schemaVersion", name + "-definition/v" + version);
            value.put("periodsRef", PAIR); value.put("gid", "group-a");
            for (int i = 0; i < 2; i++) { var period = PERIODS.get(i); value.put(i == 0 ? "baseline" : "target",
                    Map.of("periodsRef", period.periodsRef(), "startDate", period.startDate(), "endDate", period.endDate(), "timeZone", period.timeZone())); }
            String digest = RunPinnedSkills.contentDigest(new SkillScanner().loadSkill(approvedRoot.resolve(name + "/" + version), "backend-test"));
            value.put("skillPin", Map.of("name", name, "version", version, "relativeDirectory", name + "/" + version, "sha256", digest)); return value;
        }
        private String publishScope() throws Exception {
            var action = new ActionSpec("enumeration", "enumeration", "TOOL", "group_members", "1", "{}");
            var definition = new CampaignScopeStore.Definition("native-dimension-scope", action, "group-a", Instant.ofEpochMilli(EXPIRY));
            base.scopes.prepare(token, definition);
            var request = new GroupMembersPage.Request("group-a", null, null);
            var child = new ChildSpec("authority-page", action.actionId(), ChildMode.SYNC, "authority-request",
                    new WireRequest("POST", AgentAuthorityClient.GROUP_MEMBERS_PATH, JSON.writeValueAsString(request.asMap())));
            runs.prepareChild(token, child); var dispatch = runs.beginDispatch(token, child.childId());
            try { return base.scopes.commitPage(dispatch, definition.collectionId(), new GroupMembersPage(GroupMembersPage.SCHEMA,
                    OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), "group-a", VERSION, null, List.of(1L, 2L), null)).artifactId(); }
            finally { runs.callbackExited(dispatch); }
        }
        boolean inputAllowed(Caller caller, TypeRef type, Object value) { return allowed.get() && OWNER.equals(caller)
                && ("ScopeRef".equals(type.name()) ? scopeRef.equals(value) : "PeriodsRef".equals(type.name()) && PAIR.equals(value)); }
        boolean queryAllowed(AgentPrincipal principal, String scope, String period, Map<String, Object> request) {
            if (!allowed.get() || !PRINCIPAL.equals(principal) || !Set.of("baseline", "target").contains(period)
                    || !"group-a".equals(request.get("gid")) || !(request.get("scope") instanceof Map<?, ?> raw)) return false;
            FrozenQueryScope selected = FrozenQueryScope.fromMap(raw);
            return scope.equals(selected.parentScopeRef()) && VERSION.equals(selected.enumerationVersion())
                    && ("LINK_METRICS".equals(request.get("queryKind")) ? scopeRef.equals(scope) && selected.linkIds().equals(List.of(1L, 2L))
                    : "DIMENSION_BREAKDOWN".equals(request.get("queryKind")) && !scopeRef.equals(scope) && selected.linkIds().equals(List.of(1L))
                            && DIMENSIONS.equals(request.get("dimensions")) && List.of().equals(request.get("filters")));
        }
        DeclineSelectionCall decline() { return new DeclineSelectionCall(base.jdbc, token.definition(), STEP, PRINCIPAL, approvedRoot, runs, steps,
                calls, skills, base.scopes, base.results, selections, gateway, catalog, contracts, models, this::inputAllowed, auth, this::queryAllowed); }
        DimensionChangeCall dimension() { return new DimensionChangeCall(base.jdbc, token.definition(), STEP, PRINCIPAL, approvedRoot, runs, steps,
                calls, skills, selections, base.results, gateway, catalog, contracts, models, this::inputAllowed, auth, this::queryAllowed); }
        Runtime runtime() throws Exception {
            if (productionFactory) return productionRuntime();
            RunToken current = token;
            var policy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { assertEquals(scopeRef, inputs.value("scope")); assertEquals(PAIR, inputs.value("periods")); }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) { assertEquals(Set.of("dimensionChanges"), outputs.keySet()); }
            };
            var execute = new PersistentExplorationExecutor((step, inputs, permit, authorized) -> {
                var select = new DeclineSelectionExplorationSkill(decline()); var drill = new DimensionChangeExplorationSkill(dimension());
                var methods = List.of(DeclineSelectionExplorationSkill.definition(), DimensionChangeExplorationSkill.definition());
                List<ModelInvocationRegistry.ToolDefinition> tools = new ArrayList<>();
                for (var method : methods) tools.add(new ModelInvocationRegistry.ToolDefinition(method.name(), method.description(), JSON.readTree(method.inputSchema())));
                var configuration = new JdbcExplorationLedger.ModelConfiguration("scripted-model", "1", CONFIG, null, tools,
                        Map.of("scope", runs.inspectArtifact(OWNER, scopeArtifact, auth)), Instant.ofEpochMilli(EXPIRY));
                var projection = combinedProjection(current);
                var ledger = new JdbcExplorationLedger(base.jdbc, base.transactions, CLOCK, runs, steps, calls, permit, models, configuration,
                        Map.of(FrozenDeclineSelection.REF.name(), FrozenDeclineSelection.REF, FrozenDimensionChange.REF_V3.name(), FrozenDimensionChange.REF_V3),
                        auth, ExplorationBudgetPolicy.defaults(), projection, skills, candidates);
                var adapter = new NativeExplorationAdapter(ledger.identity(), ledger, model, List.of(select.registration(), drill.registration()),
                        new MemorySaver(), Runnable::run, new NativeExplorationAdapter.Limits(0, 4096, 32768, 32768, 8, Duration.ofSeconds(10)), ledger);
                return new PersistentExplorationExecutor.Session(ledger, adapter, PROMPT, (owner, id, version) -> {
                    CallRecord call = calls.call(owner.runToken(), id).orElseThrow();
                    if (FrozenDeclineSelection.REF.equals(call.spec().executor())) decline().continueInvocation(owner, id, version);
                    else dimension().continueInvocation(owner, id, version);
                    assertFalse(calls.call(owner.runToken(), id).orElseThrow().callbackActive());
                });
            });
            var driver = new PersistentPlanDriver(current, runs, steps, catalog, contracts, List.of(),
                    (caller, inputs) -> allowed.get() && OWNER.equals(caller), auth, this::inputAllowed,
                    List.of(new PersistentPlanDriver.ReactExecutor("combined-explore", "1", policy, execute)), candidates);
            return new Runtime(driver, driver.compile(new MemorySaver()));
        }
        Runtime productionRuntime() throws Exception {
            var scope = new com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope();
            var saver = new MemorySaver();
            var factory = new CampaignExplorationRuntimeFactory(base.jdbc, base.transactions, CLOCK, runs, steps, calls,
                    base.results, candidates, skills, base.scopes, selections, approvedRoot, gateway, models, model, saver,
                    Runnable::run, new CampaignExplorationRuntimeFactory.Settings("scripted-model", "1", CONFIG,
                        new NativeExplorationAdapter.Limits(0, 65536, 32768, 32768, 8, Duration.ofSeconds(10)),
                        ExplorationBudgetPolicy.defaults(), ExplorationRepeatPolicy.disabled()));
            var prepared = factory.open(token, PRINCIPAL, catalog, contracts, this::inputAllowed, auth, this::queryAllowed,
                    scope, Instant.ofEpochMilli(EXPIRY));
            assertEquals(1, prepared.registrations().size());
            var driver = new PersistentPlanDriver(token, runs, steps, catalog, contracts, List.of(),
                    (caller, inputs) -> allowed.get() && OWNER.equals(caller), auth, this::inputAllowed,
                    prepared.registrations(), candidates, scope);
            return new Runtime(driver, driver.compile(saver));
        }
        ExplorationArtifactProjection combinedProjection(RunToken run) {
            var selection = new DeclineSelectionArtifactProjection(runs, selections);
            var dimension = new DimensionChangeArtifactProjection(runs, selections, run);
            return new ExplorationArtifactProjection() {
                public String configurationId() { return CampaignRunStore.sha256(selection.configurationId() + ":" + dimension.configurationId()); }
                public Map<String, Object> project(Caller caller, ArtifactMetadata expected, ArtifactAuthorizer authorizer) {
                    var value = selection.project(caller, expected, authorizer);
                    return value.isEmpty() ? dimension.project(caller, expected, authorizer) : value;
                }
            };
        }
        void receiveAndResume(String executor) throws Exception {
            CallRecord call = call(executor); var pending = children().stream().filter(child -> child.spec().mode() == ChildMode.ASYNC
                    && call.spec().actionId().equals(child.spec().actionId()) && child.state() == ChildState.WAITING).toList();
            assertEquals(2, pending.size()); reopen();
            for (ChildRecord child : pending) {
                var target = "decline_selection".equals(executor) ? decline().resultTargets(token).get(child.spec().childId()) : dimension().resultTargets(token).get(child.spec().childId());
                var result = new StatisticsJobResultReceiver(runs, base.results, gateway, CLOCK, 1).receive(token, child.spec().childId(), PRINCIPAL, target,
                        () -> "decline_selection".equals(executor) ? decline().reauthorize(token, child.spec().childId()) : dimension().reauthorize(token, child.spec().childId()));
                assertEquals(StatisticsJobResultReceiver.Outcome.READY, result.outcome(), result.code());
                assertEquals(child.spec(), runs.child(token, child.spec().childId()).orElseThrow().spec());
            }
            var runtime = runtime(); runtime.driver().refreshWaiting(); assertEquals(StepStatus.READY, step().status());
            assertEquals(1, runtime.graph().advance().advancedSteps());
        }
        void reopen() { exited(); token = steps.acquireRun(token); }
        StepRecord step() { return steps.step(token, STEP).orElseThrow(); }
        List<ChildRecord> children() { return runs.children(token); }
        CallRecord call(String executor) {
            var ids = base.jdbc.query("SELECT c.call_id FROM campaign_exploration_call c JOIN campaign_action_ledger a "
                    + "ON a.run_id=c.run_id AND a.revision=c.revision AND a.action_id=c.action_id WHERE c.run_id=? AND a.executor_name=?",
                    (rs, row) -> rs.getString(1), RUN, executor);
            assertEquals(1, ids.size()); return calls.call(token, ids.get(0)).orElseThrow();
        }
        InvocationRecord completion(String executor) { return skills.readCompletion(token, call(executor).spec().callId(), auth); }
        ArtifactMetadata artifactOfType(String type) {
            var ids = base.jdbc.query("SELECT artifact_id FROM campaign_artifact WHERE run_id=? AND artifact_type=?", (rs, row) -> rs.getString(1), RUN, type);
            assertEquals(1, ids.size()); return runs.inspectArtifact(OWNER, ids.get(0), auth);
        }
        int artifacts(String type) { return base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE run_id=? AND artifact_type=?", Integer.class, RUN, type); }
        int callsCount() { return base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call WHERE run_id=?", Integer.class, RUN); }
        void exited() { for (String table : List.of("campaign_child_ledger", "campaign_step_ledger", "campaign_exploration_call"))
            assertEquals(0, base.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE callback_active=TRUE", Integer.class)); }
    }

    private static final class Model implements ChatModel {
        final Fixture f; final AtomicInteger count = new AtomicInteger(); boolean selectionConsumedWhileStepRunning;
        Model(Fixture f) { this.f = f; }
        @Override public ChatResponse call(Prompt prompt) {
            int turn = count.incrementAndGet();
            if (turn == 1) return tool(SELECT_CALL, FrozenDeclineSelection.REF.name(), f.declineArguments);
            if (turn == 2) {
                assertEquals(StepStatus.RUNNING, f.step().status()); selectionConsumedWhileStepRunning = true;
                InvocationRecord pair = f.completion("decline_selection");
                JsonNode ready = ready(prompt, SELECT_CALL, f.call("decline_selection"));
                assertEquals(pair.completionId(), ready.path("completionId").asText());
                assertEquals("OBSERVED_ONLY", ready.path("evidence").path("selectedEntities").path("quality").path("interpretation").asText());
                f.selectedMetadata = f.runs.inspectArtifact(OWNER, pair.outputs().get("selectedEntities").artifactId(), f.auth);
                String arguments = FrozenCampaignRun.encode(Map.of("inputBindings", Map.of("periods", PlanBinding.input("periods"),
                        "definition", PlanBinding.input("dimensionDefinition"), "selectedEntities", PlanBinding.artifact(pair.outputs().get("selectedEntities").artifactId()),
                        "selectionEvidence", PlanBinding.artifact(pair.outputs().get("selectionEvidence").artifactId())), "parameters", Map.of()));
                if (f.mode == Mode.REVOKED) f.allowed.set(false);
                return tool(DIMENSION_CALL, FrozenDimensionChange.REF_V3.name(), arguments);
            }
            assertEquals(3, turn, "Every logical MODEL response must be reused after reopen");
            InvocationRecord result = f.completion("dimension_change");
            JsonNode observed = ready(prompt, DIMENSION_CALL, f.call("dimension_change"));
            JsonNode evidence = observed.path("evidence").path("dimensionChanges");
            assertEquals("dimension-change-artifact-projection/v1", evidence.path("schemaVersion").asText());
            assertEquals("OBSERVED_ONLY", evidence.path("quality").path("interpretation").asText());
            assertEquals("UNVERIFIED", evidence.path("quality").path("collectionCompleteness").asText());
            assertEquals(f.scopeRef, evidence.path("scope").path("sourceScopeRef").asText());
            if (f.mode == Mode.EMPTY) {
                assertEquals("NOT_APPLICABLE", evidence.path("coverage").path("evidenceDisposition").asText());
                assertEquals(0, evidence.path("preview").path("returnedRows").asInt());
            } else {
                assertEquals(2, evidence.path("preview").path("returnedRows").asInt());
                int sumBaselineUv = 0; long baselinePv = 0, targetPv = 0; Set<String> devices = new HashSet<>();
                for (JsonNode entry : evidence.path("preview").path("rows")) {
                    assertEquals("SHARD_COHORT", entry.path("analysisUnit").asText());
                    assertEquals("UNKNOWN", entry.path("quality").path("baseline").path("collectionQuality").path("status").asText());
                    assertEquals("UNKNOWN", entry.path("quality").path("target").path("collectionQuality").path("status").asText());
                    assertEquals(1, entry.path("cohortSummary").path("baseline").path("uv").asLong());
                    sumBaselineUv += entry.path("row").path("baseline").path("uv").asInt();
                    JsonNode row = entry.path("row");
                    baselinePv += row.path("baseline").path("pv").asLong(); targetPv += row.path("target").path("pv").asLong();
                    String device = row.path("dimensions").path("device").path("value").asText(); devices.add(device);
                    if ("Mobile".equals(device)) {
                        assertEquals("浙江", row.path("dimensions").path("province").path("value").asText());
                        assertEquals(-5, row.path("pvDelta").asLong());
                    } else {
                        assertEquals("UNKNOWN", row.path("dimensions").path("province").path("state").asText());
                        assertTrue(row.path("dimensions").path("province").path("value").isNull());
                        assertEquals(-2, row.path("pvDelta").asLong());
                    }
                }
                assertEquals(Set.of("Mobile", "Desktop"), devices); assertEquals(10, baselinePv); assertEquals(3, targetPv);
                assertEquals(2, sumBaselineUv, "Joint distinct counts overlap; the preserved cohort summary must not be their sum");
                assertTrue(evidence.path("limitations").toString().contains("COHORT_UV_UIP_MUST_NOT_BE_SUMMED"));
                assertTrue(evidence.toString().contains("OBSERVED_ONLY"));
            }
            ArtifactRef ref = result.outputs().get("dimensionChanges");
            String text = new ExplorationCandidate(ExplorationCandidate.SCHEMA_VERSION, ExplorationCandidate.Kind.COMPLETE,
                    "Observed dimensional differences are available; source collection quality is unverified and no cause is inferred.",
                    List.of(ref.artifactId()), Map.of("dimensionChanges", PlanBinding.artifact(ref.artifactId())), null, null, null).encode();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
        private JsonNode ready(Prompt prompt, String toolId, CallRecord call) {
            var toolResponses = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream()).filter(response -> toolId.equals(response.id())).toList();
            assertEquals(1, toolResponses.size()); JsonNode reply = tree(toolResponses.get(0).responseData());
            if ("READY".equals(reply.path("status").asText())) return reply;
            assertEquals("PENDING", reply.path("status").asText()); assertEquals(call.spec().callId(), reply.path("skillCallId").asText());
            var ready = prompt.getInstructions().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .map(UserMessage::getText).filter(value -> value.startsWith("{")).map(NativeDimensionChangeSkillTest::tree)
                    .filter(value -> "trusted_action_observation".equals(value.path("type").asText())
                            && call.spec().callId().equals(value.path("skillCallId").asText())).toList();
            assertEquals(1, ready.size()); assertEquals("READY", ready.get(0).path("status").asText()); return ready.get(0);
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("scripted-model").build(); }
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final Fixture f; final Map<String, Map<String, Object>> accepted = new LinkedHashMap<>(); int submits, pageReads, recoveries;
        Gateway(Fixture f) { this.f = f; }
        long submitted(String kind) { return accepted.values().stream().filter(value -> kind.equals(value.get("queryKind"))).count(); }
        public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy GET"); }
        public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy POST"); }
        public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) { throw new AssertionError("No mutable group submission"); }
        public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits++; assertEquals(PRINCIPAL, context.principal()); assertEquals("native-dimension-session", context.sessionId());
            assertEquals(request, context.arguments());
            ChildRecord child = f.children().stream().filter(value -> value.spec().requestId().equals(request.get("requestId"))).findFirst().orElseThrow();
            assertEquals(ChildState.DISPATCHING, child.state()); assertTrue(child.callbackActive());
            assertEquals(FrozenCampaignRun.encode(request), child.spec().wire().bodyJson());
            assertEquals(StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, child.spec().wire().path());
            assertTrue(accepted.values().stream().noneMatch(value -> value.get("requestId").equals(request.get("requestId"))));
            String job = "job-" + (accepted.size() + 1); accepted.put(job, Map.copyOf(request));
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED"));
        }
        public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) { recoveries++; throw new AssertionError("Known jobs must be reused"); }
        public ToolResult readStatisticsJob(ToolContext context, String job) {
            assertEquals(PRINCIPAL, context.principal()); assertTrue(accepted.containsKey(job));
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", 2, "pageCount", 1, "expiresAt", EXPIRY));
        }
        public ToolResult readStatisticsJobPage(ToolContext context, String job, int index, int size) {
            pageReads++; assertEquals(0, index); assertEquals(500, size); assertEquals(PRINCIPAL, context.principal());
            var request = accepted.get(job); var scope = FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope"));
            boolean baseline = "2026-09-01".equals(request.get("startDate")); var period = PERIODS.get(baseline ? 0 : 1);
            boolean dimension = "DIMENSION_BREAKDOWN".equals(request.get("queryKind"));
            List<Map<String, Object>> rows = new ArrayList<>(); long total;
            if (dimension) {
                assertEquals(List.of(1L), scope.linkIds()); total = baseline ? 10 : 3;
                rows.add(bucket("KNOWN", "浙江", "Mobile", baseline ? 6 : 1, total));
                rows.add(bucket("UNKNOWN", null, "Desktop", baseline ? 4 : 2, total));
            } else {
                assertEquals(List.of(1L, 2L), scope.linkIds()); total = 0;
                for (long id : scope.linkIds()) { long pv = id == 1 ? baseline || f.mode == Mode.EMPTY ? 10 : 3 : baseline || f.mode == Mode.EMPTY ? 2 : 4;
                    var row = new LinkedHashMap<>(counts(pv, period)); row.put("linkId", id); rows.add(row); total += pv; }
            }
            var meta = new LinkedHashMap<String, Object>(); meta.put("snapshotId", job); meta.put("queryKind", request.get("queryKind")); meta.put("gid", "group-a");
            meta.put("linkIds", scope.linkIds()); meta.put("scopeProof", scope.proof("b".repeat(64))); meta.put("groupScopeComplete", false);
            meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1"); String hash = CampaignRunStore.sha256(job);
            meta.put("sourceCut", Map.of("manifestSelectionHash", hash)); meta.put("manifestVersion", Map.of("selectionHash", hash));
            meta.put("snapshotCreatedAt", CLOCK.millis()); meta.put("snapshotExpiresAt", EXPIRY);
            meta.put("requestedStart", period.startInclusive()); meta.put("requestedEnd", period.endExclusive()); meta.put("effectiveEnd", period.endExclusive());
            meta.put("businessTimezone", "Asia/Shanghai"); meta.put("pageIndex", 0); meta.put("nextPageIndex", null); meta.put("totalRows", rows.size());
            meta.put("aggregationLevel", dimension ? "DIMENSION_BREAKDOWN" : "LINK_WINDOW"); meta.put("availability", "AVAILABLE");
            meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("provisional", false);
            meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of());
            meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                    "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"), "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            var summary = new LinkedHashMap<>(counts(total, period));
            if (dimension) {
                summary.remove("denied"); summary.put("ratioDenominator", total);
                long known = baseline ? 6 : 1, unknown = total - known;
                var quality = Map.of("province", quality(known, unknown, "CN_PROVINCE"), "device", quality(total, 0, "DEVICE"));
                summary.put("dimensionQuality", quality); meta.put("dimensionQuality", quality);
                meta.put("dimensions", DIMENSIONS); meta.put("filters", List.of()); meta.put("dimensionQualityScope", "FILTERED_FULL_WINDOW");
                meta.put("resultComplete", true); meta.put("truncated", false);
            }
            return ToolResult.success(Map.of("items", rows, "metrics", Map.of("requested", summary), "meta", meta));
        }
    }
    private static Map<String, Object> counts(long pv, CampaignParentCoverage.Period period) { return Map.of("pv", pv, "uv", 1, "uip", 1, "denied", 0,
            "window", "requested", "startInclusive", period.startInclusive(), "endExclusive", period.endExclusive()); }
    private static Map<String, Object> bucket(String state, String province, String device, long pv, long total) {
        Map<String, Object> cell = new LinkedHashMap<>(); cell.put("state", state); cell.put("value", province);
        return Map.of("dimensions", Map.of("province", cell, "device", Map.of("state", "KNOWN", "value", device)), "pv", pv, "uv", 1, "uip", 1, "pvRatio", (double) pv / total);
    }
    private static Map<String, Object> quality(long known, long unknown, String semantic) { return Map.of("status", "AVAILABLE", "knownCount", known,
            "unknownCount", unknown, "eligibleCount", known + unknown, "notApplicableCount", 0, "coverage", (double) known / (known + unknown), "semantic", semantic, "reasonCounts", Map.of()); }
    private static ChatResponse tool(String id, String name, String arguments) { return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments))).build()))); }
    private static JsonNode tree(String text) { try { return JSON.readTree(text); } catch (Exception invalid) { throw new IllegalStateException(invalid); } }
}
