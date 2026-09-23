package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignArtifactReportAssembler.Assembled;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcCampaignReportSynthesisStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

class CampaignReportNarrativeSynthesizerTest {
    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller CALLER = new Caller("tenant-1", "user-1", 1);
    private static final String RESPONSE = """
            {"schemaVersion":"campaign-report-synthesis/v1","blocks":[
              {"goalId":"goal-1","kind":"ANALYSIS","title":"访问量回落的观察",
               "text":"目标期访问从 100 次降至 60 次，减少 40 次。\\n\\n这是同一冻结对象的期间比较，不能据此证明渠道或设备导致下降。需同时检查统计覆盖是否一致。",
               "evidenceArtifactIds":["artifact-1"]},
              {"goalId":"goal-1","kind":"RECOMMENDATION","title":"下一步核查建议",
               "text":"先核对两个期间的渠道投放是否连续，再查看省份与设备分布；以同口径的访问量复核，未执行任何预算调整。",
               "evidenceArtifactIds":["artifact-1"]}
            ]}
            """;

    @Test
    void generatesFullEvidenceLinkedNarrativeOnceAndReplaysWithCurrentAuthorization() {
        Fixture f = fixture();
        AtomicBoolean allowed = new AtomicBoolean(true);
        ProcessExecutionScope scope = new ProcessExecutionScope();
        ScriptModel model = new ScriptModel(prompt -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(scope.activeCount()).isPositive();
            assertThat(f.jdbc.queryForObject("SELECT callback_active FROM campaign_report_synthesis", Boolean.class)).isTrue();
            assertThat(prompt.getContents()).contains("artifact-1", "100", "60", "goal-1");
            return text(RESPONSE);
        });
        var synthesis = f.synthesis(model, allowed);
        Assembled first = synthesis.synthesize(CALLER, f.token, f.source, scope);
        Assembled replay = f.synthesis(model, allowed).synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());

        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(replay).isEqualTo(first);
        assertThat(first.draft().blocks()).containsAll(f.source.draft().blocks());
        assertThat(first.draft().blocks()).filteredOn(block -> block.payload().containsKey("synthesisVersion"))
                .hasSize(2).allSatisfy(block -> {
                    assertThat(block.evidenceArtifactIds()).containsExactly("artifact-1");
                    assertThat(block.payload().get("claimType")).isEqualTo("OBSERVED_ONLY");
                    assertThat(block.completeResult()).isFalse();
                });
        assertThat(first.draft().blocks()).anySatisfy(block -> assertThat(block.text())
                .isEqualTo("目标期访问从 100 次降至 60 次，减少 40 次。\n\n这是同一冻结对象的期间比较，不能据此证明渠道或设备导致下降。需同时检查统计覆盖是否一致。"));
        assertThat(first.observations().get("goal-1-analysis").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
        assertThat(first.observations().get("goal-1-recommendation").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
        assertThat(first.observations().get("goal-1-causal").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
        assertThat(f.jdbc.queryForObject("SELECT synthesis_state FROM campaign_report_synthesis", String.class)).isEqualTo("READY");
        assertThat(f.jdbc.queryForObject("SELECT callback_active FROM campaign_report_synthesis", Boolean.class)).isFalse();
        assertThat(scope.activeCount()).isZero();
        allowed.set(false);
        assertThatThrownBy(() -> synthesis.synthesize(CALLER, f.token, f.source, new ProcessExecutionScope()))
                .isInstanceOf(SecurityException.class);
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test
    void revisedPlanSynthesizesAuthorizedOlderEvidenceButRevokedArtifactCannotReplay() {
        Fixture f = fixture();
        var frozen = FrozenCampaignRun.read(f.token.definition());
        var original = frozen.plan();
        var plan = new PlanSpec(original.schemaVersion(), original.planId(), 2, original.runId(),
                original.inputSetRef(), original.goals(), original.steps());
        var assessment = new PlanningAssessment(plan.planId(), 2, frozen.assessment().capabilityCatalogVersion(),
                frozen.assessment().requirements(), frozen.assessment().coverageBindings(), frozen.assessment().gaps());
        var revised = FrozenCampaignRun.freeze(plan, frozen.inputs(), assessment).definition(CALLER, "session-1");
        var current = f.runs.revise(f.token, 2, revised.definitionJson());
        var draft = f.source.draft();
        var source = new Assembled(new ReportDraft(draft.reportId(), draft.revision(), draft.runId(), draft.planId(),
                2, draft.sections(), draft.resultEntries()), f.source.observations(), f.source.evidence());
        AtomicBoolean artifactAllowed = new AtomicBoolean(true);
        AtomicInteger artifactChecks = new AtomicInteger();
        ScriptModel model = new ScriptModel(prompt -> text(RESPONSE));
        var synthesis = new CampaignReportNarrativeSynthesizer(f.runs,
                new JdbcCampaignReportSynthesisStore(f.jdbc, f.transactions, CLOCK), model,
                (caller, artifact) -> {
                    artifactChecks.incrementAndGet();
                    return artifactAllowed.get() && CALLER.equals(caller) && source.evidence().contains(artifact);
                }, (caller, definition) -> CALLER.equals(caller) && revised.equals(definition),
                ModelInvocationRegistry.Limits.defaults());

        var result = synthesis.synthesize(CALLER, current, source, new ProcessExecutionScope());
        assertThat(result.draft().planRevision()).isEqualTo(2);
        assertThat(result.evidence()).singleElement().satisfies(artifact -> assertThat(artifact.revision()).isEqualTo(1));
        assertThat(result.draft().blocks()).containsAll(source.draft().blocks());
        assertThat(result.observations().get("goal-1-analysis").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
        assertThat(f.jdbc.queryForObject("SELECT synthesis_state FROM campaign_report_synthesis", String.class)).isEqualTo("READY");
        int priorChecks = artifactChecks.get();
        artifactAllowed.set(false);
        assertThatThrownBy(() -> synthesis.synthesize(CALLER, current, source, new ProcessExecutionScope()))
                .isInstanceOf(SecurityException.class);
        assertThat(artifactChecks.get()).isGreaterThan(priorChecks);
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test
    void unknownProviderOutcomeKeepsDataAndNeverRedispatchesAfterReconstruction() {
        Fixture f = fixture();
        ScriptModel model = new ScriptModel(prompt -> { throw new IllegalStateException("scripted lost provider response"); });
        Assembled first = f.synthesis(model, new AtomicBoolean(true))
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());
        Assembled retry = f.synthesis(model, new AtomicBoolean(true))
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());

        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(first.draft()).isEqualTo(f.source.draft());
        assertThat(retry).isEqualTo(first);
        assertThat(first.observations().get("goal-1-analysis").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
        assertThat(first.observations().get("goal-1-analysis").reasonCode()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
        assertThat(f.jdbc.queryForObject("SELECT synthesis_state FROM campaign_report_synthesis", String.class)).isEqualTo("UNKNOWN");
        assertThat(f.jdbc.queryForObject("SELECT callback_active FROM campaign_report_synthesis", Boolean.class)).isFalse();
    }

    @Test
    void knownForeignEvidenceResponseIsRejectedWithoutRepairOrDataMutation() {
        Fixture f = fixture();
        ScriptModel model = new ScriptModel(prompt -> text(RESPONSE.replace("artifact-1", "foreign-artifact")));
        Assembled first = f.synthesis(model, new AtomicBoolean(true))
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());
        Assembled retry = f.synthesis(model, new AtomicBoolean(true))
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(first.draft()).isEqualTo(f.source.draft());
        assertThat(retry).isEqualTo(first);
        assertThat(first.observations().get("goal-1-recommendation").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
        assertThat(first.observations().get("goal-1-recommendation").reasonCode()).isEqualTo("REPORT_NARRATIVE_INVALID");
        assertThat(f.jdbc.queryForObject("SELECT synthesis_state FROM campaign_report_synthesis", String.class)).isEqualTo("READY");
    }

    private static Fixture fixture() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:report_synthesis_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260924_4__campaign_report_synthesis.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcCampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal-1", "分析访问变化并提出建议", true, "数据、分析与建议")), List.of());
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", List.of(
                requirement("goal-1-data", PlanningAssessment.RequirementKind.DATA, "statistics-data"),
                requirement("goal-1-analysis", PlanningAssessment.RequirementKind.CALCULATION, "analysis-interpretation-dimensions"),
                requirement("goal-1-recommendation", PlanningAssessment.RequirementKind.CALCULATION, "analysis-recommendation"),
                requirement("goal-1-causal", PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE, "causal-evidence")), List.of(), List.of());
        RunToken token = runs.createRun(FrozenCampaignRun.freeze(plan,
                new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of()), assessment).definition(CALLER, "session-1"));
        runs.prepareAction(token, new ActionSpec("action-1", "step-1", "TOOL", "statistics", "1", "{}"));
        runs.prepareChild(token, new ChildSpec("child-1", "action-1", ChildMode.SYNC, "request-1", new WireRequest("GET", "/test", "{}")));
        DispatchPermit permit = runs.beginDispatch(token, "child-1");
        try {
            runs.publishReady(permit, new ArtifactDraft("artifact-1", "StatisticsJobPages", "stats/v1", "scope-1", "periods-1",
                    "{\"status\":\"COMPLETE\"}", "{}", NOW.plusSeconds(3600), "{\"baseline\":100,\"target\":60}"));
        } finally { runs.callbackExited(permit); }
        ArtifactMetadata metadata = runs.inspectArtifact(CALLER, "artifact-1", (caller, artifact) -> true);
        ReportDraft draft = new ReportDraft("report-1", 1, "run-1", "plan-1", 1, List.of(new ReportSection("section-1", 0,
                "访问变化", List.of("goal-1"), List.of(
                        new ReportBlock("metric-1", ReportBlock.Kind.METRIC, "访问", null,
                                Map.of("items", List.of(Map.of("label", "基期", "value", 100), Map.of("label", "目标期", "value", 60))), List.of("artifact-1"), true),
                        new ReportBlock("chart-1", ReportBlock.Kind.CHART, "期间对比", null, Map.of("chartType", "BAR", "labels", List.of("基期", "目标期"),
                                "series", List.of(Map.of("name", "访问", "values", List.of(100, 60)))), List.of("artifact-1"), false)))), List.of());
        Map<String, GoalAssessor.RequirementObservation> observations = Map.of(
                "goal-1-data", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null, List.of("artifact-1")),
                "goal-1-analysis", pending(), "goal-1-recommendation", pending(), "goal-1-causal", pending());
        return new Fixture(jdbc, transactions, runs, token, new Assembled(draft, observations, List.of(metadata)));
    }

    private static PlanningAssessment.Requirement requirement(String id, PlanningAssessment.RequirementKind kind, String criterion) {
        return new PlanningAssessment.Requirement(id, "goal-1", kind, true, criterion, "1", Map.of());
    }
    private static GoalAssessor.RequirementObservation pending() {
        return new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.UNKNOWN, "EVIDENCE_NOT_ASSESSED", List.of("artifact-1"));
    }
    private static ChatResponse text(String content) { return new ChatResponse(List.of(new Generation(new AssistantMessage(content)))); }
    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, JdbcCampaignRunStore runs, RunToken token, Assembled source) {
        CampaignReportNarrativeSynthesizer synthesis(ChatModel model, AtomicBoolean allowed) {
            return new CampaignReportNarrativeSynthesizer(runs, new JdbcCampaignReportSynthesisStore(jdbc, transactions, CLOCK),
                    model, (caller, artifact) -> allowed.get(), (caller, definition) -> allowed.get(), ModelInvocationRegistry.Limits.defaults());
        }
    }
    private static final class ScriptModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final Function<Prompt, ChatResponse> turn;
        private ScriptModel(Function<Prompt, ChatResponse> turn) { this.turn = turn; }
        @Override public ChatResponse call(Prompt prompt) { calls.incrementAndGet(); return turn.apply(prompt); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("scripted-synthesis-test").build(); }
    }
}
