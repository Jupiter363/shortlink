package com.jupiter.shortlink.contract;

/**
 * Binds a controlled issuance nonce to the exact immutable event time without per-event storage.
 */
public final class EventIdentity {
    private EventIdentity() {}

    public static String bind(long occurredAt, String suffix) {
        if (occurredAt <= 0 || !validSuffix(suffix)) {
            throw new IllegalArgumentException(
                    "Positive event time and a bounded issuance suffix are required");
        }
        return "v1:" + occurredAt + ":" + suffix;
    }

    public static boolean valid(String id, long occurredAt) {
        if (id == null || occurredAt <= 0) return false;
        String prefix = "v1:" + occurredAt + ":";
        return id.startsWith(prefix) && validSuffix(id.substring(prefix.length()));
    }

    private static boolean validSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty() || suffix.length() > 256) return false;
        for (int i = 0; i < suffix.length(); i++) {
            char ch = suffix.charAt(i);
            if (ch < 0x21 || ch > 0x7e) return false;
        }
        return true;
    }
}
