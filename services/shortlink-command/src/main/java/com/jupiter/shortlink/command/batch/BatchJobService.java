package com.jupiter.shortlink.command.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService.Created;
import com.jupiter.shortlink.command.link.LinkCommandService.Creation;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.command.security.CommandAuthorization;
import com.jupiter.shortlink.command.security.CommandPrincipal;
import com.jupiter.shortlink.id.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Durable job coordinator. Every state/lease/identity/quota change is a fenced local MySQL
 * transaction.
 */
@Service
public class BatchJobService {
    private static final Set<String> TERMINAL =
            Set.of("SUCCEEDED", "PARTIAL_SUCCESS", "FAILED", "CANCELLED");
    private static final int MAX_INLINE_BYTES = 8 * 1024 * 1024;
    private static final long JOB_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommandAuthorization auth;
    private final GroupCommandService groups;
    private final LinkCommandService links;
    private final TenantQuotaService quota;
    private final IdGenerator ids;
    private final BusinessOutbox outbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final BatchLimits limits;
    private final ImmutableImportStore objects;
    private final ImportParser parser;

    public BatchJobService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            CommandAuthorization auth,
            GroupCommandService groups,
            LinkCommandService links,
            TenantQuotaService quota,
            IdGenerator ids,
            BusinessOutbox outbox,
            ObjectMapper json,
            Clock clock,
            BatchLimits limits,
            ImmutableImportStore objects) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
        this.auth = auth;
        this.groups = groups;
        this.links = links;
        this.quota = quota;
        this.ids = ids;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.limits = limits;
        this.objects = objects;
        this.parser = new ImportParser(json);
    }

    public record ImportRequest(
            String requestId, String gid, ImmutableImportStore.Reference object) {}

    public record Status(
            String jobId,
            String state,
            long totalRows,
            long validRows,
            long invalidRows,
            long succeededRows,
            long failedRows,
            String error,
            String checksum,
            Long actualBytes) {}

    public record RowResult(long row, String state, Long linkId, Created result, String error) {}

    public record Candidate(String jobId, long tenantId) {}

    public record Lease(
            String jobId,
            CommandPrincipal principal,
            String gid,
            long fence,
            String owner,
            String phase,
            String inline,
            ImmutableImportStore.Reference object,
            long declaredBytes,
            String declaredChecksum) {}

    public Status submitInline(CommandPrincipal p, String requestId, List<Creation> creations) {
        requireRequestId(requestId);
        auth.check(p, false);
        if (creations == null || creations.size() < 501 || creations.size() > 50000)
            throw new IllegalArgumentException("Asynchronous inline rows must be 501..50000");
        String gid = creations.get(0) == null ? null : creations.get(0).gid();
        requireGid(gid);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Creation creation : creations) {
            if (creation == null || !gid.equals(creation.gid()))
                throw new IllegalArgumentException("One immutable group per job is required");
            byte[] row = serialize(creation).getBytes(StandardCharsets.UTF_8);
            if (bytes.size() + row.length + 1 > Math.min(MAX_INLINE_BYTES, limits.maxInputBytes()))
                throw new IllegalArgumentException(
                        "Inline byte budget exceeded; use a versioned import object");
            bytes.writeBytes(row);
            bytes.write('\n');
        }
        byte[] input = bytes.toByteArray();
        String checksum = ImportParser.sha256(input);
        return admit(
                p,
                requestId,
                gid,
                "INLINE",
                new String(input, StandardCharsets.UTF_8),
                null,
                input.length,
                checksum,
                links.digest(List.of("INLINE", gid, checksum, input.length, ImportParser.VERSION)));
    }

    public Status submitImport(CommandPrincipal p, ImportRequest request) {
        if (request == null) throw new IllegalArgumentException("Import required");
        requireRequestId(request.requestId());
        requireGid(request.gid());
        auth.check(p, false);
        String digest =
                links.digest(
                        List.of("OBJECT", request.gid(), request.object(), ImportParser.VERSION));
        Status previous = previous(p, request.requestId(), digest);
        if (previous != null) return previous;
        objects.verifyReference(p.tenantId(), request.object());
        return admit(
                p,
                request.requestId(),
                request.gid(),
                "OBJECT",
                null,
                request.object(),
                request.object().bytes(),
                request.object().sha256(),
                digest);
    }

    private Status admit(
            CommandPrincipal p,
            String requestId,
            String gid,
            String kind,
            String inline,
            ImmutableImportStore.Reference object,
            long bytes,
            String checksum,
            String digest) {
        Status previous = previous(p, requestId, digest);
        if (previous != null) return previous;
        return tx.execute(
                s -> {
                    auth.check(p, true);
                    quota.lock(p.tenantId());
                    Status committed = previous(p, requestId, digest);
                    if (committed != null) return committed;
                    quota.admitValidation(p.tenantId());
                    groups.lockActive(p, gid);
                    groups.adjustReferences(p, gid, 0, 1);
                    String job = UUID.randomUUID().toString();
                    long now = clock.millis();
                    jdbc.update(
                            "INSERT INTO"
                                + " t_batch_job(job_id,tenant_id,username,auth_version,request_id,request_digest,gid,state,input_kind,input_json,object_bucket,object_key,object_version,declared_sha256,declared_bytes,created_at,updated_at,next_attempt_at)"
                                + " VALUES (?,?,?,?,?,?,?,'VALIDATING',?,?,?,?,?,?,?,?,?,?)",
                            job,
                            p.tenantId(),
                            p.username(),
                            p.authVersion(),
                            requestId,
                            digest,
                            gid,
                            kind,
                            inline,
                            object == null ? null : object.bucket(),
                            object == null ? null : object.key(),
                            object == null ? null : object.version(),
                            checksum,
                            bytes,
                            now,
                            now,
                            now);
                    schedule(job, p.tenantId(), "VALIDATING");
                    return getInternal(p.tenantId(), job);
                });
    }

    private Status previous(CommandPrincipal p, String requestId, String digest) {
        var rows =
                jdbc.queryForList(
                        "SELECT job_id,request_digest FROM t_batch_job WHERE tenant_id=? AND"
                                + " request_id=?",
                        p.tenantId(),
                        requestId);
        if (rows.isEmpty()) return null;
        if (!digest.equals(rows.get(0).get("request_digest")))
            throw conflict("Request ID belongs to different immutable input");
        return getInternal(p.tenantId(), (String) rows.get(0).get("job_id"));
    }

    public Status get(CommandPrincipal p, String job) {
        auth.check(p, false);
        return getInternal(p.tenantId(), job);
    }

    private Status getInternal(long tenant, String job) {
        Map<String, Object> r = job(tenant, job, false);
        return status(r);
    }

    public List<RowResult> results(CommandPrincipal p, String id, long after, int limit) {
        auth.check(p, false);
        if (after < 0 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("Invalid result cursor");
        Status status = getInternal(p.tenantId(), id);
        return jdbc.query(
                "SELECT row_no,state,link_id,result_json,error_code FROM t_batch_row WHERE job_id=?"
                        + " AND row_no>? ORDER BY row_no LIMIT ?",
                (r, n) -> {
                    String state = r.getString("state"), error = r.getString("error_code");
                    if ((state.equals("VALIDATED") || state.equals("RESERVED"))
                            && TERMINAL.contains(status.state())) {
                        state = status.state().equals("CANCELLED") ? "CANCELLED" : "FAILED";
                        error = "JOB_" + status.state();
                    }
                    Long linkId = (Long) r.getObject("link_id");
                    String result = r.getString("result_json");
                    return new RowResult(
                            r.getLong("row_no"),
                            state,
                            linkId,
                            result == null ? null : read(result, Created.class),
                            error);
                },
                id,
                after,
                limit);
    }

    public Status cancel(CommandPrincipal p, String id) {
        return tx.execute(
                s -> {
                    auth.check(p, true);
                    quota.lock(p.tenantId());
                    Map<String, Object> row = job(p.tenantId(), id, true);
                    if (!TERMINAL.contains(str(row, "state"))) {
                        groups.lockActive(p, str(row, "gid"));
                        finish(row, p, "CANCELLED", "USER_CANCELLED");
                    }
                    return getInternal(p.tenantId(), id);
                });
    }

    /** A bounded candidate per tenant prevents a large tenant from occupying every local worker. */
    public List<Candidate> candidates(int count) {
        if (count < 1 || count > 128) throw new IllegalArgumentException("Invalid poll budget");
        return jdbc.query(
                "SELECT MIN(job_id) AS job_id,tenant_id FROM t_batch_job WHERE state IN"
                    + " ('VALIDATING','READY','RUNNING') AND next_attempt_at<=? AND lease_until<=?"
                    + " GROUP BY tenant_id ORDER BY MIN(next_attempt_at),tenant_id LIMIT ?",
                (r, n) -> new Candidate(r.getString("job_id"), r.getLong("tenant_id")),
                clock.millis(),
                clock.millis(),
                count);
    }

    public Candidate hintCandidate(String job, long tenant) {
        if (job == null || job.length() != 36 || tenant < 1) return null;
        var rows =
                jdbc.query(
                        "SELECT job_id,tenant_id FROM t_batch_job WHERE job_id=? AND tenant_id=?"
                            + " AND state IN ('VALIDATING','READY','RUNNING') AND lease_until<=?"
                            + " AND next_attempt_at<=?",
                        (r, n) -> new Candidate(r.getString("job_id"), r.getLong("tenant_id")),
                        job,
                        tenant,
                        clock.millis(),
                        clock.millis());
        return rows.size() == 1 ? rows.get(0) : null;
    }

    public Lease claim(String id, String owner) {
        var candidates =
                jdbc.queryForList("SELECT tenant_id,username FROM t_batch_job WHERE job_id=?", id);
        if (candidates.isEmpty()) return null;
        long tenant = num(candidates.get(0), "tenant_id");
        String username = str(candidates.get(0), "username");
        return tx.execute(
                s -> {
                    CommandPrincipal p = lockWorkerAccount(tenant, username);
                    quota.lock(tenant);
                    Map<String, Object> r = job(tenant, id, true);
                    long now = dbNow();
                    if (TERMINAL.contains(str(r, "state"))
                            || num(r, "lease_until") > now
                            || num(r, "next_attempt_at") > now) return null;
                    groups.lockActive(p, str(r, "gid"));
                    if (p.authVersion() == Long.MAX_VALUE) {
                        finish(r, p, "FAILED", "ACCOUNT_UNAVAILABLE");
                        return null;
                    }
                    if (now - num(r, "created_at") >= JOB_MAX_AGE_MILLIS) {
                        finish(r, p, "FAILED", "JOB_EXPIRED");
                        return null;
                    }
                    if (num(r, "attempts") >= limits.maxAttempts()) {
                        finish(r, p, "FAILED", "RETRY_EXHAUSTED");
                        return null;
                    }
                    long fence = num(r, "fence") + 1;
                    String phase = str(r, "state").equals("VALIDATING") ? "VALIDATING" : "RUNNING";
                    jdbc.update(
                            "UPDATE t_batch_job SET"
                                + " state=?,fence=?,lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?"
                                + " WHERE job_id=?",
                            phase,
                            fence,
                            owner,
                            now + limits.leaseMillis(),
                            now,
                            id);
                    ImmutableImportStore.Reference ref =
                            str(r, "input_kind").equals("OBJECT")
                                    ? new ImmutableImportStore.Reference(
                                            str(r, "object_bucket"),
                                            str(r, "object_key"),
                                            str(r, "object_version"),
                                            str(r, "declared_sha256"),
                                            num(r, "declared_bytes"))
                                    : null;
                    String inline =
                            phase.equals("VALIDATING") && ref == null
                                    ? jdbc.queryForObject(
                                            "SELECT input_json FROM t_batch_job WHERE job_id=?",
                                            String.class,
                                            id)
                                    : null;
                    return new Lease(
                            id,
                            p,
                            str(r, "gid"),
                            fence,
                            owner,
                            phase,
                            inline,
                            ref,
                            num(r, "declared_bytes"),
                            str(r, "declared_sha256"));
                });
    }

    /**
     * One invocation validates a fixed object or commits a single bounded business chunk, then
     * yields its tenant slot.
     */
    public void run(Lease lease) throws IOException {
        try {
            runAsync(lease).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException failure) throw failure;
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw e;
        }
    }

    /** Production workers release their thread while the membership barrier drains. */
    public CompletableFuture<Void> runAsync(Lease lease) throws IOException {
        if (lease.phase().equals("VALIDATING")) {
            validate(lease);
            return CompletableFuture.completedFuture(null);
        }
        return executeChunk(lease);
    }

    private void validate(Lease lease) throws IOException {
        List<ImportParser.Row> buffer = new ArrayList<>(limits.chunkRows());
        ImportParser.Manifest manifest;
        try (InputStream input =
                lease.object() == null
                        ? new ByteArrayInputStream(lease.inline().getBytes(StandardCharsets.UTF_8))
                        : objects.open(lease.object())) {
            manifest =
                    parser.parse(
                            input,
                            limits.maxInputBytes(),
                            lease.object() == null ? 50000 : limits.maxRows(),
                            c -> {
                                links.validateCreation(c);
                                if (!lease.gid().equals(c.gid()))
                                    throw new IllegalArgumentException("Import group mismatch");
                            },
                            row -> {
                                buffer.add(row);
                                if (buffer.size() == limits.chunkRows()) {
                                    stage(lease, buffer);
                                    buffer.clear();
                                }
                            });
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("IMPORT_"))
                throw new InvalidImportException(e.getMessage());
            throw e;
        }
        if (!buffer.isEmpty()) stage(lease, buffer);
        if (manifest.bytes() != lease.declaredBytes()
                || !manifest.sha256().equals(lease.declaredChecksum()))
            throw new InvalidImportException("IMPORT_MANIFEST_MISMATCH");
        if (manifest.rows() < (lease.object() == null ? 501 : 50001))
            throw new InvalidImportException("IMPORT_ROW_RANGE");
        tx.executeWithoutResult(
                s -> {
                    Map<String, Object> r = fenced(lease);
                    quota.ready(lease.principal().tenantId(), manifest.validRows());
                    long now = dbNow();
                    assertLease(lease);
                    jdbc.update(
                            "UPDATE t_batch_job SET"
                                + " actual_sha256=?,actual_bytes=?,total_rows=?,valid_rows=?,invalid_rows=?,reserved_rows=?,validation_held=FALSE,state='READY',lease_until=0,lease_owner=NULL,attempts=0,updated_at=?,next_attempt_at=?"
                                + " WHERE job_id=?",
                            manifest.sha256(),
                            manifest.bytes(),
                            manifest.rows(),
                            manifest.validRows(),
                            manifest.rows() - manifest.validRows(),
                            manifest.validRows(),
                            now,
                            now,
                            lease.jobId());
                    schedule(lease.jobId(), lease.principal().tenantId(), "READY");
                    if (manifest.validRows() == 0)
                        finish(
                                job(lease.principal().tenantId(), lease.jobId(), true),
                                lease.principal(),
                                "COMPLETED",
                                null);
                });
    }

    private void stage(Lease lease, List<ImportParser.Row> rows) {
        tx.executeWithoutResult(
                s -> {
                    fenced(lease);
                    List<Object[]> values = new ArrayList<>(rows.size());
                    for (ImportParser.Row row : rows)
                        values.add(
                                new Object[] {
                                    lease.jobId(),
                                    row.number(),
                                    row.digest(),
                                    row.creation() == null ? null : serialize(row.creation()),
                                    row.creation() == null ? "INVALID" : "VALIDATED",
                                    row.error()
                                });
                    // Retry reads the SAME immutable input from byte zero. A staged row is
                    // immutable and never carries a new identity.
                    jdbc.batchUpdate(
                            "INSERT INTO"
                                + " t_batch_row(job_id,row_no,row_digest,creation_json,state,error_code)"
                                + " VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                                + " creation_json=VALUES(creation_json),state=VALUES(state),error_code=VALUES(error_code)",
                            values,
                            new int[] {
                                Types.VARCHAR,
                                Types.BIGINT,
                                Types.VARCHAR,
                                Types.VARCHAR,
                                Types.VARCHAR,
                                Types.VARCHAR
                            });
                    long first = rows.get(0).number(), last = rows.get(rows.size() - 1).number();
                    var stored =
                            jdbc.queryForList(
                                    "SELECT row_no,row_digest FROM t_batch_row WHERE job_id=? AND"
                                            + " row_no>=? AND row_no<=? ORDER BY row_no",
                                    lease.jobId(),
                                    first,
                                    last);
                    if (stored.size() != rows.size())
                        throw new IllegalStateException("Staged row count differs");
                    for (int i = 0; i < rows.size(); i++)
                        if (!rows.get(i).digest().equals(str(stored.get(i), "row_digest")))
                            throw new InvalidImportException("IMMUTABLE_INPUT_CHANGED");
                    renew(lease);
                });
    }

    private CompletableFuture<Void> executeChunk(Lease lease) {
        List<Map<String, Object>> pending =
                tx.execute(
                        s -> {
                            fenced(lease);
                            return pending(lease.jobId());
                        });
        if (pending.isEmpty()) {
            tx.executeWithoutResult(
                    s -> {
                        Map<String, Object> r = fenced(lease);
                        finish(r, lease.principal(), "COMPLETED", null);
                    });
            return CompletableFuture.completedFuture(null);
        }
        int unassigned = (int) pending.stream().filter(r -> r.get("link_id") == null).count();
        // The independent allocator transaction is NEVER inside a business transaction/row lock.
        List<IdRange> allocation = unassigned == 0 ? List.of() : ids.reserveRanges(unassigned);
        List<Map<String, Object>> reservedRows =
                tx.execute(
                        s -> {
                            fenced(lease);
                            Iterator<Long> reserved = rangeIterator(allocation);
                            List<Object[]> values = new ArrayList<>();
                            for (Map<String, Object> row : pending)
                                if (row.get("link_id") == null)
                                    values.add(
                                            new Object[] {
                                                reserved.next(), lease.jobId(), num(row, "row_no")
                                            });
                            if (reserved.hasNext())
                                throw new IllegalStateException("Unexpected surplus IDs");
                            if (!values.isEmpty())
                                jdbc.batchUpdate(
                                        "UPDATE t_batch_row SET link_id=?,state='RESERVED' WHERE"
                                            + " job_id=? AND row_no=? AND link_id IS NULL AND"
                                            + " state='VALIDATED'",
                                        values);
                            renew(lease);
                            return pending(lease.jobId());
                        });
        List<Creation> registrationCreations = new ArrayList<>();
        List<IdRange> registrationRanges = new ArrayList<>();
        for (Map<String, Object> row : reservedRows) {
            if (row.get("link_id") == null)
                throw new IllegalStateException("Durable row identity missing");
            registrationCreations.add(read(str(row, "creation_json"), Creation.class));
            addRange(registrationRanges, num(row, "link_id"));
        }
        // Crash after identity commit reuses the durable link_id. Commit-unknown never substitutes
        // a different identity.
        return links.publishReservedManyAsync(
                registrationCreations,
                registrationRanges,
                permit -> {
                    tx.executeWithoutResult(
                            s -> {
                                Map<String, Object> job = fenced(lease);
                                List<Map<String, Object>> rows = pending(lease.jobId());
                                List<Creation> valid = new ArrayList<>();
                                List<Map<String, Object>> validRows = new ArrayList<>();
                                List<Object[]> failed = new ArrayList<>();
                                List<IdRange> ranges = new ArrayList<>();
                                for (Map<String, Object> row : rows) {
                                    Creation c = read(str(row, "creation_json"), Creation.class);
                                    try {
                                        links.validateCreation(c);
                                    } catch (IllegalArgumentException e) {
                                        failed.add(
                                                new Object[] {
                                                    "CREATION_NO_LONGER_VALID",
                                                    lease.jobId(),
                                                    num(row, "row_no")
                                                });
                                        continue;
                                    }
                                    if (row.get("link_id") == null)
                                        throw new IllegalStateException(
                                                "Durable row identity missing");
                                    valid.add(c);
                                    validRows.add(row);
                                    addRange(ranges, num(row, "link_id"));
                                }
                                List<Created> results =
                                        valid.isEmpty()
                                                ? List.of()
                                                : links.insertReservedMany(
                                                        lease.principal(), valid, ranges, permit);
                                List<Object[]> success = new ArrayList<>();
                                for (int i = 0; i < results.size(); i++)
                                    success.add(
                                            new Object[] {
                                                serialize(results.get(i)),
                                                lease.jobId(),
                                                num(validRows.get(i), "row_no")
                                            });
                                if (!success.isEmpty())
                                    jdbc.batchUpdate(
                                            "UPDATE t_batch_row SET"
                                                + " state='SUCCEEDED',result_json=?,error_code=NULL"
                                                + " WHERE job_id=? AND row_no=? AND"
                                                + " state='RESERVED'",
                                            success);
                                if (!failed.isEmpty())
                                    jdbc.batchUpdate(
                                            "UPDATE t_batch_row SET state='FAILED',error_code=?"
                                                + " WHERE job_id=? AND row_no=? AND state IN"
                                                + " ('VALIDATED','RESERVED')",
                                            failed);
                                quota.completeRows(
                                        lease.principal().tenantId(), success.size(), rows.size());
                                assertLease(lease);
                                jdbc.update(
                                        "UPDATE t_batch_job SET"
                                            + " succeeded_rows=succeeded_rows+?,failed_rows=failed_rows+?,reserved_rows=reserved_rows-?,lease_until=0,lease_owner=NULL,attempts=0,next_attempt_at=?,updated_at=?"
                                            + " WHERE job_id=?",
                                        success.size(),
                                        failed.size(),
                                        rows.size(),
                                        dbNow(),
                                        dbNow(),
                                        lease.jobId());
                                if (num(job, "reserved_rows") == rows.size())
                                    finish(
                                            job(lease.principal().tenantId(), lease.jobId(), true),
                                            lease.principal(),
                                            "COMPLETED",
                                            null);
                                links.assertPublicationOpen(permit);
                            });
                    return null;
                });
    }

    private List<Map<String, Object>> pending(String job) {
        return jdbc.queryForList(
                "SELECT row_no,creation_json,link_id FROM t_batch_row WHERE job_id=? AND state IN"
                        + " ('VALIDATED','RESERVED') ORDER BY row_no LIMIT ?",
                job,
                limits.chunkRows());
    }

    /**
     * Failure handling also fences. A stale worker cannot cancel, release quota, or publish after
     * takeover.
     */
    public void failed(Lease lease, Exception failure) {
        try {
            tx.executeWithoutResult(
                    s -> {
                        Map<String, Object> r = fenced(lease);
                        String reason =
                                failure instanceof InvalidImportException
                                        ? failure.getMessage()
                                        : failure.getClass().getSimpleName();
                        if (failure instanceof InvalidImportException
                                || num(r, "attempts") >= limits.maxAttempts())
                            finish(r, lease.principal(), "FAILED", reason);
                        else
                            jdbc.update(
                                    "UPDATE t_batch_job SET"
                                        + " lease_until=0,lease_owner=NULL,next_attempt_at=?,last_error=?,updated_at=?"
                                        + " WHERE job_id=?",
                                    dbNow()
                                            + Math.min(
                                                    60000L,
                                                    1000L << Math.min(6, num(r, "attempts"))),
                                    reason,
                                    dbNow(),
                                    lease.jobId());
                    });
        } catch (StaleLeaseException ignored) {
            /* Another owner/cancellation already decides this job. */
        }
    }

    private Map<String, Object> fenced(Lease lease) {
        CommandPrincipal current =
                lockWorkerAccount(lease.principal().tenantId(), lease.principal().username());
        quota.lock(current.tenantId());
        Map<String, Object> r = job(current.tenantId(), lease.jobId(), true);
        long now = dbNow();
        if (!str(r, "state").equals(lease.phase())
                || num(r, "fence") != lease.fence()
                || !lease.owner().equals(str(r, "lease_owner"))
                || num(r, "lease_until") <= now) throw new StaleLeaseException();
        groups.lockActive(current, lease.gid());
        if (current.authVersion() == Long.MAX_VALUE)
            throw new InvalidImportException("ACCOUNT_UNAVAILABLE");
        if (now - num(r, "created_at") >= JOB_MAX_AGE_MILLIS)
            throw new InvalidImportException("JOB_EXPIRED");
        return r;
    }

    private void assertLease(Lease lease) {
        Map<String, Object> r = job(lease.principal().tenantId(), lease.jobId(), false);
        if (num(r, "fence") != lease.fence()
                || !lease.owner().equals(str(r, "lease_owner"))
                || !lease.phase().equals(str(r, "state"))
                || num(r, "lease_until") <= dbNow()) throw new StaleLeaseException();
    }

    private void renew(Lease lease) {
        assertLease(lease);
        long now = dbNow();
        jdbc.update(
                "UPDATE t_batch_job SET lease_until=?,updated_at=? WHERE job_id=?",
                now + limits.leaseMillis(),
                now,
                lease.jobId());
    }

    private CommandPrincipal lockWorkerAccount(long tenant, String username) {
        var r =
                jdbc.queryForList(
                        "SELECT auth_version,disabled,del_flag FROM t_user WHERE username=? AND"
                                + " id=? FOR UPDATE",
                        username,
                        tenant);
        if (r.size() != 1) throw new IllegalStateException("Job account fact missing");
        boolean unavailable = num(r.get(0), "disabled") != 0 || num(r.get(0), "del_flag") != 0;
        return new CommandPrincipal(
                tenant, username, unavailable ? Long.MAX_VALUE : num(r.get(0), "auth_version"));
    }

    private void finish(Map<String, Object> r, CommandPrincipal p, String state, String error) {
        if (TERMINAL.contains(str(r, "state"))) return;
        if (state.equals("COMPLETED"))
            state =
                    num(r, "succeeded_rows") == 0
                            ? "FAILED"
                            : (num(r, "invalid_rows") + num(r, "failed_rows") > 0
                                    ? "PARTIAL_SUCCESS"
                                    : "SUCCEEDED");
        if (bool(r, "references_held")) {
            quota.finish(p.tenantId(), num(r, "reserved_rows"), bool(r, "validation_held"));
            groups.adjustReferences(p, str(r, "gid"), 0, -1);
        }
        jdbc.update(
                "UPDATE t_batch_job SET"
                    + " state=?,fence=fence+1,lease_owner=NULL,lease_until=0,reserved_rows=0,validation_held=FALSE,references_held=FALSE,last_error=?,updated_at=?"
                    + " WHERE job_id=?",
                state,
                error,
                dbNow(),
                str(r, "job_id"));
    }

    private Map<String, Object> job(long tenant, String id, boolean lock) {
        // Never re-read the potentially megabyte inline input on every chunk/lease check.
        String columns =
                "job_id,tenant_id,username,auth_version,request_id,request_digest,gid,state,input_kind,object_bucket,object_key,object_version,declared_sha256,declared_bytes,actual_sha256,actual_bytes,total_rows,valid_rows,invalid_rows,succeeded_rows,failed_rows,reserved_rows,validation_held,references_held,fence,lease_owner,lease_until,next_attempt_at,attempts,last_error,created_at";
        var rows =
                jdbc.queryForList(
                        "SELECT "
                                + columns
                                + " FROM t_batch_job WHERE tenant_id=? AND job_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        tenant,
                        id);
        if (rows.size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job unavailable");
        return rows.get(0);
    }

    private void schedule(String job, long tenant, String state) {
        String event = UUID.randomUUID().toString();
        outbox.append(
                event,
                "shortlink.batch.ready.v1",
                Long.toString(tenant),
                Map.of(
                        "eventId",
                        event,
                        "schemaVersion",
                        1,
                        "jobId",
                        job,
                        "tenantId",
                        Long.toString(tenant),
                        "state",
                        state));
    }

    private long dbNow() {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",
                        Long.class));
    }

    private Status status(Map<String, Object> r) {
        return new Status(
                str(r, "job_id"),
                str(r, "state"),
                num(r, "total_rows"),
                num(r, "valid_rows"),
                num(r, "invalid_rows"),
                num(r, "succeeded_rows"),
                num(r, "failed_rows"),
                str(r, "last_error"),
                str(r, "actual_sha256"),
                r.get("actual_bytes") == null ? null : num(r, "actual_bytes"));
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid input", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Durable payload corrupted", e);
        }
    }

    private static void requireGid(String gid) {
        if (gid == null || gid.isBlank() || gid.length() > 64)
            throw new IllegalArgumentException("Invalid group");
    }

    private static void requireRequestId(String id) {
        if (id == null || id.isBlank() || id.length() > 96)
            throw new IllegalArgumentException("requestId must be 1..96 characters");
    }

    private static String str(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).longValue();
    }

    private static boolean bool(Map<String, Object> row, String key) {
        return num(row, key) != 0;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void addRange(List<IdRange> ranges, long id) {
        if (!ranges.isEmpty() && ranges.get(ranges.size() - 1).endExclusive() == id) {
            IdRange old = ranges.remove(ranges.size() - 1);
            ranges.add(new IdRange(old.startInclusive(), id + 1));
        } else ranges.add(new IdRange(id, id + 1));
    }

    private static Iterator<Long> rangeIterator(List<IdRange> ranges) {
        return new Iterator<>() {
            int at;
            long next = ranges.isEmpty() ? 0 : ranges.get(0).startInclusive();

            public boolean hasNext() {
                return at < ranges.size();
            }

            public Long next() {
                if (!hasNext()) throw new NoSuchElementException();
                long value = next++;
                if (next == ranges.get(at).endExclusive() && ++at < ranges.size())
                    next = ranges.get(at).startInclusive();
                return value;
            }
        };
    }

    public static final class StaleLeaseException extends RuntimeException {}

    public static final class InvalidImportException extends RuntimeException {
        public InvalidImportException(String message) {
            super(message);
        }
    }
}
