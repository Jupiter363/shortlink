package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A closed model-proposed terminal statement, not a tool call, authorization or goal assessment.
 * Text fields are public decision/request summaries only. They are never parsed as plans, rules,
 * expressions or executable instructions; this DTO cannot establish evidence truth or completion.
 */
public record ExplorationCandidate(String schemaVersion, Kind kind, String decisionSummary,
                                   List<String> evidenceArtifactIds, Map<String, PlanBinding> outputBindings,
                                   List<String> missingInputs, String replanRequest, String reasonCode) {
    public static final String SCHEMA_VERSION = "exploration-candidate/v1";
    public static final int MAX_JSON_BYTES = 32_768;
    public static final int MAX_SUMMARY_CHARACTERS = 2_048;
    public static final int MAX_SUMMARY_BYTES = 8_192;
    public static final int MAX_EVIDENCE = 64;
    public static final int MAX_OUTPUTS = 16;
    public static final int MAX_MISSING_INPUTS = 16;
    public static final int MAX_REPLAN_CHARACTERS = 1_024;
    public static final int MAX_REPLAN_BYTES = 4_096;
    private static final String INVALID = "EXPLORATION_CANDIDATE_INVALID";
    private static final String TOO_LARGE = "EXPLORATION_CANDIDATE_TOO_LARGE";
    private static final String ARTIFACT_PATTERN = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}";
    private static final String NAME_PATTERN = "[A-Za-z][A-Za-z0-9_.-]{0,95}";
    private static final String REASON_PATTERN = "[A-Z][A-Z0-9_]{0,63}";
    private static final Set<String> COMMON = Set.of("schemaVersion", "kind", "decisionSummary", "evidenceArtifactIds");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public enum Kind { COMPLETE, NEEDS_INPUT, REQUEST_REPLAN, NO_PROGRESS }

    public ExplorationCandidate {
        require(SCHEMA_VERSION.equals(schemaVersion) && kind != null);
        boundedText(decisionSummary, MAX_SUMMARY_CHARACTERS, MAX_SUMMARY_BYTES);
        evidenceArtifactIds = names(evidenceArtifactIds, 0, MAX_EVIDENCE, ARTIFACT_PATTERN);
        switch (kind) {
            case COMPLETE -> {
                require(missingInputs == null && replanRequest == null && reasonCode == null);
                require(outputBindings != null && !outputBindings.isEmpty());
                if (outputBindings.size() > MAX_OUTPUTS) throw tooLarge();
                Map<String, PlanBinding> copied = new TreeMap<>();
                for (var entry : outputBindings.entrySet()) {
                    require(entry.getKey() != null && entry.getKey().matches(NAME_PATTERN));
                    PlanBinding binding = entry.getValue();
                    require(binding != null && binding.source() == PlanBinding.Source.ARTIFACT
                            && binding.input() == null && binding.stepId() == null && binding.output() == null
                            && binding.artifactId() != null && binding.artifactId().matches(ARTIFACT_PATTERN));
                    copied.put(entry.getKey(), binding);
                }
                outputBindings = Collections.unmodifiableMap(copied);
            }
            case NEEDS_INPUT -> {
                require(outputBindings == null && replanRequest == null && reasonCode == null);
                missingInputs = names(missingInputs, 1, MAX_MISSING_INPUTS, NAME_PATTERN);
            }
            case REQUEST_REPLAN -> {
                require(outputBindings == null && missingInputs == null && reasonCode == null);
                boundedText(replanRequest, MAX_REPLAN_CHARACTERS, MAX_REPLAN_BYTES);
            }
            case NO_PROGRESS -> {
                require(outputBindings == null && missingInputs == null && replanRequest == null
                        && reasonCode != null && reasonCode.matches(REASON_PATTERN));
            }
        }
    }

    /** Rejects absent required fields, irrelevant null fields, coercion, duplicate keys and suffixes. */
    public static ExplorationCandidate parse(String encoded) {
        require(encoded != null);
        utf8Bound(encoded, MAX_JSON_BYTES);
        JsonNode root;
        try { root = JSON.readTree(encoded); }
        catch (JsonProcessingException invalid) { throw invalid(); }
        require(root != null && root.isObject());
        Kind kind;
        try { kind = Kind.valueOf(string(root.get("kind"))); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
        Set<String> fields = new HashSet<>(COMMON);
        fields.add(specialField(kind));
        exactFields(root, fields);
        String version = string(root.get("schemaVersion"));
        String summary = string(root.get("decisionSummary"));
        List<String> evidence = strings(root.get("evidenceArtifactIds"), MAX_EVIDENCE);
        Map<String, PlanBinding> outputs = null;
        List<String> missing = null;
        String replan = null, reason = null;
        switch (kind) {
            case COMPLETE -> {
                JsonNode object = root.get("outputBindings");
                require(object != null && object.isObject());
                if (object.size() > MAX_OUTPUTS) throw tooLarge();
                outputs = new TreeMap<>();
                var entries = object.fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    exactFields(entry.getValue(), Set.of("source", "artifactId"));
                    require("ARTIFACT".equals(string(entry.getValue().get("source"))));
                    outputs.put(entry.getKey(), PlanBinding.artifact(string(entry.getValue().get("artifactId"))));
                }
            }
            case NEEDS_INPUT -> missing = strings(root.get("missingInputs"), MAX_MISSING_INPUTS);
            case REQUEST_REPLAN -> replan = string(root.get("replanRequest"));
            case NO_PROGRESS -> reason = string(root.get("reasonCode"));
        }
        return new ExplorationCandidate(version, kind, summary, evidence, outputs, missing, replan, reason);
    }

    /** Canonical object-key ordering; arrays retain their declared order. Irrelevant fields are absent. */
    public String encode() {
        Map<String, Object> value = new TreeMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("kind", kind.name());
        value.put("decisionSummary", decisionSummary);
        value.put("evidenceArtifactIds", evidenceArtifactIds);
        switch (kind) {
            case COMPLETE -> {
                Map<String, Object> outputs = new TreeMap<>();
                outputBindings.forEach((name, binding) -> outputs.put(name,
                        Map.of("source", "ARTIFACT", "artifactId", binding.artifactId())));
                value.put("outputBindings", outputs);
            }
            case NEEDS_INPUT -> value.put("missingInputs", missingInputs);
            case REQUEST_REPLAN -> value.put("replanRequest", replanRequest);
            case NO_PROGRESS -> value.put("reasonCode", reasonCode);
        }
        try {
            String encoded = JSON.writeValueAsString(value);
            utf8Bound(encoded, MAX_JSON_BYTES);
            return encoded;
        } catch (JsonProcessingException invalid) { throw invalid(); }
    }

    /** Terminal-response JSON Schema, separate from native tool-call definitions. Byte limits are server-enforced. */
    public static Map<String, Object> schema() {
        Map<String, Object> artifactId = Map.of("type", "string", "minLength", 1, "maxLength", 96,
                "pattern", "^" + ARTIFACT_PATTERN + "$", "description", "An opaque visible Artifact ID, never a URL or expression.");
        Map<String, Object> name = Map.of("type", "string", "minLength", 1, "maxLength", 96,
                "pattern", "^" + NAME_PATTERN + "$");
        Map<String, Object> binding = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("source", "artifactId"), "properties", Map.of(
                        "source", Map.of("const", "ARTIFACT"), "artifactId", artifactId));
        List<Object> branches = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("schemaVersion", Map.of("const", SCHEMA_VERSION));
            properties.put("kind", Map.of("const", kind.name()));
            properties.put("decisionSummary", textSchema(MAX_SUMMARY_CHARACTERS, MAX_SUMMARY_BYTES,
                    "A short public decision summary, not private reasoning or chain of thought."));
            properties.put("evidenceArtifactIds", Map.of("type", "array", "maxItems", MAX_EVIDENCE,
                    "uniqueItems", true, "items", artifactId));
            Object special = switch (kind) {
                case COMPLETE -> Map.of("type", "object", "minProperties", 1, "maxProperties", MAX_OUTPUTS,
                        "propertyNames", name, "additionalProperties", binding,
                        "description", "Bind only existing authorized artifacts to declared output ports; completion still requires server assessment.");
                case NEEDS_INPUT -> Map.of("type", "array", "minItems", 1, "maxItems", MAX_MISSING_INPUTS,
                        "uniqueItems", true, "items", name);
                case REQUEST_REPLAN -> textSchema(MAX_REPLAN_CHARACTERS, MAX_REPLAN_BYTES,
                        "A short public request explaining the needed plan change. Do not embed a Plan, expression, executor or executable instructions.");
                case NO_PROGRESS -> Map.of("type", "string", "minLength", 1, "maxLength", 64,
                        "pattern", "^" + REASON_PATTERN + "$");
            };
            properties.put(specialField(kind), special);
            branches.add(Map.of("type", "object", "additionalProperties", false,
                    "required", List.of("schemaVersion", "kind", "decisionSummary", "evidenceArtifactIds", specialField(kind)),
                    "properties", Collections.unmodifiableMap(properties)));
        }
        return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "title", "Exploration terminal candidate",
                "description", "A model proposal only: no tool calls, new criteria, authorization or proof of completion. UTF-8 limits are enforced without truncation.",
                "x-maxUtf8Bytes", MAX_JSON_BYTES, "oneOf", List.copyOf(branches));
    }

    private static Map<String, Object> textSchema(int characters, int bytes, String description) {
        return Map.of("type", "string", "minLength", 1, "maxLength", characters, "pattern", "\\S",
                "x-maxUtf8Bytes", bytes, "description", description);
    }

    private static String specialField(Kind kind) {
        return switch (kind) {
            case COMPLETE -> "outputBindings";
            case NEEDS_INPUT -> "missingInputs";
            case REQUEST_REPLAN -> "replanRequest";
            case NO_PROGRESS -> "reasonCode";
        };
    }

    private static List<String> names(List<String> input, int minimum, int maximum, String pattern) {
        require(input != null && input.size() >= minimum);
        if (input.size() > maximum) throw tooLarge();
        Set<String> seen = new HashSet<>();
        for (String value : input) require(value != null && value.matches(pattern) && seen.add(value));
        return List.copyOf(input);
    }

    private static List<String> strings(JsonNode value, int maximum) {
        require(value != null && value.isArray());
        if (value.size() > maximum) throw tooLarge();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) result.add(string(item));
        return result;
    }

    private static String string(JsonNode value) {
        require(value != null && value.isTextual());
        return value.textValue();
    }

    private static void exactFields(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject() && value.size() == expected.size());
        value.fieldNames().forEachRemaining(name -> require(expected.contains(name)));
    }

    private static void boundedText(String value, int maximumCharacters, int maximumBytes) {
        require(value != null && !value.isBlank());
        if (value.codePointCount(0, value.length()) > maximumCharacters) throw tooLarge();
        utf8Bound(value, maximumBytes);
    }

    /** Count without allocating another full UTF-8 buffer; malformed surrogate text is rejected. */
    private static void utf8Bound(String value, int maximum) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                require(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)));
                index++; bytes += 4;
            } else {
                require(!Character.isLowSurrogate(current));
                bytes += current <= 0x7f ? 1 : current <= 0x7ff ? 2 : 3;
            }
            if (bytes > maximum) throw tooLarge();
        }
    }

    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID); }
    private static IllegalArgumentException tooLarge() { return new IllegalArgumentException(TOO_LARGE); }
}
