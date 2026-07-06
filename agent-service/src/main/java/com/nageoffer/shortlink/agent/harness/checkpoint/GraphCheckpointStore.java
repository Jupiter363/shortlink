package com.nageoffer.shortlink.agent.harness.checkpoint;

import java.util.Optional;

public interface GraphCheckpointStore {

    void save(GraphCheckpoint checkpoint);

    Optional<GraphCheckpoint> loadLatest(String threadId, String graphName, String graphVersion);
}
