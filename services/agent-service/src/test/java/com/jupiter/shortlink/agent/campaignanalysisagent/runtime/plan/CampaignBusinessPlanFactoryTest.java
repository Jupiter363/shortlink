package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CampaignBusinessPlanFactoryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "alice", 7, false);
    private static final String QUESTION = "核查两组下降短链并查看另一组省份设备统计";
    private static final String VERSION = "a".repeat(64);

    @Test
    void combinesTwoRealDependencyChainsAndIndependentMultiPeriodQueriesWithoutLosingGoals() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted(), CLOCK.instant().plusSeconds(3600));
        var frozen = frozen(fixture.factory(), request);
        var parsed = fixture.factory().validateDefinition(frozen.definition(OWNER, "session"));
        assertThat(request.goals()).extracting(PlanSpec.Goal::goalId).containsExactly("goal-1", "goal-2", "goal-3");
        assertThat(request.requirements()).hasSize(7);
        assertThat(parsed.dependencies()).hasSize(2);
        assertThat(parsed.queries()).hasSize(2);
        assertThat(FrozenScopeCollection.resolve(frozen.definition(OWNER, "session"))).hasSize(2);
        assertThat(FrozenDeclineSelection.templates(frozen.definition(OWNER, "session"))).hasSize(2);
        assertThat(FrozenDimensionChange.resolveTemplates(frozen.definition(OWNER, "session"), FrozenDimensionChange.REF_V2)).hasSize(2);
        assertThat(FrozenStatisticsJobQuery.resolve(frozen.definition(OWNER, "session"), StatisticsJobFixedExecutor.REF)).hasSize(2);
        assertThat(request.menu().capabilities()).hasSize(4);
        assertThat(request.menu().policies()).extracting(PlanningProposal.PolicyItem::policyRef).containsExactly("statistics-exploration");
        var evidence = request.requirements().stream().filter(value -> value.kind() == PlanningAssessment.RequirementKind.DATA).toList();
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).parameters().get("queryHash")).isNotEqualTo(evidence.get(1).parameters().get("queryHash"));
    }

    @Test
    void structuralPlanCannotChangeGoalMetricOrBorrowAnotherGroupsSameTypedInput() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted(), CLOCK.instant().plusSeconds(3600));
        var frozen = frozen(fixture.factory(), request);
        var changed = new ArrayList<>(frozen.plan().steps());
        int index = 1;
        var selection = changed.get(index);
        changed.set(index, new PlanSpec.Step(selection.stepId(), selection.goalIds(), selection.executionMode(),
                selection.executor(), null, selection.dependsOn(), selection.inputBindings(), Map.of("metric", "UV"), selection.outputContractRef()));
        var metric = replaceSteps(frozen, changed);
        new PlanValidator(fixture.factory().catalog()).validate(metric.plan(), metric.inputs(), metric.assessment());
        assertThatThrownBy(() -> fixture.factory().validateDefinition(metric.definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class);

        changed = new ArrayList<>(frozen.plan().steps());
        var collection = changed.get(0);
        changed.set(0, new PlanSpec.Step(collection.stepId(), collection.goalIds(), collection.executionMode(), collection.executor(),
                null, collection.dependsOn(), Map.of("definition", PlanBinding.input("goal-2-collectionDefinition")),
                collection.parameters(), collection.outputContractRef()));
        var group = replaceSteps(frozen, changed);
        assertThatThrownBy(() -> fixture.factory().validateDefinition(group.definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void analysisAndRecommendationBindActualDataWithoutPermanentPlanningGap() throws Exception {
        var fixture = fixture();
        var original = interpreted();
        var analytical = new CampaignInterpretedRequest(original.schemaVersion(), original.question(),
                original.goals().stream().map(goal -> new CampaignInterpretedRequest.Goal(goal.question(), goal.sourceStart(),
                        goal.sourceEnd(), goal.method(), goal.metric(), goal.queries(), goal.causal(), goal.dependsOn(), true, true)).toList(),
                List.of());
        var request = fixture.factory().prepare(OWNER, "session", "turn", analytical, CLOCK.instant().plusSeconds(3600));
        var plan = frozen(fixture.factory(), request);
        fixture.factory().validateDefinition(plan.definition(OWNER, "session"));
        assertThat(plan.assessment().gaps()).isEmpty();
        var calculation = request.requirements().stream()
                .filter(requirement -> requirement.kind() == PlanningAssessment.RequirementKind.CALCULATION).toList();
        assertThat(calculation).hasSize(6);
        for (var requirement : calculation) {
            assertThat(plan.assessment().coverageBindings()).anyMatch(binding -> binding.requirementId().equals(requirement.requirementId())
                    && !binding.evidenceOutputs().isEmpty());
            assertThat(fixture.factory().catalog().criterion(requirement.criterionRef(), "1").orElseThrow().requiredEvidenceTypes())
                    .isNotEmpty();
        }
    }

    @Test
    void liveGateAuthorizesExactJointDimensionQueryAndRejectsScopeDateOrDimensionDrift() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted(), CLOCK.instant().plusSeconds(3600));
        var definition = frozen(fixture.factory(), request).definition(OWNER, "session");
        var authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(PRINCIPAL)).thenReturn(PRINCIPAL);
        when(authority.resolveGroupMembersPage(eq(PRINCIPAL), anyString(), isNull(), isNull())).thenAnswer(call ->
                new GroupMembersPage(GroupMembersPage.SCHEMA, OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(),
                        call.getArgument(1), VERSION, null, List.of(1L, 2L), null));
        var gate = new CampaignBusinessQueryAuthorizer(authority, fixture.factory(), fixture.dependencies(),
                new CampaignBusinessAuthorizer(authority, fixture.factory()), definition);
        var query = fixture.factory().inspect(OWNER, request.inputs()).queries().get(0);
        var wire = new LinkedHashMap<>(query.request()); wire.put("requestId", "stat_" + "b".repeat(64));
        assertThat(gate.mayUse(PRINCIPAL, query.scopeRef(), query.periodsRef(), wire)).isTrue();
        wire.put("dimensions", List.of("province", "browser"));
        assertThat(gate.mayUse(PRINCIPAL, query.scopeRef(), query.periodsRef(), wire)).isFalse();
        wire.put("dimensions", query.request().get("dimensions")); wire.put("endDate", "2026-09-10");
        assertThat(gate.mayUse(PRINCIPAL, query.scopeRef(), query.periodsRef(), wire)).isFalse();
        when(authority.verifyCurrentPrincipal(PRINCIPAL)).thenThrow(new SecurityException("revoked"));
        assertThat(gate.mayUse(PRINCIPAL, query.scopeRef(), query.periodsRef(), wire)).isFalse();
    }

    private static FrozenCampaignRun frozen(CampaignBusinessPlanFactory factory, PlanningProposal.Request request) {
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session", "turn");
        var parsed = factory.inspect(OWNER, request.inputs());
        List<PlanSpec.Step> steps = new ArrayList<>();
        for (var branch : parsed.dependencies()) {
            String goal = branch.prefix().substring(0, branch.prefix().length() - 1);
            for (var step : FrozenCampaignRun.read(branch.definition()).plan().steps()) {
                Map<String,PlanBinding> bindings = new LinkedHashMap<>();
                step.inputBindings().forEach((name, binding) -> bindings.put(name,
                        binding.source() == PlanBinding.Source.INPUT ? PlanBinding.input(branch.prefix() + binding.input())
                                : PlanBinding.output(branch.prefix() + binding.stepId(), binding.output())));
                steps.add(new PlanSpec.Step(branch.prefix() + step.stepId(), List.of(goal), step.executionMode(), step.executor(), null,
                        step.dependsOn().stream().map(value -> branch.prefix() + value).toList(), bindings, step.parameters(), step.outputContractRef()));
            }
        }
        for (var query : parsed.queries()) {
            String stepId = query.prefix().substring(0, query.prefix().length() - 1);
            steps.add(new PlanSpec.Step(stepId, List.of("goal-3"), PlanSpec.ExecutionMode.FIXED,
                    StatisticsJobFixedExecutor.REF, null, List.of(), Map.of("scope", PlanBinding.input(query.prefix() + "scope"),
                    "periods", PlanBinding.input(query.prefix() + "periods"), "query", PlanBinding.input(query.prefix() + "query")),
                    Map.of(), StatisticsJobFixedExecutor.capability().signature().outputContractRef()));
        }
        List<PlanningAssessment.CoverageBinding> coverage = new ArrayList<>();
        for (var requirement : request.requirements()) {
            String prefix = requirement.goalId() + "-";
            List<PlanningAssessment.EvidenceOutput> outputs = switch (requirement.criterionRef()) {
                case "selected-entities-delivery" -> List.of(new PlanningAssessment.EvidenceOutput(prefix + "select-declines", "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput(prefix + "select-declines", "selectionEvidence"));
                case "dimension-change-delivery" -> List.of(new PlanningAssessment.EvidenceOutput(prefix + "dimension-change", "dimensionChanges"));
                case "statistics-query-evidence" -> List.of(new PlanningAssessment.EvidenceOutput(requirement.requirementId(), "pages"));
                case "statistics-evidence-delivery" -> List.of(new PlanningAssessment.EvidenceOutput("goal-3-query-1", "pages"),
                        new PlanningAssessment.EvidenceOutput("goal-3-query-2", "pages"));
                case "analysis-interpretation", "analysis-recommendation" -> List.of(
                        new PlanningAssessment.EvidenceOutput("goal-3-query-1", "pages"),
                        new PlanningAssessment.EvidenceOutput("goal-3-query-2", "pages"));
                case "analysis-interpretation-dimensions", "analysis-recommendation-dimensions" -> List.of(
                        new PlanningAssessment.EvidenceOutput(prefix + "dimension-change", "dimensionChanges"));
                default -> throw new IllegalArgumentException(requirement.criterionRef());
            };
            coverage.add(new PlanningAssessment.CoverageBinding(requirement.requirementId(), outputs));
        }
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, identity.planId(), 1, identity.runId(), request.inputs().inputSetRef(), request.goals(), steps);
        var assessment = new PlanningAssessment(identity.planId(), 1, factory.catalog().version(), request.requirements(), coverage, List.of());
        new PlanValidator(factory.catalog()).validate(plan, request.inputs(), assessment);
        return FrozenCampaignRun.freeze(plan, request.inputs(), assessment);
    }

    private static FrozenCampaignRun replaceSteps(FrozenCampaignRun frozen, List<PlanSpec.Step> steps) {
        var original = frozen.plan();
        return FrozenCampaignRun.freeze(new PlanSpec(original.schemaVersion(), original.planId(), original.revision(),
                original.runId(), original.inputSetRef(), original.goals(), steps), frozen.inputs(), frozen.assessment());
    }

    private static CampaignInterpretedRequest interpreted() {
        var first = query("group-a", "2026-09-01", "LINK_METRICS");
        var second = query("group-a", "2026-09-02", "LINK_METRICS");
        return new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, QUESTION, List.of(
                goal("第一组下降与维度", "DECLINE_DIMENSIONS", List.of(first, second)),
                goal("第二组下降与维度", "DECLINE_DIMENSIONS", List.of(query("group-b", "2026-09-01", "LINK_METRICS"), query("group-b", "2026-09-02", "LINK_METRICS"))),
                goal("第三组两期联合维度", "STATISTICS", List.of(query("group-c", "2026-09-01", "DIMENSION_BREAKDOWN"), query("group-c", "2026-09-02", "DIMENSION_BREAKDOWN")))), List.of());
    }
    private static CampaignInterpretedRequest.Goal goal(String question, String method, List<CampaignInterpretedRequest.Query> queries) {
        return new CampaignInterpretedRequest.Goal(question, 0, QUESTION.length(), method, "PV", queries, false, List.of(), false, false);
    }
    private static CampaignInterpretedRequest.Query query(String gid, String date, String kind) {
        return new CampaignInterpretedRequest.Query(gid, null, new CampaignInterpretedRequest.Period(date, date), kind,
                "DIMENSION_BREAKDOWN".equals(kind) ? List.of("province", "device") : List.of(), List.of());
    }
    private static Fixture fixture() throws Exception {
        var dependencies = new CampaignDependencyAnalysisPlanFactory(new ClassPathResource("campaign-skills").getFile().toPath(), CLOCK);
        return new Fixture(dependencies, new CampaignBusinessPlanFactory(dependencies, CLOCK));
    }
    private record Fixture(CampaignDependencyAnalysisPlanFactory dependencies, CampaignBusinessPlanFactory factory) {}
}
