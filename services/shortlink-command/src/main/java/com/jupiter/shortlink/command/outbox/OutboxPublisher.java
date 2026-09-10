package com.jupiter.shortlink.command.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Kafka and SQL acknowledgements are separate commits: delivery remains at least once. */
@Component
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate ackTx;
    private final TransactionTemplate retryTx;
    private final Clock clock;
    private final Producer<String, String> producer;
    private final OutboxSettings settings;
    private final Semaphore capacity;
    private final Set<Delivery> inFlight = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor senders;
    private final ThreadPoolExecutor acknowledgements;
    private final ReentrantLock pollLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastWarning = new AtomicLong();
    private final Counter ackFailures, brokerFailures, retryFailures, fencedAcknowledgements;
    private final Counter claimFailures, committedEvents, retriedEvents;
    private final Timer claimSuccessTimer, claimFailureTimer;
    private final Timer ackCommitTimer, ackRollbackTimer, ackUnknownTimer;
    private final Timer retryCommitTimer, retryFailureTimer, ackWaitTimer;
    private final DistributionSummary claimBatchSizes, ackBatchSizes;
    private final AtomicLong ackProcessing = new AtomicLong();

    public record Claimed(
            String id, String topic, String key, String payload, long fence, int attempts) {}

    private static final class Delivery {
        final Claimed claim;
        final long startedNanos = System.nanoTime();
        final AtomicBoolean completed = new AtomicBoolean();
        volatile Runnable sendTask;

        Delivery(Claimed claim) {
            this.claim = claim;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxPublisher(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            @Value("${shortlink.kafka.bootstrap-servers}") String brokers,
            @Value("${shortlink.outbox.batch-size:64}") int batch,
            @Value("${shortlink.outbox.max-in-flight:64}") int inFlight,
            @Value("${shortlink.outbox.send-workers:4}") int sendWorkers,
            @Value("${shortlink.outbox.ack-workers:4}") int ackWorkers,
            @Value("${shortlink.outbox.ack-timeout-ms:12000}") long ackTimeout,
            @Value("${shortlink.outbox.lease-ms:30000}") long lease,
            @Value("${shortlink.outbox.shutdown-ms:5000}") long shutdown,
            @Value("${shortlink.outbox.db-timeout-seconds:3}") int dbTimeout,
            @Value("${shortlink.outbox.ack-batch-size:16}") int ackBatchSize,
            @Value("${shortlink.outbox.ack-batch-linger-ms:2}") long ackBatchLinger,
            MeterRegistry registry) {
        this(
                boundedJdbc(jdbc, dbTimeout),
                manager,
                clock,
                new OutboxSettings(
                        batch,
                        inFlight,
                        sendWorkers,
                        ackWorkers,
                        ackTimeout,
                        lease,
                        shutdown,
                        dbTimeout,
                        ackBatchSize,
                        ackBatchLinger),
                brokers,
                registry);
    }

    private OutboxPublisher(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            OutboxSettings settings,
            String brokers,
            MeterRegistry registry) {
        this(
                jdbc,
                manager,
                clock,
                new KafkaProducer<>(producerProperties(brokers)),
                settings,
                registry);
    }

    public OutboxPublisher(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            Producer<String, String> producer) {
        this(jdbc, manager, clock, producer, OutboxSettings.defaults(), null);
    }

    public OutboxPublisher(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            Producer<String, String> producer,
            OutboxSettings settings) {
        this(jdbc, manager, clock, producer, settings, null);
    }

    public OutboxPublisher(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            Producer<String, String> producer,
            OutboxSettings settings,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = transaction(manager, settings, "shortlink-outbox-claim");
        this.ackTx = transaction(manager, settings, "shortlink-outbox-ack");
        this.retryTx = transaction(manager, settings, "shortlink-outbox-retry");
        // Queue scans must not inherit a caller's REPEATABLE_READ next-key locks. The
        // scheduler has no outer transaction; a nested caller temporarily needs one extra
        // connection while its transaction is suspended. Only committed intents are claimed.
        this.clock = clock;
        this.producer = producer;
        this.settings = settings;
        this.capacity = new Semaphore(settings.maxInFlight());
        this.senders = executor(settings.sendWorkers(), settings.maxInFlight(), "outbox-send-");
        this.acknowledgements =
                executor(settings.ackWorkers(), settings.maxInFlight(), "outbox-ack-");
        ackFailures = counter(registry, "shortlink.outbox.ack.failures");
        brokerFailures = counter(registry, "shortlink.outbox.broker.failures");
        retryFailures = counter(registry, "shortlink.outbox.retry.failures");
        fencedAcknowledgements = counter(registry, "shortlink.outbox.fenced.acknowledgements");
        claimFailures = counter(registry, "shortlink.outbox.claim.failures");
        committedEvents = counter(registry, "shortlink.outbox.ack.committed.events");
        retriedEvents = counter(registry, "shortlink.outbox.ack.retried.events");
        claimSuccessTimer = timer(registry, "shortlink.outbox.claim.transaction", "committed");
        claimFailureTimer = timer(registry, "shortlink.outbox.claim.transaction", "failed");
        ackCommitTimer = timer(registry, "shortlink.outbox.ack.transaction", "committed");
        ackRollbackTimer = timer(registry, "shortlink.outbox.ack.transaction", "rolled_back");
        ackUnknownTimer = timer(registry, "shortlink.outbox.ack.transaction", "unknown");
        retryCommitTimer = timer(registry, "shortlink.outbox.ack.retry.transaction", "committed");
        retryFailureTimer = timer(registry, "shortlink.outbox.ack.retry.transaction", "failed");
        ackWaitTimer = registry == null ? null : registry.timer("shortlink.outbox.ack.wait");
        claimBatchSizes = summary(registry, "shortlink.outbox.claim.batch.size");
        ackBatchSizes = summary(registry, "shortlink.outbox.ack.batch.size");
        if (registry != null) {
            Gauge.builder("shortlink.outbox.inflight", inFlight, Set::size).register(registry);
            // Reserved capacity also covers an uncommitted claim, including an empty poll.
            Gauge.builder("shortlink.outbox.capacity.used", this, OutboxPublisher::inFlightCount)
                    .register(registry);
            Gauge.builder("shortlink.outbox.send.pending", senders, p -> p.getQueue().size())
                    .register(registry);
            Gauge.builder(
                            "shortlink.outbox.ack.pending",
                            acknowledgements,
                            p -> p.getQueue().size())
                    .register(registry);
            Gauge.builder("shortlink.outbox.ack.processing", ackProcessing, AtomicLong::get)
                    .register(registry);
        }
    }

    private static TransactionTemplate transaction(
            PlatformTransactionManager manager, OutboxSettings settings, String name) {
        var template = new TransactionTemplate(manager);
        template.setName(name);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(settings.databaseTimeoutSeconds());
        return template;
    }

    private static ThreadPoolExecutor executor(int workers, int bound, String prefix) {
        AtomicLong ids = new AtomicLong();
        return new ThreadPoolExecutor(
                workers,
                workers,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(bound),
                task -> {
                    Thread thread = new Thread(task, prefix + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static Counter counter(MeterRegistry registry, String name) {
        return registry == null ? null : registry.counter(name);
    }

    private static void increment(Counter counter) {
        if (counter != null) counter.increment();
    }

    private static void increment(Counter counter, long amount) {
        if (counter != null && amount > 0) counter.increment(amount);
    }

    private static Timer timer(MeterRegistry registry, String name, String outcome) {
        return registry == null ? null : registry.timer(name, "outcome", outcome);
    }

    private static DistributionSummary summary(MeterRegistry registry, String name) {
        return registry == null ? null : registry.summary(name);
    }

    private static void record(Timer timer, long started) {
        if (timer != null) timer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private static JdbcTemplate boundedJdbc(JdbcTemplate source, int timeout) {
        JdbcTemplate jdbc = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(timeout);
        return jdbc;
    }

    static Properties producerProperties(String brokers) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "250");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "4194304");
        com.jupiter.shortlink.contract.KafkaSecurity.apply(p);
        return p;
    }

    public List<Claimed> claim() {
        return claim(settings.batchSize());
    }

    private List<Claimed> claim(int limit) {
        long started = System.nanoTime();
        try {
            List<Claimed> claimed =
                    tx.execute(
                            s -> {
                                long now = clock.millis();
                                List<Claimed> rows =
                                        jdbc.query(
                                                "SELECT"
                                                    + " event_id,topic,event_key,payload,fence,attempts"
                                                    + " FROM t_outbox WHERE state<>'PUBLISHED' AND"
                                                    + " next_attempt_at<=? AND lease_until<=? ORDER"
                                                    + " BY created_at,event_id LIMIT ? FOR UPDATE"
                                                    + " SKIP LOCKED",
                                                (r, n) ->
                                                        new Claimed(
                                                                r.getString(1),
                                                                r.getString(2),
                                                                r.getString(3),
                                                                r.getString(4),
                                                                Math.addExact(r.getLong(5), 1),
                                                                Math.addExact(r.getInt(6), 1)),
                                                now,
                                                now,
                                                limit);
                                for (Claimed c : rows) {
                                    if (jdbc.update(
                                                    "UPDATE t_outbox SET"
                                                        + " state='SENDING',fence=?,attempts=?,lease_until=?"
                                                        + " WHERE event_id=?",
                                                    c.fence(),
                                                    c.attempts(),
                                                    Math.addExact(now, settings.leaseMillis()),
                                                    c.id())
                                            != 1) {
                                        throw new IllegalStateException(
                                                "Outbox claim row disappeared");
                                    }
                                }
                                return rows;
                            });
            record(claimSuccessTimer, started);
            if (claimBatchSizes != null) claimBatchSizes.record(claimed.size());
            return claimed;
        } catch (RuntimeException failure) {
            increment(claimFailures);
            record(claimFailureTimer, started);
            throw failure;
        }
    }

    @Scheduled(fixedDelayString = "${shortlink.outbox.poll-ms:25}")
    public void publishDue() {
        if (closed.get() || Thread.currentThread().isInterrupted() || !pollLock.tryLock()) return;
        int unassigned = 0;
        try {
            if (closed.get()) return;
            expireDeliveries();
            int requested = Math.min(settings.batchSize(), capacity.availablePermits());
            if (requested == 0 || !capacity.tryAcquire(requested)) return;
            unassigned = requested;
            // TransactionTemplate returns only after commit. An unknown commit sends nothing.
            List<Claimed> claims = claim(requested);
            capacity.release(requested - claims.size());
            unassigned = claims.size();
            for (Claimed claim : claims) {
                if (closed.get() || Thread.currentThread().isInterrupted()) break;
                Delivery delivery = new Delivery(claim);
                inFlight.add(delivery);
                unassigned--;
                try {
                    Send task = new Send(delivery);
                    delivery.sendTask = task;
                    senders.execute(task);
                } catch (RejectedExecutionException stopping) {
                    complete(delivery, stopping);
                }
            }
            // A claim returning after the close deadline is never sent; its lease is recoverable.
        } finally {
            capacity.release(unassigned);
            pollLock.unlock();
        }
    }

    private final class Send implements Runnable {
        private final Delivery delivery;

        Send(Delivery delivery) {
            this.delivery = delivery;
        }

        @Override
        public void run() {
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                complete(delivery, new CancellationException("Outbox publisher stopping"));
                return;
            }
            if (delivery.completed.get()) return;
            try {
                Claimed c = delivery.claim;
                // Even max.block.ms waits use a bounded sender pool, not the scheduler.
                producer.send(
                        new ProducerRecord<>(c.topic(), c.key(), c.payload()),
                        (metadata, failure) -> complete(delivery, failure));
            } catch (RuntimeException failure) {
                complete(delivery, failure);
            }
        }
    }

    private void expireDeliveries() {
        long now = System.nanoTime();
        long timeout = TimeUnit.MILLISECONDS.toNanos(settings.ackTimeoutMillis());
        for (Delivery delivery : inFlight) {
            if (now - delivery.startedNanos >= timeout) {
                complete(delivery, new TimeoutException("Outbox broker acknowledgement timeout"));
            }
        }
    }

    private void complete(Delivery delivery, Throwable failure) {
        if (!delivery.completed.compareAndSet(false, true)) return;
        if (failure != null && delivery.sendTask != null) senders.remove(delivery.sendTask);
        if (failure != null
                && !(failure instanceof CancellationException)
                && !(failure instanceof RejectedExecutionException)) increment(brokerFailures);
        try {
            // Kafka's callback only queues a bounded terminal task, never a database operation.
            acknowledgements.execute(new Acknowledgement(delivery, failure));
        } catch (RejectedExecutionException stopping) {
            abandon(delivery);
            warn();
        }
    }

    private final class Acknowledgement implements Runnable {
        private final Delivery delivery;
        private final Throwable failure;
        private final long enqueuedNanos = System.nanoTime();

        Acknowledgement(Delivery delivery, Throwable failure) {
            this.delivery = delivery;
            this.failure = failure;
        }

        @Override
        public void run() {
            List<Acknowledgement> batch = new ArrayList<>(settings.ackBatchSize());
            batch.add(this);
            ackProcessing.incrementAndGet();
            try {
                long deadline =
                        System.nanoTime()
                                + TimeUnit.MILLISECONDS.toNanos(settings.ackBatchLingerMillis());
                while (batch.size() < settings.ackBatchSize()
                        && !Thread.currentThread().isInterrupted()) {
                    Runnable next = acknowledgements.getQueue().poll();
                    if (next == null && !closed.get() && remaining(deadline) > 0) {
                        next =
                                acknowledgements
                                        .getQueue()
                                        .poll(remaining(deadline), TimeUnit.NANOSECONDS);
                    }
                    if (next == null) break;
                    // Removal transfers sole ownership from the executor queue to this batch.
                    // shutdownNow can only return tasks which have not been removed here.
                    batch.add((Acknowledgement) next);
                    ackProcessing.incrementAndGet();
                }
                if (!Thread.currentThread().isInterrupted()) {
                    batch.sort(
                            Comparator.comparing((Acknowledgement a) -> a.delivery.claim.id())
                                    .thenComparingLong(a -> a.delivery.claim.fence()));
                    for (Acknowledgement a : batch) record(ackWaitTimer, a.enqueuedNanos);
                    writeAcknowledgements(batch);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                // Only committed outcomes or an explicit handoff to durable lease recovery
                // release capacity. An in-progress transaction still owns all its slots.
                for (Acknowledgement a : batch) abandon(a.delivery);
                ackProcessing.addAndGet(-batch.size());
            }
        }
    }

    private record Applied(long published, long retried, long fenced) {}

    private void writeAcknowledgements(List<Acknowledgement> batch) {
        long started = System.nanoTime();
        if (ackBatchSizes != null) ackBatchSizes.record(batch.size());
        AtomicBoolean statementsFinished = new AtomicBoolean();
        AtomicInteger completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        try {
            Applied applied =
                    ackTx.execute(
                            status -> {
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCompletion(int result) {
                                                completion.set(result);
                                            }
                                        });
                                long published = 0, retried = 0, fenced = 0;
                                for (Acknowledgement a : batch) {
                                    if (Thread.currentThread().isInterrupted()) {
                                        throw new CancellationException(
                                                "Outbox acknowledgement interrupted");
                                    }
                                    if (a.failure == null) {
                                        if (ack(a.delivery.claim)) published++;
                                        else fenced++;
                                    } else if (retryUpdate(a.delivery.claim)) {
                                        retried++;
                                    }
                                }
                                statementsFinished.set(true);
                                return new Applied(published, retried, fenced);
                            });
            // Counts reflect committed row outcomes, never statement results from a transaction
            // which may subsequently roll back or lose its commit reply.
            increment(committedEvents, applied.published());
            increment(retriedEvents, applied.retried());
            increment(fencedAcknowledgements, applied.fenced());
            record(ackCommitTimer, started);
        } catch (RuntimeException databaseFailure) {
            long successes = batch.stream().filter(a -> a.failure == null).count();
            increment(ackFailures, successes);
            increment(retryFailures, batch.size() - successes);
            boolean rolledBack =
                    !statementsFinished.get()
                            && completion.get() == TransactionSynchronization.STATUS_ROLLED_BACK;
            record(rolledBack ? ackRollbackTimer : ackUnknownTimer, started);
            if (rolledBack && !Thread.currentThread().isInterrupted()) {
                retryRolledBackBatch(batch);
            } else {
                // A lost commit/rollback reply must not cause a second write based on a guess.
                // SENDING rows recover after their durable lease; PUBLISHED rows stay terminal.
                warn();
            }
        }
    }

    private void retryRolledBackBatch(List<Acknowledgement> batch) {
        long started = System.nanoTime();
        try {
            Long retried =
                    retryTx.execute(
                            status -> {
                                long changed = 0;
                                for (Acknowledgement a : batch) {
                                    if (Thread.currentThread().isInterrupted()) {
                                        throw new CancellationException(
                                                "Outbox retry accounting interrupted");
                                    }
                                    if (retryUpdate(a.delivery.claim)) changed++;
                                }
                                return changed;
                            });
            increment(retriedEvents, retried);
            record(retryCommitTimer, started);
        } catch (RuntimeException retryFailure) {
            increment(retryFailures, batch.size());
            record(retryFailureTimer, started);
            warn(); // One bounded recovery transaction only; never a per-event retry loop.
        }
    }

    private void abandon(Delivery delivery) {
        if (inFlight.remove(delivery)) capacity.release();
    }

    private void warn() {
        long now = System.nanoTime(), previous = lastWarning.get();
        if ((previous == 0 || now - previous >= TimeUnit.SECONDS.toNanos(10))
                && lastWarning.compareAndSet(previous, now)) {
            LOG.warn("Outbox acknowledgement deferred to lease recovery");
        }
    }

    public int inFlightCount() {
        return settings.maxInFlight() - capacity.availablePermits();
    }

    public boolean ack(Claimed c) {
        return jdbc.update(
                        "UPDATE t_outbox SET state='PUBLISHED',published_at=?,lease_until=0"
                                + " WHERE event_id=? AND fence=? AND state='SENDING'",
                        clock.millis(),
                        c.id(),
                        c.fence())
                == 1;
    }

    public void retry(Claimed c) {
        retryUpdate(c);
    }

    private boolean retryUpdate(Claimed c) {
        long delay = Math.min(60000, 250L << Math.min(8, c.attempts()));
        return jdbc.update(
                        "UPDATE t_outbox SET state='READY',next_attempt_at=?,lease_until=0"
                                + " WHERE event_id=? AND fence=? AND state='SENDING'",
                        clock.millis() + delay,
                        c.id(),
                        c.fence())
                == 1;
    }

    @Scheduled(fixedDelay = 3600000)
    public void retain() {
        if (!closed.get())
            jdbc.update(
                    "DELETE FROM t_outbox WHERE state='PUBLISHED' AND published_at<? LIMIT 1000",
                    clock.millis() - Duration.ofDays(7).toMillis());
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long deadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.shutdownMillis());
        boolean interrupted = Thread.currentThread().isInterrupted();
        try {
            // Barrier with the claim transaction. If its bounded SQL times out later, closed
            // still prevents submission; committed but unsent claims remain recoverable.
            if (pollLock.tryLock(remaining(deadline), TimeUnit.NANOSECONDS)) pollLock.unlock();
            senders.shutdown();
            if (!senders.awaitTermination(
                    Math.min(remaining(deadline), TimeUnit.SECONDS.toNanos(1)),
                    TimeUnit.NANOSECONDS)) {
                for (Runnable queued : senders.shutdownNow()) {
                    complete(
                            ((Send) queued).delivery, new CancellationException("Outbox stopping"));
                }
            }
        } catch (InterruptedException error) {
            interrupted = true;
        } finally {
            for (Runnable queued : senders.shutdownNow()) {
                complete(((Send) queued).delivery, new CancellationException("Outbox stopping"));
            }
            try {
                producer.close(
                        Duration.ofNanos(
                                interrupted
                                        ? 0
                                        : Math.min(
                                                remaining(deadline), TimeUnit.SECONDS.toNanos(2))));
            } catch (RuntimeException closeFailure) {
                warn();
            }
            for (Delivery delivery : inFlight) {
                complete(delivery, new CancellationException("Outbox publisher stopping"));
            }
            acknowledgements.shutdown();
            try {
                if (!acknowledgements.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                    for (Runnable queued : acknowledgements.shutdownNow()) {
                        abandon(((Acknowledgement) queued).delivery);
                    }
                }
            } catch (InterruptedException error) {
                interrupted = true;
                for (Runnable queued : acknowledgements.shutdownNow()) {
                    abandon(((Acknowledgement) queued).delivery);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
