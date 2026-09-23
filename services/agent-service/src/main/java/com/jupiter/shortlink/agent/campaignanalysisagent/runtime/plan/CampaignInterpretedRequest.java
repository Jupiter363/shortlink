package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model output is a candidate only. Identity, capabilities and authority never come from this DTO. */
public record CampaignInterpretedRequest(String schemaVersion, String question, List<Goal> goals,
        List<String> clarification) {
    public static final String SCHEMA = "campaign-requirements/v1";
    public static final String WIRE_SCHEMA = "campaign-requirements/v2";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Period(String startDate, String endDate) {}
    public record Query(String gid, String fullShortUrl, Period period, String queryKind,
            List<String> dimensions, List<Map<String,Object>> filters) {}
    public record Ranking(String order, Integer topN) {}
    public record Goal(String question, int sourceStart, int sourceEnd, String method,
            String metric, List<Query> queries, boolean causal, List<Integer> dependsOn,
            boolean needsAnalysis, boolean needsRecommendation,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Ranking ranking) {
        public Goal(String question, int sourceStart, int sourceEnd, String method, String metric,
                List<Query> queries, boolean causal, List<Integer> dependsOn,
                boolean needsAnalysis, boolean needsRecommendation) {
            this(question, sourceStart, sourceEnd, method, metric, queries, causal, dependsOn,
                    needsAnalysis, needsRecommendation, null);
        }
    }
    public record SourceUnit(String sourceId, String text) {}

    /** Deterministic source locations from frozen text, independent of supported analysis intents. */
    public static List<SourceUnit> sourceUnits(String original) {
        if (original == null || original.isBlank() || original.length() > 16000) throw invalid();
        var units = new ArrayList<SourceUnit>();
        int start = 0;
        for (int index = 0; index < original.length(); index++) {
            char character = original.charAt(index);
            if ("\r\n。！？!?；;".indexOf(character) < 0) continue;
            int end = index + 1;
            String text = original.substring(start, end);
            if (!text.isBlank()) { units.add(new SourceUnit("source-" + (units.size() + 1), text)); start = end; }
        }
        if (start < original.length()) {
            String remaining = original.substring(start);
            if (remaining.isBlank() && !units.isEmpty()) {
                var last = units.get(units.size() - 1);
                units.set(units.size() - 1, new SourceUnit(last.sourceId(), last.text() + remaining));
            } else units.add(new SourceUnit("source-" + (units.size() + 1), remaining));
        }
        return List.copyOf(units);
    }

    /** The model references server-issued IDs; it never computes string offsets or rewrites the question. */
    public static String prompt(String original, LocalDate frozenToday) {
        return prompt(original, frozenToday, null);
    }

    public static String prompt(String original, LocalDate frozenToday, String priorContext) {
        if (frozenToday == null) throw invalid();
        var input = new java.util.LinkedHashMap<String,Object>();
        input.put("question", original); input.put("sourceUnits", sourceUnits(original));
        input.put("frozenToday", frozenToday.toString());
        if (priorContext != null) {
            if (priorContext.isBlank() || priorContext.length() > 16000) throw invalid();
            input.put("priorContext", Map.of("purpose", "REFERENCE_ONLY", "question", priorContext));
        }
        return FrozenCampaignRun.encode(input);
    }

    public static CampaignInterpretedRequest parse(String json, String original) {
        try {
            if (json == null || json.length() > 1024 * 1024) throw invalid();
            var tree=JSON.readTree(com.jupiter.shortlink.agent.campaignanalysisagent.planning.StrictStructuredJson.unwrapSingleFence(json));
            boolean currentWire = WIRE_SCHEMA.equals(tree.path("schemaVersion").asText());
            if (currentWire) tree = normalizeSourceIds(tree, original);
            if (!tree.path("goals").isArray()) throw invalid();
            for (var node:tree.path("goals")) {
                if (!node.path("needsAnalysis").isBoolean() || !node.path("needsRecommendation").isBoolean()
                        || !node.path("causal").isBoolean() || !node.path("sourceStart").isIntegralNumber()
                        || !node.path("sourceEnd").isIntegralNumber()) throw invalid();
                if (node.hasNonNull("ranking")) {
                    var ranking = node.get("ranking");
                    requireFields(ranking, Set.of("order", "topN"));
                    if (!ranking.path("order").isTextual() || !ranking.path("topN").isNull()
                            && (!ranking.path("topN").isIntegralNumber() || !ranking.path("topN").canConvertToInt())) throw invalid();
                }
            }
            var value = JSON.treeToValue(tree, CampaignInterpretedRequest.class);
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
                if (goal.ranking() != null && (!"STATISTICS".equals(goal.method()) || goal.queries().isEmpty()
                        || !Set.of("ASC", "DESC").contains(goal.ranking().order())
                        || goal.ranking().topN() != null && (goal.ranking().topN() < 1 || goal.ranking().topN() > 500)
                        || goal.queries().stream().anyMatch(query -> !"LINK_METRICS".equals(query.queryKind())
                                || query.fullShortUrl() != null))) throw invalid();
            }
            for (int i=0;i<covered.length;i++) if (!covered[i] && !Character.isWhitespace(original.charAt(i))) throw invalid();
            return currentWire ? distinctQueryCandidates(value) : value;
        } catch (Exception invalid) { throw invalid(); }
    }

    /** Normalize only new wire candidates: frozen v1 definitions must remain byte-for-byte rebuildable. */
    private static CampaignInterpretedRequest distinctQueryCandidates(CampaignInterpretedRequest value) {
        var goals = new ArrayList<Goal>(value.goals().size());
        for (Goal goal : value.goals()) {
            List<Query> queries = Set.of("STATISTICS", "EXPLORE").contains(goal.method())
                    ? List.copyOf(new LinkedHashSet<>(goal.queries())) : goal.queries();
            goals.add(queries.size() == goal.queries().size() ? goal : new Goal(goal.question(),
                    goal.sourceStart(), goal.sourceEnd(), goal.method(), goal.metric(), queries, goal.causal(),
                    goal.dependsOn(), goal.needsAnalysis(), goal.needsRecommendation(), goal.ranking()));
        }
        return new CampaignInterpretedRequest(value.schemaVersion(), value.question(), List.copyOf(goals), value.clarification());
    }

    /** v2 wire output normalizes to the existing trusted DTO only after exact source coverage succeeds. */
    private static JsonNode normalizeSourceIds(JsonNode candidate, String original) {
        requireFields(candidate, Set.of("schemaVersion", "goals", "clarification"));
        if (!candidate.path("goals").isArray() || candidate.path("goals").isEmpty()) throw invalid();
        var ranges = new HashMap<String, int[]>();
        int cursor = 0;
        for (SourceUnit unit : sourceUnits(original)) {
            ranges.put(unit.sourceId(), new int[]{cursor, cursor + unit.text().length()});
            cursor += unit.text().length();
        }
        var covered = new HashSet<String>();
        ObjectNode normalized = candidate.deepCopy();
        for (JsonNode value : normalized.path("goals")) {
            var fields = new HashSet<>(Set.of("question", "sourceIds", "method", "metric", "queries", "causal", "dependsOn",
                    "needsAnalysis", "needsRecommendation"));
            if (value.has("ranking")) fields.add("ranking");
            requireFields(value, fields);
            if (!value.path("sourceIds").isArray() || value.path("sourceIds").isEmpty()) throw invalid();
            int start = original.length(), end = 0;
            var selected = new HashSet<String>();
            for (JsonNode source : value.path("sourceIds")) {
                if (!source.isTextual() || !selected.add(source.textValue())) throw invalid();
                int[] range = ranges.get(source.textValue());
                if (range == null) throw invalid();
                start = Math.min(start, range[0]); end = Math.max(end, range[1]);
            }
            covered.addAll(selected);
            ObjectNode goal = (ObjectNode) value;
            goal.remove("sourceIds"); goal.put("sourceStart", start); goal.put("sourceEnd", end);
        }
        // Check actual claimed IDs before deriving broad legacy spans, so a gap between IDs
        // cannot be silently counted as covered by that goal's minimum/maximum offsets.
        if (!covered.equals(ranges.keySet())) throw invalid();
        normalized.put("schemaVersion", SCHEMA); normalized.put("question", original);
        return normalized;
    }

    private static void requireFields(JsonNode value, Set<String> expected) {
        if (!value.isObject()) throw invalid();
        var names = new HashSet<String>(); value.fieldNames().forEachRemaining(names::add);
        if (!expected.equals(names)) throw invalid();
    }

    public static String instructions() {
        return """
                Extract the complete user's analytics requirements as JSON, never execute tools. Preserve
                every requested objective, including unsupported or ambiguous objectives. The server supplies
                sourceUnits with stable sourceId and exact original text. Each goal must reference one or more
                actual IDs in sourceIds; their union must cover ALL supplied source units, including selected
                group/scope context. Goals may share sourceIds for shared context or multiple requests in one
                sentence. Keep all those requests as goals; source coverage alone does not replace analysis.
                Distinguish requested deliverables from constraints on them. "Use the actual selected
                cohort", "retain evidence gaps and statistical limitations" and "do not infer unproven
                causes" qualify the relevant analysis goals, including when placed in a separate final
                sentence. Preserve these constraints in those goals' question text and attach their sourceIds
                to the affected goals. Source coverage does not require a separate goal for every source unit.
                Do not create a standalone EXPLORE/UNRESOLVED goal with no new deliverable just to restate
                a reporting constraint. Conversely, an explicitly requested follow-up explanation of results
                or limitations, or a recommendation, IS a separate deliverable: retain that dependent goal
                and its needsAnalysis/needsRecommendation flags. When no new data is needed, use queries=[]
                and dependsOn to reuse producer evidence; never drop an independent intent for lacking queries.
                Never invent IDs or compute character offsets. Do not return or rewrite the top-level question;
                the server already freezes it. Do not return sourceStart/sourceEnd. Return schemaVersion
                campaign-requirements/v2. Missing information still retains its goal and sourceIds.
                question and sourceUnits describe ONLY the current user message. priorContext, when present,
                is reference-only context for resolving explicit follow-ups, prior periods and group names.
                Do not turn prior objectives into current goals unless the current question requests them.
                Never assign sourceIds to priorContext. Repeated text in distinct current sourceUnits still
                has distinct sourceIds: cover every supplied current ID, including repeated scope context.
                Keep meaningful individual goals, not one goal for every metric. Use only gids explicitly
                supplied by the user/selected context; never invent a group identifier. If a group is ambiguous,
                add the missing information to clarification and retain the goal with queries=[].
                Dates are inclusive Asia/Shanghai calendar dates relative to frozenToday. Never silently
                replace a requested interval. For ordinary recent analytics without dates use the last seven
                calendar days including frozenToday and mention this assumption in goal.question.
                STATISTICS uses one or more METRICS, LINK_METRICS or DIMENSION_BREAKDOWN queries.
                METRICS and LINK_METRICS require dimensions=[] and filters=[]. LINK_METRICS returns each
                link's PV, UV and UIP together in ONE query for the whole query period. Never repeat an
                identical query for individual metrics; metric selects an operation such as ranking or
                decline selection, not which metrics the query retrieves. It does not accept a day dimension. To compare
                individual links between days or periods, use separate dated LINK_METRICS queries for
                each period. Do not replace that comparison with a single combined-period total.
                Keep an independently requested comparison/interpretation as a goal. When its exact data
                queries already belong to preceding goals and it needs no new data, set queries=[] and
                dependsOn to those producer goals; reuse their evidence instead of duplicating queries.
                Different groups, periods, query kinds, dimensions or filters are different data requests.
                Explicit link ranking/sorting uses STATISTICS with LINK_METRICS and ranking={order:DESC,
                topN:null}; use ASC only when requested, and topN=N only for an explicit Top N request.
                Omit ranking or set it null for ordinary facts/analysis. The metric field selects PV/UV/UIP.
                Each query is ranked independently; two daily rankings require two daily queries. The
                server ranks the complete returned population, never a preview. Equal values share
                competition rank (1,1,3), with numeric linkId ascending as stable display order; Top N
                includes all ties at its boundary and may contain more than N rows. Do not claim a
                different tie policy, rank changes, or a selected-cohort ranking from this contract.
                Only DIMENSION_BREAKDOWN accepts 1 to 3 distinct dimensions and dimension filters.
                EXPLORE requests bounded local investigation with the same exact authorized queries.
                Use DECLINE_DIMENSIONS whenever the complete request is to select ALL declining links in
                one group between TWO non-overlapping periods and then examine province+device joint
                changes of that selected cohort. Keep this complete selection-and-drilldown chain in ONE
                goal even across consecutive clauses or source units; goals may share sourceIds. Preserve
                separately requested comparisons and independent rankings as their own goals. Do not split
                this covered chain into STATISTICS selection and EXPLORE whole-group dimension queries.
                It has two LINK_METRICS queries in baseline,target order, metric PV/UV/UIP, no URL/filter.
                Each query also has dimensions=[]; the baseline endDate must be before the target startDate.
                DECLINE_DIMENSIONS is an implemented Skill: the server selects the actual declining links,
                freezes their IDs, and restricts BOTH periods' province+device queries to that same selected
                cohort. The user supplies the group, periods and metric, not the resulting link IDs. Do not
                ask for the selected IDs or claim this method cannot restrict drilldown to actual selected
                links. Whether any links decline is an execution result; an empty selection is valid.
                A request to mark unknown/missing region data means preserve any such returned buckets and
                disclose coverage/quality limitations. It does not require unknown-region rows to exist.
                Do not invent unknown rows, infer zero from missing/incomplete evidence, or ask the user to
                guarantee their existence before querying. Determine presence and completeness from results.
                This method is only for that exact supported scenario; do not force other complex goals
                into it. dependsOn (zero-based goal indices) orders work and references evidence; it does
                not turn a CURRENT_GROUP DIMENSION_BREAKDOWN into a query of the selected links. Never
                substitute whole-group dimensions for a requested selected cohort. Other investigations
                may use EXPLORE with exact supported queries; retain unsupported selected-cohort operations
                and other capability gaps as UNRESOLVED goals rather than silently widening their scope.
                UNRESOLVED retains requests outside these data capabilities; do not invent revenue/conversions.
                clarification is for missing or ambiguous user inputs needed to choose a supported request,
                such as an unidentified group. Runtime results, selected IDs, unknown-region presence and
                observational limitations are not missing user inputs; keep those as execution/report limits.
                Do not add clarification entries for this chain when its group and periods are explicit;
                independent goals may still require genuinely missing user inputs. If none do, return [].
                causal=true if the user asks to establish a cause, attribution or impact. Observational data
                cannot alone establish causality. A request NOT to infer unproven causes sets causal=false;
                it is a reporting constraint, not an unmet causal goal or a reason to request clarification.
                Dimensions/filters use the server's supplied exact vocabulary.
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
                {"type":"object","additionalProperties":false,"required":["schemaVersion","goals","clarification"],
                "properties":{"schemaVersion":{"const":"campaign-requirements/v2"},
                "clarification":{"type":"array","description":"Only missing or ambiguous user inputs. Do not request future selected link IDs, proof that links decline or unknown-region rows exist, or permission to omit observational limitations. A fully specified supported DECLINE_DIMENSIONS chain needs no such clarification.","items":{"type":"string"}},"goals":{"type":"array","minItems":1,
                "items":{"type":"object","additionalProperties":false,"required":["question","sourceIds","method","metric","queries","causal","dependsOn","needsAnalysis","needsRecommendation"],
                "allOf":[{"if":{"properties":{"method":{"const":"DECLINE_DIMENSIONS"}}},"then":{"properties":{"queries":{"minItems":2,"maxItems":2,"items":{"properties":{"queryKind":{"const":"LINK_METRICS"},"fullShortUrl":{"type":"null"},"dimensions":{"maxItems":0},"filters":{"maxItems":0}}}}}}}],
                "properties":{"question":{"type":"string","description":"The requested deliverable plus its applicable scope and reporting constraints. Attach constraints to affected goals rather than inventing a standalone exploration. Preserve explicitly requested independent explanations or recommendations as dependent goals, even when no new queries are needed."},"sourceIds":{"type":"array","minItems":1,"uniqueItems":true,"description":"Include source units containing applicable constraints; share those IDs across affected goals instead of creating a constraint-only goal.","items":{"type":"string"}},
                "method":{"enum":["STATISTICS","DECLINE_DIMENSIONS","EXPLORE","UNRESOLVED"],"description":"Use DECLINE_DIMENSIONS for the complete all-declining-links then province+device joint drilldown scenario across two periods in one group, even across clauses. This implemented Skill freezes actual selected link IDs and restricts both periods to that cohort; empty selection is valid, and unknown-region presence is determined from results, not user clarification. Preserve separate comparisons and rankings. Other supported investigations may use EXPLORE; unsupported operations remain UNRESOLVED. Never substitute a whole-group breakdown for a selected cohort."},"metric":{"enum":["PV","UV","UIP"]},
                "causal":{"type":"boolean"},"dependsOn":{"type":"array","uniqueItems":true,"description":"Zero-based producer goal indices. Evidence reuse does not change a query's group scope into a selected cohort.","items":{"type":"integer","minimum":0}},
                "needsAnalysis":{"type":"boolean"},"needsRecommendation":{"type":"boolean"},
                "ranking":{"type":["object","null"],"additionalProperties":false,"required":["order","topN"],"properties":{"order":{"enum":["ASC","DESC"]},"topN":{"type":["integer","null"],"minimum":1,"maximum":500}}},
                "queries":{"type":"array","uniqueItems":true,"description":"Distinct data requests only. One LINK_METRICS query returns PV, UV and UIP together; never duplicate it per metric. A dependent comparison/interpretation needing no new data uses queries=[] and dependsOn for evidence reuse. DECLINE_DIMENSIONS retains its two baseline,target LINK_METRICS queries.","items":{"type":"object","additionalProperties":false,"required":["gid","fullShortUrl","period","queryKind","dimensions","filters"],
                "allOf":[{"if":{"properties":{"queryKind":{"enum":["METRICS","LINK_METRICS"]}}},"then":{"properties":{"dimensions":{"maxItems":0},"filters":{"maxItems":0}}}},{"if":{"properties":{"queryKind":{"const":"DIMENSION_BREAKDOWN"}}},"then":{"properties":{"dimensions":{"minItems":1,"maxItems":3,"uniqueItems":true},"filters":{"maxItems":8}}}}],
                "properties":{"gid":{"type":"string"},"fullShortUrl":{"type":["string","null"]},"period":{"type":"object","required":["startDate","endDate"],"additionalProperties":false,"properties":{"startDate":{"type":"string"},"endDate":{"type":"string"}}},
                "queryKind":{"enum":["METRICS","LINK_METRICS","DIMENSION_BREAKDOWN"]},"dimensions":{"type":"array","items":{"enum":["day","hour","weekday","country","province","device","os","browser","isp","refererDomain"]}},"filters":{"type":"array","items":{"type":"object"}}}}}}}}}}
                """;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("CAMPAIGN_REQUIREMENTS_INVALID"); }
}
