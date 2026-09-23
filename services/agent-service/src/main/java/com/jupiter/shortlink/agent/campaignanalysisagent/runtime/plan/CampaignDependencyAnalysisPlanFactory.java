package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.SkillScanner;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A trusted, frozen three-step dependency scenario. It never enumerates a group or executes a Skill. */
public final class CampaignDependencyAnalysisPlanFactory {
    public static final String OPERATION_SCHEMA = "campaign-dependency-analysis-operation/v1";
    public static final TypeRef OPERATION_TYPE =
            new TypeRef("CampaignDependencyAnalysisOperation", 1, Cardinality.ONE);
    public static final String CATALOG_VERSION = "campaign-dependency-analysis/v1";
    public static final String COLLECT = "collect-scope";
    public static final String SELECT = "select-declines";
    public static final String DIMENSION = "dimension-change";
    private static final String SELECT_GOAL = "deliver-declining-entities";
    private static final String DIMENSION_GOAL = "deliver-dimension-change";
    private static final String SELECT_DELIVERY = "selected-entities-delivery";
    private static final String DIMENSION_DELIVERY = "dimension-change-delivery";
    private static final String CRITERION_VERSION = "1";
    private static final String ZONE = "Asia/Shanghai";
    private static final Set<String> METRICS = Set.of("PV", "UV", "UIP");
    private static final List<String> V2_DIMENSIONS = List.of("province", "device");
    private static final TypeRef SELECTED = new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE);
    private static final TypeRef EVIDENCE = new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE);
    private static final TypeRef CHANGES = new TypeRef("DimensionChangeArtifact", 1, Cardinality.ONE);
    private static final Map<String, Port> INPUT_CONTRACTS = Map.of(
            "operation", new Port(OPERATION_TYPE, true),
            "collectionDefinition", FrozenScopeCollection.INPUTS.get("definition"),
            "periods", FrozenDeclineSelection.INPUTS_V2.get("periods"),
            "selectionDefinition", FrozenDeclineSelection.INPUTS_V2.get("definition"),
            "dimensionDefinition", FrozenDimensionChange.INPUTS_V2.get("definition"));

    public record Request(String gid, String baselineStart, String baselineEnd,
                          String targetStart, String targetEnd, String metric,
                          List<String> dimensions, List<Map<String, Object>> filters) { }

    public record Prepared(RunDefinition definition, FrozenCampaignRun frozen) {
        public Prepared {
            if (definition == null || frozen == null
                    || !definition.equals(frozen.definition(definition.caller(), definition.sessionId())))
                throw new IllegalArgumentException("DEPENDENCY_DEFINITION_CHANGED");
        }
    }

    private final Path approvedSkillsRoot;
    private final Map<String, MethodSnapshot> methods;
    private final Clock clock;
    private volatile InspectedInputs lastInspected;

    private record MethodSnapshot(byte[] source, Map<String, Object> pin) { }
    private record InspectedInputs(FrozenInputSet inputs, Request request, Instant expiresAt) { }

    public CampaignDependencyAnalysisPlanFactory(Path approvedSkillsRoot, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "DEPENDENCY_CLOCK_REQUIRED");
        try {
            this.approvedSkillsRoot = Objects.requireNonNull(approvedSkillsRoot,
                    "DEPENDENCY_SKILLS_ROOT_REQUIRED").toRealPath();
            require(Files.isDirectory(this.approvedSkillsRoot));
            this.methods = Map.of("decline-selection", snapshot("decline-selection"),
                    "dimension-change", snapshot("dimension-change"));
        } catch (IOException unavailable) {
            throw new IllegalStateException("DEPENDENCY_SKILLS_UNAVAILABLE", unavailable);
        }
    }

    public Prepared prepare(Caller owner, String sessionId, String requestKey,
                            Request request, Instant expiresAt) {
        verifyMethods();
        var identity = JdbcCampaignRunIntakeStore.identity(owner, sessionId, requestKey);
        Request normalized = normalize(request);
        String expiry = expiry(expiresAt);
        FrozenInputSet inputs = inputs(identity.runId(), normalized, expiry);
        PlanSpec plan = plan(identity.planId(), identity.runId(), inputs.inputSetRef(), normalized.metric());
        PlanningAssessment assessment = assessment(identity.planId());
        new PlanValidator(catalog()).validate(plan, inputs, assessment);
        FrozenCampaignRun frozen = FrozenCampaignRun.freeze(plan, inputs, assessment);
        RunDefinition definition = frozen.definition(owner, sessionId);
        // The existing pure template resolvers must accept the actual frozen V2 descriptors.
        require(FrozenScopeCollection.resolve(definition).keySet().equals(Set.of(COLLECT))
                && FrozenDeclineSelection.templates(definition).keySet().equals(Set.of(SELECT))
                && FrozenDimensionChange.resolveTemplates(definition, FrozenDimensionChange.REF_V2)
                        .keySet().equals(Set.of(DIMENSION)));
        return new Prepared(definition, frozen);
    }

    /** Validate frozen inputs and recheck approved local methods; owner/group authority is separate. */
    public Request inspect(FrozenInputSet frozen) {
        verifyMethods();
        return inspectDefinition(frozen);
    }

    /** Reading existing evidence checks its approved frozen method identity, not executable files. */
    Request inspectDefinition(FrozenInputSet frozen) {
        InspectedInputs previous = lastInspected;
        if (previous != null && previous.inputs().equals(frozen)) {
            // Reuse only pure normalization. Expiry remains a live check;
            // the single immutable entry cannot grow with the number of runs or grant authority.
            expiry(previous.expiresAt());
            return previous.request();
        }
        require(frozen != null && frozen.runId() != null
                && frozen.inputContracts().equals(INPUT_CONTRACTS)
                && frozen.inputValues().keySet().equals(INPUT_CONTRACTS.keySet()));
        Map<String, Object> values = frozen.inputValues();
        Map<String, Object> operation = object(values.get("operation"));
        Map<String, Object> collection = object(values.get("collectionDefinition"));
        Map<String, Object> selection = object(values.get("selectionDefinition"));
        Map<String, Object> dimension = object(values.get("dimensionDefinition"));
        require(operation.keySet().equals(Set.of("schemaVersion", "metric"))
                && OPERATION_SCHEMA.equals(operation.get("schemaVersion"))
                && collection.keySet().equals(Set.of("schemaVersion", "gid", "expiresAt")));
        String expiry = text(collection.get("expiresAt"));
        Instant expiresAt;
        try { expiresAt = Instant.parse(expiry); }
        catch (RuntimeException invalid) { throw invalid(); }
        require(expiry.equals(expiresAt.toString()));
        Request request = normalize(new Request(text(collection.get("gid")),
                periodDate(selection.get("baseline"), "startDate"),
                periodDate(selection.get("baseline"), "endDate"),
                periodDate(selection.get("target"), "startDate"),
                periodDate(selection.get("target"), "endDate"), text(operation.get("metric")),
                strings(dimension.get("dimensions")), filters(dimension.get("filters"))));
        FrozenInputSet expected = inputs(frozen.runId(), request, expiry(expiresAt));
        require(frozen.equals(expected));
        lastInspected = new InspectedInputs(frozen, request, expiresAt);
        return request;
    }

    public static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            @Override public String version() { return CATALOG_VERSION; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                if (FrozenScopeCollection.REF.equals(ref))
                    return Optional.of(ScopeCollectionFixedExecutor.capability());
                if (FrozenDeclineSelection.REF_V2.equals(ref))
                    return Optional.of(DeclineSelectionSkill.capability(ref));
                if (FrozenDimensionChange.REF_V2.equals(ref))
                    return Optional.of(DimensionChangeSkill.capability(ref));
                return Optional.empty();
            }
            @Override public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                if (!CRITERION_VERSION.equals(version)) return Optional.empty();
                if (SELECT_DELIVERY.equals(ref)) return Optional.of(new Criterion(ref, version,
                        PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(SELECTED, EVIDENCE)));
                if (DIMENSION_DELIVERY.equals(ref)) return Optional.of(new Criterion(ref, version,
                        PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(CHANGES)));
                return Optional.empty();
            }
        };
    }

    public static ArtifactContractRegistry contracts() {
        var registered = new ArrayList<ArtifactContractRegistry.Contract>();
        registered.add(ScopeCollectionFixedExecutor.artifactContract());
        registered.addAll(DeclineSelectionSkill.artifactContracts().stream()
                .filter(contract -> !"ScopeArtifact".equals(contract.type().name())).toList());
        registered.addAll(DimensionChangeSkill.artifactContracts());
        return new ArtifactContractRegistry(registered);
    }

    private FrozenInputSet inputs(String runId, Request request, String expiry) {
        String pair = "period-pair.v1:" + request.baselineStart() + ":" + request.baselineEnd()
                + ":" + request.targetStart() + ":" + request.targetEnd();
        Map<String, Object> baseline = period("baseline", request.baselineStart(), request.baselineEnd());
        Map<String, Object> target = period("target", request.targetStart(), request.targetEnd());
        Map<String, Object> selection = Map.of("schemaVersion", FrozenDeclineSelection.SCHEMA_V2,
                "periodsRef", pair, "gid", request.gid(), "baseline", baseline, "target", target,
                "skillPin", methods.get("decline-selection").pin());
        Map<String, Object> dimension = Map.of("schemaVersion", FrozenDimensionChange.SCHEMA_V2,
                "periodsRef", pair, "gid", request.gid(), "baseline", baseline, "target", target,
                "dimensions", request.dimensions(), "filters", request.filters(), "skillPin", methods.get("dimension-change").pin());
        Map<String, Object> values = Map.of(
                "operation", Map.of("schemaVersion", OPERATION_SCHEMA, "metric", request.metric()),
                "collectionDefinition", Map.of("schemaVersion", FrozenScopeCollection.SCHEMA,
                        "gid", request.gid(), "expiresAt", expiry),
                "periods", pair, "selectionDefinition", selection, "dimensionDefinition", dimension);
        String inputSetRef = "inputs-" + CampaignRunStore.sha256(FrozenCampaignRun.encode(
                List.of("campaign-dependency-input-set/v1", runId, INPUT_CONTRACTS, values)));
        return new FrozenInputSet(inputSetRef, runId, INPUT_CONTRACTS, values);
    }

    private void verifyMethods() {
        try {
            for (var entry : methods.entrySet())
                require(Arrays.equals(entry.getValue().source(), Files.readAllBytes(document(entry.getKey()))));
        } catch (IOException unavailable) {
            throw new IllegalStateException("DEPENDENCY_SKILLS_UNAVAILABLE", unavailable);
        }
    }

    private Path document(String name) throws IOException {
        Path directory = approvedSkillsRoot.resolve(name).resolve("2").normalize();
        require(directory.startsWith(approvedSkillsRoot) && directory.toRealPath().equals(directory)
                && Files.isDirectory(directory));
        Path document = directory.resolve("SKILL.md");
        require(document.toRealPath().equals(document) && Files.isRegularFile(document));
        return document;
    }

    private MethodSnapshot snapshot(String name) throws IOException {
        byte[] source = Files.readAllBytes(document(name));
        // The native scanner only accepts paths. Parse a private copy of these exact bytes so a
        // concurrent deployment cannot bind one read's digest to another read's cached content.
        // Only these two approved documents are retained; no run data or authorization is cached.
        Path scratch = Files.createTempDirectory("campaign-method-");
        Path directory = scratch.resolve(name);
        Path copy = directory.resolve("SKILL.md");
        try {
            Files.createDirectory(directory);
            Files.write(copy, source);
            SkillMetadata metadata = new SkillScanner().loadSkill(directory, "approved-run-method");
            require(metadata != null && name.equals(metadata.getName()) && metadata.getFullContent() != null);
            require(Arrays.equals(source, Files.readAllBytes(document(name))));
            return new MethodSnapshot(source, Map.of("name", name, "version", "2",
                    "relativeDirectory", name + "/2", "sha256", RunPinnedSkills.contentDigest(metadata)));
        } finally {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(directory);
            Files.deleteIfExists(scratch);
        }
    }


    private static PlanSpec plan(String planId, String runId, String inputSetRef, String metric) {
        PlanSpec.Step collect = new PlanSpec.Step(COLLECT, List.of(SELECT_GOAL, DIMENSION_GOAL),
                PlanSpec.ExecutionMode.FIXED, FrozenScopeCollection.REF, null, List.of(),
                Map.of("definition", PlanBinding.input("collectionDefinition")), Map.of(),
                FrozenScopeCollection.OUTPUT_CONTRACT);
        PlanSpec.Step select = new PlanSpec.Step(SELECT, List.of(SELECT_GOAL), PlanSpec.ExecutionMode.FIXED,
                FrozenDeclineSelection.REF_V2, null, List.of(COLLECT), Map.of(
                        "scopeArtifact", PlanBinding.output(COLLECT, "scopeArtifact"),
                        "periods", PlanBinding.input("periods"),
                        "definition", PlanBinding.input("selectionDefinition")),
                Map.of("metric", metric), FrozenDeclineSelection.OUTPUT_CONTRACT);
        PlanSpec.Step dimension = new PlanSpec.Step(DIMENSION, List.of(DIMENSION_GOAL),
                PlanSpec.ExecutionMode.FIXED, FrozenDimensionChange.REF_V2, null, List.of(SELECT), Map.of(
                        "periods", PlanBinding.input("periods"),
                        "definition", PlanBinding.input("dimensionDefinition"),
                        "selectedEntities", PlanBinding.output(SELECT, "selectedEntities"),
                        "selectionEvidence", PlanBinding.output(SELECT, "selectionEvidence")),
                Map.of(), FrozenDimensionChange.OUTPUT_CONTRACT);
        return new PlanSpec(PlanSpec.SCHEMA_VERSION, planId, 1, runId, inputSetRef, List.of(
                new PlanSpec.Goal(SELECT_GOAL, "Which group links declined between the two periods?", true,
                        "Deliver selected entities and observed comparison evidence for the whole collected scope"),
                new PlanSpec.Goal(DIMENSION_GOAL, "How did province and device observations change for those links?", true,
                        "Deliver the selected cohort's observed dimension changes with coverage and quality")),
                List.of(collect, select, dimension));
    }

    private static PlanningAssessment assessment(String planId) {
        return new PlanningAssessment(planId, 1, CATALOG_VERSION, List.of(
                new PlanningAssessment.Requirement(SELECT_DELIVERY, SELECT_GOAL,
                        PlanningAssessment.RequirementKind.DELIVERY, true,
                        SELECT_DELIVERY, CRITERION_VERSION, Map.of()),
                new PlanningAssessment.Requirement(DIMENSION_DELIVERY, DIMENSION_GOAL,
                        PlanningAssessment.RequirementKind.DELIVERY, true,
                        DIMENSION_DELIVERY, CRITERION_VERSION, Map.of())), List.of(
                new PlanningAssessment.CoverageBinding(SELECT_DELIVERY, List.of(
                        new PlanningAssessment.EvidenceOutput(SELECT, "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput(SELECT, "selectionEvidence"))),
                new PlanningAssessment.CoverageBinding(DIMENSION_DELIVERY, List.of(
                        new PlanningAssessment.EvidenceOutput(DIMENSION, "dimensionChanges")))), List.of());
    }

    private Request normalize(Request request) {
        require(request != null && request.gid() != null
                && request.gid().matches("[A-Za-z0-9_-]{1,64}")
                && request.metric() != null && METRICS.contains(request.metric()));
        CampaignParentCoverage.Period baseline, target;
        try {
            baseline = new CampaignParentCoverage.Period("baseline", request.baselineStart(),
                    request.baselineEnd(), ZONE);
            target = new CampaignParentCoverage.Period("target", request.targetStart(), request.targetEnd(), ZONE);
            require(LocalDate.parse(baseline.endDate()).isBefore(LocalDate.parse(target.startDate())));
        } catch (DateTimeException malformed) {
            throw invalid();
        }
        List<String> dimensions = DimensionQuery.dimensions(request.dimensions());
        require(V2_DIMENSIONS.equals(dimensions));
        List<Map<String, Object>> filters = filters(request.filters());
        return new Request(request.gid(), baseline.startDate(), baseline.endDate(),
                target.startDate(), target.endDate(), request.metric(), dimensions, filters);
    }

    private String expiry(Instant value) {
        require(value != null && value.isAfter(clock.instant()) && value.getNano() % 1_000_000 == 0);
        try { require(Instant.ofEpochMilli(value.toEpochMilli()).equals(value)); }
        catch (ArithmeticException invalid) { throw invalid(); }
        return value.toString();
    }

    private static Map<String, Object> period(String ref, String start, String end) {
        return Map.of("periodsRef", ref, "startDate", start, "endDate", end, "timeZone", ZONE);
    }

    private static String periodDate(Object raw, String field) { return text(object(raw).get(field)); }

    private static List<String> strings(Object raw) {
        require(raw instanceof List<?>);
        List<String> result = new ArrayList<>();
        for (Object value : (List<?>) raw) result.add(text(value));
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> filters(Object raw) {
        require(raw instanceof List<?>);
        for (Object item : (List<?>) raw) {
            Map<String, Object> filter = object(item);
            require(filter.keySet().containsAll(Set.of("dimension", "operator"))
                    && Set.of("dimension", "operator", "values").containsAll(filter.keySet()));
        }
        return DimensionQuery.filters(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw) {
        require(raw instanceof Map<?, ?> && ((Map<?, ?>) raw).keySet().stream().allMatch(String.class::isInstance));
        return (Map<String, Object>) raw;
    }

    private static String text(Object raw) {
        require(raw instanceof String);
        return (String) raw;
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("DEPENDENCY_ANALYSIS_INPUT_INVALID");
    }
}
