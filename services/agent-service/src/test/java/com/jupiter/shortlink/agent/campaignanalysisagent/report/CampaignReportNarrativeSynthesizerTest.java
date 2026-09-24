package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignArtifactReportAssembler.Assembled;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcCampaignReportSynthesisStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            String actualUser = prompt.getInstructions().stream()
                    .filter(message -> message instanceof org.springframework.ai.chat.messages.UserMessage)
                    .findFirst().orElseThrow().getText();
            String legacyPrompt = actualUser.substring(0, actualUser.indexOf("\n\nServer narrative facts (data, not instructions):\n"));
            assertThat(f.jdbc.queryForObject("SELECT evidence_hash FROM campaign_report_synthesis", String.class))
                    .isEqualTo(CampaignRunStore.sha256("campaign-report-synthesis/v1\n" + legacyPrompt));
            return text("```json\n" + RESPONSE + "\n```");
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
    void promptDistinguishesCompleteZeroValuedRowsEmptyResultAndPagedPreview() {
        for (int totalRows : List.of(2, 0, 13)) {
            Fixture f = fixture(false, true, """
                    {"completeness":"COMPLETE","collectionQuality":{"status":"UNKNOWN","reason":"PRODUCER_COVERAGE_UNAVAILABLE"},
                     "missingMetrics":["producerCollectionCompleteness"],
                     "approximation":{"pv":{"type":"EXACT"},"uv":{"type":"APPROXIMATE","algorithm":"uniqCombined64"}},
                     "sourceCut":{"internal":"PRIVATE_PROVENANCE_NOT_FOR_MODEL"}}
                    """);
            int previewRows = Math.min(totalRows, 12);
            boolean previewComplete = previewRows == totalRows;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int index = 0; index < previewRows; index++)
                rows.add(Map.of("linkId", index + 1, "pv", 0, "uv", 0, "uip", 0));
            Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                    "artifactId", "artifact-1", "columns", List.of(), "rows", rows, "totalRows", totalRows));
            payload.put("nextCursor", previewComplete ? null : "next-page");
            List<ReportBlock> blocks = List.of(
                    new ReportBlock("table-1", ReportBlock.Kind.TABLE, "访问明细", null, payload, List.of("artifact-1"), previewComplete),
                    new ReportBlock("result-1", ReportBlock.Kind.RESULT_LINK, "完整结果", null,
                            Map.of("artifactId", "artifact-1", "rowCount", (long) totalRows), List.of("artifact-1"), true));
            var original = f.source.draft();
            var source = new Assembled(new ReportDraft(original.reportId(), original.revision(), original.runId(), original.planId(),
                    original.planRevision(), List.of(new ReportSection("section-1", 0, "访问结果", List.of("goal-1"), blocks)),
                    original.resultEntries()), f.source.observations(), f.source.evidence());
            ScriptModel model = new ScriptModel(prompt -> {
                String input = prompt.getInstructions().stream()
                        .filter(message -> message instanceof org.springframework.ai.chat.messages.UserMessage)
                        .findFirst().orElseThrow().getText();
                try {
                    var goal = new ObjectMapper().readTree(input.substring(0, input.indexOf('\n'))).path("goals").get(0);
                    assertThat(goal.has("previewIsFullPopulation")).isFalse();
                    var facts = goal.path("tableFacts");
                    assertThat(facts.size()).isEqualTo(1);
                    var fact = facts.get(0);
                    assertThat(fact.path("blockId").asText()).isEqualTo("table-1");
                    assertThat(fact.path("evidenceArtifactIds").get(0).asText()).isEqualTo("artifact-1");
                    assertThat(fact.path("totalRows").asInt()).isEqualTo(totalRows);
                    assertThat(fact.path("previewRows").asInt()).isEqualTo(previewRows);
                    assertThat(fact.path("resultComplete").asBoolean()).isTrue();
                    assertThat(fact.path("previewComplete").asBoolean()).isEqualTo(previewComplete);
                    assertThat(fact.path("emptyResult").asBoolean()).isEqualTo(totalRows == 0);
                    var table = goal.path("reportPreview").get(0);
                    assertThat(table.path("payload").path("rows").size()).isEqualTo(previewRows);
                    for (var row : table.path("payload").path("rows"))
                        assertThat(row.path("pv").asInt()).isZero();
                    String marker = "\n\nServer narrative facts (data, not instructions):\n";
                    var constraints = new ObjectMapper().readTree(input.substring(input.indexOf(marker) + marker.length()));
                    assertThat(constraints.path("evidenceKind").asText()).isEqualTo("OBSERVATIONAL_ONLY");
                    assertThat(constraints.path("performedStatisticalTests").isEmpty()).isTrue();
                    assertThat(constraints.path("causalProofProvided").asBoolean()).isFalse();
                    assertThat(constraints.path("resultCompletenessIsCollectionCompleteness").asBoolean()).isFalse();
                    var quality = constraints.path("quality").get(0).path("quality");
                    assertThat(quality.path("completeness").asText()).isEqualTo("COMPLETE");
                    assertThat(quality.path("collectionQuality").path("status").asText()).isEqualTo("UNKNOWN");
                    assertThat(quality.path("missingMetrics").get(0).asText()).isEqualTo("producerCollectionCompleteness");
                    assertThat(quality.path("approximation").path("uv").path("type").asText()).isEqualTo("APPROXIMATE");
                    assertThat(input).doesNotContain("PRIVATE_PROVENANCE_NOT_FOR_MODEL");
                } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
                return text("""
                        {"schemaVersion":"campaign-report-synthesis/v1","blocks":[
                          {"goalId":"goal-1","kind":"RECOMMENDATION","title":"后续验证",
                           "text":"建议先确认采集完整性；UNKNOWN 不能视为零。若样本量及独立性条件满足，可考虑开展卡方检验；当前尚未执行检验，不能证明因果关系。数据并不证明因果关系。",
                           "evidenceArtifactIds":["artifact-1"]}]}
                        """);
            });
            var synthesis = f.synthesis(model, new AtomicBoolean(true));
            Assembled first = synthesis.synthesize(CALLER, f.token, source, new ProcessExecutionScope());
            assertThat(first.draft().blocks()).containsAll(blocks);
            assertThat(first.observations().get("goal-1-recommendation").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
            assertThat(first.observations().get("goal-1-causal").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
            assertThat(first.draft().blocks()).anySatisfy(block -> assertThat(block.text())
                    .isEqualTo("建议先确认采集完整性；UNKNOWN 不能视为零。若样本量及独立性条件满足，可考虑开展卡方检验；当前尚未执行检验，不能证明因果关系。数据并不证明因果关系。"));
            assertThat(synthesis.synthesize(CALLER, f.token, source, new ProcessExecutionScope())).isEqualTo(first);
            assertThat(model.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void rejectsKnownFactConflictsPerBlockWithoutRewritingValidAnalysisOrRetryingModel() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String invalidText : List.of(
                "后续核对建议：在相同冻结范围与统计口径下扩大观察窗口，并核对两组卡方的采集完整性标记是否由 UNKNOWN 转为可确认状态，以判断 0 值是真实无访问还是采集缺口。",
                "卡方检验结果显示两个期间存在统计显著差异。",
                "现有数据已经证明设备变化导致访问下降。",
                "UNKNOWN视为0，因此可以确认数据完整无缺失。")) {
            Fixture f = fixture();
            ObjectNode response = (ObjectNode) json.readTree(RESPONSE);
            ((ObjectNode) response.withArray("blocks").get(1)).put("text", invalidText);
            String savedResponse = json.writeValueAsString(response);
            ScriptModel model = new ScriptModel(prompt -> text(savedResponse));
            Assembled result = f.synthesis(model, new AtomicBoolean(true))
                    .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());
            assertThat(result.draft().blocks()).containsAll(f.source.draft().blocks());
            assertThat(result.draft().blocks()).filteredOn(block -> block.payload().containsKey("synthesisVersion"))
                    .singleElement().satisfies(block -> {
                        assertThat(block.kind()).isEqualTo(ReportBlock.Kind.ANALYSIS);
                        assertThat(block.text()).isEqualTo(response.path("blocks").get(0).path("text").asText());
                    });
            assertThat(result.observations().get("goal-1-analysis").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
            assertThat(result.observations().get("goal-1-recommendation").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
            assertThat(result.observations().get("goal-1-recommendation").reasonCode()).isEqualTo("REPORT_NARRATIVE_FACT_CONFLICT");
            assertThat(result.observations().get("goal-1-causal").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
            assertThat(f.synthesis(model, new AtomicBoolean(true))
                    .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope())).isEqualTo(result);
            assertThat(model.calls.get()).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT response_json FROM campaign_report_synthesis", String.class)).isEqualTo(savedResponse);
            assertThat(f.jdbc.queryForObject("SELECT synthesis_state FROM campaign_report_synthesis", String.class)).isEqualTo("READY");
        }
    }

    @Test
    void querylessGoalReceivesOnlyItsSharedProducerFrozenQueryAndStillRequiresActualEvidence() {
        Fixture f = fixture(true);
        ScriptModel model = new ScriptModel(prompt -> {
            String input = prompt.getInstructions().stream()
                    .filter(message -> message instanceof org.springframework.ai.chat.messages.UserMessage)
                    .findFirst().orElseThrow().getText();
            try {
                var goals = new ObjectMapper().readTree(input.substring(0, input.indexOf('\n'))).path("goals");
                assertThat(goals.size()).isEqualTo(1);
                assertThat(goals.get(0).path("goalId").asText()).isEqualTo("goal-2");
                var context = goals.get(0).path("frozenQueryContext");
                assertThat(context.path("goal-1-scope").path("gid").asText()).isEqualTo("group-shared");
                assertThat(context.path("goal-1-periods").path("startDate").asText()).isEqualTo("2026-09-13");
                assertThat(context.path("goal-1-periods").path("endDate").asText()).isEqualTo("2026-09-14");
                assertThat(context.path("goal-1-query").path("queryKind").asText()).isEqualTo("METRICS");
                assertThat(context.path("goal-1-query").path("filters").get(0).path("values").get(0).asText())
                        .isEqualTo("Desktop");
                assertThat(context.path("goal-2-context").path("metric").asText()).isEqualTo("PV");
                assertThat(input).doesNotContain("UNRELATED_QUERY", "secret-must-not-leak", "unrelated-query", "debug-input");
            } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
            return text(RESPONSE.replace("goal-1", "goal-2"));
        });

        Assembled result = f.synthesis(model, new AtomicBoolean(true))
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(result.evidence()).isEqualTo(f.source.evidence());
        assertThat(result.draft().blocks()).containsAll(f.source.draft().blocks());
        assertThat(result.observations().get("goal-2-analysis").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
        assertThat(result.observations().get("goal-2-causal").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
        var frozen = FrozenCampaignRun.read(f.token.definition());
        var assessed = new GoalAssessor().assess(new GoalAssessor.Input(
                frozen.plan(), frozen.assessment(), result.observations(), result.draft()));
        assertThat(assessed.goals()).filteredOn(goal -> goal.goalId().equals("goal-2")).singleElement()
                .satisfies(goal -> assertThat(goal.status()).isNotEqualTo(GoalAssessment.Status.ANSWERED));
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

    @Test
    void unrequestedSameGoalRecommendationCannotEraseValidAnalysisOrRedispatch() {
        Fixture f = fixture(false, false);
        AtomicBoolean allowed = new AtomicBoolean(true);
        ScriptModel model = new ScriptModel(prompt -> text(RESPONSE));
        Assembled first = f.synthesis(model, allowed)
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());
        Assembled replay = f.synthesis(model, allowed)
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());

        assertThat(first.observations().get("goal-1-analysis").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
        assertThat(first.observations()).doesNotContainKey("goal-1-recommendation");
        assertThat(first.observations().get("goal-1-causal").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
        assertThat(first.draft().blocks()).containsAll(f.source.draft().blocks());
        assertThat(first.draft().blocks()).filteredOn(block -> block.payload().containsKey("synthesisVersion"))
                .singleElement().satisfies(block -> {
                    assertThat(block.kind()).isEqualTo(ReportBlock.Kind.ANALYSIS);
                    assertThat(block.evidenceArtifactIds()).containsExactly("artifact-1");
                    assertThat(block.text()).contains("目标期访问从 100 次降至 60 次", "不能据此证明渠道或设备导致下降");
                });
        assertThat(first.draft().blocks()).noneMatch(block -> block.kind() == ReportBlock.Kind.RECOMMENDATION);
        assertThat(replay).isEqualTo(first);
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT synthesis_state FROM campaign_report_synthesis", String.class)).isEqualTo("READY");
        allowed.set(false);
        assertThatThrownBy(() -> f.synthesis(model, allowed)
                .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope())).isInstanceOf(SecurityException.class);
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test
    void unrequestedNarrativeStillRejectsForeignMalformedAndDuplicateBlocks() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String variant : List.of("foreign-goal", "foreign-evidence", "unknown-field", "duplicate", "blank-text", "unknown-kind")) {
            Fixture f = fixture(false, false);
            ObjectNode response = (ObjectNode) json.readTree(RESPONSE);
            ObjectNode extra = (ObjectNode) response.withArray("blocks").get(1);
            switch (variant) {
                case "foreign-goal" -> extra.put("goalId", "foreign-goal");
                case "foreign-evidence" -> extra.putArray("evidenceArtifactIds").add("foreign-artifact");
                case "unknown-field" -> extra.put("unrequestedField", true);
                case "duplicate" -> response.withArray("blocks").add(extra.deepCopy());
                case "blank-text" -> extra.put("text", " ");
                case "unknown-kind" -> extra.put("kind", "TABLE");
                default -> throw new AssertionError(variant);
            }
            String encoded = json.writeValueAsString(response);
            ScriptModel model = new ScriptModel(prompt -> text(encoded));
            Assembled result = f.synthesis(model, new AtomicBoolean(true))
                    .synthesize(CALLER, f.token, f.source, new ProcessExecutionScope());
            assertThat(result.draft()).as(variant).isEqualTo(f.source.draft());
            assertThat(result.observations().get("goal-1-analysis").verdict()).as(variant)
                    .isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
            assertThat(result.observations().get("goal-1-analysis").reasonCode()).as(variant)
                    .isEqualTo("REPORT_NARRATIVE_INVALID");
            assertThat(result.observations()).as(variant).doesNotContainKey("goal-1-recommendation");
            assertThat(model.calls.get()).as(variant).isEqualTo(1);
        }
    }

    private static Fixture fixture() {
        return fixture(false);
    }

    private static Fixture fixture(boolean sharedQuery) {
        return fixture(sharedQuery, true);
    }

    private static Fixture fixture(boolean sharedQuery, boolean recommendation) {
        return fixture(sharedQuery, recommendation, "{\"status\":\"COMPLETE\"}");
    }

    private static Fixture fixture(boolean sharedQuery, boolean recommendation, String qualityJson) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:report_synthesis_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260924_4__campaign_report_synthesis.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcCampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal-1", recommendation ? "分析访问变化并提出建议" : "分析访问变化",
                        true, recommendation ? "数据、分析与建议" : "数据与分析")), List.of());
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", List.of(
                requirement("goal-1-data", PlanningAssessment.RequirementKind.DATA, "statistics-data"),
                requirement("goal-1-analysis", PlanningAssessment.RequirementKind.CALCULATION, "analysis-interpretation-dimensions"),
                requirement("goal-1-recommendation", PlanningAssessment.RequirementKind.CALCULATION, "analysis-recommendation"),
                requirement("goal-1-causal", PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE, "causal-evidence")), List.of(), List.of());
        if (!recommendation) assessment = new PlanningAssessment(assessment.planId(), assessment.revision(),
                assessment.capabilityCatalogVersion(), assessment.requirements().stream()
                        .filter(value -> !"goal-1-recommendation".equals(value.requirementId())).toList(), List.of(), List.of());
        Map<String, Object> inputValues = Map.of();
        if (sharedQuery) {
            var executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics", "1");
            plan = new PlanSpec(plan.schemaVersion(), plan.planId(), plan.revision(), plan.runId(), plan.inputSetRef(),
                    List.of(new PlanSpec.Goal("goal-1", "查询访问数据", true, "原始数据"),
                            new PlanSpec.Goal("goal-2", "复用上项数据分析并提出建议", true, "数据、分析与建议")),
                    List.of(new PlanSpec.Step("step-1", List.of("goal-1", "goal-2"), PlanSpec.ExecutionMode.FIXED,
                                    executor, null, List.of(), Map.of("scope", PlanBinding.input("goal-1-scope"),
                                            "periods", PlanBinding.input("goal-1-periods"), "query", PlanBinding.input("goal-1-query"),
                                            "debug", PlanBinding.input("debug-input")), Map.of(), "statistics-result/v1"),
                            new PlanSpec.Step("step-unrelated", List.of("goal-2"), PlanSpec.ExecutionMode.FIXED,
                                    executor, null, List.of(), Map.of("query", PlanBinding.input("unrelated-query")),
                                    Map.of(), "statistics-result/v1")));
            var requirements = new ArrayList<PlanningAssessment.Requirement>();
            requirements.add(requirement("goal-1-data", PlanningAssessment.RequirementKind.DATA, "statistics-data"));
            for (var original : assessment.requirements())
                requirements.add(new PlanningAssessment.Requirement(original.requirementId().replace("goal-1", "goal-2"),
                        "goal-2", original.kind(), original.required(), original.criterionRef(), original.criterionVersion(), original.parameters()));
            assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", requirements,
                    requirements.stream().filter(value -> value.kind() != PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE)
                            .map(value -> new PlanningAssessment.CoverageBinding(value.requirementId(),
                                    List.of(new PlanningAssessment.EvidenceOutput("step-1", "pages")))).toList(), List.of());
            inputValues = Map.of("goal-1-scope", Map.of("scopeRef", "scope-1", "gid", "group-shared", "token", "secret-must-not-leak"),
                    "goal-1-periods", Map.of("periodsRef", "periods-1", "startDate", "2026-09-13", "endDate", "2026-09-14"),
                    "goal-1-query", Map.of("queryKind", "METRICS", "filters", List.of(Map.of("dimension", "device", "values", List.of("Desktop")))),
                    "goal-2-context", Map.of("metric", "PV"), "debug-input", "secret-must-not-leak",
                    "unrelated-query", Map.of("gid", "UNRELATED_QUERY"));
        }
        RunToken token = runs.createRun(FrozenCampaignRun.freeze(plan,
                new FrozenInputSet("inputs-1", "run-1", Map.of(), inputValues), assessment).definition(CALLER, "session-1"));
        runs.prepareAction(token, new ActionSpec("action-1", "step-1", "TOOL", "statistics", "1", "{}"));
        runs.prepareChild(token, new ChildSpec("child-1", "action-1", ChildMode.SYNC, "request-1", new WireRequest("GET", "/test", "{}")));
        DispatchPermit permit = runs.beginDispatch(token, "child-1");
        try {
            runs.publishReady(permit, new ArtifactDraft("artifact-1", "StatisticsJobPages", "stats/v1", "scope-1", "periods-1",
                    qualityJson, "{}", NOW.plusSeconds(3600), "{\"baseline\":100,\"target\":60}"));
        } finally { runs.callbackExited(permit); }
        ArtifactMetadata metadata = runs.inspectArtifact(CALLER, "artifact-1", (caller, artifact) -> true);
        ReportDraft draft = new ReportDraft("report-1", 1, "run-1", "plan-1", 1, List.of(new ReportSection("section-1", 0,
                "访问变化", sharedQuery ? List.of("goal-1", "goal-2") : List.of("goal-1"), List.of(
                        new ReportBlock("metric-1", ReportBlock.Kind.METRIC, "访问", null,
                                Map.of("items", List.of(Map.of("label", "基期", "value", 100), Map.of("label", "目标期", "value", 60))), List.of("artifact-1"), true),
                        new ReportBlock("chart-1", ReportBlock.Kind.CHART, "期间对比", null, Map.of("chartType", "BAR", "labels", List.of("基期", "目标期"),
                                "series", List.of(Map.of("name", "访问", "values", List.of(100, 60)))), List.of("artifact-1"), false)))), List.of());
        Map<String, GoalAssessor.RequirementObservation> observations = new LinkedHashMap<>(Map.of(
                "goal-1-data", new GoalAssessor.RequirementObservation(RequirementAssessment.Verdict.MET, null, List.of("artifact-1")),
                "goal-1-analysis", pending(), "goal-1-recommendation", pending(), "goal-1-causal", pending()));
        if (!recommendation) observations.remove("goal-1-recommendation");
        if (sharedQuery) {
            var sharedObservations = new LinkedHashMap<String, GoalAssessor.RequirementObservation>();
            sharedObservations.put("goal-1-data", observations.get("goal-1-data"));
            observations.forEach((name, value) -> sharedObservations.put(name.replace("goal-1", "goal-2"), value));
            observations = sharedObservations;
        }
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
