package com.jupiter.shortlink.agent.tool.shortlink;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Computes comparisons and rankings from authorized whole-window aggregates, never daily UV sums. */
@Component
public class CampaignStatisticsTools {
    private static final String BASE = "/internal/short-link-admin/v1/agent-tools";
    private static final String JOBS = BASE + "/statistics/jobs";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PAGES = 10;
    private static final List<String> METRICS = List.of("pv", "uv", "uip");
    private final ShortLinkBusinessGateway gateway;
    private final Clock clock;

    @Autowired
    public CampaignStatisticsTools(ShortLinkBusinessGateway gateway) {
        this(gateway, Clock.system(BUSINESS_ZONE));
    }

    CampaignStatisticsTools(ShortLinkBusinessGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Tool(name = "compare_statistics", description = "Compare two to sixteen owned object/period combinations. "
            + "Uses whole-window click PV and distinct UV/UIP, returns calculated differences and quality limitations. "
            + "For pending jobs, retain the returned continuation arguments and check on a later user request.")
    public ToolResult compareStatistics(
            @ToolParam(description = "Objects: each has gid and optional fullShortUrl and label.") List<Map<String, Object>> scopes,
            @ToolParam(description = "Inclusive calendar periods: startDate, endDate and optional label; first period is the current target.") List<Map<String, Object>> periods,
            @ToolParam(required = false, description = "Exact job references from continuation; never invent scope or jobId.") List<Map<String, Object>> jobs,
            org.springframework.ai.chat.model.ToolContext trusted) {
        try {
            ToolContext context = trustedContext(trusted);
            List<Map<String, Object>> objects = scopes(scopes);
            List<Map<String, Object>> windows = periods(periods);
            int combinations = objects.size() * windows.size();
            require(combinations >= 2 && combinations <= 16, "Comparison requires two to sixteen object/period combinations");
            Map<String, Map<String, Object>> references = references(jobs, objects, windows);
            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> retainedJobs = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            boolean pending = false, failed = false;
            for (var object : objects) for (var window : windows) {
                Map<String, Object> query = query(object, window);
                String key = key(query);
                String jobId = text(references.getOrDefault(key, Map.of()).get("jobId"));
                Fetch fetched = fetch(query, "METRICS", jobId, false, context);
                Map<String, Object> row = new LinkedHashMap<>(query);
                row.put("key", key);
                row.put("status", fetched.status);
                if (object.containsKey("label")) row.put("scopeLabel", object.get("label"));
                if (window.containsKey("label")) row.put("periodLabel", window.get("label"));
                if (!fetched.jobId.isEmpty()) {
                    Map<String, Object> reference = new LinkedHashMap<>(query);
                    reference.put("queryKind", "METRICS");
                    reference.put("jobId", fetched.jobId);
                    retainedJobs.add(reference);
                }
                if ("READY".equals(fetched.status)) {
                    Map<String, Object> summary = summary(fetched.data);
                    if (!validMetrics(summary)) {
                        failed = true;
                        row.put("status", "INCOMPLETE");
                        row.put("warning", "整段期间的 PV、UV、UIP 汇总缺失或无效");
                        warnings.add(key + "：整段期间统计不可用，未将缺失值当作零");
                    } else {
                        for (String metric : METRICS) row.put(metric, number(summary.get(metric)));
                        row.put("quality", map(fetched.data.get("meta")));
                        qualityWarnings(map(fetched.data.get("meta")), warnings, key);
                    }
                } else {
                    pending |= "PENDING".equals(fetched.status);
                    failed |= !"PENDING".equals(fetched.status);
                    row.put("warning", fetched.message);
                    warnings.add(key + ": " + fetched.message);
                }
                rows.add(row);
            }
            String status = failed ? "INCOMPLETE" : pending ? "PENDING" : "READY";
            var result = base("comparison", status, rows, warnings);
            result.put("columns", columns("gid", "startDate", "endDate", "pv", "uv", "uip"));
            result.put("comparisons", comparisons(rows, objects, windows));
            result.put("metrics", Map.of("semantics", "每行对应独立的整段期间汇总，去重 UV、UIP 不可直接相加"));
            result.put("meta", Map.of("businessTimezone", "Asia/Shanghai", "queryCount", combinations,
                    "resultComplete", "READY".equals(status), "rateUnit", "ratio"));
            if (pending) result.put("continuation", Map.of("scopes", objects, "periods", windows, "jobs", retainedJobs));
            return ToolResult.success(result);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.failure(invalid.getMessage());
        }
    }

    @Tool(name = "rank_short_links", description = "Rank all owned short links over an explicit date range by pv, uv or uip. "
            + "Reads the complete frozen result before sorting; never treats one page or summed daily UV as the full ranking. "
            + "Long ranges use LINK_METRICS jobs and return continuation arguments when pending.")
    public ToolResult rankShortLinks(
            @ToolParam(description = "Owned group id.") String gid,
            @ToolParam(description = "Inclusive start date yyyy-MM-dd.") String startDate,
            @ToolParam(description = "Inclusive end date yyyy-MM-dd.") String endDate,
            @ToolParam(description = "Ranking metric: pv, uv or uip.") String metric,
            @ToolParam(description = "Number of displayed links, between 1 and 50.") Integer limit,
            @ToolParam(required = false, description = "Exact LINK_METRICS jobId returned in continuation.") String jobId,
            org.springframework.ai.chat.model.ToolContext trusted) {
        try {
            ToolContext context = trustedContext(trusted);
            require(metric != null && METRICS.contains(metric), "Ranking metric must be pv, uv or uip");
            require(limit != null && limit >= 1 && limit <= 50, "Ranking limit must be between one and fifty");
            var object = scopes(List.of(Map.of("gid", required(gid, "gid")))).get(0);
            var window = periods(List.of(Map.of("startDate", required(startDate, "startDate"),
                    "endDate", required(endDate, "endDate")))).get(0);
            Map<String, Object> query = query(object, window);
            Fetch fetched = fetch(query, "LINK_METRICS", text(jobId), true, context);
            List<String> warnings = new ArrayList<>();
            if (!"READY".equals(fetched.status)) {
                warnings.add(fetched.message);
                var result = base("ranking", fetched.status, List.of(), warnings);
                result.put("metrics", Map.of());
                result.put("meta", Map.of("resultComplete", false, "metric", metric, "limit", limit));
                if ("PENDING".equals(fetched.status)) {
                    Map<String, Object> continuation = new LinkedHashMap<>(query);
                    continuation.put("metric", metric);
                    continuation.put("limit", limit);
                    continuation.put("jobId", fetched.jobId);
                    result.put("continuation", continuation);
                }
                return ToolResult.success(result);
            }
            var summary = summary(fetched.data);
            if (!validMetrics(summary)) return incompleteRanking("整段期间的分组汇总缺失，无法确认完整排名", metric, limit);
            List<Map<String, Object>> items = maps(fetched.data.get("items"));
            Set<String> identities = new LinkedHashSet<>();
            BigDecimal totalPv = BigDecimal.ZERO;
            for (var item : items) {
                if (!validMetrics(item) || text(item.get("linkId")).isEmpty()
                        || !identities.add(text(item.get("linkId"))))
                    return incompleteRanking("短链统计缺失、无效或重复，未输出不完整排名", metric, limit);
                totalPv = totalPv.add(number(item.get("pv")));
            }
            if (totalPv.compareTo(number(summary.get("pv"))) != 0)
                return incompleteRanking("短链 PV 合计与冻结分组汇总不一致，未输出不完整排名", metric, limit);
            items.sort(Comparator.<Map<String, Object>, BigDecimal>comparing(row -> number(row.get(metric))).reversed()
                    .thenComparing(row -> text(row.get("linkId"))));
            var quality = map(fetched.data.get("meta"));
            // All frozen pages have been validated and collected before ranking.
            quality.remove("nextCursor");
            quality.remove("nextPageIndex");
            qualityWarnings(quality, warnings, "ranking");
            var rowQuality = new LinkedHashMap<>(quality);
            // Shared provenance remains once in result.meta for the graph boundary.
            // Keep row quality and unknown extensions without repeating each build window.
            rowQuality.keySet().removeAll(List.of("sourceCut", "manifestVersion", "windowVersions"));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int index = 0; index < Math.min(limit, items.size()); index++) {
                Map<String, Object> row = new LinkedHashMap<>(items.get(index));
                row.putAll(query);
                row.put("rank", index + 1);
                row.put("pvShare", ratio(number(row.get("pv")), totalPv));
                row.put("quality", rowQuality);
                rows.add(row);
            }
            var result = base("ranking", "READY", rows, warnings);
            result.put("columns", columns("rank", "fullShortUrl", "pv", "uv", "uip", "pvShare"));
            result.put("metrics", summary);
            Map<String, Object> meta = new LinkedHashMap<>(quality);
            meta.put("metric", metric);
            meta.put("limit", limit);
            meta.put("rankedCount", rows.size());
            meta.put("totalLinks", items.size());
            meta.put("resultComplete", true);
            meta.put("rankingBasis", "OBSERVED_WHOLE_WINDOW_METRICS");
            result.put("meta", meta);
            return ToolResult.success(result);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.failure(invalid.getMessage());
        }
    }

    Fetch fetchDimensions(Map<String, Object> query, String jobId, ToolContext context) {
        return fetch(query, "DIMENSION_BREAKDOWN", jobId, true, context);
    }

    private Fetch fetch(Map<String, Object> query, String kind, String jobId, boolean allPages, ToolContext context) {
        if (!jobId.isEmpty()) {
            if (!jobId.matches("[A-Za-z0-9_-]{1,128}")) return Fetch.failed("统计任务引用无效", jobId);
            return job(query, kind, jobId, allPages, context, null);
        }
        long days = ChronoUnit.DAYS.between(LocalDate.parse(text(query.get("startDate"))), LocalDate.parse(text(query.get("endDate")))) + 1;
        if (days > 7) return submit(query, kind, allPages, context);
        Map<String, Object> arguments = new LinkedHashMap<>(query);
        String path = "DIMENSION_BREAKDOWN".equals(kind) ? BASE + "/statistics/dimensions"
                : "LINK_METRICS".equals(kind) ? BASE + "/statistics/link-metrics"
                : query.containsKey("fullShortUrl") ? BASE + "/short-link/stats" : BASE + "/group/stats";
        if (allPages) arguments.put("pageSize", 500);
        ToolResult response = "DIMENSION_BREAKDOWN".equals(kind)
                ? safe(gateway.post(path, bound(context, arguments), arguments)) : get(path, arguments, context);
        if (!response.success()) {
            if (text(response.message()).contains("TOO_LARGE")) return submit(query, kind, allPages, context);
            return Fetch.failed(text(response.message()), "");
        }
        Map<String, Object> data = map(response.data());
        if (!allPages) return new Fetch("READY", data, "", "");
        return readPages(data, query, kind, "", path, context);
    }

    private Fetch submit(Map<String, Object> query, String kind, boolean allPages, ToolContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>(query);
        arguments.put("queryKind", kind);
        // A fresh analysis must obtain a new snapshot; later turns reuse the returned jobId.
        arguments.put("requestId", UUID.randomUUID().toString());
        ToolResult submitted = safe(gateway.post(JOBS, bound(context, arguments), arguments));
        if (!submitted.success()) return Fetch.failed(text(submitted.message()), "");
        var status = map(submitted.data());
        String jobId = text(status.get("jobId"));
        if (!jobId.matches("[A-Za-z0-9_-]{1,128}")) return Fetch.failed("任务提交未返回有效 jobId", "");
        return job(query, kind, jobId, allPages, context, status);
    }

    private Fetch job(Map<String, Object> query, String kind, String jobId, boolean allPages,
            ToolContext context, Map<String, Object> submittedStatus) {
        Map<String, Object> status = submittedStatus;
        if (status == null) {
            ToolResult checked = get(JOBS + "/" + jobId, Map.of(), context);
            if (!checked.success()) return Fetch.failed(text(checked.message()), jobId);
            status = map(checked.data());
        }
        if (!jobId.equals(text(status.get("jobId")))) return Fetch.failed("统计任务标识不一致", jobId);
        String state = text(status.get("state"));
        if (Set.of("QUEUED", "RUNNING").contains(state))
            return new Fetch("PENDING", Map.of(), jobId, "PENDING：统计结果尚未完成，未推测指标值");
        if (!"SUCCEEDED".equals(state)) return Fetch.failed("统计任务没有可用结果：" + state, jobId);
        ToolResult response = get(JOBS + "/" + jobId + "/page", Map.of("pageIndex", 0, "size", 500), context);
        if (!response.success()) return Fetch.failed(text(response.message()), jobId);
        var data = map(response.data());
        if (!matchesJob(map(data.get("meta")), query, kind, jobId))
            return Fetch.failed("任务结果无法证明与指定对象、查询类型及期间一致", jobId);
        return allPages ? readPages(data, query, kind, jobId, "", context) : new Fetch("READY", data, jobId, "");
    }

    private Fetch readPages(Map<String, Object> first, Map<String, Object> query, String kind,
            String jobId, String path, ToolContext context) {
        Map<String, Object> initialMeta = map(first.get("meta"));
        String snapshot = text(initialMeta.get("snapshotId"));
        if (snapshot.isEmpty() || !(first.get("items") instanceof List<?>))
            return Fetch.failed("冻结快照或结果行缺失", jobId);
        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, Object> page = first;
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < MAX_PAGES; index++) {
            Map<String, Object> meta = map(page.get("meta"));
            if (!snapshot.equals(text(meta.get("snapshotId")))
                    || !sameSnapshot(initialMeta, meta)
                    || !Objects.equals(first.get("metrics"), page.get("metrics"))
                    || !(page.get("items") instanceof List<?> values)
                    || values.stream().anyMatch(value -> !(value instanceof Map<?, ?>)))
                return Fetch.failed("分页期间快照或整段汇总发生变化", jobId);
            if (!jobId.isEmpty() && !matchesJob(meta, query, kind, jobId))
                return Fetch.failed("任务结果页与当前指定范围不一致", jobId);
            if ("DIMENSION_BREAKDOWN".equals(kind) && !DimensionQuery.matches(meta, query))
                return Fetch.failed("结果页的联合维度或筛选条件与当前请求不一致", jobId);
            all.addAll(maps(page.get("items")));
            if (all.size() > MAX_PAGES * 500)
                return Fetch.failed("结果超过 5000 行完整收集范围，未返回不完整结果", jobId);
            Object next = jobId.isEmpty() ? meta.get("nextCursor") : meta.get("nextPageIndex");
            if (next == null || text(next).isEmpty()) {
                if (Boolean.TRUE.equals(meta.get("hasMore")) || Boolean.TRUE.equals(page.get("hasMore")))
                    return Fetch.failed("结果提示还有数据，但缺少续页引用", jobId);
                if (meta.get("totalRows") instanceof Number total && total.longValue() != all.size())
                    return Fetch.failed("完整结果行数与冻结元数据不一致", jobId);
                Map<String, Object> complete = new LinkedHashMap<>(first);
                complete.put("items", all);
                return new Fetch("READY", complete, jobId, "");
            }
            if (index + 1 == MAX_PAGES) return Fetch.failed("结果超过 10 页完整收集范围，未将已读页面当作完整结果", jobId);
            if (!seen.add(text(next))) return Fetch.failed("续页引用重复，无法确认已读取完整结果", jobId);
            Map<String, Object> arguments = new LinkedHashMap<>();
            String nextPath;
            if (jobId.isEmpty()) {
                arguments.putAll(query);
                arguments.put("snapshotId", snapshot);
                arguments.put("cursor", text(next));
                arguments.put("pageSize", 500);
                nextPath = path;
            } else {
                Integer pageIndex = exactPageIndex(next);
                if (pageIndex == null || pageIndex <= 0) return Fetch.failed("下一页索引无效", jobId);
                arguments.put("pageIndex", pageIndex);
                arguments.put("size", 500);
                nextPath = JOBS + "/" + jobId + "/page";
            }
            ToolResult response = "DIMENSION_BREAKDOWN".equals(kind) && jobId.isEmpty()
                    ? safe(gateway.post(nextPath, bound(context, arguments), arguments)) : get(nextPath, arguments, context);
            if (!response.success()) return Fetch.failed(text(response.message()), jobId);
            page = map(response.data());
        }
        return Fetch.failed("分页结果不完整", jobId);
    }

    private static boolean matchesJob(Map<String, Object> meta, Map<String, Object> query, String kind, String jobId) {
        Map<String, Object> proof = new LinkedHashMap<>(meta);
        proof.putAll(map(meta.get("queryScope")));
        if (!jobId.equals(text(meta.get("snapshotId"))) || !kind.equals(text(proof.get("queryKind")))
                || !text(query.get("gid")).equals(text(proof.get("gid")))) return false;
        long start = LocalDate.parse(text(query.get("startDate"))).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        long end = LocalDate.parse(text(query.get("endDate"))).plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        if (!epochEquals(proof.getOrDefault("requestedStart", proof.get("startInclusive")), start)
                || !epochEquals(proof.getOrDefault("requestedEnd", proof.get("endExclusive")), end)) return false;
        if ("DIMENSION_BREAKDOWN".equals(kind) && !DimensionQuery.matches(meta, query)) return false;
        String requestedUrl = normalizeUrl(query.get("fullShortUrl"));
        String actualUrl = normalizeUrl(proof.get("fullShortUrl"));
        return requestedUrl.isEmpty() ? Boolean.TRUE.equals(proof.get("groupScopeComplete"))
                : requestedUrl.equals(actualUrl);
    }

    private static boolean sameSnapshot(Map<String, Object> first, Map<String, Object> next) {
        return List.of("requestedStart", "requestedEnd", "effectiveEnd", "recoveryEpoch", "metricVersion", "sourceCut", "manifestVersion", "dimensions", "filters")
                .stream().allMatch(key -> Objects.equals(first.get(key), next.get(key)));
    }

    private List<Map<String, Object>> comparisons(List<Map<String, Object>> rows,
            List<Map<String, Object>> scopes, List<Map<String, Object>> periods) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        rows.forEach(row -> indexed.put(text(row.get("key")), row));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var scope : scopes) for (int index = 1; index < periods.size(); index++)
            differences(indexed.get(key(query(scope, periods.get(0)))), indexed.get(key(query(scope, periods.get(index)))), "PERIOD", result);
        for (var period : periods) for (int index = 1; index < scopes.size(); index++)
            differences(indexed.get(key(query(scopes.get(index), period))), indexed.get(key(query(scopes.get(0), period))), "OBJECT", result);
        return result;
    }

    private void differences(Map<String, Object> target, Map<String, Object> baseline,
            String kind, List<Map<String, Object>> output) {
        if (target == null || baseline == null) return;
        List<String> warnings = new ArrayList<>();
        boolean comparable = "READY".equals(target.get("status")) && "READY".equals(baseline.get("status"));
        if (!comparable) warnings.add("至少一个整段期间汇总不可用");
        Map<String, Object> targetMeta = map(target.get("quality")), baselineMeta = map(baseline.get("quality"));
        if (!complete(targetMeta) || !complete(baselineMeta)) {
            comparable = false;
            warnings.add("数据不完整或已过期，差值仅描述已观测记录，不据此推断增长率");
        }
        if (text(targetMeta.get("metricVersion")).isEmpty()
                || !Objects.equals(targetMeta.get("metricVersion"), baselineMeta.get("metricVersion"))) {
            comparable = false;
            warnings.add("指标版本缺失或不一致");
        }
        LocalDate today = LocalDate.now(clock);
        if (!LocalDate.parse(text(target.get("endDate"))).isBefore(today)
                || !LocalDate.parse(text(baseline.get("endDate"))).isBefore(today)) {
            comparable = false;
            warnings.add("请求期间尚未结束，当前观测值不能作为完整期间对比");
        }
        if ("PERIOD".equals(kind)) {
            LocalDate ts = LocalDate.parse(text(target.get("startDate"))), te = LocalDate.parse(text(target.get("endDate")));
            LocalDate bs = LocalDate.parse(text(baseline.get("startDate"))), be = LocalDate.parse(text(baseline.get("endDate")));
            if (ChronoUnit.DAYS.between(ts, te) != ChronoUnit.DAYS.between(bs, be)) {
                comparable = false;
                warnings.add("两个期间长度不同");
            }
            if (!(te.isBefore(bs) || be.isBefore(ts))) {
                comparable = false;
                warnings.add("两个期间存在重叠");
            }
        }
        for (String metric : METRICS) {
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("kind", kind);
            comparison.put("targetKey", target.get("key"));
            comparison.put("baselineKey", baseline.get("key"));
            comparison.put("metric", metric);
            BigDecimal current = optionalNumber(target.get(metric)), previous = optionalNumber(baseline.get(metric));
            BigDecimal delta = current == null || previous == null ? null : current.subtract(previous);
            comparison.put("delta", delta);
            comparison.put("rate", comparable && delta != null ? ratio(delta, previous) : null);
            comparison.put("comparable", comparable);
            List<String> reasons = new ArrayList<>(warnings);
            if (previous != null && previous.signum() == 0) reasons.add("基期为零，变化率无定义");
            comparison.put("warnings", reasons);
            output.add(comparison);
        }
    }

    private static List<Map<String, Object>> scopes(List<Map<String, Object>> values) {
        require(values != null && !values.isEmpty() && values.size() <= 16, "One to sixteen scopes are required");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var value : values) {
            require(value != null, "Invalid scope");
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("gid", required(text(value.get("gid")), "gid"));
            for (String field : List.of("fullShortUrl", "label")) if (!text(value.get(field)).isEmpty()) scope.put(field, text(value.get(field)));
            require(seen.add(scope.get("gid") + "|" + normalizeUrl(scope.get("fullShortUrl"))), "Duplicate comparison scope");
            result.add(scope);
        }
        return result;
    }

    private static List<Map<String, Object>> periods(List<Map<String, Object>> values) {
        require(values != null && !values.isEmpty() && values.size() <= 16, "One to sixteen periods are required");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var value : values) {
            require(value != null, "Invalid period");
            LocalDate start, end;
            try {
                start = LocalDate.parse(text(value.get("startDate")));
                end = LocalDate.parse(text(value.get("endDate")));
            } catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid statistics calendar date"); }
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            require(days > 0 && days <= 180, "Statistics periods must contain one to 180 inclusive days");
            require(seen.add(start + ":" + end), "Duplicate comparison period");
            Map<String, Object> period = new LinkedHashMap<>(Map.of("startDate", start.toString(), "endDate", end.toString()));
            if (!text(value.get("label")).isEmpty()) period.put("label", text(value.get("label")));
            result.add(period);
        }
        return result;
    }

    private static Map<String, Map<String, Object>> references(List<Map<String, Object>> jobs,
            List<Map<String, Object>> scopes, List<Map<String, Object>> periods) {
        Set<String> expected = new LinkedHashSet<>();
        for (var scope : scopes) for (var period : periods) expected.add(key(query(scope, period)));
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (jobs == null) return result;
        require(jobs.size() <= 16, "Too many job references");
        for (var job : jobs) {
            require(job != null && expected.contains(key(job)), "Job reference does not match a requested scope and period");
            require("METRICS".equals(text(job.get("queryKind"))) && text(job.get("jobId")).matches("[A-Za-z0-9_-]{1,128}"), "Invalid comparison job reference");
            require(result.putIfAbsent(key(job), job) == null, "Duplicate job reference for the same query");
        }
        return result;
    }

    private ToolResult get(String path, Map<String, Object> arguments, ToolContext context) {
        return safe(gateway.get(path, bound(context, arguments), arguments));
    }

    private static ToolResult safe(ToolResult result) { return result == null ? ToolResult.failure("Statistics gateway returned no response") : result; }

    static ToolContext trustedContext(org.springframework.ai.chat.model.ToolContext trusted) {
        ToolContext context = ToolContext.fromSpringContext(trusted, Map.of());
        require(context.principal() != null && !context.principal().system(), "A trusted user principal is required");
        return new ToolContext(context.sessionId(), context.principal().username(), Map.of(), context.principal());
    }

    private static ToolContext bound(ToolContext context, Map<String, Object> arguments) {
        return new ToolContext(context.sessionId(), context.username(), arguments, context.principal());
    }

    private static Map<String, Object> query(Map<String, Object> scope, Map<String, Object> period) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("gid", scope.get("gid"));
        if (!text(scope.get("fullShortUrl")).isEmpty()) query.put("fullShortUrl", scope.get("fullShortUrl"));
        query.put("startDate", period.get("startDate"));
        query.put("endDate", period.get("endDate"));
        return query;
    }

    private static String key(Map<String, Object> query) {
        return text(query.get("gid")) + "|" + normalizeUrl(query.get("fullShortUrl")) + "|"
                + text(query.get("startDate")) + "|" + text(query.get("endDate"));
    }

    private static Map<String, Object> summary(Map<String, Object> data) { return map(map(data.get("metrics")).get("requested")); }
    private static boolean validMetrics(Map<String, Object> data) {
        return METRICS.stream().allMatch(metric -> optionalNumber(data.get(metric)) != null
                && optionalNumber(data.get(metric)).signum() >= 0
                && optionalNumber(data.get(metric)).stripTrailingZeros().scale() <= 0);
    }
    private static BigDecimal number(Object value) { return new BigDecimal(value.toString()); }
    private static BigDecimal optionalNumber(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try { return number(value); } catch (NumberFormatException invalid) { return null; }
    }
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null : numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }
    private static boolean complete(Map<String, Object> meta) {
        return "AVAILABLE".equals(meta.get("availability")) && "COMPLETE".equals(meta.get("completeness"))
                && "FRESH".equals(meta.get("freshness")) && !Boolean.TRUE.equals(meta.get("provisional"));
    }
    private static void qualityWarnings(Map<String, Object> meta, List<String> warnings, String label) {
        if (!complete(meta)) warnings.add(label + "：指标仅反映当前可用快照，数据不完整、已过期或质量未知");
        if (!"COMPLETE".equals(map(meta.get("collectionQuality")).get("status")))
            warnings.add(label + "：采集完整性未得到证明，当前计数不代表全部实际访问");
        addApproximationWarning(meta, warnings, label + "：");
    }
    static void addApproximationWarning(Map<String, Object> meta, List<String> warnings, String prefix) {
        Map<String, Object> approximation = map(meta.get("approximation"));
        List<String> approximateMetrics = List.of("uv", "uip").stream()
                .filter(metric -> "APPROXIMATE".equals(map(approximation.get(metric)).get("type")))
                .map(metric -> metric.toUpperCase(java.util.Locale.ROOT))
                .toList();
        if (!approximateMetrics.isEmpty())
            warnings.add(prefix + String.join("、", approximateMetrics) + " 保留原有近似统计口径");
    }
    private static Map<String, Object> base(String type, String status, List<Map<String, Object>> rows, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type); result.put("status", status); result.put("rows", rows); result.put("warnings", warnings);
        return result;
    }
    private static ToolResult incompleteRanking(String message, String metric, int limit) {
        var result = base("ranking", "INCOMPLETE", List.of(), List.of(message));
        result.put("metrics", Map.of());
        result.put("meta", Map.of("resultComplete", false, "metric", metric, "limit", limit));
        return ToolResult.success(result);
    }
    private static List<Map<String, Object>> columns(String... fields) {
        Map<String, String> labels = Map.of("gid", "分组", "startDate", "开始日期", "endDate", "结束日期", "pv", "PV", "uv", "UV", "uip", "UIP", "rank", "排名", "fullShortUrl", "短链", "pvShare", "PV占比");
        List<Map<String, Object>> result = new ArrayList<>();
        for (String field : fields) result.add(Map.of("key", field, "label", labels.getOrDefault(field, field)));
        return result;
    }
    private static boolean epochEquals(Object value, long expected) {
        try { return value != null && number(value).longValueExact() == expected; } catch (ArithmeticException | NumberFormatException invalid) { return false; }
    }
    private static Integer exactPageIndex(Object value) {
        try { return number(value).intValueExact(); } catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }
    private static String normalizeUrl(Object value) { return text(value).replaceFirst("(?i)^https?://", ""); }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private static String required(String value, String name) { require(value != null && !value.isBlank(), "Missing required argument: " + name); return value.trim(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) values.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> values) for (Object item : values) if (item instanceof Map<?, ?>) result.add(map(item));
        return result;
    }
    record Fetch(String status, Map<String, Object> data, String jobId, String message) {
        static Fetch failed(String message, String jobId) { return new Fetch("INCOMPLETE", Map.of(), jobId, message); }
    }
}
