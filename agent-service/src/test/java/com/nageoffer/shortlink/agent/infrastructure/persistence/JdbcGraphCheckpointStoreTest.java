package com.nageoffer.shortlink.agent.infrastructure.persistence;

import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcGraphCheckpointStoreTest {

    @Test
    void savesAndLoadsLatestCheckpointJson() {
        DataSource dataSource = h2DataSource();
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        JdbcGraphCheckpointStore store = new JdbcGraphCheckpointStore(new JdbcTemplate(dataSource));

        store.save(new GraphCheckpoint(
                "thread-001",
                "trace-001",
                "campaign-analysis-graph",
                "v1",
                "{\"step\":\"collect\",\"score\":91}",
                1L,
                "RUNNING"
        ));
        store.save(new GraphCheckpoint(
                "thread-001",
                "trace-002",
                "campaign-analysis-graph",
                "v1",
                "{\"step\":\"summary\",\"score\":97}",
                2L,
                "FINISHED"
        ));

        Optional<GraphCheckpoint> checkpoint = store.loadLatest(
                "thread-001",
                "campaign-analysis-graph",
                "v1"
        );

        assertThat(checkpoint).isPresent();
        assertThat(checkpoint.get().traceId()).isEqualTo("trace-002");
        assertThat(checkpoint.get().checkpointJson()).isEqualTo("{\"step\":\"summary\",\"score\":97}");
        assertThat(checkpoint.get().checkpointVersion()).isEqualTo(2L);
        assertThat(checkpoint.get().status()).isEqualTo("FINISHED");
        assertThat(checkpoint.get().createTime()).isNotNull();
        assertThat(checkpoint.get().updateTime()).isNotNull();

        Integer rowCount = new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from t_agent_graph_checkpoint where thread_id = ? and graph_name = ? and graph_version = ?",
                Integer.class,
                "thread-001",
                "campaign-analysis-graph",
                "v1"
        );
        assertThat(rowCount).isEqualTo(1);
    }

    private DataSource h2DataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:agent_checkpoint_store;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
