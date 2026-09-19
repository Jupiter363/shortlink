package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import static com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanTestFixtures.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidationException.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanValidatorTest {
    @Test
    void fixedAndReactPlansUseTheSameStaticGate() {
        for (Fixture fixture : List.of(fixed(), pipeline(), react())) {
            assertThatCode(() -> validate(fixture)).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixed-without-executor", "fixed-with-policy", "react-without-policy", "react-with-executor"})
    void executionModesCannotHideOrReplaceTheExecutor(String invalid) {
        Fixture fixture = react();
        var old = fixture.plan().steps().get(0);
        boolean fixed = invalid.startsWith("fixed");
        var changed = new PlanSpec.Step(old.stepId(), old.goalIds(), fixed ? PlanSpec.ExecutionMode.FIXED : PlanSpec.ExecutionMode.REACT,
                invalid.endsWith("without-executor") || invalid.endsWith("without-policy") ? null : QUERY,
                invalid.endsWith("without-executor") || invalid.endsWith("without-policy") ? null : old.explorationPolicy(),
                old.dependsOn(), old.inputBindings(), old.parameters(), old.outputContractRef());
        rejected(fixture.steps(List.of(changed)), INVALID_MODE);
    }

    @Test
    void aLaterInvalidStepPreventsTheCallerFromDispatchingEvenTheFirstStep() {
        Fixture fixture = pipeline();
        var second = fixture.plan().steps().get(1);
        var bad = step(second, List.of(), second.inputBindings(), second.parameters());
        Fixture invalid = fixture.steps(List.of(fixture.plan().steps().get(0), bad));
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> {
            validate(invalid);
            invalid.plan().steps().forEach(ignored -> calls.incrementAndGet());
        }).isInstanceOf(PlanValidationException.class);
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"self", "cycle", "forward", "missing", "duplicate", "transitive-only"})
    void dependenciesMustBeUniqueDirectAndTopologicallyOrdered(String kind) {
        Fixture fixture = pipeline();
        var first = fixture.plan().steps().get(0);
        var second = fixture.plan().steps().get(1);
        List<PlanSpec.Step> steps = switch (kind) {
            case "self" -> List.of(step(first, List.of(first.stepId()), first.inputBindings(), first.parameters()), second);
            case "cycle" -> List.of(step(first, List.of(second.stepId()), first.inputBindings(), first.parameters()), second);
            case "forward" -> List.of(second, first);
            case "missing" -> List.of(first, step(second, List.of("absent"), second.inputBindings(), second.parameters()));
            case "duplicate" -> List.of(first, step(second, List.of(first.stepId(), first.stepId()), second.inputBindings(), second.parameters()));
            default -> {
                var middle = new PlanSpec.Step("middle", first.goalIds(), first.executionMode(), first.executor(), null,
                        List.of(first.stepId()), first.inputBindings(), first.parameters(), first.outputContractRef());
                yield List.of(first, middle, step(second, List.of("middle"), second.inputBindings(), second.parameters()));
            }
        };
        rejected(fixture.steps(steps), INVALID_DEPENDENCY);
    }

    @Test
    void duplicateIdentitiesAndUnknownGoalsAreRejected() {
        Fixture fixture = fixed();
        rejected(fixture.steps(List.of(fixture.plan().steps().get(0), fixture.plan().steps().get(0))), DUPLICATE_STEP);
        var plan = fixture.plan();
        var duplicateGoals = new PlanSpec(plan.schemaVersion(), plan.planId(), plan.revision(), plan.runId(), plan.inputSetRef(),
                List.of(plan.goals().get(0), plan.goals().get(0)), plan.steps());
        rejected(new Fixture(duplicateGoals, fixture.inputs(), fixture.assessment(), fixture.catalog()), DUPLICATE_GOAL);
        var old = plan.steps().get(0);
        var unknown = new PlanSpec.Step(old.stepId(), List.of("other"), old.executionMode(), old.executor(), null,
                old.dependsOn(), old.inputBindings(), old.parameters(), old.outputContractRef());
        rejected(fixture.steps(List.of(unknown)), UNKNOWN_GOAL);
    }

    @Test
    void inputAndAssessmentIdentityCannotDriftToAnotherRunOrRevision() {
        Fixture f = fixed();
        var inputs = new FrozenInputSet(f.inputs().inputSetRef(), "run-2", f.inputs().inputContracts(), f.inputs().inputValues());
        rejected(new Fixture(f.plan(), inputs, f.assessment(), f.catalog()), IDENTITY);
        var assessment = new PlanningAssessment("plan-1", 2, "catalog-v1", f.assessment().requirements(), f.assessment().coverageBindings(), List.of());
        rejected(f.assessment(assessment), IDENTITY);
        var latest = new FrozenInputSet("inputs-latest", "run-1", f.inputs().inputContracts(), f.inputs().inputValues());
        rejected(new Fixture(f.plan(), latest, f.assessment(), f.catalog()), IDENTITY);
    }

    @Test
    void executorAndCatalogVersionsAreExactRegisteredVersions() {
        Fixture f = fixed();
        var old = f.plan().steps().get(0);
        var unknown = new PlanSpec.Step(old.stepId(), old.goalIds(), old.executionMode(),
                new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, QUERY.name(), "next"), null,
                old.dependsOn(), old.inputBindings(), old.parameters(), old.outputContractRef());
        rejected(f.steps(List.of(unknown)), UNREGISTERED_EXECUTOR);
        var a = f.assessment();
        rejected(f.assessment(new PlanningAssessment(a.planId(), a.revision(), "old-catalog", a.requirements(), a.coverageBindings(), a.gaps())), CATALOG_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"major", "cardinality", "name"})
    void outputTypeMajorVersionAndCardinalityMustMatchTheConsumer(String change) {
        Fixture f = pipeline();
        var type = new CapabilityCatalog.TypeRef(change.equals("name") ? "unrelated" : STATS.name(),
                change.equals("major") ? 2 : 1, change.equals("cardinality") ? CapabilityCatalog.Cardinality.MANY : CapabilityCatalog.Cardinality.ONE);
        var old = f.catalog().capabilities.get(SUMMARIZE);
        var signature = new CapabilityCatalog.Signature(Map.of("evidence", port(type)), old.signature().outputContractRef(),
                old.signature().outputs(), old.signature().parameters());
        f.catalog().capabilities.put(SUMMARIZE, new CapabilityCatalog.Capability(SUMMARIZE, signature, false));
        rejected(f, TYPE_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-port", "unknown-input", "mixed-binding"})
    void bindingsAreClosedAndMustResolveToActualPorts(String change) {
        Fixture f = fixed();
        var old = f.plan().steps().get(0);
        Map<String, PlanBinding> bindings = switch (change) {
            case "missing-port" -> Map.of("scope", PlanBinding.input("scopeRef"));
            case "unknown-input" -> Map.of("scope", PlanBinding.input("latestScope"), "periods", PlanBinding.input("periodsRef"));
            default -> Map.of("scope", new PlanBinding(PlanBinding.Source.INPUT, "scopeRef", null, null, "artifact-1"),
                    "periods", PlanBinding.input("periodsRef"));
        };
        rejected(f.steps(List.of(step(old, List.of(), bindings, old.parameters()))), INVALID_BINDING);
    }

    @Test
    void artifactsNeedTrustedTypeMetadataAndStillDoNotImplyRuntimeAuthorization() {
        Fixture f = pipeline();
        var old = f.plan().steps().get(1);
        Fixture withArtifact = f.steps(List.of(f.plan().steps().get(0), step(old, List.of(), Map.of("evidence", PlanBinding.artifact("artifact-1")), Map.of())));
        rejected(withArtifact, UNKNOWN_ARTIFACT);
        assertThatCode(() -> new PlanValidator(f.catalog(), id -> Optional.of(STATS))
                .validate(withArtifact.plan(), withArtifact.inputs(), withArtifact.assessment())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"scopeRef", "gid", "executor", "sql", "expression", "json_path", "snapshotId", "tenantId"})
    void authorityAndExecutableFieldsCannotBeHiddenInsideAllowedNestedParameters(String hidden) {
        Fixture f = fixed();
        var old = f.plan().steps().get(0);
        Fixture invalid = f.steps(List.of(step(old, List.of(), old.inputBindings(),
                Map.of("metric", "pv", "filters", List.of(Map.of(hidden, "private-value"))))));
        assertThatThrownBy(() -> validate(invalid)).isInstanceOf(PlanValidationException.class)
                .hasMessage("Invalid campaign plan: INVALID_PARAMETERS").hasMessageNotContaining("private-value");
    }

    @Test
    void registeredParameterRulesRejectUnknownMissingAndWrongValues() {
        Fixture f = fixed(); var old = f.plan().steps().get(0);
        for (Map<String, Object> params : List.<Map<String, Object>>of(Map.of(), Map.of("metric", "roi"), Map.of("metric", "pv", "extra", true))) {
            rejected(f.steps(List.of(step(old, List.of(), old.inputBindings(), params))), INVALID_PARAMETERS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope", "period", "completion", "termination", "executor", "policy-version"})
    void explorationCannotWeakenRegisteredPolicyOrChangeFrozenBoundaries(String change) {
        Fixture f = react(); var old = f.plan().steps().get(0); var p = old.explorationPolicy();
        var changed = new PlanSpec.ExplorationPolicy(p.policyRef(), change.equals("policy-version") ? "next" : p.policyVersion(),
                change.equals("executor") ? List.of(SUMMARIZE) : p.allowedExecutors(),
                change.equals("scope") ? "different-scope" : p.scopeRef(), change.equals("period") ? "different-period" : p.periodsRef(),
                change.equals("completion") ? List.of() : p.completionCriteria(), change.equals("termination") ? "unlimited" : p.terminationPolicyRef());
        var step = new PlanSpec.Step(old.stepId(), old.goalIds(), old.executionMode(), null, changed, old.dependsOn(),
                old.inputBindings(), old.parameters(), old.outputContractRef());
        rejected(f.steps(List.of(step)), change.equals("policy-version") ? UNREGISTERED_POLICY : POLICY_MISMATCH);
    }

    @Test
    void explorationCannotIndirectlyInvokeAnotherExplorerThroughACapability() {
        Fixture f = react(); var old = f.catalog().capabilities.get(QUERY);
        f.catalog().capabilities.put(QUERY, new CapabilityCatalog.Capability(QUERY, old.signature(), true));
        rejected(f, POLICY_MISMATCH);
    }

    @Test
    void dataAndCalculationsWithoutGoalDeliveryCannotPassPlanning() {
        Fixture f = fixed(); var a = f.assessment();
        rejected(f.assessment(new PlanningAssessment(a.planId(), a.revision(), a.capabilityCatalogVersion(),
                List.of(a.requirements().get(0)), List.of(a.coverageBindings().get(0)), List.of())), MISSING_DELIVERY);
    }

    @Test
    void unknownCriterionAndUncoveredRequiredConditionsNeedExplicitGaps() {
        Fixture f = fixed(); var a = f.assessment();
        f.catalog().criteria.remove("delivery/v1@" + VERSION);
        rejected(f, UNKNOWN_CRITERION);
        var partial = new PlanningAssessment(a.planId(), a.revision(), a.capabilityCatalogVersion(), a.requirements(),
                List.of(a.coverageBindings().get(0)), List.of(new PlanningAssessment.Gap("delivery",
                PlanningAssessment.GapReason.PLANNING_UNRESOLVED, "No registered delivery checker")));
        assertThatCode(() -> validate(f.assessment(partial))).doesNotThrowAnyException();
        Fixture known = fixed();
        rejected(known.assessment(new PlanningAssessment(a.planId(), a.revision(), a.capabilityCatalogVersion(),
                a.requirements(), List.of(a.coverageBindings().get(0)), List.of())), INVALID_COVERAGE);
    }

    @Test
    void coverageMustReferenceRealOutputPortsAndCompatibleEvidence() {
        Fixture f = fixed(); var a = f.assessment();
        rejected(f.assessment(new PlanningAssessment(a.planId(), a.revision(), a.capabilityCatalogVersion(), a.requirements(),
                List.of(a.coverageBindings().get(0), coverage("delivery", "query-step", "made-up")), List.of())), INVALID_BINDING);
        f.catalog().register(new CapabilityCatalog.Criterion("delivery/v1", VERSION,
                PlanningAssessment.RequirementKind.DELIVERY, CapabilityCatalog.Parameters.none(), Set.of(ANALYSIS)));
        rejected(f, TYPE_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void infrastructureAndAnotherGoalsOutputsCannotSilentlyCoverARequirement(boolean wrongGoal) {
        Fixture f = fixed(); var p = f.plan(); var old = p.steps().get(0);
        var changed = new PlanSpec.Step(old.stepId(), wrongGoal ? List.of("other") : List.of(),
                old.executionMode(), old.executor(), old.explorationPolicy(), old.dependsOn(),
                old.inputBindings(), old.parameters(), old.outputContractRef());
        var goals = new ArrayList<>(p.goals());
        goals.add(new PlanSpec.Goal("other", "Another question", true, "Its own delivered answer"));
        var plan = new PlanSpec(p.schemaVersion(), p.planId(), p.revision(), p.runId(), p.inputSetRef(), goals, List.of(changed));
        rejected(new Fixture(plan, f.inputs(), f.assessment(), f.catalog()), INVALID_COVERAGE);
    }

    @Test
    void oneStepCanExplicitlyProduceEvidenceForMultipleGoals() {
        Fixture f = fixed(); var p = f.plan(); var old = p.steps().get(0); var a = f.assessment();
        var goals = new ArrayList<>(p.goals());
        goals.add(new PlanSpec.Goal("other", "Another question using the same data", true, "Deliver the shared evidence"));
        var step = new PlanSpec.Step(old.stepId(), List.of("goal", "other"), old.executionMode(), old.executor(),
                old.explorationPolicy(), old.dependsOn(), old.inputBindings(), old.parameters(), old.outputContractRef());
        var requirements = new ArrayList<>(a.requirements());
        requirements.add(new PlanningAssessment.Requirement("other-delivery", "other", PlanningAssessment.RequirementKind.DELIVERY,
                true, "delivery/v1", VERSION, Map.of()));
        var coverage = new ArrayList<>(a.coverageBindings());
        coverage.add(coverage("other-delivery", old.stepId(), "stats"));
        var assessment = new PlanningAssessment(a.planId(), a.revision(), a.capabilityCatalogVersion(), requirements, coverage, List.of());
        var plan = new PlanSpec(p.schemaVersion(), p.planId(), p.revision(), p.runId(), p.inputSetRef(), goals, List.of(step));
        assertThatCode(() -> new PlanValidator(f.catalog()).validate(plan, f.inputs(), assessment)).doesNotThrowAnyException();
    }

    @Test
    void anUnresolvedGoalIsRetainedWhileIndependentGoalsHaveExecutableSteps() {
        Fixture f = fixed(); var p = f.plan(); var a = f.assessment();
        List<PlanSpec.Goal> goals = new ArrayList<>(p.goals());
        goals.add(new PlanSpec.Goal("cause", "Prove causality", true, "Causal evidence and actual delivery"));
        List<PlanningAssessment.Requirement> requirements = new ArrayList<>(a.requirements());
        requirements.add(new PlanningAssessment.Requirement("causal-evidence", "cause", PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE,
                true, "causal/v1", VERSION, Map.of()));
        requirements.add(new PlanningAssessment.Requirement("causal-delivery", "cause", PlanningAssessment.RequirementKind.DELIVERY,
                true, "causal-delivery/v1", VERSION, Map.of()));
        var assessment = new PlanningAssessment(a.planId(), a.revision(), a.capabilityCatalogVersion(), requirements, a.coverageBindings(),
                List.of(new PlanningAssessment.Gap("causal-evidence", PlanningAssessment.GapReason.UNSUPPORTED, "No causal source"),
                        new PlanningAssessment.Gap("causal-delivery", PlanningAssessment.GapReason.PLANNING_UNRESOLVED, "Cannot yet deliver causal answer")));
        var plan = new PlanSpec(p.schemaVersion(), p.planId(), p.revision(), p.runId(), p.inputSetRef(), goals, p.steps());
        assertThatCode(() -> new PlanValidator(f.catalog()).validate(plan, f.inputs(), assessment)).doesNotThrowAnyException();
        assertThat(plan.goals()).hasSize(2);
        assertThat(assessment.gaps()).hasSize(2);
    }

    private static PlanSpec.Step step(PlanSpec.Step old, List<String> dependencies, Map<String, PlanBinding> bindings, Map<String, Object> parameters) {
        return new PlanSpec.Step(old.stepId(), old.goalIds(), old.executionMode(), old.executor(), old.explorationPolicy(),
                dependencies, bindings, parameters, old.outputContractRef());
    }

    private static void validate(Fixture fixture) {
        new PlanValidator(fixture.catalog()).validate(fixture.plan(), fixture.inputs(), fixture.assessment());
    }

    private static void rejected(Fixture fixture, PlanValidationException.Code code) {
        assertThatThrownBy(() -> validate(fixture)).isInstanceOfSatisfying(PlanValidationException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
