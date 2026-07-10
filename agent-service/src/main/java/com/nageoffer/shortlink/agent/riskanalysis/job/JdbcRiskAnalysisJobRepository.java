package com.nageoffer.shortlink.agent.riskanalysis.job;

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
public class JdbcRiskAnalysisJobRepository {

    private static final int CLAIM_SCAN_LIMIT = 16;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskAnalysisJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean createIfAbsent(RiskAnalysisJob job) {
        return createIfAbsent(job, true, "", job.createTime());
    }

    public boolean createIfAbsentIfBatchOwned(
            RiskAnalysisJob job,
            String batchOwnerToken,
            LocalDateTime now
    ) {
        return createIfAbsent(job, false, batchOwnerToken, now);
    }

    public boolean isBatchLeaseOwned(
            String batchId,
            String batchOwnerToken,
            LocalDateTime now
    ) {
        Integer owned = jdbcTemplate.queryForObject("""
                        select count(*)
                        from t_agent_risk_profile_batch
                        where batch_id = ?
                          and owner_token = ?
                          and status = ?
                          and lease_until > ?
                        """,
                Integer.class,
                batchId,
                batchOwnerToken,
                com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(now)
        );
        return owned != null && owned > 0;
    }

    private boolean createIfAbsent(
            RiskAnalysisJob job,
            boolean bypassBatchFence,
            String batchOwnerToken,
            LocalDateTime now
    ) {
        try {
            return jdbcTemplate.update("""
                            insert into t_agent_risk_analysis_job (
                                job_id,
                                batch_id,
                                gid,
                                graph_name,
                                graph_version,
                                status,
                                attempt_count,
                                next_retry_time,
                                owner_token,
                                lease_until,
                                session_id,
                                trace_id,
                                error_summary,
                                create_time,
                                update_time
                            )
                            select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            where ? = 1
                               or exists (
                                   select 1
                                   from t_agent_risk_profile_batch
                                   where batch_id = ?
                                     and owner_token = ?
                                     and status = ?
                                     and lease_until > ?
                               )
                            """,
                    job.jobId(),
                    job.batchId(),
                    job.gid(),
                    job.graphName(),
                    job.graphVersion(),
                    job.status().name(),
                    job.attemptCount(),
                    timestamp(job.nextRetryTime()),
                    job.ownerToken(),
                    timestamp(job.leaseUntil()),
                    job.sessionId(),
                    job.traceId(),
                    job.errorSummary(),
                    timestamp(job.createTime()),
                    timestamp(job.updateTime()),
                    bypassBatchFence ? 1 : 0,
                    job.batchId(),
                    batchOwnerToken == null ? "" : batchOwnerToken,
                    com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus.RUNNING.name(),
                    Timestamp.valueOf(now)
            ) > 0;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public Optional<RiskAnalysisJob> claimNext(
            String ownerToken,
            String traceId,
            LocalDateTime now,
            Duration leaseDuration,
            int maxAttempts
    ) {
        int safeMaxAttempts = Math.max(1, maxAttempts);
        failExpiredExhaustedRunningJobs(now, safeMaxAttempts);
        failExhaustedRetryWaitJobs(now, safeMaxAttempts);
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        List<ClaimCandidate> candidates = jdbcTemplate.query("""
                        select job.job_id, job.attempt_count
                        from t_agent_risk_analysis_job job
                        join t_agent_risk_profile_batch batch
                          on batch.batch_id = job.batch_id
                        where batch.status in (?, ?)
                          and job.attempt_count < ?
                          and (
                              job.status = ?
                              or (job.status = ? and job.next_retry_time <= ?)
                              or (job.status = ? and (job.lease_until is null or job.lease_until <= ?))
                          )
                        order by job.id
                        limit ?
                        """,
                (rs, rowNum) -> new ClaimCandidate(
                        rs.getString("job_id"),
                        rs.getInt("attempt_count")
                ),
                "SUCCEEDED",
                "PARTIAL_SUCCESS",
                safeMaxAttempts,
                RiskAnalysisJobStatus.PENDING.name(),
                RiskAnalysisJobStatus.RETRY_WAIT.name(),
                Timestamp.valueOf(now),
                RiskAnalysisJobStatus.RUNNING.name(),
                Timestamp.valueOf(now),
                CLAIM_SCAN_LIMIT
        );
        for (ClaimCandidate candidate : candidates) {
            int updatedRows = jdbcTemplate.update("""
                            update t_agent_risk_analysis_job
                            set status = ?,
                                owner_token = ?,
                                trace_id = case
                                    when trace_id is null or trace_id = '' then ?
                                    else trace_id
                                end,
                                lease_until = ?,
                                next_retry_time = null,
                                attempt_count = attempt_count + 1,
                                update_time = ?
                            where job_id = ?
                              and attempt_count = ?
                              and attempt_count < ?
                              and exists (
                                  select 1
                                  from t_agent_risk_profile_batch batch
                                  where batch.batch_id = t_agent_risk_analysis_job.batch_id
                                    and batch.status in (?, ?)
                              )
                              and (
                                  status = ?
                                  or (status = ? and next_retry_time <= ?)
                                  or (status = ? and (lease_until is null or lease_until <= ?))
                              )
                            """,
                    RiskAnalysisJobStatus.RUNNING.name(),
                    ownerToken,
                    traceId,
                    Timestamp.valueOf(leaseUntil),
                    Timestamp.valueOf(now),
                    candidate.jobId(),
                    candidate.attemptCount(),
                    safeMaxAttempts,
                    "SUCCEEDED",
                    "PARTIAL_SUCCESS",
                    RiskAnalysisJobStatus.PENDING.name(),
                    RiskAnalysisJobStatus.RETRY_WAIT.name(),
                    Timestamp.valueOf(now),
                    RiskAnalysisJobStatus.RUNNING.name(),
                    Timestamp.valueOf(now)
            );
            if (updatedRows > 0) {
                return findByJobId(candidate.jobId());
            }
        }
        return Optional.empty();
    }

    private void failExpiredExhaustedRunningJobs(LocalDateTime now, int maxAttempts) {
        jdbcTemplate.update("""
                        update t_agent_risk_analysis_job
                        set status = ?,
                            next_retry_time = null,
                            owner_token = '',
                            lease_until = null,
                            error_summary = ?,
                            update_time = ?
                        where status = ?
                          and attempt_count >= ?
                          and (lease_until is null or lease_until <= ?)
                        """,
                RiskAnalysisJobStatus.FAILED.name(),
                "Risk analysis job lease expired after maximum attempts",
                Timestamp.valueOf(now),
                RiskAnalysisJobStatus.RUNNING.name(),
                maxAttempts,
                Timestamp.valueOf(now)
        );
    }

    private void failExhaustedRetryWaitJobs(LocalDateTime now, int maxAttempts) {
        jdbcTemplate.update("""
                        update t_agent_risk_analysis_job
                        set status = ?,
                            next_retry_time = null,
                            owner_token = '',
                            lease_until = null,
                            error_summary = case
                                when error_summary is null or error_summary = ''
                                    then ?
                                else error_summary
                            end,
                            update_time = ?
                        where status = ?
                          and attempt_count >= ?
                        """,
                RiskAnalysisJobStatus.FAILED.name(),
                "Risk analysis job exhausted maximum attempts",
                Timestamp.valueOf(now),
                RiskAnalysisJobStatus.RETRY_WAIT.name(),
                maxAttempts
        );
    }

    public boolean renewLease(
            String jobId,
            String ownerToken,
            String traceId,
            int expectedAttemptCount,
            LocalDateTime now,
            Duration leaseDuration
    ) {
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        return jdbcTemplate.update("""
                        update t_agent_risk_analysis_job
                        set lease_until = ?,
                            update_time = ?
                        where job_id = ?
                          and owner_token = ?
                          and trace_id = ?
                          and attempt_count = ?
                          and status = ?
                          and lease_until is not null
                          and lease_until > ?
                        """,
                Timestamp.valueOf(leaseUntil),
                Timestamp.valueOf(now),
                jobId,
                ownerToken,
                traceId,
                expectedAttemptCount,
                RiskAnalysisJobStatus.RUNNING.name(),
                Timestamp.valueOf(now)
        ) > 0;
    }

    public boolean recordFailure(
            String jobId,
            String ownerToken,
            String traceId,
            int expectedAttemptCount,
            int maxAttempts,
            LocalDateTime now,
            LocalDateTime nextRetryTime,
            String errorSummary
    ) {
        int safeMaxAttempts = Math.max(1, maxAttempts);
        String safeErrorSummary = limit(errorSummary, 2048);
        RiskAnalysisJobStatus nextStatus = expectedAttemptCount >= safeMaxAttempts
                ? RiskAnalysisJobStatus.FAILED
                : RiskAnalysisJobStatus.RETRY_WAIT;
        Timestamp retryTimestamp = nextStatus == RiskAnalysisJobStatus.FAILED
                ? null
                : Timestamp.valueOf(nextRetryTime);
        return jdbcTemplate.update("""
                        update t_agent_risk_analysis_job
                        set status = ?,
                            next_retry_time = ?,
                            owner_token = '',
                            lease_until = null,
                            error_summary = ?,
                            update_time = ?
                        where job_id = ?
                          and owner_token = ?
                          and trace_id = ?
                          and attempt_count = ?
                          and status = ?
                        """,
                nextStatus.name(),
                retryTimestamp,
                safeErrorSummary,
                Timestamp.valueOf(now),
                jobId,
                ownerToken,
                traceId,
                expectedAttemptCount,
                RiskAnalysisJobStatus.RUNNING.name()
        ) > 0;
    }

    public boolean recordSuccess(
            String jobId,
            String ownerToken,
            String traceId,
            int expectedAttemptCount,
            LocalDateTime now
    ) {
        return jdbcTemplate.update("""
                        update t_agent_risk_analysis_job
                        set status = ?,
                            next_retry_time = null,
                            owner_token = '',
                            lease_until = null,
                            error_summary = '',
                            update_time = ?
                        where job_id = ?
                          and owner_token = ?
                          and trace_id = ?
                          and attempt_count = ?
                          and status = ?
                        """,
                RiskAnalysisJobStatus.SUCCEEDED.name(),
                Timestamp.valueOf(now),
                jobId,
                ownerToken,
                traceId,
                expectedAttemptCount,
                RiskAnalysisJobStatus.RUNNING.name()
        ) > 0;
    }

    public Optional<RiskAnalysisJob> findByJobId(String jobId) {
        List<RiskAnalysisJob> jobs = jdbcTemplate.query("""
                        select *
                        from t_agent_risk_analysis_job
                        where job_id = ?
                        """,
                (rs, rowNum) -> mapJob(rs),
                jobId
        );
        return jobs.stream().findFirst();
    }

    public Optional<RiskAnalysisJob> findByUniqueKey(
            String batchId,
            String gid,
            String graphName,
            String graphVersion
    ) {
        List<RiskAnalysisJob> jobs = jdbcTemplate.query("""
                        select *
                        from t_agent_risk_analysis_job
                        where batch_id = ?
                          and gid = ?
                          and graph_name = ?
                          and graph_version = ?
                        """,
                (rs, rowNum) -> mapJob(rs),
                batchId,
                gid,
                graphName,
                graphVersion
        );
        return jobs.stream().findFirst();
    }

    public List<RiskAnalysisJob> findByBatchId(String batchId) {
        return jdbcTemplate.query("""
                        select *
                        from t_agent_risk_analysis_job
                        where batch_id = ?
                        order by gid, graph_name, graph_version
                        """,
                (rs, rowNum) -> mapJob(rs),
                batchId
        );
    }

    public boolean deleteUnstartedJob(String jobId) {
        return jdbcTemplate.update("""
                        delete from t_agent_risk_analysis_job
                        where job_id = ?
                          and status = ?
                          and attempt_count = 0
                        """,
                jobId,
                RiskAnalysisJobStatus.PENDING.name()
        ) > 0;
    }

    private RiskAnalysisJob mapJob(ResultSet rs) throws SQLException {
        return new RiskAnalysisJob(
                rs.getString("job_id"),
                rs.getString("batch_id"),
                rs.getString("gid"),
                rs.getString("graph_name"),
                rs.getString("graph_version"),
                RiskAnalysisJobStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                localDateTime(rs.getTimestamp("next_retry_time")),
                rs.getString("owner_token"),
                localDateTime(rs.getTimestamp("lease_until")),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("error_summary"),
                localDateTime(rs.getTimestamp("create_time")),
                localDateTime(rs.getTimestamp("update_time"))
        );
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private record ClaimCandidate(String jobId, int attemptCount) {
    }
}
