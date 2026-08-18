package com.nageoffer.shortlink.agent.migration;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.nageoffer.shortlink.agent.harness.checkpoint.AgentGraphThreadKeyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real MySQL instance when AGENT_TEST_MYSQL_URL is supplied.
 * MysqlSaver owns its tables and therefore this contract intentionally checks
 * recovery through a newly-created saver instance (the same path used after a
 * process restart), rather than relying on MemorySaver state.
 */
class MysqlSaverRestartRecoveryContractTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_TEST_MYSQL_URL", matches = ".+")
    void checkpointSurvivesSaverRecreationAndGraphVersionRemainsIsolated() throws Exception {
        DataSource dataSource = dataSource();
        MysqlSaver firstProcessSaver = saver(dataSource);
        String sessionId = "recovery-" + UUID.randomUUID();
        String v1Thread = AgentGraphThreadKeyFactory.create("campaign-analysis", "v1", sessionId);
        String v2Thread = AgentGraphThreadKeyFactory.create("campaign-analysis", "v2", sessionId);
        RunnableConfig v1Config = RunnableConfig.builder().threadId(v1Thread).build();

        Checkpoint checkpoint = Checkpoint.builder()
                .id(UUID.randomUUID().toString())
                .state(Map.of("answer", "persisted", "sessionId", sessionId))
                .nodeId("tool_call")
                .nextNodeId("insight_compute")
                .build();
        firstProcessSaver.put(v1Config, checkpoint);

        MysqlSaver afterRestartSaver = saver(dataSource);
        Optional<Checkpoint> restored = afterRestartSaver.get(
                RunnableConfig.builder().threadId(v1Thread).build()
        );
        Optional<Checkpoint> incompatibleVersion = afterRestartSaver.get(
                RunnableConfig.builder().threadId(v2Thread).build()
        );

        assertThat(restored).isPresent();
        assertThat(restored.orElseThrow().getState())
                .containsEntry("answer", "persisted")
                .containsEntry("sessionId", sessionId);
        assertThat(incompatibleVersion).isEmpty();
    }

    private MysqlSaver saver(DataSource dataSource) {
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("AGENT_TEST_MYSQL_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("AGENT_TEST_MYSQL_USER", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("AGENT_TEST_MYSQL_PASSWORD", ""));
        return dataSource;
    }
}
