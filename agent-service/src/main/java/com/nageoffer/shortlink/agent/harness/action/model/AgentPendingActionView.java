package com.nageoffer.shortlink.agent.harness.action.model;

import java.lang.reflect.Array;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record AgentPendingActionView(
        String actionId,
        String agentType,
        String actionType,
        AgentActionStatus status,
        String gid,
        String targetType,
        Map<String, Object> target,
        String title,
        String summary,
        Map<String, Object> evidenceSummary,
        int attemptCount,
        long version,
        LocalDateTime expireTime,
        String rejectionReason,
        String rejectionReviewAction,
        Map<String, Object> result,
        Map<String, Object> failure
) {

    private static final String BLOCK_IP_ACTION = "risk.block-ip";
    private static final int MAX_REJECTION_REASON_LENGTH = 2048;
    private static final int MAX_REJECTION_REVIEW_ACTION_LENGTH = 32;
    private static final Object DROP_VALUE = new Object();
    private static final Pattern IPV4_ADDRESS_PATTERN = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV6_KEY_PATTERN = Pattern.compile("(?i)^[0-9a-f:.]+$");
    private static final Pattern IPV4_TEXT_PATTERN =
            Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern IPV6_TEXT_PATTERN = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])"
    );
    private static final Pattern BEARER_TOKEN_PATTERN =
            Pattern.compile("(?i)\\b(bearer)\\s+[^\\s,;]+");
    private static final Pattern SK_API_KEY_PATTERN =
            Pattern.compile("(?i)\\bsk-[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(token|password|secret|apiKey|credential)\\s*([=:])\\s*[^\\s,;]+"
    );
    private static final Pattern JSON_SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(\\\"(?:token|password|secret|apiKey|credential)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")"
    );
    private static final Pattern IDENTITY_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(userId|visitorId|username|user|visitor)\\s*([=:])\\s*[^\\s,;]+"
    );
    private static final Pattern ACRONYM_WORD_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_WORD_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Set<String> METRIC_CONTAINER_SUFFIXES = Set.of(
            "metrics",
            "aggregates",
            "statistics",
            "stats",
            "windows",
            "distribution",
            "breakdown",
            "trend",
            "counters",
            "measures",
            "indicators"
    );
    private static final Set<String> EXPLICIT_METRIC_NAMES = Set.of(
            "pv2h",
            "uv2h",
            "pv24h",
            "uv24h",
            "pv7d",
            "uv7d",
            "pvperuv",
            "riskscore",
            "anomalyscore",
            "groupriskscore",
            "avgriskscore",
            "maxriskscore",
            "pvgrowth2hvs24havg",
            "totalshortlinksscanned"
    );
    private static final Set<String> METRIC_NAME_TOKENS = Set.of(
            "count",
            "score",
            "rate",
            "ratio",
            "share",
            "total",
            "average",
            "percent",
            "percentage",
            "size",
            "volume",
            "duration",
            "latency",
            "threshold",
            "avg",
            "scanned"
    );
    private static final Set<String> EXPLICIT_NUMERIC_SERIES_NAMES = Set.of(
            "riskscores",
            "requestcounts",
            "risktrend7d",
            "trend",
            "windows"
    );
    private static final Set<String> NUMERIC_SERIES_TOKENS = Set.of(
            "counts",
            "scores",
            "rates",
            "ratios",
            "shares",
            "totals",
            "averages",
            "percents",
            "percentages",
            "sizes",
            "volumes",
            "durations",
            "latencies",
            "thresholds",
            "avgs"
    );

    public AgentPendingActionView {
        target = immutableMap(target);
        evidenceSummary = immutableMap(evidenceSummary);
        rejectionReason = limit(rejectionReason, MAX_REJECTION_REASON_LENGTH);
        rejectionReviewAction = limit(rejectionReviewAction, MAX_REJECTION_REVIEW_ACTION_LENGTH);
        result = immutableMap(result);
        failure = failure == null ? null : immutableMap(failure);
    }

    public static Map<String, Object> summarizeEvidence(Map<String, ?> evidence) {
        return sanitizeEvidenceMap(evidence, false, true);
    }

    public static Map<String, Object> sanitizeFinalResult(
            AgentActionType actionType,
            Map<String, ?> result
    ) {
        boolean maskPolicyKey = actionType != null && BLOCK_IP_ACTION.equals(actionType.value());
        return sanitizeResultMap(result, maskPolicyKey);
    }

    static Map<String, Object> immutableMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(immutableValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static Map<String, Object> sanitizeEvidenceMap(
            Map<?, ?> source,
            boolean metricsContext,
            boolean namedMetricsAllowed
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((rawKey, value) -> {
            if (!(rawKey instanceof String key)) {
                return;
            }
            if (isIpLiteralKey(key)) {
                return;
            }
            String normalizedKey = normalizeKey(key);
            if (isIdentityAggregateCountKey(normalizedKey)) {
                if (value instanceof Number && (metricsContext || namedMetricsAllowed)) {
                    sanitized.put(key, value);
                }
                return;
            }
            if (isSensitiveKey(normalizedKey)) {
                return;
            }
            if ("reasoncodes".equals(normalizedKey)) {
                Object reasonCodes = sanitizeReasonCodes(value);
                if (hasContent(reasonCodes)) {
                    sanitized.put(key, reasonCodes);
                }
                return;
            }
            if (isSafeEvidenceTextKey(normalizedKey)) {
                if (value instanceof String text) {
                    sanitized.put(key, text);
                }
                return;
            }
            if (value instanceof Number
                    && (metricsContext || namedMetricsAllowed)
                    && isNumericMetricKey(key, normalizedKey)) {
                sanitized.put(key, value);
                return;
            }
            if (isContainer(value)) {
                boolean nestedMetricsContext = metricsContext || isMetricContainerKey(normalizedKey);
                boolean allowBareNumbers = isNumericMetricSeriesKey(key, normalizedKey);
                Object nested = sanitizeEvidenceContainer(
                        value,
                        nestedMetricsContext,
                        nestedMetricsContext,
                        allowBareNumbers
                );
                if (hasContent(nested)) {
                    sanitized.put(key, nested);
                }
            }
        });
        return sanitized.isEmpty() ? Map.of() : Collections.unmodifiableMap(sanitized);
    }

    private static Object sanitizeEvidenceContainer(
            Object value,
            boolean metricsContext,
            boolean namedMetricsAllowed,
            boolean allowBareNumbers
    ) {
        if (value instanceof Map<?, ?> map) {
            return sanitizeEvidenceMap(map, metricsContext, namedMetricsAllowed);
        }
        List<Object> sanitized = new ArrayList<>();
        for (Object item : elements(value)) {
            if (item instanceof Number && allowBareNumbers) {
                sanitized.add(item);
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> nested = sanitizeEvidenceMap(
                        map,
                        metricsContext,
                        namedMetricsAllowed
                );
                if (!nested.isEmpty()) {
                    sanitized.add(nested);
                }
                continue;
            }
            if (isContainer(item)) {
                Object nested = sanitizeEvidenceContainer(
                        item,
                        metricsContext,
                        namedMetricsAllowed,
                        allowBareNumbers
                );
                if (hasContent(nested)) {
                    sanitized.add(nested);
                }
            }
        }
        return sanitized.isEmpty() ? List.of() : Collections.unmodifiableList(sanitized);
    }

    private static Object sanitizeReasonCodes(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?>) {
            return null;
        }
        if (!isContainer(value)) {
            return null;
        }
        List<Object> sanitized = new ArrayList<>();
        for (Object item : elements(value)) {
            if (item instanceof String text) {
                sanitized.add(text);
            } else if (item instanceof Enum<?> enumValue) {
                sanitized.add(enumValue.name());
            }
        }
        return sanitized.isEmpty() ? List.of() : Collections.unmodifiableList(sanitized);
    }

    private static Map<String, Object> sanitizeResultMap(Map<?, ?> source, boolean maskPolicyKey) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((rawKey, value) -> {
            if (!(rawKey instanceof String key)) {
                return;
            }
            if (isIpLiteralKey(key)) {
                return;
            }
            String normalizedKey = normalizeKey(key);
            if (isIdentityAggregateCountKey(normalizedKey)) {
                if (value instanceof Number) {
                    sanitized.put(key, value);
                }
                return;
            }
            if (isSensitiveKey(normalizedKey)) {
                return;
            }
            if ("policykey".equals(normalizedKey)) {
                if (value instanceof String text) {
                    sanitized.put(key, maskPolicyKey ? maskPolicyKey(text) : text);
                }
                return;
            }
            Object sanitizedValue = sanitizeResultValue(value, maskPolicyKey);
            if (sanitizedValue != DROP_VALUE) {
                sanitized.put(key, sanitizedValue);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Collections.unmodifiableMap(sanitized);
    }

    private static Object sanitizeResultValue(Object value, boolean maskPolicyKey) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return sanitizeResultText(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeResultMap(map, maskPolicyKey);
        }
        if (isContainer(value)) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : elements(value)) {
                Object sanitizedItem = sanitizeResultValue(item, maskPolicyKey);
                if (sanitizedItem != DROP_VALUE) {
                    sanitized.add(sanitizedItem);
                }
            }
            return Collections.unmodifiableList(sanitized);
        }
        return DROP_VALUE;
    }

    private static String sanitizeResultText(String text) {
        String sanitized = IPV4_TEXT_PATTERN.matcher(text).replaceAll("***");
        sanitized = IPV6_TEXT_PATTERN.matcher(sanitized).replaceAll("***");
        sanitized = JSON_SENSITIVE_ASSIGNMENT_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + "***" + match.group(3));
        sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + " ***");
        sanitized = SK_API_KEY_PATTERN.matcher(sanitized).replaceAll("***");
        sanitized = SENSITIVE_ASSIGNMENT_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + match.group(2) + "***");
        return IDENTITY_ASSIGNMENT_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + match.group(2) + "***");
    }

    private static Iterable<?> elements(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        int length = Array.getLength(value);
        List<Object> elements = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            elements.add(Array.get(value, index));
        }
        return elements;
    }

    private static boolean isContainer(Object value) {
        return value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || value != null && value.getClass().isArray();
    }

    private static boolean hasContent(Object value) {
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return value != null;
    }

    private static boolean isSafeEvidenceTextKey(String normalizedKey) {
        return "maskedip".equals(normalizedKey)
                || "eventid".equals(normalizedKey)
                || "batchid".equals(normalizedKey);
    }

    private static boolean isMetricContainerKey(String normalizedKey) {
        for (String suffix : METRIC_CONTAINER_SUFFIXES) {
            if (normalizedKey.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumericMetricKey(String key, String normalizedKey) {
        return EXPLICIT_METRIC_NAMES.contains(normalizedKey)
                || METRIC_NAME_TOKENS.contains(lastSemanticToken(key));
    }

    private static boolean isNumericMetricSeriesKey(String key, String normalizedKey) {
        return EXPLICIT_NUMERIC_SERIES_NAMES.contains(normalizedKey)
                || NUMERIC_SERIES_TOKENS.contains(lastSemanticToken(key));
    }

    private static boolean isSensitiveKey(String normalizedKey) {
        if ("maskedip".equals(normalizedKey)) {
            return false;
        }
        return "ip".equals(normalizedKey)
                || normalizedKey.contains("iphash")
                || normalizedKey.contains("rawip")
                || normalizedKey.endsWith("ipaddress")
                || "clientip".equals(normalizedKey)
                || "remoteip".equals(normalizedKey)
                || "sourceip".equals(normalizedKey)
                || "requestip".equals(normalizedKey)
                || isIdentityKey(normalizedKey)
                || normalizedKey.contains("token")
                || normalizedKey.contains("password")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("apikey")
                || normalizedKey.contains("credential")
                || "authorization".equals(normalizedKey)
                || "cookie".equals(normalizedKey)
                || "payloadjson".equals(normalizedKey)
                || "evidencejson".equals(normalizedKey)
                || "resultjson".equals(normalizedKey)
                || "targetrefjson".equals(normalizedKey)
                || "rawdata".equals(normalizedKey)
                || "rawpayload".equals(normalizedKey)
                || "jdbcurl".equals(normalizedKey)
                || "stacktrace".equals(normalizedKey)
                || "exception".equals(normalizedKey);
    }

    private static boolean isIdentityAggregateCountKey(String normalizedKey) {
        return "usercount".equals(normalizedKey) || "visitorcount".equals(normalizedKey);
    }

    private static boolean isIdentityKey(String normalizedKey) {
        return normalizedKey.startsWith("user")
                || normalizedKey.startsWith("visitor")
                || normalizedKey.startsWith("account")
                || normalizedKey.startsWith("rawuser")
                || normalizedKey.startsWith("rawvisitor");
    }

    private static boolean isIpLiteralKey(String key) {
        String candidate = key.trim();
        if (candidate.startsWith("[")) {
            int closingBracket = candidate.indexOf(']');
            if (closingBracket <= 1) {
                return false;
            }
            String address = candidate.substring(1, closingBracket);
            String suffix = candidate.substring(closingBracket + 1);
            return isAddressSuffix(suffix)
                    && (isIpv4Address(address) || isObviousIpv6Address(address));
        }
        String addressWithoutCidr = stripNumericSuffix(candidate, '/');
        if (isIpv4Address(addressWithoutCidr) || isObviousIpv6Address(addressWithoutCidr)) {
            return true;
        }
        String addressWithoutPort = stripNumericSuffix(candidate, ':');
        return !addressWithoutPort.equals(candidate) && isIpv4Address(addressWithoutPort);
    }

    private static boolean isIpv4Address(String candidate) {
        return IPV4_ADDRESS_PATTERN.matcher(candidate).matches();
    }

    private static boolean isObviousIpv6Address(String candidate) {
        int zoneSeparator = candidate.indexOf('%');
        String address = zoneSeparator >= 0 ? candidate.substring(0, zoneSeparator) : candidate;
        long colonCount = address.chars().filter(character -> character == ':').count();
        return colonCount >= 2 && IPV6_KEY_PATTERN.matcher(address).matches();
    }

    private static boolean isAddressSuffix(String suffix) {
        if (suffix.isEmpty()) {
            return true;
        }
        char separator = suffix.charAt(0);
        return (separator == ':' || separator == '/') && isDigits(suffix.substring(1));
    }

    private static String stripNumericSuffix(String candidate, char separator) {
        int separatorIndex = candidate.lastIndexOf(separator);
        if (separatorIndex <= 0 || !isDigits(candidate.substring(separatorIndex + 1))) {
            return candidate;
        }
        return candidate.substring(0, separatorIndex);
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String lastSemanticToken(String key) {
        String tokenized = ACRONYM_WORD_BOUNDARY.matcher(key).replaceAll("$1_$2");
        tokenized = CAMEL_WORD_BOUNDARY.matcher(tokenized).replaceAll("$1_$2");
        String[] tokens = tokenized.split("[^A-Za-z0-9]+");
        for (int index = tokens.length - 1; index >= 0; index--) {
            if (!tokens[index].isEmpty()) {
                return tokens[index].toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static String normalizeKey(String key) {
        StringBuilder normalized = new StringBuilder(key.length());
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    private static String maskPolicyKey(String policyKey) {
        int separator = policyKey.lastIndexOf(':');
        return separator < 0 ? "***" : policyKey.substring(0, separator + 1) + "***";
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
