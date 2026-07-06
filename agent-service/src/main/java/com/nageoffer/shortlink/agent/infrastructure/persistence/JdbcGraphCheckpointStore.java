package com.nageoffer.shortlink.agent.infrastructure.persistence;

import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcGraphCheckpointStore implements GraphCheckpointStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcGraphCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(GraphCheckpoint checkpoint) {
        int updatedRows = jdbcTemplate.update("""
                        update t_agent_graph_checkpoint
                        set trace_id = ?,
                            checkpoint_json = ?,
                            checkpoint_version = ?,
                            status = ?,
                            update_time = CURRENT_TIMESTAMP
                        where thread_id = ?
                          and graph_name = ?
                          and graph_version = ?
                        """,
                checkpoint.traceId(),
                checkpoint.checkpointJson(),
                checkpoint.checkpointVersion(),
                checkpoint.status(),
                checkpoint.threadId(),
                checkpoint.graphName(),
                checkpoint.graphVersion()
        );
        if (updatedRows > 0) {
            return;
        }

        jdbcTemplate.update("""
                        insert into t_agent_graph_checkpoint (
                            thread_id,
                            trace_id,
                            graph_name,
                            graph_version,
                            checkpoint_json,
                            checkpoint_version,
                            status
                        )
                        values (?, ?, ?, ?, ?, ?, ?)
                        """,
                checkpoint.threadId(),
                checkpoint.traceId(),
                checkpoint.graphName(),
                checkpoint.graphVersion(),
                checkpoint.checkpointJson(),
                checkpoint.checkpointVersion(),
                checkpoint.status()
        );
    }

    @Override
    public Optional<GraphCheckpoint> loadLatest(String threadId, String graphName, String graphVersion) {
        List<GraphCheckpoint> checkpoints = jdbcTemplate.query("""
                        select thread_id,
                               trace_id,
                               graph_name,
                               graph_version,
                               checkpoint_json,
                               checkpoint_version,
                               status,
                               create_time,
                               update_time
                        from t_agent_graph_checkpoint
                        where thread_id = ?
                          and graph_name = ?
                          and graph_version = ?
                        order by checkpoint_version desc, update_time desc
                        limit 1
                        """,
                (rs, rowNum) -> mapCheckpoint(rs),
                threadId,
                graphName,
                graphVersion
        );
        return checkpoints.stream().findFirst();
    }

    private GraphCheckpoint mapCheckpoint(ResultSet rs) throws SQLException {
        return new GraphCheckpoint(
                rs.getString("thread_id"),
                rs.getString("trace_id"),
                rs.getString("graph_name"),
                rs.getString("graph_version"),
                rs.getString("checkpoint_json"),
                rs.getLong("checkpoint_version"),
                rs.getString("status"),
                timestampToLocalDateTime(rs.getTimestamp("create_time")),
                timestampToLocalDateTime(rs.getTimestamp("update_time"))
        );
    }

    private LocalDateTime timestampToLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
