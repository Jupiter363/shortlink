package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class FrozenStatisticsJobQueryTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T08:00:00Z"), ZoneOffset.UTC);
    private static final PlanSpec.ExecutorRef EXECUTOR = executor("1");
    private static final String STEP = "analysis";

    @Test
    void frozenRunRoundTripPreservesDeterministicRequestsAndDeeplyImmutableDescriptorsForAllFourKinds() {
        Map<String, Object> dimensions = descriptor("DIMENSION_BREAKDOWN");
        List<String> mutableDimensions = new ArrayList<>(List.of("province", "device"));
        List<String> mutableValues = new ArrayList<>(List.of("example.com", "news.example.com"));
        Map<String, Object> mutableFilter = new LinkedHashMap<>(Map.of("dimension", "refererDomain",
                "operator", "IN", "values", mutableValues));
        List<Map<String, Object>> mutableFilters = new ArrayList<>(List.of(mutableFilter,
                new LinkedHashMap<>(Map.of("dimension", "province", "operator", "IS_UNKNOWN"))));
        dimensions.put("dimensions", mutableDimensions);
        dimensions.put("filters", mutableFilters);
        Map<String, Object> access = changed(descriptor("ACCESS_RECORDS"), "fullShortUrl", "https://short.example/one");
        FrozenCampaignRun frozen = frozen(List.of(new Query("metrics", EXECUTOR, descriptor("METRICS")),
                new Query("access", EXECUTOR, access), new Query("links", EXECUTOR, descriptor("LINK_METRICS")),
                new Query("dimensions", EXECUTOR, dimensions)));
        RunDefinition original = frozen.definition(OWNER, "session-1");

        mutableDimensions.add("unsupported-after-freeze");
        mutableValues.add("changed.example.com");
        mutableFilter.put("operator", "GT");
        mutableFilters.clear();
        dimensions.put("gid", "mutated-group");
        assertEquals(original, frozen.definition(OWNER, "session-1"), "Caller mutations cannot alter frozen input values");

        FrozenCampaignRun restored = FrozenCampaignRun.read(original);
        RunDefinition rebuilt = restored.definition(OWNER, "session-1");
        assertEquals(original, rebuilt);
        Map<String, FrozenStatisticsJobQuery.Bound> before = FrozenStatisticsJobQuery.resolve(original, EXECUTOR);
        Map<String, FrozenStatisticsJobQuery.Bound> after = FrozenStatisticsJobQuery.resolve(rebuilt, EXECUTOR);
        assertEquals(Set.of("metrics", "access", "links", "dimensions"), before.keySet());
        assertEquals(before, after);
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        for (var entry : after.entrySet()) {
            var bound = entry.getValue();
            Set<String> expectedFields = switch (entry.getKey()) {
                case "access" -> Set.of("gid", "queryKind", "startDate", "endDate", "fullShortUrl", "requestId");
                case "dimensions" -> Set.of("gid", "queryKind", "startDate", "endDate", "dimensions", "filters", "requestId");
                default -> Set.of("gid", "queryKind", "startDate", "endDate", "requestId");
            };
            assertEquals(expectedFields, bound.request().keySet(), entry.getKey());
            assertEquals("POST", bound.child().wire().method());
            assertEquals(FrozenStatisticsJobQuery.SUBMIT_PATH, bound.child().wire().path());
            assertEquals(FrozenCampaignRun.encode(bound.request()), bound.child().wire().bodyJson());
            assertEquals(bound.child().requestId(), bound.request().get("requestId"));
            assertTrue(bound.child().requestId().matches("[A-Za-z0-9_-]{1,96}"));
            assertThrows(UnsupportedOperationException.class, () -> bound.request().put("gid", "other"));
            assertThrows(UnsupportedOperationException.class, () -> bound.descriptor().put("gid", "other"));
        }
        var dimension = after.get("dimensions");
        assertEquals(List.of("province", "device"), dimension.request().get("dimensions"));
        assertEquals(List.of("example.com", "news.example.com"), list(map(list(dimension.request().get("filters")).get(0)).get("values")));
        assertThrows(UnsupportedOperationException.class, () -> list(dimension.descriptor().get("dimensions")).add("browser"));
        assertThrows(UnsupportedOperationException.class, () -> list(dimension.request().get("filters")).clear());
        assertThrows(UnsupportedOperationException.class, () -> map(list(dimension.descriptor().get("filters")).get(0)).put("operator", "GT"));
        assertThrows(UnsupportedOperationException.class, () -> list(map(list(dimension.request().get("filters")).get(0)).get("values")).clear());
    }

    @Test
    void changedFrozenQueriesCollideWithTheOriginalDurableSlotWhileSeparatePeriodStepsRemainDistinct() {
        Map<String, Object> originalQuery = descriptor("METRICS");
        RunDefinition definition = one(EXECUTOR, originalQuery).definition(OWNER, "session-1");
        var original = FrozenStatisticsJobQuery.resolve(definition, EXECUTOR).get(STEP);
        JdbcTemplate jdbc = jdbc();
        CampaignRunStore runs = new JdbcCampaignRunStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), CLOCK);
        RunToken run = runs.createRun(definition);
        runs.prepareAction(run, new ActionSpec(original.child().actionId(), STEP, "TOOL", EXECUTOR.name(), EXECUTOR.version(), "{}"));
        ChildRecord saved = runs.prepareChild(run, original.child());

        List<Query> changes = List.of(
                new Query(STEP, EXECUTOR, changed(originalQuery, "startDate", "2026-09-02")),
                new Query(STEP, EXECUTOR, changed(originalQuery, "gid", "group-beta")),
                new Query(STEP, EXECUTOR, changed(originalQuery, "scopeRef", "scope-beta")),
                new Query(STEP, EXECUTOR, changed(originalQuery, "periodsRef", "period-beta")),
                new Query(STEP, executor("2"), originalQuery));
        for (Query change : changes) {
            RunDefinition altered = frozen(List.of(change)).definition(OWNER, "session-1");
            var resolved = FrozenStatisticsJobQuery.resolve(altered, change.executor()).get(STEP);
            assertEquals(original.child().childId(), resolved.child().childId());
            assertEquals(original.child().actionId(), resolved.child().actionId());
            assertEquals(original.target().artifactId(), resolved.target().artifactId());
            assertNotEquals(original.child().requestId(), resolved.child().requestId());
            assertNotEquals(original.child().wire().bodyJson(), resolved.child().wire().bodyJson());
            IllegalStateException conflict = assertThrows(IllegalStateException.class, () -> runs.prepareChild(run, resolved.child()));
            assertEquals("CHILD_REQUEST_CHANGED", conflict.getMessage());
            assertEquals(saved, runs.child(run, saved.spec().childId()).orElseThrow());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger", Integer.class));

        var twoPeriods = frozen(List.of(new Query("current-period", EXECUTOR, originalQuery),
                new Query("baseline-period", EXECUTOR, changed(changed(originalQuery, "startDate", "2026-08-25"), "endDate", "2026-08-31"))));
        var separated = FrozenStatisticsJobQuery.resolve(twoPeriods.definition(OWNER, "session-1"), EXECUTOR);
        var current = separated.get("current-period");
        var baseline = separated.get("baseline-period");
        assertNotEquals(current.child().childId(), baseline.child().childId());
        assertNotEquals(current.child().actionId(), baseline.child().actionId());
        assertNotEquals(current.target().artifactId(), baseline.target().artifactId());
        assertNotEquals(current.child().requestId(), baseline.child().requestId());
    }

    @Test
    void invalidFrozenDescriptorsBindingsAndParametersFailBeforeAnyResolvedQueryCanBeDispatched() {
        LocalDate first = LocalDate.of(2026, 1, 1);
        Map<String, Object> exact180 = changed(changed(descriptor("METRICS"), "startDate", first.toString()),
                "endDate", first.plusDays(179).toString());
        assertEquals(first.plusDays(179).toString(), FrozenStatisticsJobQuery.resolve(
                one(EXECUTOR, exact180).definition(OWNER, "session-1"), EXECUTOR).get(STEP).request().get("endDate"));

        Map<String, FrozenCampaignRun> invalid = new LinkedHashMap<>();
        invalid.put("unsupported descriptor schema", withInvalidQuery(changed(descriptor("METRICS"), "schemaVersion", "statistics-job-query/v2")));
        invalid.put("frozen member set is not a current query", withInvalidQuery(changed(descriptor("METRICS"), "scopeKind", "FROZEN_SET")));
        invalid.put("multiple objects", withInvalidQuery(changed(descriptor("METRICS"), "gid", List.of("group-alpha", "group-beta"))));
        invalid.put("unknown descriptor field", withInvalidQuery(changed(descriptor("METRICS"), "sql", "select 1")));
        invalid.put("impossible date", withInvalidQuery(changed(descriptor("METRICS"), "startDate", "2026-02-30")));
        invalid.put("reversed period", withInvalidQuery(changed(descriptor("METRICS"), "endDate", "2026-08-31")));
        invalid.put("more than 180 days", withInvalidQuery(changed(exact180, "endDate", first.plusDays(180).toString())));
        invalid.put("null dimensions", withInvalidQuery(changed(descriptor("DIMENSION_BREAKDOWN"), "dimensions", null)));
        invalid.put("null filters", withInvalidQuery(changed(descriptor("DIMENSION_BREAKDOWN"), "filters", null)));
        invalid.put("open filter object", withInvalidQuery(changed(descriptor("DIMENSION_BREAKDOWN"), "filters",
                List.of(Map.of("dimension", "device", "operator", "IN", "values", List.of("PC"), "expression", "custom")))));
        invalid.put("unsupported filter operator", withInvalidQuery(changed(descriptor("DIMENSION_BREAKDOWN"), "filters",
                List.of(Map.of("dimension", "device", "operator", "GT", "values", List.of("PC"))))));
        invalid.put("invalid temporal filter value", withInvalidQuery(changed(descriptor("DIMENSION_BREAKDOWN"), "filters",
                List.of(Map.of("dimension", "hour", "operator", "IN", "values", List.of("24"))))));
        invalid.put("scope reference binding mismatch", changeInputs(withInvalidQuery(descriptor("METRICS")), "bad-scope", "scope-other"));
        invalid.put("period reference binding mismatch", changeInputs(withInvalidQuery(descriptor("METRICS")), "bad-periods", "period-other"));
        invalid.put("parameters outside the descriptor", changeLastStep(withInvalidQuery(descriptor("METRICS")), step ->
                new PlanSpec.Step(step.stepId(), step.goalIds(), step.executionMode(), step.executor(), step.explorationPolicy(),
                        step.dependsOn(), step.inputBindings(), Map.of("gid", "other"), step.outputContractRef())));
        invalid.put("STEP_OUTPUT scope binding", changeLastStep(withInvalidQuery(descriptor("METRICS")), step -> {
            Map<String, PlanBinding> bindings = new LinkedHashMap<>(step.inputBindings());
            bindings.put("scope", PlanBinding.output("valid", "scope"));
            return new PlanSpec.Step(step.stepId(), step.goalIds(), step.executionMode(), step.executor(), step.explorationPolicy(),
                    List.of("valid"), bindings, step.parameters(), step.outputContractRef());
        }));
        FrozenCampaignRun wrongType = withInvalidQuery(descriptor("METRICS"));
        Map<String, Port> contracts = new LinkedHashMap<>(wrongType.inputs().inputContracts());
        contracts.put("bad-scope", new Port(FrozenStatisticsJobQuery.PERIODS_TYPE, true));
        invalid.put("wrong scope port type", FrozenCampaignRun.freeze(wrongType.plan(),
                new FrozenInputSet("inputs-1", "run-1", contracts, wrongType.inputs().inputValues()), wrongType.assessment()));

        AtomicInteger gatewayDispatches = new AtomicInteger();
        invalid.forEach((scenario, frozen) -> {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                // A caller can dispatch only after every matching frozen step has resolved.
                var resolved = FrozenStatisticsJobQuery.resolve(frozen.definition(OWNER, "session-1"), EXECUTOR);
                resolved.values().forEach(query -> gatewayDispatches.incrementAndGet());
            }, scenario);
            assertEquals("FROZEN_STATISTICS_QUERY_INVALID", failure.getMessage(), scenario);
            assertEquals(0, gatewayDispatches.get(), scenario);
        });
    }

    private static FrozenCampaignRun one(PlanSpec.ExecutorRef executor, Map<String, Object> descriptor) {
        return frozen(List.of(new Query(STEP, executor, descriptor)));
    }

    private static FrozenCampaignRun withInvalidQuery(Map<String, Object> descriptor) {
        return frozen(List.of(new Query("valid", EXECUTOR, descriptor("METRICS")), new Query("bad", EXECUTOR, descriptor)));
    }

    private static FrozenCampaignRun frozen(List<Query> queries) {
        List<PlanSpec.Step> steps = new ArrayList<>();
        Map<String, Port> contracts = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Query query : queries) {
            String scope = query.stepId() + "-scope", periods = query.stepId() + "-periods", descriptor = query.stepId() + "-query";
            contracts.put(scope, new Port(FrozenStatisticsJobQuery.SCOPE_TYPE, true));
            contracts.put(periods, new Port(FrozenStatisticsJobQuery.PERIODS_TYPE, true));
            contracts.put(descriptor, new Port(FrozenStatisticsJobQuery.QUERY_TYPE, true));
            values.put(scope, query.descriptor().get("scopeRef"));
            values.put(periods, query.descriptor().get("periodsRef"));
            values.put(descriptor, query.descriptor());
            steps.add(new PlanSpec.Step(query.stepId(), List.of("goal"), PlanSpec.ExecutionMode.FIXED, query.executor(), null,
                    List.of(), Map.of("scope", PlanBinding.input(scope), "periods", PlanBinding.input(periods),
                    "query", PlanBinding.input(descriptor)), Map.of(), "statistics-job-pages/v1"));
        }
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Inspect frozen statistics", true, "Return authorized query evidence")), steps);
        return FrozenCampaignRun.freeze(plan, new FrozenInputSet("inputs-1", "run-1", contracts, values),
                new PlanningAssessment("plan-1", 1, "catalog/v1", List.of(), List.of(), List.of()));
    }

    private static FrozenCampaignRun changeInputs(FrozenCampaignRun frozen, String input, Object value) {
        Map<String, Object> values = new LinkedHashMap<>(frozen.inputs().inputValues());
        values.put(input, value);
        return FrozenCampaignRun.freeze(frozen.plan(), new FrozenInputSet("inputs-1", "run-1", frozen.inputs().inputContracts(), values),
                frozen.assessment());
    }

    private static FrozenCampaignRun changeLastStep(FrozenCampaignRun frozen, UnaryOperator<PlanSpec.Step> change) {
        List<PlanSpec.Step> steps = new ArrayList<>(frozen.plan().steps());
        steps.set(steps.size() - 1, change.apply(steps.get(steps.size() - 1)));
        PlanSpec plan = frozen.plan();
        return FrozenCampaignRun.freeze(new PlanSpec(plan.schemaVersion(), plan.planId(), plan.revision(), plan.runId(),
                plan.inputSetRef(), plan.goals(), steps), frozen.inputs(), frozen.assessment());
    }

    private static Map<String, Object> descriptor(String kind) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", FrozenStatisticsJobQuery.SCHEMA);
        values.put("scopeRef", "scope-main");
        values.put("periodsRef", "period-main");
        values.put("scopeKind", "CURRENT_GROUP");
        values.put("gid", "group-alpha");
        values.put("startDate", "2026-09-01");
        values.put("endDate", "2026-09-07");
        values.put("businessTimezone", "Asia/Shanghai");
        values.put("queryKind", kind);
        if (kind.equals("DIMENSION_BREAKDOWN")) {
            values.put("dimensions", List.of("province", "device"));
            values.put("filters", List.of(Map.of("dimension", "country", "operator", "IN", "values", List.of("China"))));
        }
        return values;
    }

    private static Map<String, Object> changed(Map<String, Object> original, String key, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(original);
        changed.put(key, value);
        return changed;
    }

    private static PlanSpec.ExecutorRef executor(String version) {
        return new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "frozen-statistics-job", version);
    }

    private static JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:frozen_statistics_query_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private record Query(String stepId, PlanSpec.ExecutorRef executor, Map<String, Object> descriptor) {}
}
