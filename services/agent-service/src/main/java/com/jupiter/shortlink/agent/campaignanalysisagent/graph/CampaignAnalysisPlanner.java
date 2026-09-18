package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import com.jupiter.shortlink.agent.tool.shortlink.StatisticsQueryJobPlanner;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure, bounded planning. A gid remains subject to the tool service's authorization. */
final class CampaignAnalysisPlanner {
    private static final int MAX_INVOCATIONS = 16;
    private static final Pattern ARGUMENT = Pattern.compile(
            "(?iu)(?<![a-z0-9_])(gid|fullShortUrl|groupName|startDate|endDate|current|size|orderTag|snapshotId|cursor|metric|limit)"
                    + "\\s*[:=：]\\s*(\"[^\"]+\"|'[^']+'|「[^」]+」|“[^”]+”|[^\\s,;，；]+)");
    private static final Pattern DATE = Pattern.compile("(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])");
    private static final Pattern DAYS = Pattern.compile(
            "(?iu)(?:最近|过去|近|last\\s*|past\\s*|recent\\s*)\\s*([0-9]+)\\s*(?:天|日|days?\\b)");
    private static final Pattern URL = Pattern.compile("(?iu)https?://[^\\s,;，；「」“”\"']+");
    private static final Pattern SELECTED_SCOPE = Pattern.compile(
            "(?isu)^\\s*分析范围\\s*[:：]\\s*分组\\s*[「“\"]([^」”\"]+)[」”\"]"
                    + "\\s*[;；]?\\s*gid\\s*[:=：]\\s*([^\\s;；]+)\\s*[;；]?\\s*");
    private static final Set<String> DEFAULT_NAMES = Set.of("default", "default group", "默认", "默认分组", "默认组");
    private static final List<String> DIMENSIONS = List.of("day", "hour", "weekday", "country", "province",
            "device", "os", "browser", "isp", "refererDomain");
    private static final Pattern DRILL_ARGUMENT = Pattern.compile(
            "(?iu)(?<![a-z0-9_])(dimensions|day|hour|weekday|country|province|device|os|browser|isp|refererDomain)"
                    + "\\s*[:=：]\\s*(\"[^\"]*\"|'[^']*'|「[^」]*」|“[^”]*”|[^\\s;；。]+)");
    private static final Pattern NATURAL_DIMENSIONS = Pattern.compile(
            "按\\s*([^，,；;。]+?)(?:联合分析|联合分布|下钻|分析|统计|分布|拆分|细分|分组|$)");
    private static final Pattern NATURAL_RANKING = Pattern.compile(
            "(?iu)按\\s*(?:pv|uv|uip)\\s*(?:排名|排行|排序)(?:\\s*(?:前|top)?\\s*[0-9]+)?");
    private static final Pattern RANKING_COUNT = Pattern.compile(
            "(?iu)(?:前\\s*|\\btop\\s*)[0-9]+(?![0-9])(?!\\s*(?:天|日|小时|周|月|年))");
    private static final Pattern NATURAL_FILTER = Pattern.compile("(?:只看|仅看|只统计|筛选为|限定为)\\s*([^\\s，,；;。]+)");
    private static final Set<String> PROVINCES = Set.of("北京", "北京市", "天津", "天津市", "上海", "上海市",
            "重庆", "重庆市", "河北", "河北省", "山西", "山西省", "辽宁", "辽宁省", "吉林", "吉林省",
            "黑龙江", "黑龙江省", "江苏", "江苏省", "浙江", "浙江省", "安徽", "安徽省", "福建", "福建省",
            "江西", "江西省", "山东", "山东省", "河南", "河南省", "湖北", "湖北省", "湖南", "湖南省",
            "广东", "广东省", "海南", "海南省", "四川", "四川省", "贵州", "贵州省", "云南", "云南省",
            "陕西", "陕西省", "甘肃", "甘肃省", "青海", "青海省", "台湾", "台湾省", "内蒙古", "内蒙古自治区",
            "广西", "广西壮族自治区", "西藏", "西藏自治区", "宁夏", "宁夏回族自治区", "新疆", "新疆维吾尔自治区",
            "香港", "香港特别行政区", "澳门", "澳门特别行政区");
    private final Clock clock;
    private final CampaignQueryScopeResolver dates;

    CampaignAnalysisPlanner(Clock clock) {
        this.clock = clock;
        this.dates = new CampaignQueryScopeResolver(clock);
    }

    record Invocation(String name, Map<String, Object> arguments) {
        Invocation { arguments = Map.copyOf(arguments); }
    }

    record Plan(List<Invocation> invocations, List<String> warnings,
            Map<String, Object> context, boolean needsGroups) {
        Plan {
            invocations = List.copyOf(invocations);
            warnings = List.copyOf(warnings);
            context = Map.copyOf(context);
        }
    }

    Plan plan(String message, Map<String, Object> previous, List<Object> ownedGroups) {
        previous = previous == null ? Map.of() : previous;
        String body = message == null ? "" : message.trim();
        var job = StatisticsQueryJobPlanner.continuation(body);
        if (job.isPresent()) return new Plan(
                List.of(new Invocation(job.get().toolName(), job.get().arguments())),
                List.of(), safeContext(previous), false);
        String selectedGid = null;
        var selected = SELECTED_SCOPE.matcher(body);
        if (selected.find()) {
            selectedGid = clean(selected.group(2));
            body = body.substring(selected.end());
        }
        List<Token> tokens = tokens(body);
        String normalized = stripScopeArguments(body, tokens).toLowerCase(Locale.ROOT);
        boolean followup = contains(normalized, "继续", "接着", "再看", "那", "换成", "改成", "改为",
                "同样", "上述", "上次", "刚才", "这些", "这个", "呢", "再按", "只看", "仅看", "只统计",
                "清除筛选", "去掉筛选", "清空筛选", "清除过滤", "去掉过滤", "follow up", "instead", "same", "then")
                || (Intent.parse(previous.get("intent")).drill && (DRILL_ARGUMENT.matcher(normalized).find()
                        || NATURAL_DIMENSIONS.matcher(normalized).find()));
        // Generic continuation wording carries no new business intent by itself. A period-only
        // follow-up retains ranking/comparison/drill, while explicit trends or records replace it.
        String intentText = normalized.replaceFirst("^\\s*(?:继续|接着)(?:分析|查询)\\s*[,，:：;；]?\\s*", "");
        Intent intent = intent(intentText);
        boolean rankingAdjustment = Intent.parse(previous.get("intent")).rank
                && (!named(tokens, "metric").isEmpty() || !named(tokens, "limit").isEmpty()
                        || RANKING_COUNT.matcher(normalized).find()
                        || Pattern.compile("(?iu)(?:换成|改成|改为)\\s*(?:pv|uv|uip)(?![a-z0-9_])")
                                .matcher(normalized).find());
        if (rankingAdjustment && !intent.records && !intent.links && !intent.groups
                && !intent.compare && !intent.drill
                && !contains(normalized, "趋势", "高峰", "汇总", "构成", "分组统计", "group stats")) {
            intent = new Intent(true, false, false, false, false, true, false);
            followup = true;
        }
        if (!intent.any() && (followup || dateOnly(normalized))) intent = Intent.parse(previous.get("intent"));
        if (!intent.any()) return new Plan(List.of(), List.of(), safeContext(previous), false);

        List<String> warnings = new ArrayList<>();
        boolean inherit = followup || intent.stats || intent.records || intent.links;
        Selection periods = intent.stats || intent.records
                ? periods(body, tokens, previous, inherit, followup, warnings)
                : new Selection(List.of(), false, false, false);
        Selection scopes = scopes(body, tokens, selectedGid, previous, ownedGroups,
                inherit, followup, intent, warnings);
        Map<String, Object> context = context(scopes.values, periods.values, intent.text());
        String activeSelection = selectedGid == null ? text(previous.get("selectedGid")) : selectedGid;
        if (!activeSelection.isBlank()) {
            context = new LinkedHashMap<>(context);
            context.put("selectedGid", activeSelection);
        }
        if (scopes.needsGroups || (intent.groups && ownedGroups == null))
            return new Plan(List.of(), warnings, context, true);
        if (scopes.invalid || periods.invalid) return new Plan(List.of(), warnings, context, false);

        List<Invocation> invocations = new ArrayList<>();
        Map<String, Object> extras = extras(tokens);
        int combinations = scopes.values.size() * Math.max(1, periods.values.size());
        if (combinations > 1 && (extras.containsKey("snapshotId") || extras.containsKey("cursor")
                || (extras.get("current") instanceof Number current && current.longValue() > 1))) {
            warnings.add("多对象或多期间查询不能共用一个分页快照；请指定要继续读取的对象和期间。");
            return new Plan(List.of(), warnings, context, false);
        }
        boolean compare = intent.compare && intent.stats && !intent.drill && combinations >= 2;
        if (compare && combinations > MAX_INVOCATIONS) {
            warnings.add("本次比较包含 " + combinations + " 个对象与期间组合，超过单轮 "
                    + MAX_INVOCATIONS + " 个；请缩小比较范围，未执行部分查询。");
            return new Plan(List.of(), warnings, context, false);
        }
        Map<String, Object> ranking = intent.rank ? ranking(body, tokens, previous, warnings) : Map.of();
        if (intent.rank && (ranking.isEmpty() || scopes.values.stream().anyMatch(scope -> scope.containsKey("fullShortUrl")))) {
            if (!ranking.isEmpty()) warnings.add("短链排名针对分组内的短链；请提供分组范围，不要同时指定单个 fullShortUrl。");
            return new Plan(List.of(), warnings, context, false);
        }
        if (intent.rank) {
            context = new LinkedHashMap<>(context);
            context.put("ranking", ranking);
        }
        Map<String, Object> drill = intent.drill ? drill(body, tokens, previous, warnings) : Map.of();
        if (intent.drill) {
            if (drill.isEmpty()) return new Plan(List.of(), warnings, context, false);
            if (intent.rank || (intent.records && !((List<?>) drill.get("filters")).isEmpty())) {
                warnings.add("联合维度筛选暂不能与短链排名或带筛选的访问记录混合查询；请单独请求维度下钻，未忽略筛选执行其它查询。");
                return new Plan(List.of(), warnings, context, false);
            }
            context = new LinkedHashMap<>(context);
            context.put("drill", drill);
        }
        if (compare) invocations.add(new Invocation("compare_statistics", Map.of(
                "scopes", scopes.values,
                "periods", periods.values.stream().map(period -> arguments(Map.of(), period, Map.of())).toList())));
        for (Map<String, Object> scope : scopes.values) {
            if (intent.links) invocations.add(new Invocation("page_short_links", arguments(scope, Map.of(), extras)));
            for (Map<String, Object> period : periods.values) {
                Map<String, Object> arguments = arguments(scope, period, extras);
                if (intent.rank) {
                    Map<String, Object> rankArguments = arguments(scope, period, ranking);
                    invocations.add(new Invocation("rank_short_links", rankArguments));
                }
                if (intent.drill) invocations.add(new Invocation("get_dimension_breakdown", arguments(scope, period, drill)));
                if (intent.stats && !compare && !intent.rank && !intent.drill) invocations.add(queryInvocation(arguments, "METRICS",
                        scope.containsKey("fullShortUrl") ? "get_short_link_stats" : "get_group_stats"));
                if (intent.records) invocations.add(queryInvocation(arguments, "ACCESS_RECORDS", "get_group_access_records"));
            }
        }
        if (invocations.size() > MAX_INVOCATIONS) {
            warnings.add("本次对象与期间组合需要 " + invocations.size() + " 次查询，超过单轮 "
                    + MAX_INVOCATIONS + " 次；请缩小对象或期间数量，未截断或执行部分查询。");
            return new Plan(List.of(), warnings, context, false);
        }
        return new Plan(invocations, warnings, context, false);
    }

    private Invocation queryInvocation(Map<String, Object> arguments, String kind, String synchronousTool) {
        return StatisticsQueryJobPlanner.longRange(arguments, kind)
                .map(plan -> new Invocation(plan.toolName(), plan.arguments()))
                .orElseGet(() -> new Invocation(synchronousTool, arguments));
    }

    private Map<String, Object> ranking(String message, List<Token> tokens,
            Map<String, Object> previous, List<String> warnings) {
        Map<String, Object> inherited = previousRanking(previous);
        List<Token> explicitMetrics = named(tokens, "metric");
        Set<String> metrics = new LinkedHashSet<>();
        if (!explicitMetrics.isEmpty()) {
            for (Token metric : explicitMetrics) metrics.add(metric.value.toLowerCase(Locale.ROOT));
        } else {
            var matcher = Pattern.compile("(?iu)(?<![a-z0-9_])(pv|uv|uip)(?![a-z0-9_])")
                    .matcher(stripScopeArguments(message, tokens));
            while (matcher.find()) metrics.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        if (metrics.size() > 1 || (!metrics.isEmpty() && !Set.of("pv", "uv", "uip").containsAll(metrics))) {
            warnings.add("短链排名请指定一个排序指标：PV、UV 或 UIP，未猜测指标。");
            return Map.of();
        }
        String metric = metrics.isEmpty() ? text(inherited.getOrDefault("metric", "pv")) : metrics.iterator().next();
        Set<String> limits = new LinkedHashSet<>();
        for (Token limit : named(tokens, "limit")) limits.add(limit.value);
        if (limits.isEmpty()) {
            var matcher = Pattern.compile("(?iu)(?:\\btop\\s*|前\\s*|排名\\s*|排行\\s*)([0-9]+)")
                    .matcher(stripScopeArguments(message, tokens));
            while (matcher.find()) limits.add(matcher.group(1));
        }
        try {
            if (limits.size() > 1) throw new NumberFormatException("multiple limits");
            int limit = limits.isEmpty() ? ((Number) inherited.getOrDefault("limit", 10)).intValue()
                    : Integer.parseInt(limits.iterator().next());
            if (limit < 1 || limit > 50) throw new NumberFormatException("limit out of range");
            return Map.of("metric", metric, "limit", limit);
        } catch (NumberFormatException invalid) {
            warnings.add("短链排名数量需为 1 至 50 的单个整数，未截断或执行查询。");
            return Map.of();
        }
    }

    private Map<String, Object> drill(String message, List<Token> tokens,
            Map<String, Object> previous, List<String> warnings) {
        Map<String, Object> inherited = previousDrill(previous);
        String input = stripScopeArguments(message, tokens);
        List<String> dimensions = new ArrayList<>();
        Map<String, Map<String, Object>> filters = new LinkedHashMap<>();
        boolean clear = contains(input, "清除筛选", "去掉筛选", "清空筛选", "清除过滤", "去掉过滤");
        if (!clear && inherited.get("filters") instanceof List<?> priorFilters)
            for (Object item : priorFilters) {
                if (item instanceof Map<?, ?> filter) {
                    Map<String, Object> copy = copyFilter(filter);
                    filters.put(text(copy.get("dimension")), copy);
                }
            }
        try {
            if (Pattern.compile("(?iu)\\bdimensions\\s*[:=：]\\s*(?:$|[;；])").matcher(input).find())
                throw new IllegalArgumentException("维度下钻需要 1 至 3 个不同维度，请明确 dimensions=province,device。");
            boolean explicitDimensions = false;
            var arguments = DRILL_ARGUMENT.matcher(input);
            while (arguments.find()) {
                String name = arguments.group(1);
                String value = clean(arguments.group(2));
                if (name.equalsIgnoreCase("dimensions")) {
                    explicitDimensions = true;
                    for (String dimension : splitDrillValues(value)) dimensions.add(dimension(dimension));
                } else {
                    String key = dimension(name);
                    Map<String, Object> filter = filter(key, splitDrillValues(value));
                    if (filters.containsKey(key) && !filters.get(key).equals(filter)
                            && explicitlyFilteredBefore(input, arguments.start(), key))
                        throw new IllegalArgumentException("同一维度不能同时提供多个不同筛选；多个值请合并到同一个 IN 筛选。");
                    filters.put(key, filter);
                }
            }
            String natural = DRILL_ARGUMENT.matcher(input).replaceAll(" ");
            if (!explicitDimensions) {
                var clauses = NATURAL_DIMENSIONS.matcher(natural);
                while (clauses.find()) {
                    String names = clauses.group(1).trim().replaceAll("的$", "");
                    for (String name : names.split("\\s*(?:以及|、|和|与|及|,|，|(?i:\\band\\b))\\s*"))
                        dimensions.add(dimension(name.trim()));
                }
            }
            if (dimensions.isEmpty() && !explicitDimensions && inherited.get("dimensions") instanceof List<?> priorDimensions)
                for (Object value : priorDimensions) dimensions.add(text(value));
            validateDimensions(dimensions);
            var clauses = NATURAL_FILTER.matcher(natural);
            while (clauses.find()) {
                String value = clauses.group(1);
                if (value.contains("=")) throw new IllegalArgumentException("无法确定筛选字段；请使用 province=浙江 或 device=Mobile 等明确参数。");
                List<String> values = List.of(value.split("、|和|与|及"));
                if (values.stream().allMatch(PROVINCES::contains)) {
                    filters.put("province", filter("province", values));
                } else {
                    var unknown = Pattern.compile("(.+?)(?:为)?(?:未知|未识别|IS_UNKNOWN)$", Pattern.CASE_INSENSITIVE).matcher(value);
                    if (!unknown.matches()) throw new IllegalArgumentException("无法确定筛选值所属维度；请使用 province=浙江、device=Mobile 或 province=IS_UNKNOWN 等明确参数，未猜测分类值。");
                    String key = dimension(unknown.group(1));
                    filters.put(key, Map.of("dimension", key, "operator", "IS_UNKNOWN"));
                }
            }
            if (Pattern.compile("(?iu)(?<![a-z0-9_])(?:day|hour|weekday|country|province|device|os|browser|isp|refererDomain)\\s*(?:!=|<>|>=|<=|>|<|\\bIN\\b|\\bNOT\\b|\\bLIKE\\b)").matcher(natural).find()
                    || Pattern.compile("(?iu)(?<![a-z0-9_])[a-z][a-z0-9_]*\\s*[:=：]").matcher(ARGUMENT.matcher(natural).replaceAll(" ")).find())
                throw new IllegalArgumentException("筛选仅支持白名单维度的 IN 或 IS_UNKNOWN；请使用 province=浙江,江苏 或 province=IS_UNKNOWN。");
            if (filters.size() > 8) throw new IllegalArgumentException("维度下钻最多支持 8 个筛选维度，请缩小筛选范围。");
            return Map.of("dimensions", List.copyOf(dimensions), "filters", List.copyOf(filters.values()));
        } catch (IllegalArgumentException invalid) {
            warnings.add(invalid.getMessage());
            return Map.of();
        }
    }

    private static boolean explicitlyFilteredBefore(String message, int end, String dimension) {
        var arguments = DRILL_ARGUMENT.matcher(message.substring(0, end));
        while (arguments.find()) if (arguments.group(1).equalsIgnoreCase(dimension)) return true;
        return false;
    }

    private static List<String> splitDrillValues(String input) {
        if (input.isBlank()) throw new IllegalArgumentException("维度与筛选值不能为空，请提供明确参数。");
        return List.of(input.split("\\s*[,，、]\\s*", -1));
    }

    private static String dimension(String name) {
        for (String dimension : DIMENSIONS) if (dimension.equalsIgnoreCase(name)) return dimension;
        return switch (name) {
            case "日期", "日", "每天" -> "day";
            case "小时", "每小时", "时段" -> "hour";
            case "星期", "星期几", "周几" -> "weekday";
            case "国家", "国别" -> "country";
            case "省份", "地区", "地域" -> "province";
            case "设备", "设备类型" -> "device";
            case "操作系统", "系统" -> "os";
            case "浏览器" -> "browser";
            case "运营商" -> "isp";
            case "来源域名", "来源站点" -> "refererDomain";
            default -> throw new IllegalArgumentException("无法确定分析维度，请明确 dimensions=province,device；可选 day/hour/weekday/country/province/device/os/browser/isp/refererDomain。");
        };
    }

    private static void validateDimensions(List<String> dimensions) {
        if (dimensions.isEmpty() || dimensions.size() > 3 || new LinkedHashSet<>(dimensions).size() != dimensions.size()
                || !DIMENSIONS.containsAll(dimensions))
            throw new IllegalArgumentException("维度下钻需要 1 至 3 个不同维度，请明确 dimensions=province,device。");
    }

    private static Map<String, Object> filter(String dimension, List<String> input) {
        if (input.size() == 1 && "IS_UNKNOWN".equalsIgnoreCase(input.get(0)))
            return Map.of("dimension", dimension, "operator", "IS_UNKNOWN");
        if (input.isEmpty() || input.size() > 20) throw new IllegalArgumentException("每个 IN 筛选需要 1 至 20 个明确分类值。");
        List<String> values = new ArrayList<>();
        for (String value : input) {
            if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl)
                    || "IS_UNKNOWN".equalsIgnoreCase(value))
                throw new IllegalArgumentException("筛选值需为非空分类文本；IS_UNKNOWN 必须单独使用，不能与 IN 值混合。");
            if (Set.of("hour", "weekday").contains(dimension)) {
                try {
                    int number = Integer.parseInt(value);
                    if (!value.equals(Integer.toString(number)) || (dimension.equals("hour") ? number < 0 || number > 23 : number < 1 || number > 7))
                        throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("hour 筛选须为 0 至 23、weekday 须为 1 至 7 的标准整数文本。");
                }
            }
            if (dimension.equals("day")) {
                try {
                    if (!LocalDate.parse(value).toString().equals(value)) throw new DateTimeException("invalid day");
                } catch (DateTimeException invalid) {
                    throw new IllegalArgumentException("day 筛选须为有效的 yyyy-MM-dd 日期。");
                }
            }
            if (!values.contains(value)) values.add(value);
        }
        return Map.of("dimension", dimension, "operator", "IN", "values", List.copyOf(values));
    }

    private static Map<String, Object> copyFilter(Map<?, ?> source) {
        String key = text(source.get("dimension"));
        if (!DIMENSIONS.contains(key)) throw new IllegalArgumentException("Invalid inherited dimension");
        if ("IS_UNKNOWN".equals(source.get("operator"))) return Map.of("dimension", key, "operator", "IS_UNKNOWN");
        if (!"IN".equals(source.get("operator")) || !(source.get("values") instanceof List<?> values))
            throw new IllegalArgumentException("Invalid inherited filter");
        if (values.stream().anyMatch(value -> !(value instanceof String))) throw new IllegalArgumentException("Invalid inherited values");
        return filter(key, values.stream().map(CampaignAnalysisPlanner::text).toList());
    }

    private static Map<String, Object> previousDrill(Map<String, Object> previous) {
        if (!Intent.parse(previous.get("intent")).drill || !(previous.get("drill") instanceof Map<?, ?> drill)
                || !(drill.get("dimensions") instanceof List<?> dimensions) || !(drill.get("filters") instanceof List<?> filters))
            return Map.of();
        try {
            List<String> names = dimensions.stream().map(CampaignAnalysisPlanner::text).toList();
            validateDimensions(names);
            List<Map<String, Object>> copies = new ArrayList<>();
            Set<String> filtered = new LinkedHashSet<>();
            for (Object value : filters) {
                if (!(value instanceof Map<?, ?> source)) return Map.of();
                Map<String, Object> filter = copyFilter(source);
                if (!filtered.add(text(filter.get("dimension")))) return Map.of();
                copies.add(filter);
            }
            return copies.size() <= 8 ? Map.of("dimensions", names, "filters", List.copyOf(copies)) : Map.of();
        } catch (IllegalArgumentException invalid) {
            return Map.of();
        }
    }

    private Selection scopes(String message, List<Token> tokens, String selectedGid,
            Map<String, Object> previous, List<Object> ownedGroups, boolean inherit,
            boolean followup, Intent intent, List<String> warnings) {
        if (!(intent.stats || intent.records || intent.links))
            return new Selection(List.of(), false, false, false);
        List<Token> gids = named(tokens, "gid");
        List<Token> urls = named(tokens, "fullShortUrl");
        List<Token> names = named(tokens, "groupName");
        if (urls.isEmpty()) {
            var rawUrls = URL.matcher(message);
            while (rawUrls.find()) urls.add(new Token("fullShortUrl", clean(rawUrls.group()), rawUrls.start()));
        }
        String nameText = stripArguments(message, tokens);
        boolean namedReference = !names.isEmpty() || contains(nameText.toLowerCase(Locale.ROOT),
                "分组", "默认", "default", "group");
        boolean comparison = comparison(message);
        boolean canInheritScope = inherit && (followup || dateOnly(message))
                && !contextRows(previous.get("scopes"), "gid", "fullShortUrl", "label").isEmpty();
        boolean lookupNames = namedReference || (gids.isEmpty() && urls.isEmpty()
                && ((selectedGid == null && !canInheritScope) || (comparison && !dateOnly(message))));
        if (ownedGroups == null && lookupNames)
            return new Selection(List.of(), namedReference, true, false);

        List<Map<String, Object>> namedScopes = new ArrayList<>();
        if (ownedGroups != null && (lookupNames || gids.isEmpty())) {
            if (!names.isEmpty()) {
                for (Token name : names) {
                    Map<String, Object> scope = resolveName(name.value, ownedGroups, warnings);
                    if (scope == null) return new Selection(List.of(), true, false, true);
                    namedScopes.add(scope);
                }
            } else {
                List<String> matchingNames = matchingNames(nameText, ownedGroups);
                for (String name : matchingNames) {
                    Map<String, Object> scope = resolveName(name, ownedGroups, warnings);
                    if (scope == null) return new Selection(List.of(), true, false, true);
                    namedScopes.add(scope);
                }
            }
        }
        if (namedReference && namedScopes.isEmpty() && gids.isEmpty() && selectedGid == null) {
            warnings.add("未能从当前用户的分组列表唯一定位目标分组；请使用列表中的准确分组名或 gid。");
            return new Selection(List.of(), true, false, true);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Token gid : gids) result.add(Map.of("gid", gid.value));
        result.addAll(namedScopes);
        result = distinct(result, "gid", "fullShortUrl");
        boolean explicit = !gids.isEmpty() || !urls.isEmpty() || !namedScopes.isEmpty() || namedReference;
        boolean sameSelectedFollowup = selectedGid != null && selectedGid.equals(text(previous.get("selectedGid")))
                && (followup || dateOnly(message));
        if (result.isEmpty() && !namedReference && selectedGid != null && !sameSelectedFollowup)
            result.add(Map.of("gid", selectedGid));
        if (result.isEmpty() && inherit && !namedReference) {
            List<Map<String, Object>> prior = contextRows(previous.get("scopes"), "gid", "fullShortUrl", "label");
            if (prior.size() == 1 || followup) result.addAll(prior);
            else if (prior.size() > 1) {
                warnings.add("上一轮涉及多个对象；请明确继续哪些对象，未自动选取其中一个。");
                return new Selection(List.of(), explicit, false, true);
            }
        }
        if (result.isEmpty() && !namedReference && selectedGid != null) result.add(Map.of("gid", selectedGid));
        if (!urls.isEmpty()) {
            if (result.size() == 1) {
                String gid = text(result.get(0).get("gid"));
                result = new ArrayList<>();
                for (Token url : urls) result.add(Map.of("gid", gid, "fullShortUrl", url.value));
            } else if (!gids.isEmpty()) {
                List<Map<String, Object>> paired = new ArrayList<>();
                Set<String> usedGids = new LinkedHashSet<>();
                for (Token url : urls) {
                    Token preceding = null;
                    for (Token gid : gids) if (gid.start < url.start) preceding = gid;
                    if (preceding == null) {
                        warnings.add("多个分组与短链的对应关系不明确；请在每条 fullShortUrl 前标明所属 gid。");
                        return new Selection(List.of(), true, false, true);
                    }
                    paired.add(Map.of("gid", preceding.value, "fullShortUrl", url.value));
                    usedGids.add(preceding.value);
                }
                for (Map<String, Object> scope : result)
                    if (!usedGids.contains(text(scope.get("gid")))) paired.add(scope);
                result = paired;
            } else {
                warnings.add("短链需要明确所属分组；请提供 gid 或唯一匹配的分组名，未猜测短链归属。");
                return new Selection(List.of(), true, false, true);
            }
        }
        if (result.isEmpty()) {
            warnings.add("未能从当前用户的分组列表唯一定位目标分组；请提供准确分组名或 gid。");
            return new Selection(List.of(), explicit, false, true);
        }
        return new Selection(distinct(result, "gid", "fullShortUrl"), explicit, false, false);
    }

    private Map<String, Object> resolveName(String requested, List<Object> groups, List<String> warnings) {
        String normalized = requested.toLowerCase(Locale.ROOT).trim();
        Map<String, Map<String, Object>> matches = new LinkedHashMap<>();
        for (Object value : groups) {
            if (!(value instanceof Map<?, ?> group)) continue;
            String gid = text(group.get("gid"));
            String name = text(group.get("name"));
            if (gid.isBlank() || name.isBlank()) continue;
            String candidate = name.toLowerCase(Locale.ROOT);
            if (normalized.equals(candidate) || (DEFAULT_NAMES.contains(normalized) && DEFAULT_NAMES.contains(candidate)))
                matches.put(gid, Map.of("gid", gid, "label", name));
        }
        if (matches.size() == 1) return matches.values().iterator().next();
        warnings.add(matches.isEmpty()
                ? "未能从当前用户的分组列表唯一定位分组「" + requested + "」；没有唯一匹配的名称，请核对或提供 gid。"
                : "当前用户分组列表中的「" + requested + "」存在多个匹配项；请指定准确 gid，未执行统计查询。");
        return null;
    }

    private List<String> matchingNames(String message, List<Object> groups) {
        List<String> available = new ArrayList<>();
        for (Object value : groups) {
            if (value instanceof Map<?, ?> group && !text(group.get("name")).isBlank())
                available.add(text(group.get("name")));
        }
        available.addAll(DEFAULT_NAMES);
        available.sort((a, b) -> Integer.compare(b.length(), a.length()));
        boolean[] occupied = new boolean[message.length()];
        List<String> matches = new ArrayList<>();
        for (String name : available) {
            var matcher = Pattern.compile("(?iu)(?<![a-z0-9_])" + Pattern.quote(name) + "(?![a-z0-9_])").matcher(message);
            while (matcher.find()) {
                boolean overlap = false;
                for (int i = matcher.start(); i < matcher.end(); i++) overlap |= occupied[i];
                if (overlap) continue;
                for (int i = matcher.start(); i < matcher.end(); i++) occupied[i] = true;
                matches.add(name);
            }
        }
        return matches;
    }

    private Selection periods(String message, List<Token> tokens, Map<String, Object> previous,
            boolean inherit, boolean followup, List<String> warnings) {
        String temporal = DRILL_ARGUMENT.matcher(stripScopeArguments(message, tokens)).replaceAll(" ");
        String normalized = temporal.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        List<Token> starts = named(tokens, "startDate");
        List<Token> ends = named(tokens, "endDate");
        boolean explicit = !starts.isEmpty() || !ends.isEmpty();
        try {
            if (explicit) {
                if (starts.size() != ends.size()) return invalidDates(warnings, "统计查询缺少完整时间范围；每个 startDate 都需要对应的 endDate。");
                for (int i = 0; i < starts.size(); i++)
                    result.add(period(LocalDate.parse(starts.get(i).value), LocalDate.parse(ends.get(i).value), "指定期间"));
            } else {
                var matcher = DATE.matcher(temporal);
                List<String> isoDates = new ArrayList<>();
                List<Integer> endsAt = new ArrayList<>();
                List<Integer> startsAt = new ArrayList<>();
                while (matcher.find()) { isoDates.add(matcher.group(1)); startsAt.add(matcher.start()); endsAt.add(matcher.end()); }
                if (isoDates.size() >= 2) {
                    explicit = true;
                    if (isoDates.size() % 2 != 0) return invalidDates(warnings, "多个日期的期间对应关系不明确；请为每个期间提供起止日期。");
                    String between = temporal.substring(endsAt.get(0), startsAt.get(1)).toLowerCase(Locale.ROOT);
                    boolean twoDays = isoDates.size() == 2 && comparison(temporal)
                            && !Pattern.compile("(?iu)至|到|~|～|—|–|\\bto\\b|through").matcher(between).find();
                    if (twoDays) for (String day : isoDates) result.add(period(LocalDate.parse(day), LocalDate.parse(day), day));
                    else for (int i = 0; i < isoDates.size(); i += 2)
                        result.add(period(LocalDate.parse(isoDates.get(i)), LocalDate.parse(isoDates.get(i + 1)), "指定期间"));
                } else if (isoDates.size() == 1) {
                    explicit = true;
                    Map<String, Object> one = new LinkedHashMap<>();
                    dates.completeDates(temporal, one, warnings);
                    if (!dates.validDates(one, warnings)) return new Selection(List.of(), true, false, true);
                    result.add(one);
                } else {
                    LocalDate today = LocalDate.now(clock);
                    boolean todayMentioned = contains(normalized, "今天", "今日", "today");
                    boolean yesterdayMentioned = contains(normalized, "昨天", "昨日", "yesterday");
                    boolean thisWeek = contains(normalized, "本周", "这周", "this week");
                    boolean lastWeek = contains(normalized, "上周", "上一周", "previous week");
                    if (todayMentioned && yesterdayMentioned) {
                        result.add(period(today, today, "今天"));
                        result.add(period(today.minusDays(1), today.minusDays(1), "昨天"));
                    } else if (thisWeek || lastWeek) {
                        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                        if (thisWeek) result.add(period(monday, today, "本周截至今天"));
                        if (lastWeek) result.add(period(monday.minusWeeks(1), monday.minusDays(1), "上周"));
                    } else {
                        var dayRanges = DAYS.matcher(temporal);
                        while (dayRanges.find()) {
                            long count = Long.parseLong(dayRanges.group(1));
                            if (count < 1) throw new DateTimeException("nonpositive range");
                            result.add(period(today.minusDays(count - 1), today, "最近 " + count + " 天"));
                        }
                        if (result.isEmpty()) {
                            Map<String, Object> one = new LinkedHashMap<>();
                            dates.completeDates(temporal, one, warnings);
                            if (!one.isEmpty()) result.add(one);
                        }
                    }
                    explicit = !result.isEmpty() || contains(normalized, "最近", "过去", "今天", "昨天", "日期", "本月", "上月", "小时", "last ", "past ", "since ");
                }
            }
            if (result.size() == 1 && contains(normalized, "环比", "上一周期", "previous period")) {
                LocalDate start = LocalDate.parse(text(result.get(0).get("startDate")));
                LocalDate end = LocalDate.parse(text(result.get(0).get("endDate")));
                long length = ChronoUnit.DAYS.between(start, end) + 1;
                result.add(period(start.minusDays(length), start.minusDays(1), "上一等长期间"));
            } else if (result.size() == 1 && contains(normalized, "同比", "year over year")) {
                LocalDate start = LocalDate.parse(text(result.get(0).get("startDate")));
                LocalDate end = LocalDate.parse(text(result.get(0).get("endDate")));
                result.add(period(start.minusYears(1), end.minusYears(1), "上年同期"));
            }
        } catch (DateTimeException | NumberFormatException | ArithmeticException invalid) {
            return invalidDates(warnings, "统计查询起止日期无效、顺序颠倒或相对期间无效，未执行统计查询。");
        }
        if (result.isEmpty() && !explicit && inherit) {
            List<Map<String, Object>> prior = contextRows(previous.get("periods"), "startDate", "endDate", "label");
            if (prior.size() == 1 || followup) result.addAll(prior);
            else if (prior.size() > 1) return invalidDates(warnings, "上一轮涉及多个期间；请明确继续哪个期间，未自动选取其中一个。");
        }
        if (result.isEmpty()) return invalidDates(warnings, "统计查询缺少完整时间范围；请提供最近几天或明确起止日期，未执行统计查询。");
        for (Map<String, Object> period : result)
            if (!dates.validDates(period, warnings)) return new Selection(List.of(), explicit, false, true);
        return new Selection(distinct(result, "startDate", "endDate"), explicit, false, false);
    }

    private static Map<String, Object> period(LocalDate start, LocalDate end, String label) {
        if (start.isAfter(end)) throw new DateTimeException("reversed range");
        return Map.of("startDate", start.toString(), "endDate", end.toString(), "label", label);
    }

    private static Selection invalidDates(List<String> warnings, String message) {
        warnings.add(message);
        return new Selection(List.of(), true, false, true);
    }

    private static Map<String, Object> arguments(Map<String, Object> scope, Map<String, Object> period,
            Map<String, Object> extras) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("gid", "fullShortUrl")) if (scope.containsKey(key)) result.put(key, scope.get(key));
        for (String key : List.of("startDate", "endDate")) if (period.containsKey(key)) result.put(key, period.get(key));
        result.putAll(extras);
        return result;
    }

    private static Map<String, Object> extras(List<Token> tokens) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Token token : tokens) {
            if (!Set.of("current", "size", "orderTag", "snapshotId", "cursor").contains(token.name)) continue;
            Object value = token.value;
            if (Set.of("current", "size").contains(token.name)) {
                try { value = Long.parseLong(token.value); } catch (NumberFormatException ignored) { }
            }
            result.put(token.name, value);
        }
        return result;
    }

    private static List<Token> tokens(String message) {
        List<Token> result = new ArrayList<>();
        var matcher = ARGUMENT.matcher(message);
        while (matcher.find()) {
            String name = canonical(matcher.group(1));
            result.add(new Token(name, clean(matcher.group(2)), matcher.start()));
            if ("gid".equals(name)) {
                int offset = matcher.end();
                var additional = Pattern.compile("[,，]([a-zA-Z0-9_-]+)(?![a-zA-Z0-9_]|[:=：])").matcher(message);
                while (offset < message.length() && additional.region(offset, message.length()).lookingAt()) {
                    result.add(new Token("gid", additional.group(1), additional.start()));
                    offset = additional.end();
                }
            }
        }
        return result;
    }

    private static String canonical(String name) {
        for (String value : List.of("gid", "fullShortUrl", "groupName", "startDate", "endDate", "current", "size", "orderTag", "snapshotId", "cursor", "metric", "limit"))
            if (name.equalsIgnoreCase(value)) return value;
        throw new IllegalArgumentException("Unknown argument");
    }

    private static List<Token> named(List<Token> tokens, String name) {
        return new ArrayList<>(tokens.stream().filter(token -> name.equals(token.name)).toList());
    }

    private static String stripArguments(String message, List<Token> ignored) {
        return DRILL_ARGUMENT.matcher(ARGUMENT.matcher(message).replaceAll(" ")).replaceAll(" ");
    }

    private static String stripScopeArguments(String message, List<Token> ignored) {
        StringBuilder result = new StringBuilder();
        var matcher = ARGUMENT.matcher(message);
        int last = 0;
        while (matcher.find()) {
            result.append(message, last, matcher.start());
            result.append(Set.of("gid", "fullShortUrl", "groupName").contains(canonical(matcher.group(1))) ? " " : matcher.group());
            last = matcher.end();
        }
        return URL.matcher(result.append(message.substring(last)).toString()).replaceAll(" ");
    }

    private static String clean(String value) {
        String result = value.trim().replaceAll("[.。；;]+$", "");
        if (result.length() > 1 && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'")) || (result.startsWith("「") && result.endsWith("」"))
                || (result.startsWith("“") && result.endsWith("”")))) result = result.substring(1, result.length() - 1);
        return result;
    }

    private static List<Map<String, Object>> distinct(List<Map<String, Object>> rows, String first, String second) {
        Map<List<String>, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) unique.putIfAbsent(List.of(text(row.get(first)), text(row.get(second))), Map.copyOf(row));
        return new ArrayList<>(unique.values());
    }

    private static List<Map<String, Object>> contextRows(Object value, String... fields) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(value instanceof List<?> values)) return result;
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> row) || text(row.get(fields[0])).isBlank()) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String field : fields) if (row.get(field) instanceof String text && !text.isBlank()) copy.put(field, text);
            result.add(Map.copyOf(copy));
        }
        return result;
    }

    private static Map<String, Object> safeContext(Map<String, Object> previous) {
        Map<String, Object> result = new LinkedHashMap<>(context(
                contextRows(previous.get("scopes"), "gid", "fullShortUrl", "label"),
                contextRows(previous.get("periods"), "startDate", "endDate", "label"), text(previous.get("intent"))));
        Map<String, Object> ranking = previousRanking(previous);
        if (!ranking.isEmpty()) result.put("ranking", ranking);
        Map<String, Object> drill = previousDrill(previous);
        if (!drill.isEmpty()) result.put("drill", drill);
        String selectedGid = text(previous.get("selectedGid"));
        if (!selectedGid.isBlank()) result.put("selectedGid", selectedGid);
        return result;
    }

    private static Map<String, Object> previousRanking(Map<String, Object> previous) {
        if (!Intent.parse(previous.get("intent")).rank || !(previous.get("ranking") instanceof Map<?, ?> ranking))
            return Map.of();
        String metric = text(ranking.get("metric")).toLowerCase(Locale.ROOT);
        if (!Set.of("pv", "uv", "uip").contains(metric)) return Map.of();
        try {
            int limit = Integer.parseInt(text(ranking.get("limit")));
            return limit >= 1 && limit <= 50 ? Map.of("metric", metric, "limit", limit) : Map.of();
        } catch (NumberFormatException invalid) {
            return Map.of();
        }
    }

    private static Map<String, Object> context(List<Map<String, Object>> scopes, List<Map<String, Object>> periods, String intent) {
        return Map.of("scopes", List.copyOf(scopes), "periods", List.copyOf(periods), "intent", intent);
    }

    private static boolean comparison(String message) {
        return contains(message.toLowerCase(Locale.ROOT), "对比", "比较", "环比", "同比", "compare", "comparison", "versus", " vs ");
    }

    private static boolean dateOnly(String message) {
        return DATE.matcher(message).find() || DAYS.matcher(message).find()
                || contains(message, "今天", "昨天", "本周", "上周", "today", "yesterday");
    }

    private static Intent intent(String message) {
        boolean rank = contains(message, "排名", "排行") || NATURAL_RANKING.matcher(message).find()
                || Pattern.compile("(?iu)\\btop\\s*[0-9]+").matcher(message).find();
        boolean compare = comparison(message);
        String dimensionClauses = NATURAL_RANKING.matcher(message).replaceAll(" ");
        boolean drill = contains(message, "下钻", "清除筛选", "去掉筛选", "清空筛选", "清除过滤", "去掉过滤")
                || NATURAL_DIMENSIONS.matcher(dimensionClauses).find() || NATURAL_FILTER.matcher(message).find()
                || DRILL_ARGUMENT.matcher(message).find() || Pattern.compile("(?iu)\\bdimensions\\s*[:=：]").matcher(message).find();
        boolean records = contains(message, "访问记录", "访问日志", "access records", "access record", "records")
                || (!drill && message.contains("明细"));
        boolean stats = contains(message, "stats", "statistics", "performance", "traffic", "趋势", "高峰", "统计", "流量",
                "汇总", "构成", "表现", "数据", "对比", "比较", "环比", "同比", "compare", " pv", " uv", " uip")
                || rank || compare || drill || (!records && contains(message, "分析", "诊断", "analysis", "analyze"));
        boolean links = contains(message, "短链列表", "短链接列表", "短链分页", "短链接分页", "查看短链", "查询短链",
                "link list", "links list", "list link", "short link page", "link page", "links page", "page links",
                "page short links", "link paging", "links paging", "show links", "all links");
        if (rank && !contains(message, "短链列表", "短链接列表", "短链分页", "短链接分页",
                "link list", "links list", "list link", "short link page", "link page", "links page", "page links",
                "page short links", "link paging", "links paging")) links = false;
        boolean groups = contains(message, "列出分组", "查看分组", "查询分组", "分组列表", "我的分组", "list groups", "show group", "group list", "all groups", "groups and", "groups,");
        return new Intent(stats, records, links, groups, compare, rank, drill);
    }

    private static boolean contains(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private record Token(String name, String value, int start) { }
    private record Selection(List<Map<String, Object>> values, boolean explicit, boolean needsGroups, boolean invalid) { }
    private record Intent(boolean stats, boolean records, boolean links, boolean groups, boolean compare, boolean rank, boolean drill) {
        boolean any() { return stats || records || links || groups; }
        String text() {
            List<String> result = new ArrayList<>();
            if (stats && records) result.add("STATS_AND_RECORDS");
            else if (stats) result.add("STATS");
            else if (records) result.add("RECORDS");
            if (links) result.add("LINKS");
            if (groups) result.add("GROUPS");
            if (compare) result.add("COMPARE");
            if (rank) result.add("RANK");
            if (drill) result.add("DRILL");
            return String.join(",", result);
        }
        static Intent parse(Object value) {
            String intent = CampaignAnalysisPlanner.text(value);
            return new Intent(intent.contains("STATS"), intent.contains("RECORDS"), intent.contains("LINKS"),
                    intent.contains("GROUPS"), intent.contains("COMPARE"), intent.contains("RANK"), intent.contains("DRILL"));
        }
    }
}
