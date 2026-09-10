package com.jupiter.shortlink.command.outbox;

import static com.jupiter.shortlink.command.outbox.OutboxTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Timeout(15)
class OutboxPublisherTest {
    FaultJdbc jdbc;
    DataSourceTransactionManager manager;
    MutableClock clock;
    final List<OutboxPublisher> publishers = new ArrayList<>();

    @BeforeAll
    static void logging() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("ROOT"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }

    @BeforeEach
    void fixture() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:outbox-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new FaultJdbc(source, true);
        jdbc.execute(SCHEMA);
        manager = new DataSourceTransactionManager(source);
        clock = new MutableClock();
    }

    @AfterEach
    void close() {
        jdbc.releaseAck.countDown();
        jdbc.releaseClaim.countDown();
        jdbc.releaseSelectedClaim.countDown();
        publishers.forEach(OutboxPublisher::close);
    }

    MockProducer<String, String> producer(boolean automatic) {
        return new MockProducer<>(automatic, new StringSerializer(), new StringSerializer());
    }

    OutboxPublisher publisher(MockProducer<String, String> producer) {
        return publisher(producer, 12000, 1000, null);
    }

    OutboxPublisher publisher(
            MockProducer<String, String> producer,
            long timeout,
            long shutdown,
            SimpleMeterRegistry registry) {
        var publisher =
                new OutboxPublisher(
                        jdbc,
                        manager,
                        clock,
                        producer,
                        // Keep single-event fault isolation assertions in the original cases.
                        // Separate tests below exercise the real default batch of 16.
                        new OutboxSettings(4, 4, 2, 2, timeout, 30000, shutdown, 3, 1, 0),
                        registry);
        publishers.add(publisher);
        return publisher;
    }

    @Test
    void slowBrokerAcksDoNotBlockThePollerAndCapacityIncludesAllPendingRecords() {
        insert(jdbc, clock, 6, "events");
        var kafka = producer(false);
        var publisher = publisher(kafka);
        assertTimeout(java.time.Duration.ofSeconds(1), publisher::publishDue);
        await(() -> kafka.history().size() == 4);
        assertEquals(4, publisher.inFlightCount());
        publisher.publishDue();
        assertEquals(4, count(jdbc, "SENDING"));
        assertTrue(kafka.completeNext());
        await(() -> publisher.inFlightCount() == 3);
        publisher.publishDue();
        await(() -> kafka.history().size() == 5);
        assertEquals(4, publisher.inFlightCount());
        assertEquals(1, count(jdbc, "READY"));
    }

    @Test
    void partialKafkaFailureIsRetriedAndNoDatabaseWorkRunsOnKafkaCallbackThread() throws Exception {
        insert(jdbc, clock, 3, "events");
        var kafka = producer(false);
        var publisher = publisher(kafka);
        publisher.publishDue();
        await(() -> kafka.history().size() == 3);
        Thread network =
                new Thread(
                        () -> {
                            kafka.completeNext();
                            kafka.errorNext(new IllegalStateException("Injected broker failure"));
                            kafka.completeNext();
                        },
                        "kafka-test-network");
        network.start();
        network.join(2000);
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(2, count(jdbc, "PUBLISHED"));
        assertEquals(1, count(jdbc, "READY"));
        assertTrue(jdbc.acknowledgementThreads.stream().allMatch(n -> n.startsWith("outbox-ack-")));
        assertTrue(
                jdbc.queryForObject(
                                "SELECT next_attempt_at FROM t_outbox WHERE state='READY'",
                                Long.class)
                        > clock.millis());
    }

    @Test
    void ackSqlFailureRetriesOnlyTheAffectedEventAndMetricsExposeIt() {
        insert(jdbc, clock, 3, "events");
        jdbc.failBeforeAck.set(1);
        var registry = new SimpleMeterRegistry();
        var publisher = publisher(producer(true), 12000, 1000, registry);
        publisher.publishDue();
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(2, count(jdbc, "PUBLISHED"));
        assertEquals(1, count(jdbc, "READY"));
        assertEquals(1, registry.get("shortlink.outbox.ack.failures").counter().count());
        assertEquals(0, registry.get("shortlink.outbox.inflight").gauge().value());
    }

    @Test
    void unknownAckCommitNeverReopensPublishedEvent() {
        insert(jdbc, clock, 1, "events");
        jdbc.failAfterAck.set(1);
        var publisher = publisher(producer(true));
        publisher.publishDue();
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(1, count(jdbc, "PUBLISHED"));
        assertEquals(0, count(jdbc, "READY"));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM t_outbox", Integer.class));
    }

    @Test
    void failedRetryAccountingLeavesDurableLeaseForAnotherPublisher() {
        insert(jdbc, clock, 1, "events");
        jdbc.failBeforeAck.set(1);
        jdbc.failRetry = true;
        var publisher = publisher(producer(true));
        publisher.publishDue();
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(1, count(jdbc, "SENDING"));
        jdbc.failRetry = false;
        clock.advance(31000);
        var replacement = publisher(producer(true));
        replacement.publishDue();
        await(() -> count(jdbc, "PUBLISHED") == 1);
        assertEquals(2L, jdbc.queryForObject("SELECT fence FROM t_outbox", Long.class));
    }

    @Test
    void timeoutWinsOnceAndLateKafkaAckCannotPublishANewerAttempt() throws Exception {
        insert(jdbc, clock, 1, "events");
        var kafka = producer(false);
        var publisher = publisher(kafka, 100, 1000, null);
        publisher.publishDue();
        await(() -> kafka.history().size() == 1);
        Thread.sleep(140);
        publisher.publishDue();
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(1, count(jdbc, "READY"));
        kafka.completeNext();
        assertEquals(0, count(jdbc, "PUBLISHED"));
        assertEquals(0, publisher.inFlightCount());
        clock.advance(1000);
        publisher.publishDue();
        await(() -> kafka.history().size() == 2);
        kafka.completeNext();
        await(() -> count(jdbc, "PUBLISHED") == 1);
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM t_outbox", Integer.class));
    }

    @Test
    void expiredLeaseFencesBothLateAckAndLateRetry() {
        insert(jdbc, clock, 1, "events");
        var original = publisher(producer(false));
        var old = original.claim().get(0);
        clock.advance(31000);
        var replacement = publisher(producer(false));
        var current = replacement.claim().get(0);
        assertFalse(original.ack(old));
        original.retry(old);
        assertEquals(1, count(jdbc, "SENDING"));
        assertEquals(
                current.fence(), jdbc.queryForObject("SELECT fence FROM t_outbox", Long.class));
        assertTrue(replacement.ack(current));
    }

    @Test
    void blockedDatabaseAcknowledgementDoesNotBlockCallbackAndShutdownIsBounded() throws Exception {
        insert(jdbc, clock, 4, "events");
        jdbc.blockAck = true;
        var kafka = producer(false);
        var publisher = publisher(kafka, 12000, 100, null);
        publisher.publishDue();
        await(() -> kafka.history().size() == 4);
        assertTimeout(
                java.time.Duration.ofSeconds(1),
                () -> {
                    for (int n = 0; n < 4; n++) assertTrue(kafka.completeNext());
                });
        assertTrue(jdbc.ackEntered.await(1, TimeUnit.SECONDS));
        assertEquals(4, publisher.inFlightCount());
        assertTimeout(java.time.Duration.ofSeconds(2), publisher::close);
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(4, count(jdbc, "SENDING"));
        publisher.publishDue();
        assertEquals(4, kafka.history().size());
    }

    @Test
    void closeRacingAClaimCommitSendsNothingAndReleasesLocalCapacity() throws Exception {
        insert(jdbc, clock, 1, "events");
        jdbc.blockClaim = true;
        var kafka = producer(false);
        var publisher = publisher(kafka, 12000, 100, null);
        Thread poll = new Thread(publisher::publishDue, "test-outbox-poller");
        poll.start();
        assertTrue(jdbc.claimEntered.await(1, TimeUnit.SECONDS));
        assertTimeout(java.time.Duration.ofSeconds(2), publisher::close);
        jdbc.releaseClaim.countDown();
        poll.join(2000);
        assertFalse(poll.isAlive());
        assertEquals(0, kafka.history().size());
        assertEquals(0, publisher.inFlightCount());
        assertEquals(1, count(jdbc, "SENDING"));
    }

    @Test
    void interruptedPollerDoesNotClaimAndPreservesInterruptStatus() {
        insert(jdbc, clock, 1, "events");
        var publisher = publisher(producer(true));
        Thread.currentThread().interrupt();
        try {
            publisher.publishDue();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, count(jdbc, "READY"));
        assertEquals(0, publisher.inFlightCount());
    }

    @Test
    void blockingSendNeverRunsOnTheSchedulingThread() throws Exception {
        insert(jdbc, clock, 4, "events");
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        var kafka =
                new MockProducer<String, String>(
                        true, new StringSerializer(), new StringSerializer()) {
                    @Override
                    public Future<RecordMetadata> send(
                            ProducerRecord<String, String> record, Callback callback) {
                        assertTrue(Thread.currentThread().getName().startsWith("outbox-send-"));
                        entered.countDown();
                        awaitLatch(release);
                        return super.send(record, callback);
                    }
                };
        var publisher = publisher(kafka);
        try {
            assertTimeout(java.time.Duration.ofSeconds(1), publisher::publishDue);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(4, publisher.inFlightCount());
        } finally {
            release.countDown();
        }
        await(() -> count(jdbc, "PUBLISHED") == 4);
    }

    @Test
    void unknownClaimCommitSendsNothingAndReleasesOnlyLocalReservations() {
        insert(jdbc, clock, 1, "events");
        var uncertainManager =
                new DataSourceTransactionManager(jdbc.getDataSource()) {
                    @Override
                    protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus
                                    status) {
                        super.doCommit(status);
                        throw new org.springframework.transaction.TransactionSystemException(
                                "Commit reply lost");
                    }
                };
        var kafka = producer(true);
        var publisher = new OutboxPublisher(jdbc, uncertainManager, clock, kafka);
        publishers.add(publisher);
        assertThrows(
                org.springframework.transaction.TransactionSystemException.class,
                publisher::publishDue);
        assertEquals(0, kafka.history().size());
        assertEquals(0, publisher.inFlightCount());
        assertEquals(1, count(jdbc, "SENDING"));
        clock.advance(31000);
        var replacement = publisher(producer(true));
        replacement.publishDue();
        await(() -> count(jdbc, "PUBLISHED") == 1);
    }

    @Test
    void interruptedClosePreservesTheCallersInterruptFlag() {
        var publisher = publisher(producer(false));
        Thread.currentThread().interrupt();
        try {
            publisher.close();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, publisher.inFlightCount());
    }

    @Test
    void rolledBackPartialClaimNeverSendsAndDoesNotLeakCapacity() {
        insert(jdbc, clock, 2, "events");
        jdbc.failClaimUpdateAt = 2;
        var kafka = producer(true);
        var publisher = publisher(kafka);
        assertThrows(
                org.springframework.dao.DataAccessResourceFailureException.class,
                publisher::publishDue);
        assertEquals(0, kafka.history().size());
        assertEquals(0, publisher.inFlightCount());
        assertEquals(2, count(jdbc, "READY"));
        assertEquals(
                List.of(0L, 0L),
                jdbc.queryForList("SELECT fence FROM t_outbox ORDER BY event_id", Long.class));
        jdbc.failClaimUpdateAt = 0;
        publisher.publishDue();
        await(() -> count(jdbc, "PUBLISHED") == 2);
    }

    @Test
    void claimCommitsSeparatelyAndRestoresTheCallersRepeatableReadTransaction() {
        insert(jdbc, clock, 1, "events");
        jdbc.execute("CREATE TABLE caller_marker (id INT PRIMARY KEY)");
        var publisher = publisher(producer(false));
        var outer = new org.springframework.transaction.support.TransactionTemplate(manager);
        outer.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(
                status -> {
                    jdbc.update("INSERT INTO caller_marker VALUES (1)");
                    var outerConnection =
                            jdbc.execute(
                                    (org.springframework.jdbc.core.ConnectionCallback<
                                                    java.sql.Connection>)
                                            connection ->
                                                    org.springframework.jdbc.datasource
                                                            .DataSourceUtils.getTargetConnection(
                                                            connection));
                    assertEquals(
                            org.springframework.transaction.TransactionDefinition
                                    .ISOLATION_REPEATABLE_READ,
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager
                                    .getCurrentTransactionIsolationLevel());
                    assertEquals(1, publisher.claim().size());
                    assertSame(
                            outerConnection,
                            jdbc.execute(
                                    (org.springframework.jdbc.core.ConnectionCallback<
                                                    java.sql.Connection>)
                                            connection ->
                                                    org.springframework.jdbc.datasource
                                                            .DataSourceUtils.getTargetConnection(
                                                            connection)));
                    assertEquals(
                            org.springframework.transaction.TransactionDefinition
                                    .ISOLATION_REPEATABLE_READ,
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager
                                    .getCurrentTransactionIsolationLevel());
                    status.setRollbackOnly();
                });
        assertEquals(
                List.of(java.sql.Connection.TRANSACTION_READ_COMMITTED),
                List.copyOf(jdbc.claimIsolationLevels));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM caller_marker", Integer.class));
        assertEquals(1, count(jdbc, "SENDING"));
        assertEquals(1L, jdbc.queryForObject("SELECT fence FROM t_outbox", Long.class));
    }

    @Test
    void defaultAckBatchSharesOneTransactionAndConnectionForSixteenQueuedEvents() throws Exception {
        var registry = new SimpleMeterRegistry();
        var kafka = producer(false);
        var publisher = batchedPublisher(kafka, registry, manager);
        releaseSeventeenCallbacksBehindOneBlockedAck(publisher, kafka);
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(17, count(jdbc, "PUBLISHED"));
        assertEquals(16, OutboxSettings.defaults().ackBatchSize());
        assertEquals(2, OutboxSettings.defaults().ackBatchLingerMillis());
        assertEquals(
                2,
                registry.get("shortlink.outbox.ack.transaction")
                        .tag("outcome", "committed")
                        .timer()
                        .count());
        var batches = registry.get("shortlink.outbox.ack.batch.size").summary();
        assertEquals(2, batches.count());
        assertEquals(17, batches.totalAmount());
        assertEquals(16, batches.max());
        assertEquals(17, registry.get("shortlink.outbox.ack.committed.events").counter().count());
        assertEquals(17, registry.get("shortlink.outbox.ack.wait").timer().count());
        assertEquals(0, registry.get("shortlink.outbox.ack.processing").gauge().value());
        assertEquals(
                1, List.copyOf(jdbc.ackConnectionIds).subList(1, 17).stream().distinct().count());
        assertEquals(17, jdbc.ackAutoCommit.size());
        assertTrue(jdbc.ackAutoCommit.stream().noneMatch(Boolean::booleanValue));
        assertTrue(jdbc.acknowledgementThreads.stream().allMatch(n -> n.startsWith("outbox-ack-")));
    }

    @Test
    void failedSecondStatementRollsBackTheWholeBatchBeforeOneFencedRetryTransaction()
            throws Exception {
        var registry = new SimpleMeterRegistry();
        var kafka = producer(false);
        var publisher = batchedPublisher(kafka, registry, manager);
        jdbc.failAckUpdateAt = 3; // First batch has one event; fail the second item of batch two.
        releaseSeventeenCallbacksBehindOneBlockedAck(publisher, kafka);
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(1, count(jdbc, "PUBLISHED"));
        assertEquals(16, count(jdbc, "READY"));
        assertEquals(
                16,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE state='READY' AND published_at IS NULL"
                            + " AND fence=1",
                        Integer.class));
        assertEquals(1, registry.get("shortlink.outbox.ack.committed.events").counter().count());
        assertEquals(16, registry.get("shortlink.outbox.ack.retried.events").counter().count());
        assertEquals(
                1,
                registry.get("shortlink.outbox.ack.transaction")
                        .tag("outcome", "rolled_back")
                        .timer()
                        .count());
        assertEquals(
                1,
                registry.get("shortlink.outbox.ack.retry.transaction")
                        .tag("outcome", "committed")
                        .timer()
                        .count());
        assertEquals(16, jdbc.retryTransactions.size());
        assertTrue(jdbc.retryTransactions.stream().allMatch("shortlink-outbox-retry"::equals));
        jdbc.failAckUpdateAt = 0;
        clock.advance(1000);
        publisher.publishDue();
        await(() -> kafka.history().size() == 33);
        for (int i = 0; i < 16; i++) assertTrue(kafka.completeNext());
        await(() -> count(jdbc, "PUBLISHED") == 17);
    }

    @Test
    void uncertainRollbackLeavesTheWholeBatchToLeaseRecoveryWithoutAnotherWrite() throws Exception {
        var registry = new SimpleMeterRegistry();
        var uncertain =
                new DataSourceTransactionManager(jdbc.getDataSource()) {
                    @Override
                    protected void doRollback(
                            org.springframework.transaction.support.DefaultTransactionStatus
                                    status) {
                        super.doRollback(status);
                        if ("shortlink-outbox-ack"
                                .equals(
                                        org.springframework.transaction.support
                                                .TransactionSynchronizationManager
                                                .getCurrentTransactionName())) {
                            throw new org.springframework.transaction.TransactionSystemException(
                                    "Rollback reply lost");
                        }
                    }
                };
        var kafka = producer(false);
        var publisher = batchedPublisher(kafka, registry, uncertain);
        jdbc.failAckUpdateAt = 3;
        releaseSeventeenCallbacksBehindOneBlockedAck(publisher, kafka);
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(1, count(jdbc, "PUBLISHED"));
        assertEquals(16, count(jdbc, "SENDING"));
        assertEquals(0, count(jdbc, "READY"));
        assertTrue(jdbc.retryTransactions.isEmpty());
        assertEquals(
                1,
                registry.get("shortlink.outbox.ack.transaction")
                        .tag("outcome", "unknown")
                        .timer()
                        .count());
        assertEquals(1, registry.get("shortlink.outbox.ack.committed.events").counter().count());
    }

    @Test
    void closingAnOwnedBatchRollsItBackAndReleasesEverySlotOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        jdbc =
                new FaultJdbc(jdbc.getDataSource(), true) {
                    @Override
                    public int update(String sql, Object... args) {
                        if (sql.startsWith("UPDATE t_outbox SET state='PUBLISHED'")
                                && ackUpdates.get() == 2) {
                            entered.countDown();
                            awaitLatch(release);
                        }
                        return super.update(sql, args);
                    }
                };
        var registry = new SimpleMeterRegistry();
        var kafka = producer(false);
        var publisher = batchedPublisher(kafka, registry, manager);
        try {
            releaseSeventeenCallbacksBehindOneBlockedAck(publisher, kafka);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(16, publisher.inFlightCount());
            assertEquals(16, registry.get("shortlink.outbox.ack.processing").gauge().value());
            assertTimeout(java.time.Duration.ofSeconds(2), publisher::close);
            await(() -> publisher.inFlightCount() == 0);
            assertEquals(1, count(jdbc, "PUBLISHED"));
            assertEquals(16, count(jdbc, "SENDING"));
            assertEquals(0, registry.get("shortlink.outbox.ack.processing").gauge().value());
            publisher.close();
            assertEquals(0, publisher.inFlightCount());
        } finally {
            release.countDown();
        }
    }

    private OutboxPublisher batchedPublisher(
            MockProducer<String, String> kafka,
            SimpleMeterRegistry registry,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        // The legacy constructor deliberately uses the new default batch size of 16.
        // One worker makes the queued batch deterministic without changing production defaults.
        var publisher =
                new OutboxPublisher(
                        jdbc,
                        txManager,
                        clock,
                        kafka,
                        new OutboxSettings(64, 64, 4, 1, 12000, 30000, 1000, 3),
                        registry);
        publishers.add(publisher);
        return publisher;
    }

    private void releaseSeventeenCallbacksBehindOneBlockedAck(
            OutboxPublisher publisher, MockProducer<String, String> kafka) throws Exception {
        insert(jdbc, clock, 17, "events");
        jdbc.blockAck = true;
        publisher.publishDue();
        await(() -> kafka.history().size() == 17);
        assertTrue(kafka.completeNext());
        assertTrue(jdbc.ackEntered.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 16; i++) assertTrue(kafka.completeNext());
        assertEquals(17, publisher.inFlightCount());
        jdbc.blockAck = false;
        jdbc.releaseAck.countDown();
    }

    @Test
    void invalidCapacityAndThreadBudgetsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxSettings(65, 64, 4, 4, 12000, 30000, 5000, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxSettings(4, 4, 5, 4, 12000, 30000, 5000, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxSettings(64, 64, 4, 4, 12000, 30000, 5000, 3, 65, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxSettings(64, 64, 4, 4, 12000, 30000, 5000, 3, 16, 21));
    }
}
