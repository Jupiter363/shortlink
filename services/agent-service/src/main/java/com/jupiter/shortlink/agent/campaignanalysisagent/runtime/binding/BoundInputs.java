package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;

import java.util.Map;

/**
 * Invocation-scoped values. Use with try-with-resources and never retain values in graph/checkpoints.
 * Frozen INPUT values retain their Java representation; artifact values are parsed JsonNode values.
 * Closing drops this holder's references; callers must not retain aliases beyond the invocation.
 */
public final class BoundInputs implements AutoCloseable {
    private Map<String, Object> values;
    private Map<String, BoundArtifact> artifacts;

    BoundInputs(Map<String, Object> values, Map<String, BoundArtifact> artifacts) {
        this.values = Map.copyOf(values);
        this.artifacts = Map.copyOf(artifacts);
    }

    public Map<String, Object> values() { requireOpen(); return values; }
    public Object value(String name) { requireOpen(); return values.get(name); }
    public Map<String, BoundArtifact> artifacts() { requireOpen(); return artifacts; }
    public BoundArtifact artifact(String name) { requireOpen(); return artifacts.get(name); }

    @Override
    public void close() { values = null; artifacts = null; }

    private void requireOpen() {
        if (values == null) throw new IllegalStateException("Bound inputs are closed");
    }
}
