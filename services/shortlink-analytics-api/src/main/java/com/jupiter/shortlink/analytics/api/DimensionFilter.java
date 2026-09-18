package com.jupiter.shortlink.analytics.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Exact observed values; missing data is selected explicitly with IS_UNKNOWN. */
public record DimensionFilter(String dimension, String operator,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> values) {}
