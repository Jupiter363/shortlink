package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirective;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirectiveParser;
import com.nageoffer.shortlink.agent.securityriskagent.evidence.RiskEvidenceClassifier;
import com.nageoffer.shortlink.agent.securityriskagent.evidence.RiskEvidenceStatus;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskToolInvocation;
import com.nageoffer.shortlink.agent.securityriskagent.safety.RiskToolStateSanitizer;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RiskToolPlanningNode {

    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String TOOL_ARGUMENTS_STATE_SCOPE = "tool_arguments";
    private static final String TOOL_EXECUTION_FAILED_MESSAGE = "Agent tool execution failed";
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("(gid|fullShortUrl|startDate|endDate|current|size)\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ACTION_DIRECTIVE_PATTERN = Pattern.compile(
            "(?i)(?:^|\\s)action\\s*="
    );
    private static final Set<String> TOOL_ARGUMENT_KEYS = Set.of(
            "gid",
            "fullShortUrl",
            "startDate",
            "endDate",
            "current",
            "size"
    );

    private final AgentToolRegistry toolRegistry;

    private final SecurityRiskSanitizer sanitizer;

    private final RiskToolStateSanitizer toolStateSanitizer;

    private final RiskEvidenceClassifier evidenceClassifier;

    private final RiskManualActionDirectiveParser manualActionDirectiveParser;

    public RiskToolPlanningNode(
            AgentToolRegistry toolRegistry,
            SecurityRiskSanitizer sanitizer,
            RiskToolStateSanitizer toolStateSanitizer
    ) {
        this.toolRegistry = toolRegistry;
        this.sanitizer = sanitizer;
        this.toolStateSanitizer = toolStateSanitizer;
        this.evidenceClassifier = new RiskEvidenceClassifier();
        this.manualActionDirectiveParser = new RiskManualActionDirectiveParser();
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
        Optional<RiskManualActionDirective> manualActionDirective = structuredBatch
                ? Optional.empty()
                : parseManualActionDirective(message);
        boolean hasProfileContext = profileContext != null && !profileContext.isEmpty();
        if (hasProfileContext) {
            Map<String, Object> profileExecution = sanitizedProfileExecution(profileContext);
            toolExecutions.add(profileExecution);
            if (!Boolean.TRUE.equals(profileExecution.get("success"))) {
                warnings.add(toolFailureWarning("risk_profile_context", profileExecution.get("message")));
            }
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
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("toolExecutions", toolExecutions);
        output.put("toolWarnings", warnings);
        output.put("evidenceRequested", evidenceRequested);
        output.put("evidenceStatus", evidenceStatus.name());
        output.put("visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE));
        manualActionDirective.ifPresent(value -> output.put("manualActionDirective", value));
        return output;
    }

    private Optional<RiskManualActionDirective> parseManualActionDirective(String message) {
        if (message == null
                || message.isBlank()
                || !ACTION_DIRECTIVE_PATTERN.matcher(message).find()) {
            return Optional.empty();
        }
        List<String> directiveTokens = new ArrayList<>();
        for (String token : message.strip().split("\\s+")) {
            int separator = token.indexOf('=');
            if (separator < 0) {
                if (isDirectiveKey(token)) {
                    directiveTokens.add(token);
                }
                continue;
            }
            String key = token.substring(0, separator);
            if (!TOOL_ARGUMENT_KEYS.contains(key)) {
                directiveTokens.add(token);
            }
        }
        return manualActionDirectiveParser.parse(String.join(" ", directiveTokens));
    }

    private boolean isDirectiveKey(String token) {
        return "action".equals(token)
                || "timezone".equals(token)
                || "allowedWindows".equals(token);
    }

    private Map<String, Object> executeTool(AgentTool tool, RiskToolInvocation invocation, String sessionId, String username) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", invocation.name());
        try {
            execution.put("arguments", sanitizeArguments(invocation));
            ToolResult result = tool.execute(new ToolContext(sessionId, username, invocation.arguments()));
            if (result.success()) {
                Object safeData = toolStateSanitizer.sanitize(invocation.name(), result.data());
                execution.put("success", true);
                execution.put("data", safeData);
            } else {
                execution.put("success", false);
                execution.put("message", sanitizer.sanitizeText(result.message()));
            }
        } catch (Exception ignored) {
            execution.remove("data");
            execution.putIfAbsent("arguments", Map.of());
            execution.put("success", false);
            execution.put("message", TOOL_EXECUTION_FAILED_MESSAGE);
        }
        return execution;
    }

    private Map<String, Object> failedExecution(RiskToolInvocation invocation, String message) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", invocation.name());
        try {
            execution.put("arguments", sanitizeArguments(invocation));
        } catch (Exception ignored) {
            execution.put("arguments", Map.of());
        }
        execution.put("success", false);
        execution.put("message", sanitizer.sanitizeText(message));
        return execution;
    }

    private Map<String, Object> sanitizedProfileExecution(ProfileRiskAnalysisContext profileContext) {
        try {
            Object safeExecution = toolStateSanitizer.sanitize(
                    "risk_profile_context",
                    profileContext.toToolExecution()
            );
            if (safeExecution instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                map.forEach((key, value) -> typed.put(String.valueOf(key), value));
                return typed;
            }
        } catch (Exception ignored) {
            // Fall through to a fixed safe failure envelope.
        }
        return Map.of(
                "name", "risk_profile_context",
                "arguments", Map.of(),
                "success", false,
                "message", TOOL_EXECUTION_FAILED_MESSAGE
        );
    }

    private Map<String, Object> sanitizeArguments(RiskToolInvocation invocation) {
        Object safeArguments = toolStateSanitizer.sanitize(TOOL_ARGUMENTS_STATE_SCOPE, invocation.arguments());
        if (!(safeArguments instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        map.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return typed;
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
