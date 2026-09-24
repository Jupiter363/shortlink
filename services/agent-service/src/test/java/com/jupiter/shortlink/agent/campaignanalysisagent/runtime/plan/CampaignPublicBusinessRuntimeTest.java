package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunRequest;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.CampaignPlanConfiguration;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

/** Public raw request, native structured extraction/planning and the actual business Graph, with scripted I/O only. */
@Timeout(90)
class CampaignPublicBusinessRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "alice", 7, false);
    private static final String SESSION = "public-business-session";
    private static final String QUESTION = "找出group-a在2026-09-02相较2026-09-01下降的短链，查看这些短链的省份设备变化；另外查看group-b在2026-09-02的短链数据。";
    private static final String VERSION = "a".repeat(64);
    private static final long EXPIRY = CLOCK.millis() + 3_600_000;
    private static final List<String> DIMENSIONS = List.of("province", "device");

    @Test
    void rawPublicRequestPlansDependentCohortAndIndependentQueryThenResumesOriginalJobsWithoutRepeatingCompletedWork() throws Exception {
        try (var f = new Fixture()) {
            var request = new AgentRunRequest(SESSION, "campaign-analysis", PRINCIPAL.username(), QUESTION,
                    PRINCIPAL, "public-turn", Set.of("campaign-response/v2"), AgentRunRequest.Operation.NEW, null, null);
            var initial = f.service.run(request);
            WorkRef reference = initial.continuation().workRef();
            f.gateway.runId = reference.runId();
            assertThat(f.scheduled).containsExactly(reference);
            assertThat(f.model.calls).hasValue(0);
            assertThat(f.gateway.calls()).isZero();
            assertThat(f.requests.read(reference).state()).isEqualTo("PREPARED");

            StepRecord independent = null;
            boolean independentFinishedFirst = false;
            boolean complete = false;
            for (int advance = 0; advance < 16 && !complete; advance++) {
                try {
                    f.runtime.intake().submit(PRINCIPAL, reference).get(20, TimeUnit.SECONDS);
                } catch (TimeoutException timeout) {
                    System.err.println("Public business advance timed out: ordinal=" + advance
                            + ", submissions=" + f.gateway.submissions + ", pageReads=" + f.gateway.pageReads);
                    for (var thread : java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                        String name = thread.getThreadName().toLowerCase(Locale.ROOT);
                        if (name.contains("campaign") || name.contains("graph") || name.contains("commonpool"))
                            System.err.println(thread);
                    }
                    throw timeout;
                }
                var token = f.runtime.runs().loadRun(OWNER, reference.runId()).orElseThrow().token();
                var standalone = f.runtime.steps().step(token, "standalone").orElseThrow();
                complete = f.runtime.steps().step(token, "dimension").orElseThrow().status() == StepStatus.SUCCEEDED;
                if (standalone.status() == StepStatus.SUCCEEDED) {
                    if (independent == null) independent = standalone;
                    else assertThat(standalone).as("A finished independent step is not reopened on dependency advances").isEqualTo(independent);
                    independentFinishedFirst |= !complete;
                }
            }
            assertThat(complete).isTrue();
            assertThat(independentFinishedFirst).isTrue();
            assertThat(f.requests.read(reference).state()).isEqualTo("ACCEPTED");
            assertThat(f.model.calls).hasValue(2);
            assertThat(f.gateway.submissions).isEqualTo(5);
            assertThat(f.gateway.accepted).hasSize(5);
            assertThat(f.gateway.pageReads).isEqualTo(5);
            assertThat(f.gateway.recoveries).isZero();
            assertThat(f.gateway.polls.values()).allMatch(count -> count >= 2);
            var token = f.runtime.runs().loadRun(OWNER, reference.runId()).orElseThrow().token();
            assertThat(f.runtime.steps().steps(token)).hasSize(4).allMatch(step -> step.status() == StepStatus.SUCCEEDED);
            var frozen = FrozenCampaignRun.read(token.definition());
            assertThat(frozen.plan().goals()).hasSize(2);
            assertThat(frozen.assessment().requirements()).hasSize(4);
            assertThat(frozen.assessment().gaps()).isEmpty();

            var gate = f.runtime.extension().profile().artifactAuthorizer();
            String scopeId = f.runtime.steps().step(token, "collect").orElseThrow().outputs().get("scopeArtifact");
            var scope = f.runtime.runs().readArtifact(OWNER, scopeId, gate);
            var outputs = f.runtime.steps().step(token, "select").orElseThrow().outputs();
            var selections = new JdbcCampaignDeclineSelectionStore(f.jdbc, f.tx, CLOCK, f.runtime.runs());
            var pair = selections.inspectPair(OWNER, outputs.get("selectedEntities"), outputs.get("selectionEvidence"), gate);
            assertThat(pair.scopeArtifact().ref().artifactId()).isEqualTo(scopeId);
            assertThat(pair.selectedCount()).isEqualTo(1);
            var selected = selections.readSelectedPage(OWNER, outputs.get("selectedEntities"), null, 10, gate);
            assertThat(selected.rows()).singleElement().satisfies(row -> {
                assertThat(row.linkId()).isEqualTo(1); assertThat(row.baseline()).isEqualTo(8); assertThat(row.target()).isEqualTo(2);
            });
            for (var wire : f.gateway.accepted.values()) {
                if (!wire.containsKey("scope")) { assertThat(wire.get("gid")).isEqualTo("group-b"); continue; }
                var shard = FrozenQueryScope.fromMap((Map<?, ?>) wire.get("scope"));
                if ("LINK_METRICS".equals(wire.get("queryKind"))) {
                    assertThat(shard.parentScopeRef()).isEqualTo(scope.metadata().ref().scopeRef());
                    assertThat(shard.linkIds()).containsExactly(1L, 2L);
                } else { assertThat(shard.linkIds()).containsExactly(1L); assertThat(wire.get("dimensions")).isEqualTo(DIMENSIONS); }
            }
            var dimension = f.runtime.runs().readArtifact(OWNER,
                    f.runtime.steps().step(token, "dimension").orElseThrow().outputs().get("dimensionChanges"), gate);
            assertThat(JSON.readTree(dimension.payloadJson()).path("evidenceDisposition").asText()).isEqualTo("OBSERVED");
            assertThat(JSON.readTree(dimension.payloadJson()).path("memberCount").asInt()).isEqualTo(1);

            int io = f.gateway.calls();
            int enqueued = f.scheduled.size();
            var response = f.service.progress(PRINCIPAL, SESSION, reference);
            assertThat(response.report()).isNotNull();
            var view = response.report().view();
            assertThat(view.runId()).isEqualTo(reference.runId());
            assertThat(view.planId()).isEqualTo(token.definition().planId());
            assertThat(view.planRevision()).isEqualTo(token.definition().revision());
            assertThat(view.modules()).extracting(module -> module.goalId()).containsExactly("goal-1", "goal-2");
            assertThat(view.blocksById().values().stream().flatMap(block -> block.evidenceArtifactIds().stream()).toList())
                    .contains(outputs.get("selectedEntities"), outputs.get("selectionEvidence"), dimension.metadata().ref().artifactId(), independent.outputs().get("pages"));
            var reportReference = CampaignReportDeliveryService.Reference.of(token.definition());
            assertThat(f.reports.read(OWNER, reportReference, view.reportRef())).isEqualTo(view);
            assertThat(f.reports.export(OWNER, reportReference, view.reportRef()).view()).isEqualTo(view);
            assertThat(f.reports.history(OWNER, SESSION, null, 20).items())
                    .anyMatch(item -> item.reportRef().equals(view.reportRef()) && item.runId().equals(reference.runId()));
            var dataBlock = view.blocksById().values().stream().filter(block -> block.kind() == ReportBlock.Kind.TABLE
                    && block.evidenceArtifactIds().contains(dimension.metadata().ref().artifactId())).findFirst().orElseThrow();
            assertThat(f.reports.rows(OWNER, reportReference, view.reportRef(), dataBlock.blockId(), null, 25).rows()).isNotEmpty();
            assertThat(f.scheduled).hasSize(enqueued);
            assertThat(f.gateway.calls()).isEqualTo(io);
            assertThat(f.model.calls).hasValue(2);
            var retry = f.service.run(request);
            assertThat(retry.continuation()).isEqualTo(initial.continuation());
            f.runtime.intake().submit(PRINCIPAL, reference).get(20, TimeUnit.SECONDS);
            assertThat(f.gateway.calls()).isEqualTo(io);
            assertThat(f.model.calls).hasValue(2);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_public_request", Integer.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class)).isZero();
            int childRows = f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger", Integer.class);
            int reportRows = f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class);
            int scheduledBeforeRevocation = f.scheduled.size();
            f.deniedGroups.add("group-a");
            assertThat(f.runtime.principals().resolve(OWNER, SESSION)).isEqualTo(PRINCIPAL);
            assertThatThrownBy(() -> f.reports.read(OWNER, reportReference, view.reportRef()))
                    .as("An unchanged account identity cannot read a report after one frozen group is revoked")
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> f.reports.export(OWNER, reportReference, view.reportRef())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> f.reports.rows(OWNER, reportReference, view.reportRef(), dataBlock.blockId(), null, 25))
                    .isInstanceOf(SecurityException.class);
            assertThat(f.reports.history(OWNER, SESSION, null, 20).items())
                    .as("Revoked report versions disclose neither their title nor their assessments").isEmpty();
            assertThatThrownBy(() -> f.runtime.runs().readArtifact(OWNER, scopeId, gate)).isInstanceOf(SecurityException.class);
            assertThat(f.gateway.calls()).isEqualTo(io);
            assertThat(f.model.calls).hasValue(2);
            assertThat(f.scheduled).hasSize(scheduledBeforeRevocation);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_public_request", Integer.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger", Integer.class)).isEqualTo(childRows);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class)).isEqualTo(reportRows);
            f.deniedGroups.clear();
            assertThat(f.reports.read(OWNER, reportReference, view.reportRef()))
                    .as("Restoring only the isolated fixture ACL reads the same immutable report without re-analysis")
                    .isEqualTo(view);
            f.live.set(false);
            assertThatThrownBy(() -> f.service.progress(PRINCIPAL, SESSION, reference)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> f.reports.export(OWNER, reportReference, view.reportRef())).isInstanceOf(SecurityException.class);
            assertThat(f.reports.history(OWNER, SESSION, null, 20).items()).isEmpty();
            assertThat(f.gateway.calls()).isEqualTo(io);
            assertThat(f.model.calls).hasValue(2);
            assertThat(f.scheduled).hasSize(scheduledBeforeRevocation);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final ScriptedModel model = new ScriptedModel();
        final AtomicBoolean live = new AtomicBoolean(true);
        final Set<String> deniedGroups = ConcurrentHashMap.newKeySet();
        final Gateway gateway = new Gateway();
        final ExecutorService callbacks = Executors.newSingleThreadExecutor();
        final List<WorkRef> scheduled = new ArrayList<>();
        final CampaignStatisticsFixedRuntime runtime;
        final CampaignPublicRequestStore requests;
        final CampaignPublicRequestService service;
        final CampaignReportDeliveryService reports;
        Fixture() throws Exception {
            var source = new DriverManagerDataSource("jdbc:h2:mem:public_business_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            var migrations = List.of("V20260919__campaign_run_ledger.sql", "V20260919_2__campaign_step_ledger.sql",
                    "V20260919_3__campaign_run_owner.sql", "V20260920__campaign_statistics_result.sql",
                    "V20260920_2__campaign_statistics_release.sql", "V20260920_3__campaign_submission_deferral.sql",
                    "V20260920_5__campaign_scope_collection.sql", "V20260920_6__campaign_local_calculation.sql",
                    "V20260920_7__campaign_decline_selection.sql", "V20260920_8__campaign_model_invocation.sql",
                    "V20260920_9__campaign_exploration_call.sql", "V20260920_10__campaign_exploration_ledger.sql",
                    "V20260920_11__campaign_exploration_budget.sql", "V20260920_12__campaign_skill_invocation.sql",
                    "V20260920_13__campaign_skill_observation.sql", "V20260920_14__campaign_exploration_candidate.sql",
                    "V20260920_15__campaign_exploration_progress.sql", "V20260920_16__campaign_skill_capacity_wait.sql",
                    "V20260920_17__campaign_run_intake.sql", "V20260920_18__campaign_planning_request.sql",
                    "V20260920_19__campaign_statistics_consumers.sql",
                    "V20260920_22__campaign_report_lifecycle.sql", "V20260921__campaign_run_result_binding.sql",
                    "V20260923__campaign_conversation_session_owner.sql", "V20260923_2__campaign_advance_outcome.sql",
                    "V20260923_3__campaign_conversation_turn.sql", "V20260924_3__campaign_public_request.sql",
                    "V20260924_6__campaign_public_request_cancellation.sql");
            new ResourceDatabasePopulator(migrations.stream().map(name -> new ClassPathResource("sql/migration/" + name))
                    .toArray(ClassPathResource[]::new)).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            String root = new ClassPathResource("campaign-skills").getFile().getAbsolutePath();
            var extension = new CampaignPlanConfiguration().campaignBusinessExtension(model, callbacks, root);
            runtime = new CampaignStatisticsFixedRuntime(jdbc, tx, CLOCK, authority(live, deniedGroups), gateway, new MemorySaver(),
                    "public-business-test-domain", new ProcessCapacityExecutor.Limits(1, 1, 1, 2), java.nio.file.Path.of(root), extension);
            gateway.runtime = runtime;
            requests = new CampaignPublicRequestStore(jdbc, tx, CLOCK);
            var turns = new JdbcCampaignConversationTurnStore(jdbc, tx, new JdbcCampaignConversationSessionOwner(jdbc, tx, CLOCK), CLOCK);
            var artifacts = runtime.extension().profile().artifactAuthorizer();
            CampaignReportDeliveryService.RunAccess access = (caller, definition) -> {
                runtime.principals().resolve(caller, definition.sessionId());
                return caller.equals(definition.caller()) && runtime.extension().profile().runAuthorizer()
                        .mayExecute(caller, FrozenCampaignRun.read(definition).inputs());
            };
            reports = new CampaignReportDeliveryService(jdbc, tx, CLOCK, runtime.runs(), runtime.steps(),
                    new JdbcReportLifecycleStore(jdbc, tx, CLOCK), artifacts, access,
                    new CampaignArtifactReportRows(runtime.runs(), runtime.results(), new JdbcCampaignDeclineSelectionStore(jdbc, tx, CLOCK, runtime.runs())));
            service = new CampaignPublicRequestService(requests, turns, runtime.principals(), runtime.intake(), model,
                    runtime.extension().inputs(), CampaignBusinessProfile.REF, CampaignBusinessProfile.VERSION,
                    scheduled::add, new CampaignPublicDeliveryAdapter(runtime, reports, requests), CLOCK, Duration.ofHours(1));
            runtime.intake().installPreparation(service);
            runtime.intake().observeAdvances((caller, runId, scope) -> runtime.runs().loadRun(caller, runId)
                    .ifPresent(run -> reports.publishCurrent(caller, run.token(), scope)));
        }
        public void close() throws InterruptedException {
            runtime.close(); callbacks.shutdown(); assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class ScriptedModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        public ChatResponse call(Prompt prompt) {
            int number = calls.incrementAndGet();
            assertThat(number).isBetween(1, 2);
            String text = prompt.getInstructions().toString();
            String result;
            if (number == 1) {
                assertThat(text).contains(QUESTION, CampaignInterpretedRequest.WIRE_SCHEMA);
                result = FrozenCampaignRun.encode(new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, QUESTION,
                        List.of(new CampaignInterpretedRequest.Goal("下降短链及其省份设备变化", 0, QUESTION.length(), "DECLINE_DIMENSIONS", "PV",
                                List.of(query("group-a", "2026-09-01"), query("group-a", "2026-09-02")), false, List.of(), false, false),
                                new CampaignInterpretedRequest.Goal("另一组短链数据", 0, QUESTION.length(), "STATISTICS", "PV",
                                        List.of(query("group-b", "2026-09-02")), false, List.of(), false, false)), List.of()));
            } else {
                assertThat(text).contains("goal-1-selected", "goal-1-dimensions", "goal-2-query-1", "goal-2-delivery", "goal-1-collectionDefinition");
                result = proposal().encode();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(result))));
        }
        public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("public-scripted-model").build(); }
    }

    private static CampaignInterpretedRequest.Query query(String gid, String day) {
        return new CampaignInterpretedRequest.Query(gid, null, new CampaignInterpretedRequest.Period(day, day),
                "LINK_METRICS", List.of(), List.of());
    }
    private static PlanningProposal proposal() {
        var collect = new PlanSpec.Step("collect", List.of("goal-1"), PlanSpec.ExecutionMode.FIXED, FrozenScopeCollection.REF, null,
                List.of(), Map.of("definition", PlanBinding.input("goal-1-collectionDefinition")), Map.of(), FrozenScopeCollection.OUTPUT_CONTRACT);
        var select = new PlanSpec.Step("select", List.of("goal-1"), PlanSpec.ExecutionMode.FIXED, FrozenDeclineSelection.REF_V2, null,
                List.of("collect"), Map.of("scopeArtifact", PlanBinding.output("collect", "scopeArtifact"),
                "periods", PlanBinding.input("goal-1-periods"), "definition", PlanBinding.input("goal-1-selectionDefinition")),
                Map.of("metric", "PV"), FrozenDeclineSelection.OUTPUT_CONTRACT);
        var dimension = new PlanSpec.Step("dimension", List.of("goal-1"), PlanSpec.ExecutionMode.FIXED, FrozenDimensionChange.REF_V2, null,
                List.of("select"), Map.of("selectedEntities", PlanBinding.output("select", "selectedEntities"),
                "selectionEvidence", PlanBinding.output("select", "selectionEvidence"), "periods", PlanBinding.input("goal-1-periods"),
                "definition", PlanBinding.input("goal-1-dimensionDefinition")), Map.of(), FrozenDimensionChange.OUTPUT_CONTRACT);
        var standalone = new PlanSpec.Step("standalone", List.of("goal-2"), PlanSpec.ExecutionMode.FIXED, StatisticsJobFixedExecutor.REF, null,
                List.of(), Map.of("scope", PlanBinding.input("goal-2-query-1-scope"), "periods", PlanBinding.input("goal-2-query-1-periods"),
                "query", PlanBinding.input("goal-2-query-1-query")), Map.of(), CampaignStatisticsResultStore.SCHEMA_VERSION);
        return new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(collect, select, dimension, standalone), List.of(
                new PlanningAssessment.CoverageBinding("goal-1-selected", List.of(new PlanningAssessment.EvidenceOutput("select", "selectedEntities"), new PlanningAssessment.EvidenceOutput("select", "selectionEvidence"))),
                new PlanningAssessment.CoverageBinding("goal-1-dimensions", List.of(new PlanningAssessment.EvidenceOutput("dimension", "dimensionChanges"))),
                new PlanningAssessment.CoverageBinding("goal-2-query-1", List.of(new PlanningAssessment.EvidenceOutput("standalone", "pages"))),
                new PlanningAssessment.CoverageBinding("goal-2-delivery", List.of(new PlanningAssessment.EvidenceOutput("standalone", "pages")))), List.of());
    }

    // The scripted authority and page fixtures below reuse the E125 two-link cohort contract.
    private static AgentAuthorityClient authority(AtomicBoolean live, Set<String> deniedGroups) {
        var authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(any(AgentPrincipal.class))).thenAnswer(call -> {
            if (!live.get() || !PRINCIPAL.equals(call.getArgument(0))) throw new SecurityException("REVOKED");
            return PRINCIPAL;
        });
        when(authority.resolveGroupMembersPage(any(AgentPrincipal.class), anyString(), isNull(), isNull()))
                .thenAnswer(call -> {
                    assertThat(call.<AgentPrincipal>getArgument(0)).isEqualTo(PRINCIPAL);
                    assertThat(call.<String>getArgument(1)).isIn("group-a", "group-b");
                    if (!live.get() || deniedGroups.contains(call.<String>getArgument(1))) throw new SecurityException("REVOKED");
                    return new GroupMembersPage(GroupMembersPage.SCHEMA, OWNER.tenantId(), OWNER.subject(),
                            OWNER.authVersion(), call.getArgument(1), VERSION, null,
                            "group-a".equals(call.getArgument(1)) ? List.of(1L, 2L) : List.of(3L, 4L), null);
                });
        when(authority.resolvePage(any(AgentPrincipal.class), anyString(), isNull(), any(), isNull(), anyString()))
                .thenAnswer(call -> {
                    assertThat(call.<AgentPrincipal>getArgument(0)).isEqualTo(PRINCIPAL);
                    assertThat(call.<String>getArgument(1)).isEqualTo("group-a");
                    assertThat(call.<String>getArgument(5)).isEqualTo(VERSION);
                    if (!live.get() || deniedGroups.contains(call.<String>getArgument(1))) throw new SecurityException("REVOKED");
                    List<Long> ids = call.getArgument(3);
                    assertThat(ids).isNotEmpty().allMatch(id -> id == 1 || id == 2);
                    return new AgentAuthorityClient.AuthorizedScope(OWNER.tenantId(), VERSION,
                            ids.stream().<Map<String, Object>>map(id -> Map.of("linkId", id, "gid", "group-a",
                                    "fullShortUrl", "https://s.example/" + id)).toList(), null);
                });
        return authority;
    }

    private static ClassPathResource migration(String name) { return new ClassPathResource("sql/migration/" + name); }

    private static final class Gateway implements ShortLinkBusinessGateway {
        CampaignStatisticsFixedRuntime runtime;
        String runId;
        final Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
        final Map<String, Integer> polls = new HashMap<>();
        final Set<String> released = new HashSet<>();
        int submissions, recoveries, statuses, releases, pageReads;
        int calls() { return submissions + recoveries + statuses + releases + pageReads; }

        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
            throw new AssertionError("Legacy GET is forbidden");
        }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> query) {
            throw new AssertionError("Legacy POST is forbidden");
        }
        @Override public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) {
            return accept(context, request, false);
        }
        @Override public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            return accept(context, request, true);
        }
        private ToolResult accept(ToolContext context, Map<String, Object> request, boolean fixedMembers) {
            submissions++;
            assertThat(context.principal()).isEqualTo(PRINCIPAL);
            assertThat(context.sessionId()).isEqualTo(SESSION);
            assertThat(context.arguments()).isEqualTo(request);
            var token = runtime.runs().loadRun(OWNER, runId).orElseThrow().token();
            var child = runtime.runs().children(token).stream()
                    .filter(value -> value.spec().requestId().equals(request.get("requestId"))).findFirst().orElseThrow();
            assertThat(child.state()).isEqualTo(ChildState.DISPATCHING);
            assertThat(child.callbackActive()).isTrue();
            assertThat(child.spec().wire().bodyJson()).isEqualTo(FrozenCampaignRun.encode(request));
            assertThat(child.spec().wire().path()).isEqualTo(fixedMembers ? StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH : FrozenStatisticsJobQuery.SUBMIT_PATH);
            assertThat(accepted.values()).noneMatch(previous -> previous.get("requestId").equals(request.get("requestId")));
            String job = "dependency-job-" + (accepted.size() + 1);
            accepted.put(job, Map.copyOf(request));
            return ToolResult.success(Map.of("jobId", job, "state", "QUEUED"));
        }
        @Override public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++;
            throw new AssertionError("An accepted original job must not be recovered or resubmitted");
        }
        @Override public ToolResult readStatisticsJob(ToolContext context, String job) {
            statuses++;
            assertThat(context.principal()).isEqualTo(PRINCIPAL);
            if (polls.merge(job, 1, Integer::sum) == 1)
                return ToolResult.success(Map.of("jobId", job, "state", "RUNNING"));
            return ToolResult.success(status(job));
        }
        @Override public ToolResult releaseStatisticsJobResult(ToolContext context, String job, Map<String, Object> request) {
            releases++;
            assertThat(FrozenCampaignRun.encode(request)).isEqualTo(FrozenCampaignRun.encode(accepted.get(job)));
            var token = runtime.runs().loadRun(OWNER, runId).orElseThrow().token();
            var child = runtime.runs().children(token).stream().filter(value -> job.equals(value.jobId())).findFirst().orElseThrow();
            assertThat(child.state()).isEqualTo(ChildState.READY);
            assertThat(runtime.runs().readArtifact(OWNER, child.artifactId(), runtime.extension().profile().artifactAuthorizer())).isNotNull();
            assertThat(released.add(job)).as("A confirmed release happens once").isTrue();
            return ToolResult.success(status(job));
        }
        private Map<String, Object> status(String job) {
            int rows = "LINK_METRICS".equals(accepted.get(job).get("queryKind")) ? members(job).size() : 1;
            var status = new LinkedHashMap<String, Object>(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", rows,
                    "pageCount", 1, "expiresAt", EXPIRY, "resultState", released.contains(job) ? "RELEASED" : "AVAILABLE",
                    "resultReady", !released.contains(job)));
            if (released.contains(job)) status.put("resultCode", "RESULT_RELEASED");
            return status;
        }
        private FrozenQueryScope scope(String job) {
            Object scope = accepted.get(job).get("scope");
            return scope == null ? null : FrozenQueryScope.fromMap((Map<?, ?>) scope);
        }
        private List<Long> members(String job) { return scope(job) == null ? List.of(3L, 4L) : scope(job).linkIds(); }
        @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String,Object> request) {
            recoveries++; throw new AssertionError("Known independent job must not be resubmitted");
        }

        @Override public ToolResult readStatisticsJobPage(ToolContext context, String job, int page, int size) {
            pageReads++;
            assertThat(page).isZero();
            assertThat(size).isEqualTo(500);
            assertThat(released).doesNotContain(job);
            var request = accepted.get(job);
            var scope = scope(job);
            var members = members(job);
            boolean baseline = "2026-09-01".equals(request.get("startDate"));
            boolean dimension = "DIMENSION_BREAKDOWN".equals(request.get("queryKind"));
            long start = day(request.get("startDate").toString()), end = day(request.get("endDate").toString()) + 86_400_000;
            long total = members.stream().mapToLong(id -> pv(id, baseline)).sum();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (dimension) {
                assertThat(scope.linkIds()).containsExactly(1L);
                assertThat(request.get("dimensions")).isEqualTo(DIMENSIONS);
                assertThat(request.get("filters")).isEqualTo(List.of());
                rows.add(Map.of("dimensions", Map.of("province", Map.of("state", "KNOWN", "value", "浙江"),
                                "device", Map.of("state", "KNOWN", "value", "Mobile")), "pv", total, "uv", 1, "uip", 1, "pvRatio", 1.0));
            } else for (long id : members) {
                var row = new LinkedHashMap<>(counts(pv(id, baseline), start, end));
                row.put("linkId", id);
                rows.add(row);
            }
            var meta = new LinkedHashMap<String, Object>();
            meta.put("snapshotId", job); meta.put("queryKind", request.get("queryKind")); meta.put("gid", request.get("gid"));
            meta.put("linkIds", members);
            if (scope != null) meta.put("scopeProof", scope.proof("b".repeat(64)));
            meta.put("groupScopeComplete", scope == null);
            meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1");
            String hash = CampaignRunStore.sha256(job);
            meta.put("sourceCut", Map.of("manifestSelectionHash", hash)); meta.put("manifestVersion", Map.of("selectionHash", hash));
            meta.put("snapshotCreatedAt", CLOCK.millis()); meta.put("snapshotExpiresAt", EXPIRY);
            meta.put("requestedStart", start); meta.put("requestedEnd", end); meta.put("effectiveEnd", end); meta.put("businessTimezone", "Asia/Shanghai");
            meta.put("pageIndex", 0); meta.put("nextPageIndex", null); meta.put("totalRows", rows.size());
            meta.put("aggregationLevel", dimension ? "DIMENSION_BREAKDOWN" : "LINK_WINDOW");
            meta.put("availability", "AVAILABLE"); meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("provisional", false);
            meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of());
            meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                    "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"),
                    "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            var summary = new LinkedHashMap<String, Object>(counts(total, start, end));
            if (dimension) {
                summary.remove("denied"); summary.put("ratioDenominator", total);
                Map<String, Object> quality = Map.of("province", quality(total, "CN_PROVINCE"), "device", quality(total, "DEVICE"));
                summary.put("dimensionQuality", quality); meta.put("dimensionQuality", quality);
                meta.put("dimensions", DIMENSIONS); meta.put("filters", List.of()); meta.put("dimensionQualityScope", "FILTERED_FULL_WINDOW");
                meta.put("resultComplete", true); meta.put("truncated", false);
            }
            return ToolResult.success(Map.of("items", rows, "metrics", Map.of("requested", summary), "meta", meta));
        }
    }

    private static long pv(long id, boolean baseline) { return id == 1 ? (baseline ? 8 : 2) : (baseline ? 4 : 6); }
    private static long day(String value) { return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
    private static Map<String, Object> counts(long pv, long start, long end) {
        return Map.of("pv", pv, "uv", pv == 0 ? 0 : 1, "uip", pv == 0 ? 0 : 1, "denied", 0,
                "window", "requested", "startInclusive", start, "endExclusive", end);
    }
    private static Map<String, Object> quality(long total, String semantic) {
        return Map.of("status", "AVAILABLE", "knownCount", total, "unknownCount", 0, "eligibleCount", total,
                "notApplicableCount", 0, "coverage", 1.0, "semantic", semantic, "reasonCounts", Map.of());
    }
}
