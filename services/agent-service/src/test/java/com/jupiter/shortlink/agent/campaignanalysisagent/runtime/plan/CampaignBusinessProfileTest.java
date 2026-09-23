package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual persisted business plans and native runtime assembly; no model or statistics I/O. */
@Timeout(30)
class CampaignBusinessProfileTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void restoresStatisticsOnlyAndDependencyPlansWithoutInstantiatingUnplannedSkills() throws Exception {
        for (boolean dependency : List.of(false, true)) {
            var source = new DriverManagerDataSource("jdbc:h2:mem:business_profile_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql")).execute(source);
            var jdbc = new JdbcTemplate(source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            var runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
            var steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
            var approvedRoot = new ClassPathResource("campaign-skills").getFile().toPath();
            var dependencyPlans = new CampaignDependencyAnalysisPlanFactory(approvedRoot, CLOCK);
            var plans = new CampaignBusinessPlanFactory(dependencyPlans, CLOCK);
            var original = definition(plans, dependency);
            RunToken token = runs.createRun(original);
            // Restore bytes from the authoritative ledger, not the in-memory proposal instance.
            token = runs.loadRun(OWNER, original.runId()).orElseThrow().token();
            assertEquals(original, token.definition());
            var scopes = mock(CampaignScopeStore.class);
            var results = mock(CampaignStatisticsResultStore.class);
            var selections = mock(CampaignDeclineSelectionStore.class);
            var candidates = mock(CampaignExplorationCandidateStore.class);
            var calls = mock(CampaignExplorationCallStore.class);
            var skills = mock(CampaignSkillInvocationStore.class);
            var gateway = mock(ShortLinkBusinessGateway.class);
            var model = mock(ChatModel.class);
            var authority = mock(AgentAuthorityClient.class);
            when(authority.verifyCurrentPrincipal(PRINCIPAL)).thenReturn(PRINCIPAL);
            when(authority.resolveGroupMembersPage(eq(PRINCIPAL), eq("group-a"), isNull(), isNull()))
                    .thenReturn(new GroupMembersPage(GroupMembersPage.SCHEMA, OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(),
                            "group-a", "a".repeat(64), null, List.of(1L, 2L), null));
            var saver = new MemorySaver();
            var exploration = new CampaignExplorationRuntimeFactory(jdbc, tx, CLOCK, runs, steps, calls, results, candidates,
                    skills, scopes, selections, approvedRoot, gateway, new ModelInvocationRegistry(List.of()), model,
                    saver, Runnable::run, new CampaignExplorationRuntimeFactory.Settings("unused-model", "1", "b".repeat(64),
                        new NativeExplorationAdapter.Limits(0, 4096, 32768, 32768, 8, Duration.ofSeconds(10)),
                        ExplorationBudgetPolicy.defaults(), ExplorationRepeatPolicy.disabled()));
            var process = new ProcessExecutionScope();
            try {
                for (int recovery = 0; recovery < 2; recovery++) {
                    // Reconstruct the production profile for each resume as the real intake does.
                    var profile = new CampaignBusinessProfile(runs, steps, scopes, results, selections, candidates, plans,
                            dependencyPlans, approvedRoot, authority, gateway, saver, exploration).profile();
                    var current = runs.loadRun(OWNER, original.runId()).orElseThrow().token();
                    var runtime = profile.runtimeFactory().create(new CampaignRunIntake.RuntimeContext(current, PRINCIPAL, process,
                            profile.runAuthorizer(), profile.artifactAuthorizer(), profile.inputAuthorizer()));
                    assertNotNull(runtime.driver()); assertNotNull(runtime.graph());
                    assertEquals(dependency ? 3 : 1, steps.steps(current).size());
                    assertEquals(dependency ? 0 : 1, runtime.resultTargets().size());
                    assertTrue(steps.steps(current).stream().allMatch(step -> step.status() == CampaignStepStore.StepStatus.PENDING));
                }
                if (!dependency) {
                    RunToken restored = token;
                    assertEquals("DECLINE_SKILL_NOT_IN_PLAN", assertThrows(IllegalArgumentException.class,
                            () -> new DeclineSelectionSkill(restored, PRINCIPAL, approvedRoot, runs, steps, scopes, results,
                                    selections, gateway, (principal, scope, periods, request) -> false,
                                    (caller, artifact) -> false, FrozenDeclineSelection.REF_V2)).getMessage());
                }
                verifyNoInteractions(gateway, model);
            } finally { process.closeAndAwaitActualExit(); }
        }
    }

    private static RunDefinition definition(CampaignBusinessPlanFactory plans, boolean dependency) {
        String question = dependency ? "查看下降短链及维度变化" : "查询短链访问数据";
        var baseline = new CampaignInterpretedRequest.Query("group-a", null,
                new CampaignInterpretedRequest.Period("2026-09-01", "2026-09-01"), "LINK_METRICS", List.of(), List.of());
        var target = new CampaignInterpretedRequest.Query("group-a", null,
                new CampaignInterpretedRequest.Period("2026-09-02", "2026-09-02"), "LINK_METRICS", List.of(), List.of());
        var interpreted = new CampaignInterpretedRequest(CampaignInterpretedRequest.SCHEMA, question,
                List.of(new CampaignInterpretedRequest.Goal(question, 0, question.length(),
                    dependency ? "DECLINE_DIMENSIONS" : "STATISTICS", "PV", dependency ? List.of(baseline, target) : List.of(target),
                    false, List.of(), false, false)), List.of());
        var request = plans.prepare(OWNER, "session", "profile-test", interpreted, CLOCK.instant().plusSeconds(3600));
        var parsed = plans.inspect(OWNER, request.inputs());
        List<PlanSpec.Step> steps = new ArrayList<>();
        List<PlanningAssessment.CoverageBinding> coverage = new ArrayList<>();
        if (dependency) {
            var branch = parsed.dependencies().get(0);
            for (var step : FrozenCampaignRun.read(branch.definition()).plan().steps()) {
                Map<String,PlanBinding> bindings = new LinkedHashMap<>();
                step.inputBindings().forEach((name, binding) -> bindings.put(name, binding.source() == PlanBinding.Source.INPUT
                        ? PlanBinding.input("goal-1-" + binding.input()) : PlanBinding.output("goal-1-" + binding.stepId(), binding.output())));
                steps.add(new PlanSpec.Step("goal-1-" + step.stepId(), List.of("goal-1"), step.executionMode(), step.executor(), null,
                        step.dependsOn().stream().map(id -> "goal-1-" + id).toList(), bindings, step.parameters(), step.outputContractRef()));
            }
            coverage.add(new PlanningAssessment.CoverageBinding("goal-1-selected", List.of(
                    new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectedEntities"),
                    new PlanningAssessment.EvidenceOutput("goal-1-select-declines", "selectionEvidence"))));
            coverage.add(new PlanningAssessment.CoverageBinding("goal-1-dimensions", List.of(
                    new PlanningAssessment.EvidenceOutput("goal-1-dimension-change", "dimensionChanges"))));
        } else {
            var query = parsed.queries().get(0);
            steps.add(new PlanSpec.Step("statistics", List.of("goal-1"), PlanSpec.ExecutionMode.FIXED, StatisticsJobFixedExecutor.REF,
                    null, List.of(), Map.of("scope", PlanBinding.input(query.prefix() + "scope"),
                        "periods", PlanBinding.input(query.prefix() + "periods"), "query", PlanBinding.input(query.prefix() + "query")),
                    Map.of(), "statistics-job-pages/v1"));
            request.requirements().forEach(requirement -> coverage.add(new PlanningAssessment.CoverageBinding(requirement.requirementId(),
                    List.of(new PlanningAssessment.EvidenceOutput("statistics", "pages")))));
        }
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session", "profile-test");
        return new PlanningProposal(PlanningProposal.SCHEMA_VERSION, steps, coverage, List.of())
                .materialize(request, identity.planId(), 1, plans.catalog(), plans.contracts()).definition(OWNER, "session");
    }
}
