package com.nageoffer.shortlink.agent.riskanalysis.job;

import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class RiskAnalysisJobPublicationService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcRiskProfileBatchRepository batchRepository;

    private final JdbcRiskAnalysisJobRepository jobRepository;

    private final RiskAnalysisJobService jobService;

    private final TransactionTemplate transactionTemplate;

    private final Clock clock;

    @Autowired
    public RiskAnalysisJobPublicationService(
            JdbcRiskProfileBatchRepository batchRepository,
            JdbcRiskAnalysisJobRepository jobRepository,
            RiskAnalysisJobService jobService,
            PlatformTransactionManager transactionManager
    ) {
        this(
                batchRepository,
                jobRepository,
                jobService,
                transactionManager,
                Clock.system(SHANGHAI)
        );
    }

    public RiskAnalysisJobPublicationService(
            JdbcRiskProfileBatchRepository batchRepository,
            JdbcRiskAnalysisJobRepository jobRepository,
            RiskAnalysisJobService jobService,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.batchRepository = batchRepository;
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public RiskProfileBatch publishIfOwned(
            RiskProfileBatch completion,
            Set<RiskAnalysisJobScope> desiredScopes
    ) {
        validateCompletion(completion);
        Set<RiskAnalysisJobScope> safeDesiredScopes = normalizeScopes(desiredScopes);
        if (completion.status() == RiskProfileBatchStatus.FAILED && !safeDesiredScopes.isEmpty()) {
            throw new IllegalArgumentException("FAILED batch must not publish risk analysis jobs");
        }
        return transactionTemplate.execute(status -> publishInTransaction(completion, safeDesiredScopes));
    }

    private RiskProfileBatch publishInTransaction(
            RiskProfileBatch completion,
            Set<RiskAnalysisJobScope> desiredScopes
    ) {
        LocalDateTime lockAttemptTime = now();
        if (!batchRepository.lockOwnedRunningBatch(
                completion.batchId(),
                completion.ownerToken(),
                lockAttemptTime
        )) {
            throw new RiskProfileBatchLeaseLostException(completion.batchId());
        }
        LocalDateTime lockedAt = now();
        if (!batchRepository.isOwnedRunningBatch(
                completion.batchId(),
                completion.ownerToken(),
                lockedAt
        )) {
            throw new RiskProfileBatchLeaseLostException(completion.batchId());
        }
        reconcileStaleJobs(completion.batchId(), desiredScopes);
        for (RiskAnalysisJobScope scope : desiredScopes) {
            jobService.createIfAbsentForScope(completion.batchId(), scope, completion.ownerToken());
        }
        List<RiskAnalysisJob> jobs = jobRepository.findByBatchId(completion.batchId());
        Set<RiskAnalysisJobScope> persistedScopes = new TreeSet<>();
        for (RiskAnalysisJob job : jobs) {
            ensureUnstarted(job);
            persistedScopes.add(new RiskAnalysisJobScope(
                    job.gid(),
                    job.graphName(),
                    job.graphVersion()
            ));
        }
        if (!persistedScopes.equals(desiredScopes)) {
            throw new IllegalStateException("Risk analysis job scopes do not match the desired publication set");
        }
        RiskProfileBatch published = withAnalysisJobCount(completion, desiredScopes.size());
        if (!batchRepository.complete(published, now())) {
            throw new RiskProfileBatchLeaseLostException(completion.batchId());
        }
        return batchRepository.findByBatchId(completion.batchId())
                .orElseThrow(() -> new IllegalStateException("Published risk profile batch was not persisted"));
    }

    private void reconcileStaleJobs(
            String batchId,
            Set<RiskAnalysisJobScope> desiredScopes
    ) {
        for (RiskAnalysisJob job : jobRepository.findByBatchId(batchId)) {
            RiskAnalysisJobScope persistedScope = new RiskAnalysisJobScope(
                    job.gid(),
                    job.graphName(),
                    job.graphVersion()
            );
            if (desiredScopes.contains(persistedScope)) {
                continue;
            }
            ensureUnstarted(job);
            if (!jobRepository.deleteUnstartedJob(job.jobId())) {
                throw new IllegalStateException("Stale risk analysis job could not be removed");
            }
        }
    }

    private void ensureUnstarted(RiskAnalysisJob job) {
        if (job.status() != RiskAnalysisJobStatus.PENDING || job.attemptCount() != 0) {
            throw new IllegalStateException(
                    "Risk analysis job is not safe to publish or reconcile: " + job.jobId()
            );
        }
    }

    private void validateCompletion(RiskProfileBatch completion) {
        if (completion == null) {
            throw new IllegalArgumentException("completion must not be null");
        }
        if (completion.batchId().isBlank()) {
            throw new IllegalArgumentException("completion.batchId must not be blank");
        }
        if (completion.ownerToken().isBlank()) {
            throw new IllegalArgumentException("completion.ownerToken must not be blank");
        }
        if (completion.status() == RiskProfileBatchStatus.RUNNING) {
            throw new IllegalArgumentException("completion.status must be terminal");
        }
        if (completion.finishTime() == null) {
            throw new IllegalArgumentException("completion.finishTime must not be null");
        }
    }

    private Set<RiskAnalysisJobScope> normalizeScopes(Set<RiskAnalysisJobScope> desiredScopes) {
        if (desiredScopes == null || desiredScopes.isEmpty()) {
            return Set.of();
        }
        if (desiredScopes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("desiredScopes must not contain null");
        }
        return java.util.Collections.unmodifiableSet(new TreeSet<>(desiredScopes));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private RiskProfileBatch withAnalysisJobCount(RiskProfileBatch completion, int analysisJobCount) {
        return new RiskProfileBatch(
                completion.batchId(),
                completion.windowStart(),
                completion.windowEnd(),
                completion.status(),
                completion.ownerToken(),
                completion.leaseUntil(),
                completion.scannedCount(),
                completion.generatedCount(),
                completion.failedCount(),
                analysisJobCount,
                completion.failures(),
                completion.startTime(),
                completion.finishTime()
        );
    }
}
