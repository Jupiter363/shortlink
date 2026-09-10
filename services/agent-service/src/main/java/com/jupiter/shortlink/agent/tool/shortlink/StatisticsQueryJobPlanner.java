package com.jupiter.shortlink.agent.tool.shortlink;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic graph routing for explicit job continuation and bounded long-range queries. */
public final class StatisticsQueryJobPlanner {
    private static final Pattern JOB =
            Pattern.compile("(?i)\\bjobId\\s*[:=]\\s*([A-Za-z0-9_-]{1,128})");
    private static final Pattern PAGE = Pattern.compile("(?i)\\bpageIndex\\s*[:=]\\s*([0-9]+)");

    private StatisticsQueryJobPlanner() {}

    public record Plan(String toolName, Map<String, Object> arguments) {}

    public static Optional<String> statusAnswer(List<Map<String, Object>> executions) {
        List<String> answers = new ArrayList<>();
        for (var execution : executions) {
            if (!Boolean.TRUE.equals(execution.get("success"))
                    || !(execution.get("data") instanceof Map<?, ?> data)
                    || !(data.get("jobId") instanceof String jobId)
                    || !(data.get("state") instanceof String state)) continue;
            String detail =
                    switch (state) {
                        case "QUEUED", "RUNNING" -> "PENDING，统计结果尚未完成；可在后续请求查询状态。";
                        case "SUCCEEDED" -> "已完成；使用 pageIndex=0 分页读取结果，保留每页的统计质量和范围。";
                        case "FAILED", "CANCELLED" -> "未生成可用统计结果：" + state + "。";
                        default -> null;
                    };
            if (detail != null) answers.add("统计查询 jobId=" + jobId + " " + detail);
        }
        return answers.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", answers));
    }

    public static Optional<Plan> continuation(String message) {
        var job = JOB.matcher(message == null ? "" : message);
        if (!job.find()) return Optional.empty();
        var page = PAGE.matcher(message);
        if (page.find()) {
            try {
                return Optional.of(
                        new Plan(
                                "get_statistics_query_job_page",
                                Map.of(
                                        "jobId",
                                        job.group(1),
                                        "pageIndex",
                                        Integer.parseInt(page.group(1)),
                                        "size",
                                        500)));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Statistics page index exceeds budget");
            }
        }
        return Optional.of(new Plan("get_statistics_query_job", Map.of("jobId", job.group(1))));
    }

    public static Optional<Plan> longRange(Map<String, Object> arguments, String queryKind) {
        try {
            LocalDate start = LocalDate.parse(String.valueOf(arguments.get("startDate")));
            LocalDate end = LocalDate.parse(String.valueOf(arguments.get("endDate")));
            if (ChronoUnit.DAYS.between(start, end) < 7) return Optional.empty();
        } catch (java.time.format.DateTimeParseException invalid) {
            return Optional.empty();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        for (String key : List.of("gid", "fullShortUrl", "startDate", "endDate"))
            if (arguments.get(key) != null) request.put(key, arguments.get(key));
        request.put("queryKind", queryKind);
        request.put(
                "requestId",
                UUID.nameUUIDFromBytes(request.toString().getBytes(StandardCharsets.UTF_8))
                        .toString());
        return Optional.of(new Plan("submit_statistics_query_job", request));
    }
}
