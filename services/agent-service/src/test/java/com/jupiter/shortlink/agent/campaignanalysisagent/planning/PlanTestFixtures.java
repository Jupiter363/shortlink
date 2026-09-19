package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class PlanTestFixtures {
    static final String VERSION = "1.0.0";
    static final CapabilityCatalog.TypeRef SCOPE = type("ScopeRef");
    static final CapabilityCatalog.TypeRef PERIODS = type("PeriodsRef");
    static final CapabilityCatalog.TypeRef STATS = type("campaign.stats");
    static final CapabilityCatalog.TypeRef ANALYSIS = type("campaign.analysis");
    static final PlanSpec.ExecutorRef QUERY = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", VERSION);
    static final PlanSpec.ExecutorRef SUMMARIZE = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, "summarize", VERSION);

    static CapabilityCatalog.TypeRef type(String name) {
        return new CapabilityCatalog.TypeRef(name, 1, CapabilityCatalog.Cardinality.ONE);
    }

    static CapabilityCatalog.Port port(CapabilityCatalog.TypeRef type) {
        return new CapabilityCatalog.Port(type, true);
    }

    static final class Catalog implements CapabilityCatalog {
        private final String version;
        final Map<PlanSpec.ExecutorRef, Capability> capabilities = new HashMap<>();
        final Map<String, Policy> policies = new HashMap<>();
        final Map<String, Criterion> criteria = new HashMap<>();

        Catalog() { this("catalog-v1"); }
        Catalog(String version) { this.version = version; }
        @Override public String version() { return version; }
        @Override public Optional<Capability> capability(PlanSpec.ExecutorRef executor) {
            return Optional.ofNullable(capabilities.get(executor));
        }
        @Override public Optional<Policy> policy(String ref, String version) {
            return Optional.ofNullable(policies.get(ref + "@" + version));
        }
        @Override public Optional<Criterion> criterion(String ref, String version) {
            return Optional.ofNullable(criteria.get(ref + "@" + version));
        }
        void register(Criterion criterion) {
            criteria.put(criterion.criterionRef() + "@" + criterion.criterionVersion(), criterion);
        }
    }

    record Fixture(PlanSpec plan, FrozenInputSet inputs, PlanningAssessment assessment, Catalog catalog) {
        Fixture steps(List<PlanSpec.Step> steps) {
            return new Fixture(new PlanSpec(plan.schemaVersion(), plan.planId(), plan.revision(), plan.runId(),
                    plan.inputSetRef(), plan.goals(), steps), inputs, assessment, catalog);
        }
        Fixture assessment(PlanningAssessment changed) { return new Fixture(plan, inputs, changed, catalog); }
    }

    static Fixture fixed() {
        Catalog catalog = new Catalog();
        var parameters = new CapabilityCatalog.Parameters(Set.of("metric"), Map.of(
                "metric", value -> value instanceof String metric && Set.of("pv", "uv", "uip").contains(metric),
                "filters", value -> value instanceof List<?>));
        var signature = new CapabilityCatalog.Signature(Map.of("scope", port(SCOPE), "periods", port(PERIODS)),
                "stats/v1", Map.of("stats", port(STATS)), parameters);
        catalog.capabilities.put(QUERY, new CapabilityCatalog.Capability(QUERY, signature, false));
        catalog.register(new CapabilityCatalog.Criterion("data/v1", VERSION,
                PlanningAssessment.RequirementKind.DATA, CapabilityCatalog.Parameters.none(), Set.of(STATS)));
        catalog.register(new CapabilityCatalog.Criterion("delivery/v1", VERSION,
                PlanningAssessment.RequirementKind.DELIVERY, CapabilityCatalog.Parameters.none(), Set.of(STATS)));
        var goal = new PlanSpec.Goal("goal", "Analyze visits", true, "Deliver evidence and interpretation");
        var step = new PlanSpec.Step("query-step", List.of("goal"), null, QUERY, null, List.of(),
                Map.of("scope", PlanBinding.input("scopeRef"), "periods", PlanBinding.input("periodsRef")),
                Map.of("metric", "pv"), "stats/v1");
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1", List.of(goal), List.of(step));
        var inputs = new FrozenInputSet("inputs-1", "run-1", Map.of("scopeRef", port(SCOPE), "periodsRef", port(PERIODS)),
                Map.of("scopeRef", "scope-1", "periodsRef", "periods-1"));
        var assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", List.of(
                requirement("data", PlanningAssessment.RequirementKind.DATA, "data/v1"),
                requirement("delivery", PlanningAssessment.RequirementKind.DELIVERY, "delivery/v1")),
                List.of(coverage("data", "query-step", "stats"), coverage("delivery", "query-step", "stats")), List.of());
        return new Fixture(plan, inputs, assessment, catalog);
    }

    static Fixture pipeline() {
        Fixture fixture = fixed();
        var signature = new CapabilityCatalog.Signature(Map.of("evidence", port(STATS)), "analysis/v1",
                Map.of("analysis", port(ANALYSIS)), CapabilityCatalog.Parameters.none());
        fixture.catalog.capabilities.put(SUMMARIZE, new CapabilityCatalog.Capability(SUMMARIZE, signature, false));
        fixture.catalog.register(new CapabilityCatalog.Criterion("delivery/v1", VERSION,
                PlanningAssessment.RequirementKind.DELIVERY, CapabilityCatalog.Parameters.none(), Set.of(ANALYSIS)));
        var step = new PlanSpec.Step("summarize-step", List.of("goal"), PlanSpec.ExecutionMode.FIXED, SUMMARIZE,
                null, List.of("query-step"), Map.of("evidence", PlanBinding.output("query-step", "stats")),
                Map.of(), "analysis/v1");
        var assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", fixture.assessment.requirements(),
                List.of(coverage("data", "query-step", "stats"), coverage("delivery", "summarize-step", "analysis")), List.of());
        return fixture.steps(List.of(fixture.plan.steps().get(0), step)).assessment(assessment);
    }

    static Fixture react() {
        Fixture fixture = fixed();
        var criteria = List.of(new PlanSpec.CriterionUse("data-present/v1", Map.of("complete", true)));
        var definition = new CapabilityCatalog.Policy("explore/v1", VERSION,
                fixture.catalog.capabilities.get(QUERY).signature(), Set.of(QUERY), criteria, "bounded/v1");
        fixture.catalog.policies.put("explore/v1@" + VERSION, definition);
        var policy = new PlanSpec.ExplorationPolicy("explore/v1", VERSION, List.of(QUERY),
                "scope-1", "periods-1", criteria, "bounded/v1");
        var old = fixture.plan.steps().get(0);
        var step = new PlanSpec.Step(old.stepId(), old.goalIds(), PlanSpec.ExecutionMode.REACT, null, policy,
                old.dependsOn(), old.inputBindings(), old.parameters(), old.outputContractRef());
        return fixture.steps(List.of(step));
    }

    static PlanningAssessment.Requirement requirement(String id, PlanningAssessment.RequirementKind kind, String criterion) {
        return new PlanningAssessment.Requirement(id, "goal", kind, true, criterion, VERSION, Map.of());
    }

    static PlanningAssessment.CoverageBinding coverage(String id, String step, String port) {
        return new PlanningAssessment.CoverageBinding(id, List.of(new PlanningAssessment.EvidenceOutput(step, port)));
    }
}
