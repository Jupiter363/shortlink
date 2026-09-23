package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CampaignArtifactReportAssemblerTest {
    private static final JsonMapper JSON = JsonMapper.builder().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    @Test void ranksEntireResultByPvInsteadOfIdOrFirstTwelveRows() throws Exception {
        var data = new ArrayList<Map<String,Object>>();
        for (int id = 1; id <= 15; id++) data.add(row(id, id));
        var f = fixture(data, 120, true, 1, 0);
        var result = f.assemble(); var ranking = ranking(result);
        assertThat(ranking.completeResult()).isTrue();
        assertThat(ranked(ranking)).extracting(value -> ((Number) value.get("linkId")).intValue())
                .containsExactly(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        assertThat(ranked(ranking)).extracting(value -> ((Number) value.get("rank")).intValue())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        assertThat(ranking.payload()).doesNotContainKeys("artifactId", "nextCursor");
        assertThat(result.observations().get("goal-1-ranking-1").verdict()).isEqualTo(RequirementAssessment.Verdict.MET);
    }

    @Test void zeroTiesShareRankAndTopOneIncludesAllBoundaryTies() throws Exception {
        var f = fixture(List.of(row(9, 0), row(2, 0), row(4, 0)), 0, true, 1, 1);
        var ranking = ranking(f.assemble());
        assertThat(ranked(ranking)).extracting(value -> ((Number) value.get("linkId")).intValue()).containsExactly(2, 4, 9);
        assertThat(ranked(ranking)).extracting(value -> ((Number) value.get("rank")).intValue()).containsExactly(1, 1, 1);
        assertThat(ranking.payload()).containsEntry("populationSize", 3L).containsEntry("topN", 1)
                .containsEntry("tiePolicy", "COMPETITION_INCLUDE_BOUNDARY_TIES");
    }

    @Test void incompleteOrUnreconciledDataCannotCompleteRanking() throws Exception {
        for (var f : List.of(fixture(List.of(row(1, 2), row(2, 7)), 9, false, 0, 0),
                fixture(List.of(row(1, 2), row(2, 7)), 10, true, 1, 0))) {
            var result = f.assemble();
            assertThat(result.draft().blocks()).noneMatch(block -> block.blockId().equals("ranking-goal-1-ranking-1"));
            assertThat(result.observations().get("goal-1-ranking-1").verdict()).isEqualTo(RequirementAssessment.Verdict.UNKNOWN);
            var frozen = FrozenCampaignRun.read(f.token.definition());
            assertThat(new GoalAssessor().assess(new GoalAssessor.Input(frozen.plan(), frozen.assessment(), result.observations(), result.draft()))
                    .goals()).allMatch(goal -> goal.status() != GoalAssessment.Status.ANSWERED);
        }
    }

    @Test void distinguishesCompleteZeroValuedRowsEmptyResultAndPagedPreview() throws Exception {
        for (int totalRows : List.of(2, 0, 13)) {
            var data = new ArrayList<Map<String,Object>>();
            for (int id = 1; id <= totalRows; id++) data.add(row(id, 0));
            var result = fixture(data, 0, true, 1, 0).assemble();
            var table = result.draft().blocks().stream().filter(block -> block.blockId().endsWith("-table")).findFirst().orElseThrow();
            assertThat(table.payload().get("totalRows")).isEqualTo((long) totalRows);
            assertThat(ranked(table)).hasSize(Math.min(totalRows, 12)).allSatisfy(value ->
                    assertThat(((Number) value.get("pv")).longValue()).isZero());
            assertThat(table.completeResult()).isEqualTo(totalRows <= 12);
            assertThat(table.payload().get("nextCursor") == null).isEqualTo(totalRows <= 12);
            assertThat(result.draft().blocks()).filteredOn(block -> block.kind() == ReportBlock.Kind.RESULT_LINK)
                    .singleElement().satisfies(block -> {
                        assertThat(block.completeResult()).isTrue();
                        assertThat(block.payload()).containsEntry("rowCount", (long) totalRows);
                    });
            var limitation = result.draft().blocks().stream().filter(block -> block.kind() == ReportBlock.Kind.LIMITATION)
                    .findFirst().orElseThrow().text();
            if (totalRows <= 12)
                assertThat(limitation).contains("当前已展示完整结果，共 " + totalRows + " 条").doesNotContain("部分行", "当前仅展示");
            else assertThat(limitation).contains("当前仅展示 12 条，完整结果共 13 条").doesNotContain("当前已展示完整结果");
            var analysis = result.draft().blocks().stream().filter(block -> block.blockId().endsWith("-analysis"))
                    .findFirst().orElseThrow().text();
            assertThat(analysis).contains("该统计快照包含 " + totalRows + " 条结果");
            if (totalRows == 0) assertThat(analysis).contains("完整查询返回零行，属于空结果");
            else assertThat(analysis).contains("指标为零的记录仍属于已返回结果，不代表零行");
        }
    }

    private static Map<String,Object> row(long id, long pv) { return Map.of("linkId", id, "pv", pv, "uv", 0, "uip", 0); }
    private static ReportBlock ranking(CampaignArtifactReportAssembler.Assembled result) {
        return result.draft().blocks().stream().filter(block -> block.blockId().equals("ranking-goal-1-ranking-1")).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> ranked(ReportBlock block) {
        return (List<Map<String,Object>>) block.payload().get("rows");
    }
    private static Fixture fixture(List<Map<String,Object>> data, long summary, boolean complete, int pageCount, int topN) throws Exception {
        Caller caller = new Caller("tenant", "subject", 1);
        Map<String,Object> wire = Map.of("gid", "group", "queryKind", "LINK_METRICS", "startDate", "2026-09-13", "endDate", "2026-09-13");
        var requirement = new PlanningAssessment.Requirement("goal-1-ranking-1", "goal-1", PlanningAssessment.RequirementKind.CALCULATION,
                true, "statistics-ranking", "1", Map.of("queryHash", CampaignRunStore.sha256(JSON.writeValueAsString(wire)),
                        "metric", "PV", "order", "DESC", "topN", topN));
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan", 1, "run", "inputs",
                List.of(new PlanSpec.Goal("goal-1", "短链排名", true, "完整排名")), List.of());
        var assessment = new PlanningAssessment("plan", 1, "catalog", List.of(requirement),
                List.of(new PlanningAssessment.CoverageBinding(requirement.requirementId(), List.of(new PlanningAssessment.EvidenceOutput("query", "pages")))), List.of());
        var definition = FrozenCampaignRun.freeze(plan, new FrozenInputSet("inputs", "run", Map.of(), Map.of()), assessment).definition(caller, "session");
        var token = new RunToken(definition, 1, "advance");
        String body = JSON.writeValueAsString(Map.of("resultComplete", complete, "totalRows", data.size(), "receivedPageCount", pageCount,
                "metrics", Map.of("requested", Map.of("pv", summary, "uv", 0, "uip", 0))));
        var metadata = new ArtifactMetadata(new ArtifactRef("artifact", "StatisticsJobPages", "statistics-job-pages/v1", CampaignRunStore.sha256(body),
                "scope", "period", Instant.parse("2026-10-01T00:00:00Z")), caller, "run", "plan", 1, "action", "child", "1", "{}", "{}");
        var runs = mock(CampaignRunStore.class);
        when(runs.readArtifact(eq(caller), eq("artifact"), any())).thenReturn(new Artifact(metadata, body));
        when(runs.inspectArtifact(eq(caller), eq("artifact"), any())).thenReturn(metadata);
        when(runs.child(eq(token), eq("child"))).thenReturn(Optional.of(new ChildRecord(new ChildSpec("child", "action", ChildMode.ASYNC,
                "request", new WireRequest("POST", "/statistics", JSON.writeValueAsString(wire))), ChildState.READY,
                "job", "artifact", null, 1, null, false, null)));
        var steps = mock(CampaignStepStore.class);
        when(steps.steps(token)).thenReturn(List.of(new CampaignStepStore.StepRecord(new CampaignStepStore.StepSpec("query", "{}", List.of(),
                Set.of("pages"), Set.of("pages")), CampaignStepStore.StepStatus.SUCCEEDED, 1, null, false, null, Map.of("pages", "artifact"))));
        var statistics = mock(CampaignStatisticsResultStore.class);
        when(statistics.readPage(eq(caller), eq("artifact"), eq(0), any())).thenReturn(JSON.writeValueAsString(Map.of("items", data)));
        return new Fixture(caller, token, new CampaignArtifactReportAssembler(runs, steps,
                new CampaignArtifactReportRows(runs, statistics, mock(CampaignDeclineSelectionStore.class))));
    }
    private record Fixture(Caller caller, RunToken token, CampaignArtifactReportAssembler assembler) {
        CampaignArtifactReportAssembler.Assembled assemble() { return assembler.assemble(caller, token, 1, (user, artifact) -> user.equals(artifact.owner())); }
    }
}
