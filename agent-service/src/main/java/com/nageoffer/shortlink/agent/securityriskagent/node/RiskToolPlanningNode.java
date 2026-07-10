package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import com.nageoffer.shortlink.agent.securityriskagent.evidence.RiskEvidenceClassifier;
import com.nageoffer.shortlink.agent.securityriskagent.evidence.RiskEvidenceStatus;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskToolInvocation;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RiskToolPlanningNode {

    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("(gid|fullShortUrl|startDate|endDate|current|size)\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final AgentToolRegistry toolRegistry;

    private final SecurityRiskSanitizer sanitizer;

    private final RiskEvidenceClassifier evidenceClassifier;

    public RiskToolPlanningNode(AgentToolRegistry toolRegistry, SecurityRiskSanitizer sanitizer) {
        this.toolRegistry = toolRegistry;
        this.sanitizer = sanitizer;
        this.evidenceClassifier = new RiskEvidenceClassifier();
    }

    public Map<String, Object> apply(OverAllState state) {
        return planAndExecute(
                state.value("message", ""),
                state.value("sessionId", ""),
                state.value("username", ""),
                state.value("profileRiskContext", ProfileRiskAnalysisContext.empty()),
                state.value("analysisInput").isPresent()
        );
    }

    public Map<String, Object> planAndExecute(String message, String sessionId, String username) {
        return planAndExecute(message, sessionId, username, ProfileRiskAnalysisContext.empty(), false);
    }

    public Map<String, Object> planAndExecute(
            String message,
            String sessionId,
            String username,
            ProfileRiskAnalysisContext profileContext
    ) {
        return planAndExecute(message, sessionId, username, profileContext, false);
    }

    private Map<String, Object> planAndExecute(
            String message,
            String sessionId,
            String username,
            ProfileRiskAnalysisContext profileContext,
            boolean structuredBatch
    ) {
        List<Map<String, Object>> toolExecutions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean hasProfileContext = profileContext != null && !profileContext.isEmpty();
        if (hasProfileContext) {
            toolExecutions.add(profileContext.toToolExecution());
        }
        List<RiskToolInvocation> plannedInvocations = structuredBatch
                ? List.of()
                : planToolInvocations(message);
        if (!structuredBatch) {
            for (RiskToolInvocation invocation : plannedInvocations) {
                Optional<AgentTool> toolOptional = toolRegistry.findByName(invocation.name());
                if (toolOptional.isEmpty()) {
                    Map<String, Object> execution = failedExecution(invocation, "Agent tool is not registered");
                    toolExecutions.add(execution);
                    warnings.add(toolFailureWarning(invocation.name(), execution.get("message")));
                    continue;
                }
                Map<String, Object> execution = executeTool(toolOptional.get(), invocation, sessionId, username);
                toolExecutions.add(execution);
                if (!Boolean.TRUE.equals(execution.get("success"))) {
                    warnings.add(toolFailureWarning(invocation.name(), execution.get("message")));
                }
            }
        }
        boolean evidenceRequested = structuredBatch || hasProfileContext || !plannedInvocations.isEmpty();
        RiskEvidenceStatus evidenceStatus = evidenceClassifier.classify(
                evidenceRequested,
                toolExecutions,
                List.of()
        );
        return Map.of(
                "toolExecutions", toolExecutions,
                "toolWarnings", warnings,
                "evidenceRequested", evidenceRequested,
                "evidenceStatus", evidenceStatus.name(),
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE)
        );
    }

    private Map<String, Object> executeTool(AgentTool tool, RiskToolInvocation invocation, String sessionId, String username) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", invocation.name());
        execution.put("arguments", invocation.arguments());
        try {
            ToolResult result = tool.execute(new ToolContext(sessionId, username, invocation.arguments()));
            if (result.success()) {
                execution.put("success", true);
                execution.put("data", result.data());
            } else {
                execution.put("success", false);
                execution.put("message", sanitizer.sanitizeText(result.message()));
            }
        } catch (Exception ex) {
            execution.put("success", false);
            execution.put("message", sanitizer.sanitizeText(ex.getMessage()));
        }
        return execution;
    }

    private Map<String, Object> failedExecution(RiskToolInvocation invocation, String message) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", invocation.name());
        execution.put("arguments", invocation.arguments());
        execution.put("success", false);
        execution.put("message", sanitizer.sanitizeText(message));
        return execution;
    }

    private String toolFailureWarning(String toolName, Object message) {
        String detail = message == null ? "unknown failure" : String.valueOf(message);
        return sanitizer.sanitizeText("Agent tool " + toolName + " failed: " + detail);
    }

    private List<RiskToolInvocation> planToolInvocations(String message) {
        Map<String, Object> arguments = extractArguments(message);
        boolean hasGid = arguments.containsKey("gid");
        boolean hasFullShortUrl = arguments.containsKey("fullShortUrl");
        boolean hasDateRange = arguments.containsKey("startDate") && arguments.containsKey("endDate");
        if (!hasGid || !hasDateRange) {
            return List.of();
        }
        List<RiskToolInvocation> invocations = new ArrayList<>();
        invocations.add(new RiskToolInvocation(hasFullShortUrl ? "get_short_link_stats" : "get_group_stats", arguments));
        if (wantsAccessRecords(message)) {
            invocations.add(new RiskToolInvocation("get_group_access_records", arguments));
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
}
