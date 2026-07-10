package com.nageoffer.shortlink.agent.securityriskagent.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record RiskAnalysisInput(
        String batchId,
        String gid,
        LocalDateTime profileWindowEnd,
        List<RiskProfileTargetRef> candidates
) {

    public RiskAnalysisInput {
        batchId = requireText(batchId, "batchId");
        gid = requireText(gid, "gid");
        profileWindowEnd = Objects.requireNonNull(profileWindowEnd, "profileWindowEnd must not be null");
        candidates = candidates == null
                ? List.of()
                : candidates.stream().filter(Objects::nonNull).distinct().toList();
    }

    public Map<String, Object> toStateValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("batchId", batchId);
        value.put("gid", gid);
        value.put("profileWindowEnd", profileWindowEnd.toString());
        value.put("candidates", candidates.stream()
                .map(candidate -> Map.<String, Object>of(
                        "domain", candidate.domain(),
                        "shortUri", candidate.shortUri()
                ))
                .toList());
        return value;
    }

    public static Optional<RiskAnalysisInput> fromStateValue(Object value) {
        if (value instanceof RiskAnalysisInput input) {
            return Optional.of(input);
        }
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        try {
            String batchId = stringValue(map.get("batchId"));
            String gid = stringValue(map.get("gid"));
            LocalDateTime profileWindowEnd = LocalDateTime.parse(stringValue(map.get("profileWindowEnd")));
            List<RiskProfileTargetRef> candidates = candidates(map.get("candidates"));
            return Optional.of(new RiskAnalysisInput(batchId, gid, profileWindowEnd, candidates));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static List<RiskProfileTargetRef> candidates(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(candidate -> new RiskProfileTargetRef(
                        stringValue(candidate.get("domain")),
                        stringValue(candidate.get("shortUri"))
                ))
                .toList();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
