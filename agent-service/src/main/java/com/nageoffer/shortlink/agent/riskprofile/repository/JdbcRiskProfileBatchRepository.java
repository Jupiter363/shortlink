package com.nageoffer.shortlink.agent.riskprofile.repository;

import com.nageoffer.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRiskProfileBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RiskJsonCodec jsonCodec;

    @Autowired
    public JdbcRiskProfileBatchRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskJsonCodec());
    }

    public JdbcRiskProfileBatchRepository(JdbcTemplate jdbcTemplate, RiskJsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public boolean tryAcquire(
            String batchId,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String ownerToken,
            LocalDateTime now,
            Duration leaseDuration
    ) {
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        int updatedRows = jdbcTemplate.update("""
                        update t_agent_risk_profile_batch
                        set status = ?,
                            owner_token = ?,
                            lease_until = ?,
                            start_time = ?,
                            finish_time = null,
                            update_time = CURRENT_TIMESTAMP
                        where batch_id = ?
                          and status in (?, ?)
                          and (lease_until is null or lease_until <= ?)
                        """,
                RiskProfileBatchStatus.RUNNING.name(),
                ownerToken,
                Timestamp.valueOf(leaseUntil),
                Timestamp.valueOf(now),
                batchId,
                RiskProfileBatchStatus.RUNNING.name(),
                RiskProfileBatchStatus.FAILED.name(),
                Timestamp.valueOf(now)
        );
        if (updatedRows > 0) {
            return true;
        }
        try {
            jdbcTemplate.update("""
                            insert into t_agent_risk_profile_batch (
                                batch_id,
                                window_start,
                                window_end,
                                status,
                                owner_token,
                                lease_until,
                                failures_json,
                                start_time
                            )
                            values (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    batchId,
                    Timestamp.valueOf(windowStart),
                    Timestamp.valueOf(windowEnd),
                    RiskProfileBatchStatus.RUNNING.name(),
                    ownerToken,
                    Timestamp.valueOf(leaseUntil),
                    "[]",
                    Timestamp.valueOf(now)
            );
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public boolean complete(RiskProfileBatch batch) {
        return complete(batch, batch.finishTime());
    }

    public boolean complete(RiskProfileBatch batch, LocalDateTime completionTime) {
        if (batch.status() == RiskProfileBatchStatus.RUNNING || completionTime == null) {
            return false;
        }
        String errorSummary = batch.failures().stream()
                .findFirst()
                .map(RiskProfileBatchFailure::message)
                .orElse("");
        return jdbcTemplate.update("""
                        update t_agent_risk_profile_batch
                        set status = ?,
                            owner_token = '',
                            lease_until = null,
                            scanned_count = ?,
                            generated_count = ?,
                            failed_count = ?,
                            analysis_job_count = ?,
                            failures_json = ?,
                            error_summary = ?,
                            finish_time = ?,
                            update_time = CURRENT_TIMESTAMP
                        where batch_id = ?
                          and owner_token = ?
                          and status = ?
                          and lease_until is not null
                          and lease_until > ?
                        """,
                batch.status().name(),
                batch.scannedCount(),
                batch.generatedCount(),
                batch.failedCount(),
                batch.analysisJobCount(),
                jsonCodec.toJson(batch.failures()),
                errorSummary,
                timestamp(batch.finishTime()),
                batch.batchId(),
                batch.ownerToken(),
                RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(completionTime)
        ) > 0;
    }

    public Optional<RiskProfileBatch> findByBatchId(String batchId) {
        List<RiskProfileBatch> batches = jdbcTemplate.query("""
                        select *
                        from t_agent_risk_profile_batch
                        where batch_id = ?
                        """,
                (rs, rowNum) -> mapBatch(rs),
                batchId
        );
        return batches.stream().findFirst();
    }

    public List<RiskProfileBatch> findRecoverableBefore(
            LocalDateTime windowEndExclusive,
            LocalDateTime now,
            int limit
    ) {
        if (windowEndExclusive == null || now == null || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        select *
                        from t_agent_risk_profile_batch
                        where window_end < ?
                          and (
                              status = ?
                              or (
                                  status = ?
                                  and (lease_until is null or lease_until <= ?)
                              )
                          )
                        order by update_time, window_end, id
                        limit ?
                        """,
                (rs, rowNum) -> mapBatch(rs),
                Timestamp.valueOf(windowEndExclusive),
                RiskProfileBatchStatus.FAILED.name(),
                RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(now),
                limit
        );
    }

    public void recordRecoveryAttempt(String batchId, LocalDateTime attemptTime) {
        if (batchId == null || batchId.isBlank() || attemptTime == null) {
            return;
        }
        jdbcTemplate.update("""
                        update t_agent_risk_profile_batch
                        set update_time = ?
                        where batch_id = ?
                          and (
                              status = ?
                              or (
                                  status = ?
                                  and (lease_until is null or lease_until <= ?)
                              )
                          )
                        """,
                Timestamp.valueOf(attemptTime),
                batchId,
                RiskProfileBatchStatus.FAILED.name(),
                RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(attemptTime)
        );
    }

    public boolean lockOwnedRunningBatch(
            String batchId,
            String ownerToken,
            LocalDateTime now
    ) {
        List<String> batchIds = jdbcTemplate.queryForList("""
                        select batch_id
                        from t_agent_risk_profile_batch
                        where batch_id = ?
                          and owner_token = ?
                          and status = ?
                          and lease_until is not null
                          and lease_until > ?
                        for update
                        """,
                String.class,
                batchId,
                ownerToken,
                RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(now)
        );
        return !batchIds.isEmpty();
    }

    public boolean isOwnedRunningBatch(
            String batchId,
            String ownerToken,
            LocalDateTime now
    ) {
        Integer owned = jdbcTemplate.queryForObject("""
                        select count(*)
                        from t_agent_risk_profile_batch
                        where batch_id = ?
                          and owner_token = ?
                          and status = ?
                          and lease_until is not null
                          and lease_until > ?
                        """,
                Integer.class,
                batchId,
                ownerToken,
                RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(now)
        );
        return owned != null && owned > 0;
    }

    private RiskProfileBatch mapBatch(ResultSet rs) throws SQLException {
        RiskProfileBatchFailure[] failures = jsonCodec.fromJson(
                rs.getString("failures_json"),
                RiskProfileBatchFailure[].class
        );
        return new RiskProfileBatch(
                rs.getString("batch_id"),
                localDateTime(rs.getTimestamp("window_start")),
                localDateTime(rs.getTimestamp("window_end")),
                RiskProfileBatchStatus.valueOf(rs.getString("status")),
                rs.getString("owner_token"),
                localDateTime(rs.getTimestamp("lease_until")),
                rs.getInt("scanned_count"),
                rs.getInt("generated_count"),
                rs.getInt("failed_count"),
                rs.getInt("analysis_job_count"),
                List.of(failures),
                localDateTime(rs.getTimestamp("start_time")),
                localDateTime(rs.getTimestamp("finish_time"))
        );
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
