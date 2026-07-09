package com.nageoffer.shortlink.agent.riskcommon.safety;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

public class RiskSensitiveDataGuard {

    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final String[] FORBIDDEN_TOKENS = {
            "rawip",
            "ipaddress",
            "visitorid",
            "userid",
            "access_records.rawdata",
            "access_records.rows"
    };

    public void requireSafe(String value) {
        if (!isSafe(value)) {
            throw new IllegalArgumentException("Risk payload contains sensitive data");
        }
    }

    public boolean isSafe(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String lowerValue = value.toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN_TOKENS) {
            if (lowerValue.contains(token)) {
                return false;
            }
        }
        return !IPV4_PATTERN.matcher(value).find();
    }
}
