package com.nageoffer.shortlink.admin.authority.capability.api;

import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.regex.Pattern;

final class AgentCapabilityRequestIds {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private AgentCapabilityRequestIds() {
    }

    static String resolve(String requestedId) {
        String safeId = safeOrNull(requestedId);
        if (safeId != null) {
            return safeId;
        }
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }

    static String safeOrNull(String value) {
        if (StringUtils.hasText(value) && SAFE_REQUEST_ID.matcher(value).matches()) {
            return value;
        }
        return null;
    }
}
