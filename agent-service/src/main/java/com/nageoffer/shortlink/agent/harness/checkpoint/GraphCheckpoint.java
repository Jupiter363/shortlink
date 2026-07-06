package com.nageoffer.shortlink.agent.harness.checkpoint;

import java.time.LocalDateTime;

public record GraphCheckpoint(
        String threadId,
        String traceId,
        String graphName,
        String graphVersion,
        String checkpointJson,
        long checkpointVersion,
        String status,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public GraphCheckpoint(
            String threadId,
            String traceId,
            String graphName,
            String graphVersion,
            String checkpointJson,
            long checkpointVersion,
            String status
    ) {
        this(threadId, traceId, graphName, graphVersion, checkpointJson, checkpointVersion, status, null, null);
    }

    public GraphCheckpoint {
        threadId = requireText(threadId, "threadId");
        traceId = requireText(traceId, "traceId");
        graphName = requireText(graphName, "graphName");
        graphVersion = requireText(graphVersion, "graphVersion");
        checkpointJson = requireText(checkpointJson, "checkpointJson");
        status = requireText(status, "status");
        if (checkpointVersion < 0) {
            throw new IllegalArgumentException("checkpointVersion must not be negative");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
