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

    private static final String SYSTEM_PROMPT = "You are the intelligent campaign delivery and analysis Agent for the short-link admin console. Current phase only performs API integration and safe read-only analysis; never execute write actions directly.";
    private static final String INSIGHT_EXPLANATION_CONTRACT = """
            Insight explanation contract:
            - Use Derived insight context as the factual source for anomaly and performance explanations.
            - Do not recalculate, invent, or overwrite card metrics, thresholds, evidence, type, sourceTool, or reasonCode.
            - For each derived insight, explain possibleCauses, riskLevel, evidenceReferences, and recommendedActions.
            - Keep riskLevel conservative: high only for multiple strong warning signals; otherwise medium or low.
            - recommendedActions must be read-only or low-risk operational suggestions.
            - State that this is not a definitive security conclusion when discussing traffic anomaly cards.
            - Respond in the user's language unless the user explicitly asks otherwise.
            """;
    private static final String INTAKE_NODE = "intake";
    private static final String TOOL_PLANNING_NODE = "tool_planning";
    private static final String LLM_ANALYSIS_NODE = "llm_analysis";
    private static final String RESPONSE_COMPOSE_NODE = "response_compose";
    private static final String CHECKPOINT_SAVE_FAILED_WARNING = "Graph checkpoint save failed";
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("(gid|fullShortUrl|startDate|endDate|current|size|orderTag)\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CampaignInsightCardFactory INSIGHT_CARD_FACTORY = new CampaignInsightCardFactory();

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
        List<ToolInvocation> composableInvocations = planComposableToolInvocations(normalized, arguments, hasGid, hasFullShortUrl, hasDateRange);
        if (!composableInvocations.isEmpty()) {
            return composableInvocations;
        }
        return List.of();
    }

    private List<ToolInvocation> planComposableToolInvocations(
            String normalized,
            Map<String, Object> arguments,
            boolean hasGid,
            boolean hasFullShortUrl,
            boolean hasDateRange
    ) {
        List<ToolInvocation> invocations = new ArrayList<>();
        if (wantsListGroups(normalized, hasGid)) {
            invocations.add(new ToolInvocation("list_groups", Map.of()));
        }
        if (hasGid && wantsShortLinkPage(normalized)) {
            invocations.add(new ToolInvocation("page_short_links", arguments));
        }
        if (hasGid && hasDateRange && wantsStats(normalized)) {
            String toolName = hasFullShortUrl ? "get_short_link_stats" : "get_group_stats";
            invocations.add(new ToolInvocation(toolName, arguments));
        }
        if (hasGid && hasDateRange && wantsAccessRecords(normalized)) {
            invocations.add(new ToolInvocation("get_group_access_records", arguments));
        }
        return invocations;
    }

    private boolean wantsListGroups(String normalized, boolean hasGid) {
        boolean explicitListGroups = containsAny(
                normalized,
                "list groups",
                "show groups",
                "group list",
                "all groups",
                "groups and",
                "groups,"
        );
        return explicitListGroups || (!hasGid && containsAny(normalized, "group", "groups", "gid", "\u5206\u7ec4", "\u6709\u54ea\u4e9b"));
    }

    private boolean wantsShortLinkPage(String normalized) {
        return containsAny(
                normalized,
                "link list",
                "links list",
                "list link",
                "list links",
                "short link list",
                "short links list",
                "link page",
                "links page",
                "page links",
                "page short links",
                "short link page",
                "short links page",
                "link paging",
                "links paging",
                "show links",
                "all links",
                "\u77ed\u94fe\u5217\u8868",
                "\u77ed\u94fe\u63a5\u5217\u8868",
                "\u77ed\u94fe\u5206\u9875",
                "\u77ed\u94fe\u63a5\u5206\u9875"
        );
    }

    private boolean wantsStats(String normalized) {
        return containsAny(normalized, "stats", "statistics", "analysis", "analyze", "performance", "\u7edf\u8ba1", "\u5206\u6790", "\u8868\u73b0", "\u6570\u636e");
    }

    private boolean wantsAccessRecords(String normalized) {
        return containsAny(normalized, "access", "record", "\u8bbf\u95ee", "\u8bb0\u5f55", "\u660e\u7ec6");
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
        warnings.addAll(failedToolWarnings(state.value("toolExecutions", List.of())));
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
        List<Object> derivedInsightCards = INSIGHT_CARD_FACTORY.build(toolExecutions);
        StringBuilder prompt = new StringBuilder(message)
                .append("\n\nTool execution context:\n")
                .append(toJson(INSIGHT_CARD_FACTORY.sanitizeForPrompt(toolExecutions)));
        if (!derivedInsightCards.isEmpty()) {
            prompt.append("\n\nDerived insight context:\n")
                    .append(toJson(INSIGHT_CARD_FACTORY.sanitizeForPrompt(derivedInsightCards)))
                    .append("\n\n")
                    .append(INSIGHT_EXPLANATION_CONTRACT);
        }
        return prompt.toString();
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
                "cards", buildCards(state),
                "pendingActions", List.of(),
                "toolCalls", state.value("toolExecutions", List.of()),
                "dataSources", dataSources(state, nodes),
                "visitedNodes", nodes
        );
    }

    private List<Object> buildCards(OverAllState state) {
        List<Map<String, Object>> toolExecutions = state.value("toolExecutions", List.of());
        List<Object> cards = new ArrayList<>();
        for (Map<String, Object> execution : toolExecutions) {
            cards.add(buildToolCard(execution));
        }
        cards.addAll(INSIGHT_CARD_FACTORY.build(toolExecutions));
        return cards;
    }

    private Map<String, Object> buildToolCard(Map<String, Object> execution) {
        String toolName = textValue(execution.get("name"));
        if (!toolSucceeded(execution)) {
            return toolWarningCard(execution);
        }
        return switch (toolName) {
            case "list_groups" -> groupSummaryCard(execution);
            case "page_short_links" -> shortLinkPageCard(execution);
            case "get_short_link_stats", "get_group_stats" -> statsSummaryCard(execution);
            case "get_group_access_records" -> accessRecordsCard(execution);
            default -> genericToolResultCard(execution);
        };
    }

    private Map<String, Object> groupSummaryCard(Map<String, Object> execution) {
        Object data = execution.get("data");
        List<Object> rows = rowsFrom(data);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("groupCount", rows.size());
        summary.put("shortLinkCount", sumLong(rows, "shortLinkCount", "shortLinkCnt", "linkCount"));

        Map<String, Object> card = baseCard("group_summary", "Short link groups", execution);
        card.put("summary", summary);
        card.put("rows", rows);
        card.put("rawData", data);
        return card;
    }

    private Map<String, Object> shortLinkPageCard(Map<String, Object> execution) {
        Object data = execution.get("data");
        List<Object> rows = rowsFrom(data);
        Map<String, Object> dataMap = mapValue(data);
        Map<String, Object> summary = pagedSummary(rows, dataMap);

        Map<String, Object> card = baseCard("short_link_page", "Short link page", execution);
        card.put("summary", summary);
        card.put("rows", rows);
        card.put("rawData", data);
        return card;
    }

    private Map<String, Object> statsSummaryCard(Map<String, Object> execution) {
        Object data = execution.get("data");
        Map<String, Object> stats = mapValue(data);
        Map<String, Object> metrics = new LinkedHashMap<>();
        putIfPresent(metrics, stats, "pv");
        putIfPresent(metrics, stats, "uv");
        putIfPresent(metrics, stats, "uip");

        Map<String, Object> card = baseCard("stats_summary", "Short link statistics", execution);
        card.put("metrics", metrics);
        card.put("rawData", data);
        return card;
    }

    private Map<String, Object> accessRecordsCard(Map<String, Object> execution) {
        Object data = execution.get("data");
        List<Object> rows = rowsFrom(data);
        Map<String, Object> dataMap = mapValue(data);
        Map<String, Object> summary = pagedSummary(rows, dataMap);

        Map<String, Object> card = baseCard("access_records", "Access records", execution);
        card.put("summary", summary);
        card.put("rows", rows);
        card.put("rawData", data);
        return card;
    }

    private Map<String, Object> pagedSummary(List<Object> rows, Map<String, Object> dataMap) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recordCount", rows.size());
        putIfPresent(summary, dataMap, "total");
        putIfPresent(summary, dataMap, "current");
        putIfPresent(summary, dataMap, "size");
        return summary;
    }

    private Map<String, Object> toolWarningCard(Map<String, Object> execution) {
        Map<String, Object> card = baseCard("tool_warning", "Tool call warning", execution);
        card.put("severity", "warning");
        card.put("message", failureMessage(execution));
        return card;
    }

    private Map<String, Object> genericToolResultCard(Map<String, Object> execution) {
        Map<String, Object> card = baseCard("tool_result", "Tool result", execution);
        card.put("rawData", execution.get("data"));
        return card;
    }

    private Map<String, Object> baseCard(String type, String title, Map<String, Object> execution) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", type);
        card.put("title", title);
        card.put("sourceTool", textValue(execution.get("name")));
        card.put("arguments", mapValue(execution.get("arguments")));
        return card;
    }

    private List<String> failedToolWarnings(List<Map<String, Object>> toolExecutions) {
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> execution : toolExecutions) {
            if (!toolSucceeded(execution)) {
                warnings.add("Tool " + textValue(execution.get("name")) + " failed: " + failureMessage(execution));
            }
        }
        return warnings;
    }

    private boolean toolSucceeded(Map<String, Object> execution) {
        return Boolean.TRUE.equals(execution.get("success"));
    }

    private String failureMessage(Map<String, Object> execution) {
        String message = textValue(execution.get("message"));
        return message.isBlank() ? "unknown error" : message;
    }

    private List<Object> rowsFrom(Object data) {
        if (data instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        Map<String, Object> dataMap = mapValue(data);
        for (String key : List.of("records", "list", "rows")) {
            Object value = dataMap.get(key);
            if (value instanceof List<?> list) {
                return new ArrayList<>(list);
            }
        }
        return List.of();
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private long sumLong(List<Object> rows, String... keys) {
        long sum = 0L;
        for (Object row : rows) {
            Map<String, Object> rowMap = mapValue(row);
            for (String key : keys) {
                Object value = rowMap.get(key);
                if (value != null) {
                    sum += longValue(value);
                    break;
                }
            }
        }
        return sum;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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
