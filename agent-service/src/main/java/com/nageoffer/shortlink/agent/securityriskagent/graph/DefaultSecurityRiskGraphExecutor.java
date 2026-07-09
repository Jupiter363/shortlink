package com.nageoffer.shortlink.agent.securityriskagent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.securityriskagent.node.RiskIntakeNode;
import com.nageoffer.shortlink.agent.securityriskagent.node.RiskLlmExplanationNode;
import com.nageoffer.shortlink.agent.securityriskagent.node.RiskResponseComposeNode;
import com.nageoffer.shortlink.agent.securityriskagent.node.RiskScoringNode;
import com.nageoffer.shortlink.agent.securityriskagent.node.RiskToolPlanningNode;
import com.nageoffer.shortlink.agent.securityriskagent.prompt.SecurityRiskPromptBuilder;
import com.nageoffer.shortlink.agent.securityriskagent.rule.SecurityRiskCardFactory;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultSecurityRiskGraphExecutor implements SecurityRiskGraphExecutor {

    private static final String GRAPH_NAME = "security-risk-graph";
    private static final String GRAPH_VERSION = "v1";
    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String RISK_SCORING_NODE = "risk_scoring";
    private static final String LLM_EXPLANATION_NODE = "llm_explanation";
    private static final String RESPONSE_COMPOSE_NODE = "response_compose";
    private static final String CHECKPOINT_SAVE_NODE = "checkpoint_save";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GraphCheckpointStore checkpointStore;
    private final AgentProperties agentProperties;
    private final SecurityRiskSanitizer sanitizer;
    private final RiskIntakeNode intakeNode;
    private final RiskToolPlanningNode toolPlanningNode;
    private final RiskScoringNode scoringNode;
    private final RiskLlmExplanationNode llmExplanationNode;
    private final RiskResponseComposeNode responseComposeNode;
    private final CompiledGraph graph;

    public DefaultSecurityRiskGraphExecutor(
            LlmChatClient llmChatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry
    ) {
        this.checkpointStore = checkpointStore;
        this.agentProperties = agentProperties;
        this.sanitizer = new SecurityRiskSanitizer();
        this.intakeNode = new RiskIntakeNode(GRAPH_NAME, GRAPH_VERSION);
        this.toolPlanningNode = new RiskToolPlanningNode(toolRegistry, this.sanitizer);
        this.scoringNode = new RiskScoringNode(new SecurityRiskCardFactory(this.sanitizer));
        this.llmExplanationNode = new RiskLlmExplanationNode(llmChatClient, new SecurityRiskPromptBuilder(this.sanitizer), this.sanitizer);
        this.responseComposeNode = new RiskResponseComposeNode(GRAPH_NAME, GRAPH_VERSION, this.sanitizer);
        this.graph = compileGraph();
    }

    @Override
    public AgentRunResult execute(SecurityRiskGraphRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sessionId", request.sessionId());
        input.put("username", request.username());
        input.put("message", request.message());
        input.put("traceId", request.traceId());
        try {
            Optional<OverAllState> state = graph.invoke(input, RunnableConfig.builder()
                    .threadId(request.sessionId())
                    .build());
            if (state.isEmpty()) {
                return fallbackResult(request, "Security risk graph produced no result.", "Graph execution returned empty state");
            }
            AgentRunResult result = toRunResult(request, state.get());
            return saveCheckpointOrWarn(request, state.get(), result);
        } catch (Exception ex) {
            return fallbackResult(request, "Security risk graph failed.", "Graph execution failed");
        }
    }

    private CompiledGraph compileGraph() {
        try {
            return new StateGraph(GRAPH_NAME, Map::of)
                    .addNode(INTAKE_NODE, AsyncNodeAction.node_async(state -> tracedNode(INTAKE_NODE, state, this::intake)))
                    .addNode(RISK_TOOL_PLANNING_NODE, AsyncNodeAction.node_async(state -> tracedNode(RISK_TOOL_PLANNING_NODE, state, this::planAndExecuteTools)))
                    .addNode(RISK_SCORING_NODE, AsyncNodeAction.node_async(state -> tracedNode(RISK_SCORING_NODE, state, this::scoreRisk)))
                    .addNode(LLM_EXPLANATION_NODE, AsyncNodeAction.node_async(state -> tracedNode(LLM_EXPLANATION_NODE, state, this::explainWithLlm)))
                    .addNode(RESPONSE_COMPOSE_NODE, AsyncNodeAction.node_async(state -> tracedNode(RESPONSE_COMPOSE_NODE, state, this::composeResponse)))
                    .addEdge(StateGraph.START, INTAKE_NODE)
                    .addEdge(INTAKE_NODE, RISK_TOOL_PLANNING_NODE)
                    .addEdge(RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE)
                    .addEdge(RISK_SCORING_NODE, LLM_EXPLANATION_NODE)
                    .addEdge(LLM_EXPLANATION_NODE, RESPONSE_COMPOSE_NODE)
                    .addEdge(RESPONSE_COMPOSE_NODE, StateGraph.END)
                    .compile();
        } catch (GraphStateException ex) {
            throw new IllegalStateException("Security risk graph initialization failed", ex);
        }
    }

    private Map<String, Object> tracedNode(String nodeName, OverAllState state, GraphNode node) throws Exception {
        long startEpochMs = System.currentTimeMillis();
        Map<String, Object> output = new LinkedHashMap<>(node.apply(state));
        output.put("traceEvents", appendTraceEvent(
                state.value("traceEvents", List.of()),
                traceEvent(state.value("traceId", ""), nodeName, "success", startEpochMs, null)
        ));
        return output;
    }

    private Map<String, Object> intake(OverAllState state) {
        return intakeNode.apply(state);
    }

    private Map<String, Object> planAndExecuteTools(OverAllState state) {
        return toolPlanningNode.apply(state);
    }

    private Map<String, Object> scoreRisk(OverAllState state) {
        return scoringNode.apply(state);
    }

    private Map<String, Object> explainWithLlm(OverAllState state) {
        return llmExplanationNode.apply(state);
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
                state.value("warnings", List.of())
        );
    }

    private AgentRunResult saveCheckpointOrWarn(SecurityRiskGraphRequest request, OverAllState state, AgentRunResult result) {
        long startEpochMs = System.currentTimeMillis();
        try {
            Optional<Long> checkpointVersion = saveCheckpoint(request, state, result);
            return withTraceEvent(result, traceEvent(request.traceId(), CHECKPOINT_SAVE_NODE, "success", startEpochMs, null));
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
                    appendTraceEvent(result.traceEvents(), traceEvent(request.traceId(), CHECKPOINT_SAVE_NODE, "failed", startEpochMs, "Graph checkpoint save failed")),
                    warnings
            );
        }
    }

    private Optional<Long> saveCheckpoint(SecurityRiskGraphRequest request, OverAllState state, AgentRunResult result) {
        if (!agentProperties.getGraph().isCheckpointEnabled()) {
            return Optional.empty();
        }
        long checkpointVersion = System.currentTimeMillis();
        checkpointStore.save(new GraphCheckpoint(
                request.sessionId(),
                request.traceId(),
                GRAPH_NAME,
                GRAPH_VERSION,
                checkpointJson(request, state, result),
                checkpointVersion,
                "FINISHED"
        ));
        return Optional.of(checkpointVersion);
    }

    private String checkpointJson(SecurityRiskGraphRequest request, OverAllState state, AgentRunResult result) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("sessionId", request.sessionId());
        checkpoint.put("traceId", request.traceId());
        checkpoint.put("graphName", GRAPH_NAME);
        checkpoint.put("graphVersion", GRAPH_VERSION);
        checkpoint.put("visitedNodes", state.value("visitedNodes", List.of()));
        checkpoint.put("answer", sanitizer.sanitizeText(result.answer()));
        checkpoint.put("warnings", sanitizeForResponse(result.warnings()));
        checkpoint.put("cards", sanitizeForResponse(result.cards()));
        checkpoint.put("toolExecutions", sanitizeForResponse(state.value("toolExecutions", List.of())));
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
                result.warnings()
        );
    }

    private List<Object> appendTraceEvent(List<Object> traceEvents, Map<String, Object> traceEvent) {
        List<Object> appended = new ArrayList<>(traceEvents);
        appended.add(traceEvent);
        return appended;
    }

    private Map<String, Object> traceEvent(String traceId, String nodeName, String status, long startEpochMs, String error) {
        long endEpochMs = System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("traceId", traceId);
        event.put("nodeName", nodeName);
        event.put("status", status);
        event.put("timing", Map.of(
                "startEpochMs", startEpochMs,
                "endEpochMs", endEpochMs,
                "durationMs", Math.max(0L, endEpochMs - startEpochMs)
        ));
        if (error != null && !error.isBlank()) {
            event.put("error", error);
        }
        return event;
    }

    private AgentRunResult fallbackResult(SecurityRiskGraphRequest request, String answer, String warning) {
        return new AgentRunResult(
                request.sessionId(),
                request.traceId(),
                sanitizer.sanitizeText(answer),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(traceEvent(request.traceId(), "graph_execution", "failed", System.currentTimeMillis(), sanitizer.sanitizeText(warning))),
                List.of(sanitizer.sanitizeText(warning))
        );
    }

    @FunctionalInterface
    private interface GraphNode {

        Map<String, Object> apply(OverAllState state) throws Exception;
    }

}
