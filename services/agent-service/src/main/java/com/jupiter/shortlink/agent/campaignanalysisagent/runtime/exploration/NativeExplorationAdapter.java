package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.tool.CancellableAsyncToolCallback;
import com.alibaba.cloud.ai.graph.agent.tool.CancellationToken;
import com.alibaba.cloud.ai.graph.agent.tool.ToolCancelledException;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Opt-in native ReactAgent adapter. Deliberately has no Spring annotation or production entry point.
 * It owns admission/projection boundaries; all model/tool looping remains in ReactAgent 1.1.2.3.
 * A durable session supplies canonical business history and CALL permits; the original P0 ledger
 * and the optional single-turn model boundary remain supported without implying durable history.
 */
public final class NativeExplorationAdapter {
    public static final String DISPATCH_SCOPE = NativeExplorationAdapter.class.getName() + ".dispatch";
    public static final String INPUT_KEY = "explorationInput";
    public static final String OUTPUT_KEY = "explorationResult";
    static final String READY_OBSERVATION_KEY = "explorationReadyObservationId";
    private static final ObjectMapper PUBLIC_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public record ExecutionKey(String tenantId, String subject, long authVersion, String sessionId, String runId,
                               String planId, int revision, String stepId, String policyVersion,
                               String runnerVersion, String topologyVersion) {
        public ExecutionKey {
            for (String value : List.of(tenantId, subject, sessionId, runId, planId, stepId,
                    policyVersion, runnerVersion, topologyVersion)) {
                if (value.isBlank()) throw new IllegalArgumentException("Trusted execution identity is required");
            }
            if (revision < 1 || authVersion < 0) throw new IllegalArgumentException("Invalid execution version");
        }

        public String threadId() {
            // Length-prefixed fields avoid delimiter collisions. User input cannot supply this ID.
            StringBuilder material = new StringBuilder();
            for (String value : List.of(tenantId, subject, Long.toString(authVersion), sessionId, runId, planId,
                    Integer.toString(revision), stepId, policyVersion, runnerVersion, topologyVersion))
                material.append(value.length()).append(':').append(value);
            return UUID.nameUUIDFromBytes(material.toString().getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    public record Limits(int repairAttempts, int inputCharacters, int observationCharacters,
                         int modelResponseCharacters, int modelToolCalls, Duration toolTimeout) {
        public Limits {
            if (repairAttempts < 0 || inputCharacters < 1 || observationCharacters < 1
                    || modelResponseCharacters < 1 || modelToolCalls < 1
                    || toolTimeout == null || toolTimeout.isZero() || toolTimeout.isNegative()) {
                throw new IllegalArgumentException("Explicit P0 limits are required");
            }
        }
    }

    /** Projection is supplied by a trusted Artifact writer, never by model output. */
    public record Observation(String artifactId, String jobId) {
        public Observation {
            if ((artifactId == null) == (jobId == null))
                throw new IllegalArgumentException("Exactly one ready artifact or pending job is required");
            String reference = artifactId != null ? artifactId : jobId;
            if (!reference.matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("An opaque backend reference is required");
        }

        public static Observation ready(String artifactId) { return new Observation(artifactId, null); }
        public static Observation pending(String jobId) { return new Observation(null, jobId); }

        String json() {
            return artifactId != null ? "{\"status\":\"READY\",\"artifactId\":\"" + artifactId + "\"}"
                    : "{\"status\":\"PENDING\",\"jobId\":\"" + jobId + "\"}";
        }
    }

    public record RegisteredTool(ToolCallback callback, Function<String, Observation> persistAndProject) {
        public RegisteredTool {
            Objects.requireNonNull(callback);
            Objects.requireNonNull(persistAndProject);
        }
    }

    /** Passed through trusted ToolContext; an internal HTTP/page operation must use this boundary. */
    public static final class DispatchScope {
        private final ExplorationLedger ledger;
        private final long attempt;
        private final CancellationToken cancellation;

        private DispatchScope(ExplorationLedger ledger, long attempt, CancellationToken cancellation) {
            this.ledger = ledger;
            this.attempt = attempt;
            this.cancellation = cancellation;
        }

        public <T> T dispatch(Callable<T> operation) throws Exception {
            if (cancellation.isCancelled() || !ledger.mayDispatch(attempt))
                throw new IllegalStateException("Exploration dispatch permission was revoked");
            // A network operation already admitted here may finish after cancellation.
            return operation.call();
        }

        /** Use this exact parent on each RunStore child dispatch; P0 has no durable CALL authority. */
        public CallPermit callPermit() {
            if (!(ledger instanceof DurableExplorationSession session))
                throw new IllegalStateException("DURABLE_CALL_PERMIT_REQUIRED");
            if (cancellation.isCancelled() || !ledger.mayDispatch(attempt))
                throw new IllegalStateException("EXPLORATION_DISPATCH_REVOKED");
            return Objects.requireNonNull(session.callPermit(attempt), "DURABLE_CALL_PERMIT_REQUIRED");
        }
    }

    private final ExecutionKey identity;
    private final ExplorationLedger ledger;
    private final Limits limits;
    private final ReactAgent agent;
    private final ModelCallBoundary modelCallBoundary;
    private final DurableExplorationSession durableSession;
    private final List<ToolCallback> registeredCallbacks;
    private final AtomicBoolean modelBoundaryFailed = new AtomicBoolean();
    private final AtomicReference<String> durableBoundaryFailure = new AtomicReference<>();
    private final ThreadLocal<String> acceptedToolCall = new ThreadLocal<>();
    private final Set<String> registeredNames;
    private final AtomicReference<CanonicalExplorationResume.Prepared> resumed = new AtomicReference<>();

    public NativeExplorationAdapter(ExecutionKey identity, ExplorationLedger ledger, ChatModel model,
            List<RegisteredTool> tools, BaseCheckpointSaver saver, Executor executor, Limits limits) {
        this(identity, ledger, model, tools, saver, executor, limits, null);
    }

    public NativeExplorationAdapter(ExecutionKey identity, ExplorationLedger ledger, ChatModel model,
            List<RegisteredTool> tools, BaseCheckpointSaver saver, Executor executor, Limits limits,
            ModelCallBoundary modelCallBoundary) {
        this.identity = Objects.requireNonNull(identity);
        this.ledger = Objects.requireNonNull(ledger);
        this.limits = Objects.requireNonNull(limits);
        this.durableSession = ledger instanceof DurableExplorationSession session ? session : null;
        if (durableSession != null && modelCallBoundary != null && modelCallBoundary != durableSession)
            throw new IllegalArgumentException("DURABLE_SESSION_MODEL_BOUNDARY_MISMATCH");
        this.modelCallBoundary = durableSession != null ? durableSession : modelCallBoundary;
        if (!identity.equals(ledger.identity())) throw new IllegalArgumentException("Ledger identity mismatch");
        var names = new HashSet<String>();
        List<ToolCallback> guarded = new ArrayList<>();
        for (RegisteredTool tool : tools) {
            if (!names.add(tool.callback().getToolDefinition().name()))
                throw new IllegalArgumentException("Duplicate registered tool");
            guarded.add(new GuardedCallback(tool, executor));
        }
        registeredNames = Set.copyOf(names);
        registeredCallbacks = List.copyOf(guarded);
        agent = ReactAgent.builder().name("campaign_exploration_p0").model(Objects.requireNonNull(model))
                .tools(guarded).parallelToolExecution(false).wrapSyncToolsAsAsync(false)
                .hooks(new AdmissionHook(), new ProtocolHook()).interceptors(new ResponseBoundary(), new DispatchInterceptor())
                .saver(Objects.requireNonNull(saver)).releaseThread(false)
                .stateSerializer(AgentStateSerializerFactory.create())
                .outputKey("candidate").enableLogging(false).build();
    }

    public Map<String, Object> invoke(String prompt) throws Exception {
        if (prompt == null || prompt.length() > limits.inputCharacters())
            throw new IllegalArgumentException("Exploration input exceeds its explicit bound");
        if (!identity.equals(ledger.identity())) throw new IllegalStateException("Ledger identity changed");
        if (durableSession != null) {
            ledger.freezeInput(prompt);
            return invokeDurable();
        }
        // A waiting/terminal step must not append another user message or START checkpoint.
        // The before-model hook repeats this check for revocation racing with native invocation.
        if (ledger.readyToResume().isPresent()) return resume();
        if (ledger.mayCallModel()) {
            ledger.freezeInput(prompt);
            agent.invoke(prompt, RunnableConfig.builder().threadId(identity.threadId()).build());
        }
        return projection();
    }

    /** Rebuild from backend facts; caller supplies no messages, tool parameters, or completion IDs. */
    public synchronized Map<String, Object> resume() throws Exception {
        if (durableSession != null) return invokeDurable();
        var facts = ledger.readyToResume();
        if (facts.isEmpty()) return projection();
        CanonicalExplorationResume.Prepared prepared;
        try {
            prepared = CanonicalExplorationResume.prepare(identity, facts.get(), registeredNames, limits);
        } catch (IllegalArgumentException invalid) {
            ledger.fail("RESUME_CONTEXT_INVALID");
            return projection();
        }
        if (!ledger.approveResume(prepared.observationId())) return projection();
        resumed.set(prepared);
        try {
            var result = agent.invoke(Map.of(), RunnableConfig.builder().threadId(identity.threadId()).build());
            if (result.isPresent() && prepared.observationId().equals(
                    result.get().data().get(READY_OBSERVATION_KEY))
                    && ledger.view().status() != ExplorationLedger.Status.FAILED)
                ledger.acknowledgeResume(prepared.observationId());
            return projection();
        } finally {
            resumed.set(null);
            ledger.releaseResume(prepared.observationId());
        }
    }

    private Map<String, Object> invokeDurable() throws Exception {
        // START carries no user/model history from the caller or saver. The native before-model
        // hook replaces messages from durable facts, including when the saver has no checkpoint.
        if (!modelBoundaryFailed.get() && ledger.mayCallModel()) {
            agent.invoke(Map.of(), RunnableConfig.builder().threadId(identity.threadId()).build());
            if (!modelBoundaryFailed.get()) durableSession.acknowledgeCanonical();
        }
        return projection();
    }

    private List<Message> canonicalNativeMessages(List<ModelInvocationRegistry.Message> canonical) {
        List<ModelInvocationRegistry.ToolDefinition> tools = new ArrayList<>();
        for (ToolCallback callback : registeredCallbacks) {
            var definition = callback.getToolDefinition();
            try {
                tools.add(new ModelInvocationRegistry.ToolDefinition(definition.name(), definition.description(),
                        PUBLIC_JSON.readTree(definition.inputSchema())));
            } catch (IOException invalid) {
                throw new IllegalArgumentException("MODEL_TOOL_SCHEMA_INVALID");
            }
        }
        // Recheck exact role/ID pairing and the registered tool-name closure before checkpointing.
        // The session owns full history/configuration checks; projectRequest rechecks the native
        // request after native has applied its options, so no provider options are introduced here.
        var request = new ModelInvocationRegistry.Request(ModelInvocationRegistry.REQUEST_SCHEMA, canonical, tools);
        List<Message> messages = new ArrayList<>();
        for (var message : request.messages()) {
            switch (message.role()) {
                case "system" -> messages.add(new SystemMessage(message.text()));
                case "user" -> messages.add(new UserMessage(message.text()));
                case "assistant" -> messages.add(AssistantMessage.builder().content(message.text())
                        .toolCalls(message.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(
                                call.id(), "function", call.name(), call.arguments())).toList()).build());
                case "tool" -> messages.add(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(message.toolCallId(), message.toolName(), message.text()))).build());
                default -> throw new IllegalArgumentException("MODEL_MESSAGE_TYPE_UNSUPPORTED");
            }
        }
        return List.copyOf(messages);
    }

    private void failDurableBoundary(String reason) {
        // A storage/authorization boundary failure is not evidence of terminal protocol failure.
        // Fence this adapter invocation; a reconstructed session decides from committed MODEL,
        // CALL and receipt facts whether replay is legal or the result remains unknown.
        modelBoundaryFailed.set(true);
        durableBoundaryFailure.compareAndSet(null, reason);
    }

    private Map<String, Object> projection() {
        var state = ledger.view();
        var result = new LinkedHashMap<String, Object>();
        String durableFailure = durableBoundaryFailure.get();
        if (durableSession != null && (state.status() == ExplorationLedger.Status.BLOCKED
                || state.status() == ExplorationLedger.Status.FAILED) && state.reason() != null && !state.reason().isBlank())
            durableFailure = null;
        result.put("status", durableFailure == null ? state.status().name() : ExplorationLedger.Status.BLOCKED.name());
        result.put("reason", durableFailure == null ? state.reason() : durableFailure);
        result.put("artifactIds", state.artifactIds());
        if (state.jobId() != null) result.put("jobId", state.jobId());
        return Map.copyOf(result);
    }

    /**
     * Only boundary projection: parent state and parent thread identity never enter the child graph.
     * The negative asNode sentinel test documents why includeContents=false alone is insufficient.
     */
    public AsyncNodeActionWithConfig projectedNode() {
        return AsyncNodeActionWithConfig.node_async((state, config) -> {
            Object prompt = state.data().get(INPUT_KEY);
            if (!(prompt instanceof String text)) throw new IllegalArgumentException("Missing exploration input");
            return Map.of(OUTPUT_KEY, invoke(text));
        });
    }

    ReactAgent nativeAgentForVerification() { return agent; }

    @HookPositions(HookPosition.BEFORE_MODEL)
    private final class AdmissionHook extends ModelHook {
        @Override public String getName() { return "campaign_admission"; }
        @Override public List<JumpTo> canJumpTo() { return List.of(JumpTo.end); }

        @Override
        public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
            // AgentCommand(null, ...) does not clear jump_to in native 1.1.2.3. Consume the
            // previous repair jump explicitly before the admission hook's conditional edge.
            var updates = new LinkedHashMap<String, Object>();
            boolean allowed;
            try {
                allowed = !modelBoundaryFailed.get() && ledger.mayCallModel();
                if (allowed && durableSession != null) {
                    updates.put("messages", ReplaceAllWith.of(canonicalNativeMessages(durableSession.canonicalMessages())));
                    updates.put(READY_OBSERVATION_KEY, OverAllState.MARK_FOR_REMOVAL);
                }
            } catch (RuntimeException invalid) {
                if (durableSession == null) throw invalid;
                failDurableBoundary("DURABLE_CONTEXT_REJECTED");
                allowed = false;
            }
            var ready = resumed.get();
            if (allowed && ready != null) {
                boolean applied = ready.observationId().equals(state.data().get(READY_OBSERVATION_KEY));
                if (!ready.matchesPendingPair(state.data().get("messages"))
                        || (applied && !ready.matchesAppliedObservation(state.data().get("messages")))) {
                    ledger.fail("RESUME_CONTEXT_INVALID");
                    allowed = false;
                } else if (!applied) {
                    updates.put("messages", ReplaceAllWith.of(ready.messages()));
                    updates.put(READY_OBSERVATION_KEY, ready.observationId());
                }
            }
            updates.put("jump_to", allowed ? OverAllState.MARK_FOR_REMOVAL : JumpTo.end);
            return CompletableFuture.completedFuture(updates);
        }
    }

    @HookPositions(HookPosition.AFTER_MODEL)
    private final class ProtocolHook extends MessagesModelHook {
        @Override public String getName() { return "campaign_protocol"; }
        @Override public List<JumpTo> canJumpTo() { return List.of(JumpTo.model, JumpTo.end); }

        @Override
        public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
            if (durableSession == null) return processModel(messages);
            try {
                return processModel(messages);
            } catch (RuntimeException invalid) {
                failDurableBoundary("DURABLE_PROTOCOL_REJECTED");
                return new AgentCommand(JumpTo.end, messages, UpdatePolicy.REPLACE);
            }
        }

        private AgentCommand processModel(List<Message> messages) {
            if (modelBoundaryFailed.get())
                return new AgentCommand(JumpTo.end, messages, UpdatePolicy.REPLACE);
            Message last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (!(last instanceof AssistantMessage assistant)) {
                ledger.fail("MODEL_PROTOCOL_INVALID");
                return new AgentCommand(JumpTo.end, messages, UpdatePolicy.REPLACE);
            }
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                ledger.candidate();
                return new AgentCommand(JumpTo.end, messages, UpdatePolicy.REPLACE);
            }
            var ids = new HashSet<String>();
            for (var call : calls) {
                if (call.id() == null || call.id().isBlank() || !ids.add(call.id())) {
                    ledger.fail("MODEL_PROTOCOL_INVALID");
                    return new AgentCommand(JumpTo.end, messages, UpdatePolicy.REPLACE);
                }
            }
            if (calls.size() > 1) {
                var repaired = new ArrayList<>(messages);
                repaired.add(ToolResponseMessage.builder().responses(calls.stream()
                        .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                                "{\"executed\":false,\"code\":\"BATCH_REJECTED\"}"))
                        .toList()).build());
                boolean retry = ledger.rejectBatch(limits.repairAttempts());
                return new AgentCommand(retry ? JumpTo.model : JumpTo.end, repaired, UpdatePolicy.REPLACE);
            }
            var call = calls.get(0);
            var ready = resumed.get();
            if (ready != null && ready.repeatsOriginalCall(call)) {
                ledger.fail("RESUME_TOOL_RESUBMISSION_REJECTED");
                return new AgentCommand(JumpTo.end, messages, UpdatePolicy.REPLACE);
            }
            ledger.registerCall(new ExplorationLedger.CallInput(call.id(), call.name(), call.arguments(), assistant.getText()));
            return new AgentCommand(messages, UpdatePolicy.REPLACE);
        }
    }

    private final class DispatchInterceptor extends ToolInterceptor {
        @Override public String getName() { return "campaign_dispatch"; }

        @Override
        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
            try {
                if (modelBoundaryFailed.get() || !ledger.mayCallModel())
                    return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), "DISPATCH_REVOKED");
            } catch (RuntimeException invalid) {
                if (durableSession == null) throw invalid;
                failDurableBoundary("DURABLE_DISPATCH_REJECTED");
                return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), "DURABLE_DISPATCH_REJECTED");
            }
            acceptedToolCall.set(request.getToolCallId());
            try {
                return handler.call(request);
            } finally {
                acceptedToolCall.remove();
            }
        }
    }

    /** Reject before AgentLlmNode can put model text/arguments/metadata into state or a checkpoint. */
    private final class ResponseBoundary extends ModelInterceptor {
        private final ObjectMapper json = AgentStateSerializerFactory.create().objectMapper();
        @Override public String getName() { return "campaign_response_boundary"; }

        @Override
        public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
            try {
                if (modelBoundaryFailed.get() || !ledger.mayCallModel())
                    return ModelResponse.of(new AssistantMessage("EXPLORATION_NOT_ACTIVE"));
            } catch (RuntimeException invalid) {
                if (durableSession == null) throw invalid;
                failDurableBoundary("DURABLE_CONTEXT_REJECTED");
                return ModelResponse.of(new AssistantMessage("DURABLE_CONTEXT_REJECTED"));
            }
            if (modelCallBoundary != null) return persistentModel(request, handler);
            ModelResponse response = handler.call(request);
            if (!(response.getMessage() instanceof AssistantMessage message)) {
                ledger.fail("MODEL_PROTOCOL_INVALID");
                return ModelResponse.of(new AssistantMessage("MODEL_PROTOCOL_INVALID"));
            }
            try {
                if (message.getToolCalls() != null && message.getToolCalls().size() > limits.modelToolCalls())
                    throw new IOException("Model tool-call batch exceeds its bound");
                // Count serialized content without allocating a second copy of an oversized response.
                // Bound the exact values AgentLlmNode publishes: message and token usage. The
                // transport ChatResponse itself is not stored in graph state and contains Spring
                // metadata objects that are not general Jackson beans. Use the production graph
                // mapper so normal messages/usage have the same serialization as a checkpoint.
                var published = new LinkedHashMap<String, Object>();
                published.put("message", message);
                if (response.getChatResponse() != null)
                    published.put("usage", response.getChatResponse().getMetadata().getUsage());
                json.writeValue(new BoundedWriter(limits.modelResponseCharacters()), published);
                return response;
            } catch (IOException invalid) {
                ledger.fail("MODEL_RESPONSE_REJECTED");
                return ModelResponse.of(new AssistantMessage("MODEL_RESPONSE_REJECTED"));
            }
        }

        private ModelResponse persistentModel(ModelRequest request, ModelCallHandler handler) {
            try {
                var projected = projectRequest(request, registeredCallbacks);
                var response = Objects.requireNonNull(modelCallBoundary.call(projected, () -> {
                    if (!ledger.mayCallModel()) throw new IllegalStateException("MODEL_EXECUTION_REVOKED");
                    ModelResponse nativeResponse = handler.call(request);
                    // Native 1.1.2.3 catches model exceptions and synthesizes text with no ChatResponse.
                    // That fallback is not a successful model fact and may contain a provider's raw error.
                    if (nativeResponse == null || nativeResponse.getChatResponse() == null
                            || nativeResponse.getChatResponse().getResult() == null
                            || !(nativeResponse.getMessage() instanceof AssistantMessage message)
                            || nativeResponse.getChatResponse().getResult().getOutput() != message)
                        throw new IllegalStateException("MODEL_RESPONSE_UNAVAILABLE");
                    requirePublicAssistant(message);
                    try {
                        if (message.getToolCalls().size() > limits.modelToolCalls())
                            throw new IOException("MODEL_RESPONSE_REJECTED");
                        var bounded = new LinkedHashMap<String, Object>();
                        bounded.put("message", message);
                        bounded.put("usage", nativeResponse.getChatResponse().getMetadata().getUsage());
                        json.writeValue(new BoundedWriter(limits.modelResponseCharacters()), bounded);
                    } catch (IOException rejected) {
                        throw new IllegalStateException("MODEL_RESPONSE_REJECTED");
                    }
                    return publicResponse(message);
                }), "MODEL_RESPONSE_REQUIRED");
                // Replay receives the same public projection and the same native-size check as a fresh fact.
                if (response.toolCalls().size() > limits.modelToolCalls())
                    throw new IllegalStateException("MODEL_RESPONSE_REJECTED");
                AssistantMessage message = AssistantMessage.builder().content(response.text())
                        .toolCalls(response.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(
                                call.id(), "function", call.name(), call.arguments())).toList()).build();
                json.writeValue(new BoundedWriter(limits.modelResponseCharacters()), Map.of("message", message));
                return ModelResponse.of(message);
            } catch (Exception rejected) {
                if (durableSession != null) {
                    failDurableBoundary("MODEL_BOUNDARY_REJECTED");
                    return ModelResponse.of(new AssistantMessage("MODEL_BOUNDARY_REJECTED"));
                }
                modelBoundaryFailed.set(true);
                try { ledger.fail("MODEL_BOUNDARY_REJECTED"); }
                catch (RuntimeException ledgerFailure) { throw new IllegalStateException("MODEL_BOUNDARY_REJECTED"); }
                return ModelResponse.of(new AssistantMessage("MODEL_BOUNDARY_REJECTED"));
            }
        }
    }

    /**
     * Closed projection of the request that native 1.1.2.3 sends to ChatClient. Runtime generation
     * options, named resolvers, dynamic callbacks, media and unknown message properties are not
     * supported by this first durable boundary. ModelRequest.context is native control state and
     * is not forwarded by AgentLlmNode's ChatClient request builder; it is never serialized here.
     */
    public static ModelInvocationRegistry.Request projectRequest(ModelRequest request, List<ToolCallback> registeredTools) {
        Objects.requireNonNull(request, "MODEL_REQUEST_REQUIRED");
        Objects.requireNonNull(registeredTools, "MODEL_TOOLS_REQUIRED");
        if (request.getDynamicToolCallbacks() != null && !request.getDynamicToolCallbacks().isEmpty())
            throw new IllegalArgumentException("MODEL_DYNAMIC_TOOLS_UNSUPPORTED");
        Map<String, ToolCallback> registered = new LinkedHashMap<>();
        for (ToolCallback tool : registeredTools) {
            if (tool == null || registered.putIfAbsent(tool.getToolDefinition().name(), tool) != null)
                throw new IllegalArgumentException("MODEL_TOOLS_INVALID");
        }
        ToolCallingChatOptions options = request.getOptions();
        List<ToolCallback> callbacks = List.of();
        if (options != null) {
            if (options.getClass() != DefaultToolCallingChatOptions.class || options.getModel() != null
                    || options.getFrequencyPenalty() != null || options.getMaxTokens() != null
                    || options.getPresencePenalty() != null || options.getStopSequences() != null
                    || options.getTemperature() != null || options.getTopK() != null || options.getTopP() != null
                    || !Boolean.FALSE.equals(options.getInternalToolExecutionEnabled())
                    || !options.getToolNames().isEmpty() || !options.getToolContext().isEmpty())
                throw new IllegalArgumentException("MODEL_RUNTIME_OPTIONS_UNSUPPORTED");
            callbacks = options.getToolCallbacks();
            Set<String> callbackNames = new HashSet<>();
            for (ToolCallback callback : callbacks) {
                if (callback == null || registered.get(callback.getToolDefinition().name()) != callback
                        || !callbackNames.add(callback.getToolDefinition().name()))
                    throw new IllegalArgumentException("MODEL_TOOL_CALLBACK_UNAPPROVED");
            }
        }
        List<String> selected = request.getTools();
        if (selected != null && (!registered.keySet().containsAll(selected)
                || new HashSet<>(selected).size() != selected.size()))
            throw new IllegalArgumentException("MODEL_TOOL_SELECTION_INVALID");
        if (request.getToolDescriptions() != null) request.getToolDescriptions().forEach((name, description) -> {
            ToolCallback callback = registered.get(name);
            if (callback == null || !Objects.equals(callback.getToolDefinition().description(), description))
                throw new IllegalArgumentException("MODEL_TOOL_DESCRIPTION_CHANGED");
        });
        List<ModelInvocationRegistry.ToolDefinition> tools = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            if (selected != null && !selected.isEmpty() && !selected.contains(definition.name())) continue;
            try {
                tools.add(new ModelInvocationRegistry.ToolDefinition(definition.name(), definition.description(),
                        PUBLIC_JSON.readTree(definition.inputSchema())));
            } catch (IOException invalid) { throw new IllegalArgumentException("MODEL_TOOL_SCHEMA_INVALID"); }
        }
        List<ModelInvocationRegistry.Message> messages = new ArrayList<>();
        if (request.getSystemMessage() != null) projectMessage(request.getSystemMessage(), messages);
        if (request.getMessages() == null) throw new IllegalArgumentException("MODEL_MESSAGES_REQUIRED");
        for (Message message : request.getMessages()) projectMessage(message, messages);
        return new ModelInvocationRegistry.Request(ModelInvocationRegistry.REQUEST_SCHEMA, messages, tools);
    }

    private static void projectMessage(Message message, List<ModelInvocationRegistry.Message> messages) {
        if (message == null) throw new IllegalArgumentException("MODEL_MESSAGE_REQUIRED");
        requireMessageProperties(message);
        if (message.getClass() == SystemMessage.class) {
            messages.add(new ModelInvocationRegistry.Message("system", message.getText(), null, null, null));
        } else if (message.getClass() == UserMessage.class) {
            if (!((UserMessage) message).getMedia().isEmpty()) throw new IllegalArgumentException("MODEL_MEDIA_UNSUPPORTED");
            messages.add(new ModelInvocationRegistry.Message("user", message.getText(), null, null, null));
        } else if (message.getClass() == AssistantMessage.class) {
            AssistantMessage assistant = (AssistantMessage) message;
            requirePublicAssistant(assistant);
            var response = publicResponse(assistant);
            messages.add(new ModelInvocationRegistry.Message("assistant", response.text(), response.toolCalls(), null, null));
        } else if (message.getClass() == ToolResponseMessage.class) {
            var responses = ((ToolResponseMessage) message).getResponses();
            if (responses == null || responses.isEmpty()) throw new IllegalArgumentException("MODEL_TOOL_RESPONSE_EMPTY");
            for (var response : responses) {
                if (response == null) throw new IllegalArgumentException("MODEL_TOOL_RESPONSE_INVALID");
                messages.add(new ModelInvocationRegistry.Message("tool", response.responseData(), null, response.id(), response.name()));
            }
        } else throw new IllegalArgumentException("MODEL_MESSAGE_TYPE_UNSUPPORTED");
    }

    private static void requireMessageProperties(Message message) {
        for (var property : message.getMetadata().entrySet()) {
            String name = property.getKey();
            Object value = property.getValue();
            if ("messageType".equals(name) && (message.getMessageType().equals(value)
                    || message.getMessageType().name().equals(value)
                    || message.getMessageType().name().toLowerCase(java.util.Locale.ROOT).equals(value))) continue;
            if (message instanceof AssistantMessage && "canonicalAssistantMessageId".equals(name)
                    && value instanceof String id && id.matches("[A-Za-z0-9_-]{1,128}")) continue;
            if (message instanceof UserMessage && "trustedReadyObservationId".equals(name)
                    && value instanceof String id && id.matches("[A-Za-z0-9_-]{1,128}")
                    && "trusted_ledger".equals(message.getMetadata().get("source"))) continue;
            if (message instanceof UserMessage && "source".equals(name) && "trusted_ledger".equals(value)
                    && message.getMetadata().get("trustedReadyObservationId") instanceof String id
                    && id.matches("[A-Za-z0-9_-]{1,128}")) continue;
            throw new IllegalArgumentException("MODEL_MESSAGE_PROPERTIES_UNSUPPORTED");
        }
    }

    private static void requirePublicAssistant(AssistantMessage message) {
        if (message.getClass() != AssistantMessage.class || message.getMedia() == null || !message.getMedia().isEmpty()
                || message.getToolCalls() == null) throw new IllegalArgumentException("MODEL_RESPONSE_CONTENT_UNSUPPORTED");
        for (var call : message.getToolCalls())
            if (call == null || !"function".equals(call.type())) throw new IllegalArgumentException("MODEL_TOOL_TYPE_UNSUPPORTED");
    }

    private static ModelInvocationRegistry.Response publicResponse(AssistantMessage message) {
        return new ModelInvocationRegistry.Response(message.getText() == null ? "" : message.getText(),
                message.getToolCalls().stream().map(call -> new ModelInvocationRegistry.ToolCall(
                        call.id(), call.name(), call.arguments())).toList());
    }

    private static final class BoundedWriter extends Writer {
        private long remaining;
        private BoundedWriter(long remaining) { this.remaining = remaining; }
        @Override public void write(char[] text, int offset, int length) throws IOException {
            if (length > remaining) throw new IOException("Model response exceeds its bound");
            remaining -= length;
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    /**
     * Native AgentToolNode copies Throwable.getMessage() into a ToolResponse. Expose a fixed
     * code without the original cause/message, while retaining native cancellation/interrupt
     * categories. A production diagnostic sink is intentionally outside this P0 adapter.
     */
    private static Throwable failureForNative(Throwable failure) {
        if (failure instanceof TimeoutException) return new TimeoutException("TOOL_EXECUTION_TIMED_OUT");
        if (failure instanceof CancellationException) return new CancellationException("TOOL_EXECUTION_CANCELLED");
        if (failure instanceof ToolCancelledException) return new ToolCancelledException("TOOL_EXECUTION_CANCELLED");
        if (failure instanceof InterruptedException) return new InterruptedException("TOOL_EXECUTION_INTERRUPTED");
        return new IllegalStateException("TOOL_EXECUTION_FAILED");
    }

    private final class GuardedCallback implements CancellableAsyncToolCallback {
        private final RegisteredTool tool;
        private final Executor executor;

        private GuardedCallback(RegisteredTool tool, Executor executor) {
            this.tool = tool;
            this.executor = Objects.requireNonNull(executor);
        }

        @Override public ToolDefinition getToolDefinition() { return tool.callback().getToolDefinition(); }
        @Override public ToolMetadata getToolMetadata() { return tool.callback().getToolMetadata(); }
        @Override public Duration getTimeout() { return limits.toolTimeout(); }
        @Override public String call(String input) { throw new UnsupportedOperationException("Native async path required"); }

        @Override
        public CompletableFuture<String> callAsync(String input, ToolContext context, CancellationToken cancellation) {
            final long attempt;
            try {
                attempt = ledger.beginCallback(Objects.requireNonNull(acceptedToolCall.get(),
                        "Native tool admission must precede callback execution"), getToolDefinition().name());
            } catch (RuntimeException invalid) {
                if (durableSession == null) throw invalid;
                failDurableBoundary("DURABLE_CALLBACK_REJECTED");
                return CompletableFuture.failedFuture(new IllegalStateException("DURABLE_CALLBACK_REJECTED"));
            }
            cancellation.onCancel(() -> ledger.unresolved(attempt));
            var data = new LinkedHashMap<>(context.getContext());
            data.put(DISPATCH_SCOPE, new DispatchScope(ledger, attempt, cancellation));
            // AgentToolNode applies orTimeout to this exposed promise. It must not be the
            // executor's AsyncSupply future: completion while queued would skip its supplier
            // entirely, including the finally that owns the callback permit.
            var result = new CompletableFuture<String>();
            try {
                executor.execute(() -> {
                    String observation = null;
                    Throwable failure = null;
                    try {
                        if (!ledger.mayDispatch(attempt) || cancellation.isCancelled())
                            throw new IllegalStateException("Dispatch permission was revoked before execution");
                        String raw = tool.callback().call(input, new ToolContext(data));
                        Observation projected = Objects.requireNonNull(tool.persistAndProject().apply(raw));
                        observation = projected.json();
                        if (observation.length() > limits.observationCharacters())
                            throw new IllegalStateException("Observation exceeds its explicit bound");
                        ledger.recordObservation(attempt, projected);
                    } catch (Throwable thrown) {
                        failure = thrown;
                        ledger.unresolved(attempt);
                        if (thrown instanceof InterruptedException) Thread.currentThread().interrupt();
                    } finally {
                        ledger.callbackExited(attempt);
                    }
                    // Complete only after actual exit, so the next model cannot race the
                    // finally block and mistake an ordinary successful callback for in-flight.
                    if (failure == null) result.complete(observation);
                    else result.completeExceptionally(failureForNative(failure));
                });
            } catch (RuntimeException | Error rejected) {
                ledger.unresolved(attempt);
                ledger.callbackExited(attempt);
                result.completeExceptionally(failureForNative(rejected));
            }
            return result;
        }
    }
}
