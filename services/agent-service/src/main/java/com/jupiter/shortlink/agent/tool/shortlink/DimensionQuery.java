package com.jupiter.shortlink.agent.tool.shortlink;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Canonical dimensions and exact filters shared by requests and frozen-result proof checks. */
final class DimensionQuery {
    private static final Set<String> ALLOWED = Set.of("day", "hour", "weekday", "country", "province",
            "device", "os", "browser", "isp", "refererDomain");

    private DimensionQuery() {}

    static List<String> dimensions(Object input) {
        require(input instanceof List<?>, "请指定联合分析维度");
        var values = (List<?>) input;
        require(!values.isEmpty() && values.size() <= 3, "联合分析需要 1 至 3 个维度");
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : values) {
            require(value instanceof String && ALLOWED.contains(value), "分析维度不在支持范围内");
            require(seen.add(value.toString()), "联合分析维度不能重复");
            result.add(value.toString());
        }
        return List.copyOf(result);
    }

    static List<Map<String, Object>> filters(Object input) {
        if (input == null) return List.of();
        require(input instanceof List<?>, "维度筛选格式无效");
        var values = (List<?>) input;
        require(values.size() <= 8, "最多支持 8 个维度筛选");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : values) {
            require(value instanceof Map<?, ?>, "维度筛选格式无效");
            var source = (Map<?, ?>) value;
            Object dimension = source.get("dimension"), operator = source.get("operator");
            require(dimension instanceof String && ALLOWED.contains(dimension), "筛选维度不在支持范围内");
            require(seen.add(dimension.toString()), "同一维度不能提供多个筛选条件");
            require("IN".equals(operator) || "IS_UNKNOWN".equals(operator), "筛选操作仅支持 IN 或 IS_UNKNOWN");
            var filter = new LinkedHashMap<String, Object>();
            filter.put("dimension", dimension);
            filter.put("operator", operator);
            Object raw = source.get("values");
            if ("IS_UNKNOWN".equals(operator)) {
                require(raw == null || (raw instanceof List<?> list && list.isEmpty()), "未知值筛选不能同时指定具体值");
            } else {
                require(raw instanceof List<?> list && !list.isEmpty() && list.size() <= 20,
                        "每个 IN 筛选需要 1 至 20 个字符串值");
                Set<String> sorted = new TreeSet<>();
                for (Object item : (List<?>) raw) {
                    require(item instanceof String text && !text.isBlank() && text.length() <= 256
                            && text.chars().noneMatch(Character::isISOControl), "筛选值必须为非空字符串，且不能包含控制字符");
                    String text = item.toString();
                    validateTemporalValue(dimension.toString(), text);
                    sorted.add(text);
                }
                filter.put("values", List.copyOf(sorted));
            }
            result.add(filter);
        }
        result.sort(Comparator.comparing(filter -> filter.get("dimension").toString()));
        return List.copyOf(result);
    }

    static boolean matches(Map<String, Object> meta, Map<String, Object> query) {
        try {
            return "DIMENSION_BREAKDOWN".equals(meta.get("queryKind"))
                    && meta.containsKey("dimensions") && meta.containsKey("filters")
                    && dimensions(meta.get("dimensions")).equals(dimensions(query.get("dimensions")))
                    && filters(meta.get("filters")).equals(filters(query.get("filters")));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void validateTemporalValue(String dimension, String value) {
        if ("day".equals(dimension)) {
            try {
                require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                        && LocalDate.parse(value).toString().equals(value), "日期筛选值必须为有效的 yyyy-MM-dd 日期");
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("日期筛选值必须为有效的 yyyy-MM-dd 日期");
            }
        } else if ("hour".equals(dimension) || "weekday".equals(dimension)) {
            require(value.matches("0|[1-9][0-9]?"), "小时或星期筛选必须使用标准整数文本");
            int number = Integer.parseInt(value);
            require("hour".equals(dimension) ? number <= 23 : number >= 1 && number <= 7,
                    "小时或星期筛选值超出有效范围");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }
}
