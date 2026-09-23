package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A model may propose execution paths, but cannot rewrite the trusted questions or requirements. */
public record PlanningProposal(String schemaVersion, List<PlanSpec.Step> steps,
                               List<PlanningAssessment.CoverageBinding> coverageBindings,
                               List<PlanningAssessment.Gap> gaps) {
    public static final String SCHEMA_VERSION = "campaign-planning-proposal/v1";
    private static final String INVALID = "PLANNING_PROPOSAL_INVALID";
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL).build();
    static {
        for (var shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean))
            JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
    }

    /** Byte/depth bounds are configurable server policy, not limits on business goals or steps. */
    public record Limits(int requestBytes, int proposalBytes, int maxDepth) {
        public Limits {
            require(requestBytes > 0 && proposalBytes > 0 && maxDepth > 0
                    && maxDepth <= ImmutablePlanValues.MAX_JSON_NESTING, "PLANNING_LIMITS_INVALID");
        }
        public static Limits defaults() { return new Limits(4 * 1024 * 1024, 1024 * 1024, 128); }
    }

    /** Trusted server input. Metadata is visibility/type information, never a current ACL proof. */
    public record Request(String question, List<PlanSpec.Goal> goals,
                          List<PlanningAssessment.Requirement> requirements, FrozenInputSet inputs,
                          Map<String, ArtifactMetadata> allowedArtifacts, Menu menu) {
        public Request {
            text(question); Objects.requireNonNull(inputs); Objects.requireNonNull(menu);
            goals = List.copyOf(Objects.requireNonNull(goals));
            requirements = List.copyOf(Objects.requireNonNull(requirements));
            allowedArtifacts = Map.copyOf(Objects.requireNonNull(allowedArtifacts));
            require(!goals.isEmpty(), "PLANNING_GOALS_REQUIRED");
            Map<String, PlanSpec.Goal> namedGoals = new HashMap<>();
            for (var goal : goals) {
                ref(goal.goalId()); text(goal.question()); text(goal.acceptance());
                require(namedGoals.putIfAbsent(goal.goalId(), goal) == null, INVALID);
            }
            Set<String> names = new HashSet<>();
            for (var requirement : requirements) {
                ref(requirement.requirementId()); ref(requirement.goalId());
                require(namedGoals.containsKey(requirement.goalId()) && names.add(requirement.requirementId())
                        && requirement.kind() != null, INVALID);
                text(requirement.criterionRef()); text(requirement.criterionVersion());
            }
            Map<String, ArtifactMetadata> artifacts = new HashMap<>();
            allowedArtifacts.forEach((name, metadata) -> {
                ref(name); require(metadata != null && metadata.ref() != null, INVALID);
                ref(metadata.ref().artifactId());
                var previous = artifacts.putIfAbsent(metadata.ref().artifactId(), metadata);
                require(previous == null || previous.equals(metadata), "PLANNING_ARTIFACT_CHANGED");
            });
        }
    }

    public record CapabilityOffer(PlanSpec.ExecutorRef executor, String description,
                                  Map<String, Object> parameterSchema) {
        public CapabilityOffer {
            Objects.requireNonNull(executor); text(description);
            parameterSchema = ImmutablePlanValues.json(Objects.requireNonNull(parameterSchema));
        }
    }

    public record PolicyOffer(String policyRef, String policyVersion, String description,
                              Map<String, Object> parameterSchema) {
        public PolicyOffer {
            text(policyRef); text(policyVersion); text(description);
            parameterSchema = ImmutablePlanValues.json(Objects.requireNonNull(parameterSchema));
        }
    }

    /** These DTOs contain public contracts, never Predicate instances or executable expressions. */
    public record CapabilityItem(PlanSpec.ExecutorRef executor, String description,
                                 Map<String, CapabilityCatalog.Port> inputs, String outputContractRef,
                                 Map<String, CapabilityCatalog.Port> outputs, Map<String, Object> parameterSchema) {
        public CapabilityItem {
            Objects.requireNonNull(executor); text(description); text(outputContractRef);
            inputs = Map.copyOf(inputs); outputs = Map.copyOf(outputs);
            parameterSchema = ImmutablePlanValues.json(Objects.requireNonNull(parameterSchema));
        }
    }

    public record PolicyItem(String policyRef, String policyVersion, String description,
                             Map<String, CapabilityCatalog.Port> inputs, String outputContractRef,
                             Map<String, CapabilityCatalog.Port> outputs, Map<String, Object> parameterSchema,
                             Set<PlanSpec.ExecutorRef> allowedExecutors,
                             List<PlanSpec.CriterionUse> completionCriteria, String terminationPolicyRef) {
        public PolicyItem {
            text(policyRef); text(policyVersion); text(description); text(outputContractRef); text(terminationPolicyRef);
            inputs = Map.copyOf(inputs); outputs = Map.copyOf(outputs);
            parameterSchema = ImmutablePlanValues.json(Objects.requireNonNull(parameterSchema));
            allowedExecutors = Collections.unmodifiableSet(new LinkedHashSet<>(allowedExecutors.stream()
                    .sorted(java.util.Comparator.comparing(PlanningProposal::write)).toList()));
            completionCriteria = List.copyOf(completionCriteria);
        }
    }

    public record Menu(String catalogVersion, List<CapabilityItem> capabilities, List<PolicyItem> policies) {
        public Menu {
            text(catalogVersion); capabilities = List.copyOf(capabilities); policies = List.copyOf(policies);
            Set<PlanSpec.ExecutorRef> executors = new HashSet<>();
            for (var capability : capabilities) require(executors.add(capability.executor()), "PLANNING_MENU_DUPLICATE");
            Set<List<String>> policyKeys = new HashSet<>();
            for (var policy : policies) require(policyKeys.add(List.of(policy.policyRef(), policy.policyVersion()))
                    && executors.containsAll(policy.allowedExecutors()), "PLANNING_MENU_CAPABILITY_MISSING");
        }
        public String configurationId() { return CampaignRunStore.sha256(write(this)); }
    }

    public PlanningProposal {
        require(SCHEMA_VERSION.equals(schemaVersion), INVALID);
        steps = List.copyOf(Objects.requireNonNull(steps));
        coverageBindings = List.copyOf(Objects.requireNonNull(coverageBindings));
        gaps = List.copyOf(Objects.requireNonNull(gaps));
    }

    /** Build only from server-listed references; the catalog remains the validation authority. */
    public static Menu menu(CapabilityCatalog catalog, List<CapabilityOffer> capabilities, List<PolicyOffer> policies) {
        Objects.requireNonNull(catalog);
        List<CapabilityItem> capabilityItems = new ArrayList<>();
        for (var offer : capabilities) {
            var registered = catalog.capability(offer.executor()).orElseThrow(() -> invalid("PLANNING_CAPABILITY_UNREGISTERED"));
            require(offer.executor().equals(registered.executor()) && !registered.startsExploration(), "PLANNING_CAPABILITY_INVALID");
            var signature = registered.signature(); parameterSchema(offer.parameterSchema(), signature.parameters());
            capabilityItems.add(new CapabilityItem(offer.executor(), offer.description(), signature.inputs(),
                    signature.outputContractRef(), signature.outputs(), offer.parameterSchema()));
        }
        List<PolicyItem> policyItems = new ArrayList<>();
        for (var offer : policies) {
            var registered = catalog.policy(offer.policyRef(), offer.policyVersion())
                    .orElseThrow(() -> invalid("PLANNING_POLICY_UNREGISTERED"));
            require(offer.policyRef().equals(registered.policyRef()) && offer.policyVersion().equals(registered.policyVersion()), INVALID);
            var signature = registered.signature(); parameterSchema(offer.parameterSchema(), signature.parameters());
            policyItems.add(new PolicyItem(offer.policyRef(), offer.policyVersion(), offer.description(), signature.inputs(),
                    signature.outputContractRef(), signature.outputs(), offer.parameterSchema(), registered.allowedExecutors(),
                    registered.completionCriteria(), registered.terminationPolicyRef()));
        }
        capabilityItems.sort(java.util.Comparator.comparing(item -> write(item.executor())));
        policyItems.sort(java.util.Comparator.comparing(PolicyItem::policyRef).thenComparing(PolicyItem::policyVersion));
        return new Menu(catalog.version(), capabilityItems, policyItems);
    }

    public static PlanningProposal parse(String encoded) { return parse(encoded, Limits.defaults()); }
    public static PlanningProposal parse(String encoded, Limits limits) {
        JsonNode root = read(proposalJson(encoded, limits.proposalBytes()), limits.proposalBytes(), limits);
        fields(root, Set.of("schemaVersion", "steps", "coverageBindings", "gaps"), Set.of());
        require(SCHEMA_VERSION.equals(string(root.get("schemaVersion"))), INVALID);
        array(root.get("steps")).forEach(PlanningProposal::stepShape);
        for (var binding : array(root.get("coverageBindings"))) {
            fields(binding, Set.of("requirementId", "evidenceOutputs"), Set.of()); string(binding.get("requirementId"));
            for (var output : array(binding.get("evidenceOutputs"))) {
                fields(output, Set.of("stepId", "output"), Set.of()); string(output.get("stepId")); string(output.get("output"));
            }
        }
        for (var gap : array(root.get("gaps"))) {
            fields(gap, Set.of("requirementId", "reason", "explanation"), Set.of());
            string(gap.get("requirementId")); string(gap.get("reason")); string(gap.get("explanation"));
        }
        return convert(root, PlanningProposal.class);
    }

    /** Presentation-only normalization after the original provider response has been persisted. */
    private static String proposalJson(String encoded, int maximum) {
        // Bound the original response, including its wrapper, before making another String.
        utf8(encoded, maximum);
        // The unchanged strict JSON reader rejects prose, multiple objects/fences, duplicate keys,
        // and unknown fields. Never extract a plausible object from an otherwise invalid answer.
        try { return StrictStructuredJson.unwrapSingleFence(encoded); }
        catch (IllegalArgumentException invalid) { throw invalid(INVALID); }
    }

    public String encode() { return encode(Limits.defaults()); }
    public String encode(Limits limits) { return boundedWrite(this, limits.proposalBytes(), limits); }
    public static String encodeRequest(Request request) { return encodeRequest(request, Limits.defaults()); }
    public static String encodeRequest(Request request, Limits limits) { return boundedWrite(request, limits.requestBytes(), limits); }
    public static Request decodeRequest(String encoded) { return decodeRequest(encoded, Limits.defaults()); }
    public static Request decodeRequest(String encoded, Limits limits) {
        JsonNode root = read(encoded, limits.requestBytes(), limits);
        fields(root, Set.of("question", "goals", "requirements", "inputs", "allowedArtifacts", "menu"), Set.of());
        string(root.get("question"));
        for (var goal : array(root.get("goals"))) {
            fields(goal, Set.of("goalId", "question", "required", "acceptance"), Set.of());
            string(goal.get("goalId")); string(goal.get("question")); string(goal.get("acceptance"));
            require(goal.get("required").isBoolean(), INVALID);
        }
        for (var requirement : array(root.get("requirements"))) {
            fields(requirement, Set.of("requirementId", "goalId", "kind", "required", "criterionRef", "criterionVersion", "parameters"), Set.of());
            require(requirement.get("required").isBoolean() && requirement.get("parameters").isObject(), INVALID);
            for (String field : List.of("requirementId", "goalId", "kind", "criterionRef", "criterionVersion")) string(requirement.get(field));
        }
        return convert(root, Request.class);
    }

    /** No Artifact binding can be accepted without the server's real artifact type registry. */
    public FrozenCampaignRun materialize(Request request, String planId, int revision, CapabilityCatalog catalog) {
        return materialize(request, planId, revision, catalog, null, Limits.defaults());
    }
    public FrozenCampaignRun materialize(Request request, String planId, int revision, CapabilityCatalog catalog,
                                         ArtifactContractRegistry contracts) {
        return materialize(request, planId, revision, catalog, contracts, Limits.defaults());
    }
    public FrozenCampaignRun materialize(Request request, String planId, int revision, CapabilityCatalog catalog,
                                         ArtifactContractRegistry contracts, Limits limits) {
        Objects.requireNonNull(request); Objects.requireNonNull(catalog); ref(planId); require(revision > 0, INVALID);
        // Reapply the wire checks to programmatic candidates as well as externally parsed candidates.
        PlanningProposal checked = parse(encode(limits), limits);
        encodeRequest(request, limits);
        Menu expected = menu(catalog,
                request.menu().capabilities().stream().map(item -> new CapabilityOffer(item.executor(), item.description(), item.parameterSchema())).toList(),
                request.menu().policies().stream().map(item -> new PolicyOffer(item.policyRef(), item.policyVersion(), item.description(), item.parameterSchema())).toList());
        require(expected.equals(request.menu()), "PLANNING_MENU_CHANGED");
        Set<PlanSpec.ExecutorRef> allowed = new HashSet<>();
        expected.capabilities().forEach(item -> allowed.add(item.executor()));
        Set<List<String>> policies = new HashSet<>();
        expected.policies().forEach(item -> policies.add(List.of(item.policyRef(), item.policyVersion())));
        for (var step : checked.steps()) {
            if (step.executionMode() == PlanSpec.ExecutionMode.FIXED)
                require(allowed.contains(step.executor()), "PLANNING_CAPABILITY_NOT_OFFERED");
            else {
                var policy = step.explorationPolicy();
                require(policies.contains(List.of(policy.policyRef(), policy.policyVersion()))
                        && allowed.containsAll(policy.allowedExecutors()), "PLANNING_POLICY_NOT_OFFERED");
            }
        }
        // This phase has no trusted negative capability proof. A failed search is not UNSUPPORTED.
        for (var gap : checked.gaps()) require(gap.reason() != PlanningAssessment.GapReason.UNSUPPORTED,
                "PLANNING_UNSUPPORTED_UNPROVEN");
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, planId, revision, request.inputs().runId(),
                request.inputs().inputSetRef(), request.goals(), checked.steps());
        PlanningAssessment assessment = new PlanningAssessment(planId, revision, catalog.version(), request.requirements(),
                checked.coverageBindings(), checked.gaps());
        Map<String, ArtifactMetadata> visible = new HashMap<>();
        request.allowedArtifacts().values().forEach(metadata -> visible.put(metadata.ref().artifactId(), metadata));
        new PlanValidator(catalog, id -> {
            var metadata = visible.get(id);
            return metadata == null || contracts == null ? Optional.empty() : Optional.of(contracts.typeOf(metadata));
        }).validate(plan, request.inputs(), assessment);
        return FrozenCampaignRun.freeze(plan, request.inputs(), assessment);
    }

    /** JSON Schema guides native output; only the backend catalog and materialize() authorize a plan. */
    public static Map<String, Object> schema() {
        var text = Map.<String, Object>of("type", "string", "minLength", 1);
        var strings = arraySchema(text);
        var parameters = Map.<String, Object>of("type", "object");
        var executor = objectSchema(Map.of("kind", Map.of("enum", List.of("TOOL", "SKILL")), "name", text, "version", text));
        var binding = Map.<String, Object>of("oneOf", List.of(
                objectSchema(Map.of("source", Map.of("const", "INPUT"), "input", text)),
                objectSchema(Map.of("source", Map.of("const", "STEP_OUTPUT"), "stepId", text, "output", text)),
                objectSchema(Map.of("source", Map.of("const", "ARTIFACT"), "artifactId", text))));
        var policy = objectSchema(Map.of("policyRef", text, "policyVersion", text, "allowedExecutors", arraySchema(executor),
                "scopeRef", text, "periodsRef", text, "completionCriteria", arraySchema(objectSchema(Map.of("criterionRef", text, "parameters", parameters))),
                "terminationPolicyRef", text));
        Map<String, Object> common = new LinkedHashMap<>(Map.of("stepId", text, "goalIds", strings,
                "dependsOn", strings, "inputBindings", Map.of("type", "object", "additionalProperties", binding),
                "parameters", parameters, "outputContractRef", text));
        Map<String, Object> fixed = new LinkedHashMap<>(common); fixed.put("executionMode", Map.of("const", "FIXED")); fixed.put("executor", executor);
        Map<String, Object> react = new LinkedHashMap<>(common); react.put("executionMode", Map.of("const", "REACT")); react.put("explorationPolicy", policy);
        var coverage = objectSchema(Map.of("requirementId", text, "evidenceOutputs", arraySchema(objectSchema(Map.of("stepId", text, "output", text)))));
        var gap = objectSchema(Map.of("requirementId", text, "reason", Map.of("enum", List.of("NEEDS_INPUT", "PLANNING_UNRESOLVED", "EVIDENCE_UNAVAILABLE")), "explanation", text));
        return ImmutablePlanValues.json(objectSchema(Map.of("schemaVersion", Map.of("const", SCHEMA_VERSION),
                "steps", arraySchema(Map.of("oneOf", List.of(objectSchema(fixed), objectSchema(react)))),
                "coverageBindings", arraySchema(coverage), "gaps", arraySchema(gap))));
    }

    private static void stepShape(JsonNode step) {
        fields(step, Set.of("stepId", "goalIds", "executionMode", "dependsOn", "inputBindings", "parameters", "outputContractRef"),
                Set.of("executor", "explorationPolicy"));
        ref(string(step.get("stepId"))); string(step.get("outputContractRef"));
        array(step.get("goalIds")).forEach(value -> ref(string(value)));
        array(step.get("dependsOn")).forEach(value -> ref(string(value)));
        require(step.get("inputBindings").isObject() && step.get("parameters").isObject(), INVALID);
        step.get("inputBindings").elements().forEachRemaining(PlanningProposal::bindingShape);
        String mode = string(step.get("executionMode"));
        if ("FIXED".equals(mode)) {
            require(absent(step, "explorationPolicy"), INVALID); executorShape(step.get("executor"));
        } else {
            require("REACT".equals(mode) && absent(step, "executor"), INVALID);
            var policy = step.get("explorationPolicy");
            fields(policy, Set.of("policyRef", "policyVersion", "allowedExecutors", "scopeRef", "periodsRef", "completionCriteria", "terminationPolicyRef"), Set.of());
            for (String name : List.of("policyRef", "policyVersion", "scopeRef", "periodsRef", "terminationPolicyRef")) string(policy.get(name));
            array(policy.get("allowedExecutors")).forEach(PlanningProposal::executorShape);
            for (var criterion : array(policy.get("completionCriteria"))) {
                fields(criterion, Set.of("criterionRef", "parameters"), Set.of()); string(criterion.get("criterionRef"));
                require(criterion.get("parameters").isObject(), INVALID);
            }
        }
    }

    private static void executorShape(JsonNode executor) {
        fields(executor, Set.of("kind", "name", "version"), Set.of());
        require(Set.of("TOOL", "SKILL").contains(string(executor.get("kind"))), INVALID);
        string(executor.get("name")); string(executor.get("version"));
    }
    private static void bindingShape(JsonNode binding) {
        require(binding != null && binding.isObject(), INVALID);
        switch (string(binding.get("source"))) {
            case "INPUT" -> { fields(binding, Set.of("source", "input"), Set.of()); string(binding.get("input")); }
            case "STEP_OUTPUT" -> { fields(binding, Set.of("source", "stepId", "output"), Set.of()); ref(string(binding.get("stepId"))); string(binding.get("output")); }
            case "ARTIFACT" -> { fields(binding, Set.of("source", "artifactId"), Set.of()); ref(string(binding.get("artifactId"))); }
            default -> throw invalid(INVALID);
        }
    }
    private static void parameterSchema(Map<String, Object> schema, CapabilityCatalog.Parameters parameters) {
        require(parameters != null && "object".equals(schema.get("type")) && Boolean.FALSE.equals(schema.get("additionalProperties"))
                && schema.get("properties") instanceof Map<?, ?> && schema.get("required") instanceof List<?>, "PLANNING_PARAMETER_SCHEMA_INVALID");
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties"); List<?> required = (List<?>) schema.get("required");
        require(properties.keySet().equals(parameters.properties().keySet()) && new HashSet<>(required).equals(parameters.required())
                && new HashSet<>(required).size() == required.size(), "PLANNING_PARAMETER_SCHEMA_CHANGED");
        JsonNode tree = JSON.valueToTree(schema); schemaData(tree);
    }
    private static void schemaData(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                require(!Set.of("$ref", "$dynamicRef", "$recursiveRef").contains(field.getKey()), "PLANNING_REMOTE_SCHEMA_FORBIDDEN");
                schemaData(field.getValue());
            }
        } else if (node.isArray()) node.forEach(PlanningProposal::schemaData);
    }
    private static boolean absent(JsonNode node, String name) { return !node.has(name) || node.get(name).isNull(); }
    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        require(node != null && node.isObject(), INVALID);
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>(required); allowed.addAll(optional);
        require(actual.containsAll(required) && allowed.containsAll(actual), INVALID);
        for (String field : required) require(!node.get(field).isNull(), INVALID);
    }
    private static List<JsonNode> array(JsonNode node) {
        require(node != null && node.isArray(), INVALID);
        List<JsonNode> values = new ArrayList<>(); node.forEach(values::add); return values;
    }
    private static String string(JsonNode node) { require(node != null && node.isTextual(), INVALID); String text = node.textValue(); text(text); return text; }
    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "additionalProperties", false, "properties", properties,
                "required", properties.keySet().stream().sorted().toList());
    }
    private static Map<String, Object> arraySchema(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
    private static JsonNode read(String value, int maximum, Limits limits) {
        utf8(value, maximum);
        try {
            JsonMapper reader = JSON.copy();
            reader.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(limits.maxDepth()).build());
            JsonNode root = reader.readTree(value); require(root != null, INVALID); jsonValues(root, 0, limits.maxDepth()); return root;
        } catch (JsonProcessingException invalid) { throw invalid(INVALID); }
    }
    private static <T> T convert(JsonNode value, Class<T> type) {
        try { return JSON.treeToValue(value, type); }
        catch (JsonProcessingException invalid) { throw invalid(INVALID); }
    }
    private static String boundedWrite(Object value, int maximum, Limits limits) {
        // Count before materializing a JSON tree/string so an oversized trusted payload cannot make
        // multiple unbounded copies merely to discover that it exceeds the configured byte limit.
        final long[] size = {0};
        try {
            JSON.writeValue(new java.io.OutputStream() {
                private void count(int bytes) throws java.io.IOException {
                    size[0] += bytes;
                    if (size[0] > maximum) throw new java.io.IOException("PLANNING_JSON_TOO_LARGE");
                }
                @Override public void write(int value) throws java.io.IOException { count(1); }
                @Override public void write(byte[] values, int offset, int length) throws java.io.IOException { count(length); }
            }, value);
        } catch (java.io.IOException failure) {
            throw invalid(size[0] > maximum ? "PLANNING_JSON_TOO_LARGE" : INVALID);
        }
        JsonNode tree = JSON.valueToTree(value); jsonValues(tree, 0, limits.maxDepth());
        String encoded = write(value); utf8(encoded, maximum); return encoded;
    }
    private static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw invalid(INVALID); }
    }
    private static void jsonValues(JsonNode node, int depth, int maximum) {
        require(node != null && depth <= maximum, "PLANNING_JSON_DEPTH_EXCEEDED");
        if (node.isTextual()) utf8(node.textValue(), Integer.MAX_VALUE);
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) { var field = fields.next(); utf8(field.getKey(), Integer.MAX_VALUE); jsonValues(field.getValue(), depth + 1, maximum); }
        } else if (node.isArray()) for (var child : node) jsonValues(child, depth + 1, maximum);
    }
    private static void utf8(String value, int maximum) {
        require(value != null, INVALID); long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x80) bytes++;
            else if (character < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(character) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) { bytes += 4; index++; }
            else { require(!Character.isSurrogate(character), INVALID); bytes += 3; }
            require(bytes <= maximum, "PLANNING_JSON_TOO_LARGE");
        }
    }
    private static void ref(String value) { require(value != null && value.length() <= 96 && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"), INVALID); }
    private static void text(String value) { require(value != null && !value.isBlank(), INVALID); utf8(value, Integer.MAX_VALUE); }
    private static void require(boolean condition, String code) { if (!condition) throw invalid(code); }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
