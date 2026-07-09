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
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmApiKeyNotConfiguredException;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClientException;
import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DefaultSecurityRiskGraphExecutor implements SecurityRiskGraphExecutor {

    private static final String GRAPH_NAME = "security-risk-graph";
    private static final String GRAPH_VERSION = "v1";
    private static final String SYSTEM_PROMPT = """
            You are the Security Risk Agent for a short-link admin console.
            Use only the sanitized tool and risk signal context as evidence.
            Do not expose raw IP addresses, raw user identifiers, secrets, tokens, or database connection strings.
            Do not claim that suspicious traffic is definitely an attack; explain confidence and possible false positives.
            Do not execute write actions directly. Recommend review actions only.
            Respond in the user's language unless the user explicitly asks otherwise.
            """;
    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String RISK_SCORING_NODE = "risk_scoring";
    private static final String LLM_EXPLANATION_NODE = "llm_explanation";
    private static final String RESPONSE_COMPOSE_NODE = "response_compose";
    private static final String CHECKPOINT_SAVE_NODE = "checkpoint_save";
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("(gid|fullShortUrl|startDate|endDate|current|size)\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmChatClient llmChatClient;
    private final GraphCheckpointStore checkpointStore;
    private final AgentProperties agentProperties;
    private final AgentToolRegistry toolRegistry;
    private final SecurityRiskCardFactory cardFactory = new SecurityRiskCardFactory();
    private final CompiledGraph graph;

    public DefaultSecurityRiskGraphExecutor(
            LlmChatClient llmChatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry
    ) {
        this.llmChatClient = llmChatClient;
        this.checkpointStore = checkpointStore;
        this.agentProperties = agentProperties;
        this.toolRegistry = toolRegistry;
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
        return Map.of(
                "graphName", GRAPH_NAME,
                "graphVersion", GRAPH_VERSION,
                "visitedNodes", List.of(INTAKE_NODE)
        );
    }

    private Map<String, Object> planAndExecuteTools(OverAllState state) {
        String message = state.value("message", "");
        String sessionId = state.value("sessionId", "");
        String username = state.value("username", "");
        List<Map<String, Object>> toolExecutions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ToolInvocation invocation : planToolInvocations(message)) {
            Optional<AgentTool> toolOptional = toolRegistry.findByName(invocation.name());
            if (toolOptional.isEmpty()) {
                warnings.add("Agent tool not registered: " + invocation.name());
                continue;
            }
            toolExecutions.add(executeTool(toolOptional.get(), invocation, sessionId, username));
        }
        return Map.of(
                "toolExecutions", toolExecutions,
                "toolWarnings", warnings,
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE)
        );
    }

    private Map<String, Object> executeTool(AgentTool tool, ToolInvocation invocation, String sessionId, String username) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", invocation.name());
        execution.put("arguments", invocation.arguments());
        try {
            ToolResult result = tool.execute(new ToolContext(sessionId, username, invocation.arguments()));
            execution.put("success", result.success());
            if (result.success()) {
                execution.put("data", result.data());
            } else {
                execution.put("message", result.message());
            }
        } catch (Exception ex) {
            execution.put("success", false);
            execution.put("message", ex.getMessage());
        }
        return execution;
    }

    private List<ToolInvocation> planToolInvocations(String message) {
        Map<String, Object> arguments = extractArguments(message);
        boolean hasGid = arguments.containsKey("gid");
        boolean hasFullShortUrl = arguments.containsKey("fullShortUrl");
        boolean hasDateRange = arguments.containsKey("startDate") && arguments.containsKey("endDate");
        if (!hasGid || !hasDateRange) {
            return List.of();
        }
        List<ToolInvocation> invocations = new ArrayList<>();
        invocations.add(new ToolInvocation(hasFullShortUrl ? "get_short_link_stats" : "get_group_stats", arguments));
        if (wantsAccessRecords(message)) {
            invocations.add(new ToolInvocation("get_group_access_records", arguments));
        }
        return invocations;
    }

    private boolean wantsAccessRecords(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.contains("access")
                || normalized.contains("record")
                || normalized.contains("\u8BBF\u95EE")
                || normalized.contains("\u660E\u7EC6");
    }

    private Map<String, Object> extractArguments(String message) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (message == null || message.isBlank()) {
            return arguments;
        }
        Matcher keyValueMatcher = KEY_VALUE_PATTERN.matcher(message);
        while (keyValueMatcher.find()) {
            putArgument(arguments, keyValueMatcher.group(1), keyValueMatcher.group(2));
        }
        if (!arguments.containsKey("startDate") || !arguments.containsKey("endDate")) {
            Matcher dateMatcher = DATE_PATTERN.matcher(message);
            List<String> dates = new ArrayList<>();
            while (dateMatcher.find()) {
                dates.add(dateMatcher.group());
            }
            if (dates.size() >= 2) {
                arguments.putIfAbsent("startDate", dates.get(0));
                arguments.putIfAbsent("endDate", dates.get(1));
            }
        }
        return arguments;
    }

    private void putArgument(Map<String, Object> arguments, String name, String value) {
        String safeValue = sanitizeArgumentValue(value);
        if ("current".equals(name) || "size".equals(name)) {
            try {
                arguments.put(name, Long.parseLong(safeValue));
            } catch (NumberFormatException ex) {
                arguments.put(name, safeValue);
            }
            return;
        }
        arguments.put(name, safeValue);
    }

    private String sanitizeArgumentValue(String value) {
        String sanitized = value == null ? "" : value.trim();
        while (!sanitized.isEmpty() && isTrailingArgumentPunctuation(sanitized.charAt(sanitized.length() - 1))) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }

    private boolean isTrailingArgumentPunctuation(char value) {
        return value == '.'
                || value == ';'
                || value == '\u3002'
                || value == '\uFF1B';
    }

    private Map<String, Object> scoreRisk(OverAllState state) {
        List<Map<String, Object>> toolExecutions = state.value("toolExecutions", List.of());
        List<Object> riskCards = cardFactory.build(toolExecutions);
        return Map.of(
                "riskCards", riskCards,
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE)
        );
    }

    private Map<String, Object> explainWithLlm(OverAllState state) {
        List<String> warnings = new ArrayList<>(state.value("toolWarnings", List.of()));
        Map<String, Object> llmDataSource = Map.of();
        String answer;
        try {
            DeepSeekChatResponse chatResponse = llmChatClient.chat(new DeepSeekChatRequest(
                    List.of(
                            new DeepSeekChatRequest.Message("system", SYSTEM_PROMPT),
                            new DeepSeekChatRequest.Message("user", userPrompt(state))
                    ),
                    null,
                    null,
                    null
            ));
            answer = cardFactory.sanitizeText(chatResponse.content());
            llmDataSource = Map.of(
                    "type", "llm",
                    "provider", "deepseek",
                    "model", chatResponse.model(),
                    "finishReason", chatResponse.finishReason()
            );
        } catch (LlmApiKeyNotConfiguredException ex) {
            answer = "Security risk agent is ready, but DeepSeek API key is not configured.";
            warnings.add(cardFactory.sanitizeText(ex.getMessage()));
        } catch (LlmChatClientException ex) {
            answer = "DeepSeek API request failed. Please check provider connectivity and configuration.";
            warnings.add(cardFactory.sanitizeText(ex.getMessage()));
        }
        return Map.of(
                "answer", answer,
                "llmDataSource", llmDataSource,
                "warnings", warnings,
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE, LLM_EXPLANATION_NODE)
        );
    }

    private String userPrompt(OverAllState state) {
        String message = cardFactory.sanitizeText(state.value("message", ""));
        List<Map<String, Object>> toolExecutions = state.value("toolExecutions", List.of());
        List<Object> riskCards = state.value("riskCards", List.of());
        StringBuilder prompt = new StringBuilder(message);
        if (!toolExecutions.isEmpty()) {
            prompt.append("\n\nSanitized tool context:\n")
                    .append(toJson(sanitizeForResponse(toolExecutions)));
        }
        if (!riskCards.isEmpty()) {
            prompt.append("\n\nRisk signal context:\n")
                    .append(toJson(sanitizeForResponse(riskCards)));
        }
        return prompt.toString();
    }

    private Map<String, Object> composeResponse(OverAllState state) {
        List<String> nodes = List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE, LLM_EXPLANATION_NODE, RESPONSE_COMPOSE_NODE);
        List<Object> cards = state.value("riskCards", List.of());
        return Map.of(
                "cards", sanitizeForResponse(cards),
                "pendingActions", pendingActions(cards),
                "toolCalls", sanitizeForResponse(state.value("toolExecutions", List.of())),
                "dataSources", sanitizeForResponse(dataSources(state, nodes)),
                "visitedNodes", nodes
        );
    }

    private List<Object> pendingActions(List<Object> cards) {
        if (hasHighRiskCard(cards)) {
            return List.of(Map.of(
                    "type", "review_security_risk",
                    "title", "Review high risk traffic signal",
                    "status", "pending_confirmation"
            ));
        }
        return List.of();
    }

    private boolean hasHighRiskCard(List<Object> cards) {
        return cards.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(card -> "high".equalsIgnoreCase(String.valueOf(card.get("riskLevel"))));
    }

    private List<Object> dataSources(OverAllState state, List<String> nodes) {
        List<Object> dataSources = new ArrayList<>();
        dataSources.add(Map.of(
                "type", "graph",
                "name", GRAPH_NAME,
                "version", GRAPH_VERSION,
                "nodes", nodes
        ));
        Map<String, Object> llmDataSource = state.value("llmDataSource", Map.of());
        if (!llmDataSource.isEmpty()) {
            dataSources.add(llmDataSource);
        }
        List<Map<String, Object>> toolExecutions = state.value("toolExecutions", List.of());
        if (!toolExecutions.isEmpty()) {
            dataSources.add(Map.of(
                    "type", "tool",
                    "executions", sanitizeForResponse(toolExecutions)
            ));
        }
        return dataSources;
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
        checkpoint.put("answer", cardFactory.sanitizeText(result.answer()));
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
        return cardFactory.sanitizeForPrompt(value);
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
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
                cardFactory.sanitizeText(answer),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(traceEvent(request.traceId(), "graph_execution", "failed", System.currentTimeMillis(), cardFactory.sanitizeText(warning))),
                List.of(cardFactory.sanitizeText(warning))
        );
    }

    @FunctionalInterface
    private interface GraphNode {

        Map<String, Object> apply(OverAllState state) throws Exception;
    }

    private record ToolInvocation(String name, Map<String, Object> arguments) {
    }
}
