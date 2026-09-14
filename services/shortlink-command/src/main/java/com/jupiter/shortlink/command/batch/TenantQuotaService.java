package com.jupiter.shortlink.command.batch;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Call after the account row lock, before any job/group/route lock. All mutations join that
 * transaction.
 */
@Service
public class TenantQuotaService {
    private final JdbcTemplate jdbc;
    private final BatchLimits limits;

    public TenantQuotaService(JdbcTemplate jdbc, BatchLimits limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    public void lock(long tenant) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Quota requires transaction");
        jdbc.update("INSERT IGNORE INTO t_tenant_quota(tenant_id) VALUES (?)", tenant);
        jdbc.queryForMap("SELECT * FROM t_tenant_quota WHERE tenant_id=? FOR UPDATE", tenant);
    }

    public void consume(long tenant, long rows) {
        requirePositive(rows);
        require(
                jdbc.update(
                        "UPDATE t_tenant_quota SET used_rows=used_rows+? WHERE tenant_id=? AND"
                                + " used_rows+reserved_rows+?<=?",
                        rows,
                        tenant,
                        rows,
                        limits.tenantRows()));
    }

    /** Reject already exhausted tenants before permanent ID/membership allocation. */
    public void checkAvailable(long tenant, long rows) {
        requirePositive(rows);
        var current =
                jdbc.queryForList(
                        "SELECT used_rows,reserved_rows FROM t_tenant_quota WHERE tenant_id=?",
                        tenant);
        long remaining = limits.tenantRows();
        if (!current.isEmpty()) {
            long used = ((Number) current.get(0).get("used_rows")).longValue();
            long reserved = ((Number) current.get(0).get("reserved_rows")).longValue();
            if (used < 0 || reserved < 0 || used > remaining || reserved > remaining - used)
                remaining = 0;
            else remaining -= used + reserved;
        }
        if (remaining < rows)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Tenant quota exhausted");
        // This read is an early rejection only. The original locked consume still authorizes
        // the eventual transaction, including races with other creates and durable batches.
    }

    public void releaseDeleted(long tenant) {
        require(
                jdbc.update(
                        "UPDATE t_tenant_quota SET used_rows=used_rows-1 WHERE tenant_id=? AND"
                                + " used_rows>0",
                        tenant));
    }

    public void admitValidation(long tenant) {
        require(
                jdbc.update(
                        "UPDATE t_tenant_quota SET"
                            + " active_jobs=active_jobs+1,validation_jobs=validation_jobs+1,validation_bytes=validation_bytes+?"
                            + " WHERE tenant_id=? AND active_jobs<? AND validation_jobs<? AND"
                            + " validation_bytes+?<=?",
                        limits.maxInputBytes(),
                        tenant,
                        limits.tenantJobs(),
                        limits.validationJobs(),
                        limits.maxInputBytes(),
                        limits.validationBytes()));
    }

    public void ready(long tenant, long rows) {
        if (rows < 0) throw new IllegalArgumentException("Negative rows");
        require(
                jdbc.update(
                        "UPDATE t_tenant_quota SET"
                            + " reserved_rows=reserved_rows+?,validation_jobs=validation_jobs-1,validation_bytes=validation_bytes-?"
                            + " WHERE tenant_id=? AND used_rows+reserved_rows+?<=? AND"
                            + " validation_jobs>0 AND validation_bytes>=?",
                        rows,
                        limits.maxInputBytes(),
                        tenant,
                        rows,
                        limits.tenantRows(),
                        limits.maxInputBytes()));
    }

    public void completeRows(long tenant, long succeeded, long finished) {
        if (succeeded < 0 || finished < succeeded || finished < 1)
            throw new IllegalArgumentException("Invalid row settlement");
        require(
                jdbc.update(
                        "UPDATE t_tenant_quota SET"
                                + " reserved_rows=reserved_rows-?,used_rows=used_rows+? WHERE"
                                + " tenant_id=? AND reserved_rows>=?",
                        finished,
                        succeeded,
                        tenant,
                        finished));
    }

    public void finish(long tenant, long remaining, boolean validationHeld) {
        require(
                jdbc.update(
                        "UPDATE t_tenant_quota SET"
                            + " active_jobs=active_jobs-1,reserved_rows=reserved_rows-?,validation_jobs=validation_jobs-?,validation_bytes=validation_bytes-?"
                            + " WHERE tenant_id=? AND active_jobs>0 AND reserved_rows>=? AND"
                            + " validation_jobs>=? AND validation_bytes>=?",
                        remaining,
                        validationHeld ? 1 : 0,
                        validationHeld ? limits.maxInputBytes() : 0,
                        tenant,
                        remaining,
                        validationHeld ? 1 : 0,
                        validationHeld ? limits.maxInputBytes() : 0));
    }

    private static void require(int count) {
        if (count != 1)
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Tenant quota exhausted or accounting conflict");
    }

    private static void requirePositive(long rows) {
        if (rows < 1) throw new IllegalArgumentException("Positive row count required");
    }
}
