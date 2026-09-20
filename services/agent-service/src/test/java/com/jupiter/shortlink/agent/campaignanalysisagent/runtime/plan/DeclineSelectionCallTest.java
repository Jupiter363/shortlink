package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.skills.registry.filesystem.SkillScanner;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Actual authorized scope, MODEL/CALL, job reception and shared LOCAL publication; no native runner. */
@Timeout(30)
class DeclineSelectionCallTest {
    static final String RUN = "decline-call-run", STEP = "explore", PAIR = "comparison-periods";
    static final String CONFIGURATION = "c".repeat(64);
    static final AgentPrincipal PRINCIPAL = new AgentPrincipal(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), false);
    static final Set<String> OUTPUTS = Set.of("selectedEntities", "selectionEvidence");

    @Test
    void actualSkillCallWaitsForBothOriginalJobsThenPublishesReadableDeclinesWithoutResubmission() throws Exception {
        var f = new CallFixture();
        CallPermit continued = null;
        StepPermit resumedStep = null;
        boolean initialCallExited = false, initialStepExited = false;
        try {
            var adapter = f.adapter();
            var waiting = adapter.execute(f.initialCall);
            assertEquals(CampaignSkillInvocationStore.State.WAITING, waiting.state());
            assertTrue(waiting.outputs().isEmpty()); assertNull(waiting.completionId());
            assertEquals(2, f.gateway.submits); assertEquals(0, f.gateway.pageReads);
            assertTrue(f.calls.call(f.token, f.callSpec.callId()).orElseThrow().callbackActive());
            List<ChildRecord> originalJobs = f.children(f.token).stream().filter(c -> c.spec().mode() == ChildMode.ASYNC).toList();
            assertEquals(2, originalJobs.size());
            assertTrue(originalJobs.stream().allMatch(c -> c.state() == ChildState.WAITING && !c.callbackActive()
                    && f.callSpec.actionId().equals(c.spec().actionId()) && c.jobId() != null));
            assertEquals(0, f.finalCount());
            f.calls.callbackExited(f.initialCall); initialCallExited = true;
            f.steps.settle(f.step, StepStatus.WAITING, Map.of(), "awaiting-skill-inputs", f.auth);
            f.steps.callbackExited(f.step); initialStepExited = true;

            RunToken writer = f.steps.acquireRun(f.token);
            var reopened = f.adapter();
            Map<String, StatisticsJobResultReceiver.Target> targets = reopened.resultTargets(writer);
            assertEquals(2, targets.size());
            var receiver = new StatisticsJobResultReceiver(f.runs, f.base.results, f.gateway, CLOCK, 1);
            var baseline = originalJobs.stream().filter(c -> f.period(c).equals("baseline")).findFirst().orElseThrow();
            var target = originalJobs.stream().filter(c -> f.period(c).equals("target")).findFirst().orElseThrow();
            assertEquals(StatisticsJobResultReceiver.Outcome.READY,
                    receiver.receive(writer, baseline.spec().childId(), PRINCIPAL, targets.get(baseline.spec().childId()),
                            () -> reopened.reauthorize(writer, baseline.spec().childId())).outcome());
            assertEquals(StepStatus.WAITING, f.steps.refreshWaiting(writer, STEP).status());
            assertThrows(IllegalStateException.class, () -> f.steps.beginStep(writer, STEP));
            assertEquals(waiting, f.invocations.invocation(writer, f.callSpec.callId()).orElseThrow());
            assertEquals(0, f.finalCount(), "A single period is not a complete comparison");
            assertEquals(2, f.gateway.submits);

            f.allowed.set(false);
            assertFalse(reopened.reauthorize(writer, target.spec().childId()));
            int beforeDeniedRead = f.gateway.reads();
            assertEquals(StatisticsJobResultReceiver.Outcome.STOPPED,
                    receiver.receive(writer, target.spec().childId(), PRINCIPAL, targets.get(target.spec().childId()),
                            () -> reopened.reauthorize(writer, target.spec().childId())).outcome());
            assertEquals(beforeDeniedRead, f.gateway.reads());
            f.allowed.set(true);
            assertEquals(StatisticsJobResultReceiver.Outcome.READY,
                    receiver.receive(writer, target.spec().childId(), PRINCIPAL, targets.get(target.spec().childId()),
                            () -> reopened.reauthorize(writer, target.spec().childId())).outcome());
            assertEquals(StepStatus.READY, f.steps.refreshWaiting(writer, STEP).status());
            resumedStep = f.steps.beginStep(writer, STEP);
            continued = reopened.beginContinuation(resumedStep, f.callSpec.callId(), waiting.rowVersion());
            assertEquals(f.initialCall.callId(), continued.callId());
            assertEquals(f.initialCall.actionId(), continued.actionId()); assertEquals(2, continued.attemptVersion());
            var completed = reopened.execute(continued);
            assertEquals(CampaignSkillInvocationStore.State.COMPLETED, completed.state());
            assertEquals(OUTPUTS, completed.outputs().keySet()); assertNotNull(completed.completionId());
            assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads); assertEquals(0, f.gateway.recoveries);
            assertEquals(1, f.count("campaign_model_response", RUN));
            assertEquals(1, f.count("campaign_exploration_call", RUN));
            assertEquals(2, f.finalCount());
            for (ChildRecord original : originalJobs) {
                ChildRecord ready = f.runs.child(writer, original.spec().childId()).orElseThrow();
                assertEquals(original.spec(), ready.spec()); assertEquals(original.jobId(), ready.jobId());
                assertEquals(ChildState.READY, ready.state());
                Artifact source = f.runs.readArtifact(OWNER, ready.artifactId(), f.auth);
                assertEquals(f.callSpec.actionId(), source.metadata().actionId());
                assertEquals("UNKNOWN", JSON.readTree(f.base.results.readPage(OWNER, ready.artifactId(), 0, f.auth))
                        .path("meta").path("collectionQuality").path("status").asText());
            }
            List<ChildRecord> locals = f.children(writer).stream().filter(c -> c.spec().mode() == ChildMode.LOCAL).toList();
            assertEquals(2, locals.size());
            assertTrue(locals.stream().allMatch(c -> c.state() == ChildState.READY && c.attemptVersion() == 1
                    && f.callSpec.actionId().equals(c.spec().actionId())));
            var index = new JdbcCampaignDeclineSelectionStore(f.base.jdbc, f.base.transactions, CLOCK, f.runs);
            ArtifactRef selectedRef = completed.outputs().get("selectedEntities"), evidenceRef = completed.outputs().get("selectionEvidence");
            var selected = index.readSelectedPage(OWNER, selectedRef.artifactId(), null, 500, f.auth);
            assertEquals(1, selected.rows().size()); assertNull(selected.nextCursor());
            var decline = selected.rows().get(0);
            assertEquals(1, decline.linkId()); assertEquals(10, decline.baseline()); assertEquals(3, decline.target());
            assertEquals(BigInteger.valueOf(-7), decline.delta());
            assertEquals(CampaignLinkComparability.Explanation.OBSERVED_ONLY, decline.explanation());
            assertTrue(decline.reasonCodes().contains("BASELINE_COLLECTION_COMPLETENESS_UNVERIFIED"));
            assertTrue(decline.reasonCodes().contains("TARGET_COLLECTION_COMPLETENESS_UNVERIFIED"));
            var evidence = index.readEvidencePage(OWNER, evidenceRef.artifactId(), null, 500, f.auth);
            assertEquals(List.of(1L, 2L), evidence.rows().stream().map(CampaignLinkComparability.Result::linkId).toList());
            assertEquals(BigInteger.valueOf(2), evidence.rows().get(1).delta()); assertNull(evidence.nextCursor());
            for (ArtifactRef ref : completed.outputs().values()) {
                Artifact actual = f.runs.readArtifact(OWNER, ref.artifactId(), f.auth);
                assertEquals(ref, actual.metadata().ref()); assertEquals(RUN, actual.metadata().runId());
                assertEquals(f.callSpec.actionId(), actual.metadata().actionId());
                assertEquals(Instant.ofEpochMilli(EXPIRY), ref.expiresAt());
                assertEquals(ref.payloadHash(), CampaignRunStore.sha256(actual.payloadJson()));
                assertEquals(f.scopeRef, ref.scopeRef()); assertEquals(PAIR, ref.periodsRef());
            }
            assertEquals(completed, f.invocations.invocation(writer, continued.callId()).orElseThrow());
            assertTrue(f.calls.call(writer, continued.callId()).orElseThrow().callbackActive(), "execute does not impersonate finally");

            // A revoked CALL cannot reuse the old token-only index mutation routes, even after a valid seal.
            ChildRecord pageChild = locals.stream().filter(c -> c.spec().localInvocation().outputs().containsKey("selectionChain")).findFirst().orElseThrow();
            ChildRecord finalChild = locals.stream().filter(c -> c.spec().localInvocation().outputs().containsKey("selectedEntities")).findFirst().orElseThrow();
            String collectionId = JSON.readTree(f.runs.readArtifact(OWNER, selectedRef.artifactId(), f.auth).payloadJson()).path("collectionId").asText();
            CallPermit finalPermit = continued;
            f.calls.revoke(finalPermit, "EXECUTION_UNRESOLVED");
            assertThrows(IllegalStateException.class, () -> index.append(writer, pageChild.spec().childId(), f.auth));
            assertThrows(IllegalStateException.class, () -> index.seal(writer, collectionId, finalChild.spec().childId(), f.auth));
            assertThrows(IllegalStateException.class, () -> index.append(finalPermit, pageChild.spec().childId(), f.auth));
            assertThrows(IllegalStateException.class, () -> index.seal(finalPermit, collectionId, finalChild.spec().childId(), f.auth));
            assertEquals(selected, index.readSelectedPage(OWNER, selectedRef.artifactId(), null, 500, f.auth));
            assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);
        } finally {
            f.allowed.set(true);
            if (continued != null) f.calls.callbackExited(continued);
            if (resumedStep != null) f.steps.callbackExited(resumedStep);
            if (!initialCallExited) f.calls.callbackExited(f.initialCall);
            if (!initialStepExited) f.steps.callbackExited(f.step);
        }
        for (String table : List.of("campaign_child_ledger", "campaign_step_ledger", "campaign_exploration_call"))
            assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE callback_active=TRUE", Integer.class));
    }

    static final class CallFixture {
        final CampaignParentCoverageTest.Fixture base = new CampaignParentCoverageTest.Fixture();
        final CampaignRunStore runs = base.runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final CampaignSkillInvocationStore invocations;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final ArtifactAuthorizer auth = (caller, metadata) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(metadata.owner());
        final Gateway gateway = new Gateway(this);
        final Path approvedRoot;
        final String scopeRef, scopeArtifact;
        final PlanSpec.Step planStep;
        final RunToken token;
        final StepPermit step;
        final CallSpec callSpec;
        final CallPermit initialCall;
        final String arguments;
        final CapabilityCatalog catalog;
        final ArtifactContractRegistry contracts = new ArtifactContractRegistry(DeclineSelectionSkill.artifactContracts());
        final ModelInvocationRegistry models = new ModelInvocationRegistry(List.of(
                new ModelInvocationRegistry.Contract("scripted-model", "1", CONFIGURATION, ignored -> true)));

        CallFixture() throws Exception { this(true); }

        CallFixture(boolean prepareInitialModelCall) throws Exception {
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_3__campaign_submission_deferral.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql"),
                    new ClassPathResource("sql/migration/V20260920_12__campaign_skill_invocation.sql")).execute(base.jdbc.getDataSource());
            steps = new JdbcCampaignStepStore(base.jdbc, base.transactions, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(base.jdbc, base.transactions, CLOCK);
            approvedRoot = new ClassPathResource("campaign-skills").getFile().toPath();
            String digest = RunPinnedSkills.contentDigest(new SkillScanner().loadSkill(approvedRoot.resolve("decline-selection/1"), "backend-test"));
            // This only derives a frozen reference; a real same-run authority receipt is published below.
            scopeRef = FrozenCampaignScope.freeze(PRINCIPAL, "group-a", List.of(new FrozenCampaignScope.AuthorityPage(
                    PRINCIPAL, "group-a", null, VERSION, List.of(1L, 2L), null))).scopeRef();
            var descriptor = new LinkedHashMap<String, Object>();
            descriptor.put("schemaVersion", FrozenDeclineSelection.SCHEMA); descriptor.put("scopeRef", scopeRef);
            descriptor.put("periodsRef", PAIR); descriptor.put("gid", "group-a");
            for (int i = 0; i < PERIODS.size(); i++) {
                var period = PERIODS.get(i);
                descriptor.put(i == 0 ? "baseline" : "target", Map.of("periodsRef", period.periodsRef(), "startDate", period.startDate(),
                        "endDate", period.endDate(), "timeZone", period.timeZone()));
            }
            descriptor.put("skillPin", Map.of("name", "decline-selection", "version", "1", "relativeDirectory", "decline-selection/1", "sha256", digest));
            var policy = new PlanSpec.ExplorationPolicy("decline-explore", "1", List.of(FrozenDeclineSelection.REF), scopeRef, PAIR,
                    List.of(new PlanSpec.CriterionUse("supported-evidence", Map.of())), "one-skill");
            planStep = new PlanSpec.Step(STEP, List.of("goal"), PlanSpec.ExecutionMode.REACT, null, policy, List.of(),
                    Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods"), "definition", PlanBinding.input("definition")),
                    Map.of(), FrozenDeclineSelection.OUTPUT_CONTRACT);
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "decline-call-plan", 1, RUN, "decline-call-inputs",
                    List.of(new PlanSpec.Goal("goal", "Find observed declines", true, "Deliver selected entities and evidence")), List.of(planStep));
            var inputPorts = new LinkedHashMap<>(FrozenDeclineSelection.INPUTS); inputPorts.remove("scopeArtifact");
            var inputs = new FrozenInputSet("decline-call-inputs", RUN, inputPorts,
                    Map.of("scope", scopeRef, "periods", PAIR, "definition", descriptor));
            var frozen = FrozenCampaignRun.freeze(plan, inputs, new PlanningAssessment(plan.planId(), 1, "decline-call-catalog/v1", List.of(), List.of(), List.of()));
            token = steps.acquireRun(runs.createRun(frozen.definition(OWNER, "decline-call-session")));
            scopeArtifact = publishScope();
            assertEquals(scopeRef, base.scopes.inspectPublished(OWNER, scopeArtifact, auth).scopeRef());
            assertEquals(RUN, runs.inspectArtifact(OWNER, scopeArtifact, auth).runId());
            catalog = new CapabilityCatalog() {
                public String version() { return "decline-call-catalog/v1"; }
                public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                    return FrozenDeclineSelection.REF.equals(ref) ? Optional.of(DeclineSelectionSkill.capability()) : Optional.empty();
                }
                public Optional<Policy> policy(String ref, String version) {
                    return "decline-explore".equals(ref) && "1".equals(version) ? Optional.of(new Policy(ref, version,
                            new Signature(inputPorts, FrozenDeclineSelection.OUTPUT_CONTRACT, DeclineSelectionSkill.capability().signature().outputs(), Parameters.none()),
                            Set.of(FrozenDeclineSelection.REF), policy.completionCriteria(), policy.terminationPolicyRef())) : Optional.empty();
                }
                public Optional<Criterion> criterion(String ref, String version) {
                    return Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(),
                            Set.of(new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE), new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE))));
                }
            };
            invocations = new JdbcCampaignSkillInvocationStore(base.jdbc, base.transactions, CLOCK, catalog, contracts);
            steps.initialize(token, List.of(new StepSpec(STEP, FrozenCampaignRun.encode(planStep), List.of(), OUTPUTS, OUTPUTS)));
            step = steps.beginStep(token, STEP);
            arguments = FrozenCampaignRun.encode(Map.of("inputBindings", Map.of(
                    "scope", PlanBinding.input("scope"), "scopeArtifact", PlanBinding.artifact(scopeArtifact),
                    "periods", PlanBinding.input("periods"), "definition", PlanBinding.input("definition")), "parameters", Map.of("metric", "PV")));
            if (!prepareInitialModelCall) {
                callSpec = null; initialCall = null;
                return; // Native test owns the actual model response and CALL admission.
            }
            var identity = ModelInvocationRegistry.identity(token.definition(), STEP, 1);
            String request = "{\"schemaVersion\":\"campaign-model-request/v1\",\"messages\":[{\"role\":\"user\",\"text\":\"Find observed declines\"}],"
                    + "\"tools\":[{\"name\":\"decline_selection\",\"description\":\"Apply approved comparison method\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
            var invocation = new ModelInvocationRegistry.InvocationSpec(identity.invocationId(), 1, "scripted-model", "1", CONFIGURATION,
                    policy.policyRef(), policy.policyVersion(), inputs.inputSetRef(), request,
                    Map.of("scope", runs.inspectArtifact(OWNER, scopeArtifact, auth)), Instant.ofEpochMilli(EXPIRY));
            var approval = models.approve(invocation);
            var action = new ModelInvocationRegistry.ModelActionSpec(identity.actionId(), STEP, invocation.invocationId(), invocation.modelRef(),
                    invocation.modelVersion(), invocation.policyRef(), invocation.policyVersion(), FrozenCampaignRun.encode(planStep));
            var child = new ChildSpec(identity.childId(), identity.actionId(), ChildMode.MODEL, identity.requestId(), null, null, invocation);
            var response = new ModelInvocationRegistry.Response("Use the approved comparison method.",
                    List.of(new ModelInvocationRegistry.ToolCall("select-call", FrozenDeclineSelection.REF.name(), arguments)));
            runs.prepareModelChild(step, action, child, approval, auth);
            var dispatch = runs.beginModelDispatch(step, child.childId(), approval, auth);
            try { runs.publishModelResponse(step, dispatch, approval, response, auth); }
            finally { runs.callbackExited(dispatch); }
            var id = CampaignExplorationCallStore.identity(token.definition(), STEP, child.childId(), "select-call");
            callSpec = new CallSpec(id.callId(), id.actionId(), STEP, child.childId(), CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(response)),
                    "select-call", FrozenDeclineSelection.REF, arguments);
            calls.prepare(step, callSpec, approval, auth); initialCall = calls.beginCall(step, callSpec.callId(), approval, auth);
        }

        String publishScope() throws Exception {
            var action = new ActionSpec("enumeration", "enumeration", "TOOL", "group_members", "1", "{}");
            var definition = new CampaignScopeStore.Definition("decline-call-scope", action, "group-a", Instant.ofEpochMilli(EXPIRY));
            base.scopes.prepare(token, definition);
            var request = new GroupMembersPage.Request("group-a", null, null);
            var child = new ChildSpec("authority-page", action.actionId(), ChildMode.SYNC, "authority-request",
                    new WireRequest("POST", AgentAuthorityClient.GROUP_MEMBERS_PATH, JSON.writeValueAsString(request.asMap())));
            runs.prepareChild(token, child); var permit = runs.beginDispatch(token, child.childId());
            try {
                var published = base.scopes.commitPage(permit, definition.collectionId(), new GroupMembersPage(GroupMembersPage.SCHEMA,
                        OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), "group-a", VERSION, null, List.of(1L, 2L), null));
                assertEquals(CampaignScopeStore.State.PUBLISHED, published.state()); return published.artifactId();
            } finally { runs.callbackExited(permit); }
        }
        DeclineSelectionCall adapter() {
            return new DeclineSelectionCall(base.jdbc, token.definition(), STEP, PRINCIPAL, approvedRoot, runs, steps, calls,
                    invocations, base.scopes, base.results, new JdbcCampaignDeclineSelectionStore(base.jdbc, base.transactions, CLOCK, runs),
                    gateway, catalog, contracts, models,
                    (caller, type, value) -> allowed.get() && OWNER.equals(caller)
                            && ("ScopeRef".equals(type.name()) ? scopeRef.equals(value) : "PeriodsRef".equals(type.name()) && PAIR.equals(value)),
                    auth, (principal, scope, periods, request) -> allowed.get() && PRINCIPAL.equals(principal) && scopeRef.equals(scope)
                            && Set.of("baseline", "target").contains(periods) && "group-a".equals(request.get("gid"))
                            && "LINK_METRICS".equals(request.get("queryKind")) && request.get("scope") instanceof Map<?, ?> selected
                            && FrozenQueryScope.fromMap(selected).linkIds().equals(List.of(1L, 2L)));
        }
        List<ChildRecord> children(RunToken current) { return runs.children(current); }
        String period(ChildRecord child) {
            return "2026-09-01".equals(gateway.accepted.get(child.jobId()).get("startDate")) ? "baseline" : "target";
        }
        int count(String table, String run) { return base.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=?", Integer.class, run); }
        int finalCount() { return base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE run_id=? AND artifact_type IN (?,?)",
                Integer.class, RUN, DeclineSelectionPublisher.SELECTED_TYPE, DeclineSelectionPublisher.EVIDENCE_TYPE); }
    }

    static final class Gateway implements ShortLinkBusinessGateway {
        final CallFixture f;
        final Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
        int submits, recoveries, statuses, pageReads;
        Gateway(CallFixture f) { this.f = f; }
        int reads() { return statuses + pageReads; }
        public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy GET"); }
        public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy POST"); }
        public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) { throw new AssertionError("Frozen members cannot use current-group submission"); }
        public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits++; assertEquals(PRINCIPAL, context.principal()); assertEquals("decline-call-session", context.sessionId());
            assertEquals(request, context.arguments());
            RunToken current = f.runs.loadRun(OWNER, RUN).orElseThrow().token();
            ChildRecord child = f.children(current).stream().filter(c -> c.spec().requestId().equals(request.get("requestId"))).findFirst().orElseThrow();
            assertEquals(ChildState.DISPATCHING, child.state()); assertTrue(child.callbackActive());
            if (f.callSpec != null) assertEquals(f.callSpec.actionId(), child.spec().actionId());
            else assertEquals(1, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call "
                    + "WHERE run_id=? AND revision=? AND step_id=? AND action_id=? AND callback_active=TRUE",
                    Integer.class, RUN, current.definition().revision(), STEP, child.spec().actionId()));
            assertEquals(StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, child.spec().wire().path());
            assertEquals(FrozenCampaignRun.encode(request), child.spec().wire().bodyJson());
            assertTrue(accepted.values().stream().noneMatch(value -> value.get("requestId").equals(request.get("requestId"))));
            String job = "job-" + (accepted.size() + 1); accepted.put(job, Map.copyOf(request));
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED"));
        }
        public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++; throw new AssertionError("A known original job must not be recovered or submitted again");
        }
        public ToolResult readStatisticsJob(ToolContext context, String job) {
            statuses++; assertTrue(accepted.containsKey(job)); assertEquals(PRINCIPAL, context.principal());
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", 2, "pageCount", 1,
                    "expiresAt", EXPIRY, "resultState", "AVAILABLE", "resultReady", true));
        }
        public ToolResult readStatisticsJobPage(ToolContext context, String job, int page, int size) {
            pageReads++; assertEquals(0, page); assertEquals(500, size);
            var request = accepted.get(job); FrozenQueryScope scope = FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope"));
            assertEquals(List.of(1L, 2L), scope.linkIds()); assertEquals(f.scopeRef, scope.parentScopeRef()); assertEquals(VERSION, scope.enumerationVersion());
            boolean baseline = "2026-09-01".equals(request.get("startDate"));
            var period = PERIODS.get(baseline ? 0 : 1);
            List<Map<String, Object>> rows = new ArrayList<>(); long total = 0;
            for (long id : scope.linkIds()) {
                long pv = id == 1 ? baseline ? 10 : 3 : baseline ? 2 : 4; total += pv;
                var row = new LinkedHashMap<>(counts(pv, period)); row.put("linkId", id); rows.add(row);
            }
            var meta = new LinkedHashMap<String, Object>();
            meta.put("snapshotId", job); meta.put("queryKind", "LINK_METRICS"); meta.put("gid", "group-a");
            meta.put("linkIds", scope.linkIds()); meta.put("scopeProof", scope.proof("b".repeat(64))); meta.put("groupScopeComplete", false);
            meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1");
            String hash = CampaignRunStore.sha256(job);
            meta.put("sourceCut", Map.of("manifestSelectionHash", hash)); meta.put("manifestVersion", Map.of("selectionHash", hash));
            meta.put("snapshotCreatedAt", CLOCK.millis()); meta.put("snapshotExpiresAt", EXPIRY);
            meta.put("requestedStart", period.startInclusive()); meta.put("requestedEnd", period.endExclusive());
            meta.put("effectiveEnd", period.endExclusive()); meta.put("businessTimezone", period.timeZone());
            meta.put("pageIndex", 0); meta.put("nextPageIndex", null); meta.put("totalRows", rows.size()); meta.put("aggregationLevel", "LINK_WINDOW");
            meta.put("availability", "AVAILABLE"); meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("provisional", false);
            meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of());
            meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                    "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"), "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            return ToolResult.success(Map.of("items", rows, "metrics", Map.of("requested", counts(total, period)), "meta", meta));
        }
    }
    private static Map<String, Object> counts(long pv, CampaignParentCoverage.Period period) {
        return Map.of("pv", pv, "uv", 1, "uip", 1, "denied", 0, "window", "requested",
                "startInclusive", period.startInclusive(), "endExclusive", period.endExclusive());
    }
}
