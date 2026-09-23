package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model output is a candidate only. Identity, capabilities and authority never come from this DTO. */
public record CampaignInterpretedRequest(String schemaVersion, String question, List<Goal> goals,
        List<String> clarification) {
    public static final String SCHEMA = "campaign-requirements/v1";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Period(String startDate, String endDate) {}
    public record Query(String gid, String fullShortUrl, Period period, String queryKind,
            List<String> dimensions, List<Map<String,Object>> filters) {}
    public record Goal(String question, int sourceStart, int sourceEnd, String method,
            String metric, List<Query> queries, boolean causal, List<Integer> dependsOn,
            boolean needsAnalysis, boolean needsRecommendation) {}

    public static CampaignInterpretedRequest parse(String json, String original) {
        try {
            if (json == null || json.length() > 1024 * 1024) throw invalid();
            var tree=JSON.readTree(json);
            if (!tree.path("goals").isArray()) throw invalid();
            for (var node:tree.path("goals")) {
                if (!node.path("needsAnalysis").isBoolean() || !node.path("needsRecommendation").isBoolean()
                        || !node.path("causal").isBoolean() || !node.path("sourceStart").isIntegralNumber()
                        || !node.path("sourceEnd").isIntegralNumber()) throw invalid();
            }
            var value = JSON.readValue(json, CampaignInterpretedRequest.class);
            if (!SCHEMA.equals(value.schemaVersion()) || !original.equals(value.question())
                    || value.goals() == null || value.goals().isEmpty() || value.clarification() == null) throw invalid();
            boolean[] covered = new boolean[original.length()];
            for (int i=0; i<value.goals().size(); i++) {
                Goal goal = value.goals().get(i);
                if (goal == null || goal.question() == null || goal.question().isBlank()
                        || goal.sourceStart()<0 || goal.sourceEnd()>original.length() || goal.sourceStart()>=goal.sourceEnd()
                        || !Set.of("STATISTICS","DECLINE_DIMENSIONS","EXPLORE","UNRESOLVED").contains(goal.method())
                        || !Set.of("PV","UV","UIP").contains(goal.metric())
                        || goal.queries()==null || goal.dependsOn()==null
                        || goal.dependsOn().stream().anyMatch(index -> index == null || index<0 || index>=value.goals().size()))
                    throw invalid();
                for (int p=goal.sourceStart(); p<goal.sourceEnd(); p++) covered[p]=true;
                for (Query query : goal.queries()) {
                    if (query.gid()==null || !query.gid().matches("[A-Za-z0-9_-]{1,64}") || query.period()==null
                            || !Set.of("METRICS","LINK_METRICS","DIMENSION_BREAKDOWN").contains(query.queryKind())
                            || query.dimensions()==null || query.filters()==null) throw invalid();
                    CampaignStatisticsCurrentInputAuthorizer.parsePeriod("period.v1:"+query.period().startDate()+":"+query.period().endDate());
                    if (query.fullShortUrl()!=null && (query.fullShortUrl().isBlank() || query.fullShortUrl().contains("://")
                            || query.fullShortUrl().length()>2048)) throw invalid();
                    if ("DIMENSION_BREAKDOWN".equals(query.queryKind())) {
                        DimensionQuery.dimensions(query.dimensions()); DimensionQuery.filters(query.filters());
                    } else if (!query.dimensions().isEmpty() || !query.filters().isEmpty()) throw invalid();
                }
            }
            for (int i=0;i<covered.length;i++) if (!covered[i] && !Character.isWhitespace(original.charAt(i))) throw invalid();
            return value;
        } catch (Exception invalid) { throw invalid(); }
    }

    public static String instructions() {
        return """
                Extract the complete user's analytics requirements as JSON, never execute tools. Preserve the
                exact question and every requested objective, including unsupported or ambiguous objectives.
                goals[].sourceStart/sourceEnd are zero-based UTF-16 spans; their union must cover every
                non-whitespace character of the original question. Spans can overlap for shared context.
                Keep meaningful individual goals, not one goal for every metric. Use only gids explicitly
                supplied by the user/selected context; never invent a group identifier. If a group is ambiguous,
                add the missing information to clarification and retain the goal with queries=[].
                Dates are inclusive Asia/Shanghai calendar dates relative to frozenToday. Never silently
                replace a requested interval. For ordinary recent analytics without dates use the last seven
                calendar days including frozenToday and mention this assumption in goal.question.
                STATISTICS uses one or more METRICS, LINK_METRICS or DIMENSION_BREAKDOWN queries.
                EXPLORE requests bounded local investigation with the same exact authorized queries.
                DECLINE_DIMENSIONS is only for selecting ALL declining links in one group between TWO
                non-overlapping periods then examining province+device joint changes of that selected cohort.
                It has two LINK_METRICS queries in baseline,target order, metric PV/UV/UIP, no URL/filter.
                Other dependency requests remain as objectives with dependsOn (zero-based goal indices).
                UNRESOLVED retains requests outside these data capabilities; do not invent revenue/conversions.
                causal=true if the user asks to establish a cause, attribution or impact. Observational data
                cannot alone establish causality. Dimensions/filters use the server's supplied exact vocabulary.
                needsAnalysis=true for requested interpretation, diagnosis, explanation or analysis beyond
                retrieving facts; needsRecommendation=true for requested advice or next actions. Do not turn
                these off merely because the available tools only retrieve data.
                Return all required fields. fullShortUrl is null unless explicitly specified; dimensions,
                filters and dependsOn are arrays (empty where not applicable); metric defaults to PV.
                No tenant, owner, session, Run, credential, tool code or free-form executable instructions.
                """;
    }

    public static String schemaJson() {
        return """
                {"type":"object","additionalProperties":false,"required":["schemaVersion","question","goals","clarification"],
                "properties":{"schemaVersion":{"const":"campaign-requirements/v1"},"question":{"type":"string"},
                "clarification":{"type":"array","items":{"type":"string"}},"goals":{"type":"array","minItems":1,
                "items":{"type":"object","additionalProperties":false,"required":["question","sourceStart","sourceEnd","method","metric","queries","causal","dependsOn","needsAnalysis","needsRecommendation"],
                "properties":{"question":{"type":"string"},"sourceStart":{"type":"integer"},"sourceEnd":{"type":"integer"},
                "method":{"enum":["STATISTICS","DECLINE_DIMENSIONS","EXPLORE","UNRESOLVED"]},"metric":{"enum":["PV","UV","UIP"]},
                "causal":{"type":"boolean"},"dependsOn":{"type":"array","items":{"type":"integer"}},
                "needsAnalysis":{"type":"boolean"},"needsRecommendation":{"type":"boolean"},
                "queries":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["gid","fullShortUrl","period","queryKind","dimensions","filters"],
                "properties":{"gid":{"type":"string"},"fullShortUrl":{"type":["string","null"]},"period":{"type":"object","required":["startDate","endDate"],"additionalProperties":false,"properties":{"startDate":{"type":"string"},"endDate":{"type":"string"}}},
                "queryKind":{"enum":["METRICS","LINK_METRICS","DIMENSION_BREAKDOWN"]},"dimensions":{"type":"array","items":{"enum":["day","hour","weekday","country","province","device","os","browser","isp","refererDomain"]}},"filters":{"type":"array","items":{"type":"object"}}}}}}}}}
                """;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("CAMPAIGN_REQUIREMENTS_INVALID"); }
}
