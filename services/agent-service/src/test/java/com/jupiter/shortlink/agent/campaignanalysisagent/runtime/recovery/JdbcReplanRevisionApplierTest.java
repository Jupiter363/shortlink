package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcReplanRevisionApplierTest {
    private JdbcTemplate jdbc;
    private JdbcReplanRevisionApplier applier;
    private JdbcReplanRevisionApplier.Request request;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:replan;MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSource source = dataSource;
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE run_lock (run_id VARCHAR(64) PRIMARY KEY, locked BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE consumer_adoption (run_id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE revision (run_id VARCHAR(64), revision INT, PRIMARY KEY(run_id, revision))");
        jdbc.execute("CREATE TABLE receipt (run_id VARCHAR(64) PRIMARY KEY)");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        applier = new JdbcReplanRevisionApplier(jdbc, tx);
        request = new JdbcReplanRevisionApplier.Request("run-1", 1, 2);
    }

    @Test
    void failureRollsBackEveryStagedRow() {
        assertThatThrownBy(() -> applier.apply(request,
                r -> jdbc.update("INSERT INTO run_lock VALUES (?, TRUE)", r.runId()),
                r -> jdbc.update("INSERT INTO consumer_adoption VALUES (?)", r.runId()),
                r -> jdbc.update("INSERT INTO revision VALUES (?, ?)", r.runId(), r.candidateRevision()),
                r -> { throw new IllegalStateException("receipt failed"); }))
                .hasMessage("receipt failed");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_lock", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_adoption", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM revision", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt", Integer.class)).isZero();
    }

    @Test
    void successAdoptsBeforeReceiptAndDuplicateKeyRollsBack() {
        List<String> order = new ArrayList<>();
        var lock = (JdbcReplanRevisionApplier.RevisionLock) r -> {
            jdbc.update("INSERT INTO run_lock VALUES (?, TRUE)", r.runId()); order.add("lock");
        };
        var adoption = (JdbcReplanRevisionApplier.ConsumerAdoption) r -> {
            jdbc.update("INSERT INTO consumer_adoption VALUES (?)", r.runId()); order.add("adopt");
        };
        var insertion = (JdbcReplanRevisionApplier.RevisionInsertion) r -> {
            jdbc.update("INSERT INTO revision VALUES (?, ?)", r.runId(), r.candidateRevision()); order.add("revision");
        };
        var receipt = (JdbcReplanRevisionApplier.ReceiptRecording) r -> {
            jdbc.update("INSERT INTO receipt VALUES (?)", r.runId()); order.add("receipt");
        };

        applier.apply(request, lock, adoption, insertion, receipt);
        assertThat(order).containsExactly("lock", "adopt", "revision", "receipt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_adoption", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> applier.apply(request, lock, adoption, insertion, receipt))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_lock", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_adoption", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM revision", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt", Integer.class)).isEqualTo(1);
    }
}
