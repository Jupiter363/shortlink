package com.jupiter.shortlink.command.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.command.security.CommandPrincipal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class MetadataJobService {
    public static final String TOPIC = "shortlink.metadata.fetch.v1";
    public static final int MAX_INTAKE_RECORDS = 16;
    public static final int MAX_EVENT_BYTES = 65536;
    private static final String DB_MILLIS =
            "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)";

    public record IntakeRecord(String payload, String topic, int partition, long offset) {}

    /** Counts input records in a confirmed commit, including idempotent replays. */
    public record IntakeResult(int accepted, int rejected) {}

    public record Event(
            String eventId,
            int schemaVersion,
            String tenantId,
            long linkId,
            long targetRevision,
            String urlDigest,
            String originUrl) {}

    public record Lease(
            String jobId,
            long tenantId,
            long linkId,
            long targetRevision,
            String digest,
            String url,
            long fence,
            String owner,
            int attempts) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate intakeTx;
    private final ObjectMapper json;
    private final LinkCommandService links;
    private final GroupCommandService groups;
    private final BusinessOutbox outbox;
    private final Clock clock;
    private final Map<String, Timer> executionTimers;

    public MetadataJobService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            LinkCommandService links,
            GroupCommandService groups,
            BusinessOutbox outbox,
            Clock clock) {
        this(jdbc, manager, json, links, groups, outbox, clock, new SimpleMeterRegistry());
    }

    @Autowired
    public MetadataJobService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            LinkCommandService links,
            GroupCommandService groups,
            BusinessOutbox outbox,
            Clock clock,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
        // Worker operations are independently durable. Returning from claim/apply/retry is
        // confirmation of this commit, even when a caller has an unrelated outer transaction.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Identity lookup precedes the account lock; a later route hint must see any move
        // committed while waiting for that lock. Resource and job writes still use row locks.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.intakeTx = new TransactionTemplate(manager);
        this.intakeTx.setTimeout(5);
        this.intakeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.links = links;
        this.groups = groups;
        this.outbox = outbox;
        this.clock = clock;
        Map<String, Timer> timers = new HashMap<>();
        for (String operation : List.of("candidates", "claim", "apply", "retry"))
            for (String outcome : List.of("success", "empty", "obsolete", "stale", "failed"))
                timers.put(
                        operation + "/" + outcome,
                        registry.timer(
                                "shortlink.metadata.execution.duration",
                                "operation",
                                operation,
                                "outcome",
                                outcome));
        this.executionTimers = Map.copyOf(timers);
    }

    /** Return only after durable inbox commit. Kafka offset commits happen afterwards. */
    public void accept(String payload, String sourceTopic, int partition, long offset) {
        acceptBatch(List.of(new IntakeRecord(payload, sourceTopic, partition, offset)));
    }

    /** One bounded Kafka poll is atomic across valid jobs and poison-message DLQ intents. */
    public IntakeResult acceptBatch(List<IntakeRecord> records) {
        if (records.isEmpty()) return new IntakeResult(0, 0);
        if (records.size() > MAX_INTAKE_RECORDS)
            throw new IllegalArgumentException("Metadata intake batch exceeds budget");
        List<Object[]> rows = new ArrayList<>(records.size());
        Map<String, BusinessOutbox.Entry> rejects = new LinkedHashMap<>();
        int rejected = 0;
        long now = clock.millis();
        for (IntakeRecord record : records) {
            Objects.requireNonNull(record.topic(), "Source topic required");
            if (record.partition() < 0 || record.offset() < 0)
                throw new IllegalArgumentException("Invalid source receipt");
            Event event;
            try {
                event = validate(record.payload());
            } catch (Exception e) {
                rejected++;
                BusinessOutbox.Entry entry = rejection(record, e.getClass().getSimpleName());
                rejects.putIfAbsent(entry.eventId(), entry);
                continue;
            }
            rows.add(
                    new Object[] {
                        UUID.randomUUID().toString(),
                        Long.parseLong(event.tenantId()),
                        event.linkId(),
                        event.targetRevision(),
                        event.urlDigest(),
                        event.originUrl(),
                        now,
                        now,
                        now
                    });
        }
        // At most 16 * 64 KiB is parsed. An oversized record is represented only by its
        // fixed receipt summary, so it cannot wedge the partition or grow a retry buffer.
        intakeTx.executeWithoutResult(
                s -> {
                    if (!rows.isEmpty())
                        jdbc.batchUpdate(
                                "INSERT IGNORE INTO"
                                    + " t_metadata_job(job_id,tenant_id,link_id,target_revision,url_digest,origin_url,created_at,updated_at,next_attempt_at)"
                                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                                rows);
                    if (!rejects.isEmpty()) {
                        String placeholders =
                                String.join(",", Collections.nCopies(rejects.size(), "?"));
                        Set<String> existing =
                                new HashSet<>(
                                        jdbc.queryForList(
                                                "SELECT event_id FROM t_outbox WHERE event_id IN ("
                                                        + placeholders
                                                        + ")",
                                                String.class,
                                                rejects.keySet().toArray()));
                        outbox.appendMany(
                                rejects.values().stream()
                                        .filter(entry -> !existing.contains(entry.eventId()))
                                        .toList());
                    }
                });
        // Includes transaction-manager commit failures: no result escapes an unknown commit.
        return new IntakeResult(rows.size(), rejected);
    }

    private Event validate(String payload) throws Exception {
        if (payload == null
                || payload.length() > MAX_EVENT_BYTES
                || payload.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES)
            throw new IllegalArgumentException("EVENT_BYTE_LIMIT");
        Event event = json.readValue(payload, Event.class);
        long tenant = Long.parseLong(event.tenantId());
        if (event.eventId() == null
                || event.eventId().length() > 64
                || event.schemaVersion() != 1
                || tenant < 1
                || event.linkId() < 1
                || event.targetRevision() < 1
                || event.originUrl() == null
                || event.originUrl().length() > 2048
                || !links.digest(event.originUrl()).equals(event.urlDigest()))
            throw new IllegalArgumentException("EVENT_CONTRACT");
        // Persist valid intents even when the fetch policy rejects the target. The
        // worker records FAILED for the matching revision instead of leaving PENDING.
        return event;
    }

    private BusinessOutbox.Entry rejection(IntakeRecord record, String reason) {
        String topic = record.topic();
        int partition = record.partition();
        long offset = record.offset();
        String event =
                UUID.nameUUIDFromBytes(
                                (topic + ":" + partition + ":" + offset)
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        return new BusinessOutbox.Entry(
                event,
                "shortlink.metadata.dlq.v1",
                topic + ":" + partition,
                Map.of(
                        "eventId",
                        event,
                        "schemaVersion",
                        1,
                        "sourceTopic",
                        topic,
                        "sourcePartition",
                        partition,
                        "sourceOffset",
                        offset,
                        "reason",
                        reason));
    }

    public List<String> candidates(int count) {
        if (count < 1 || count > 32) throw new IllegalArgumentException("Invalid worker budget");
        return observed(
                "candidates",
                () ->
                        jdbc.queryForList(
                                "SELECT job_id FROM t_metadata_job WHERE state IN"
                                    + " ('READY','RUNNING') AND next_attempt_at<=? AND"
                                    + " lease_until<=? ORDER BY next_attempt_at,job_id LIMIT ?",
                                String.class,
                                clock.millis(),
                                clock.millis(),
                                count),
                rows -> rows.isEmpty() ? "empty" : "success");
    }

    public Lease claim(String id, String owner) {
        return observed(
                "claim",
                () ->
                        tx.execute(
                                s -> {
                                    Map<String, Object> row =
                                            jdbc.queryForMap(
                                                    "SELECT * FROM t_metadata_job WHERE job_id=?"
                                                        + " FOR UPDATE",
                                                    id);
                                    long fence = num(row, "fence") + 1;
                                    int attempts = (int) Math.min(9, num(row, "attempts") + 1);
                                    // Evaluate the clock only in this second statement, AFTER
                                    // acquiring the
                                    // row lock. A wait for that lock must not leave us using a
                                    // pre-wait time.
                                    int claimed =
                                            jdbc.update(
                                                    "UPDATE t_metadata_job SET"
                                                        + " state='RUNNING',fence=?,lease_owner=?,lease_until="
                                                            + DB_MILLIS
                                                            + "+15000,attempts=?,updated_at="
                                                            + DB_MILLIS
                                                            + " WHERE job_id=? AND state IN"
                                                            + " ('READY','RUNNING') AND"
                                                            + " lease_until<="
                                                            + DB_MILLIS
                                                            + " AND next_attempt_at<="
                                                            + DB_MILLIS,
                                                    fence,
                                                    owner,
                                                    attempts,
                                                    id);
                                    if (claimed == 0) return null;
                                    if (claimed != 1)
                                        throw new IllegalStateException(
                                                "Invalid metadata claim count");
                                    return new Lease(
                                            id,
                                            num(row, "tenant_id"),
                                            num(row, "link_id"),
                                            num(row, "target_revision"),
                                            str(row, "url_digest"),
                                            str(row, "origin_url"),
                                            fence,
                                            owner,
                                            attempts);
                                }),
                lease -> lease == null ? "empty" : "success");
    }

    public void complete(Lease lease, SafeMetadataFetcher.Metadata metadata) {
        apply(lease, Objects.requireNonNull(metadata), null);
    }

    private void apply(Lease lease, SafeMetadataFetcher.Metadata metadata, String failure) {
        observed(
                "apply",
                () ->
                        tx.execute(
                                s -> {
                                    // Resolve current account/group/route after the network
                                    // operation. A -> B -> A
                                    // still has a different targetRevision.
                                    var accounts =
                                            jdbc.queryForList(
                                                    "SELECT username FROM t_account_identity WHERE"
                                                        + " id=?",
                                                    lease.tenantId());
                                    if (accounts.size() != 1) {
                                        terminalOnly(lease, "OBSOLETE", "ACCOUNT_UNAVAILABLE");
                                        return "obsolete";
                                    }
                                    String username = str(accounts.get(0), "username");
                                    var account =
                                            jdbc.queryForMap(
                                                    "SELECT auth_version,disabled,del_flag FROM"
                                                        + " t_user WHERE username=? AND id=? FOR"
                                                        + " UPDATE",
                                                    username,
                                                    lease.tenantId());
                                    CommandPrincipal p =
                                            new CommandPrincipal(
                                                    lease.tenantId(),
                                                    username,
                                                    num(account, "auth_version"));
                                    var routes =
                                            jdbc.queryForList(
                                                    "SELECT"
                                                        + " current_gid,route_status,target_revision,origin_url"
                                                        + " FROM t_link_route WHERE link_id=? AND"
                                                        + " tenant_id=?",
                                                    lease.linkId(),
                                                    lease.tenantId());
                                    if (routes.isEmpty()
                                            || num(account, "disabled") != 0
                                            || num(account, "del_flag") != 0) {
                                        terminalOnly(lease, "OBSOLETE", "RESOURCE_UNAVAILABLE");
                                        return "obsolete";
                                    }
                                    Map<String, Object> before = routes.get(0);
                                    if (!str(before, "route_status").equals("ACTIVE")) {
                                        terminalOnly(lease, "OBSOLETE", "RESOURCE_INACTIVE");
                                        return "obsolete";
                                    }
                                    groups.lockActive(p, str(before, "current_gid"));
                                    var route =
                                            jdbc.queryForMap(
                                                    "SELECT"
                                                        + " current_gid,route_status,target_revision,origin_url"
                                                        + " FROM t_link_route WHERE link_id=? AND"
                                                        + " tenant_id=? FOR UPDATE",
                                                    lease.linkId(),
                                                    lease.tenantId());
                                    lockFence(lease);
                                    boolean current =
                                            num(route, "target_revision") == lease.targetRevision()
                                                    && links.digest(str(route, "origin_url"))
                                                            .equals(lease.digest())
                                                    && str(route, "route_status").equals("ACTIVE");
                                    if (!current) {
                                        finish(lease, "OBSOLETE", "TARGET_CHANGED");
                                        return "obsolete";
                                    }
                                    int updated =
                                            metadata == null
                                                    ? jdbc.update(
                                                            "UPDATE t_link SET"
                                                                + " metadata_status='FAILED',update_time=CURRENT_TIMESTAMP"
                                                                + " WHERE gid=? AND id=? AND"
                                                                + " tenant_id=? AND"
                                                                + " target_revision=? AND"
                                                                + " del_flag=0",
                                                            str(route, "current_gid"),
                                                            lease.linkId(),
                                                            lease.tenantId(),
                                                            lease.targetRevision())
                                                    : jdbc.update(
                                                            "UPDATE t_link SET"
                                                                + " title=?,favicon=?,metadata_status='READY',update_time=CURRENT_TIMESTAMP"
                                                                + " WHERE gid=? AND id=? AND"
                                                                + " tenant_id=? AND"
                                                                + " target_revision=? AND"
                                                                + " del_flag=0",
                                                            metadata.title(),
                                                            metadata.favicon(),
                                                            str(route, "current_gid"),
                                                            lease.linkId(),
                                                            lease.tenantId(),
                                                            lease.targetRevision());
                                    if (updated != 1)
                                        throw new IllegalStateException(
                                                "Current metadata detail missing");
                                    jdbc.update(
                                            "UPDATE t_link_route SET metadata_status=? WHERE"
                                                + " link_id=? AND tenant_id=? AND"
                                                + " target_revision=?",
                                            metadata == null ? "FAILED" : "READY",
                                            lease.linkId(),
                                            lease.tenantId(),
                                            lease.targetRevision());
                                    finish(
                                            lease,
                                            metadata == null ? "FAILED" : "COMPLETED",
                                            failure);
                                    return "success";
                                }),
                Function.identity());
    }

    public void failed(Lease lease, Exception error) {
        if (lease.attempts() >= 8 || "METADATA_TARGET_DENIED".equals(error.getMessage())) {
            try {
                apply(lease, null, error.getClass().getSimpleName());
            } catch (StaleLeaseException ignored) {
            }
            return;
        }
        try {
            observed(
                    "retry",
                    () ->
                            tx.execute(
                                    s -> {
                                        lockFence(lease);
                                        int updated =
                                                jdbc.update(
                                                        "UPDATE t_metadata_job SET"
                                                            + " state='READY',lease_until=0,lease_owner=NULL,next_attempt_at="
                                                                + DB_MILLIS
                                                                + "+?,last_error=?,updated_at="
                                                                + DB_MILLIS
                                                                + " WHERE job_id=? AND"
                                                                + " state='RUNNING' AND fence=? AND"
                                                                + " lease_owner=? AND lease_until>"
                                                                + DB_MILLIS,
                                                        Math.min(
                                                                60000L,
                                                                1000L
                                                                        << Math.min(
                                                                                6,
                                                                                lease.attempts())),
                                                        error.getClass().getSimpleName(),
                                                        lease.jobId(),
                                                        lease.fence(),
                                                        lease.owner());
                                        if (updated != 1) throw new StaleLeaseException();
                                        return "success";
                                    }),
                    Function.identity());
        } catch (StaleLeaseException ignored) {
        }
    }

    private void terminalOnly(Lease lease, String state, String error) {
        lockFence(lease);
        finish(lease, state, error);
    }

    private void lockFence(Lease lease) {
        var r =
                jdbc.queryForMap(
                        "SELECT state,fence,lease_owner,lease_until FROM t_metadata_job WHERE"
                                + " job_id=? FOR UPDATE",
                        lease.jobId());
        if (!str(r, "state").equals("RUNNING")
                || num(r, "fence") != lease.fence()
                || !lease.owner().equals(str(r, "lease_owner"))
                || num(r, "lease_until") <= dbNow()) throw new StaleLeaseException();
    }

    private void finish(Lease lease, String state, String error) {
        // This transaction already owns the job row lock. Re-check the current database
        // clock in the final mutation; an expired lease rolls back ALL resource writes.
        int updated =
                jdbc.update(
                        "UPDATE t_metadata_job SET"
                                + " state=?,lease_until=0,lease_owner=NULL,last_error=?,updated_at="
                                + DB_MILLIS
                                + " WHERE job_id=? AND state='RUNNING' AND fence=? AND"
                                + " lease_owner=? AND lease_until>"
                                + DB_MILLIS,
                        state,
                        error,
                        lease.jobId(),
                        lease.fence(),
                        lease.owner());
        if (updated != 1) throw new StaleLeaseException();
    }

    private long dbNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT " + DB_MILLIS, Long.class));
    }

    private <T> T observed(String operation, Supplier<T> work, Function<T, String> resultOutcome) {
        long started = System.nanoTime();
        String outcome = "failed";
        try {
            T result = work.get();
            outcome = resultOutcome.apply(result);
            return result;
        } catch (StaleLeaseException e) {
            outcome = "stale";
            throw e;
        } finally {
            executionTimers
                    .get(operation + "/" + outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private static String str(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static long num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean b ? (b ? 1 : 0) : ((Number) value).longValue();
    }

    public static final class StaleLeaseException extends RuntimeException {}
}
