package com.nageoffer.shortlink.agent.riskprofile.batch;

import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobPublicationService;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobScope;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchResult;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphDefinition;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RiskProfileBatchCoordinator {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Duration WINDOW_DURATION = Duration.ofHours(2);

    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(30);

    private final RiskProfileBatchService batchService;

    private final JdbcRiskProfileBatchRepository batchRepository;

    private final RiskAnalysisJobPublicationService publicationService;

    private final Clock clock;

    private final Duration leaseDuration;

    private final Supplier<String> ownerTokenSupplier;

    private final SecurityRiskSanitizer sanitizer = new SecurityRiskSanitizer();

    @Autowired
    public RiskProfileBatchCoordinator(
            RiskProfileBatchService batchService,
            JdbcRiskProfileBatchRepository batchRepository,
            RiskAnalysisJobPublicationService publicationService
    ) {
        this(
                batchService,
                batchRepository,
                publicationService,
                Clock.system(SHANGHAI),
                DEFAULT_LEASE_DURATION,
                () -> "risk-profile-" + UUID.randomUUID()
        );
    }

    public RiskProfileBatchCoordinator(
            RiskProfileBatchService batchService,
            JdbcRiskProfileBatchRepository batchRepository,
            RiskAnalysisJobPublicationService publicationService,
            Clock clock,
            Duration leaseDuration,
            Supplier<String> ownerTokenSupplier
    ) {
        this.batchService = batchService;
        this.batchRepository = batchRepository;
        this.publicationService = publicationService;
        this.clock = clock;
        this.leaseDuration = leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                ? DEFAULT_LEASE_DURATION
                : leaseDuration;
        this.ownerTokenSupplier = ownerTokenSupplier;
    }

    public RiskProfileBatch runOnce(Instant triggerTime) {
        Instant effectiveTriggerTime = triggerTime == null ? clock.instant() : triggerTime;
        Instant windowEnd = alignWindowEnd(effectiveTriggerTime);
        return runAlignedWindow(windowEnd, effectiveTriggerTime);
    }

    public RiskProfileBatch runAlignedWindow(Instant windowEnd, Instant executionTime) {
        if (windowEnd == null) {
            throw new IllegalArgumentException("windowEnd must not be null");
        }
        if (!windowEnd.equals(alignWindowEnd(windowEnd))) {
            throw new IllegalArgumentException("windowEnd must be an aligned two-hour boundary");
        }
        Instant effectiveExecutionTime = executionTime == null ? clock.instant() : executionTime;
        Instant windowStart = windowEnd.minus(WINDOW_DURATION);
        return runWindow(windowStart, windowEnd, effectiveExecutionTime);
    }

    public RiskProfileBatch retryRecoverableBatch(
            RiskProfileBatch failedBatch,
            Instant executionTime
    ) {
        if (failedBatch == null) {
            throw new IllegalArgumentException("failedBatch must not be null");
        }
        Instant effectiveExecutionTime = executionTime == null ? clock.instant() : executionTime;
        LocalDateTime executionTimeLocal = localDateTime(effectiveExecutionTime);
        boolean failed = failedBatch.status() == RiskProfileBatchStatus.FAILED;
        boolean expiredRunning = failedBatch.status() == RiskProfileBatchStatus.RUNNING
                && (failedBatch.leaseUntil() == null
                || !failedBatch.leaseUntil().isAfter(executionTimeLocal));
        if (!failed && !expiredRunning) {
            throw new IllegalArgumentException("Only FAILED or expired RUNNING risk profile batches can be retried");
        }
        if (failedBatch.windowStart() == null || failedBatch.windowEnd() == null) {
            throw new IllegalArgumentException("Failed risk profile batch window must be complete");
        }
        Instant windowStart = failedBatch.windowStart().atZone(SHANGHAI).toInstant();
        Instant windowEnd = failedBatch.windowEnd().atZone(SHANGHAI).toInstant();
        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("Failed risk profile batch window is invalid");
        }
        if (!WINDOW_DURATION.equals(Duration.between(windowStart, windowEnd))) {
            throw new IllegalArgumentException("Risk profile batch window must be exactly two hours");
        }
        if (!failedBatch.batchId().equals(batchId(windowEnd))) {
            throw new IllegalArgumentException("Failed risk profile batchId does not match its window");
        }
        return runWindow(windowStart, windowEnd, effectiveExecutionTime);
    }

    private RiskProfileBatch runWindow(
            Instant windowStart,
            Instant windowEnd,
            Instant executionTime
    ) {
        String batchId = batchId(windowEnd);
        String ownerToken = ownerTokenSupplier.get();
        LocalDateTime now = localDateTime(executionTime);
        LocalDateTime windowStartLocal = localDateTime(windowStart);
        LocalDateTime windowEndLocal = localDateTime(windowEnd);
        boolean acquired = batchRepository.tryAcquire(
                batchId,
                windowStartLocal,
                windowEndLocal,
                ownerToken,
                now,
                leaseDuration
        );
        if (!acquired) {
            return batchRepository.findByBatchId(batchId)
                    .orElseThrow(() -> new IllegalStateException("Risk profile batch lease was not acquired"));
        }

        RiskProfileBatchResult result = null;
        try {
            result = batchService.runOnce(windowEnd, ownerToken);
            if (!batchId.equals(result.batchId())) {
                throw new IllegalStateException("Risk profile batch result does not match coordinated batch");
            }
            Set<RiskAnalysisJobScope> desiredScopes = analysisJobScopes(result);
            RiskProfileBatch completed = completedBatch(
                    result,
                    windowStartLocal,
                    windowEndLocal,
                    ownerToken,
                    now,
                    desiredScopes.size()
            );
            return publicationService.publishIfOwned(completed, desiredScopes);
        } catch (RiskProfileBatchLeaseLostException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            RiskProfileBatch failed = failedBatch(
                    batchId,
                    windowStartLocal,
                    windowEndLocal,
                    ownerToken,
                    now,
                    result,
                    ex
            );
            return publicationService.publishIfOwned(failed, Set.of());
        }
    }

    public Instant alignWindowEnd(Instant triggerTime) {
        Instant effectiveTriggerTime = triggerTime == null ? clock.instant() : triggerTime;
        ZonedDateTime boundary = effectiveTriggerTime.atZone(SHANGHAI)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        if (boundary.getHour() % 2 != 0) {
            boundary = boundary.minusHours(1);
        }
        return boundary.toInstant();
    }

    private Set<RiskAnalysisJobScope> analysisJobScopes(RiskProfileBatchResult result) {
        Set<RiskAnalysisJobScope> scopes = new TreeSet<>();
        result.abnormalCandidatesByGid().keySet().stream()
                .filter(gid -> gid != null && !gid.isBlank())
                .map(gid -> new RiskAnalysisJobScope(
                        gid,
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION
                ))
                .forEach(scopes::add);
        return Set.copyOf(scopes);
    }

    private RiskProfileBatch completedBatch(
            RiskProfileBatchResult result,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String ownerToken,
            LocalDateTime startTime,
            int analysisJobCount
    ) {
        return new RiskProfileBatch(
                result.batchId(),
                windowStart,
                windowEnd,
                status(result),
                ownerToken,
                startTime.plus(leaseDuration),
                result.scannedShortLinks(),
                result.generatedProfiles(),
                result.failures().size(),
                analysisJobCount,
                result.failures(),
                startTime,
                localDateTime(clock.instant())
        );
    }

    private RiskProfileBatchStatus status(RiskProfileBatchResult result) {
        if (result.failures().isEmpty()) {
            return RiskProfileBatchStatus.SUCCEEDED;
        }
        return result.generatedProfiles() > 0
                ? RiskProfileBatchStatus.PARTIAL_SUCCESS
                : RiskProfileBatchStatus.FAILED;
    }

    private RiskProfileBatch failedBatch(
            String batchId,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            String ownerToken,
            LocalDateTime startTime,
            RiskProfileBatchResult result,
            RuntimeException exception
    ) {
        boolean preserveResult = result != null && batchId.equals(result.batchId());
        List<RiskProfileBatchFailure> failures = preserveResult
                ? new ArrayList<>(result.failures())
                : new ArrayList<>();
        failures.add(0, new RiskProfileBatchFailure(
                batchId,
                "BATCH_COORDINATION_FAILED",
                failureMessage(exception)
        ));
        return new RiskProfileBatch(
                batchId,
                windowStart,
                windowEnd,
                RiskProfileBatchStatus.FAILED,
                ownerToken,
                startTime.plus(leaseDuration),
                preserveResult ? result.scannedShortLinks() : 0,
                preserveResult ? result.generatedProfiles() : 0,
                failures.size(),
                0,
                failures,
                startTime,
                localDateTime(clock.instant())
        );
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        String sanitized = sanitizer.sanitizeText(message);
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }

    private String batchId(Instant windowEnd) {
        return "risk-profile:" + windowEnd.getEpochSecond();
    }

    private LocalDateTime localDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, SHANGHAI);
    }
}
