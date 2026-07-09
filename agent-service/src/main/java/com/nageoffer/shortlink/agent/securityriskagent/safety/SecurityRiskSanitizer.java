package com.nageoffer.shortlink.agent.securityriskagent.safety;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class SecurityRiskSanitizer {

    private static final Pattern IPV4_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}(?!\\d)");
    private static final Pattern JDBC_URL_PATTERN = Pattern.compile("(?i)jdbc:[^\\s,;\\uFF0C\\uFF1B]+");
    private static final Pattern USER_IDENTIFIER_PATTERN = Pattern.compile("(?i)\\b(user|username|uid|visitor|account)\\s*([:=])\\s*[^\\s,;\\uFF0C\\uFF1B]+");
    private static final Pattern CN_USER_IDENTIFIER_PATTERN = Pattern.compile("(\\u7528\\u6237)\\s*([:=\\uFF1A])\\s*[^\\s,;\\uFF0C\\uFF1B]+");
    private static final Pattern SECRET_IDENTIFIER_PATTERN = Pattern.compile("(?i)\\b(token|password|secret|apiKey|accessToken|refreshToken)\\s*([:=])\\s*[^\\s,;\\uFF0C\\uFF1B]+");

    public Object sanitizeObject(Object value) {
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(sanitizeObject(item));
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String textKey = String.valueOf(key);
                String sanitizedKey = sanitizeText(textKey);
                if (isUserIdentifierKey(textKey)) {
                    return;
                }
                if (isSecretKey(textKey)) {
                    result.put(sanitizedKey, "***");
                    return;
                }
                if ("ip".equalsIgnoreCase(textKey)) {
                    result.put(sanitizedKey, maskIp(textValue(item)));
                    return;
                }
                result.put(sanitizedKey, sanitizeObject(item));
            });
            return result;
        }
        if (value instanceof String text) {
            return sanitizeText(text);
        }
        return value;
    }

    public String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = JDBC_URL_PATTERN.matcher(text)
                .replaceAll("jdbc:***");
        sanitized = IPV4_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + "." + match.group(2) + ".*.*");
        sanitized = USER_IDENTIFIER_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + match.group(2) + "***");
        sanitized = CN_USER_IDENTIFIER_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + match.group(2) + "***");
        return SECRET_IDENTIFIER_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + match.group(2) + "***");
    }

    private boolean isUserIdentifierKey(String key) {
        return "user".equalsIgnoreCase(key)
                || "username".equalsIgnoreCase(key)
                || "uid".equalsIgnoreCase(key)
                || "visitor".equalsIgnoreCase(key)
                || "account".equalsIgnoreCase(key);
    }

    private boolean isSecretKey(String key) {
        return "token".equalsIgnoreCase(key)
                || "password".equalsIgnoreCase(key)
                || "secret".equalsIgnoreCase(key)
                || "apiKey".equalsIgnoreCase(key)
                || "accessToken".equalsIgnoreCase(key)
                || "refreshToken".equalsIgnoreCase(key);
    }

    public String maskIp(String ip) {
        if (ip.isBlank()) {
            return "";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return ip.length() <= 6 ? "***" : ip.substring(0, 6) + "***";
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
