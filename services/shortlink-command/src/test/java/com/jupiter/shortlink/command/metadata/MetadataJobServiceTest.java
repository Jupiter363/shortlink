package com.jupiter.shortlink.command.metadata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

import javax.sql.DataSource;

@Timeout(10)
class MetadataJobServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();
    private CountingJdbc jdbc;
    private CommitManager manager;
    private MetadataJobService jobs;
    private String database;

    @BeforeEach
    void fixture() {
        database = "jdbc:h2:mem:metadata-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var source = new DriverManagerDataSource(database, "sa", "");
        jdbc = new CountingJdbc(source);
        jdbc.execute(
                "CREATE TABLE t_metadata_job (job_id VARCHAR(36) PRIMARY KEY,tenant_id"
                    + " BIGINT,link_id BIGINT,target_revision BIGINT,url_digest"
                    + " VARCHAR(64),origin_url VARCHAR(2048),created_at BIGINT,updated_at"
                    + " BIGINT,next_attempt_at BIGINT,"
                    + "UNIQUE(tenant_id,link_id,target_revision,url_digest))");
        jdbc.execute(
                "CREATE TABLE t_outbox (event_id VARCHAR(64) PRIMARY KEY,topic"
                    + " VARCHAR(128),event_key VARCHAR(256),payload CLOB,next_attempt_at"
                    + " BIGINT,created_at BIGINT)");
        manager = new CommitManager(source);
        var links = mock(LinkCommandService.class);
        when(links.digest(any())).thenReturn("a".repeat(64));
        jobs =
                new MetadataJobService(
                        jdbc,
                        manager,
                        json,
                        links,
                        mock(GroupCommandService.class),
                        new BusinessOutbox(jdbc, json, clock),
                        clock);
    }

    private MetadataJobService.IntakeRecord valid(long id, long offset) throws Exception {
        String payload =
                json.writeValueAsString(
                        new MetadataJobService.Event(
                                "event-" + id,
                                1,
                                "1",
                                id,
                                1,
                                "a".repeat(64),
                                "https://example.org/" + id));
        return new MetadataJobService.IntakeRecord(payload, MetadataJobService.TOPIC, 0, offset);
    }

    private MetadataJobService.IntakeRecord poison(long offset) {
        return new MetadataJobService.IntakeRecord(
                "secret-invalid-payload", MetadataJobService.TOPIC, 0, offset);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void entireSixteenRecordPollCommitsOnceAndReplayKeepsJobsAndDlqIdempotent() throws Exception {
        var batch = new ArrayList<MetadataJobService.IntakeRecord>();
        for (int n = 0; n < 16; n++) batch.add(n % 4 == 0 ? poison(n) : valid(n + 1, n));
        assertEquals(new MetadataJobService.IntakeResult(12, 4), jobs.acceptBatch(batch));
        assertEquals(1, manager.commits);
        assertEquals(1, jdbc.jobBatches);
        assertEquals(1, jdbc.dlqBatches);
        assertEquals(12, count("t_metadata_job"));
        assertEquals(4, count("t_outbox"));
        assertEquals(new MetadataJobService.IntakeResult(12, 4), jobs.acceptBatch(batch));
        assertEquals(2, manager.commits);
        assertEquals(12, count("t_metadata_job"));
        assertEquals(4, count("t_outbox"));
        assertEquals(1, jdbc.dlqBatches);
        assertTrue(
                jdbc.queryForList("SELECT payload FROM t_outbox", String.class).stream()
                        .noneMatch(payload -> payload.contains("secret-invalid-payload")));
    }

    @Test
    void failureInsideValidRowsRollsBackTheEntirePoll() throws Exception {
        jdbc.failAfterFirstJob = true;
        var batch = List.of(valid(1, 1), valid(2, 2), poison(3));
        assertThrows(DataAccessResourceFailureException.class, () -> jobs.acceptBatch(batch));
        assertEquals(0, count("t_metadata_job"));
        assertEquals(0, count("t_outbox"));
        assertEquals(0, manager.commits);
        assertEquals(new MetadataJobService.IntakeResult(2, 1), jobs.acceptBatch(batch));
    }

    @Test
    void failureAfterDlqInsertionAlsoRollsBackValidJobs() throws Exception {
        jdbc.failAfterDlq = true;
        var batch = List.of(valid(1, 1), poison(2));
        assertThrows(DataAccessResourceFailureException.class, () -> jobs.acceptBatch(batch));
        assertEquals(0, count("t_metadata_job"));
        assertEquals(0, count("t_outbox"));
        assertEquals(new MetadataJobService.IntakeResult(1, 1), jobs.acceptBatch(batch));
    }

    @Test
    void beforeCommitFailureDoesNotCommitKafkaOffsetsAndReplaySucceeds() throws Exception {
        manager.failBefore = true;
        assertCommitFailureDoesNotAdvanceOffsets(false);
    }

    @Test
    void unknownCommitDoesNotCommitKafkaOffsetsAndReplayDoesNotDuplicate() throws Exception {
        manager.failAfter = true;
        assertCommitFailureDoesNotAdvanceOffsets(true);
    }

    @SuppressWarnings("unchecked")
    private void assertCommitFailureDoesNotAdvanceOffsets(boolean committed) throws Exception {
        Consumer<String, String> consumer = mock(Consumer.class);
        var record = valid(1, 10);
        var records =
                new ConsumerRecords<>(
                        Map.of(
                                new TopicPartition(MetadataJobService.TOPIC, 0),
                                List.of(
                                        new ConsumerRecord<>(
                                                MetadataJobService.TOPIC,
                                                0,
                                                10L,
                                                "key",
                                                record.payload()),
                                        new ConsumerRecord<>(
                                                MetadataJobService.TOPIC,
                                                0,
                                                11L,
                                                "key",
                                                poison(11).payload()))));
        when(consumer.poll(any(Duration.class))).thenReturn(records);
        var registry = new SimpleMeterRegistry();
        var runtime =
                new MetadataRuntime(
                        jobs,
                        mock(SafeMetadataFetcher.class),
                        "unused:9092",
                        "test",
                        4,
                        registry,
                        () -> consumer);
        try {
            assertThrows(TransactionSystemException.class, () -> runtime.intakePoll(consumer));
            verify(consumer, never()).commitSync(any(Duration.class));
            assertEquals(committed ? 1 : 0, count("t_metadata_job"));
            assertEquals(committed ? 1 : 0, count("t_outbox"));
            assertEquals(
                    0,
                    registry.get("shortlink.metadata.intake.records")
                            .tag("outcome", "accepted")
                            .counter()
                            .count());
            runtime.intakePoll(consumer);
            verify(consumer, times(1)).commitSync(Duration.ofSeconds(3));
            assertEquals(1, count("t_metadata_job"));
            assertEquals(1, count("t_outbox"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void independentIntakeCommitIsVisibleBeforeAnOuterTransactionRollsBack() throws Exception {
        var batch = List.of(valid(1, 0), poison(1));
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        outer -> {
                            jobs.acceptBatch(batch);
                            var observer =
                                    new JdbcTemplate(
                                            new DriverManagerDataSource(database, "sa", ""));
                            assertEquals(
                                    1,
                                    observer.queryForObject(
                                            "SELECT COUNT(*) FROM t_metadata_job", Integer.class));
                            assertEquals(
                                    1,
                                    observer.queryForObject(
                                            "SELECT COUNT(*) FROM t_outbox", Integer.class));
                            outer.setRollbackOnly();
                        });
        assertEquals(1, count("t_metadata_job"));
        assertEquals(1, count("t_outbox"));
    }

    @Test
    void byteBudgetRejectsBeforeJsonParsingAndOversizedPollDoesNoSql() throws Exception {
        var exact = valid(1, 0);
        String padded = exact.payload() + " ".repeat(65536 - exact.payload().length());
        var huge =
                new MetadataJobService.IntakeRecord(
                        "中".repeat(22000), MetadataJobService.TOPIC, 0, 2);
        var result =
                jobs.acceptBatch(
                        List.of(
                                new MetadataJobService.IntakeRecord(
                                        padded, MetadataJobService.TOPIC, 0, 0),
                                huge));
        assertEquals(new MetadataJobService.IntakeResult(1, 1), result);
        String payload = jdbc.queryForObject("SELECT payload FROM t_outbox", String.class);
        assertTrue(payload.contains("IllegalArgumentException"));
        assertFalse(payload.contains("JsonParseException"));
        assertThrows(
                IllegalArgumentException.class,
                () -> jobs.acceptBatch(Collections.nCopies(17, exact)));
        assertEquals(1, manager.commits);
    }

    @Test
    void emptyPollDoesNotOpenTransactionAndDuplicatePoisonReceiptWithinPollIsOneOutbox() {
        assertEquals(new MetadataJobService.IntakeResult(0, 0), jobs.acceptBatch(List.of()));
        assertEquals(0, manager.commits);
        assertEquals(
                new MetadataJobService.IntakeResult(0, 2),
                jobs.acceptBatch(List.of(poison(1), poison(1))));
        assertEquals(1, count("t_outbox"));
    }

    @Test
    void jdbcUnknownAffectedRowCountsAreNotReportedAsUniqueNewJobs() throws Exception {
        jdbc.unknownAffectedCounts = true;
        var batch = List.of(valid(1, 0));
        assertEquals(new MetadataJobService.IntakeResult(1, 0), jobs.acceptBatch(batch));
        assertEquals(new MetadataJobService.IntakeResult(1, 0), jobs.acceptBatch(batch));
        assertEquals(1, count("t_metadata_job"));
    }

    private static class CommitManager extends DataSourceTransactionManager {
        int commits;
        boolean failBefore, failAfter;

        CommitManager(DataSource source) {
            super(source);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failBefore) {
                failBefore = false;
                super.doRollback(status);
                throw new TransactionSystemException("Injected pre-commit failure");
            }
            super.doCommit(status);
            commits++;
            if (failAfter) {
                failAfter = false;
                throw new TransactionSystemException("Injected lost commit acknowledgement");
            }
        }
    }

    private static class CountingJdbc extends JdbcTemplate {
        boolean failAfterFirstJob, failAfterDlq, unknownAffectedCounts;
        int jobBatches, dlqBatches;

        CountingJdbc(DataSource source) {
            super(source);
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> args, int[] types) {
            if (sql.startsWith("INSERT IGNORE INTO t_metadata_job")) {
                jobBatches++;
                if (failAfterFirstJob) {
                    failAfterFirstJob = false;
                    super.update(sql, args.get(0));
                    throw new DataAccessResourceFailureException("Injected partial job batch");
                }
            } else if (sql.startsWith("INSERT INTO t_outbox")) {
                dlqBatches++;
                if (failAfterDlq) {
                    failAfterDlq = false;
                    super.batchUpdate(sql, args, types);
                    throw new DataAccessResourceFailureException("Injected DLQ write failure");
                }
            }
            int[] result = super.batchUpdate(sql, args, types);
            if (unknownAffectedCounts) Arrays.fill(result, java.sql.Statement.SUCCESS_NO_INFO);
            return result;
        }
    }
}
