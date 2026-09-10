package com.jupiter.shortlink.agent.harness.checkpoint;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds the native Spring AI Alibaba Graph thread id used by both agents.
 *
 * <p>MysqlSaver stores its internal thread id in a {@code VARCHAR(36)} column,
 * so the material is encoded as a deterministic UUID. Graph name, graph
 * version, and the original business session id all participate in that
 * material; changing any one of them creates a separate checkpoint chain.</p>
 */
public final class AgentGraphThreadKeyFactory {

    private AgentGraphThreadKeyFactory() {
    }

    public static String create(String graphName, String graphVersion, String sessionId) {
        return UUID.nameUUIDFromBytes(material(graphName, graphVersion, sessionId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Returns the stable, human-readable input used to derive the UUID. */
    public static String material(String graphName, String graphVersion, String sessionId) {
        return requireText(graphName, "graphName")
                + "|"
                + requireText(graphVersion, "graphVersion")
                + "|"
                + requireText(sessionId, "sessionId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
