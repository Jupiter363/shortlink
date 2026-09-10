package com.jupiter.shortlink.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

public final class EventJson {
    private EventJson() {}

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event serialization failed", e);
        }
    }

    /** A fresh wire snapshot; unknown schemas and all UTF-16 surrogates keep the legacy path. */
    public static byte[] writeUtf8(Object value) {
        if (requiresLegacyEncoding(value)) return write(value).getBytes(StandardCharsets.UTF_8);
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event serialization failed", e);
        }
    }

    private static boolean requiresLegacyEncoding(Object value) {
        // Keep this explicit and allocation-free. Schema tests lock every record component's
        // name and type, including non-String components that could later become nested values.
        if (value instanceof ClickEventV1 event) {
            return hasSurrogate(event.eventId()) || hasSurrogate(event.producerInstanceId())
                    || hasSurrogate(event.tenantId()) || hasSurrogate(event.gidAtEvent())
                    || hasSurrogate(event.domainNorm()) || hasSurrogate(event.shortUri())
                    || hasSurrogate(event.uvId()) || hasSurrogate(event.clientIp())
                    || hasSurrogate(event.userAgent()) || hasSurrogate(event.referer())
                    || hasSurrogate(event.requestId()) || hasSurrogate(event.traceId());
        }
        if (value instanceof GatewayRequestEventV1 event) {
            return hasSurrogate(event.decisionId()) || hasSurrogate(event.producerInstanceId())
                    || hasSurrogate(event.method()) || hasSurrogate(event.reason())
                    || hasSurrogate(event.tenantId()) || hasSurrogate(event.domainNorm())
                    || hasSurrogate(event.shortUri()) || hasSurrogate(event.requestId())
                    || hasSurrogate(event.traceId());
        }
        return true;
    }

    private static boolean hasSurrogate(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            // Include valid pairs: byte and Writer generators may choose different escaping.
            if (Character.isSurrogate(text.charAt(i))) return true;
        }
        return false;
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid event schema", e);
        }
    }
}
