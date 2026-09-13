package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves conversational scope only from the current user's freshly fetched group list. */
final class CampaignQueryScopeResolver {
    private static final Pattern RELATIVE_DAYS =
            Pattern.compile(
                    "(?iu)(?:最近|过去|近|last\\s*|past\\s*|recent\\s*)([0-9]+)\\s*(?:天|日|days?\\b)");
    private static final Pattern RELATIVE_HOURS =
            Pattern.compile("(?iu)(?:最近|过去|近|last\\s*|past\\s*)([0-9]+)\\s*(?:小时|hours?\\b)");
    private static final Pattern ISO_DATE =
            Pattern.compile("(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])");
    private static final Pattern OPEN_DATE_BEFORE =
            Pattern.compile("(?iu)(?:(?:截至|截止)(?:到|至|在)?|早于|晚于|从|自从|自|到|至|"
                    + "\\b(?:before|after|since|until|from|through|starting|beginning|as\\s+of|up\\s+to))"
                    + "\\s*[\"'“‘]?\\s*$");
    private static final Pattern OPEN_DATE_AFTER =
            Pattern.compile("(?iu)^\\s*[\"'”’]?\\s*(?:的\\s*)?(?:(?:当天|当日)\\s*)?(?:(?:及其?|或)?"
                    + "(?:之前|以前|之后|以后|前|后)|起|开始|以来|至今|"
                    + "\\b(?:onwards?|forward|or\\s+(?:earlier|later)|and\\s+(?:earlier|later)))");
    private static final Pattern SINGLE_DAY_BEFORE =
            Pattern.compile("(?iu)(?:在|\\bon)\\s*[\"'“‘]?\\s*$");
    private static final Pattern SINGLE_DAY_AFTER =
            Pattern.compile("(?iu)^\\s*[\"'”’]?\\s*(?:当天|当日|这一天|那一天|"
                    + "的\\s*(?:访问|流量|点击|跳转|统计|数据|表现|记录|明细|短链|pv|uv|uip))");
    private static final Pattern CLOCK_TIME_AFTER_DATE =
            Pattern.compile("(?iu)^(?:t|\\s+)[0-9]{1,2}:[0-9]{2}");
    private final Clock clock;

    CampaignQueryScopeResolver(Clock clock) {
        this.clock = clock;
    }

    Optional<String> resolveGid(String message, List<Object> groups, List<String> warnings) {
        var resolution =
                com.jupiter.shortlink.agent.harness.security.OwnedGroupNameResolver.resolve(
                        message, groups);
        if (resolution.status()
                == com.jupiter.shortlink.agent.harness.security.OwnedGroupNameResolver.Status
                        .MATCHED) return Optional.of(resolution.gid());
        warnings.add(
                resolution.status()
                                == com.jupiter.shortlink.agent.harness.security
                                        .OwnedGroupNameResolver.Status.MISSING
                        ? "未能从当前用户的分组列表唯一定位目标分组；请使用列表中的分组名或 gid，未执行统计查询。"
                        : "当前用户的分组列表存在多个匹配项；请指定准确 gid，未执行统计查询。");
        return Optional.empty();
    }

    /** Relative day ranges include today. Hour ranges disclose the API's calendar-day precision. */
    void completeDates(String message, Map<String, Object> arguments, List<String> warnings) {
        if (arguments.containsKey("startDate") || arguments.containsKey("endDate")) return;
        String normalized = normalize(message);
        if (completeSingleDay(normalized, arguments, warnings)) return;
        LocalDate today = LocalDate.now(clock);
        var days = RELATIVE_DAYS.matcher(normalized.replaceAll("(最近|过去|近)\\s+", "$1"));
        var hours = RELATIVE_HOURS.matcher(normalized.replaceAll("(最近|过去|近)\\s+", "$1"));
        try {
            if (days.find()) {
                long count = positiveCount(days.group(1));
                dates(arguments, today.minusDays(count - 1), today);
            } else if (hours.find()) {
                long count = positiveCount(hours.group(1));
                LocalDate start = ZonedDateTime.now(clock).minusHours(count).toLocalDate();
                dates(arguments, start, today);
                warnings.add(
                        "统计接口按 "
                                + clock.getZone()
                                + " 自然日查询；最近 "
                                + count
                                + " 小时按覆盖日期 "
                                + start
                                + " 至 "
                                + today
                                + " 汇总，不能视为精确滚动小时窗口。");
            } else if (containsAny(normalized, "最近一周", "近一周", "过去一周", "last week", "past week")) {
                dates(arguments, today.minusDays(6), today);
            } else if (containsAny(normalized, "昨天", "昨日", "yesterday")) {
                dates(arguments, today.minusDays(1), today.minusDays(1));
            } else if (containsAny(normalized, "今天", "今日", "today")) {
                dates(arguments, today, today);
            }
        } catch (NumberFormatException | DateTimeException | ArithmeticException invalid) {
            warnings.add("相对时间范围无效；请提供有效的正整数天数或明确起止日期。");
        }
    }

    /** A single mentioned date is a day only when the request says so, never an open boundary. */
    private static boolean completeSingleDay(String message, Map<String, Object> arguments,
            List<String> warnings) {
        var matcher = ISO_DATE.matcher(message);
        if (!matcher.find()) return false;
        String date = matcher.group(1);
        String before = message.substring(0, matcher.start());
        String after = message.substring(matcher.end());
        if (matcher.find()) return false; // Existing explicit range extraction retains precedence.
        if (OPEN_DATE_BEFORE.matcher(before).find() || OPEN_DATE_AFTER.matcher(after).find()) {
            warnings.add("统计时间只提供了单边边界；请补充完整起止日期，未将该日期当作单日查询。");
            return true;
        }
        if (CLOCK_TIME_AFTER_DATE.matcher(after).find()) return false;
        if (!SINGLE_DAY_BEFORE.matcher(before).find() && !SINGLE_DAY_AFTER.matcher(after).find()) return false;
        try {
            LocalDate day = LocalDate.parse(date);
            // Both inclusive calendar dates identify this Shanghai business day; the shared
            // statistics facade supplies the exclusive next-day timestamp boundary.
            dates(arguments, day, day);
        } catch (DateTimeException invalid) {
            warnings.add("单日统计日期无效；请提供真实存在的日历日期，未执行统计查询。");
        }
        return true;
    }

    boolean validDates(Map<String, Object> arguments, List<String> warnings) {
        if (!arguments.containsKey("startDate") || !arguments.containsKey("endDate")) {
            warnings.add("统计查询缺少完整时间范围；请提供最近几天或明确起止日期，未执行统计查询。");
            return false;
        }
        try {
            LocalDate start = LocalDate.parse(String.valueOf(arguments.get("startDate")));
            LocalDate end = LocalDate.parse(String.valueOf(arguments.get("endDate")));
            if (start.isAfter(end)) throw new DateTimeException("reversed range");
            return true;
        } catch (DateTimeException invalid) {
            warnings.add("统计查询起止日期无效或顺序颠倒，未执行统计查询。");
            return false;
        }
    }

    private static void dates(Map<String, Object> target, LocalDate start, LocalDate end) {
        target.put("startDate", start.toString());
        target.put("endDate", end.toString());
    }

    private static long positiveCount(String value) {
        long count = Long.parseLong(value);
        if (count < 1) throw new NumberFormatException("positive count required");
        return count;
    }

    private static boolean containsAny(String value, String... options) {
        for (String option : options) if (value.contains(option)) return true;
        return false;
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
    }
}
