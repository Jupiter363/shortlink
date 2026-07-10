package com.nageoffer.shortlink.agent.riskanalysis.job;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileCandidateSelector;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphDefinition;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphExecutor;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskAnalysisInput;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskProfileTargetRef;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RiskAnalysisJobWorker {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final String DEFAULT_USERNAME = "risk-analysis-worker";

    private final JdbcRiskAnalysisJobRepository jobRepository;

    private final SecurityRiskGraphExecutor graphExecutor;

    private final JdbcShortLinkRiskProfileRepository shortLinkRepository;

    private final JdbcGroupRiskProfileRepository groupRepository;

    private final RiskAnalysisJobLeaseManager leaseManager;

    private final Clock clock;

    private final Duration leaseDuration;

    private final int maxAttempts;

    private final Duration initialBackoff;

    private final Duration maxBackoff;

    private final int topCandidateSize;

    private final String username;

    private final Supplier<String> ownerTokenSupplier;

    private final Supplier<String> traceIdSupplier;

    private final SecurityRiskSanitizer sanitizer = new SecurityRiskSanitizer();

    @Autowired
    public RiskAnalysisJobWorker(
            JdbcRiskAnalysisJobRepository jobRepository,
            SecurityRiskGraphExecutor graphExecutor,
            JdbcShortLinkRiskProfileRepository shortLinkRepository,
            JdbcGroupRiskProfileRepository groupRepository,
            RiskAnalysisJobLeaseManager leaseManager,
            AgentProperties agentProperties
    ) {
        this(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                leaseManager,
                Clock.system(SHANGHAI),
                Duration.ofMinutes(Math.max(1, agentProperties.getRisk().getAnalysis().getJobLeaseMinutes())),
                Math.max(1, agentProperties.getRisk().getAnalysis().getMaxAttempts()),
                Duration.ofSeconds(Math.max(1, agentProperties.getRisk().getAnalysis().getRetryInitialSeconds())),
                Duration.ofSeconds(Math.max(1, agentProperties.getRisk().getAnalysis().getRetryMaxSeconds())),
                Math.max(1, agentProperties.getRisk().getProfile().getTopCandidateSize()),
                DEFAULT_USERNAME,
                () -> "risk-analysis-" + UUID.randomUUID(),
                () -> "risk-trace-" + UUID.randomUUID()
        );
    }

    public RiskAnalysisJobWorker(
            JdbcRiskAnalysisJobRepository jobRepository,
            SecurityRiskGraphExecutor graphExecutor,
            JdbcShortLinkRiskProfileRepository shortLinkRepository,
            JdbcGroupRiskProfileRepository groupRepository,
            RiskAnalysisJobLeaseManager leaseManager,
            Clock clock,
            Duration leaseDuration,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            int topCandidateSize,
            String username,
            Supplier<String> ownerTokenSupplier,
            Supplier<String> traceIdSupplier
    ) {
        this.jobRepository = jobRepository;
        this.graphExecutor = graphExecutor;
        this.shortLinkRepository = shortLinkRepository;
        this.groupRepository = groupRepository;
        this.leaseManager = leaseManager;
        this.clock = clock;
        this.leaseDuration = positiveDuration(leaseDuration, Duration.ofMinutes(5));
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = positiveDuration(initialBackoff, Duration.ofSeconds(30));
        this.maxBackoff = positiveDuration(maxBackoff, Duration.ofMinutes(10));
        this.topCandidateSize = Math.max(1, topCandidateSize);
        this.username = username == null || username.isBlank() ? DEFAULT_USERNAME : username.trim();
        this.ownerTokenSupplier = ownerTokenSupplier;
        this.traceIdSupplier = traceIdSupplier;
    }

    public boolean runNext() {
        LocalDateTime now = LocalDateTime.now(clock);
        String ownerToken = ownerTokenSupplier.get();
        String traceId = traceIdSupplier.get();
        Optional<RiskAnalysisJob> claimed = jobRepository.claimNext(
                ownerToken,
                traceId,
                now,
                leaseDuration,
                maxAttempts
        );
        if (claimed.isEmpty()) {
            return false;
        }
        RiskAnalysisJob job = claimed.get();
        try (RiskAnalysisJobLeaseManager.Lease lease = leaseManager.start(job, leaseDuration, clock)) {
            try {
                graphExecutor.execute(graphRequest(job));
                lease.assertOwned();
                LocalDateTime completionTime = LocalDateTime.now(clock);
                boolean recorded = jobRepository.recordSuccess(
                        job.jobId(),
                        job.ownerToken(),
                        job.traceId(),
                        job.attemptCount(),
                        completionTime
                );
                if (!recorded) {
                    throw new RiskAnalysisJobLeaseLostException(job.jobId());
                }
            } catch (RiskAnalysisJobLeaseLostException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                lease.assertOwned();
                LocalDateTime failureTime = LocalDateTime.now(clock);
                boolean recorded = jobRepository.recordFailure(
                        job.jobId(),
                        job.ownerToken(),
                        job.traceId(),
                        job.attemptCount(),
                        maxAttempts,
                        failureTime,
                        failureTime.plus(retryBackoff(job.attemptCount())),
                        failureMessage(ex)
                );
                if (!recorded) {
                    throw new RiskAnalysisJobLeaseLostException(job.jobId(), ex);
                }
            }
        }
        return true;
    }

    private SecurityRiskGraphRequest graphRequest(RiskAnalysisJob job) {
        if (!SecurityRiskGraphDefinition.GRAPH_NAME.equals(job.graphName())
                || !SecurityRiskGraphDefinition.GRAPH_VERSION.equals(job.graphVersion())) {
            throw new IllegalStateException("Unsupported security risk graph definition");
        }
        Optional<GroupRiskProfile> groupProfile = groupRepository.findByBatchIdAndGid(
                job.batchId(),
                job.gid()
        );
        List<ShortLinkRiskProfile> candidates = RiskProfileCandidateSelector.top(
                safeProfiles(shortLinkRepository.findByBatchIdAndGid(job.batchId(), job.gid())).stream()
                        .filter(profile -> job.batchId().equals(profile.batchId()))
                        .filter(profile -> job.gid().equals(profile.gid()))
                        .filter(profile -> profile.riskLevel() != RiskLevel.LOW)
                        .toList(),
                topCandidateSize
        );
        LocalDateTime profileWindowEnd = groupProfile
                .map(GroupRiskProfile::profileWindowEnd)
                .orElseGet(() -> candidates.stream()
                        .map(ShortLinkRiskProfile::profileWindowEnd)
                        .filter(value -> value != null)
                        .max(LocalDateTime::compareTo)
                        .orElseThrow(() -> new IllegalStateException("Risk profile batch data was not found")));
        RiskAnalysisInput analysisInput = new RiskAnalysisInput(
                job.batchId(),
                job.gid(),
                profileWindowEnd,
                candidates.stream()
                        .map(profile -> new RiskProfileTargetRef(profile.domain(), profile.shortUri()))
                        .toList()
        );
        return new SecurityRiskGraphRequest(
                job.sessionId(),
                username,
                "Analyze security risk profiles for the scheduled batch.",
                job.traceId(),
                analysisInput
        );
    }

    private Duration retryBackoff(int attemptCount) {
        long multiplier = 1L << Math.min(30, Math.max(0, attemptCount - 1));
        Duration calculated;
        try {
            calculated = initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException ex) {
            calculated = maxBackoff;
        }
        return calculated.compareTo(maxBackoff) > 0 ? maxBackoff : calculated;
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        String sanitized = sanitizer.sanitizeText(message);
        return sanitized.length() <= 2048 ? sanitized : sanitized.substring(0, 2048);
    }

    private List<ShortLinkRiskProfile> safeProfiles(List<ShortLinkRiskProfile> profiles) {
        return profiles == null ? List.of() : profiles;
    }

    private Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
