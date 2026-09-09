package com.jupiter.shortlink.redirect.web;

import java.util.List;

/** Correlation IDs are accepted only from the actual trusted proxy, never as event identities. */
final class GatewayRequestIds {
    static final String HEADER = "X-Request-ID";

    private GatewayRequestIds() {}

    static String select(boolean trustedPeer, List<String> values) {
        if (trustedPeer && values != null && values.size() == 1) {
            String value = values.get(0);
            if (valid(value)) return value;
        }
        return SecureRequestIds.randomUuid().toString();
    }

    private static boolean valid(String value) {
        if (value == null || value.length() != 32) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!(ch >= '0' && ch <= '9') && !(ch >= 'a' && ch <= 'f')) return false;
        }
        return true;
    }
}
