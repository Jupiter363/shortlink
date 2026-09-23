package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignInterpretedRequestTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GID = "1ff4df996bf244bdb86865533a92b12c";
    private static final String QUESTION = "分析范围：指定分组，gid=" + GID + "\r\n"
            + "比较 2026-09-01 至 2026-09-07 与 2026-09-08 至 2026-09-14 的访问量📊；\n"
            + "找出下降短链，并按省份与设备联合下钻。\n另看分组 group-b 的访问情况🙂。  ";

    @Test void optionalRankingPreservesLegacyAndRequiresWholePeriodLinkQueries() throws Exception {
        String question = "短链 PV 排名前 2 名";
        var goal = new LinkedHashMap<>(goal(question, List.of("source-1"), GID));
        assertNull(CampaignInterpretedRequest.parse(wire(List.of(goal)), question).goals().get(0).ranking());
        goal.put("ranking", Map.of("order", "DESC", "topN", 2));
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(wire(List.of(goal)), question));
        var query = new LinkedHashMap<>((Map<String,Object>) ((List<?>) goal.get("queries")).get(0));
        query.put("queryKind", "LINK_METRICS"); goal.put("queries", List.of(query));
        var parsed = CampaignInterpretedRequest.parse(wire(List.of(goal)), question);
        assertEquals(new CampaignInterpretedRequest.Ranking("DESC", 2), parsed.goals().get(0).ranking());
        assertEquals(parsed, CampaignInterpretedRequest.parse(FrozenCampaignRun.encode(parsed), question));
        goal.put("ranking", Map.of("order", "DESC", "topN", 0));
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(wire(List.of(goal)), question));
    }

    @Test void serverSourceIdsPreserveLongGroupContextChineseEmojiAndEveryOriginalGoal() throws Exception {
        var units = CampaignInterpretedRequest.sourceUnits(QUESTION);
        assertEquals(4, units.size());
        assertEquals(QUESTION, units.stream().map(CampaignInterpretedRequest.SourceUnit::text).reduce("", String::concat));
        assertEquals(units, CampaignInterpretedRequest.sourceUnits(QUESTION));
        String prompt = CampaignInterpretedRequest.prompt(QUESTION, LocalDate.of(2026, 9, 24));
        assertEquals(QUESTION, JSON.readTree(prompt).path("question").asText());
        assertEquals("source-1", JSON.readTree(prompt).path("sourceUnits").get(0).path("sourceId").asText());
        assertEquals("2026-09-24", JSON.readTree(prompt).path("frozenToday").asText());
        var value = CampaignInterpretedRequest.parse("```json\n" + wire(List.of(
                goal("比较并下钻", List.of("source-1", "source-2", "source-3"), GID),
                goal("独立分组访问", List.of("source-1", "source-4"), "group-b"))) + "\n```", QUESTION);
        assertEquals(QUESTION, value.question());
        assertEquals(CampaignInterpretedRequest.SCHEMA, value.schemaVersion());
        assertEquals(2, value.goals().size());
        assertEquals(List.of("比较并下钻", "独立分组访问"), value.goals().stream().map(CampaignInterpretedRequest.Goal::question).toList());
        assertEquals(0, value.goals().get(0).sourceStart());
        assertEquals(units.subList(0, 3).stream().mapToInt(unit -> unit.text().length()).sum(), value.goals().get(0).sourceEnd());
        assertTrue(QUESTION.substring(value.goals().get(0).sourceStart(), value.goals().get(0).sourceEnd()).contains("📊"));
        assertEquals(QUESTION.length(), value.goals().get(1).sourceEnd());
        assertEquals(GID, value.goals().get(0).queries().get(0).gid());
        assertEquals("group-b", value.goals().get(1).queries().get(0).gid());
        assertEquals(value, CampaignInterpretedRequest.parse(FrozenCampaignRun.encode(value), QUESTION),
                "Normalized old DTO remains strictly readable for existing planning/history contracts");
    }

    @Test void unknownDuplicateAndUncoveredSourcesFailWithoutFillingGapsBetweenDerivedSpans() throws Exception {
        // First and last IDs would span the entire question; the unclaimed middle units still reject.
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(
                wire(List.of(goal("遗漏目标", List.of("source-1", "source-4"), GID))), QUESTION));
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(
                wire(List.of(goal("未知来源", List.of("source-1", "source-2", "source-3", "source-999"), GID))), QUESTION));
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(
                wire(List.of(goal("重复来源", List.of("source-1", "source-2", "source-3", "source-4", "source-4"), GID))), QUESTION));
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(
                wire(List.of(goal("无来源", List.of(), GID))), QUESTION));
        var rewritten = JSON.readTree(wire(List.of(goal("改写原文", List.of("source-1", "source-2", "source-3", "source-4"), GID))));
        ((com.fasterxml.jackson.databind.node.ObjectNode) rewritten).put("question", "model-rewritten-question");
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(rewritten.toString(), QUESTION));
    }

    @Test void currentSchemaUsesSourceIdsWhileLegacyV1StillRequiresExactQuestionAndSpans() throws Exception {
        var schema = JSON.readTree(CampaignInterpretedRequest.schemaJson());
        assertEquals(CampaignInterpretedRequest.WIRE_SCHEMA, schema.path("properties").path("schemaVersion").path("const").asText());
        var goalSchema = schema.path("properties").path("goals").path("items").path("properties");
        assertTrue(goalSchema.has("sourceIds")); assertFalse(goalSchema.has("sourceStart"));
        var legacyGoal = new LinkedHashMap<>(goal("完整需求", List.of("source-1"), GID));
        legacyGoal.remove("sourceIds"); legacyGoal.put("sourceStart", 0); legacyGoal.put("sourceEnd", QUESTION.length());
        var legacy = new LinkedHashMap<String, Object>();
        legacy.put("schemaVersion", CampaignInterpretedRequest.SCHEMA); legacy.put("question", QUESTION);
        legacy.put("goals", List.of(legacyGoal)); legacy.put("clarification", List.of());
        assertEquals(QUESTION, CampaignInterpretedRequest.parse(JSON.writeValueAsString(legacy), QUESTION).question());
        legacyGoal.put("sourceStart", 1);
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(JSON.writeValueAsString(legacy), QUESTION));
        legacyGoal.put("sourceStart", 0); legacy.put("question", "改变范围");
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(JSON.writeValueAsString(legacy), QUESTION));
    }

    @Test void currentSourcesExcludePriorReferenceWhileQueryKindsStayStrict() throws Exception {
        String current = "比较9月13日与9月14日；再说明局限。";
        var prompt = JSON.readTree(CampaignInterpretedRequest.prompt(current, LocalDate.of(2026, 9, 24), QUESTION));
        assertEquals(current, prompt.path("question").asText());
        assertEquals(QUESTION, prompt.path("priorContext").path("question").asText());
        assertEquals("REFERENCE_ONLY", prompt.path("priorContext").path("purpose").asText());
        assertEquals(2, prompt.path("sourceUnits").size());
        StringBuilder covered = new StringBuilder();
        prompt.path("sourceUnits").forEach(unit -> covered.append(unit.path("text").asText()));
        assertEquals(current, covered.toString());

        var response = JSON.readTree(wire(List.of(goal("比较两期并说明局限", List.of("source-1", "source-2"), GID))));
        var queries = (com.fasterxml.jackson.databind.node.ArrayNode) response.path("goals").get(0).path("queries");
        var query = (com.fasterxml.jackson.databind.node.ObjectNode) queries.get(0);
        query.put("queryKind", "LINK_METRICS"); query.putArray("dimensions").add("day");
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(response.toString(), current));
        query.putArray("dimensions");
        query.putObject("period").put("startDate", "2026-09-13").put("endDate", "2026-09-13");
        var target = query.deepCopy();
        target.putObject("period").put("startDate", "2026-09-14").put("endDate", "2026-09-14");
        queries.add(target);
        var parsed = CampaignInterpretedRequest.parse(response.toString(), current);
        assertEquals(current, parsed.question()); assertEquals(1, parsed.goals().size());
        assertEquals(2, parsed.goals().get(0).queries().size());
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(response.toString(), QUESTION));

        var goalSchema = JSON.readTree(CampaignInterpretedRequest.schemaJson()).path("properties").path("goals").path("items");
        assertEquals(2, goalSchema.path("allOf").get(0).path("then").path("properties").path("queries").path("maxItems").asInt());
        var querySchema = goalSchema.path("properties").path("queries").path("items");
        assertEquals(0, querySchema.path("allOf").get(0).path("then").path("properties").path("dimensions").path("maxItems").asInt(-1));
        assertEquals(0, querySchema.path("allOf").get(0).path("then").path("properties").path("filters").path("maxItems").asInt(-1));
    }

    @Test void requirementsGuideSharesMultiMetricQueriesAndKeepsSelectedCohortBoundary() throws Exception {
        var properties = JSON.readTree(CampaignInterpretedRequest.schemaJson())
                .path("properties").path("goals").path("items").path("properties");
        assertTrue(properties.path("queries").path("uniqueItems").asBoolean());
        assertTrue(properties.path("dependsOn").path("uniqueItems").asBoolean());
        assertEquals(0, properties.path("dependsOn").path("items").path("minimum").asInt(-1));
        assertTrue(properties.path("queries").path("description").asText().contains("PV, UV and UIP together"));
        assertTrue(properties.path("queries").path("description").asText().contains("queries=[]"));
        assertTrue(properties.path("method").path("description").asText().contains("even across clauses"));
        assertTrue(properties.path("method").path("description").asText().contains("unsupported operations remain UNRESOLVED"));
        String instructions = CampaignInterpretedRequest.instructions();
        assertTrue(instructions.contains("Use DECLINE_DIMENSIONS whenever the complete request"));
        assertTrue(instructions.contains("Keep this complete selection-and-drilldown chain in ONE"));
        assertTrue(instructions.contains("Do not split\nthis covered chain"));
        assertTrue(instructions.contains("not turn a CURRENT_GROUP DIMENSION_BREAKDOWN"));
        assertTrue(instructions.contains("Other investigations\nmay use EXPLORE"));
        assertTrue(instructions.contains("DECLINE_DIMENSIONS is an implemented Skill"));
        assertTrue(instructions.contains("restricts BOTH periods' province+device queries"));
        assertTrue(instructions.contains("an empty selection is valid"));
        assertTrue(instructions.contains("does not require unknown-region rows to exist"));
        assertTrue(instructions.contains("not missing user inputs"));
        assertTrue(instructions.contains("NOT to infer unproven causes sets causal=false"));
        var clarification = JSON.readTree(CampaignInterpretedRequest.schemaJson()).path("properties").path("clarification");
        assertTrue(clarification.path("description").asText().contains("Only missing or ambiguous user inputs"));
        assertTrue(clarification.path("description").asText().contains("unknown-region rows exist"));
        assertTrue(properties.path("method").path("description").asText().contains("freezes actual selected link IDs"));
        assertTrue(instructions.contains("Source coverage does not require a separate goal for every source unit"));
        assertTrue(instructions.contains("Do not create a standalone EXPLORE/UNRESOLVED goal with no new deliverable"));
        assertTrue(instructions.contains("never drop an independent intent for lacking queries"));
        assertTrue(properties.path("question").path("description").asText().contains("independent explanations or recommendations"));
        assertTrue(properties.path("sourceIds").path("description").asText().contains("instead of creating a constraint-only goal"));
    }

    @Test void supportedSelectedCohortAndUnknownRegionReportingNeedNoInventedInputButRealClarificationSurvives() throws Exception {
        String question = "分析范围 gid=" + GID + "\n"
                + "比较 2026-09-13 与 2026-09-14 各短链 PV UV UIP\n"
                + "筛出 PV 下降短链且仅对该集合做省份设备联合下钻并标明地域未知\n"
                + "另独立按这两天合计 PV 完整降序排名，相同 PV 并列\n"
                + "下钻必须使用前一步实际筛选集合，保留证据缺口与统计局限，不推断未经证实的原因";
        assertEquals(5, CampaignInterpretedRequest.sourceUnits(question).size());
        var baseline = linkQuery("2026-09-13", "2026-09-13");
        var target = linkQuery("2026-09-14", "2026-09-14");
        var comparison = goal("两日各短链三指标比较，保留证据缺口与统计局限，不推断原因",
                List.of("source-1", "source-2", "source-5"), GID);
        comparison.put("queries", List.of(baseline, target));
        var selected = goal("实际下降集合联合下钻，标明地域未知与统计局限，不推断原因",
                List.of("source-1", "source-3", "source-5"), GID);
        selected.put("method", "DECLINE_DIMENSIONS"); selected.put("queries", List.of(baseline, target));
        selected.put("dependsOn", List.of(0));
        var ranking = goal("独立完整并列排名，保留证据缺口与统计局限", List.of("source-1", "source-4", "source-5"), GID);
        ranking.put("queries", List.of(linkQuery("2026-09-13", "2026-09-14"))); ranking.put("needsAnalysis", false);
        var rankingOptions = new LinkedHashMap<String,Object>();
        rankingOptions.put("order", "DESC"); rankingOptions.put("topN", null); ranking.put("ranking", rankingOptions);

        var parsed = CampaignInterpretedRequest.parse(wire(List.of(comparison, selected, ranking)), question);
        assertTrue(parsed.clarification().isEmpty());
        assertEquals(List.of("STATISTICS", "DECLINE_DIMENSIONS", "STATISTICS"),
                parsed.goals().stream().map(CampaignInterpretedRequest.Goal::method).toList());
        assertEquals(List.of(2, 2, 1), parsed.goals().stream().map(goal -> goal.queries().size()).toList());
        assertEquals(List.of(0), parsed.goals().get(1).dependsOn());
        assertEquals(List.of("2026-09-13", "2026-09-14"), parsed.goals().get(1).queries().stream()
                .map(query -> query.period().startDate()).toList());
        assertTrue(parsed.goals().stream().noneMatch(CampaignInterpretedRequest.Goal::causal));
        assertTrue(parsed.goals().get(1).queries().stream().allMatch(query -> query.filters().isEmpty()
                && query.dimensions().isEmpty()), "The Skill determines actual selected IDs and joint dimensions at execution");
        assertTrue(parsed.goals().get(2).dependsOn().isEmpty());
        assertEquals(new CampaignInterpretedRequest.Ranking("DESC", null), parsed.goals().get(2).ranking());
        assertTrue(parsed.goals().get(1).question().contains("实际下降集合"));
        assertTrue(parsed.goals().stream().allMatch(goal -> goal.sourceEnd() == question.length()),
                "The final constraint source is retained by the three existing goals, without a fourth exploratory goal");

        String withFollowup = question + "\n另解释两日比较和排名结论的可信度局限，并基于现有证据给下一步核查建议";
        var followup = goal("解释比较与排名结论的可信度局限并给核查建议", List.of("source-1", "source-5", "source-6"), GID);
        followup.put("queries", List.of()); followup.put("dependsOn", List.of(0, 2)); followup.put("needsRecommendation", true);
        var expanded = CampaignInterpretedRequest.parse(wire(List.of(comparison, selected, ranking, followup)), withFollowup);
        assertEquals(4, expanded.goals().size(), "An explicit new explanation/recommendation must not be swallowed as a constraint");
        assertEquals(parsed.goals().stream().map(CampaignInterpretedRequest.Goal::question).toList(),
                expanded.goals().subList(0, 3).stream().map(CampaignInterpretedRequest.Goal::question).toList());
        assertTrue(expanded.goals().get(3).queries().isEmpty());
        assertEquals(List.of(0, 2), expanded.goals().get(3).dependsOn());
        assertTrue(expanded.goals().get(3).needsAnalysis());
        assertTrue(expanded.goals().get(3).needsRecommendation());

        // Guidance must not strip a real missing input or let model-supplied result IDs enter the contract.
        String missingQuestion = "比较未指定分组的两天访问情况";
        var missing = goal("待指定分组的比较", List.of("source-1"), GID); missing.put("queries", List.of());
        String clarification = JSON.writeValueAsString(Map.of("schemaVersion", CampaignInterpretedRequest.WIRE_SCHEMA,
                "goals", List.of(missing), "clarification", List.of("请指定要分析的分组。")));
        assertEquals(List.of("请指定要分析的分组。"), CampaignInterpretedRequest.parse(clarification, missingQuestion).clarification());
        selected.put("selectedLinkIds", List.of("20003"));
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(
                wire(List.of(comparison, selected, ranking)), question));
    }

    @Test void mixedRequirementsRetainSharedEvidenceComparisonCohortRankingAndExplorationWithoutWeakeningQueries() throws Exception {
        String question = "分析范围 gid=" + GID + "\n"
                + "分别查询 2026-09-13 与 2026-09-14 的短链 PV UV UIP\n"
                + "比较两个日期的变化\n筛选所有 PV 下降短链\n只对这些短链做省份设备联合下钻\n"
                + "独立列出这两天合计的全部短链 PV 排名\n独立探索 group-b 的浏览器分布\n评估收入变化";
        assertEquals(8, CampaignInterpretedRequest.sourceUnits(question).size());
        var baseline = linkQuery("2026-09-13", "2026-09-13");
        var target = linkQuery("2026-09-14", "2026-09-14");
        var facts = goal("两个日期的 PV UV UIP", List.of("source-1", "source-2"), GID);
        facts.put("queries", List.of(baseline, target)); facts.put("needsAnalysis", false);
        var comparison = goal("比较已取得的两个日期数据", List.of("source-1", "source-3"), GID);
        comparison.put("queries", List.of()); comparison.put("dependsOn", List.of(0));
        var selected = goal("全部下降集合的省份设备联合变化", List.of("source-1", "source-3", "source-4", "source-5"), GID);
        selected.put("method", "DECLINE_DIMENSIONS"); selected.put("queries", List.of(baseline, target));
        selected.put("dependsOn", List.of(0));
        var ranking = goal("两天合计完整排名", List.of("source-1", "source-6"), GID);
        ranking.put("queries", List.of(linkQuery("2026-09-13", "2026-09-14")));
        var rankingOptions = new LinkedHashMap<String, Object>();
        rankingOptions.put("order", "DESC"); rankingOptions.put("topN", null); ranking.put("ranking", rankingOptions);
        var explore = goal("独立浏览器分布调查", List.of("source-7"), "group-b");
        explore.put("method", "EXPLORE");
        var browser = linkQuery("2026-09-13", "2026-09-14");
        browser.put("gid", "group-b"); browser.put("queryKind", "DIMENSION_BREAKDOWN");
        browser.put("dimensions", List.of("browser")); explore.put("queries", List.of(browser));
        var unsupported = goal("收入变化证据缺口", List.of("source-8"), GID);
        unsupported.put("method", "UNRESOLVED"); unsupported.put("queries", List.of());
        var goals = List.of(facts, comparison, selected, ranking, explore, unsupported);

        var parsed = CampaignInterpretedRequest.parse(wire(goals), question);
        assertEquals(List.of("STATISTICS", "STATISTICS", "DECLINE_DIMENSIONS", "STATISTICS", "EXPLORE", "UNRESOLVED"),
                parsed.goals().stream().map(CampaignInterpretedRequest.Goal::method).toList());
        assertEquals(2, parsed.goals().get(0).queries().size());
        assertTrue(parsed.goals().get(1).queries().isEmpty());
        assertEquals(List.of(0), parsed.goals().get(1).dependsOn());
        assertEquals(parsed.goals().get(0).queries(), parsed.goals().get(2).queries());
        assertTrue(parsed.goals().get(3).dependsOn().isEmpty(), "Independent ranking is not serialized behind drilldown");
        assertEquals(new CampaignInterpretedRequest.Ranking("DESC", null), parsed.goals().get(3).ranking());
        assertEquals("group-b", parsed.goals().get(4).queries().get(0).gid());
        assertEquals(parsed, CampaignInterpretedRequest.parse(FrozenCampaignRun.encode(parsed), question),
                "The new guidance does not invalidate historical normalized responses");
        baseline.put("dimensions", List.of("province"));
        assertThrows(IllegalArgumentException.class, () -> CampaignInterpretedRequest.parse(wire(goals), question),
                "Selected-cohort guidance must not relax the LINK_METRICS query contract");
    }

    @Test void newWireDeduplicatesOnlyExactQueriesWithinEachEligibleGoalAndPreservesFrozenHistory() throws Exception {
        String question = "基线日三指标\n目标日三指标\n探索两个日期\n下降集合联合下钻";
        var baseline = linkQuery("2026-09-13", "2026-09-13");
        var target = linkQuery("2026-09-14", "2026-09-14");
        var first = goal("基线日 PV UV UIP", List.of("source-1"), GID);
        first.put("queries", List.of(baseline, new LinkedHashMap<>(baseline), baseline));
        var second = goal("目标日 PV UV UIP", List.of("source-2"), GID);
        second.put("queries", List.of(target, new LinkedHashMap<>(target), target));
        var explore = goal("探索两个日期", List.of("source-3"), GID);
        explore.put("method", "EXPLORE"); explore.put("queries", List.of(target, baseline, target));
        var decline = goal("全部下降集合联合下钻", List.of("source-4"), GID);
        decline.put("method", "DECLINE_DIMENSIONS"); decline.put("queries", List.of(baseline, target));
        String response = wire(List.of(first, second, explore, decline));

        var parsed = CampaignInterpretedRequest.parse(response, question);
        assertEquals(List.of(1, 1, 2, 2), parsed.goals().stream().map(goal -> goal.queries().size()).toList());
        assertEquals(List.of("2026-09-14", "2026-09-13"), parsed.goals().get(2).queries().stream()
                .map(query -> query.period().startDate()).toList(), "Distinct periods and their first-seen order are retained");
        assertEquals(List.of("2026-09-13", "2026-09-14"), parsed.goals().get(3).queries().stream()
                .map(query -> query.period().startDate()).toList(), "The dependency scenario keeps its baseline/target pair");
        assertEquals(3, JSON.readTree(response).path("goals").get(0).path("queries").size(),
                "The original durable model response remains unchanged");
        assertEquals(parsed, CampaignInterpretedRequest.parse(response, question), "Retry parsing is deterministic");
        assertEquals(parsed, CampaignInterpretedRequest.parse(FrozenCampaignRun.encode(parsed), question));

        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(response);
        legacy.put("schemaVersion", CampaignInterpretedRequest.SCHEMA); legacy.put("question", question);
        for (var item : legacy.path("goals")) {
            var goal = (com.fasterxml.jackson.databind.node.ObjectNode) item;
            goal.remove("sourceIds"); goal.put("sourceStart", 0); goal.put("sourceEnd", question.length());
        }
        assertEquals(List.of(3, 3, 3, 2), CampaignInterpretedRequest.parse(legacy.toString(), question).goals()
                .stream().map(goal -> goal.queries().size()).toList(), "Already frozen v1 definitions cannot be rewritten");
    }

    private static Map<String, Object> linkQuery(String start, String end) {
        var query = new LinkedHashMap<String, Object>();
        query.put("gid", GID); query.put("fullShortUrl", null);
        query.put("period", Map.of("startDate", start, "endDate", end));
        query.put("queryKind", "LINK_METRICS"); query.put("dimensions", List.of()); query.put("filters", List.of());
        return query;
    }

    private static Map<String, Object> goal(String question, List<String> ids, String gid) {
        var value = new LinkedHashMap<String, Object>();
        value.put("question", question); value.put("sourceIds", ids); value.put("method", "STATISTICS");
        value.put("metric", "PV"); value.put("causal", false); value.put("dependsOn", List.of());
        value.put("needsAnalysis", true); value.put("needsRecommendation", false);
        var query = new LinkedHashMap<String, Object>();
        query.put("gid", gid); query.put("fullShortUrl", null);
        query.put("period", Map.of("startDate", "2026-09-01", "endDate", "2026-09-07"));
        query.put("queryKind", "METRICS"); query.put("dimensions", List.of()); query.put("filters", List.of());
        value.put("queries", List.of(query));
        return value;
    }
    private static String wire(List<Map<String, Object>> goals) throws Exception {
        return JSON.writeValueAsString(Map.of("schemaVersion", CampaignInterpretedRequest.WIRE_SCHEMA,
                "goals", goals, "clarification", List.of()));
    }
}
