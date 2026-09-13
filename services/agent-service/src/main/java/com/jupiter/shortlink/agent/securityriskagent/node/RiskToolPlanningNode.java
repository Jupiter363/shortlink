package com.jupiter.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.AgentTool;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.securityriskagent.evidence.RiskEvidenceClassifier;
import com.jupiter.shortlink.agent.securityriskagent.evidence.RiskEvidenceStatus;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskToolInvocation;
import com.jupiter.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
    private static final Pattern KEY_VALUE_PATTERN =
            Pattern.compile(
                    "(gid|fullShortUrl|startDate|endDate|current|size)\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern OPEN_DATE_BEFORE = Pattern.compile(
            "(?iu)(?:(?:截至|截止)(?:到|至|在)?|早于|晚于|从|自从|自|到|至|"
                    + "\\b(?:before|after|since|until|from|through|starting|beginning|as\\s+of|up\\s+to))"
                    + "\\s*[\"'“‘]?\\s*$");
    private static final Pattern OPEN_DATE_AFTER = Pattern.compile(
            "(?iu)^\\s*[\"'”’]?\\s*(?:的\\s*)?(?:(?:当天|当日)\\s*)?(?:(?:及其?|或)?"
                    + "(?:之前|以前|之后|以后|前|后)|起|开始|以来|至今|"
                    + "\\b(?:onwards?|forward|or\\s+(?:earlier|later)|and\\s+(?:earlier|later)))");
    private static final Pattern SINGLE_DAY_BEFORE = Pattern.compile("(?iu)(?:在|\\bon)\\s*[\"'“‘]?\\s*$");
    private static final Pattern SINGLE_DAY_AFTER = Pattern.compile(
            "(?iu)^\\s*[\"'”’]?\\s*(?:当天|当日|这一天|那一天|的\\s*(?:访问|流量|点击|跳转|统计|数据|表现|记录|明细|短链|pv|uv|uip))");

    private final AgentToolRegistry toolRegistry;

    private final SecurityRiskSanitizer sanitizer;

    private final RiskEvidenceClassifier evidenceClassifier;
    private final Clock clock;

    public RiskToolPlanningNode(AgentToolRegistry toolRegistry, SecurityRiskSanitizer sanitizer) {
        this(toolRegistry, sanitizer, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public RiskToolPlanningNode(AgentToolRegistry toolRegistry, SecurityRiskSanitizer sanitizer,
            Clock clock) {
        this.toolRegistry = toolRegistry;
        this.sanitizer = sanitizer;
        this.evidenceClassifier = new RiskEvidenceClassifier();
        this.clock = clock.withZone(ZoneId.of("Asia/Shanghai"));
    }

    public Map<String, Object> apply(OverAllState state) {
        return planAndExecute(
                state.value("message", ""),
                state.value("sessionId", ""),
                state.value("username", ""),
                state.value("profileRiskContext", ProfileRiskAnalysisContext.empty()),
                state.value("analysisInput")
                        .filter(value -> !(value instanceof Map<?, ?> map) || !map.isEmpty())
                        .isPresent(),
                AgentPrincipal.fromState(state.value("principal").orElse(null)),
                state.value("authorizedStatisticsGid", ""),
                state.value("profileScopeStatus", "EXPLICIT"));
    }

    public Map<String, Object> planAndExecute(String message, String sessionId, String username) {
        return planAndExecute(
                message, sessionId, username, ProfileRiskAnalysisContext.empty(), false, null, "", "EXPLICIT");
    }

    public Map<String, Object> planAndExecute(
            String message,
            String sessionId,
            String username,
            ProfileRiskAnalysisContext profileContext) {
        return planAndExecute(message, sessionId, username, profileContext, false, null, "", "EXPLICIT");
    }

    private Map<String, Object> planAndExecute(
            String message,
            String sessionId,
            String username,
            ProfileRiskAnalysisContext profileContext,
            boolean structuredBatch,
            AgentPrincipal principal,
            String authorizedStatisticsGid,
            String profileScopeStatus) {
        List<Map<String, Object>> toolExecutions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean hasProfileContext = profileContext != null && !profileContext.isEmpty();
        if (hasProfileContext) {
            toolExecutions.add(profileContext.toToolExecution());
        }
        List<RiskToolInvocation> plannedInvocations =
                structuredBatch || "MISSING".equals(profileScopeStatus)
                        ? List.of() : planToolInvocations(message, authorizedStatisticsGid, warnings);
        String statisticsScopeNotice = warnings.stream()
                .filter(warning -> warning.startsWith("本轮统计查询范围："))
                .findFirst().orElse("");
        if (!structuredBatch) {
            for (RiskToolInvocation invocation : plannedInvocations) {
                Optional<AgentTool> toolOptional = toolRegistry.findByName(invocation.name());
                if (toolOptional.isEmpty()) {
                    Map<String, Object> execution =
                            failedExecution(invocation, "Agent tool is not registered");
                    toolExecutions.add(execution);
                    warnings.add(toolFailureWarning(invocation.name(), execution.get("message")));
                    continue;
                }
                Map<String, Object> execution =
                        executeTool(toolOptional.get(), invocation, sessionId, username, principal);
                toolExecutions.add(execution);
                if (!Boolean.TRUE.equals(execution.get("success"))) {
                    warnings.add(toolFailureWarning(invocation.name(), execution.get("message")));
                }
            }
        }
        boolean evidenceRequested =
                structuredBatch || hasProfileContext || !plannedInvocations.isEmpty();
        RiskEvidenceStatus evidenceStatus =
                evidenceClassifier.classify(evidenceRequested, toolExecutions, List.of());
        return Map.of(
                "toolExecutions", toolExecutions,
                "toolWarnings", warnings,
                "evidenceRequested", evidenceRequested,
                "evidenceStatus", evidenceStatus.name(),
                "statisticsScopeNotice", statisticsScopeNotice,
                "statisticsEvidenceRequested", !plannedInvocations.isEmpty(),
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE));
    }

    private Map<String, Object> executeTool(
            AgentTool tool,
            RiskToolInvocation invocation,
            String sessionId,
            String username,
            AgentPrincipal principal) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", invocation.name());
        execution.put("arguments", invocation.arguments());
        try {
            ToolResult result =
                    tool.execute(
                            new ToolContext(
                                    sessionId, username, invocation.arguments(), principal));
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

    private List<RiskToolInvocation> planToolInvocations(String message,
            String authorizedStatisticsGid, List<String> warnings) {
        var continuation =
                com.jupiter.shortlink.agent.tool.shortlink.StatisticsQueryJobPlanner.continuation(
                        message);
        if (continuation.isPresent())
            return List.of(
                    new RiskToolInvocation(
                            continuation.get().toolName(), continuation.get().arguments()));
        Map<String, Object> arguments = extractArguments(message);
        boolean resolvedNamedGroup = !arguments.containsKey("gid")
                && authorizedStatisticsGid != null && !authorizedStatisticsGid.isBlank();
        // Only the preceding profile node may resolve a natural-language group. Every tool
        // still reauthorizes this exact scope through Admin using the ordinary principal.
        if (authorizedStatisticsGid != null && !authorizedStatisticsGid.isBlank())
            arguments.put("gid", authorizedStatisticsGid);
        String dateNotice = resolveDateRange(message, arguments, warnings);
        boolean hasGid = arguments.containsKey("gid");
        boolean hasFullShortUrl = arguments.containsKey("fullShortUrl");
        boolean hasDateRange =
                arguments.containsKey("startDate") && arguments.containsKey("endDate");
        if (!hasGid || !hasDateRange) {
            return List.of();
        }
        if (resolvedNamedGroup) {
            // Natural-language discovery starts a bounded first page. Continuation requires
            // an actual snapshot/cursor, never an invented offset from the message.
            arguments.put("current", 1L);
            arguments.put("size", 10L);
        }
        if (!dateNotice.isBlank()) warnings.add(dateNotice);
        List<RiskToolInvocation> invocations = new ArrayList<>();
        var metricsJob =
                com.jupiter.shortlink.agent.tool.shortlink.StatisticsQueryJobPlanner.longRange(
                        arguments, "METRICS");
        invocations.add(
                metricsJob
                        .map(plan -> new RiskToolInvocation(plan.toolName(), plan.arguments()))
                        .orElseGet(
                                () ->
                                        new RiskToolInvocation(
                                                hasFullShortUrl
                                                        ? "get_short_link_stats"
                                                        : "get_group_stats",
                                                arguments)));
        if (wantsAccessRecords(message)) {
            var recordsJob =
                    com.jupiter.shortlink.agent.tool.shortlink.StatisticsQueryJobPlanner.longRange(
                            arguments, "ACCESS_RECORDS");
            invocations.add(
                    recordsJob
                            .map(plan -> new RiskToolInvocation(plan.toolName(), plan.arguments()))
                            .orElseGet(
                                    () ->
                                            new RiskToolInvocation(
                                                    "get_group_access_records", arguments)));
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

    private String resolveDateRange(String message, Map<String, Object> arguments, List<String> warnings) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean rollingDay = false;
        if (arguments.containsKey("startDate") != arguments.containsKey("endDate")) {
            warnings.add("统计时间只提供了单边边界；请补充完整起止日期，未将该日期当作单日查询。");
            return "";
        }
        if (!arguments.containsKey("startDate") && !arguments.containsKey("endDate")) {
            Matcher singleDate = DATE_PATTERN.matcher(normalized);
            if (singleDate.find()) {
                String day = singleDate.group();
                String before = normalized.substring(0, singleDate.start());
                String after = normalized.substring(singleDate.end());
                if (OPEN_DATE_BEFORE.matcher(before).find() || OPEN_DATE_AFTER.matcher(after).find()) {
                    warnings.add("统计时间只提供了单边边界；请补充完整起止日期，未将该日期当作单日查询。");
                    return "";
                }
                if (after.matches("(?s)^(?:t|\\s+)\\d{1,2}:\\d{2}.*")) return "";
                if (!singleDate.find() && (SINGLE_DAY_BEFORE.matcher(before).find()
                        || SINGLE_DAY_AFTER.matcher(after).find() || after.isBlank())) {
                    arguments.put("startDate", day);
                    arguments.put("endDate", day);
                } else return "";
            }
        }
        if (!arguments.containsKey("startDate") && !arguments.containsKey("endDate")) {
            LocalDate today = LocalDate.now(clock);
            if (normalized.matches("(?s).*(?:最近|近|过去)\\s*24\\s*(?:小时|h).*")) {
                arguments.put("startDate", today.minusDays(1).toString());
                arguments.put("endDate", today.toString());
                rollingDay = true;
            } else if (normalized.contains("昨天") || normalized.contains("昨日")) {
                arguments.put("startDate", today.minusDays(1).toString());
                arguments.put("endDate", today.minusDays(1).toString());
            } else if (normalized.contains("今天") || normalized.contains("今日")) {
                arguments.put("startDate", today.toString());
                arguments.put("endDate", today.toString());
            }
        }
        if (!arguments.containsKey("startDate") || !arguments.containsKey("endDate")) return "";
        try {
            LocalDate start = LocalDate.parse(String.valueOf(arguments.get("startDate")));
            LocalDate end = LocalDate.parse(String.valueOf(arguments.get("endDate")));
            if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= 180)
                throw new IllegalArgumentException("Unsupported date range");
            // Explicit key/value requests keep their established behavior and warning shape.
            if (!rollingDay && normalized.contains("startdate") && normalized.contains("enddate")) return "";
            return "本轮统计查询范围：北京时间 " + start + " 00:00:00 至 " + end
                    + " 23:59:59。"
                    + (rollingDay ? "现有工具按自然日查询，覆盖昨日与今日两个自然日，并非精确滚动24小时；" : "")
                    + "数据是否完整、临时及维度未知情况以返回的快照元数据为准；仅作只读说明，不据此执行策略。";
        } catch (RuntimeException invalid) {
            arguments.remove("startDate");
            arguments.remove("endDate");
            warnings.add("统计日期无效或超过180天查询上限；未调用统计工具，请提供明确的有效日期范围。");
            return "";
        }
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
        if (!arguments.containsKey("startDate") && !arguments.containsKey("endDate")) {
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
        while (!sanitized.isEmpty()
                && isTrailingArgumentPunctuation(sanitized.charAt(sanitized.length() - 1))) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }

    private boolean isTrailingArgumentPunctuation(char value) {
        return value == '.' || value == ';' || value == '\u3002' || value == '\uFF1B';
    }
}
