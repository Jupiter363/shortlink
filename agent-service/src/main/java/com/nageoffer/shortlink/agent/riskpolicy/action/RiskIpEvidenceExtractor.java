package com.nageoffer.shortlink.agent.riskpolicy.action;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RiskIpEvidenceExtractor {

    private static final String SHORT_LINK_STATS_TOOL = "get_short_link_stats";
    private static final Pattern IP_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> SAFE_ROW_KEYS = Set.of("ipHash", "maskedIp", "cnt");

    public Optional<RiskIpEvidence> extract(
            List<Map<String, Object>> toolExecutions,
            String targetFullShortUrl
    ) {
        if (toolExecutions == null || toolExecutions.isEmpty() || !hasText(targetFullShortUrl)) {
            return Optional.empty();
        }
        RiskIpEvidence highest = null;
        for (Map<String, Object> execution : toolExecutions) {
            if (!isMatchingExecution(execution, targetFullShortUrl)) {
                continue;
            }
            Object topIpStats = mapValue(execution.get("data")).get("topIpStats");
            if (!(topIpStats instanceof Collection<?> rows)) {
                continue;
            }
            for (Object rowValue : rows) {
                if (!(rowValue instanceof Map<?, ?> row)) {
                    continue;
                }
                Optional<RiskIpEvidence> candidate = safeEvidence(row);
                if (candidate.isPresent()
                        && (highest == null || candidate.get().count() > highest.count())) {
                    highest = candidate.get();
                }
            }
        }
        return Optional.ofNullable(highest);
    }

    private boolean isMatchingExecution(Map<String, Object> execution, String targetFullShortUrl) {
        if (execution == null
                || !SHORT_LINK_STATS_TOOL.equals(execution.get("name"))
                || !Boolean.TRUE.equals(execution.get("success"))) {
            return false;
        }
        Map<?, ?> arguments = mapValue(execution.get("arguments"));
        return targetFullShortUrl.equals(arguments.get("fullShortUrl"));
    }

    private Optional<RiskIpEvidence> safeEvidence(Map<?, ?> row) {
        if (row.size() != SAFE_ROW_KEYS.size() || !row.keySet().equals(SAFE_ROW_KEYS)) {
            return Optional.empty();
        }
        Object ipHashValue = row.get("ipHash");
        Object maskedIpValue = row.get("maskedIp");
        String ipHash = ipHashValue instanceof String value ? value : "";
        String maskedIp = maskedIpValue instanceof String value ? value : "";
        long count = positiveCount(row.get("cnt"));
        if (!IP_HASH_PATTERN.matcher(ipHash).matches() || !hasText(maskedIp) || count <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RiskIpEvidence(ipHash, maskedIp, count));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private long positiveCount(Object value) {
        if (value == null) {
            return -1L;
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1L;
        }
    }

    private Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
