package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Capability;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Criterion;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Parameters;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Policy;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CampaignStatisticsPlanFactoryTest {
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final String SESSION = "session-1";
    private static final String DELIVERY = "statistics-evidence-delivery";
    private static final String DELIVERY_VERSION = "1";
    private final CapabilityCatalog catalog = catalog();
    private final CampaignStatisticsPlanFactory factory =
            new CampaignStatisticsPlanFactory(catalog, DELIVERY, DELIVERY_VERSION);

    @Test
    void comparisonFreezesOneRealFixedStepPerObjectAndPeriodWithoutGatewayIo() {
        var prepared = factory.comparison(OWNER, SESSION, "request-1",
                List.of(Map.of("gid", "alpha", "label", "Campaign A"), Map.of("gid", "beta")),
                List.of(period("2026-09-01", "2026-09-07"), period("2026-09-08", "2026-09-14")));
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, SESSION, "request-1");
        assertThat(prepared.definition().runId()).isEqualTo(identity.runId());
        assertThat(prepared.definition().planId()).isEqualTo(identity.planId());
        assertThat(prepared.definition().revision()).isEqualTo(1);
        assertThat(prepared.queries()).extracting(CampaignStatisticsPlanFactory.StepQuery::stepId)
                .containsExactly("collect-1", "collect-2", "collect-3", "collect-4");
        assertThat(prepared.frozen().plan().steps()).allSatisfy(step -> {
            assertThat(step.executionMode()).isEqualTo(PlanSpec.ExecutionMode.FIXED);
            assertThat(step.executor()).isEqualTo(StatisticsJobFixedExecutor.REF);
            assertThat(step.explorationPolicy()).isNull();
        });
        assertThat(prepared.queries()).extracting(query -> query.request().get("queryKind"))
                .containsOnly("METRICS");
        assertThat(prepared.queries().get(0).scopeRef()).isEqualTo(prepared.queries().get(1).scopeRef());
        assertThat(prepared.queries().get(0).periodsRef()).isEqualTo(prepared.queries().get(2).periodsRef());
        assertThat(prepared.queries().get(0).scopeRef()).isEqualTo("current-group.v1:alpha");
        assertThat(prepared.queries().get(0).periodsRef())
                .isEqualTo("period.v1:2026-09-01:2026-09-07");
        assertThat(prepared.queries().get(3).scopeRef()).isEqualTo("current-group.v1:beta");
        assertThat(prepared.queries().get(3).periodsRef())
                .isEqualTo("period.v1:2026-09-08:2026-09-14");
        assertThat(prepared.frozen().inputs().inputValues()).containsKey("operation");
        new PlanValidator(catalog).validate(prepared.frozen().plan(), prepared.frozen().inputs(),
                prepared.frozen().assessment());

        var bound = FrozenStatisticsJobQuery.resolve(prepared.definition(), StatisticsJobFixedExecutor.REF);
        assertThat(bound).hasSize(4);
        assertThat(bound.values()).extracting(value -> value.child().childId()).doesNotHaveDuplicates();
        assertThat(bound.values()).extracting(value -> value.child().requestId()).doesNotHaveDuplicates();
        assertThat(bound.values()).allSatisfy(value -> assertThat(value.request())
                .containsEntry("queryKind", "METRICS").containsKey("requestId"));
        ShortLinkBusinessGateway gateway = mock(ShortLinkBusinessGateway.class);
        var executor = new StatisticsJobFixedExecutor(prepared.definition(),
                new AgentPrincipal("1001", "alice", 7, false), gateway,
                (current, scope, periods, request) -> true);
        assertThat(executor.resultTargets()).hasSize(4);
        verifyNoInteractions(gateway);
    }

    @Test
    void sameRequestIsStableButComparisonOrderOrScopeChangesTheFrozenDefinition() {
        List<Map<String, Object>> scopes = List.of(Map.of("gid", "alpha"), Map.of("gid", "beta"));
        var first = period("2026-09-01", "2026-09-07");
        var second = period("2026-09-08", "2026-09-14");
        var original = factory.comparison(OWNER, SESSION, "request-2", scopes, List.of(first, second));
        var replay = factory.comparison(OWNER, SESSION, "request-2", scopes, List.of(first, second));
        var reversed = factory.comparison(OWNER, SESSION, "request-2", scopes, List.of(second, first));
        var otherOwner = factory.comparison(new Caller("1002", "alice", 7), SESSION,
                "request-2", scopes, List.of(first, second));
        assertThat(replay.definition()).isEqualTo(original.definition());
        assertThat(replay.queries()).isEqualTo(original.queries());
        assertThat(reversed.definition().runId()).isEqualTo(original.definition().runId());
        assertThat(reversed.queryPlanId()).isNotEqualTo(original.queryPlanId());
        assertThat(reversed.definition().definitionHash()).isNotEqualTo(original.definition().definitionHash());
        assertThat(otherOwner.definition().runId()).isNotEqualTo(original.definition().runId());
    }

    @Test
    void rankingFreezesOptionsAndOneCompleteLinkMetricsQuery() {
        var ranking = factory.ranking(OWNER, SESSION, "request-3", "alpha",
                "2026-09-01", "2026-09-14", "uv", 20);
        var changedMetric = factory.ranking(OWNER, SESSION, "request-3", "alpha",
                "2026-09-01", "2026-09-14", "pv", 20);
        assertThat(ranking.queries()).hasSize(1);
        assertThat(ranking.queries().get(0).scopeRef()).isEqualTo("current-group.v1:alpha");
        assertThat(ranking.queries().get(0).periodsRef())
                .isEqualTo("period.v1:2026-09-01:2026-09-14");
        assertThat(ranking.queries().get(0).request()).containsEntry("queryKind", "LINK_METRICS")
                .containsEntry("gid", "alpha");
        assertThat(ranking.frozen().inputs().inputValues().get("operation"))
                .isEqualTo(Map.of("schemaVersion", CampaignStatisticsPlanFactory.OPERATION_SCHEMA,
                        "kind", "RANKING", "queryPlanId", ranking.queryPlanId(),
                        "metric", "uv", "limit", 20));
        assertThat(FrozenStatisticsJobQuery.resolve(ranking.definition(), StatisticsJobFixedExecutor.REF))
                .hasSize(1);
        assertThat(changedMetric.definition().runId()).isEqualTo(ranking.definition().runId());
        assertThat(changedMetric.definition().definitionHash()).isNotEqualTo(ranking.definition().definitionHash());
    }

    @Test
    void linkSpecificQueriesKeepTheSameParseableGroupRefWithoutExposingUrls() {
        var prepared = factory.comparison(OWNER, SESSION, "request-links",
                List.of(Map.of("gid", "alpha", "fullShortUrl", "https://go.example/one"),
                        Map.of("gid", "alpha", "fullShortUrl", "https://go.example/two")),
                List.of(period("2026-09-01", "2026-09-07")));
        assertThat(prepared.queries()).hasSize(2);
        assertThat(prepared.queries()).extracting(CampaignStatisticsPlanFactory.StepQuery::scopeRef)
                .containsOnly("current-group.v1:alpha");
        assertThat(FrozenStatisticsJobQuery.resolve(prepared.definition(), StatisticsJobFixedExecutor.REF)
                .values()).extracting(bound -> bound.child().requestId())
                .doesNotHaveDuplicates();
        assertThat(prepared.queries().get(0).request().get("fullShortUrl"))
                .isNotEqualTo(prepared.queries().get(1).request().get("fullShortUrl"));
    }

    @Test
    void invalidQueriesFailBeforeCreatingAnyFrozenDefinition() {
        assertThatThrownBy(() -> factory.comparison(OWNER, SESSION, "bad-1",
                List.of(Map.of("gid", "alpha")), List.of(period("2026-09-01", "2026-09-07"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.comparison(OWNER, SESSION, "bad-2",
                List.of(Map.of("gid", "alpha")),
                List.of(period("1969-12-01", "1969-12-07"), period("2026-09-01", "2026-09-07"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.ranking(OWNER, SESSION, "bad-3", "alpha",
                "2026-09-01", "2026-09-14", "ctr", 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.ranking(OWNER, SESSION, "bad-4", "alpha",
                "2026-09-01", "2026-09-14", "pv", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.ranking(OWNER, SESSION, "bad-5", "alpha",
                "2026-09-14", "2026-09-01", "pv", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.comparison(OWNER, SESSION, "bad-6",
                List.of(Map.of("gid", "alpha:other"), Map.of("gid", "beta")),
                List.of(period("2026-09-01", "2026-09-07"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("STATISTICS_SCOPE_GID_INVALID");
        assertThatThrownBy(() -> factory.ranking(OWNER, SESSION, "bad-7", " alpha",
                "2026-09-01", "2026-09-07", "pv", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("STATISTICS_RANKING_GID_INVALID");
        assertThatThrownBy(() -> factory.ranking(OWNER, SESSION, "bad-8", "alpha",
                "2026-9-01", "2026-09-07", "pv", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("STATISTICS_RANKING_PERIOD_INVALID");
    }

    private static Map<String, Object> period(String start, String end) {
        return Map.of("startDate", start, "endDate", end);
    }

    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            @Override public String version() { return "campaign-statistics/v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef executor) {
                return StatisticsJobFixedExecutor.REF.equals(executor)
                        ? Optional.of(StatisticsJobFixedExecutor.capability()) : Optional.empty();
            }
            @Override public Optional<Policy> policy(String ref, String version) {
                return Optional.empty();
            }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return DELIVERY.equals(ref) && DELIVERY_VERSION.equals(version)
                        ? Optional.of(new Criterion(DELIVERY, DELIVERY_VERSION,
                                PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(),
                                Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE))) : Optional.empty();
            }
        };
    }
}
