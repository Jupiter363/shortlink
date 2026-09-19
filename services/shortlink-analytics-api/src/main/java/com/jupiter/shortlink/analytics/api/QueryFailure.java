package com.jupiter.shortlink.analytics.api;

import java.util.Map;
import java.util.Set;

public final class QueryFailure extends RuntimeException {
    public final String code;
    public final Map<String, Object> details;

    public QueryFailure(String code, String message) {
        this(code, message, Map.of());
    }

    /** Only local admission rejection may claim that no request was admitted. */
    public QueryFailure(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        if (details == null || details.isEmpty()) {
            this.details = Map.of();
        } else {
            if (!"QUERY_CAPACITY_EXHAUSTED".equals(code)
                    || !details.keySet().equals(Set.of("admitted", "capacityKind"))
                    || !Boolean.FALSE.equals(details.get("admitted"))
                    || !(details.get("capacityKind") instanceof String kind)
                    || !Set.of("ACTIVE_EXECUTION", "RESULT_STORAGE", "RECOVERY_IDENTITY").contains(kind))
                throw new IllegalArgumentException("Invalid query failure details");
            this.details = Map.copyOf(details);
        }
    }
}
