package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies JSON values rather than retaining mutable planner or caller state. */
final class ImmutablePlanValues {
    /** Structural recursion bound, independent of the number of objects or report sections. */
    static final int MAX_JSON_NESTING = 128;
    private ImmutablePlanValues() { }

    static Map<String, Object> json(Map<String, Object> values) {
        if (values == null) return Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) freeze(values, new IdentityHashMap<>(), 0);
        return frozen;
    }

    static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    static <T> Map<String, T> map(Map<String, T> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double number && Double.isFinite(number)) return value;
        if (value instanceof Float number && Float.isFinite(number)) return value;
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            throw new IllegalArgumentException("Plan values must be finite JSON values");
        }
        if (depth >= MAX_JSON_NESTING) {
            throw new IllegalArgumentException("Plan JSON nesting must not exceed 128 containers");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Plan values must not contain cycles");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                        throw new IllegalArgumentException("Plan object keys must be nonblank strings");
                    }
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
