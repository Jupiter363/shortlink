package com.jupiter.shortlink.command.outbox;

import static com.jupiter.shortlink.command.outbox.OutboxTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.mockito.AdditionalAnswers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Requires existing isolated MySQL/Kafka. Never starts containers or touches performance schemas.
 */
@Timeout(45)
class OutboxPublisherIntegrationTest {
    static DriverManagerDataSource source;
    static String bootstrap;
    static AdminClient admin;
    FaultJdbc jdbc;
    MutableClock clock;
    DataSourceTransactionManager manager;
    String topic;
    final List<OutboxPublisher> publishers = new ArrayList<>();

    @BeforeAll
    static void connect() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("ROOT"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        String url = System.getenv("SHORTLINK_OUTBOX_TEST_JDBC_URL");
        bootstrap = System.getenv("SHORTLINK_KAFKA_TEST_BOOTSTRAP");
        if (!"true".equals(System.getenv("SHORTLINK_OUTBOX_TEST_ALLOW_RESET"))
                || url == null
                || !url.matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:(3306|13306)/shortlink_outbox_it\\?.*")
                || bootstrap == null
                || !bootstrap.matches("(localhost|127\\.0\\.0\\.1):19092")) {
            throw new IllegalStateException(
                    "Explicit isolated Outbox MySQL/Kafka test configuration required");
        }
        source =
                new DriverManagerDataSource(
                        url,
                        System.getenv("SHORTLINK_OUTBOX_TEST_USER"),
                        System.getenv("SHORTLINK_OUTBOX_TEST_PASSWORD"));
        new org.springframework.jdbc.core.JdbcTemplate(source)
                .execute(
                        SCHEMA.replace(
                                "CREATE TABLE t_outbox", "CREATE TABLE IF NOT EXISTS t_outbox"));
        var schemaJdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        // Exercise the real secondary-index lock interactions, including an older isolated
        // fixture table created before this index was included in the integration setup.
        if (schemaJdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE"
                            + " table_schema=DATABASE() AND table_name='t_outbox' AND"
                            + " index_name='ix_outbox_due'",
                        Integer.class)
                == 0) {
            schemaJdbc.execute(
                    "CREATE INDEX ix_outbox_due ON t_outbox(state,next_attempt_at,lease_until)");
        }
        assertEquals(
                List.of("state", "next_attempt_at", "lease_until"),
                schemaJdbc.queryForList(
                        "SELECT column_name FROM information_schema.statistics WHERE"
                            + " table_schema=DATABASE() AND table_name='t_outbox' AND"
                            + " index_name='ix_outbox_due' ORDER BY seq_in_index",
                        String.class));
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrap);
        properties.put("default.api.timeout.ms", "10000");
        properties.put("request.timeout.ms", "5000");
        com.jupiter.shortlink.contract.KafkaSecurity.apply(properties);
        admin = AdminClient.create(properties);
    }

    @AfterAll
    static void disconnect() {
        if (admin != null) admin.close(Duration.ofSeconds(2));
    }

    @BeforeEach
    void fixture() throws Exception {
        jdbc = new FaultJdbc(source); // MySQL receives the exact production SKIP LOCKED query.
        jdbc.update("DELETE FROM t_outbox");
        manager = new DataSourceTransactionManager(source);
        clock = new MutableClock();
        topic = "shortlink.outbox.it." + UUID.randomUUID().toString().replace("-", "");
        admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1)))
                .all()
                .get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (jdbc != null) jdbc.releaseSelectedClaim.countDown();
        if (manager instanceof AckTransactions acknowledgements) acknowledgements.releaseAll();
        publishers.forEach(OutboxPublisher::close);
        if (topic != null) admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
        if (jdbc != null) jdbc.update("DELETE FROM t_outbox");
    }

    Producer<String, String> realProducer() {
        var producer =
                new KafkaProducer<String, String>(OutboxPublisher.producerProperties(bootstrap));
        try {
            producer.partitionsFor(topic); // Fail visibly if the broker cannot provide metadata.
            return producer;
        } catch (RuntimeException failure) {
            producer.close(Duration.ZERO);
            throw failure;
        }
    }

    OutboxPublisher publisher(Producer<String, String> producer) {
        return publisher(producer, null);
    }

    OutboxPublisher publisher(Producer<String, String> producer, SimpleMeterRegistry registry) {
        var publisher =
                new OutboxPublisher(
                        jdbc,
                        manager,
                        clock,
                        producer,
                        new OutboxSettings(64, 64, 4, 4, 12000, 30000, 2000, 3, 1, 0),
                        registry);
        publishers.add(publisher);
        return publisher;
    }

    @FunctionalInterface
    interface CompletionHook {
        void accept(
                ProducerRecord<String, String> record,
                Callback callback,
                RecordMetadata metadata,
                Exception exception);
    }

    @SuppressWarnings("unchecked")
    Producer<String, String> wrappedProducer(CompletionHook hook) {
        Producer<String, String> real = realProducer();
        Producer<String, String> wrapper =
                mock(Producer.class, AdditionalAnswers.delegatesTo(real));
        doAnswer(
                        invocation -> {
                            ProducerRecord<String, String> record = invocation.getArgument(0);
                            Callback callback = invocation.getArgument(1);
                            return real.send(
                                    record,
                                    (metadata, error) ->
                                            hook.accept(record, callback, metadata, error));
                        })
                .when(wrapper)
                .send(any(), any());
        return wrapper;
    }

    void publishUntil(OutboxPublisher publisher, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (count(jdbc, "PUBLISHED") < expected && System.nanoTime() < deadline) {
            publisher.publishDue();
            assertTrue(publisher.inFlightCount() <= 64);
            clock.advance(10);
            Thread.sleep(10);
        }
        assertEquals(expected, count(jdbc, "PUBLISHED"));
        await(() -> publisher.inFlightCount() == 0);
    }

    int readAndAssertEvents(int uniqueEvents, int minimumRecords) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "256");
        com.jupiter.shortlink.contract.KafkaSecurity.apply(properties);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < uniqueEvents; i++) expected.add("{\"eventId\":\"event-" + i + "\"}");
        Set<String> actual = new HashSet<>();
        int records = 0;
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while ((actual.size() < uniqueEvents || records < minimumRecords)
                    && System.nanoTime() < deadline
                    && records < 2048) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    assertTrue(expected.contains(record.value()));
                    actual.add(record.value());
                    records++;
                }
            }
        }
        assertEquals(expected, actual);
        assertTrue(records >= minimumRecords);
        return records;
    }

    @Test
    void boundedBatchPublishesAllRealEventsWithCommittedFencedSqlAcknowledgements()
            throws Exception {
        insert(jdbc, clock, 128, topic);
        var publisher = publisher(realProducer());
        publishUntil(publisher, 128);
        readAndAssertEvents(128, 128);
        assertEquals(0, count(jdbc, "READY"));
        assertEquals(0, count(jdbc, "SENDING"));
        assertTrue(jdbc.acknowledgementThreads.stream().allMatch(t -> t.startsWith("outbox-ack-")));
    }

    @Test
    void realBrokerAcceptedEventsSurviveDelayedAckAndMysqlLeaseTakeover() throws Exception {
        insert(jdbc, clock, 3, topic);
        var callbacks = new ConcurrentLinkedQueue<Runnable>();
        var registry = new SimpleMeterRegistry();
        var old =
                publisher(
                        wrappedProducer(
                                (record, callback, metadata, error) ->
                                        callbacks.add(
                                                () -> callback.onCompletion(metadata, error))),
                        registry);
        old.publishDue();
        await(() -> callbacks.size() == 3);
        assertEquals(3, old.inFlightCount());
        assertEquals(3, count(jdbc, "SENDING"));
        readAndAssertEvents(3, 3);
        clock.advance(31000);
        var replacement = publisher(realProducer());
        publishUntil(replacement, 3);
        callbacks.forEach(Runnable::run);
        await(() -> old.inFlightCount() == 0);
        assertEquals(3, registry.get("shortlink.outbox.fenced.acknowledgements").counter().count());
        assertEquals(
                List.of(2L, 2L, 2L),
                jdbc.queryForList("SELECT fence FROM t_outbox ORDER BY event_id", Long.class));
        readAndAssertEvents(3, 6); // Explicitly demonstrate replay, not HTTP-to-Kafka exactly once.
    }

    @Test
    void lostBrokerAckAndFailedSqlAckRetryOnlyTheirEvents() throws Exception {
        insert(jdbc, clock, 3, topic);
        jdbc.failBeforeAck.set(1);
        AtomicBoolean loseAck = new AtomicBoolean(true);
        var publisher =
                publisher(
                        wrappedProducer(
                                (record, callback, metadata, error) -> {
                                    if (record.key().equals("key-0")
                                            && error == null
                                            && loseAck.compareAndSet(true, false)) {
                                        callback.onCompletion(
                                                metadata,
                                                new org.apache.kafka.common.errors.TimeoutException(
                                                        "Injected lost ACK"));
                                    } else callback.onCompletion(metadata, error);
                                }));
        publisher.publishDue();
        await(() -> publisher.inFlightCount() == 0);
        assertEquals(1, count(jdbc, "PUBLISHED"));
        assertEquals(2, count(jdbc, "READY"));
        clock.advance(1000);
        publishUntil(publisher, 3);
        assertEquals(
                List.of(1, 2, 2),
                jdbc.queryForList(
                        "SELECT attempts FROM t_outbox ORDER BY attempts", Integer.class));
        readAndAssertEvents(3, 5);
    }

    @Test
    void shutdownWithHeldBrokerCallbacksLeavesRowsRecoverable() throws Exception {
        insert(jdbc, clock, 4, topic);
        var callbacks = new ConcurrentLinkedQueue<Runnable>();
        var old =
                publisher(
                        wrappedProducer(
                                (record, callback, metadata, error) ->
                                        callbacks.add(
                                                () -> callback.onCompletion(metadata, error))));
        old.publishDue();
        await(() -> callbacks.size() == 4);
        old.close();
        assertEquals(0, old.inFlightCount());
        assertEquals(4, count(jdbc, "READY"));
        callbacks.forEach(Runnable::run);
        assertEquals(0, count(jdbc, "PUBLISHED"));
        clock.advance(1000);
        publishUntil(publisher(realProducer()), 4);
        readAndAssertEvents(4, 8);
    }

    @Test
    void mysqlClaimIsIndependentOfAnOuterRepeatableReadBusinessTransaction() {
        insert(jdbc, clock, 3, topic);
        var publisher = publisher(realProducer());
        var outer = new org.springframework.transaction.support.TransactionTemplate(manager);
        outer.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(
                status -> {
                    long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    jdbc.update(
                            "INSERT INTO"
                                + " t_outbox(event_id,topic,event_key,payload,next_attempt_at,created_at)"
                                + " VALUES ('caller-uncommitted',?,'caller','{}',?,?)",
                            topic,
                            clock.millis(),
                            clock.millis());
                    var claims = publisher.claim();
                    assertEquals(3, claims.size());
                    assertTrue(claims.stream().noneMatch(c -> c.id().equals("caller-uncommitted")));
                    assertEquals(
                            connection, jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    assertEquals(
                            "REPEATABLE-READ",
                            jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                    status.setRollbackOnly();
                });
        assertEquals(
                List.of(Connection.TRANSACTION_READ_COMMITTED),
                List.copyOf(jdbc.claimIsolationLevels));
        assertEquals(3, count(jdbc, "SENDING"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE event_id='caller-uncommitted'",
                        Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 3})
    void mysqlInsertCommitsWhileAnEmptyOrPopulatedClaimTransactionIsStillOpen(int existing)
            throws Exception {
        insert(jdbc, clock, existing, topic);
        jdbc.blockAfterClaimSelect = true;
        var publisher = publisher(realProducer());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var claiming = workers.submit(publisher::claim);
            assertTrue(jdbc.claimSelected.await(2, TimeUnit.SECONDS));
            var inserting =
                    workers.submit(
                            () -> {
                                new org.springframework.transaction.support.TransactionTemplate(
                                                manager)
                                        .executeWithoutResult(
                                                status -> {
                                                    var rows = new ArrayList<Object[]>();
                                                    for (int n = 0; n < 128; n++) {
                                                        rows.add(
                                                                new Object[] {
                                                                    "inserted-" + n,
                                                                    topic,
                                                                    "key-" + n,
                                                                    "{}",
                                                                    clock.millis(),
                                                                    clock.millis()
                                                                });
                                                    }
                                                    jdbc.batchUpdate(
                                                            "INSERT INTO"
                                                                + " t_outbox(event_id,topic,event_key,payload,next_attempt_at,created_at)"
                                                                + " VALUES (?,?,?,?,?,?)",
                                                            rows);
                                                });
                                return 128;
                            });
            // A timeout, deadlock (1213), or lock timeout (1205) fails this test directly;
            // there is no SQL exception retry. The claim has not yet committed.
            assertEquals(128, inserting.get(2, TimeUnit.SECONDS));
            assertFalse(claiming.isDone());
            jdbc.releaseSelectedClaim.countDown();
            assertEquals(existing, claiming.get(2, TimeUnit.SECONDS).size());
            assertEquals(128, count(jdbc, "READY"));
            assertEquals(existing, count(jdbc, "SENDING"));
            assertEquals(
                    List.of(Connection.TRANSACTION_READ_COMMITTED),
                    List.copyOf(jdbc.claimIsolationLevels));
        } finally {
            jdbc.releaseSelectedClaim.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mysqlTwoClaimersKeepOwnershipDisjointAndRecoverAnOldLockedEvent() throws Exception {
        insert(jdbc, clock, 129, topic);
        jdbc.blockAfterClaimSelect = true;
        var first = publisher(realProducer());
        var secondJdbc = new FaultJdbc(source);
        var second =
                new OutboxPublisher(
                        secondJdbc,
                        manager,
                        clock,
                        realProducer(),
                        new OutboxSettings(64, 64, 4, 4, 12000, 30000, 2000, 3));
        publishers.add(second);
        var worker = Executors.newSingleThreadExecutor();
        try (Connection locked = source.getConnection()) {
            locked.setAutoCommit(false);
            try (var statement =
                    locked.prepareStatement(
                            "SELECT event_id FROM t_outbox WHERE event_id='event-0' FOR UPDATE")) {
                statement.executeQuery().close();
            }
            try {
                var firstClaiming = worker.submit(first::claim);
                assertTrue(jdbc.claimSelected.await(2, TimeUnit.SECONDS));
                var concurrent = second.claim();
                assertFalse(firstClaiming.isDone());
                jdbc.releaseSelectedClaim.countDown();
                var initial = firstClaiming.get(2, TimeUnit.SECONDS);
                assertEquals(64, initial.size());
                // The optimizer may lock scanned rows beyond LIMIT while sorting; don't
                // assume SKIP LOCKED promises exactly 64 row locks or strict fairness.
                var afterCommit = second.claim();
                var all = new ArrayList<OutboxPublisher.Claimed>(initial);
                all.addAll(concurrent);
                all.addAll(afterCommit);
                assertEquals(128, all.size());
                assertEquals(128, all.stream().map(OutboxPublisher.Claimed::id).distinct().count());
                assertTrue(all.stream().noneMatch(c -> c.id().equals("event-0")));
                assertTrue(all.stream().allMatch(c -> c.fence() == 1 && c.attempts() == 1));
                locked.rollback();
                var restored = second.claim();
                assertEquals(1, restored.size());
                assertEquals("event-0", restored.get(0).id());
                all.addAll(restored);
                for (var claim : all) assertTrue(second.ack(claim));
                assertEquals(129, count(jdbc, "PUBLISHED"));
                assertEquals(0, count(jdbc, "READY"));
                assertEquals(0, count(jdbc, "SENDING"));
                assertTrue(
                        jdbc.claimIsolationLevels.stream()
                                .allMatch(level -> level == Connection.TRANSACTION_READ_COMMITTED));
                assertTrue(
                        secondJdbc.claimIsolationLevels.stream()
                                .allMatch(level -> level == Connection.TRANSACTION_READ_COMMITTED));
            } finally {
                jdbc.releaseSelectedClaim.countDown();
                locked.rollback();
            }
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mysqlSkipLockedClaimDoesNotWaitForAnotherTransactionsEvent() throws Exception {
        insert(jdbc, clock, 3, topic);
        var publisher = publisher(realProducer());
        try (Connection locked = source.getConnection()) {
            locked.setAutoCommit(false);
            try (var statement =
                    locked.prepareStatement(
                            "SELECT event_id FROM t_outbox WHERE event_id='event-0' FOR UPDATE")) {
                statement.executeQuery().close();
            }
            var claims = publisher.claim();
            assertEquals(2, claims.size());
            assertTrue(claims.stream().noneMatch(c -> c.id().equals("event-0")));
            locked.rollback();
        }
        var remainder = publisher.claim();
        assertEquals(1, remainder.size());
        assertEquals("event-0", remainder.get(0).id());
    }

    @Test
    void sixteenRealBrokerAcknowledgementsCommitOnceAndKeepCapacityUntilCommit() throws Exception {
        var batch = primeAckBatch(true, null, false, false);
        releaseBufferedBatch(batch);
        assertTrue(batch.transactions.secondAckCommitEntered.await(2, TimeUnit.SECONDS));

        // The primer committed separately. All sixteen subsequent UPDATEs have executed in
        // one transaction, but another connection must still see their original SENDING state.
        assertEquals(2, batch.transactions.ackCommitAttempts.get());
        assertEquals(1, batch.transactions.ackCommits.get());
        assertEquals(1, count(jdbc, "PUBLISHED"));
        assertEquals(16, count(jdbc, "SENDING"));
        assertEquals(16, batch.publisher.inFlightCount());
        var statements = List.copyOf(batch.sql.ackStatements);
        assertEquals(17, statements.size());
        var orderedIds = new ArrayList<String>();
        for (int n = 1; n <= 16; n++) orderedIds.add("event-" + n);
        Collections.sort(orderedIds);
        assertEquals(
                orderedIds, statements.subList(1, 17).stream().map(AckStatement::eventId).toList());
        assertEquals(
                1,
                statements.subList(1, 17).stream()
                        .map(AckStatement::connectionId)
                        .distinct()
                        .count());
        assertTrue(statements.stream().noneMatch(AckStatement::autoCommit));
        assertTrue(
                statements.stream()
                        .allMatch(s -> s.isolation == Connection.TRANSACTION_READ_COMMITTED));
        assertTrue(
                statements.stream()
                        .allMatch(s -> s.transactionName.equals("shortlink-outbox-ack")));

        batch.transactions.releaseSecondAckCommit.countDown();
        await(() -> batch.publisher.inFlightCount() == 0);
        assertEquals(17, count(jdbc, "PUBLISHED"));
        assertEquals(2, batch.transactions.ackCommits.get());
        assertEquals(0, batch.transactions.retryCommitAttempts.get());
        readAndAssertEvents(17, 17);
    }

    @Test
    void staleFenceInARealAckBatchDoesNotRollBackTheOtherFifteenEvents() throws Exception {
        var batch = primeAckBatch(true, null, false, false);
        assertEquals(
                1,
                jdbc.update(
                        "UPDATE t_outbox SET fence=fence+1,lease_until=? WHERE event_id='event-5'"
                            + " AND state='SENDING'",
                        clock.millis() + 60000));
        releaseBufferedBatch(batch);
        assertTrue(batch.transactions.secondAckCommitEntered.await(2, TimeUnit.SECONDS));
        assertEquals(
                0.0,
                batch.registry.get("shortlink.outbox.fenced.acknowledgements").counter().count());
        assertEquals(16, batch.publisher.inFlightCount());
        batch.transactions.releaseSecondAckCommit.countDown();

        await(() -> batch.publisher.inFlightCount() == 0);
        assertEquals(16, count(jdbc, "PUBLISHED"));
        assertEquals(1, count(jdbc, "SENDING"));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT fence FROM t_outbox WHERE event_id='event-5'", Long.class));
        assertNull(
                jdbc.queryForObject(
                        "SELECT published_at FROM t_outbox WHERE event_id='event-5'", Long.class));
        assertEquals(
                1.0,
                batch.registry.get("shortlink.outbox.fenced.acknowledgements").counter().count());
        assertEquals(2, batch.transactions.ackCommits.get());
        assertEquals(0, batch.transactions.retryCommitAttempts.get());
        batch.callbacks.deliver("key-5");
        assertEquals(0, batch.publisher.inFlightCount());
        assertEquals(
                1.0,
                batch.registry.get("shortlink.outbox.fenced.acknowledgements").counter().count());
    }

    @Test
    void secondAckStatementFailureRollsBackTheWholeBatchBeforeOneReadyTransactionAndReplay()
            throws Exception {
        // Lexical event ordering is event-1,event-10,...; failing event-10 proves that an
        // already executed first UPDATE is rolled back, rather than committed individually.
        var batch = primeAckBatch(false, "event-10", true, false);
        releaseBufferedBatch(batch);
        assertTrue(batch.transactions.retryCommitEntered.await(2, TimeUnit.SECONDS));
        assertEquals(
                List.of("event-0", "event-1", "event-10"),
                batch.sql.ackStatements.stream().map(AckStatement::eventId).toList());
        assertEquals(1, batch.transactions.ackRollbacks.get());
        assertEquals(1, batch.transactions.ackCommitAttempts.get());
        assertEquals(1, batch.transactions.retryCommitAttempts.get());
        assertEquals(0, batch.transactions.retryCommits.get());
        assertEquals(1, count(jdbc, "PUBLISHED"));
        assertEquals(16, count(jdbc, "SENDING"));
        assertEquals(0, count(jdbc, "READY"));
        assertEquals(16, batch.publisher.inFlightCount());
        batch.transactions.releaseRetryCommit.countDown();

        await(() -> batch.publisher.inFlightCount() == 0);
        assertEquals(1, count(jdbc, "PUBLISHED"));
        assertEquals(16, count(jdbc, "READY"));
        assertEquals(1, batch.transactions.retryCommits.get());
        assertTrue(batch.registry.get("shortlink.outbox.ack.failures").counter().count() > 0);
        assertEquals(0.0, batch.registry.get("shortlink.outbox.retry.failures").counter().count());
        assertEquals(
                List.of(1),
                jdbc.queryForList("SELECT DISTINCT attempts FROM t_outbox", Integer.class));
        batch.callbacks.automatic = true;
        clock.advance(1000);
        publishUntil(batch.publisher, 17);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM t_outbox WHERE event_id='event-0'", Integer.class));
        assertEquals(
                16,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE attempts=2", Integer.class));
        readAndAssertEvents(17, 33);
    }

    @Test
    void successfulMysqlAckCommitWithAnUnknownReplyNeverReopensPublishedRowsOrReleasesTwice()
            throws Exception {
        var batch = primeAckBatch(false, null, false, true);
        releaseBufferedBatch(batch);
        await(() -> batch.publisher.inFlightCount() == 0);
        assertEquals(2, batch.transactions.ackCommitAttempts.get());
        assertEquals(2, batch.transactions.ackCommits.get());
        assertEquals(1, batch.transactions.injectedUnknownCommits.get());
        assertEquals(17, count(jdbc, "PUBLISHED"));
        assertEquals(0, count(jdbc, "READY"));
        assertEquals(0, count(jdbc, "SENDING"));
        assertEquals(0, batch.transactions.retryCommitAttempts.get());
        assertTrue(batch.registry.get("shortlink.outbox.ack.failures").counter().count() > 0);

        // Repeating the actual broker callbacks must not create another ACK transaction or
        // release the same semaphore slots again after the uncertain transaction result.
        for (int n = 0; n <= 16; n++) batch.callbacks.deliver("key-" + n);
        batch.publisher
                .close(); // Join terminal workers before asserting no deferred duplicate work.
        assertEquals(0, batch.publisher.inFlightCount());
        assertEquals(2, batch.transactions.ackCommitAttempts.get());
        assertEquals(0, batch.transactions.retryCommitAttempts.get());
        assertEquals(17, count(jdbc, "PUBLISHED"));
        assertEquals(
                List.of(1),
                jdbc.queryForList("SELECT DISTINCT attempts FROM t_outbox", Integer.class));
    }

    BatchHarness primeAckBatch(
            boolean holdSecondCommit,
            String failEvent,
            boolean holdRetryCommit,
            boolean unknownSecondCommit)
            throws Exception {
        var sql = new AckJdbc(source);
        sql.failEvent = failEvent;
        jdbc = sql;
        var transactions = new AckTransactions(source);
        transactions.holdSecondCommit = holdSecondCommit;
        transactions.holdRetryCommit = holdRetryCommit;
        transactions.unknownSecondCommit = unknownSecondCommit;
        manager = transactions;
        insert(jdbc, clock, 17, topic);
        var callbacks = new CapturedCallbacks();
        var registry = new SimpleMeterRegistry();
        var publisher =
                new OutboxPublisher(
                        jdbc,
                        manager,
                        clock,
                        wrappedProducer(callbacks::capture),
                        new OutboxSettings(32, 32, 4, 1, 12000, 30000, 2000, 3, 16, 2),
                        registry);
        publishers.add(publisher);
        publisher.publishDue();
        await(() -> callbacks.pending.size() == 17);
        assertEquals(17, publisher.inFlightCount());
        assertEquals(17, count(jdbc, "SENDING"));
        callbacks.deliver("key-0");
        assertTrue(transactions.firstAckCommitEntered.await(2, TimeUnit.SECONDS));
        assertEquals(0, count(jdbc, "PUBLISHED"));
        assertEquals(17, publisher.inFlightCount());
        return new BatchHarness(publisher, callbacks, transactions, sql, registry);
    }

    void releaseBufferedBatch(BatchHarness batch) {
        // The only ACK worker is stopped inside the primer commit. All sixteen callbacks
        // enqueue before it resumes, so grouping does not depend on a 2ms scheduling race.
        for (int n = 1; n <= 16; n++) batch.callbacks.deliver("key-" + n);
        assertEquals(17, batch.publisher.inFlightCount());
        batch.transactions.releaseFirstAckCommit.countDown();
    }

    record BatchHarness(
            OutboxPublisher publisher,
            CapturedCallbacks callbacks,
            AckTransactions transactions,
            AckJdbc sql,
            SimpleMeterRegistry registry) {}

    static final class CapturedCallbacks {
        final Map<String, Runnable> pending = new ConcurrentHashMap<>();
        volatile boolean automatic;

        void capture(
                ProducerRecord<String, String> record,
                Callback callback,
                RecordMetadata metadata,
                Exception error) {
            if (automatic) {
                callback.onCompletion(metadata, error);
            } else {
                pending.put(
                        record.key(),
                        () -> {
                            assertNull(
                                    error,
                                    "Actual broker must accept the fixture event before its"
                                        + " callback is held");
                            assertNotNull(metadata);
                            callback.onCompletion(metadata, null);
                        });
            }
        }

        void deliver(String key) {
            Runnable callback = pending.get(key);
            assertNotNull(callback, "Missing actual broker callback: " + key);
            callback.run();
        }
    }

    record AckStatement(
            String eventId,
            long connectionId,
            boolean autoCommit,
            int isolation,
            String transactionName) {}

    static final class AckJdbc extends FaultJdbc {
        final ConcurrentLinkedQueue<AckStatement> ackStatements = new ConcurrentLinkedQueue<>();
        final AtomicBoolean failed = new AtomicBoolean();
        volatile String failEvent;

        AckJdbc(DriverManagerDataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("UPDATE t_outbox SET state='PUBLISHED'")) {
                Connection connection = DataSourceUtils.getConnection(getDataSource());
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertTrue(rows.next());
                    ackStatements.add(
                            new AckStatement(
                                    (String) args[1],
                                    rows.getLong(1),
                                    connection.getAutoCommit(),
                                    connection.getTransactionIsolation(),
                                    TransactionSynchronizationManager.getCurrentTransactionName()));
                } catch (java.sql.SQLException failure) {
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                            "Cannot inspect the ACK transaction", failure);
                } finally {
                    DataSourceUtils.releaseConnection(connection, getDataSource());
                }
                if (Objects.equals(failEvent, args[1]) && failed.compareAndSet(false, true)) {
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                            "Injected failure of the second ACK statement");
                }
            }
            return super.update(sql, args);
        }
    }

    static final class AckTransactions extends DataSourceTransactionManager {
        final AtomicInteger ackCommitAttempts = new AtomicInteger();
        final AtomicInteger ackCommits = new AtomicInteger();
        final AtomicInteger ackRollbacks = new AtomicInteger();
        final AtomicInteger retryCommitAttempts = new AtomicInteger();
        final AtomicInteger retryCommits = new AtomicInteger();
        final AtomicInteger injectedUnknownCommits = new AtomicInteger();
        final CountDownLatch firstAckCommitEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstAckCommit = new CountDownLatch(1);
        final CountDownLatch secondAckCommitEntered = new CountDownLatch(1);
        final CountDownLatch releaseSecondAckCommit = new CountDownLatch(1);
        final CountDownLatch retryCommitEntered = new CountDownLatch(1);
        final CountDownLatch releaseRetryCommit = new CountDownLatch(1);
        volatile boolean holdSecondCommit;
        volatile boolean holdRetryCommit;
        volatile boolean unknownSecondCommit;

        AckTransactions(DriverManagerDataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            String name = TransactionSynchronizationManager.getCurrentTransactionName();
            boolean ack = "shortlink-outbox-ack".equals(name);
            boolean retry = "shortlink-outbox-retry".equals(name);
            int ordinal = ack ? ackCommitAttempts.incrementAndGet() : 0;
            if (ack && ordinal == 1) {
                firstAckCommitEntered.countDown();
                awaitLatch(releaseFirstAckCommit);
            } else if (ack && ordinal == 2 && holdSecondCommit) {
                secondAckCommitEntered.countDown();
                awaitLatch(releaseSecondAckCommit);
            }
            if (retry) {
                retryCommitAttempts.incrementAndGet();
                if (holdRetryCommit) {
                    retryCommitEntered.countDown();
                    awaitLatch(releaseRetryCommit);
                }
            }
            super.doCommit(status);
            if (ack) ackCommits.incrementAndGet();
            if (retry) retryCommits.incrementAndGet();
            if (ack && ordinal == 2 && unknownSecondCommit) {
                injectedUnknownCommits.incrementAndGet();
                throw new TransactionSystemException(
                        "Injected loss of the successful ACK commit reply");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            boolean ack =
                    "shortlink-outbox-ack"
                            .equals(TransactionSynchronizationManager.getCurrentTransactionName());
            super.doRollback(status);
            if (ack) ackRollbacks.incrementAndGet();
        }

        void releaseAll() {
            releaseFirstAckCommit.countDown();
            releaseSecondAckCommit.countDown();
            releaseRetryCommit.countDown();
        }
    }
}
