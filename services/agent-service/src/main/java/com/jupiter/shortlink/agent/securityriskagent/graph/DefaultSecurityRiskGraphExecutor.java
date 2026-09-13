package com.jupiter.shortlink.agent.securityriskagent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.checkpoint.AgentGraphThreadKeyFactory;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphSessionExecutionCoordinator;
import com.jupiter.shortlink.agent.harness.checkpoint.MysqlGraphCompileConfigFactory;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.jupiter.shortlink.agent.riskcenter.service.RiskCenterService;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.securityriskagent.node.ProfileCandidateLoadNode;
import com.jupiter.shortlink.agent.securityriskagent.node.RiskAutoActionNode;
import com.jupiter.shortlink.agent.securityriskagent.node.RiskEventPersistNode;
import com.jupiter.shortlink.agent.securityriskagent.node.RiskIntakeNode;
import com.jupiter.shortlink.agent.securityriskagent.node.RiskLlmExplanationNode;
import com.jupiter.shortlink.agent.securityriskagent.node.RiskResponseComposeNode;
import com.jupiter.shortlink.agent.securityriskagent.node.RiskScoringNode;
import com.jupiter.shortlink.agent.securityriskagent.node.RiskToolPlanningNode;
import com.jupiter.shortlink.agent.securityriskagent.prompt.SecurityRiskPromptBuilder;
import com.jupiter.shortlink.agent.securityriskagent.rule.SecurityRiskCardFactory;
import com.jupiter.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultSecurityRiskGraphExecutor implements SecurityRiskGraphExecutor {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DefaultSecurityRiskGraphExecutor.class);

    private static final String INTAKE_NODE = "intake";
    private static final String PROFILE_CANDIDATE_LOAD_NODE = "profile_candidate_load";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String RISK_SCORING_NODE = "risk_scoring";
    private static final String LLM_EXPLANATION_NODE = "llm_explanation";
    private static final String RISK_EVENT_PERSIST_NODE = "risk_event_persist";
    private static final String RISK_AUTO_ACTION_NODE = "risk_auto_action";
    private static final String RESPONSE_COMPOSE_NODE = "response_compose";
    private static final String CHECKPOINT_SAVE_NODE = "checkpoint_save";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EXECUTION_ID = "riskExecutionId";
    private final java.util.concurrent.ConcurrentMap<String, ExecutionGuard> executions = new java.util.concurrent.ConcurrentHashMap<>();

    private final GraphCheckpointStore checkpointStore;
    private final AgentProperties agentProperties;
    private final SecurityRiskSanitizer sanitizer;
    private final RiskIntakeNode intakeNode;
    private final ProfileCandidateLoadNode profileCandidateLoadNode;
    private final RiskToolPlanningNode toolPlanningNode;
    private final RiskScoringNode scoringNode;
    private final RiskLlmExplanationNode llmExplanationNode;
    private final RiskEventPersistNode eventPersistNode;
    private final RiskAutoActionNode autoActionNode;
    private final RiskResponseComposeNode responseComposeNode;
    private final BaseCheckpointSaver checkpointSaver;
    private final CompiledGraph graph;

    private final GraphSessionExecutionCoordinator executionCoordinator =
            GraphSessionExecutionCoordinator.global();

    @Autowired
    public DefaultSecurityRiskGraphExecutor(
            @Qualifier("agentExplanationChatClient") ChatClient chatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry,
            JdbcShortLinkRiskProfileRepository shortLinkRiskProfileRepository,
            JdbcGroupRiskProfileRepository groupRiskProfileRepository,
            RiskCenterService riskCenterService,
            RiskPolicyService riskPolicyService,
            MysqlSaver mysqlSaver,
            AgentAuthorityClient authority) {
        this.checkpointStore = checkpointStore;
        this.agentProperties = agentProperties;
        this.sanitizer = new SecurityRiskSanitizer();
        this.intakeNode =
                new RiskIntakeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION);
        this.profileCandidateLoadNode =
                new ProfileCandidateLoadNode(
                        shortLinkRiskProfileRepository,
                        groupRiskProfileRepository,
                        agentProperties.getRisk().getProfile().getTopCandidateSize(),
                        authority,
                        toolRegistry);
        this.toolPlanningNode = new RiskToolPlanningNode(toolRegistry, this.sanitizer);
        this.scoringNode = new RiskScoringNode(new SecurityRiskCardFactory(this.sanitizer));
        this.llmExplanationNode =
                new RiskLlmExplanationNode(
                        chatClient, new SecurityRiskPromptBuilder(this.sanitizer), this.sanitizer);
        this.eventPersistNode =
                new RiskEventPersistNode(riskCenterService, groupRiskProfileRepository);
        this.autoActionNode = new RiskAutoActionNode(riskPolicyService, agentProperties);
        this.responseComposeNode =
                new RiskResponseComposeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION,
                        this.sanitizer);
        this.checkpointSaver = mysqlSaver;
        this.graph = compileGraph();
    }

    public DefaultSecurityRiskGraphExecutor(
            LlmChatClient llmChatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry) {
        this.checkpointStore = checkpointStore;
        this.agentProperties = agentProperties;
        this.sanitizer = new SecurityRiskSanitizer();
        this.intakeNode =
                new RiskIntakeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION);
        this.profileCandidateLoadNode = ProfileCandidateLoadNode.noop();
        this.toolPlanningNode = new RiskToolPlanningNode(toolRegistry, this.sanitizer);
        this.scoringNode = new RiskScoringNode(new SecurityRiskCardFactory(this.sanitizer));
        this.llmExplanationNode =
                new RiskLlmExplanationNode(
                        llmChatClient,
                        new SecurityRiskPromptBuilder(this.sanitizer),
                        this.sanitizer);
        this.eventPersistNode = RiskEventPersistNode.noop();
        this.autoActionNode = RiskAutoActionNode.noop();
        this.responseComposeNode =
                new RiskResponseComposeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION,
                        this.sanitizer);
        this.checkpointSaver = MemorySaver.builder().build();
        this.graph = compileGraph();
    }

    /** Lightweight constructor for callers already migrated to ChatClient. */
    public DefaultSecurityRiskGraphExecutor(
            ChatClient chatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry) {
        this.checkpointStore = checkpointStore;
        this.agentProperties = agentProperties;
        this.sanitizer = new SecurityRiskSanitizer();
        this.intakeNode =
                new RiskIntakeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION);
        this.profileCandidateLoadNode = ProfileCandidateLoadNode.noop();
        this.toolPlanningNode = new RiskToolPlanningNode(toolRegistry, this.sanitizer);
        this.scoringNode = new RiskScoringNode(new SecurityRiskCardFactory(this.sanitizer));
        this.llmExplanationNode =
                new RiskLlmExplanationNode(
                        chatClient, new SecurityRiskPromptBuilder(this.sanitizer), this.sanitizer);
        this.eventPersistNode = RiskEventPersistNode.noop();
        this.autoActionNode = RiskAutoActionNode.noop();
        this.responseComposeNode =
                new RiskResponseComposeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION,
                        this.sanitizer);
        this.checkpointSaver = MemorySaver.builder().build();
        this.graph = compileGraph();
    }

    /**
     * Compatibility constructor used by non-Spring risk graph callers. It keeps the full
     * repository/service wiring but uses an in-memory saver; Spring production wiring uses the
     * ChatClient + MysqlSaver constructor.
     */
    public DefaultSecurityRiskGraphExecutor(
            LlmChatClient llmChatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry,
            JdbcShortLinkRiskProfileRepository shortLinkRiskProfileRepository,
            JdbcGroupRiskProfileRepository groupRiskProfileRepository,
            RiskCenterService riskCenterService,
            RiskPolicyService riskPolicyService) {
        this(
                llmChatClient,
                checkpointStore,
                agentProperties,
                toolRegistry,
                shortLinkRiskProfileRepository,
                groupRiskProfileRepository,
                riskCenterService,
                riskPolicyService,
                null);
    }

    public DefaultSecurityRiskGraphExecutor(
            LlmChatClient llmChatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry,
            JdbcShortLinkRiskProfileRepository shortLinkRiskProfileRepository,
            JdbcGroupRiskProfileRepository groupRiskProfileRepository,
            RiskCenterService riskCenterService,
            RiskPolicyService riskPolicyService,
            AgentAuthorityClient authority) {
        this.checkpointStore = checkpointStore;
        this.agentProperties = agentProperties;
        this.sanitizer = new SecurityRiskSanitizer();
        this.intakeNode =
                new RiskIntakeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION);
        this.profileCandidateLoadNode =
                new ProfileCandidateLoadNode(
                        shortLinkRiskProfileRepository,
                        groupRiskProfileRepository,
                        agentProperties.getRisk().getProfile().getTopCandidateSize(),
                        authority,
                        toolRegistry);
        this.toolPlanningNode = new RiskToolPlanningNode(toolRegistry, this.sanitizer);
        this.scoringNode = new RiskScoringNode(new SecurityRiskCardFactory(this.sanitizer));
        this.llmExplanationNode =
                new RiskLlmExplanationNode(
                        llmChatClient,
                        new SecurityRiskPromptBuilder(this.sanitizer),
                        this.sanitizer);
        this.eventPersistNode =
                new RiskEventPersistNode(riskCenterService, groupRiskProfileRepository);
        this.autoActionNode = new RiskAutoActionNode(riskPolicyService, agentProperties);
        this.responseComposeNode =
                new RiskResponseComposeNode(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION,
                        this.sanitizer);
        this.checkpointSaver = MemorySaver.builder().build();
        this.graph = compileGraph();
    }

    @Override
    public AgentRunResult execute(SecurityRiskGraphRequest request) {
        // Batch retries rebuild from authoritative immutable input, never from a previous attempt's native checkpoint.
        String nativeSession = scopedSession(request) + (request.isBatchExecution()
                ? ":attempt:" + java.util.UUID.randomUUID() : "");
        String graphThreadId =
                AgentGraphThreadKeyFactory.create(
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION,
                        nativeSession);
        try {
            return executionCoordinator.execute(
                    graphThreadId, () -> executeSerialized(request, graphThreadId));
        } catch (Exception ex) {
            if (request.isBatchExecution()) {
                throw new IllegalStateException("Security risk graph execution failed", ex);
            }
            return fallbackResult(request, "Security risk graph failed.", "Graph execution failed");
        }
    }

    private AgentRunResult executeSerialized(
            SecurityRiskGraphRequest request, String graphThreadId) {
        var lease = com.jupiter.shortlink.agent.riskanalysis.job.RiskAnalysisJobLeaseManager.currentExecution();
        long configuredMillis = agentProperties.getRisk().getAnalysis().getExecutionTimeoutMillis();
        long limitMillis = request.isBatchExecution()
                ? Math.min(configuredMillis, java.time.Duration.ofMinutes(Math.max(1, agentProperties.getRisk().getAnalysis().getJobLeaseMinutes())).toMillis())
                : Math.min(configuredMillis, 40000L);
        java.time.Duration budget = java.time.Duration.ofMillis(limitMillis);
        if (lease != null && lease.remainingExecutionTime() != null
                && lease.remainingExecutionTime().compareTo(budget) < 0) budget = lease.remainingExecutionTime();
        ExecutionGuard guard = new ExecutionGuard(budget, lease);
        String executionId = java.util.UUID.randomUUID().toString();
        executions.put(executionId, guard);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(EXECUTION_ID, executionId);
        input.put("sessionId", request.sessionId());
        input.put("username", request.username());
        input.put(
                "principal",
                request.principal() == null ? Map.of() : request.principal().toState());
        input.put(
                "profileRiskContext",
                com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext
                        .empty());
        input.put("profileRiskDataSource", Map.of());
        input.put("toolExecutions", List.of());
        input.put("cards", List.of());
        input.put(
                "analysisInput",
                request.analysisInput() == null
                        ? Map.of()
                        : request.analysisInput().toStateValue());
        input.put("message", request.message());
        input.put("traceId", request.traceId());
        if (request.analysisInput() != null) {
            input.put("analysisInput", request.analysisInput().toStateValue());
        }
        try {
            guard.assertActive();
            Optional<AgentRunResult> completed =
                    reactor.core.publisher.Flux.defer(() -> graph.stream(input,
                                    RunnableConfig.builder().threadId(graphThreadId).build()))
                            // Graph's synchronous subscription path includes loading/cloning checkpoints.
                            // Use Reactor's existing bounded scheduler so even that path is cancellable by the deadline.
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                            .last()
                            .map(com.alibaba.cloud.ai.graph.NodeOutput::state)
                            .map(state -> {
                                guard.assertActive();
                                AgentRunResult result = toRunResult(request, state);
                                guard.assertActive();
                                AgentRunResult saved = saveCheckpointOrWarn(request, state, result);
                                guard.assertActive();
                                return saved;
                            })
                            // Apply to completion, not each emitted node: intermediate progress cannot reset the deadline.
                            .timeout(budget, reactor.core.publisher.Mono.defer(() -> {
                                guard.cancel();
                                return reactor.core.publisher.Mono.error(new IllegalStateException("Security risk graph execution deadline exceeded"));
                            }))
                            .blockOptional();
            guard.assertActive();
            if (completed.isEmpty()) {
                if (request.isBatchExecution()) {
                    throw new IllegalStateException("Security risk graph produced no result");
                }
                return fallbackResult(
                        request,
                        "Security risk graph produced no result.",
                        "Graph execution returned empty state");
            }
            return completed.get();
        } catch (Exception ex) {
            logFailureTypes(request.traceId(), ex);
            if (request.isBatchExecution()) {
                throw new IllegalStateException("Security risk graph execution failed", ex);
            }
            return fallbackResult(request, "Security risk graph failed.", "Graph execution failed");
        } finally {
            guard.cancel();
            executions.remove(executionId, guard);
        }
    }

    private void assertExecutionActive(OverAllState state) {
        ExecutionGuard guard = executions.get(state.value(EXECUTION_ID, ""));
        if (guard == null) throw new IllegalStateException("Security risk execution is no longer active");
        guard.assertActive();
    }

    private static final class ExecutionGuard {
        private final long deadline;
        private final com.jupiter.shortlink.agent.riskanalysis.job.RiskAnalysisJobLeaseManager.Lease lease;
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

        private ExecutionGuard(java.time.Duration budget,
                com.jupiter.shortlink.agent.riskanalysis.job.RiskAnalysisJobLeaseManager.Lease lease) {
            this.deadline = System.nanoTime() + budget.toNanos();
            this.lease = lease;
        }

        private void cancel() { cancelled.set(true); }

        private void assertActive() {
            if (cancelled.get() || System.nanoTime() >= deadline)
                throw new IllegalStateException("Security risk graph execution deadline exceeded");
            if (lease != null) lease.assertExecutionActive();
        }
    }

    /** Log code locations only; exception messages may contain model or business payloads. */
    private static void logFailureTypes(String traceId, Throwable failure) {
        var causes = new ArrayList<String>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            var frames = java.util.Arrays.stream(current.getStackTrace()).filter(frame ->
                            frame.getClassName().startsWith("com.jupiter.")
                                    || frame.getClassName().startsWith("com.alibaba.")
                                    || frame.getClassName().startsWith("com.mysql."))
                    .limit(10)
                    .map(StackTraceElement::toString).toList();
            String sqlCode = current instanceof java.sql.SQLException sql
                    ? " SQLState=" + sql.getSQLState() + " vendorCode=" + sql.getErrorCode() : "";
            causes.add(current.getClass().getName() + sqlCode + " at " + frames);
            current = current.getCause();
        }
        LOG.warn("Security graph failed; traceId={}, causeLocations={}", traceId, causes);
    }

    private CompiledGraph compileGraph() {
        try {
            return new StateGraph(SecurityRiskGraphDefinition.GRAPH_NAME, Map::of,
                    com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory.create())
                    .addNode(
                            INTAKE_NODE,
                            AsyncNodeAction.node_async(
                                    state -> tracedNode(INTAKE_NODE, state, this::intake)))
                    .addNode(
                            PROFILE_CANDIDATE_LOAD_NODE,
                            AsyncNodeAction.node_async(
                                    state ->
                                            tracedNode(
                                                    PROFILE_CANDIDATE_LOAD_NODE,
                                                    state,
                                                    this::loadProfileCandidates)))
                    .addNode(
                            RISK_TOOL_PLANNING_NODE,
                            AsyncNodeAction.node_async(
                                    state ->
                                            tracedNode(
                                                    RISK_TOOL_PLANNING_NODE,
                                                    state,
                                                    this::planAndExecuteTools)))
                    .addNode(
                            RISK_SCORING_NODE,
                            AsyncNodeAction.node_async(
                                    state -> tracedNode(RISK_SCORING_NODE, state, this::scoreRisk)))
                    .addNode(
                            LLM_EXPLANATION_NODE,
                            AsyncNodeAction.node_async(
                                    state ->
                                            tracedNode(
                                                    LLM_EXPLANATION_NODE,
                                                    state,
                                                    this::explainWithLlm)))
                    .addNode(
                            RISK_EVENT_PERSIST_NODE,
                            AsyncNodeAction.node_async(
                                    state ->
                                            tracedNode(
                                                    RISK_EVENT_PERSIST_NODE,
                                                    state,
                                                    this::persistRiskEvents)))
                    .addNode(
                            RISK_AUTO_ACTION_NODE,
                            AsyncNodeAction.node_async(
                                    state ->
                                            tracedNode(
                                                    RISK_AUTO_ACTION_NODE,
                                                    state,
                                                    this::autoAction)))
                    .addNode(
                            RESPONSE_COMPOSE_NODE,
                            AsyncNodeAction.node_async(
                                    state ->
                                            tracedNode(
                                                    RESPONSE_COMPOSE_NODE,
                                                    state,
                                                    this::composeResponse)))
                    .addEdge(StateGraph.START, INTAKE_NODE)
                    .addEdge(INTAKE_NODE, PROFILE_CANDIDATE_LOAD_NODE)
                    .addEdge(PROFILE_CANDIDATE_LOAD_NODE, RISK_TOOL_PLANNING_NODE)
                    .addEdge(RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE)
                    .addEdge(RISK_SCORING_NODE, LLM_EXPLANATION_NODE)
                    .addEdge(LLM_EXPLANATION_NODE, RISK_EVENT_PERSIST_NODE)
                    .addEdge(RISK_EVENT_PERSIST_NODE, RISK_AUTO_ACTION_NODE)
                    .addEdge(RISK_AUTO_ACTION_NODE, RESPONSE_COMPOSE_NODE)
                    .addEdge(RESPONSE_COMPOSE_NODE, StateGraph.END)
                    .compile(MysqlGraphCompileConfigFactory.create(checkpointSaver));
        } catch (GraphStateException ex) {
            throw new IllegalStateException("Security risk graph initialization failed", ex);
        }
    }

    private Map<String, Object> tracedNode(String nodeName, OverAllState state, GraphNode node)
            throws Exception {
        assertExecutionActive(state);
        long startEpochMs = System.currentTimeMillis();
        Map<String, Object> output = new LinkedHashMap<>(node.apply(state));
        assertExecutionActive(state);
        output.put(
                "traceEvents",
                appendTraceEvent(
                        state.value("traceEvents", List.of()),
                        traceEvent(
                                state.value("traceId", ""),
                                nodeName,
                                "success",
                                startEpochMs,
                                null)));
        return output;
    }

    private Map<String, Object> intake(OverAllState state) {
        return intakeNode.apply(state);
    }

    private Map<String, Object> loadProfileCandidates(OverAllState state) {
        return profileCandidateLoadNode.apply(state);
    }

    private Map<String, Object> planAndExecuteTools(OverAllState state) {
        Map<String, Object> output = new LinkedHashMap<>(toolPlanningNode.apply(state));
        List<Map<String, Object>> scopeExecutions = state.value("profileScopeToolExecutions", List.of());
        List<Map<String, Object>> executions = new ArrayList<>(scopeExecutions);
        executions.addAll((List<Map<String, Object>>) output.get("toolExecutions"));
        List<String> warnings = new ArrayList<>(state.value("profileScopeWarnings", List.of()));
        warnings.addAll((List<String>) output.get("toolWarnings"));
        output.put("toolExecutions", executions);
        output.put("toolWarnings", warnings);
        output.put("evidenceRequested", Boolean.TRUE.equals(output.get("evidenceRequested")) || !scopeExecutions.isEmpty());
        return output;
    }

    private Map<String, Object> scoreRisk(OverAllState state) {
        return scoringNode.apply(state);
    }

    private Map<String, Object> explainWithLlm(OverAllState state) {
        String scopeStatus = state.value("profileScopeStatus", "EXPLICIT");
        String statisticsNotice = state.value("statisticsScopeNotice", "");
        boolean authorizedStatistics = !state.value("authorizedStatisticsGid", "").isBlank()
                && state.value("statisticsEvidenceRequested", false);
        if ("MISSING".equals(scopeStatus) || ("NO_PROFILE".equals(scopeStatus) && !authorizedStatistics)) {
            List<String> warnings = state.value("toolWarnings", List.of());
            return Map.of("answer", String.join("\n", warnings), "warnings", warnings,
                    "llmDataSource", Map.of());
        }
        if ("RESOLVED".equals(scopeStatus) || authorizedStatistics || !statisticsNotice.isBlank()) {
            return llmExplanationNode.explain(
                    state.value("message", "") + "\n\n已执行范围说明：" + state.value("profileScopeNotice", "")
                            + "\n" + statisticsNotice
                            + ("NO_PROFILE".equals(scopeStatus) ? "\n该分组当前没有可用风险画像；这不表示没有风险。本轮仅根据统计快照作只读说明。" : ""),
                    state.value("toolExecutions", List.of()), state.value("riskCards", List.of()),
                    state.value("toolWarnings", List.of()));
        }
        return llmExplanationNode.apply(state);
    }

    private Map<String, Object> persistRiskEvents(OverAllState state) {
        return eventPersistNode.apply(state, () -> assertExecutionActive(state));
    }

    private Map<String, Object> autoAction(OverAllState state) {
        return autoActionNode.apply(state, () -> assertExecutionActive(state));
    }

    private Map<String, Object> composeResponse(OverAllState state) {
        return responseComposeNode.apply(state);
    }

    private AgentRunResult toRunResult(SecurityRiskGraphRequest request, OverAllState state) {
        return new AgentRunResult(
                request.sessionId(),
                request.traceId(),
                state.value("answer", ""),
                state.value("cards", List.of()),
                state.value("pendingActions", List.of()),
                state.value("toolCalls", List.of()),
                state.value("dataSources", List.of()),
                state.value("traceEvents", List.of()),
                state.value("warnings", List.of()));
    }

    private AgentRunResult saveCheckpointOrWarn(
            SecurityRiskGraphRequest request, OverAllState state, AgentRunResult result) {
        long startEpochMs = System.currentTimeMillis();
        try {
            Optional<Long> checkpointVersion = saveCheckpoint(request, state, result);
            Map<String, Object> metadata = new LinkedHashMap<>();
            checkpointVersion.ifPresent(version -> metadata.put("checkpointVersion", version));
            return withTraceEvent(
                    result,
                    traceEvent(
                            request.traceId(),
                            CHECKPOINT_SAVE_NODE,
                            "success",
                            startEpochMs,
                            null,
                            metadata));
        } catch (Exception ex) {
            List<String> warnings = new ArrayList<>(result.warnings());
            warnings.add("Graph checkpoint save failed");
            return new AgentRunResult(
                    result.sessionId(),
                    result.traceId(),
                    result.answer(),
                    result.cards(),
                    result.pendingActions(),
                    result.toolCalls(),
                    result.dataSources(),
                    appendTraceEvent(
                            result.traceEvents(),
                            traceEvent(
                                    request.traceId(),
                                    CHECKPOINT_SAVE_NODE,
                                    "failed",
                                    startEpochMs,
                                    "Graph checkpoint save failed")),
                    warnings);
        }
    }

    private Optional<Long> saveCheckpoint(
            SecurityRiskGraphRequest request, OverAllState state, AgentRunResult result) {
        if (!agentProperties.getGraph().isCheckpointEnabled()) {
            return Optional.empty();
        }
        long checkpointVersion = System.currentTimeMillis();
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                        scopedSession(request),
                        request.traceId(),
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION,
                        checkpointJson(request, state, result),
                        checkpointVersion,
                        "FINISHED");
        // Serialization may itself consume the remaining budget. Never start a new write after cancellation.
        assertExecutionActive(state);
        checkpointStore.save(checkpoint);
        return Optional.of(checkpointVersion);
    }

    private String checkpointJson(
            SecurityRiskGraphRequest request, OverAllState state, AgentRunResult result) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("sessionId", request.sessionId());
        checkpoint.put("traceId", request.traceId());
        checkpoint.put("graphName", SecurityRiskGraphDefinition.GRAPH_NAME);
        checkpoint.put("graphVersion", SecurityRiskGraphDefinition.GRAPH_VERSION);
        if (request.analysisInput() != null) {
            checkpoint.put("batchId", request.analysisInput().batchId());
        }
        checkpoint.put("visitedNodes", state.value("visitedNodes", List.of()));
        checkpoint.put("answer", sanitizer.sanitizeText(result.answer()));
        checkpoint.put("warnings", sanitizeForResponse(result.warnings()));
        checkpoint.put("cards", sanitizeForResponse(result.cards()));
        checkpoint.put(
                "toolExecutions", sanitizeForResponse(state.value("toolExecutions", List.of())));
        checkpoint.put(
                "profileRiskDataSource",
                sanitizeForResponse(state.value("profileRiskDataSource", Map.of())));
        checkpoint.put(
                "persistedRiskEvents",
                sanitizeForResponse(state.value("persistedRiskEvents", List.of())));
        checkpoint.put(
                "activatedPolicies",
                sanitizeForResponse(state.value("activatedPolicies", List.of())));
        checkpoint.put("traceEvents", sanitizeForResponse(result.traceEvents()));
        try {
            return OBJECT_MAPPER.writeValueAsString(checkpoint);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Security risk checkpoint serialization failed", ex);
        }
    }

    private Object sanitizeForResponse(Object value) {
        return sanitizer.sanitizeObject(value);
    }

    private AgentRunResult withTraceEvent(AgentRunResult result, Map<String, Object> traceEvent) {
        return new AgentRunResult(
                result.sessionId(),
                result.traceId(),
                result.answer(),
                result.cards(),
                result.pendingActions(),
                result.toolCalls(),
                result.dataSources(),
                appendTraceEvent(result.traceEvents(), traceEvent),
                result.warnings());
    }

    private List<Object> appendTraceEvent(
            List<Object> traceEvents, Map<String, Object> traceEvent) {
        List<Object> appended = new ArrayList<>(traceEvents);
        appended.add(traceEvent);
        return appended;
    }

    private Map<String, Object> traceEvent(
            String traceId, String nodeName, String status, long startEpochMs, String error) {
        return traceEvent(traceId, nodeName, status, startEpochMs, error, Map.of());
    }

    private Map<String, Object> traceEvent(
            String traceId,
            String nodeName,
            String status,
            long startEpochMs,
            String error,
            Map<String, Object> metadata) {
        long endEpochMs = System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("traceId", traceId);
        event.put("nodeName", nodeName);
        event.put("status", status);
        event.put(
                "timing",
                Map.of(
                        "startEpochMs", startEpochMs,
                        "endEpochMs", endEpochMs,
                        "durationMs", Math.max(0L, endEpochMs - startEpochMs)));
        if (error != null && !error.isBlank()) {
            event.put("error", error);
        }
        event.putAll(metadata);
        return event;
    }

    private AgentRunResult fallbackResult(
            SecurityRiskGraphRequest request, String answer, String warning) {
        return new AgentRunResult(
                request.sessionId(),
                request.traceId(),
                sanitizer.sanitizeText(answer),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        traceEvent(
                                request.traceId(),
                                "graph_execution",
                                "failed",
                                System.currentTimeMillis(),
                                sanitizer.sanitizeText(warning))),
                List.of(sanitizer.sanitizeText(warning)));
    }

    @FunctionalInterface
    private interface GraphNode {

        Map<String, Object> apply(OverAllState state) throws Exception;
    }

    private static String scopedSession(SecurityRiskGraphRequest request) {
        return request.principal() == null
                ? "untrusted:" + request.sessionId()
                : request.principal().username()
                        + ":"
                        + request.principal().tenantId()
                        + ":"
                        + request.principal().authVersion()
                        + ":"
                        + request.sessionId();
    }
}
