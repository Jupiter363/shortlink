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
        // Parse and materialize the actual native proposal contract, including its permitted wrapper.
        var proposal = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, plan.plan().steps(),
                plan.assessment().coverageBindings(), List.of());
        plan = PlanningProposal.parse("```json\n" + proposal.encode() + "\n```")
                .materialize(request, plan.plan().planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
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

    @Test
    void twoDailyRankingsFreezeSeparateQueryHashesAndRegisteredCompletionRequirements() throws Exception {
        var fixture = fixture();
        String question = "分别给出两天的短链 PV 排名";
        var interpreted = new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, question,
                List.of(new CampaignInterpretedRequest.Goal(question, 0, question.length(), "STATISTICS", "PV",
                        List.of(query("group-a", "2026-09-13", "LINK_METRICS"), query("group-a", "2026-09-14", "LINK_METRICS")),
                        false, List.of(), false, false, new CampaignInterpretedRequest.Ranking("DESC", null))), List.of());
        var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted, CLOCK.instant().plusSeconds(3600));
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session", "turn");
        var parsed = fixture.factory().inspect(OWNER, request.inputs());
        var rankRequirements = request.requirements().stream().filter(value -> "statistics-ranking".equals(value.criterionRef())).toList();
        assertThat(rankRequirements).hasSize(2).allSatisfy(value -> {
            assertThat(value.kind()).isEqualTo(PlanningAssessment.RequirementKind.CALCULATION);
            assertThat(value.parameters()).containsEntry("metric", "PV").containsEntry("order", "DESC").containsEntry("topN", 0);
        });
        assertThat(rankRequirements.get(0).parameters().get("queryHash")).isNotEqualTo(rankRequirements.get(1).parameters().get("queryHash"));
        var steps = parsed.queries().stream().map(query -> new PlanSpec.Step(query.prefix().substring(0, query.prefix().length() - 1),
                List.of("goal-1"), PlanSpec.ExecutionMode.FIXED, StatisticsJobFixedExecutor.REF, null, List.of(),
                Map.of("scope", PlanBinding.input(query.prefix() + "scope"), "periods", PlanBinding.input(query.prefix() + "periods"),
                        "query", PlanBinding.input(query.prefix() + "query")), Map.of(), "statistics-job-pages/v1")).toList();
        var coverage = request.requirements().stream().map(value -> new PlanningAssessment.CoverageBinding(value.requirementId(),
                "statistics-evidence-delivery".equals(value.criterionRef())
                        ? steps.stream().map(step -> new PlanningAssessment.EvidenceOutput(step.stepId(), "pages")).toList()
                        : List.of(new PlanningAssessment.EvidenceOutput(value.requirementId().replace("-ranking-", "-query-"), "pages")))).toList();
        var frozen = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, steps, coverage, List.of())
                .materialize(request, identity.planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
        fixture.factory().validateDefinition(frozen.definition(OWNER, "session"));
    }

    @Test
    void dependentAnalysisAndRecommendationShareExactUpstreamQueriesWithoutPlanningDuplicateJobs() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", dependentRequest(List.of(0)), CLOCK.instant().plusSeconds(3600));
        var parsed = fixture.factory().inspect(OWNER, request.inputs());
        assertThat(parsed.queries()).hasSize(2);
        assertThat(request.requirements()).noneMatch(value -> value.criterionRef().endsWith("unresolved"));
        List<PlanSpec.Step> steps = new ArrayList<>();
        Map<String,PlanningAssessment.EvidenceOutput> outputsByHash = new LinkedHashMap<>();
        for (var query : parsed.queries()) {
            String stepId = query.prefix() + "shared";
            steps.add(new PlanSpec.Step(stepId, List.of("goal-1", "goal-2", "goal-3"), PlanSpec.ExecutionMode.FIXED,
                    StatisticsJobFixedExecutor.REF, null, List.of(),
                    Map.of("scope", PlanBinding.input(query.prefix() + "scope"), "periods", PlanBinding.input(query.prefix() + "periods"),
                            "query", PlanBinding.input(query.prefix() + "query")), Map.of(), "statistics-job-pages/v1"));
            outputsByHash.put(CampaignRunStore.sha256(FrozenCampaignRun.encode(query.request())),
                    new PlanningAssessment.EvidenceOutput(stepId, "pages"));
        }
        var coverage = request.requirements().stream().map(requirement -> new PlanningAssessment.CoverageBinding(
                requirement.requirementId(), "statistics-query-evidence".equals(requirement.criterionRef())
                    ? List.of(outputsByHash.get(requirement.parameters().get("queryHash"))) : List.copyOf(outputsByHash.values()))).toList();
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session", "turn");
        var plan = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, steps, coverage, List.of())
                .materialize(request, identity.planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
        var definition = plan.definition(OWNER, "session");
        fixture.factory().validateDefinition(definition);
        assertThat(plan.plan().steps()).hasSize(2); // Two original queries, not another job for either consumer.
        assertThat(FrozenStatisticsJobQuery.resolve(definition, StatisticsJobFixedExecutor.REF)).hasSize(2);
        assertThat(plan.assessment().gaps()).isEmpty();
        for (String goal : List.of("goal-1", "goal-2", "goal-3")) {
            assertThat(request.requirements().stream().filter(value -> goal.equals(value.goalId())
                    && value.kind() == PlanningAssessment.RequirementKind.DATA).map(value -> value.parameters().get("queryHash")))
                    .containsExactlyInAnyOrderElementsOf(outputsByHash.keySet());
        }
        // The consumer cannot relabel another scope's query as its prerequisite's shared evidence.
        var wrong = new ArrayList<>(steps);
        var first = wrong.get(0);
        wrong.set(0, new PlanSpec.Step(first.stepId(), List.of("goal-2", "goal-3"), first.executionMode(), first.executor(),
                null, first.dependsOn(), first.inputBindings(), first.parameters(), first.outputContractRef()));
        assertThatThrownBy(() -> fixture.factory().validateDefinition(replaceSteps(plan, wrong).definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void querylessInterpretationAndRecommendationReuseTheCompleteOriginalSkillChain() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", skillFollowUpRequest(false), CLOCK.instant().plusSeconds(3600));
        var original = frozen(fixture.factory(), request);
        var proposal = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, original.plan().steps(),
                original.assessment().coverageBindings(), List.of());
        var normalized = fixture.factory().normalizeProposal(OWNER, request, proposal);
        var plan = normalized.materialize(request, original.plan().planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
        var parsed = fixture.factory().validateDefinition(plan.definition(OWNER, "session"));
        assertThat(parsed.queries()).isEmpty();
        assertThat(parsed.dependencies()).hasSize(1);
        assertThat(plan.plan().steps()).hasSize(3).allSatisfy(step ->
                assertThat(step.goalIds()).containsExactly("goal-1", "goal-2", "goal-3"));
        assertThat(normalized.steps()).isEqualTo(proposal.steps());
        assertThat(request.requirements()).noneMatch(value -> value.criterionRef().endsWith("unresolved"));
        assertThat(plan.assessment().gaps()).isEmpty();
        for (String consumer : List.of("goal-2", "goal-3")) {
            var bindings = plan.assessment().coverageBindings().stream()
                    .filter(value -> value.requirementId().startsWith(consumer + "-")).toList();
            assertThat(bindings).hasSize(3);
            assertThat(bindings.stream().flatMap(value -> value.evidenceOutputs().stream()).distinct())
                    .containsExactlyInAnyOrder(new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectedEntities"),
                            new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectionEvidence"),
                            new PlanningAssessment.EvidenceOutput("goal-1-dimension-change", "dimensionChanges"));
        }
    }

    @Test
    void inheritedSkillEvidenceRejectsOtherBranchesPartialPairsLostProducersAndUnsupportedGoals() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", skillFollowUpRequest(true), CLOCK.instant().plusSeconds(3600));
        var plan = frozen(fixture.factory(), request);
        fixture.factory().validateDefinition(plan.definition(OWNER, "session"));
        String selectedRequirement = "goal-3-source-1-selected";
        for (var wrong : List.of(
                List.of(new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectedEntities")),
                List.of(new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput("goal-2-select-declines", "selectionEvidence")),
                List.of(new PlanningAssessment.EvidenceOutput("goal-2-select-declines", "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput("goal-2-select-declines", "selectionEvidence")))) {
            var coverage = plan.assessment().coverageBindings().stream().map(value -> selectedRequirement.equals(value.requirementId())
                    ? new PlanningAssessment.CoverageBinding(selectedRequirement, wrong) : value).toList();
            var invalid = FrozenCampaignRun.freeze(plan.plan(), plan.inputs(), new PlanningAssessment(plan.plan().planId(), 1,
                    fixture.factory().catalog().version(), request.requirements(), coverage, List.of()));
            assertThatThrownBy(() -> fixture.factory().validateDefinition(invalid.definition(OWNER, "session")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var missingProducer = plan.plan().steps().stream().map(step -> "goal-1-select-declines".equals(step.stepId())
                ? new PlanSpec.Step(step.stepId(), List.of("goal-3", "goal-4"), step.executionMode(), step.executor(), null,
                    step.dependsOn(), step.inputBindings(), step.parameters(), step.outputContractRef()) : step).toList();
        assertThatThrownBy(() -> fixture.factory().validateDefinition(replaceSteps(plan, missingProducer).definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class);

        var base = skillFollowUpRequest(false);
        var goals = new ArrayList<>(base.goals());
        var consumer = goals.get(1);
        goals.set(1, new CampaignInterpretedRequest.Goal(consumer.question(), consumer.sourceStart(), consumer.sourceEnd(),
                "UNRESOLVED", consumer.metric(), List.of(), false, consumer.dependsOn(), true, false));
        var unsupported = fixture.factory().prepare(OWNER, "session", "unsupported", new CampaignInterpretedRequest(base.schemaVersion(),
                base.question(), goals, List.of()), CLOCK.instant().plusSeconds(3600));
        assertThat(unsupported.requirements().stream().filter(value -> "goal-2".equals(value.goalId())))
                .extracting(PlanningAssessment.Requirement::criterionRef)
                .containsExactly("unresolved-analysis-delivery", "analysis-interpretation-unresolved");
        goals.set(1, new CampaignInterpretedRequest.Goal(consumer.question(), consumer.sourceStart(), consumer.sourceEnd(),
                "STATISTICS", consumer.metric(), List.of(), false, List.of(2), true, false));
        assertThatThrownBy(() -> fixture.factory().prepare(OWNER, "session", "cycle", new CampaignInterpretedRequest(base.schemaVersion(),
                base.question(), goals, List.of()), CLOCK.instant().plusSeconds(3600))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void querylessGoalWithoutDeclaredEvidenceDependencyStaysUnresolvedAndCyclesAreRejected() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", dependentRequest(List.of()), CLOCK.instant().plusSeconds(3600));
        assertThat(request.requirements().stream().filter(value -> "goal-2".equals(value.goalId())))
                .extracting(PlanningAssessment.Requirement::criterionRef)
                .containsExactly("unresolved-analysis-delivery", "analysis-interpretation-unresolved");
        var parsed = fixture.factory().inspect(OWNER, request.inputs());
        var query = parsed.queries().get(0);
        var shared = new PlanSpec.Step("illegal-shared", List.of("goal-1", "goal-2"), PlanSpec.ExecutionMode.FIXED,
                StatisticsJobFixedExecutor.REF, null, List.of(), Map.of("scope", PlanBinding.input(query.prefix() + "scope"),
                    "periods", PlanBinding.input(query.prefix() + "periods"), "query", PlanBinding.input(query.prefix() + "query")),
                Map.of(), "statistics-job-pages/v1");
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session", "turn");
        var gaps = request.requirements().stream().map(value -> new PlanningAssessment.Gap(value.requirementId(),
                PlanningAssessment.GapReason.PLANNING_UNRESOLVED, "Pending evidence")).toList();
        var plan = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(shared), List.of(), gaps)
                .materialize(request, identity.planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
        assertThatThrownBy(() -> fixture.factory().validateDefinition(plan.definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class);
        // goal-2 -> goal-3 -> goal-2 must never become an inherited data permission.
        assertThatThrownBy(() -> fixture.factory().prepare(OWNER, "session", "cycle", dependentRequest(List.of(2)),
                CLOCK.instant().plusSeconds(3600))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identicalFrozenQueriesShareDependencyProducerButDistinctQueriesRequireAnAncestor() throws Exception {
        var fixture = fixture();
        String question = "查询短链数据，再解释表现和统计局限，最后列出证据缺口";
        var firstQuery = query("group-a", "2026-09-01", "LINK_METRICS");
        for (boolean identical : List.of(true, false)) {
            var dependentQuery = identical ? firstQuery : query("group-b", "2026-09-02", "DIMENSION_BREAKDOWN");
            var interpreted = new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, question, List.of(
                    new CampaignInterpretedRequest.Goal("查询数据", 0, question.length(), "STATISTICS", "PV",
                            List.of(firstQuery), false, List.of(), false, false),
                    new CampaignInterpretedRequest.Goal("解释表现与局限", 0, question.length(), "EXPLORE", "PV",
                            List.of(dependentQuery), false, List.of(0), true, false),
                    new CampaignInterpretedRequest.Goal("列出证据缺口", 0, question.length(), "EXPLORE", "PV",
                            List.of(dependentQuery), false, List.of(0, 1), true, false)), List.of());
            var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted, CLOCK.instant().plusSeconds(3600));
            var parsed = fixture.factory().inspect(OWNER, request.inputs());
            var producer = parsed.queries().get(0);
            var shared = new PlanSpec.Step("shared-query", List.of("goal-1", "goal-2", "goal-3"),
                    PlanSpec.ExecutionMode.FIXED, StatisticsJobFixedExecutor.REF, null, List.of(),
                    Map.of("scope", PlanBinding.input(producer.prefix() + "scope"),
                            "periods", PlanBinding.input(producer.prefix() + "periods"),
                            "query", PlanBinding.input(producer.prefix() + "query")), Map.of(), "statistics-job-pages/v1");
            var coverage = request.requirements().stream().map(requirement -> new PlanningAssessment.CoverageBinding(
                    requirement.requirementId(), List.of(new PlanningAssessment.EvidenceOutput(shared.stepId(), "pages")))).toList();
            var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session", "turn");
            var sharedPlan = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(shared), coverage, List.of())
                    .materialize(request, identity.planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
            if (identical) {
                var definition = sharedPlan.definition(OWNER, "session");
                fixture.factory().validateDefinition(definition);
                assertThat(FrozenStatisticsJobQuery.resolve(definition, StatisticsJobFixedExecutor.REF)).hasSize(1);
                assertThat(sharedPlan.assessment().gaps()).isEmpty();
                assertThat(sharedPlan.assessment().coverageBindings()).hasSize(request.requirements().size());
                continue;
            }
            // Matching types and goalIds cannot turn a different scope/period/descriptor into shared evidence.
            assertThatThrownBy(() -> fixture.factory().validateDefinition(sharedPlan.definition(OWNER, "session")))
                    .isInstanceOf(IllegalArgumentException.class);
            var first = new PlanSpec.Step(shared.stepId(), List.of("goal-1"), shared.executionMode(), shared.executor(),
                    null, List.of(), shared.inputBindings(), shared.parameters(), shared.outputContractRef());
            var dependent = parsed.queries().get(1);
            var second = new PlanSpec.Step("dependent-query", List.of("goal-2", "goal-3"), PlanSpec.ExecutionMode.FIXED,
                    StatisticsJobFixedExecutor.REF, null, List.of(),
                    Map.of("scope", PlanBinding.input(dependent.prefix() + "scope"),
                            "periods", PlanBinding.input(dependent.prefix() + "periods"),
                            "query", PlanBinding.input(dependent.prefix() + "query")), Map.of(), "statistics-job-pages/v1");
            var separateCoverage = request.requirements().stream().map(requirement -> new PlanningAssessment.CoverageBinding(
                    requirement.requirementId(), List.of(new PlanningAssessment.EvidenceOutput(
                            "goal-1".equals(requirement.goalId()) ? first.stepId() : second.stepId(), "pages")))).toList();
            var separatePlan = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, List.of(first, second), separateCoverage, List.of())
                    .materialize(request, identity.planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
            assertThatThrownBy(() -> fixture.factory().validateDefinition(separatePlan.definition(OWNER, "session")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("BUSINESS_REQUEST_INVALID");
            var ordered = new PlanSpec.Step(second.stepId(), second.goalIds(), second.executionMode(), second.executor(),
                    null, List.of(first.stepId()), second.inputBindings(), second.parameters(), second.outputContractRef());
            fixture.factory().validateDefinition(replaceSteps(separatePlan, List.of(first, ordered)).definition(OWNER, "session"));
        }
    }

    @Test
    void dependentDeclineChainRequiresRealPredecessorEdgesEvenForTheSameGroupAndPeriods() throws Exception {
        var fixture = fixture();
        String question = "先查询两天短链数据，再筛选下降短链并分析其省份设备变化";
        var queries = List.of(query("group-a", "2026-09-13", "LINK_METRICS"), query("group-a", "2026-09-14", "LINK_METRICS"));
        var interpreted = new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, question, List.of(
                new CampaignInterpretedRequest.Goal("两天短链数据", 0, question.length(), "STATISTICS", "PV", queries,
                        false, List.of(), false, false),
                new CampaignInterpretedRequest.Goal("下降筛选和联合维度", 0, question.length(), "DECLINE_DIMENSIONS", "PV", queries,
                        false, List.of(0), false, false)), List.of());
        var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted, CLOCK.instant().plusSeconds(3600));
        assertThat(request.goals().get(1).acceptance()).contains("scope_collection root must explicitly depend", "[goal-1]", "CURRENT_GROUP");
        var unconnected = frozen(fixture.factory(), request);
        assertThatThrownBy(() -> fixture.factory().validateDefinition(unconnected.definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("BUSINESS_REQUEST_INVALID");
        var correctedSteps = unconnected.plan().steps().stream().map(step -> {
            if (!"goal-2-collect-scope".equals(step.stepId())) return step;
            return new PlanSpec.Step(step.stepId(), step.goalIds(), step.executionMode(), step.executor(), null,
                    List.of("goal-1-query-1", "goal-1-query-2"), step.inputBindings(), step.parameters(), step.outputContractRef());
        }).sorted(Comparator.comparing(step -> !step.goalIds().contains("goal-1"))).toList();
        var corrected = replaceSteps(unconnected, correctedSteps);
        new PlanValidator(fixture.factory().catalog()).validate(corrected.plan(), corrected.inputs(), corrected.assessment());
        fixture.factory().validateDefinition(corrected.definition(OWNER, "session"));
        // Validation never repairs or mutates an already frozen definition in place.
        assertThatThrownBy(() -> fixture.factory().validateDefinition(unconnected.definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("BUSINESS_REQUEST_INVALID");
    }

    @Test
    void normalizesOnlyTheProvenSameStepSelectionPairWithoutChangingGoalsGapsOrOrdering() throws Exception {
        var fixture = fixture();
        var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted(), CLOCK.instant().plusSeconds(3600));
        var complete = frozen(fixture.factory(), request);
        var steps = new ArrayList<>(complete.plan().steps());
        var independent = steps.stream().filter(step -> "goal-3-query-1".equals(step.stepId())).findFirst().orElseThrow();
        String extraPredecessor = steps.get(0).stepId();
        steps.set(steps.indexOf(independent), new PlanSpec.Step(independent.stepId(), independent.goalIds(),
                independent.executionMode(), independent.executor(), independent.explorationPolicy(), List.of(extraPredecessor),
                independent.inputBindings(), independent.parameters(), independent.outputContractRef()));
        var candidate = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, steps,
                complete.assessment().coverageBindings(), List.of());
        candidate = selectionCoverage(candidate, List.of(new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectedEntities")));
        String original = candidate.encode();
        var unpaired = candidate;
        assertThatThrownBy(() -> unpaired.materialize(request, complete.plan().planId(), 1,
                fixture.factory().catalog(), fixture.factory().contracts())).isInstanceOf(IllegalArgumentException.class);

        var normalized = fixture.factory().normalizeProposal(OWNER, request, candidate);
        assertThat(normalized.steps()).isEqualTo(candidate.steps());
        assertThat(normalized.gaps()).isEqualTo(candidate.gaps());
        assertThat(candidate.encode()).isEqualTo(original);
        assertThat(normalized.coverageBindings().stream().filter(value -> "goal-1-selected".equals(value.requirementId()))
                .findFirst().orElseThrow().evidenceOutputs()).containsExactly(
                        new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectionEvidence"));
        var accepted = normalized.materialize(request, complete.plan().planId(), 1,
                fixture.factory().catalog(), fixture.factory().contracts());
        fixture.factory().validateDefinition(accepted.definition(OWNER, "session"));
        assertThat(fixture.factory().normalizeProposal(OWNER, request, normalized)).isEqualTo(normalized);
        assertThat(accepted.plan().goals()).isEqualTo(request.goals());

        // Neither another selection step nor a type-compatible output for another goal is a permitted repair.
        for (var invalid : List.of(
                List.of(new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput("goal-2-select-declines", "selectionEvidence")),
                List.of(new PlanningAssessment.EvidenceOutput("goal-2-select-declines", "selectedEntities")),
                List.of(new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectionEvidence")))) {
            var changed = selectionCoverage(candidate, invalid);
            assertThatThrownBy(() -> fixture.factory().normalizeProposal(OWNER, request, changed))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var selection = steps.stream().filter(step -> "goal-1-select-declines".equals(step.stepId())).findFirst().orElseThrow();
        var brokenSteps = new ArrayList<>(steps);
        brokenSteps.set(brokenSteps.indexOf(selection), new PlanSpec.Step(selection.stepId(), selection.goalIds(),
                selection.executionMode(), selection.executor(), selection.explorationPolicy(), List.of(),
                selection.inputBindings(), selection.parameters(), selection.outputContractRef()));
        var brokenProof = new PlanningProposal(candidate.schemaVersion(), brokenSteps, candidate.coverageBindings(), candidate.gaps());
        assertThatThrownBy(() -> fixture.factory().normalizeProposal(OWNER, request, brokenProof))
                .isInstanceOf(IllegalArgumentException.class);

        var explicitGap = new PlanningProposal(candidate.schemaVersion(), candidate.steps(), candidate.coverageBindings(),
                List.of(new PlanningAssessment.Gap("goal-1-selected", PlanningAssessment.GapReason.PLANNING_UNRESOLVED, "Selection proof unresolved")));
        assertThat(fixture.factory().normalizeProposal(OWNER, request, explicitGap)).isEqualTo(explicitGap);
        var absent = new PlanningProposal(candidate.schemaVersion(), candidate.steps(), candidate.coverageBindings().stream()
                .filter(value -> !"goal-1-selected".equals(value.requirementId())).toList(), candidate.gaps());
        assertThat(fixture.factory().normalizeProposal(OWNER, request, absent)).isEqualTo(absent);
        assertThatThrownBy(() -> absent.materialize(request, complete.plan().planId(), 1,
                fixture.factory().catalog(), fixture.factory().contracts())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reusesCompleteDeclaredStatisticsDependenciesWithoutDuplicateJobsOrCrossProducerCycles() throws Exception {
        var fixture = fixture();
        String question = "查两天数据，比较表现，再分析下降短链维度，另查独立分组";
        var first = query("group-a", "2026-09-13", "LINK_METRICS");
        var second = query("group-a", "2026-09-14", "LINK_METRICS");
        var interpreted = new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, question, List.of(
                new CampaignInterpretedRequest.Goal("第一天", 0, question.length(), "STATISTICS", "PV", List.of(first),
                        false, List.of(), false, false),
                new CampaignInterpretedRequest.Goal("第二天", 0, question.length(), "STATISTICS", "PV", List.of(second),
                        false, List.of(), false, false),
                new CampaignInterpretedRequest.Goal("比较两天", 0, question.length(), "STATISTICS", "PV", List.of(first, second),
                        false, List.of(0, 1), true, false),
                new CampaignInterpretedRequest.Goal("下降与维度", 0, question.length(), "DECLINE_DIMENSIONS", "PV", List.of(first, second),
                        false, List.of(0, 1, 2), true, false),
                new CampaignInterpretedRequest.Goal("独立查询", 0, question.length(), "STATISTICS", "PV",
                        List.of(query("group-b", "2026-09-14", "LINK_METRICS")), false, List.of(), false, false)), List.of());
        var request = fixture.factory().prepare(OWNER, "session", "turn", interpreted, CLOCK.instant().plusSeconds(3600));
        var base = frozen(fixture.factory(), request);
        var steps = base.plan().steps().stream().map(step -> switch (step.stepId()) {
            case "goal-3-query-1" -> withDependencies(step, List.of("goal-1-query-1"));
            case "goal-3-query-2" -> withDependencies(step, List.of("goal-2-query-1"));
            case "goal-4-collect-scope" -> withDependencies(step, List.of(
                    "goal-1-query-1", "goal-2-query-1", "goal-3-query-1", "goal-3-query-2"));
            default -> step;
        }).sorted(Comparator.comparingInt(step -> Integer.parseInt(step.goalIds().get(0).substring(5)))).toList();
        var candidate = new PlanningProposal(PlanningProposal.SCHEMA_VERSION, steps, base.assessment().coverageBindings(), List.of());
        String receipt = candidate.encode();
        var unnormalized = candidate.materialize(request, base.plan().planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
        assertThatThrownBy(() -> fixture.factory().validateDefinition(unnormalized.definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("BUSINESS_REQUEST_INVALID");

        var normalized = fixture.factory().normalizeProposal(OWNER, request, candidate);
        var accepted = normalized.materialize(request, base.plan().planId(), 1, fixture.factory().catalog(), fixture.factory().contracts());
        var definition = accepted.definition(OWNER, "session");
        fixture.factory().validateDefinition(definition);
        assertThat(normalized.steps()).extracting(PlanSpec.Step::stepId).containsExactly("goal-1-query-1", "goal-2-query-1",
                "goal-4-collect-scope", "goal-4-select-declines", "goal-4-dimension-change", "goal-5-query-1");
        assertThat(FrozenStatisticsJobQuery.resolve(definition, StatisticsJobFixedExecutor.REF)).hasSize(3);
        assertThat(FrozenDeclineSelection.templates(definition)).hasSize(1);
        assertThat(normalized.steps().get(0).goalIds()).containsExactly("goal-1", "goal-3");
        assertThat(normalized.steps().get(1).goalIds()).containsExactly("goal-2", "goal-3");
        assertThat(normalized.steps().get(0).dependsOn()).isEmpty();
        assertThat(normalized.steps().get(1).dependsOn()).isEmpty();
        assertThat(normalized.steps().get(2).dependsOn()).containsExactly("goal-1-query-1", "goal-2-query-1");
        assertThat(normalized.steps().get(5)).isEqualTo(steps.get(7));
        assertThat(normalized.coverageBindings().stream().filter(binding -> "goal-3-analysis".equals(binding.requirementId()))
                .findFirst().orElseThrow().evidenceOutputs()).containsExactly(
                        new PlanningAssessment.EvidenceOutput("goal-1-query-1", "pages"),
                        new PlanningAssessment.EvidenceOutput("goal-2-query-1", "pages"));
        assertThat(accepted.plan().goals()).isEqualTo(request.goals());
        assertThat(accepted.assessment().requirements()).isEqualTo(request.requirements());
        assertThat(accepted.assessment().gaps()).isEmpty();
        assertThat(candidate.encode()).isEqualTo(receipt);
        assertThat(fixture.factory().normalizeProposal(OWNER, request, normalized)).isEqualTo(normalized);

        // Missing or wrong-period prerequisite evidence cannot justify a shared producer or a dependency exemption.
        for (var invalidCoverage : List.of(
                candidate.coverageBindings().stream().filter(binding -> !"goal-2-query-1".equals(binding.requirementId())).toList(),
                candidate.coverageBindings().stream().map(binding -> "goal-2-query-1".equals(binding.requirementId())
                        ? new PlanningAssessment.CoverageBinding(binding.requirementId(), List.of(
                                new PlanningAssessment.EvidenceOutput("goal-1-query-1", "pages"))) : binding).toList())) {
            var invalid = new PlanningProposal(candidate.schemaVersion(), steps, invalidCoverage, List.of());
            assertThatThrownBy(() -> fixture.factory().normalizeProposal(OWNER, request, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        var gap = new PlanningProposal(candidate.schemaVersion(), steps, candidate.coverageBindings(), List.of(
                new PlanningAssessment.Gap("goal-2-query-1", PlanningAssessment.GapReason.EVIDENCE_UNAVAILABLE, "No usable second period")));
        assertThatThrownBy(() -> fixture.factory().normalizeProposal(OWNER, request, gap)).isInstanceOf(IllegalArgumentException.class);

        // Extra ordering intent, cycles and real dataflow consumers must not be erased to make deduplication fit.
        var additional = new ArrayList<>(steps);
        var independent = additional.remove(7); additional.add(2, independent);
        additional.set(3, withDependencies(additional.get(3), List.of("goal-1-query-1", "goal-5-query-1")));
        var extraOrdering = new PlanningProposal(candidate.schemaVersion(), additional, candidate.coverageBindings(), List.of());
        assertThatThrownBy(() -> fixture.factory().normalizeProposal(OWNER, request, extraOrdering)).isInstanceOf(IllegalArgumentException.class);
        var cyclicSteps = new ArrayList<>(steps);
        cyclicSteps.set(0, withDependencies(cyclicSteps.get(0), List.of("goal-3-query-1")));
        var cyclic = new PlanningProposal(candidate.schemaVersion(), cyclicSteps, candidate.coverageBindings(), List.of());
        assertThatThrownBy(() -> fixture.factory().normalizeProposal(OWNER, request, cyclic)).isInstanceOf(IllegalArgumentException.class);
        var dataflowSteps = new ArrayList<>(steps);
        var consumer = dataflowSteps.get(7);
        var bindings = new LinkedHashMap<>(consumer.inputBindings());
        bindings.put("query", PlanBinding.output("goal-3-query-1", "pages"));
        dataflowSteps.set(7, new PlanSpec.Step(consumer.stepId(), consumer.goalIds(), consumer.executionMode(), consumer.executor(),
                null, List.of("goal-3-query-1"), bindings, consumer.parameters(), consumer.outputContractRef()));
        var dataflow = new PlanningProposal(candidate.schemaVersion(), dataflowSteps, candidate.coverageBindings(), List.of());
        assertThatThrownBy(() -> fixture.factory().normalizeProposal(OWNER, request, dataflow)).isInstanceOf(IllegalArgumentException.class);

        // A no-coverage side branch cannot borrow the valid shared goal's exemption.
        var straySteps = new ArrayList<>(normalized.steps());
        var duplicate = steps.get(2);
        straySteps.add(withDependencies(duplicate, List.of()));
        assertThatThrownBy(() -> fixture.factory().validateDefinition(replaceSteps(accepted, straySteps).definition(OWNER, "session")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("BUSINESS_REQUEST_INVALID");
    }

    private static PlanSpec.Step withDependencies(PlanSpec.Step step, List<String> dependencies) {
        return new PlanSpec.Step(step.stepId(), step.goalIds(), step.executionMode(), step.executor(), step.explorationPolicy(),
                dependencies, step.inputBindings(), step.parameters(), step.outputContractRef());
    }

    private static PlanningProposal selectionCoverage(PlanningProposal proposal, List<PlanningAssessment.EvidenceOutput> outputs) {
        return new PlanningProposal(proposal.schemaVersion(), proposal.steps(), proposal.coverageBindings().stream()
                .map(binding -> "goal-1-selected".equals(binding.requirementId())
                        ? new PlanningAssessment.CoverageBinding(binding.requirementId(), outputs) : binding).toList(), proposal.gaps());
    }

    private static CampaignInterpretedRequest dependentRequest(List<Integer> secondDependencies) {
        String question = "查询两组的不同期间，然后解释，再给建议";
        return new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, question, List.of(
                new CampaignInterpretedRequest.Goal("查询数据", 0, question.length(), "STATISTICS", "PV",
                        List.of(query("group-a", "2026-09-01", "LINK_METRICS"), query("group-b", "2026-09-02", "LINK_METRICS")),
                        false, List.of(), false, false),
                new CampaignInterpretedRequest.Goal("解释上一步数据", 0, question.length(), "STATISTICS", "PV",
                        List.of(), false, secondDependencies, true, false),
                new CampaignInterpretedRequest.Goal("据此给出建议", 0, question.length(), "STATISTICS", "PV",
                        List.of(), false, List.of(1), false, true)), List.of());
    }

    private static FrozenCampaignRun frozen(CampaignBusinessPlanFactory factory, PlanningProposal.Request request) {
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session", "turn");
        var parsed = factory.inspect(OWNER, request.inputs());
        List<PlanSpec.Step> steps = new ArrayList<>();
        for (var branch : parsed.dependencies()) {
            String goal = branch.prefix().substring(0, branch.prefix().length() - 1);
            var goalIds = new LinkedHashSet<>(List.of(goal));
            String sourceSuffix = "-source-" + goal.substring(5) + "-selected";
            request.requirements().stream().filter(value -> value.requirementId().equals(value.goalId() + sourceSuffix))
                    .forEach(value -> goalIds.add(value.goalId()));
            for (var step : FrozenCampaignRun.read(branch.definition()).plan().steps()) {
                Map<String,PlanBinding> bindings = new LinkedHashMap<>();
                step.inputBindings().forEach((name, binding) -> bindings.put(name,
                        binding.source() == PlanBinding.Source.INPUT ? PlanBinding.input(branch.prefix() + binding.input())
                                : PlanBinding.output(branch.prefix() + binding.stepId(), binding.output())));
                steps.add(new PlanSpec.Step(branch.prefix() + step.stepId(), List.copyOf(goalIds), step.executionMode(), step.executor(), null,
                        step.dependsOn().stream().map(value -> branch.prefix() + value).toList(), bindings, step.parameters(), step.outputContractRef()));
            }
        }
        for (var query : parsed.queries()) {
            String stepId = query.prefix().substring(0, query.prefix().length() - 1);
            String goalId = query.prefix().substring(0, query.prefix().indexOf("-query-"));
            steps.add(new PlanSpec.Step(stepId, List.of(goalId), PlanSpec.ExecutionMode.FIXED,
                    StatisticsJobFixedExecutor.REF, null, List.of(), Map.of("scope", PlanBinding.input(query.prefix() + "scope"),
                    "periods", PlanBinding.input(query.prefix() + "periods"), "query", PlanBinding.input(query.prefix() + "query")),
                    Map.of(), StatisticsJobFixedExecutor.capability().signature().outputContractRef()));
        }
        List<PlanningAssessment.CoverageBinding> coverage = new ArrayList<>();
        for (var requirement : request.requirements()) {
            String prefix = requirement.requirementId().matches("goal-[0-9]+-source-[0-9]+-(selected|dimensions)")
                    ? "goal-" + requirement.requirementId().split("-")[3] + "-" : requirement.goalId() + "-";
            List<PlanningAssessment.EvidenceOutput> outputs = switch (requirement.criterionRef()) {
                case "selected-entities-delivery" -> List.of(new PlanningAssessment.EvidenceOutput(prefix + "select-declines", "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput(prefix + "select-declines", "selectionEvidence"));
                case "dimension-change-delivery" -> List.of(new PlanningAssessment.EvidenceOutput(prefix + "dimension-change", "dimensionChanges"));
                case "statistics-query-evidence" -> List.of(new PlanningAssessment.EvidenceOutput(requirement.requirementId(), "pages"));
                case "statistics-evidence-delivery" -> parsed.queries().stream().filter(query -> query.prefix().startsWith(prefix))
                        .map(query -> new PlanningAssessment.EvidenceOutput(query.prefix().substring(0, query.prefix().length() - 1), "pages")).toList();
                case "analysis-interpretation", "analysis-recommendation" -> List.of(
                        new PlanningAssessment.EvidenceOutput("goal-3-query-1", "pages"),
                        new PlanningAssessment.EvidenceOutput("goal-3-query-2", "pages"));
                case "analysis-interpretation-dimensions", "analysis-recommendation-dimensions" -> steps.stream()
                        .filter(step -> step.goalIds().contains(requirement.goalId()) && FrozenDimensionChange.REF_V2.equals(step.executor()))
                        .map(step -> new PlanningAssessment.EvidenceOutput(step.stepId(), "dimensionChanges")).toList();
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

    private static CampaignInterpretedRequest skillFollowUpRequest(boolean otherGroup) {
        String question = "筛选下降短链并查看省份设备变化，然后解释这些结果，再根据解释给出建议";
        List<CampaignInterpretedRequest.Goal> goals = new ArrayList<>();
        for (String gid : otherGroup ? List.of("group-a", "group-b") : List.of("group-a"))
            goals.add(new CampaignInterpretedRequest.Goal("下降与维度 " + gid, 0, question.length(), "DECLINE_DIMENSIONS", "PV",
                    List.of(query(gid, "2026-09-13", "LINK_METRICS"), query(gid, "2026-09-14", "LINK_METRICS")),
                    false, List.of(), false, false));
        int interpretation = goals.size();
        goals.add(new CampaignInterpretedRequest.Goal("解释第一组结果", 0, question.length(), "STATISTICS", "PV",
                List.of(), false, List.of(0), true, false));
        goals.add(new CampaignInterpretedRequest.Goal("据此给出建议", 0, question.length(), "EXPLORE", "PV",
                List.of(), false, List.of(interpretation), false, true));
        return new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, question, goals, List.of());
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
