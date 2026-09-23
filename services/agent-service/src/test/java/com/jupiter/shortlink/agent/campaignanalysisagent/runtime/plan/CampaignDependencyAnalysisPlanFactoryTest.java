package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CampaignDependencyAnalysisPlanFactoryTest {
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant EXPIRY = Instant.parse("2026-09-20T00:00:00Z");

    private static CampaignDependencyAnalysisPlanFactory factory() throws Exception {
        return new CampaignDependencyAnalysisPlanFactory(
                new ClassPathResource("campaign-skills").getFile().toPath(), CLOCK);
    }

    private static CampaignDependencyAnalysisPlanFactory.Request request() {
        return new CampaignDependencyAnalysisPlanFactory.Request("group-a", "2026-09-01", "2026-09-03",
                "2026-09-04", "2026-09-06", "PV", List.of("province", "device"), List.of());
    }

    @Test
    void freezesTwoDeliveriesAndRealV2DependentTemplatesDeterministically() throws Exception {
        var factory = factory();
        var first = factory.prepare(OWNER, "session-1", "question-1", request(), EXPIRY);
        var replay = factory.prepare(OWNER, "session-1", "question-1", request(), EXPIRY);
        var changed = factory.prepare(OWNER, "session-1", "question-1",
                new CampaignDependencyAnalysisPlanFactory.Request("group-a", "2026-09-01", "2026-09-03",
                        "2026-09-04", "2026-09-06", "UV", List.of("province", "device"), List.of()), EXPIRY);
        var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session-1", "question-1");
        assertThat(first.definition().runId()).isEqualTo(identity.runId());
        assertThat(first.definition().planId()).isEqualTo(identity.planId());
        assertThat(replay.definition()).isEqualTo(first.definition());
        assertThat(changed.definition().runId()).isEqualTo(first.definition().runId());
        assertThat(changed.definition().definitionHash()).isNotEqualTo(first.definition().definitionHash());

        var frozen = first.frozen();
        new PlanValidator(CampaignDependencyAnalysisPlanFactory.catalog()).validate(
                frozen.plan(), frozen.inputs(), frozen.assessment());
        assertThat(frozen.plan().goals()).hasSize(2).allMatch(PlanSpec.Goal::required);
        assertThat(frozen.plan().steps()).extracting(PlanSpec.Step::stepId).containsExactly(
                CampaignDependencyAnalysisPlanFactory.COLLECT,
                CampaignDependencyAnalysisPlanFactory.SELECT,
                CampaignDependencyAnalysisPlanFactory.DIMENSION);
        assertThat(frozen.plan().steps()).allMatch(step -> step.executionMode() == PlanSpec.ExecutionMode.FIXED);
        assertThat(frozen.plan().steps().get(1).dependsOn()).containsExactly(CampaignDependencyAnalysisPlanFactory.COLLECT);
        assertThat(frozen.plan().steps().get(2).dependsOn()).containsExactly(CampaignDependencyAnalysisPlanFactory.SELECT);
        assertThat(frozen.assessment().requirements()).hasSize(2).allMatch(requirement ->
                requirement.required() && requirement.kind() == PlanningAssessment.RequirementKind.DELIVERY);
        assertThat(frozen.assessment().coverageBindings()).hasSize(2);
        assertThat(frozen.assessment().coverageBindings().get(0).evidenceOutputs())
                .extracting(PlanningAssessment.EvidenceOutput::stepId)
                .containsOnly(CampaignDependencyAnalysisPlanFactory.SELECT);
        assertThat(frozen.assessment().coverageBindings().get(1).evidenceOutputs())
                .extracting(PlanningAssessment.EvidenceOutput::stepId)
                .containsOnly(CampaignDependencyAnalysisPlanFactory.DIMENSION);
        assertThat(FrozenScopeCollection.resolve(first.definition())).containsKey(CampaignDependencyAnalysisPlanFactory.COLLECT);
        assertThat(FrozenDeclineSelection.templates(first.definition())).containsKey(CampaignDependencyAnalysisPlanFactory.SELECT);
        assertThat(FrozenDimensionChange.resolveTemplates(first.definition(), FrozenDimensionChange.REF_V2))
                .containsKey(CampaignDependencyAnalysisPlanFactory.DIMENSION);
        assertThat(factory.inspect(frozen.inputs())).isEqualTo(request());
        assertThat(CampaignDependencyAnalysisPlanFactory.contracts().typeOf("DimensionChangeArtifact",
                DimensionChangePublisher.SCHEMA)).isNotNull();
    }

    @Test
    void rejectsUnsupportedMetricDimensionsPeriodsFiltersAndExpiry() throws Exception {
        var factory = factory();
        assertThatThrownBy(() -> factory.prepare(OWNER, "session-1", "bad-metric",
                new CampaignDependencyAnalysisPlanFactory.Request("group-a", "2026-09-01", "2026-09-03",
                        "2026-09-04", "2026-09-06", "CTR", List.of("province", "device"), List.of()), EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.prepare(OWNER, "session-1", "missing-metric",
                new CampaignDependencyAnalysisPlanFactory.Request("group-a", "2026-09-01", "2026-09-03",
                        "2026-09-04", "2026-09-06", null, List.of("province", "device"), List.of()), EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.prepare(OWNER, "session-1", "bad-dimension",
                new CampaignDependencyAnalysisPlanFactory.Request("group-a", "2026-09-01", "2026-09-03",
                        "2026-09-04", "2026-09-06", "PV", List.of("country"), List.of()), EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.prepare(OWNER, "session-1", "bad-period",
                new CampaignDependencyAnalysisPlanFactory.Request("group-a", "2026-09-04", "2026-09-06",
                        "2026-09-01", "2026-09-03", "PV", List.of("province", "device"), List.of()), EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.prepare(OWNER, "session-1", "bad-filter",
                new CampaignDependencyAnalysisPlanFactory.Request("group-a", "2026-09-01", "2026-09-03",
                        "2026-09-04", "2026-09-06", "PV", List.of("province", "device"),
                        List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江"),
                                "unapprovedConstraint", "other"))), EXPIRY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.prepare(OWNER, "session-1", "expired", request(), CLOCK.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.prepare(OWNER, "session-1", "submillisecond", request(),
                EXPIRY.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inspectRejectsDescriptorPinAndReferenceDrift() throws Exception {
        var factory = factory();
        FrozenInputSet inputs = factory.prepare(OWNER, "session-1", "question-2", request(), EXPIRY).frozen().inputs();
        Map<String, Object> values = new LinkedHashMap<>(inputs.inputValues());
        values.put("periods", "different-pair");
        Map<String, Object> periodDrift = values;
        assertThatThrownBy(() -> factory.inspect(withValues(inputs, periodDrift)))
                .isInstanceOf(IllegalArgumentException.class);

        values = new LinkedHashMap<>(inputs.inputValues());
        Map<String, Object> selection = new LinkedHashMap<>(cast(values.get("selectionDefinition")));
        selection.put("target", Map.of("periodsRef", "target", "startDate", "2026-09-05",
                "endDate", "2026-09-06", "timeZone", "Asia/Shanghai"));
        values.put("selectionDefinition", selection);
        Map<String, Object> descriptorDrift = values;
        assertThatThrownBy(() -> factory.inspect(withValues(inputs, descriptorDrift)))
                .isInstanceOf(IllegalArgumentException.class);

        values = new LinkedHashMap<>(inputs.inputValues());
        Map<String, Object> dimension = new LinkedHashMap<>(cast(values.get("dimensionDefinition")));
        Map<String, Object> pin = new LinkedHashMap<>(cast(dimension.get("skillPin")));
        pin.put("sha256", "0".repeat(64));
        dimension.put("skillPin", pin);
        values.put("dimensionDefinition", dimension);
        Map<String, Object> pinDrift = values;
        assertThatThrownBy(() -> factory.inspect(withValues(inputs, pinDrift)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> factory.inspect(new FrozenInputSet("inputs-forged", inputs.runId(),
                inputs.inputContracts(), inputs.inputValues()))).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }

    private static FrozenInputSet withValues(FrozenInputSet source, Map<String, Object> values) {
        return new FrozenInputSet(source.inputSetRef(), source.runId(), source.inputContracts(), values);
    }
}
