package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import java.util.*;
import java.util.regex.Pattern;

/** Only query selections and server-issued continuation references survive a turn, never results. */
final class CampaignConversation {
    private static final Pattern SCOPE = Pattern.compile(
            "(gid|fullShortUrl|startDate|endDate)\\s*[:=：]\\s*([^\\s,;，；]+)");
    private static final Pattern SELECTED_SCOPE = Pattern.compile(
            "(?isu)^\\s*分析范围\\s*[:：]\\s*分组\\s*[「“\"]([^」”\"]+)[」”\"]"
                    + "\\s*[;；]?\\s*gid\\s*[:=：]\\s*([^\\s;；]+)\\s*[;；]?\\s*");

    private CampaignConversation() {}

    static Optional<CampaignAnalysisPlanner.Plan> continuation(String message, Map<String, Object> previous) {
        var selected = SELECTED_SCOPE.matcher(Objects.toString(message, ""));
        if (selected.find()) {
            // The unchanged UI default must not replace an explicitly chosen multi-object plan.
            if (!Objects.equals(selected.group(2), previous.get("selectedGid"))) {
                String body = message.substring(selected.end());
                if (Pattern.compile("(?i)下一页|next page|查看.*结果|继续(?:查询|分析)|查询进度|check (?:result|job)")
                        .matcher(body).find()) return Optional.of(empty(Map.of(),
                                "分析分组已切换；请先查询新范围，未复用上一分组的结果引用。"));
                return Optional.empty();
            }
            message = message.substring(selected.end());
        }
        final String queryMessage = message;
        String text = Objects.toString(message, "").toLowerCase(Locale.ROOT);
        boolean next = text.contains("下一页") || text.contains("next page");
        boolean check = text.contains("查看分析结果") || text.contains("查看结果")
                || text.contains("结果出来") || text.contains("查询进度")
                || text.contains("check result") || text.contains("check job")
                || text.contains("继续查询") || text.contains("继续分析")
                || Pattern.compile("(?:查看|查询|读取|检查)(?:分析|统计|排名|排行|对比|下钻)*(?:结果|进度)")
                        .matcher(text).find();
        if (!next && !check) return Optional.empty();
        if (check && !next && Pattern.compile("(?iu)(?:startDate|endDate)\\s*[:=：]|今天|今日|昨天|昨日|本周|上周|最近|过去|[0-9]{4}-[0-9]{2}-[0-9]{2}|\\b(?:today|yesterday|last|past|this week|previous week)\\b")
                .matcher(text).find()) return Optional.empty();
        // A continuation phrase can introduce a new query. Its changed business options must
        // reach the ordinary planner rather than silently replaying a frozen job's old options.
        if (hasNewAnalysisOptions(message)) return Optional.empty();
        // Explicit references are handled by the ordinary planner, with current authorization.
        if (Pattern.compile("(?i)\\bjobId\\s*[:=]").matcher(message).find()) return Optional.empty();
        var operations = maps(previous.get("operations"));
        if (check && !operations.isEmpty()) {
            var matching = operations.stream()
                    .filter(operation -> matches(queryMessage, map(operation.get("arguments"))))
                    .toList();
            if (matching.isEmpty()) return Optional.of(empty(Map.of(),
                    "当前范围没有可续接的分析任务；请先查询该范围。"));
            return Optional.of(new CampaignAnalysisPlanner.Plan(matching.stream().map(operation ->
                    new CampaignAnalysisPlanner.Invocation(operation.get("name").toString(),
                            map(operation.get("arguments")))).toList(), List.of(), previous, false));
        }
        var pages = maps(previous.get("pages"));
        var jobs = maps(previous.get("jobs"));
        List<Map<String, Object>> candidates = next && !pages.isEmpty() ? pages : jobs;
        candidates = candidates.stream().filter(c -> matches(queryMessage, map(c.get("arguments"))))
                .toList();
        if (candidates.size() != 1) {
            if (!next && candidates.isEmpty()) return Optional.empty();
            return Optional.of(empty(candidates.isEmpty() ? Map.of() : previous, candidates.isEmpty()
                    ? "当前范围没有可续接的结果；请先查询该范围，未复用其他范围的游标。"
                    : "上一轮有多个查询结果；请指定要续接的分组和期间。"));
        }
        Map<String, Object> reference = candidates.get(0);
        Map<String, Object> args = new LinkedHashMap<>(map(reference.get("arguments")));
        String name;
        if (reference.containsKey("jobId")) {
            String jobId = reference.get("jobId").toString();
            if (next && reference.containsKey("pageRead")) {
                if (!(reference.get("nextPageIndex") instanceof Number index))
                    return Optional.of(empty(previous, "已到最后一页，没有更多结果。"));
                name = "get_statistics_query_job_page";
                args = new LinkedHashMap<>(Map.of("jobId", jobId, "pageIndex", index.intValue(), "size", 500));
            } else {
                name = "get_statistics_query_job";
                args = new LinkedHashMap<>(Map.of("jobId", jobId));
            }
        } else {
            if (!next) return Optional.empty();
            if (!present(reference.get("nextCursor")) || !present(reference.get("snapshotId")))
                return Optional.of(empty(previous, "已到最后一页，没有更多访问记录。"));
            name = "get_group_access_records";
            args.put("snapshotId", reference.get("snapshotId"));
            args.put("cursor", reference.get("nextCursor"));
            args.put("current", number(args.get("current"), 1) + 1);
        }
        return Optional.of(new CampaignAnalysisPlanner.Plan(
                List.of(new CampaignAnalysisPlanner.Invocation(name, args)), List.of(), previous, false));
    }

    static Map<String, Object> updated(Map<String, Object> selection, Map<String, Object> previous,
            List<Map<String, Object>> executions, boolean continuing) {
        Map<String, Object> result = new LinkedHashMap<>(selection);
        List<Map<String, Object>> pages = new ArrayList<>(continuing ? maps(previous.get("pages")) : List.of());
        List<Map<String, Object>> jobs = new ArrayList<>(continuing ? maps(previous.get("jobs")) : List.of());
        List<Map<String, Object>> operations = new ArrayList<>(continuing ? maps(previous.get("operations")) : List.of());
        for (var execution : executions) {
            if (!Boolean.TRUE.equals(execution.get("success"))) continue;
            String name = Objects.toString(execution.get("name"), "");
            Map<String, Object> args = map(execution.get("arguments"));
            Map<String, Object> data = map(execution.get("data"));
            Map<String, Object> meta = map(data.get("meta"));
            if (Set.of("compare_statistics", "rank_short_links", "get_dimension_breakdown").contains(name)) {
                Map<String, Object> selectionArgs = operationSelection(args);
                operations.removeIf(operation -> name.equals(operation.get("name"))
                        && (Objects.equals(operation.get("selection"), selectionArgs)
                                || Objects.equals(operation.get("arguments"), args)));
                if (data.get("continuation") instanceof Map<?, ?> continuation) {
                    Map<String, Object> canonical = map(continuation);
                    operations.add(Map.of("name", name, "selection", operationSelection(canonical),
                            "arguments", canonical));
                }
                continue;
            }
            if (present(data.get("jobId")) && present(data.get("state"))) {
                String id = data.get("jobId").toString();
                Map<String, Object> old = jobs.stream().filter(j -> id.equals(j.get("jobId")))
                        .findFirst().orElse(Map.of());
                Map<String, Object> ref = new LinkedHashMap<>(old);
                ref.put("jobId", id);
                ref.put("state", data.get("state"));
                if (args.containsKey("gid")) ref.put("arguments", args);
                else ref.putIfAbsent("arguments", Map.of());
                jobs.removeIf(j -> id.equals(j.get("jobId")));
                jobs.add(ref);
            } else if ("get_statistics_query_job_page".equals(name)) {
                String id = Objects.toString(args.get("jobId"), "");
                Map<String, Object> ref = new LinkedHashMap<>(jobs.stream()
                        .filter(j -> id.equals(j.get("jobId"))).findFirst().orElse(Map.of()));
                ref.put("jobId", id);
                ref.putIfAbsent("arguments", Map.of());
                ref.put("pageRead", true);
                ref.put("nextPageIndex", meta.get("nextPageIndex"));
                pages.removeIf(p -> id.equals(p.get("jobId")));
                pages.add(ref);
                jobs.removeIf(j -> id.equals(j.get("jobId")));
                jobs.add(ref);
            } else if ("get_group_access_records".equals(name) && present(meta.get("snapshotId"))) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("arguments", args);
                ref.put("snapshotId", meta.get("snapshotId"));
                ref.put("nextCursor", meta.get("nextCursor"));
                pages.removeIf(p -> sameScope(map(p.get("arguments")), args));
                pages.add(ref);
            }
        }
        result.put("pages", pages);
        result.put("jobs", jobs);
        result.put("operations", operations);
        return result;
    }

    private static boolean matches(String message, Map<String, Object> args) {
        if (!Pattern.compile("\\bgid\\s*[:=：]").matcher(message).find()
                && Pattern.compile("groupName\\s*[:=：]|分组[「“‘\"']|(?:切换|换到).*(?:分组|组)")
                        .matcher(message).find()) return false;
        var explicit = SCOPE.matcher(message);
        while (explicit.find())
            if (!Objects.equals(explicit.group(2), Objects.toString(args.get(explicit.group(1)), ""))) return false;
        // A changed relative/explicit period is a new query, not a continuation of a frozen page.
        String withoutAssignments = SCOPE.matcher(message).replaceAll("");
        return !Pattern.compile("今天|昨天|本周|上周|最近\\s*\\d|\\d{4}-\\d{2}-\\d{2}")
                .matcher(withoutAssignments).find();
    }

    private static boolean hasNewAnalysisOptions(String message) {
        String text = Objects.toString(message, "");
        if (Pattern.compile("(?iu)(?<![a-z0-9_])(?:metric|limit|dimensions|day|hour|weekday|country|province|device|os|browser|isp|refererDomain)\\s*[:=：]")
                .matcher(text).find()) return true;
        if (Pattern.compile("按\\s*[^，,；;。]+(?:下钻|分析|统计|分布|拆分|细分|排序|排名|排行)|只看|仅看|只统计|筛选为|限定为|(?:清除|清空|去掉|取消)(?:筛选|过滤)")
                .matcher(text).find()) return true;
        // Merely asking to see an existing named result does not request a new analysis.
        String withoutScope = SCOPE.matcher(text).replaceAll("").trim();
        if (Pattern.compile("(?:继续|接着)?(?:查看|查询|读取|检查)(?:分析|统计|排名|排行|对比|下钻)*(?:结果|进度)[\\s？?。！!]*")
                .matcher(withoutScope).matches()) return false;
        return Pattern.compile("(?iu)重新|改为|改成|换成|再做|访问趋势|访问明细|访问记录|高峰|短链列表|排名|排行|对比|比较|下钻|前\\s*[0-9]+(?![0-9])(?!\\s*(?:天|日|小时|周|月|年))|\\b(?:pv|uv|uip|top\\s*[0-9]+|compare|ranking|breakdown)\\b")
                .matcher(withoutScope).find();
    }

    private static Map<String, Object> operationSelection(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>(arguments);
        result.remove("jobs");
        result.remove("jobId");
        return result;
    }

    private static boolean sameScope(Map<String, Object> left, Map<String, Object> right) {
        return List.of("gid", "fullShortUrl", "startDate", "endDate").stream()
                .allMatch(k -> Objects.equals(left.get(k), right.get(k)));
    }

    private static CampaignAnalysisPlanner.Plan empty(Map<String, Object> context, String warning) {
        return new CampaignAnalysisPlanner.Plan(List.of(), List.of(warning), context, false);
    }

    static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) source.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(CampaignConversation::map).toList();
    }

    private static boolean present(Object value) { return value != null && !value.toString().isBlank(); }
    private static long number(Object value, long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
}
