package com.jupiter.shortlink.agent.harness.checkpoint;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;

import java.util.Objects;

/**
 * Creates the bounded compile configuration shared by the two agent graphs.
 */
public final class MysqlGraphCompileConfigFactory {

    /**
     * The current graphs are finite DAGs. Keep this limit explicit so a future
     * accidental cycle cannot turn a tool call into an unbounded ReAct loop.
     */
    public static final int FINITE_GRAPH_RECURSION_LIMIT = 16;

    private MysqlGraphCompileConfigFactory() {
    }

    public static CompileConfig create(BaseCheckpointSaver saver) {
        return CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(Objects.requireNonNull(saver, "saver")).build())
                .recursionLimit(FINITE_GRAPH_RECURSION_LIMIT)
                .build();
    }
}
