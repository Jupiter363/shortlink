package com.nageoffer.shortlink.agent.securityriskagent.safety;

import com.nageoffer.shortlink.agent.riskcommon.safety.RiskHashService;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskIpSafety;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class RiskToolStateSanitizer {

    private static final String SHORT_LINK_STATS_TOOL = "get_short_link_stats";
    private static final String GROUP_STATS_TOOL = "get_group_stats";
    private static final String GROUP_ACCESS_RECORDS_TOOL = "get_group_access_records";
    private static final String UNSAFE_SHAPE_MESSAGE = "Tool data cannot be sanitized safely";
    private static final int MAX_DEPTH = 24;
    private static final int MAX_CONTAINER_ENTRIES = 1_024;
    private static final int MAX_TOTAL_VALUES = 4_096;
    private static final Pattern IP_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_IDENTITY_AGGREGATE_PATTERN = Pattern.compile(
            "[a-z0-9]*(?:user|visitor|account)(?:count|total|share|ratio|rate)(?:\\d+[a-z]*)?"
    );
    private static final Set<String> SECRET_KEY_FRAGMENTS = Set.of(
            "password", "secret", "credential", "token", "apikey", "authorization", "cookie"
    );
    private static final Set<String> SENSITIVE_IDENTITY_SUFFIXES = Set.of(
            "user", "users", "username", "userid", "useruuid", "userprofile", "useridentity",
            "useridentifier", "userfingerprint", "useragent", "visitor", "visitors", "visitorid",
            "visitoruuid", "visitorprofile", "visitoridentity", "visitoridentifier", "visitorfingerprint",
            "account", "accounts", "accountid", "accountuuid", "accountname", "accountprofile",
            "uid", "uuid", "email", "phone", "mobile", "fingerprint"
    );
    private static final Set<String> RAW_IP_KEYS = Set.of(
            "ip", "rawip", "ipaddress", "clientip", "remoteip", "sourceip"
    );
    private static final Set<String> DETAIL_KEYS = Set.of("records", "rows", "rawdata");
    private static final Set<String> ACCESS_RECORD_SAFE_KEYS = Set.of(
            "total", "current", "size", "pages", "recordcount", "totalcount", "count",
            "page", "pagenum", "pagenumber", "pagesize", "totalpages", "totalelements",
            "numberofelements", "first", "last", "empty"
    );

    private final RiskHashService hashService;

    private final SecurityRiskSanitizer sanitizer;

    public RiskToolStateSanitizer(RiskHashService hashService, SecurityRiskSanitizer sanitizer) {
        this.hashService = Objects.requireNonNull(hashService, "hashService must not be null");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
    }

    public Object sanitize(String toolName, Object data) {
        TraversalContext context = new TraversalContext();
        if (GROUP_ACCESS_RECORDS_TOOL.equals(toolName)) {
            return sanitizeAccessRecordData(data, context, 0);
        }
        if ((SHORT_LINK_STATS_TOOL.equals(toolName) || GROUP_STATS_TOOL.equals(toolName))
                && data instanceof Map<?, ?> map) {
            return sanitizeMap(map, true, context, 0);
        }
        return sanitizeValue(data, context, 0);
    }

    private Object sanitizeAccessRecordData(Object data, TraversalContext context, int depth) {
        if (data == null) {
            context.visit(depth);
            return null;
        }
        if (!(data instanceof Map<?, ?> map)) {
            if (data instanceof Collection<?> collection) {
                context.enterContainer(data, depth, collection.size());
                context.exitContainer(data);
                return List.of();
            }
            if (data.getClass().isArray()) {
                context.enterContainer(data, depth, Array.getLength(data));
                context.exitContainer(data);
                return List.of();
            }
            context.visit(depth);
            return null;
        }
        context.enterContainer(map, depth, map.size());
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String textKey = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (!ACCESS_RECORD_SAFE_KEYS.contains(normalizeKey(textKey)) || isStructured(value)) {
                    continue;
                }
                safe.put(sanitizer.sanitizeText(textKey), sanitizeValue(value, context, depth + 1));
            }
            return immutableMap(safe);
        } finally {
            context.exitContainer(map);
        }
    }

    private Object sanitizeValue(Object value, TraversalContext context, int depth) {
        if (value == null) {
            context.visit(depth);
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map, false, context, depth);
        }
        if (value instanceof Collection<?> collection) {
            return sanitizeCollection(collection, context, depth);
        }
        if (value.getClass().isArray()) {
            return sanitizeArray(value, context, depth);
        }
        context.visit(depth);
        if (value instanceof CharSequence text) {
            return sanitizer.sanitizeText(text.toString());
        }
        if (isImmutableJsonScalar(value)) {
            return value;
        }
        if (value instanceof Character character) {
            return sanitizer.sanitizeText(character.toString());
        }
        if (value instanceof Enum<?> enumValue) {
            return sanitizer.sanitizeText(enumValue.name());
        }
        return sanitizer.sanitizeText(String.valueOf(value));
    }

    private Map<String, Object> sanitizeMap(
            Map<?, ?> source,
            boolean statsRoot,
            TraversalContext context,
            int depth
    ) {
        context.enterContainer(source, depth, source.size());
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String textKey = String.valueOf(entry.getKey());
                String normalizedKey = normalizeKey(textKey);
                Object value = entry.getValue();
                if (isSensitiveKey(normalizedKey) || DETAIL_KEYS.contains(normalizedKey)) {
                    continue;
                }
                if (statsRoot && "topipstats".equals(normalizedKey)) {
                    safe.put("topIpStats", sanitizeTopIpStats(value, context, depth + 1));
                    continue;
                }
                if (RAW_IP_KEYS.contains(normalizedKey)) {
                    safe.put("maskedIp", sanitizer.maskIp(charSequenceValue(value)));
                    continue;
                }
                safe.put(
                        sanitizer.sanitizeText(textKey),
                        sanitizeValue(value, context, depth + 1)
                );
            }
            return immutableMap(safe);
        } finally {
            context.exitContainer(source);
        }
    }

    private List<Object> sanitizeCollection(
            Collection<?> source,
            TraversalContext context,
            int depth
    ) {
        context.enterContainer(source, depth, source.size());
        try {
            List<Object> safe = new ArrayList<>(source.size());
            for (Object value : source) {
                safe.add(sanitizeValue(value, context, depth + 1));
            }
            return immutableList(safe);
        } finally {
            context.exitContainer(source);
        }
    }

    private List<Object> sanitizeArray(Object source, TraversalContext context, int depth) {
        int length = Array.getLength(source);
        context.enterContainer(source, depth, length);
        try {
            List<Object> safe = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                safe.add(sanitizeValue(Array.get(source, index), context, depth + 1));
            }
            return immutableList(safe);
        } finally {
            context.exitContainer(source);
        }
    }

    private List<Object> sanitizeTopIpStats(Object value, TraversalContext context, int depth) {
        List<Object> safeRows = new ArrayList<>();
        if (value instanceof Collection<?> rows) {
            context.enterContainer(rows, depth, rows.size());
            try {
                for (Object row : rows) {
                    appendSafeTopIpRow(safeRows, row, context, depth + 1);
                }
            } finally {
                context.exitContainer(rows);
            }
            return immutableList(safeRows);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            context.enterContainer(value, depth, length);
            try {
                for (int index = 0; index < length; index++) {
                    appendSafeTopIpRow(safeRows, Array.get(value, index), context, depth + 1);
                }
            } finally {
                context.exitContainer(value);
            }
            return immutableList(safeRows);
        }
        context.visit(depth);
        return List.of();
    }

    private void appendSafeTopIpRow(
            List<Object> target,
            Object value,
            TraversalContext context,
            int depth
    ) {
        if (!(value instanceof Map<?, ?> row)) {
            context.visit(depth);
            return;
        }
        context.enterContainer(row, depth, row.size());
        try {
            Map<String, Object> safeRow = sanitizeTopIpRow(row, context, depth + 1);
            if (!safeRow.isEmpty()) {
                target.add(safeRow);
            }
        } finally {
            context.exitContainer(row);
        }
    }

    private Map<String, Object> sanitizeTopIpRow(
            Map<?, ?> row,
            TraversalContext context,
            int depth
    ) {
        Object countValue = valueIgnoreCase(row, "cnt");
        if (positiveCount(countValue) <= 0) {
            return Map.of();
        }

        String rawIp = charSequenceValue(valueIgnoreCase(row, "ip"));
        if (!rawIp.isBlank()) {
            String maskedIp = sanitizer.maskIp(rawIp);
            if (!RiskIpSafety.isAllowedMaskedIp(maskedIp)) {
                return Map.of();
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("ipHash", hashService.sha256(rawIp));
            safe.put("maskedIp", maskedIp);
            safe.put("cnt", sanitizeValue(countValue, context, depth));
            return immutableMap(safe);
        }

        String ipHash = stringValue(valueIgnoreCase(row, "ipHash"));
        String maskedIp = stringValue(valueIgnoreCase(row, "maskedIp"));
        if (!IP_HASH_PATTERN.matcher(ipHash).matches()
                || !RiskIpSafety.isAllowedMaskedIp(maskedIp)) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("ipHash", ipHash);
        safe.put("maskedIp", maskedIp);
        safe.put("cnt", sanitizeValue(countValue, context, depth));
        return immutableMap(safe);
    }

    private Object valueIgnoreCase(Map<?, ?> map, String expectedKey) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (expectedKey.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private long positiveCount(Object value) {
        if (!(value instanceof CharSequence) && !isImmutableJsonNumber(value)) {
            return -1L;
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1L;
        }
    }

    private boolean isStructured(Object value) {
        return value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || (value != null && value.getClass().isArray());
    }

    private boolean isSensitiveKey(String normalizedKey) {
        if (SECRET_KEY_FRAGMENTS.stream().anyMatch(normalizedKey::contains)) {
            return true;
        }
        if (SAFE_IDENTITY_AGGREGATE_PATTERN.matcher(normalizedKey).matches()) {
            return false;
        }
        return SENSITIVE_IDENTITY_SUFFIXES.stream().anyMatch(normalizedKey::endsWith);
    }

    private boolean isImmutableJsonScalar(Object value) {
        return value instanceof Boolean || isImmutableJsonNumber(value);
    }

    private boolean isImmutableJsonNumber(Object value) {
        return value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal;
    }

    private String normalizeKey(String key) {
        return key.replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
    }

    private String charSequenceValue(Object value) {
        return value instanceof CharSequence text ? text.toString() : "";
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private List<Object> immutableList(List<Object> value) {
        return value.isEmpty() ? List.of() : Collections.unmodifiableList(value);
    }

    private Map<String, Object> immutableMap(Map<String, Object> value) {
        return value.isEmpty() ? Map.of() : Collections.unmodifiableMap(value);
    }

    private static final class TraversalContext {

        private final IdentityHashMap<Object, Boolean> activeContainers = new IdentityHashMap<>();

        private int totalValues;

        private void visit(int depth) {
            if (depth > MAX_DEPTH || ++totalValues > MAX_TOTAL_VALUES) {
                throw unsafeShape();
            }
        }

        private void enterContainer(Object value, int depth, int size) {
            visit(depth);
            if (size > MAX_CONTAINER_ENTRIES || activeContainers.put(value, Boolean.TRUE) != null) {
                throw unsafeShape();
            }
        }

        private void exitContainer(Object value) {
            activeContainers.remove(value);
        }

        private IllegalStateException unsafeShape() {
            return new IllegalStateException(UNSAFE_SHAPE_MESSAGE);
        }
    }
}
