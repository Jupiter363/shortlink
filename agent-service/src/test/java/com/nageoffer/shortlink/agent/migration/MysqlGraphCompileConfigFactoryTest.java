package com.nageoffer.shortlink.agent.migration;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.nageoffer.shortlink.agent.harness.checkpoint.MysqlGraphCompileConfigFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

class MysqlGraphCompileConfigFactoryTest {

    @Test
    void compileConfigUsesMysqlSaverAndKeepsGraphExecutionBounded() {
        BaseCheckpointSaver saver = mock(BaseCheckpointSaver.class);

        CompileConfig compileConfig = MysqlGraphCompileConfigFactory.create(saver);

        assertThat(compileConfig.checkpointSaver()).containsSame(saver);
        assertThat(compileConfig.recursionLimit())
                .isEqualTo(MysqlGraphCompileConfigFactory.FINITE_GRAPH_RECURSION_LIMIT)
                .isEqualTo(16);
    }

    @Test
    void compileConfigCannotSilentlyFallBackToAnInMemorySaver() {
        assertThatNullPointerException()
                .isThrownBy(() -> MysqlGraphCompileConfigFactory.create(null))
                .withMessage("saver");
    }
}
