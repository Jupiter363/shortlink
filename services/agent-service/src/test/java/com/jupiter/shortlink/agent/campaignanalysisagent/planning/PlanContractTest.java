package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import static com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void recordsFreezeNestedInputsAndParametersBeforeCallerStateCanChange() {
        Map<String, Object> row = new LinkedHashMap<>(Map.of("dimension", "province"));
        List<Object> rows = new ArrayList<>(List.of(row));
        Map<String, Object> mutable = new LinkedHashMap<>(Map.of("rows", rows));
        var inputs = new FrozenInputSet("inputs", "run", Map.of(), mutable);
        var step = new PlanSpec.Step("step", List.of(), null, QUERY, null, List.of(), Map.of(), mutable, "stats/v1");
        row.put("dimension", "device");
        rows.clear();
        mutable.clear();
        for (Map<String, Object> frozen : List.of(inputs.inputValues(), step.parameters())) {
            List<Object> retained = (List<Object>) frozen.get("rows");
            assertThat(retained).containsExactly(Map.of("dimension", "province"));
            assertThatThrownBy(() -> frozen.put("other", true)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> retained.add("other")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ((Map<String, Object>) retained.get(0)).put("other", true))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void cyclesNonJsonValuesAndExcessiveDepthAreRejectedWithoutStackOverflow() {
        Map<String, Object> cycle = new HashMap<>();
        cycle.put("cycle", cycle);
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(cycle, Map.of("value", new StringBuilder()),
                Map.of("value", Double.NaN), nestedContainers(20_000))) {
            assertThatThrownBy(() -> ImmutablePlanValues.json(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> ImmutablePlanValues.json(nestedContainers(128))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ImmutablePlanValues.json(nestedContainers(129)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("128");
    }

    @Test
    void depthLimitDoesNotLimitTheNumberOfObjectsOrAnalysisSections() {
        List<Object> rows = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) rows.add(Map.of("section", index));
        assertThat((List<?>) ImmutablePlanValues.json(Map.of("rows", rows)).get("rows")).hasSize(10_000);
    }

    @Test
    void parameterScanningAcceptsTheDeclaredStructuralDepthBoundary() {
        Fixture f = fixed(); var old = f.plan().steps().get(0);
        Map<String, Object> parameters = Map.of("metric", "pv", "filters", List.of(nestedContainers(126)));
        var step = new PlanSpec.Step(old.stepId(), old.goalIds(), old.executionMode(), old.executor(), null,
                old.dependsOn(), old.inputBindings(), parameters, old.outputContractRef());
        assertThatCode(() -> new PlanValidator(f.catalog()).validate(f.steps(List.of(step)).plan(), f.inputs(), f.assessment()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expression", "jsonPath", "scopeRef"})
    void executableOrScopeBindingFieldsFailEvenWithAnOtherwiseLenientMapper(String field) {
        ObjectMapper lenient = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String input = "{\"source\":\"INPUT\",\"input\":\"scopeRef\",\"" + field + "\":\"private-value\"}";
        assertThatThrownBy(() -> lenient.readValue(input, PlanBinding.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example-plan.json", "example-hybrid-plan.json"})
    void documentedFixedAndHybridExamplesHaveValidTypedGoalAndPortBindings(String filename) throws Exception {
        JsonNode document = JSON.readTree(example(filename).toFile());
        PlanSpec plan = JSON.treeToValue(document.get("planSpec"), PlanSpec.class);
        JsonNode input = document.get("inputSets").get(0);
        FrozenInputSet inputs = new FrozenInputSet(input.get("inputSetRef").asText(), input.get("runId").asText(),
                ports(input.get("inputContracts")), values(input.get("inputValues")));
        JsonNode assessmentJson = document.get("planningAssessment");
        PlanningAssessment assessment = new PlanningAssessment(assessmentJson.get("planId").asText(),
                assessmentJson.get("revision").asInt(), assessmentJson.get("capabilityCatalogVersion").asText(),
                JSON.convertValue(assessmentJson.get("requirements"), new TypeReference<>() { }),
                JSON.convertValue(assessmentJson.get("coverageBindings"), new TypeReference<>() { }),
                JSON.convertValue(assessmentJson.get("gaps"), new TypeReference<>() { }));
        Catalog catalog = proposedExampleCatalog(document, plan, assessment.capabilityCatalogVersion());
        assertThatCode(() -> new PlanValidator(catalog).validate(plan, inputs, assessment)).doesNotThrowAnyException();
        assertThat(plan.goals()).hasSize(2);
        assertThat(plan.steps()).hasSize(3);
        assertThat(assessment.requirements()).hasSize(6);
        assertThat(plan.steps().get(0).goalIds()).containsExactly("find-declining-links", "explain-region-device-changes");
        assertThat(assessment.requirements().stream().filter(requirement -> requirement.kind()
                == PlanningAssessment.RequirementKind.DELIVERY)).hasSize(2);
    }

    /** Test-only catalog conversion; PROPOSED examples must never register production capabilities. */
    private static Catalog proposedExampleCatalog(JsonNode document, PlanSpec plan, String version) {
        Catalog catalog = new Catalog(version);
        for (JsonNode definition : document.path("proposedExecutors")) {
            var executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.valueOf(definition.get("kind").asText()),
                    definition.get("name").asText(), definition.get("version").asText());
            Map<String, Object> parameters = plan.steps().stream().filter(step -> executor.equals(step.executor()))
                    .findFirst().map(PlanSpec.Step::parameters).orElse(Map.of());
            catalog.capabilities.put(executor, new CapabilityCatalog.Capability(executor,
                    signature(document, definition, exactParameters(parameters)), definition.path("invokesLocalExplorer").asBoolean(false)));
        }
        for (JsonNode definition : document.path("proposedPolicies")) {
            String ref = definition.get("policyRef").asText();
            String policyVersion = definition.get("policyVersion").asText();
            var policy = new CapabilityCatalog.Policy(ref, policyVersion,
                    signature(document, definition, exactParameters(values(definition.get("parameterContract")))),
                    new HashSet<>(JSON.convertValue(definition.get("allowedExecutors"),
                            new TypeReference<List<PlanSpec.ExecutorRef>>() { })),
                    JSON.convertValue(definition.get("completionCriteria"), new TypeReference<>() { }),
                    definition.get("terminationPolicyRef").asText());
            catalog.policies.put(ref + "@" + policyVersion, policy);
        }
        for (JsonNode definition : document.get("proposedCriteria")) {
            String ref = definition.get("criterionRef").asText();
            JsonNode schema = definition.get("parameterSchema");
            Set<String> required = new HashSet<>();
            schema.get("required").forEach(value -> required.add(value.asText()));
            Map<String, Predicate<Object>> validators = new HashMap<>();
            schema.get("properties").fields().forEachRemaining(entry -> {
                JsonNode expected = entry.getValue().get("const");
                if (expected == null) throw new IllegalArgumentException("Example needs an explicit parameter checker");
                validators.put(entry.getKey(), value -> expected.equals(JSON.valueToTree(value)));
            });
            catalog.register(new CapabilityCatalog.Criterion(ref, definition.get("criterionVersion").asText(),
                    PlanningAssessment.RequirementKind.valueOf(definition.get("kind").asText()),
                    new CapabilityCatalog.Parameters(required, validators), criterionEvidence(ref)));
        }
        return catalog;
    }

    private static Set<CapabilityCatalog.TypeRef> criterionEvidence(String ref) {
        var selection = type("campaign.selected-entities");
        var evidence = type("campaign.decline-evidence");
        var dimensions = type("campaign.dimension-changes");
        return switch (ref) {
            case "campaign.candidate-period-coverage/v1" -> Set.of(evidence);
            case "campaign.negative-pv-selection/v1", "campaign.selection-result-delivery/v1" -> Set.of(selection, evidence);
            case "campaign.selected-joint-dimension-coverage/v1" -> Set.of(selection, dimensions);
            case "campaign.joint-change-calculation/v1" -> Set.of(dimensions);
            case "campaign.evidence-bound-explanation/v1" -> Set.of(dimensions, type("campaign.evidence-analysis"));
            default -> throw new IllegalArgumentException("Example needs registered evidence type expectations");
        };
    }

    private static CapabilityCatalog.Signature signature(JsonNode document, JsonNode definition,
                                                         CapabilityCatalog.Parameters parameters) {
        String outputRef = definition.get("outputContractRef").asText();
        return new CapabilityCatalog.Signature(ports(definition.get("inputPorts")), outputRef,
                ports(document.get("outputContracts").get(outputRef).get("outputs")), parameters);
    }

    private static CapabilityCatalog.Parameters exactParameters(Map<String, Object> expected) {
        Map<String, Predicate<Object>> validators = new HashMap<>();
        expected.forEach((name, value) -> validators.put(name, actual -> java.util.Objects.equals(value, actual)));
        return new CapabilityCatalog.Parameters(expected.keySet(), validators);
    }

    private static Map<String, CapabilityCatalog.Port> ports(JsonNode node) {
        Map<String, CapabilityCatalog.Port> result = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            String name = entry.getValue().get("type").asText();
            int major = 1;
            if (name.startsWith("ArtifactRef<") && name.endsWith(">")) {
                name = name.substring("ArtifactRef<".length(), name.length() - 1);
                int version = name.lastIndexOf("/v");
                major = Integer.parseInt(name.substring(version + 2));
                name = name.substring(0, version);
            }
            result.put(entry.getKey(), new CapabilityCatalog.Port(
                    new CapabilityCatalog.TypeRef(name, major, CapabilityCatalog.Cardinality.ONE),
                    entry.getValue().get("required").asBoolean()));
        });
        return result;
    }

    private static Map<String, Object> values(JsonNode node) {
        return JSON.convertValue(node, new TypeReference<>() { });
    }

    private static Path example(String filename) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("doc/plan/campaign-agent-architecture-2026-09-19").resolve(filename);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository example not found");
    }

    private static Map<String, Object> nestedContainers(int count) {
        Map<String, Object> value = Map.of("leaf", true);
        for (int index = 1; index < count; index++) value = Map.of("child", value);
        return value;
    }
}
