CREATE TABLE IF NOT EXISTS t_agent_graph_checkpoint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    thread_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    graph_name VARCHAR(128) NOT NULL,
    graph_version VARCHAR(64) NOT NULL,
    checkpoint_json LONGTEXT NOT NULL,
    checkpoint_version BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_graph_checkpoint UNIQUE (thread_id, graph_name, graph_version)
);
