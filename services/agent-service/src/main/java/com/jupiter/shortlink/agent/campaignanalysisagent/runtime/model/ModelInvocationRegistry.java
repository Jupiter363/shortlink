package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Server-approved model envelopes and public responses, independent of the native agent loop.
 * An Approval is not an Artifact ACL, a dispatch permit, or permission to execute a returned call.
 * Programmatic records are server-composed; external JSON must enter through the bounded decoders.
 */
public final class ModelInvocationRegistry {
    public static final String REQUEST_SCHEMA = "campaign-model-request/v1";
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).build());
    }
    // REACT deliberately has executor=null; top-level model DTOs retain the stricter null policy.
    private static final ObjectMapper STEP_JSON = JSON.copy().disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    public record Limits(int requestBytes, int invocationBytes, int responseBytes, int maxToolCalls) {
        public Limits {
            require(requestBytes > 0 && invocationBytes > 0 && responseBytes > 0 && maxToolCalls > 0,
                    "MODEL_LIMITS_INVALID");
        }
        public static Limits defaults() { return new Limits(1024 * 1024, 16 * 1024 * 1024, 1024 * 1024, 64); }
    }

    public record Identity(String invocationId, String actionId, String childId, String requestId) {}

    /** Slot identity excludes model/request content and attempts, so changing them cannot create a fresh retry. */
    public static Identity identity(RunDefinition definition, String stepId, long turnIndex) {
        require(definition != null && definition.caller() != null, "MODEL_RUN_IDENTITY_REQUIRED");
        id(stepId, 96, "MODEL_STEP_ID_INVALID");
        require(turnIndex >= 1, "MODEL_TURN_INVALID");
        var owner = definition.caller();
        List<Object> invocation = List.of(owner.tenantId(), owner.subject(), owner.authVersion(), definition.sessionId(),
                definition.runId(), definition.planId(), definition.revision(), stepId);
        String invocationHash = CampaignRunStore.sha256(write(invocation));
        String turnHash = CampaignRunStore.sha256(write(List.of(invocationHash, turnIndex)));
        return new Identity("model-invocation-" + invocationHash, "model-action-" + turnHash,
                "model-child-" + turnHash, "model-request-" + turnHash);
    }

    /** Internal MODEL action identity; never a Plan TOOL/SKILL executor or a model-created action. */
    public record ModelActionSpec(String actionId, String stepId, String invocationId,
                                  String modelRef, String modelVersion, String policyRef, String policyVersion,
                                  String stepDefinitionJson) {
        public ModelActionSpec {
            id(actionId, 96, "MODEL_ACTION_ID_INVALID");
            id(stepId, 96, "MODEL_STEP_ID_INVALID");
            id(invocationId, 96, "MODEL_INVOCATION_ID_INVALID");
            reference(modelRef, "MODEL_REFERENCE_INVALID");
            reference(modelVersion, "MODEL_VERSION_INVALID");
            reference(policyRef, "MODEL_POLICY_INVALID");
            reference(policyVersion, "MODEL_POLICY_VERSION_INVALID");
            JsonNode stepTree = object(stepDefinitionJson, "MODEL_STEP_DEFINITION_INVALID");
            Set<String> stepFields = Set.of("stepId", "goalIds", "executionMode", "executor", "explorationPolicy",
                    "dependsOn", "inputBindings", "parameters", "outputContractRef");
            Set<String> required = new HashSet<>(stepFields);
            required.remove("executor");
            fields(stepTree, stepFields, required);
            require(stepTree.has("executor") && stepTree.get("executor").isNull(), "MODEL_STEP_POLICY_MISMATCH");
            PlanSpec.Step step;
            try { step = STEP_JSON.treeToValue(stepTree, PlanSpec.Step.class); }
            catch (JsonProcessingException invalid) { throw new IllegalArgumentException("MODEL_STEP_DEFINITION_INVALID", invalid); }
            require(step.executionMode() == PlanSpec.ExecutionMode.REACT && step.executor() == null
                    && stepId.equals(step.stepId()) && step.explorationPolicy() != null
                    && policyRef.equals(step.explorationPolicy().policyRef())
                    && policyVersion.equals(step.explorationPolicy().policyVersion()), "MODEL_STEP_POLICY_MISMATCH");
            stepDefinitionJson = write(canonical(object(stepDefinitionJson, "MODEL_STEP_DEFINITION_INVALID")));
        }
        public String hash() { return CampaignRunStore.sha256(encodeAction(this)); }
    }

    public record InvocationSpec(String invocationId, long turnIndex, String modelRef, String modelVersion,
                                 String configurationHash, String policyRef, String policyVersion,
                                 String inputSetRef, String requestJson, Map<String, ArtifactMetadata> inputs,
                                 Instant expiresAt) {
        public InvocationSpec {
            id(invocationId, 96, "MODEL_INVOCATION_ID_INVALID");
            require(turnIndex >= 1, "MODEL_TURN_INVALID");
            reference(modelRef, "MODEL_REFERENCE_INVALID");
            reference(modelVersion, "MODEL_VERSION_INVALID");
            hashValue(configurationHash, "MODEL_CONFIGURATION_HASH_INVALID");
            reference(policyRef, "MODEL_POLICY_INVALID");
            reference(policyVersion, "MODEL_POLICY_VERSION_INVALID");
            reference(inputSetRef, "MODEL_INPUT_SET_INVALID");
            requestJson = encodeRequest(parseRequest(object(requestJson, "MODEL_REQUEST_INVALID")));
            require(inputs != null, "MODEL_INPUTS_REQUIRED");
            Map<String, ArtifactMetadata> ordered = new TreeMap<>();
            inputs.forEach((name, input) -> {
                reference(name, "MODEL_INPUT_NAME_INVALID");
                require(input != null && input.ref() != null && input.owner() != null
                        && input.ref().expiresAt() != null, "MODEL_INPUT_METADATA_INVALID");
                hashValue(input.ref().payloadHash(), "MODEL_INPUT_HASH_INVALID");
                ordered.put(name, input);
            });
            inputs = Collections.unmodifiableMap(ordered);
            require(expiresAt != null, "MODEL_EXPIRY_REQUIRED");
            try { expiresAt = Instant.ofEpochMilli(expiresAt.toEpochMilli()); }
            catch (ArithmeticException invalid) { throw new IllegalArgumentException("MODEL_EXPIRY_INVALID", invalid); }
            for (ArtifactMetadata input : inputs.values())
                require(!expiresAt.isAfter(input.ref().expiresAt()), "MODEL_EXPIRY_EXCEEDS_INPUT");
        }
        public String hash() { return CampaignRunStore.sha256(encode(this)); }
    }

    public record ToolCall(String id, String name, String arguments) {
        public ToolCall {
            callId(id);
            toolName(name);
            arguments = write(canonical(object(arguments, "MODEL_TOOL_ARGUMENTS_INVALID")));
        }
    }

    /** Only public text and standard function calls; provider metadata/reasoning is not a field. */
    public record Response(String text, List<ToolCall> toolCalls) {
        public Response {
            require(text != null, "MODEL_RESPONSE_TEXT_REQUIRED");
            toolCalls = calls(toolCalls);
            require(!text.isBlank() || !toolCalls.isEmpty(), "MODEL_RESPONSE_EMPTY");
        }
    }

    /** Closed model request projection. Tool schemas are data, never loaded or executed here. */
    public record Request(String schemaVersion, List<Message> messages, List<ToolDefinition> tools,
                          @JsonInclude(JsonInclude.Include.NON_NULL) GenerationOptions generationOptions) {
        public Request(String schemaVersion, List<Message> messages, List<ToolDefinition> tools) {
            this(schemaVersion, messages, tools, null);
        }
        public Request {
            require(REQUEST_SCHEMA.equals(schemaVersion), "MODEL_REQUEST_SCHEMA_INVALID");
            require(messages != null && !messages.isEmpty() && messages.stream().allMatch(value -> value != null),
                    "MODEL_MESSAGES_REQUIRED");
            require(tools != null && tools.stream().allMatch(value -> value != null), "MODEL_TOOLS_REQUIRED");
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
            Set<String> names = new HashSet<>();
            for (ToolDefinition tool : tools) require(names.add(tool.name()), "MODEL_TOOL_DEFINITION_DUPLICATE");
            validateMessages(messages, names);
        }
    }

    /** Closed server configuration, included in the request hash; no provider extensions or credentials. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationOptions(String model, Integer maxTokens, Double temperature, Double topP, Integer topK,
                                    Double frequencyPenalty, Double presencePenalty, List<String> stopSequences) {
        public GenerationOptions {
            require(model == null || (!model.isBlank() && model.length() <= 256
                    && model.chars().noneMatch(Character::isISOControl)), "MODEL_GENERATION_OPTIONS_INVALID");
            require(maxTokens == null || maxTokens > 0, "MODEL_GENERATION_OPTIONS_INVALID");
            require(topK == null || topK > 0, "MODEL_GENERATION_OPTIONS_INVALID");
            require(valid(temperature, 0, 2) && valid(topP, 0, 1)
                    && valid(frequencyPenalty, -2, 2) && valid(presencePenalty, -2, 2), "MODEL_GENERATION_OPTIONS_INVALID");
            if (stopSequences != null) {
                require(stopSequences.size() <= 32 && stopSequences.stream().allMatch(value -> value != null
                        && !value.isEmpty() && value.length() <= 4096), "MODEL_GENERATION_OPTIONS_INVALID");
                stopSequences = List.copyOf(stopSequences);
            }
            require(model != null || maxTokens != null || temperature != null || topP != null || topK != null
                    || frequencyPenalty != null || presencePenalty != null || stopSequences != null,
                    "MODEL_GENERATION_OPTIONS_EMPTY");
        }
        private static boolean valid(Double value, double minimum, double maximum) {
            return value == null || Double.isFinite(value) && value >= minimum && value <= maximum;
        }
    }

    /** Non-applicable call fields are null; role determines the only legal combinations. */
    public record Message(String role, String text, List<ToolCall> toolCalls, String toolCallId, String toolName) {
        public Message {
            require(role != null && Set.of("system", "user", "assistant", "tool").contains(role),
                    "MODEL_MESSAGE_ROLE_INVALID");
            require(text != null, "MODEL_MESSAGE_TEXT_REQUIRED");
            if ("assistant".equals(role)) {
                toolCalls = toolCalls == null ? List.of() : calls(toolCalls);
                require(toolCallId == null && toolName == null, "MODEL_MESSAGE_FIELDS_INVALID");
                require(!text.isBlank() || !toolCalls.isEmpty(), "MODEL_MESSAGE_EMPTY");
            } else if ("tool".equals(role)) {
                require(toolCalls == null, "MODEL_MESSAGE_FIELDS_INVALID");
                callId(toolCallId);
                ModelInvocationRegistry.toolName(toolName);
            } else {
                require(toolCalls == null && toolCallId == null && toolName == null, "MODEL_MESSAGE_FIELDS_INVALID");
                require(!text.isBlank(), "MODEL_MESSAGE_EMPTY");
            }
        }
    }

    public record ToolDefinition(String name, String description, JsonNode inputSchema) {
        public ToolDefinition {
            toolName(name);
            require(description != null, "MODEL_TOOL_DESCRIPTION_REQUIRED");
            require(inputSchema != null && inputSchema.isObject()
                    && inputSchema.path("type").isTextual()
                    && "object".equals(inputSchema.path("type").textValue()), "MODEL_TOOL_SCHEMA_INVALID");
            inputSchema = canonical(inputSchema.deepCopy());
            var pending = new ArrayDeque<JsonNode>();
            pending.add(inputSchema);
            while (!pending.isEmpty()) {
                JsonNode item = pending.removeFirst();
                if (item.isObject() && item.has("$ref"))
                    require(item.get("$ref").isTextual() && item.get("$ref").textValue().startsWith("#"),
                            "MODEL_TOOL_SCHEMA_EXTERNAL_REFERENCE");
                if (item.isContainerNode()) item.elements().forEachRemaining(pending::addLast);
            }
        }
        @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
    }

    /** Registered service predicates approve exact policy/input/tool configuration, never a model flag. */
    public record Contract(String modelRef, String modelVersion, String configurationHash,
                           Predicate<InvocationSpec> validator) {
        public Contract {
            reference(modelRef, "MODEL_REFERENCE_INVALID");
            reference(modelVersion, "MODEL_VERSION_INVALID");
            hashValue(configurationHash, "MODEL_CONFIGURATION_HASH_INVALID");
            require(validator != null, "MODEL_VALIDATOR_REQUIRED");
        }
    }

    private final Map<Key, Contract> contracts;
    private final Limits limits;

    public ModelInvocationRegistry(List<Contract> contracts) { this(contracts, Limits.defaults()); }

    public ModelInvocationRegistry(List<Contract> contracts, Limits limits) {
        require(contracts != null && limits != null, "MODEL_REGISTRY_CONFIGURATION_REQUIRED");
        Map<Key, Contract> registered = new HashMap<>();
        for (Contract contract : contracts) {
            require(contract != null, "MODEL_CONTRACT_REQUIRED");
            require(registered.putIfAbsent(new Key(contract.modelRef(), contract.modelVersion()), contract) == null,
                    "MODEL_CONTRACT_DUPLICATE");
        }
        this.contracts = Map.copyOf(registered);
        this.limits = limits;
    }

    public Approval approve(InvocationSpec invocation) {
        require(invocation != null, "MODEL_INVOCATION_REQUIRED");
        bytes(invocation.requestJson(), limits.requestBytes(), "MODEL_REQUEST_TOO_LARGE");
        encodedBytes(invocation, limits.invocationBytes(), "MODEL_INVOCATION_TOO_LARGE");
        Contract contract = contracts.get(new Key(invocation.modelRef(), invocation.modelVersion()));
        require(contract != null, "MODEL_CONTRACT_NOT_REGISTERED");
        require(contract.configurationHash().equals(invocation.configurationHash()), "MODEL_CONFIGURATION_CHANGED");
        boolean valid;
        try { valid = contract.validator().test(invocation); }
        catch (RuntimeException rejected) { throw new IllegalArgumentException("MODEL_INVOCATION_REJECTED", rejected); }
        require(valid, "MODEL_INVOCATION_REJECTED");
        Request request = decodeRequest(invocation.requestJson(), limits);
        return new Approval(invocation, limits, request.tools().stream().map(ToolDefinition::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    public static final class Approval {
        private final InvocationSpec invocation;
        private final Limits limits;
        private final Set<String> toolNames;

        private Approval(InvocationSpec invocation, Limits limits, Set<String> toolNames) {
            this.invocation = invocation;
            this.limits = limits;
            this.toolNames = toolNames;
        }
        public InvocationSpec invocation() { return invocation; }

        public void validateResponse(Response response) {
            require(response != null, "MODEL_RESPONSE_REQUIRED");
            require(response.toolCalls().size() <= limits.maxToolCalls(), "MODEL_TOOL_CALL_LIMIT_EXCEEDED");
            encodedBytes(response, limits.responseBytes(), "MODEL_RESPONSE_TOO_LARGE");
            for (ToolCall call : response.toolCalls())
                require(toolNames.contains(call.name()), "MODEL_TOOL_NOT_REGISTERED");
        }
    }

    public static String encode(InvocationSpec invocation) {
        require(invocation != null, "MODEL_INVOCATION_REQUIRED");
        return write(invocation);
    }
    public static InvocationSpec decode(String encoded) { return decode(encoded, Limits.defaults()); }
    public static InvocationSpec decode(String encoded, Limits limits) {
        require(limits != null, "MODEL_LIMITS_REQUIRED");
        bytes(encoded, limits.invocationBytes(), "MODEL_INVOCATION_TOO_LARGE");
        JsonNode tree = object(encoded, "MODEL_INVOCATION_INVALID");
        require(tree.path("expiresAt").isTextual(), "MODEL_EXPIRY_INVALID");
        require(tree.path("requestJson").isTextual(), "MODEL_REQUEST_INVALID");
        bytes(tree.get("requestJson").textValue(), limits.requestBytes(), "MODEL_REQUEST_TOO_LARGE");
        require(tree.path("inputs").isObject(), "MODEL_INPUTS_REQUIRED");
        tree.path("inputs").elements().forEachRemaining(input ->
                require(input.path("ref").path("expiresAt").isTextual(), "MODEL_INPUT_EXPIRY_INVALID"));
        return convert(tree, InvocationSpec.class, "MODEL_INVOCATION_INVALID");
    }
    public static String encodeAction(ModelActionSpec action) {
        require(action != null, "MODEL_ACTION_REQUIRED");
        return write(action);
    }
    public static ModelActionSpec decodeAction(String encoded) { return decodeAction(encoded, Limits.defaults()); }
    public static ModelActionSpec decodeAction(String encoded, Limits limits) {
        require(limits != null, "MODEL_LIMITS_REQUIRED");
        bytes(encoded, limits.invocationBytes(), "MODEL_ACTION_TOO_LARGE");
        return convert(object(encoded, "MODEL_ACTION_INVALID"), ModelActionSpec.class, "MODEL_ACTION_INVALID");
    }
    public static String encodeResponse(Response response) {
        require(response != null, "MODEL_RESPONSE_REQUIRED");
        return write(response);
    }
    public static Response decodeResponse(String encoded) { return decodeResponse(encoded, Limits.defaults()); }
    public static Response decodeResponse(String encoded, Limits limits) {
        require(limits != null, "MODEL_LIMITS_REQUIRED");
        bytes(encoded, limits.responseBytes(), "MODEL_RESPONSE_TOO_LARGE");
        JsonNode tree = object(encoded, "MODEL_RESPONSE_INVALID");
        require(tree.path("toolCalls").isArray() && tree.path("toolCalls").size() <= limits.maxToolCalls(),
                "MODEL_TOOL_CALL_LIMIT_EXCEEDED");
        return convert(tree, Response.class, "MODEL_RESPONSE_INVALID");
    }
    public static String encodeRequest(Request request) {
        require(request != null, "MODEL_REQUEST_REQUIRED");
        return write(canonical(JSON.valueToTree(request)));
    }
    public static Request decodeRequest(String encoded) { return decodeRequest(encoded, Limits.defaults()); }
    public static Request decodeRequest(String encoded, Limits limits) {
        require(limits != null, "MODEL_LIMITS_REQUIRED");
        bytes(encoded, limits.requestBytes(), "MODEL_REQUEST_TOO_LARGE");
        return parseRequest(object(encoded, "MODEL_REQUEST_INVALID"));
    }

    private static Request parseRequest(JsonNode value) {
        fields(value, Set.of("schemaVersion", "messages", "tools", "generationOptions"), Set.of("schemaVersion", "messages", "tools"));
        require(value.path("schemaVersion").isTextual() && value.path("messages").isArray()
                && value.path("tools").isArray(), "MODEL_REQUEST_INVALID");
        List<Message> messages = new ArrayList<>();
        for (JsonNode message : value.get("messages")) {
            fields(message, Set.of("role", "text", "toolCalls", "toolCallId", "toolName"), Set.of("role", "text"));
            require(message.path("role").isTextual() && message.path("text").isTextual(), "MODEL_MESSAGE_INVALID");
            List<ToolCall> toolCalls = null;
            if (message.hasNonNull("toolCalls")) {
                require(message.get("toolCalls").isArray(), "MODEL_TOOL_CALLS_INVALID");
                toolCalls = new ArrayList<>();
                for (JsonNode call : message.get("toolCalls")) toolCalls.add(convert(call, ToolCall.class, "MODEL_TOOL_CALL_INVALID"));
            }
            messages.add(new Message(message.get("role").textValue(), message.get("text").textValue(),
                    toolCalls, optionalText(message, "toolCallId"), optionalText(message, "toolName")));
        }
        List<ToolDefinition> tools = new ArrayList<>();
        for (JsonNode tool : value.get("tools")) {
            fields(tool, Set.of("name", "description", "inputSchema"), Set.of("name", "description", "inputSchema"));
            require(tool.path("name").isTextual() && tool.path("description").isTextual(), "MODEL_TOOL_DEFINITION_INVALID");
            tools.add(new ToolDefinition(tool.get("name").textValue(), tool.get("description").textValue(), tool.get("inputSchema")));
        }
        return new Request(value.get("schemaVersion").textValue(), messages, tools,
                value.has("generationOptions") ? parseGenerationOptions(value.get("generationOptions")) : null);
    }

    private static GenerationOptions parseGenerationOptions(JsonNode value) {
        Set<String> names = Set.of("model", "maxTokens", "temperature", "topP", "topK", "frequencyPenalty", "presencePenalty", "stopSequences");
        fields(value, names, Set.of());
        for (String name : names) if (value.has(name)) require(!value.get(name).isNull(), "MODEL_GENERATION_OPTIONS_INVALID");
        require(!value.has("model") || value.get("model").isTextual(), "MODEL_GENERATION_OPTIONS_INVALID");
        List<String> stops = null;
        if (value.has("stopSequences")) {
            require(value.get("stopSequences").isArray(), "MODEL_GENERATION_OPTIONS_INVALID");
            stops = new ArrayList<>();
            for (JsonNode stop : value.get("stopSequences")) {
                require(stop.isTextual(), "MODEL_GENERATION_OPTIONS_INVALID"); stops.add(stop.textValue());
            }
        }
        return new GenerationOptions(value.has("model") ? value.get("model").textValue() : null,
                optionInteger(value, "maxTokens"), optionDouble(value, "temperature"), optionDouble(value, "topP"),
                optionInteger(value, "topK"), optionDouble(value, "frequencyPenalty"), optionDouble(value, "presencePenalty"), stops);
    }

    private static Integer optionInteger(JsonNode value, String field) {
        if (!value.has(field)) return null;
        require(value.get(field).isIntegralNumber() && value.get(field).canConvertToInt(), "MODEL_GENERATION_OPTIONS_INVALID");
        return value.get(field).intValue();
    }

    private static Double optionDouble(JsonNode value, String field) {
        if (!value.has(field)) return null;
        require(value.get(field).isNumber(), "MODEL_GENERATION_OPTIONS_INVALID");
        return value.get(field).doubleValue();
    }

    private static void validateMessages(List<Message> messages, Set<String> tools) {
        Map<String, String> pending = new LinkedHashMap<>();
        boolean nonSystem = false;
        boolean user = false;
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                require(message.toolName().equals(pending.remove(message.toolCallId())), "MODEL_TOOL_RESPONSE_UNPAIRED");
                continue;
            }
            require(pending.isEmpty(), "MODEL_TOOL_RESPONSES_MISSING");
            if ("system".equals(message.role())) require(!nonSystem, "MODEL_SYSTEM_MESSAGE_POSITION_INVALID");
            else nonSystem = true;
            if ("user".equals(message.role())) user = true;
            if ("assistant".equals(message.role())) {
                require(user, "MODEL_ASSISTANT_WITHOUT_INPUT");
                for (ToolCall call : message.toolCalls()) {
                    require(tools.contains(call.name()), "MODEL_TOOL_NOT_REGISTERED");
                    pending.put(call.id(), call.name());
                }
            }
        }
        require(user && pending.isEmpty(), "MODEL_REQUEST_PAIRING_INVALID");
    }

    private static List<ToolCall> calls(List<ToolCall> values) {
        require(values != null, "MODEL_TOOL_CALLS_REQUIRED");
        Set<String> ids = new HashSet<>();
        for (ToolCall call : values)
            require(call != null && ids.add(call.id()), "MODEL_TOOL_CALL_ID_DUPLICATE");
        return List.copyOf(values);
    }
    private static void fields(JsonNode value, Set<String> allowed, Set<String> required) {
        require(value != null && value.isObject(), "MODEL_REQUEST_FIELDS_INVALID");
        value.fieldNames().forEachRemaining(name -> require(allowed.contains(name), "MODEL_REQUEST_FIELD_UNKNOWN"));
        required.forEach(name -> require(value.hasNonNull(name), "MODEL_REQUEST_FIELD_REQUIRED"));
    }
    private static String optionalText(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        require(node.get(field).isTextual(), "MODEL_MESSAGE_FIELD_INVALID");
        return node.get(field).textValue();
    }
    private static JsonNode object(String encoded, String code) {
        require(encoded != null, code);
        try {
            JsonNode value = JSON.readTree(encoded);
            require(value != null && value.isObject(), code);
            return value;
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException(code, invalid); }
    }
    private static <T> T convert(JsonNode value, Class<T> type, String code) {
        try { return JSON.treeToValue(value, type); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException(code, invalid); }
    }
    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            Map<String, JsonNode> ordered = new TreeMap<>();
            value.fields().forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue()));
            ordered.forEach((key, item) -> result.set(key, canonical(item)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }
    private static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("MODEL_JSON_INVALID", invalid); }
    }
    private static void encodedBytes(Object value, int maximum, String code) {
        try {
            JSON.writeValue(new OutputStream() {
                private long count;
                @Override public void write(int value) throws IOException { count(1); }
                @Override public void write(byte[] values, int offset, int length) throws IOException { count(length); }
                private void count(int length) throws IOException {
                    count += length;
                    if (count > maximum) throw new IOException(code);
                }
            }, value);
        } catch (IOException invalid) { throw new IllegalArgumentException(code, invalid); }
    }
    /** Count UTF-8 before parsing, without allocating a second encoded copy. */
    private static void bytes(String value, int maximum, String code) {
        require(value != null, code);
        long count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) count++;
            else if (c < 0x800) count += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                count += 4; i++;
            } else {
                require(!Character.isSurrogate(c), "MODEL_JSON_UNICODE_INVALID");
                count += 3;
            }
            require(count <= maximum, code);
        }
    }
    private static void id(String value, int maximum, String code) {
        require(value != null && value.length() <= maximum && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"), code);
    }
    private static void reference(String value, String code) {
        require(value != null && !value.isBlank() && value.length() <= 256
                && value.chars().noneMatch(Character::isISOControl), code);
    }
    private static void toolName(String name) {
        require(name != null && name.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"), "MODEL_TOOL_NAME_INVALID");
    }
    private static void callId(String value) {
        require(value != null && !value.isBlank() && value.length() <= 128
                && value.chars().noneMatch(Character::isISOControl), "MODEL_TOOL_CALL_ID_INVALID");
    }
    private static void hashValue(String value, String code) { require(value != null && value.matches("[a-f0-9]{64}"), code); }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
    private record Key(String modelRef, String modelVersion) {}
}
