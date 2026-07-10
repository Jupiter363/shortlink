package com.nageoffer.shortlink.agent.riskanalysis.job;

import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class RiskAnalysisJobService {

    private static final int MAX_SESSION_ID_LENGTH = 128;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcRiskAnalysisJobRepository repository;

    private final Clock clock;

    @Autowired
    public RiskAnalysisJobService(JdbcRiskAnalysisJobRepository repository) {
        this(repository, Clock.system(SHANGHAI));
    }

    public RiskAnalysisJobService(JdbcRiskAnalysisJobRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public RiskAnalysisJob createIfAbsent(String batchId, String gid, String batchOwnerToken) {
        return createIfAbsentForScope(
                batchId,
                new RiskAnalysisJobScope(
                        gid,
                        SecurityRiskGraphDefinition.GRAPH_NAME,
                        SecurityRiskGraphDefinition.GRAPH_VERSION
                ),
                batchOwnerToken
        );
    }

    public RiskAnalysisJob createIfAbsentForScope(
            String batchId,
            RiskAnalysisJobScope scope,
            String batchOwnerToken
    ) {
        String safeBatchId = requireText(batchId, "batchId");
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        String safeGid = scope.gid();
        String safeBatchOwnerToken = requireText(batchOwnerToken, "batchOwnerToken");
        String scopeKey = String.join(
                "|",
                safeBatchId,
                safeGid,
                scope.graphName(),
                scope.graphVersion()
        );
        String sessionId = "risk-batch:" + safeBatchId + ":" + safeGid;
        if (sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException("Risk analysis sessionId exceeds 128 characters");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        RiskAnalysisJob pendingJob = new RiskAnalysisJob(
                "risk-job-" + nameUuid(scopeKey),
                safeBatchId,
                safeGid,
                scope.graphName(),
                scope.graphVersion(),
                RiskAnalysisJobStatus.PENDING,
                0,
                null,
                "",
                null,
                sessionId,
                "",
                "",
                now,
                now
        );
        boolean created = repository.createIfAbsentIfBatchOwned(
                pendingJob,
                safeBatchOwnerToken,
                now
        );
        if (!created && !repository.isBatchLeaseOwned(safeBatchId, safeBatchOwnerToken, now)) {
            throw new RiskProfileBatchLeaseLostException(safeBatchId);
        }
        return repository.findByUniqueKey(
                        safeBatchId,
                        safeGid,
                        scope.graphName(),
                        scope.graphVersion()
                )
                .orElseThrow(() -> new IllegalStateException("Risk analysis job was not persisted"));
    }

    private String nameUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
