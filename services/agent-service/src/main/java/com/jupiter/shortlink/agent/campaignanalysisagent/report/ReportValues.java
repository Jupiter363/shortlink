package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded JSON-value copier used by the internal report contract. */
final class ReportValues {
    private static final int MAX_DEPTH = 128;

    private ReportValues() {}

    static Map<String, Object> json(Map<String, Object> values) {
        if (values == null) return Map.of();
        Object frozen = freeze(values, new IdentityHashMap<>(), 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) frozen;
        return result;
    }

    static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal)
            return value;
        if (value instanceof Double number && Double.isFinite(number)) return number;
        if (value instanceof Float number && Float.isFinite(number)) return number;
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>))
            throw new IllegalArgumentException("REPORT_JSON_VALUE_INVALID");
        if (depth >= MAX_DEPTH) throw new IllegalArgumentException("REPORT_JSON_TOO_DEEP");
        if (visiting.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("REPORT_JSON_CYCLE");
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || key.isBlank())
                        throw new IllegalArgumentException("REPORT_JSON_KEY_INVALID");
                    copy.put(key, freeze(entry.getValue(), visiting, depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            List<Object> copy = new ArrayList<>();
            for (Object element : (List<?>) value) copy.add(freeze(element, visiting, depth + 1));
            return Collections.unmodifiableList(copy);
        } finally {
            visiting.remove(value);
        }
    }
}
