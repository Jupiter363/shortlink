package com.jupiter.shortlink.agent.harness.runtime;

public record AgentRunRequest(
        String sessionId,
        String agentType,
        String username,
        String message,
        com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal,
        String requestKey,
        java.util.Set<String> clientCapabilities,
        Operation operation,
        Continuation continuation,
        String previousRunId) {
    public enum Operation { NEW, CONTINUE, PROGRESS, CANCEL }

    /** A lookup identity only. The server must revalidate principal, session and stored Run. */
    public record Continuation(String runId, String requestId) {
        public Continuation {
            requireId(runId);
            requireId(requestId);
        }

        public com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef workRef() {
            return new com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef(
                    runId, requestId);
        }
    }

    public AgentRunRequest {
        clientCapabilities = clientCapabilities == null ? java.util.Set.of() : java.util.Set.copyOf(clientCapabilities);
        if (clientCapabilities.size() > 16 || clientCapabilities.stream().anyMatch(
                value -> value.isBlank() || value.length() > 96))
            throw new IllegalArgumentException("CAMPAIGN_CLIENT_CAPABILITY_INVALID");
        operation = operation == null ? Operation.NEW : operation;
        if (operation != Operation.NEW && continuation == null)
            throw new IllegalArgumentException("CAMPAIGN_CONTINUATION_REQUIRED");
        if (operation == Operation.NEW && continuation != null)
            throw new IllegalArgumentException("CAMPAIGN_NEW_CANNOT_CONTINUE_RUN");
        if (previousRunId != null) {
            requireId(previousRunId);
            if (operation != Operation.NEW)
                throw new IllegalArgumentException("CAMPAIGN_PREDECESSOR_ONLY_FOR_NEW_REQUEST");
        }
    }

    public AgentRunRequest(String sessionId, String agentType, String username, String message,
                           com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal,
                           String requestKey) {
        this(sessionId, agentType, username, message, principal, requestKey, java.util.Set.of(), Operation.NEW, null, null);
    }

    public AgentRunRequest(String sessionId, String agentType, String username, String message,
                           com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal) {
        this(sessionId, agentType, username, message, principal, null);
    }

    public AgentRunRequest(String sessionId, String agentType, String username, String message) {
        this(sessionId, agentType, username, message, null, null);
    }

    private static void requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"))
            throw new IllegalArgumentException("CAMPAIGN_RUN_REFERENCE_INVALID");
    }
}
