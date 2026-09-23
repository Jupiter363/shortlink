package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.tool.shortlink.CampaignStatisticsQueryPlan;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creates a real frozen FIXED plan for the deterministic data collection part of a public
 * comparison or ranking request. The caller must obtain owner, session and request key from a
 * trusted transport; current ownership is checked again by the registered runtime authorizers.
 * This factory performs no I/O and does not claim that a comparison or ranking has been computed.
 */
public final class CampaignStatisticsPlanFactory {
    public static final TypeRef OPERATION_TYPE =
            new TypeRef("CampaignStatisticsOperation", 1, Cardinality.ONE);
    public static final String OPERATION_SCHEMA = "campaign-statistics-operation/v1";
    private static final String GOAL_ID = "collect-statistics";
    private static final String REQUIREMENT_ID = "statistics-evidence";
    private static final String BUSINESS_TIMEZONE = "Asia/Shanghai";
    private static final Set<String> RANKING_METRICS = Set.of("pv", "uv", "uip");

    public record StepQuery(String stepId, String key, String scopeRef, String periodsRef,
                            Map<String, Object> request) {
        public StepQuery {
            request = Map.copyOf(request);
        }
    }

    public record Prepared(RunDefinition definition, FrozenCampaignRun frozen,
                           String queryPlanId, List<StepQuery> queries) {
        public Prepared {
            queries = List.copyOf(queries);
            if (!definition.equals(frozen.definition(definition.caller(), definition.sessionId())))
                throw new IllegalArgumentException("STATISTICS_PLAN_DEFINITION_CHANGED");
        }
    }

    private record Query(String key, String gid, String fullShortUrl, String startDate, String endDate) {}

    private final CapabilityCatalog catalog;
    private final String deliveryCriterionRef;
    private final String deliveryCriterionVersion;

    /** The delivery criterion must be a registered proof over StatisticsJobPages. */
    public CampaignStatisticsPlanFactory(CapabilityCatalog catalog, String deliveryCriterionRef,
                                         String deliveryCriterionVersion) {
        this.catalog = Objects.requireNonNull(catalog, "STATISTICS_PLAN_CATALOG_REQUIRED");
        if (!StatisticsJobFixedExecutor.capability().equals(
                catalog.capability(StatisticsJobFixedExecutor.REF).orElse(null)))
            throw new IllegalArgumentException("STATISTICS_PLAN_EXECUTOR_UNREGISTERED");
        var criterion = catalog.criterion(deliveryCriterionRef, deliveryCriterionVersion)
                .orElseThrow(() -> new IllegalArgumentException("STATISTICS_PLAN_DELIVERY_UNREGISTERED"));
        if (criterion.kind() != PlanningAssessment.RequirementKind.DELIVERY
                || !criterion.requiredEvidenceTypes().equals(Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE))
                || !criterion.parameters().required().isEmpty()
                || !criterion.parameters().properties().isEmpty())
            throw new IllegalArgumentException("STATISTICS_PLAN_DELIVERY_INCOMPATIBLE");
        this.deliveryCriterionRef = deliveryCriterionRef;
        this.deliveryCriterionVersion = deliveryCriterionVersion;
    }

    public Prepared comparison(Caller owner, String sessionId, String requestKey,
                               List<Map<String, Object>> scopes, List<Map<String, Object>> periods) {
        CampaignStatisticsQueryPlan.Plan queryPlan = CampaignStatisticsQueryPlan.create(scopes, periods);
        List<Query> queries = queryPlan.queries().stream().map(query -> new Query(query.key(),
                query.scope().gid(), query.scope().fullShortUrl(),
                query.period().startDate().toString(), query.period().endDate().toString())).toList();
        Map<String, Object> operation = Map.of("schemaVersion", OPERATION_SCHEMA,
                "kind", "COMPARISON", "queryPlanId", queryPlan.planId());
        return build(owner, sessionId, requestKey, queries, "METRICS", operation);
    }

    public Prepared ranking(Caller owner, String sessionId, String requestKey,
                            String gid, String startDate, String endDate, String metric, Integer limit) {
        String group = groupId(gid, "STATISTICS_RANKING_GID_INVALID");
        LocalDate start = date(startDate), end = date(endDate);
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (start.isBefore(LocalDate.of(1970, 1, 1)) || days < 1 || days > 180)
            throw new IllegalArgumentException("STATISTICS_RANKING_PERIOD_INVALID");
        if (!RANKING_METRICS.contains(metric) || limit == null || limit < 1 || limit > 50)
            throw new IllegalArgumentException("STATISTICS_RANKING_OPTIONS_INVALID");
        String queryPlanId = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                "ranking-query-plan/v1", group, start.toString(), end.toString(), metric, limit)));
        Map<String, Object> operation = Map.of("schemaVersion", OPERATION_SCHEMA,
                "kind", "RANKING", "queryPlanId", queryPlanId, "metric", metric, "limit", limit);
        Query query = new Query(group + "||" + start + "|" + end, group, "",
                start.toString(), end.toString());
        return build(owner, sessionId, requestKey, List.of(query), "LINK_METRICS", operation);
    }

    private Prepared build(Caller owner, String sessionId, String requestKey, List<Query> queries,
                           String queryKind, Map<String, Object> operation) {
        var identity = JdbcCampaignRunIntakeStore.identity(owner, sessionId, requestKey);
        Map<String, Port> contracts = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        contracts.put("operation", new Port(OPERATION_TYPE, true));
        values.put("operation", operation);
        List<PlanSpec.Step> steps = new ArrayList<>();
        List<PlanningAssessment.EvidenceOutput> outputs = new ArrayList<>();
        List<StepQuery> boundQueries = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            Query query = queries.get(index);
            String stepId = "collect-" + (index + 1);
            String scopeInput = "scope-" + (index + 1);
            String periodsInput = "periods-" + (index + 1);
            String queryInput = "query-" + (index + 1);
            String scopeRef = "current-group.v1:" + groupId(query.gid(), "STATISTICS_SCOPE_GID_INVALID");
            String periodsRef = periodRef(query.startDate(), query.endDate());
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("schemaVersion", FrozenStatisticsJobQuery.SCHEMA);
            descriptor.put("scopeRef", scopeRef);
            descriptor.put("periodsRef", periodsRef);
            descriptor.put("scopeKind", "CURRENT_GROUP");
            descriptor.put("gid", query.gid());
            if (!query.fullShortUrl().isEmpty()) descriptor.put("fullShortUrl", query.fullShortUrl());
            descriptor.put("startDate", query.startDate());
            descriptor.put("endDate", query.endDate());
            descriptor.put("businessTimezone", BUSINESS_TIMEZONE);
            descriptor.put("queryKind", queryKind);
            Map<String, Object> request = FrozenStatisticsJobQuery.prepareRequest(scopeRef, periodsRef, descriptor);
            contracts.put(scopeInput, FrozenStatisticsJobQuery.INPUTS.get("scope"));
            contracts.put(periodsInput, FrozenStatisticsJobQuery.INPUTS.get("periods"));
            contracts.put(queryInput, FrozenStatisticsJobQuery.INPUTS.get("query"));
            values.put(scopeInput, scopeRef);
            values.put(periodsInput, periodsRef);
            values.put(queryInput, descriptor);
            steps.add(new PlanSpec.Step(stepId, List.of(GOAL_ID), PlanSpec.ExecutionMode.FIXED,
                    StatisticsJobFixedExecutor.REF, null, List.of(), Map.of(
                    "scope", PlanBinding.input(scopeInput),
                    "periods", PlanBinding.input(periodsInput),
                    "query", PlanBinding.input(queryInput)), Map.of(),
                    CampaignStatisticsResultStore.SCHEMA_VERSION));
            outputs.add(new PlanningAssessment.EvidenceOutput(stepId, StatisticsJobFixedExecutor.OUTPUT_NAME));
            boundQueries.add(new StepQuery(stepId, query.key(), scopeRef, periodsRef, request));
        }
        String inputSetRef = "inputs-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                "statistics-input-set/v1", identity.requestId(), operation, boundQueries)));
        FrozenInputSet inputs = new FrozenInputSet(inputSetRef, identity.runId(), contracts, values);
        String title = "METRICS".equals(queryKind) ? "Collect frozen statistics for comparison"
                : "Collect frozen link statistics for ranking";
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, identity.planId(), 1, identity.runId(),
                inputSetRef, List.of(new PlanSpec.Goal(GOAL_ID, title, true,
                "Every requested query has a complete, scope-bound StatisticsJobPages artifact")), steps);
        PlanningAssessment assessment = new PlanningAssessment(identity.planId(), 1, catalog.version(),
                List.of(new PlanningAssessment.Requirement(REQUIREMENT_ID, GOAL_ID,
                        PlanningAssessment.RequirementKind.DELIVERY, true,
                        deliveryCriterionRef, deliveryCriterionVersion, Map.of())),
                List.of(new PlanningAssessment.CoverageBinding(REQUIREMENT_ID, outputs)), List.of());
        new PlanValidator(catalog).validate(plan, inputs, assessment);
        FrozenCampaignRun frozen = FrozenCampaignRun.freeze(plan, inputs, assessment);
        return new Prepared(frozen.definition(owner, sessionId), frozen,
                operation.get("queryPlanId").toString(), boundQueries);
    }

    private static String groupId(String value, String code) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException(code);
        return value;
    }

    private static String periodRef(String startDate, String endDate) {
        LocalDate start = date(startDate, "STATISTICS_PERIOD_INVALID");
        LocalDate end = date(endDate, "STATISTICS_PERIOD_INVALID");
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (start.isBefore(LocalDate.of(1970, 1, 1)) || days < 1 || days > 180)
            throw new IllegalArgumentException("STATISTICS_PERIOD_INVALID");
        return "period.v1:" + start + ":" + end;
    }

    private static LocalDate date(String value) {
        return date(value, "STATISTICS_RANKING_PERIOD_INVALID");
    }

    private static LocalDate date(String value, String code) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException(code);
        try { return LocalDate.parse(value); }
        catch (DateTimeException invalid) {
            throw new IllegalArgumentException(code, invalid);
        }
    }
}
