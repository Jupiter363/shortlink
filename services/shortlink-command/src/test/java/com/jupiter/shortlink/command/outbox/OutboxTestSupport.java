package com.jupiter.shortlink.command.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

final class OutboxTestSupport {
    static final String SCHEMA =
            "CREATE TABLE t_outbox (event_id VARCHAR(64) PRIMARY KEY,topic VARCHAR(128) NOT"
                + " NULL,event_key VARCHAR(256) NOT NULL,payload MEDIUMTEXT NOT NULL,state"
                + " VARCHAR(16) NOT NULL DEFAULT 'READY',attempts INT NOT NULL DEFAULT"
                + " 0,next_attempt_at BIGINT NOT NULL,lease_until BIGINT NOT NULL DEFAULT 0,fence"
                + " BIGINT NOT NULL DEFAULT 0,created_at BIGINT NOT NULL,published_at BIGINT)";

    static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_000_000);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }

        void advance(long amount) {
            millis.addAndGet(amount);
        }
    }

    static class FaultJdbc extends JdbcTemplate {
        final boolean h2Dialect;
        final ConcurrentLinkedQueue<String> acknowledgementThreads = new ConcurrentLinkedQueue<>();
        final AtomicInteger failBeforeAck = new AtomicInteger();
        final AtomicInteger failAfterAck = new AtomicInteger();
        final AtomicInteger claimUpdates = new AtomicInteger();
        final AtomicInteger ackUpdates = new AtomicInteger();
        final ConcurrentLinkedQueue<Integer> ackConnectionIds = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Boolean> ackAutoCommit = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> retryTransactions = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Integer> claimIsolationLevels = new ConcurrentLinkedQueue<>();
        volatile int failClaimUpdateAt;
        volatile int failAckUpdateAt;
        volatile boolean failRetry;
        volatile boolean blockAck;
        volatile boolean blockClaim;
        volatile boolean blockAfterClaimSelect;
        final CountDownLatch ackEntered = new CountDownLatch(1);
        final CountDownLatch claimEntered = new CountDownLatch(1);
        final CountDownLatch releaseAck = new CountDownLatch(1);
        final CountDownLatch releaseClaim = new CountDownLatch(1);
        final CountDownLatch claimSelected = new CountDownLatch(1);
        final CountDownLatch releaseSelectedClaim = new CountDownLatch(1);

        FaultJdbc(DataSource dataSource) {
            this(dataSource, false);
        }

        FaultJdbc(DataSource dataSource, boolean h2Dialect) {
            super(dataSource);
            this.h2Dialect = h2Dialect;
            setQueryTimeout(3);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("UPDATE t_outbox SET state='SENDING'")
                    && claimUpdates.incrementAndGet() == failClaimUpdateAt) {
                throw new DataAccessResourceFailureException("Claim transaction failed");
            }
            boolean ack = sql.startsWith("UPDATE t_outbox SET state='PUBLISHED'");
            if (ack || sql.startsWith("UPDATE t_outbox SET state='READY'")) {
                acknowledgementThreads.add(Thread.currentThread().getName());
            }
            if (sql.startsWith("UPDATE t_outbox SET state='READY'")) {
                retryTransactions.add(
                        java.util.Objects.toString(
                                org.springframework.transaction.support
                                        .TransactionSynchronizationManager
                                        .getCurrentTransactionName(),
                                "NONE"));
            }
            if (ack) {
                ackEntered.countDown();
                if (blockAck) awaitLatch(releaseAck);
                java.sql.Connection connection = DataSourceUtils.getConnection(getDataSource());
                try {
                    ackConnectionIds.add(
                            System.identityHashCode(
                                    DataSourceUtils.getTargetConnection(connection)));
                    ackAutoCommit.add(connection.getAutoCommit());
                } catch (java.sql.SQLException failure) {
                    throw new DataAccessResourceFailureException(
                            "Cannot inspect ACK connection", failure);
                } finally {
                    DataSourceUtils.releaseConnection(connection, getDataSource());
                }
                if (ackUpdates.incrementAndGet() == failAckUpdateAt) {
                    throw new DataAccessResourceFailureException("Injected ACK statement failure");
                }
                if (take(failBeforeAck))
                    throw new DataAccessResourceFailureException("ACK failed before commit");
                int result = super.update(sql, args);
                if (take(failAfterAck)) {
                    if (org.springframework.transaction.support.TransactionSynchronizationManager
                            .isSynchronizationActive()) {
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .registerSynchronization(
                                        new org.springframework.transaction.support
                                                .TransactionSynchronization() {
                                            @Override
                                            public void afterCommit() {
                                                throw new DataAccessResourceFailureException(
                                                        "ACK commit reply lost");
                                            }
                                        });
                    } else {
                        throw new DataAccessResourceFailureException("ACK commit outcome unknown");
                    }
                }
                return result;
            }
            if (failRetry && sql.startsWith("UPDATE t_outbox SET state='READY'")) {
                throw new DataAccessResourceFailureException("Retry database unavailable");
            }
            return super.update(sql, args);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (blockClaim && sql.startsWith("SELECT event_id")) {
                claimEntered.countDown();
                awaitLatch(releaseClaim);
            }
            // Bundled H2 2.1 cannot parse SKIP LOCKED. Unit tests exercise the asynchronous
            // protocol, not this MySQL locking feature; the real MySQL IT uses unmodified SQL.
            boolean claim = sql.startsWith("SELECT event_id,topic,event_key,payload");
            if (claim) {
                java.sql.Connection connection = DataSourceUtils.getConnection(getDataSource());
                try {
                    claimIsolationLevels.add(connection.getTransactionIsolation());
                } catch (java.sql.SQLException failure) {
                    throw new DataAccessResourceFailureException(
                            "Cannot inspect claim isolation", failure);
                } finally {
                    DataSourceUtils.releaseConnection(connection, getDataSource());
                }
            }
            List<T> rows =
                    super.query(
                            h2Dialect ? sql.replace(" FOR UPDATE SKIP LOCKED", " FOR UPDATE") : sql,
                            mapper,
                            args);
            if (claim && blockAfterClaimSelect) {
                claimSelected.countDown();
                awaitLatch(releaseSelectedClaim);
            }
            return rows;
        }

        private static boolean take(AtomicInteger remaining) {
            return remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
        }
    }

    static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Fixture latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DataAccessResourceFailureException("Interrupted fixture", interrupted);
        }
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }
        assertTrue(
                condition.getAsBoolean(),
                "Condition did not become true before the bounded deadline");
    }

    static void insert(JdbcTemplate jdbc, Clock clock, int count, String topic) {
        for (int n = 0; n < count; n++) {
            jdbc.update(
                    "INSERT INTO"
                        + " t_outbox(event_id,topic,event_key,payload,next_attempt_at,created_at)"
                        + " VALUES(?,?,?,?,?,?)",
                    "event-" + n,
                    topic,
                    "key-" + n,
                    "{\"eventId\":\"event-" + n + "\"}",
                    clock.millis(),
                    clock.millis());
        }
    }

    static int count(JdbcTemplate jdbc, String state) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_outbox WHERE state=?", Integer.class, state);
    }
}
