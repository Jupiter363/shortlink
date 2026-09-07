package com.jupiter.shortlink.admin.dto.resp.analytics;

import java.util.List;
import java.util.Map;

/** Statistics and provenance stay separate; missing metrics remain missing. */
public record StatsEnvelope(
        Map<String, Object> metrics, List<Map<String, Object>> items, Map<String, Object> meta) {}
