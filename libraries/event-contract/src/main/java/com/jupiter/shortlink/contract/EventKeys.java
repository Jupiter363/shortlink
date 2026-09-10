package com.jupiter.shortlink.contract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class EventKeys {
    private EventKeys() {}

    public static int bucket(String eventId, int count) {
        if (eventId == null || eventId.isBlank() || count < 1 || count > 256)
            throw new IllegalArgumentException("Invalid bucket input");
        try {
            return Math.floorMod(
                    ByteBuffer.wrap(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(eventId.getBytes(StandardCharsets.UTF_8)))
                            .getInt(),
                    count);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String click(ClickEventV1 event, int buckets) {
        return event.tenantId() + ":" + event.linkId() + ":" + bucket(event.eventId(), buckets);
    }

    public static String result(GatewayRequestEventV1 event) {
        return event.decisionId();
    }

    public static String route(RouteChangeV1 event) {
        return event.tenantId() + ":" + event.linkId();
    }

    public static String policy(RiskPolicyChangeV1 event) {
        return event.resourceKey() + ":" + event.action();
    }
}
