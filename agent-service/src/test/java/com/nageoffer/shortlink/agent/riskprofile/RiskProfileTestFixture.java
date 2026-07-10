package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

public final class RiskProfileTestFixture {

    private static final Duration TEST_LEASE_DURATION = Duration.ofDays(1);

    private RiskProfileTestFixture() {
    }

    public static void saveShortLinkProfile(
            JdbcTemplate jdbcTemplate,
            JdbcShortLinkRiskProfileRepository repository,
            ShortLinkRiskProfile profile
    ) {
        Lease lease = ensureLease(
                jdbcTemplate,
                profile.batchId(),
                profile.profileWindowStart(),
                profile.profileWindowEnd()
        );
        if (!repository.saveIfLeaseOwned(profile, lease.ownerToken(), lease.checkTime())) {
            throw new IllegalStateException("Test short-link risk profile lease was not owned");
        }
    }

    public static void saveGroupProfile(
            JdbcTemplate jdbcTemplate,
            JdbcGroupRiskProfileRepository repository,
            GroupRiskProfile profile
    ) {
        Lease lease = ensureLease(
                jdbcTemplate,
                profile.batchId(),
                profile.profileWindowStart(),
                profile.profileWindowEnd()
        );
        if (!repository.saveIfLeaseOwned(profile, lease.ownerToken(), lease.checkTime())) {
            throw new IllegalStateException("Test group risk profile lease was not owned");
        }
    }

    private static Lease ensureLease(
            JdbcTemplate jdbcTemplate,
            String batchId,
            LocalDateTime windowStart,
            LocalDateTime windowEnd
    ) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("Test risk profile batchId must not be blank");
        }
        LocalDateTime checkTime = windowEnd == null ? LocalDateTime.of(2026, 1, 1, 0, 0) : windowEnd;
        LocalDateTime safeWindowStart = windowStart == null ? checkTime.minusHours(2) : windowStart;
        LocalDateTime safeWindowEnd = windowEnd == null ? checkTime : windowEnd;
        String ownerToken = "test-owner-" + UUID.nameUUIDFromBytes(
                batchId.getBytes(StandardCharsets.UTF_8)
        );
        JdbcRiskProfileBatchRepository batchRepository =
                new JdbcRiskProfileBatchRepository(jdbcTemplate);
        batchRepository.findByBatchId(batchId).ifPresentOrElse(
                batch -> {
                    if (!ownerToken.equals(batch.ownerToken())) {
                        throw new IllegalStateException("Test risk profile batch is owned by another fixture");
                    }
                },
                () -> {
                    if (!batchRepository.tryAcquire(
                            batchId,
                            safeWindowStart,
                            safeWindowEnd,
                            ownerToken,
                            checkTime,
                            TEST_LEASE_DURATION
                    )) {
                        throw new IllegalStateException("Test risk profile batch lease was not acquired");
                    }
                }
        );
        return new Lease(ownerToken, checkTime);
    }

    private record Lease(String ownerToken, LocalDateTime checkTime) {
    }
}
