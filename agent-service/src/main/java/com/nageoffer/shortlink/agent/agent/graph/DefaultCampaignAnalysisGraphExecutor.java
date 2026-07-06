package com.nageoffer.shortlink.agent.agent.graph;

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
import com.nageoffer.shortlink.agent.tool.core.AgentTool;
import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;
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
public class DefaultCampaignAnalysisGraphExecutor implements CampaignAnalysisGraphExecutor {

    private static final String SYSTEM_PROMPT = "你是短链接后台的智能投放与分析 Agent。当前阶段只做 API 联调和安全的分析解释，不直接执行写操作。";
    private static final String INTAKE_NODE = "intake";
    private static final String TOOL_PLANNING_NODE = "tool_planning";
    private static final String LLM_ANALYSIS_NODE = "llm_analysis";
    private static final String RESPONSE_COMPOSE_NODE = "response_compose";
    private static final String CHECKPOINT_SAVE_FAILED_WARNING = "Graph checkpoint save failed";
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("(gid|fullShortUrl|startDate|endDate|current|size|orderTag)\\s*[=:：]\\s*([^\\s,，;；]+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LlmChatClient llmChatClient;

    private final GraphCheckpointStore checkpointStore;

    private final AgentProperties agentProperties;

    private final AgentToolRegistry toolRegistry;

    private final CompiledGraph graph;

    public DefaultCampaignAnalysisGraphExecutor(
            LlmChatClient llmChatClient,
            GraphCheckpointStore checkpointStore,
            AgentProperties agentProperties,
            AgentToolRegistry toolRegistry
    ) {
        this.llmChatClient = llmChatClient;
        this.checkpointStore = checkpointStore;
        this.agentProperties = agentProperties;
        this.toolRegistry = toolRegistry;
        this.graph = compileGraph(agentProperties.getGraph().getName());
    }

    @Override
    public AgentRunResult execute(CampaignAnalysisGraphRequest request) {
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
                return fallbackResult(request, "Campaign analysis graph produced no result.", "Graph execution returned empty state");
            }
            AgentRunResult result = toRunResult(request, state.get());
            return saveCheckpointOrWarn(request, state.get(), result);
        } catch (Exception ex) {
            return fallbackResult(request, "Campaign analysis graph failed.", ex.getMessage());
        }
    }

    private CompiledGraph compileGraph(String graphName) {
        try {
            return new StateGraph(graphName, Map::of)
                    .addNode(INTAKE_NODE, AsyncNodeAction.node_async(this::intake))
                    .addNode(TOOL_PLANNING_NODE, AsyncNodeAction.node_async(this::planAndExecuteTools))
                    .addNode(LLM_ANALYSIS_NODE, AsyncNodeAction.node_async(this::analyzeWithLlm))
                    .addNode(RESPONSE_COMPOSE_NODE, AsyncNodeAction.node_async(this::composeResponse))
                    .addEdge(StateGraph.START, INTAKE_NODE)
                    .addEdge(INTAKE_NODE, TOOL_PLANNING_NODE)
                    .addEdge(TOOL_PLANNING_NODE, LLM_ANALYSIS_NODE)
                    .addEdge(LLM_ANALYSIS_NODE, RESPONSE_COMPOSE_NODE)
                    .addEdge(RESPONSE_COMPOSE_NODE, StateGraph.END)
                    .compile();
        } catch (GraphStateException ex) {
            throw new IllegalStateException("Campaign analysis graph initialization failed", ex);
        }
    }

    private Map<String, Object> intake(OverAllState state) {
        return Map.of(
                "graphName", agentProperties.getGraph().getName(),
                "graphVersion", agentProperties.getGraph().getVersion(),
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
                "visitedNodes", List.of(INTAKE_NODE, TOOL_PLANNING_NODE)
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
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean hasGid = arguments.containsKey("gid");
        boolean hasFullShortUrl = arguments.containsKey("fullShortUrl");
        boolean hasDateRange = arguments.containsKey("startDate") && arguments.containsKey("endDate");
        if (hasFullShortUrl && hasGid && hasDateRange && containsAny(normalized, "stats", "统计", "数据", "表现")) {
            return List.of(new ToolInvocation("get_short_link_stats", arguments));
        }
        if (hasGid && hasDateRange && containsAny(normalized, "access", "record", "访问", "记录", "明细")) {
            return List.of(new ToolInvocation("get_group_access_records", arguments));
        }
        if (hasGid && hasDateRange && containsAny(normalized, "stats", "统计", "分析", "表现", "数据")) {
            return List.of(new ToolInvocation("get_group_stats", arguments));
        }
        if (hasGid && containsAny(normalized, "link", "短链", "短链接", "列表", "分页", "page")) {
            return List.of(new ToolInvocation("page_short_links", arguments));
        }
        if (containsAny(normalized, "group", "分组", "gid", "有哪些")) {
            return List.of(new ToolInvocation("list_groups", Map.of()));
        }
        return List.of();
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
        if ("current".equals(name) || "size".equals(name)) {
            try {
                arguments.put(name, Long.parseLong(value));
            } catch (NumberFormatException ex) {
                arguments.put(name, value);
            }
            return;
        }
        arguments.put(name, value);
    }

    private boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> analyzeWithLlm(OverAllState state) {
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
            answer = chatResponse.content();
            llmDataSource = Map.of(
                    "type", "llm",
                    "provider", "deepseek",
                    "model", chatResponse.model(),
                    "finishReason", chatResponse.finishReason()
            );
        } catch (LlmApiKeyNotConfiguredException ex) {
            answer = "Agent service is ready, but DeepSeek API key is not configured.";
            warnings.add(ex.getMessage());
        } catch (LlmChatClientException ex) {
            answer = "DeepSeek API request failed. Please check provider connectivity and configuration.";
            warnings.add(ex.getMessage());
        }

        return Map.of(
                "answer", answer,
                "llmDataSource", llmDataSource,
                "warnings", warnings,
                "visitedNodes", List.of(INTAKE_NODE, TOOL_PLANNING_NODE, LLM_ANALYSIS_NODE)
        );
    }

    private String userPrompt(OverAllState state) {
        String message = state.value("message", "");
        List<Map<String, Object>> toolExecutions = state.value("toolExecutions", List.of());
        if (toolExecutions.isEmpty()) {
            return message;
        }
        return message + "\n\nTool execution context:\n" + toJson(toolExecutions);
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> composeResponse(OverAllState state) {
        List<String> nodes = List.of(INTAKE_NODE, TOOL_PLANNING_NODE, LLM_ANALYSIS_NODE, RESPONSE_COMPOSE_NODE);
        return Map.of(
                "cards", List.of(),
                "pendingActions", List.of(),
                "toolCalls", state.value("toolExecutions", List.of()),
                "dataSources", dataSources(state, nodes),
                "visitedNodes", nodes
        );
    }

    private List<Object> dataSources(OverAllState state, List<String> nodes) {
        List<Object> dataSources = new ArrayList<>();
        dataSources.add(Map.of(
                "type", "graph",
                "name", state.value("graphName", agentProperties.getGraph().getName()),
                "version", state.value("graphVersion", agentProperties.getGraph().getVersion()),
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
                    "executions", toolExecutions
            ));
        }
        return dataSources;
    }

    private AgentRunResult toRunResult(CampaignAnalysisGraphRequest request, OverAllState state) {
        return new AgentRunResult(
                request.sessionId(),
                request.traceId(),
                state.value("answer", ""),
                state.value("cards", List.of()),
                state.value("pendingActions", List.of()),
                state.value("toolCalls", List.of()),
                state.value("dataSources", List.of()),
                state.value("warnings", List.of())
        );
    }

    private AgentRunResult saveCheckpointOrWarn(CampaignAnalysisGraphRequest request, OverAllState state, AgentRunResult result) {
        try {
            saveCheckpoint(request, state, result);
            return result;
        } catch (Exception ex) {
            return withWarning(result, CHECKPOINT_SAVE_FAILED_WARNING);
        }
    }

    private AgentRunResult withWarning(AgentRunResult result, String warning) {
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.add(warning);
        return new AgentRunResult(
                result.sessionId(),
                result.traceId(),
                result.answer(),
                result.cards(),
                result.pendingActions(),
                result.toolCalls(),
                result.dataSources(),
                warnings
        );
    }

    private void saveCheckpoint(CampaignAnalysisGraphRequest request, OverAllState state, AgentRunResult result) {
        if (!agentProperties.getGraph().isCheckpointEnabled()) {
            return;
        }
        checkpointStore.save(new GraphCheckpoint(
                request.sessionId(),
                request.traceId(),
                agentProperties.getGraph().getName(),
                agentProperties.getGraph().getVersion(),
                checkpointJson(request, state, result),
                System.currentTimeMillis(),
                "FINISHED"
        ));
    }

    private String checkpointJson(CampaignAnalysisGraphRequest request, OverAllState state, AgentRunResult result) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("sessionId", request.sessionId());
        checkpoint.put("traceId", request.traceId());
        checkpoint.put("graphName", agentProperties.getGraph().getName());
        checkpoint.put("graphVersion", agentProperties.getGraph().getVersion());
        checkpoint.put("visitedNodes", state.value("visitedNodes", List.of()));
        checkpoint.put("answer", result.answer());
        checkpoint.put("warnings", result.warnings());
        checkpoint.put("toolExecutions", state.value("toolExecutions", List.of()));
        try {
            return OBJECT_MAPPER.writeValueAsString(checkpoint);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Graph checkpoint serialization failed", ex);
        }
    }

    private AgentRunResult fallbackResult(CampaignAnalysisGraphRequest request, String answer, String warning) {
        return new AgentRunResult(
                request.sessionId(),
                request.traceId(),
                answer,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(warning)
        );
    }

    private record ToolInvocation(String name, Map<String, Object> arguments) {
    }
}
