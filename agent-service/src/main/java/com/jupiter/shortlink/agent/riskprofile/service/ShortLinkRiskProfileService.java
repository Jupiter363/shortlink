package com.jupiter.shortlink.agent.riskprofile.service;

import com.jupiter.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.jupiter.shortlink.agent.riskprofile.detector.ShortLinkRiskDetector;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskSourceStats;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.source.RiskStatsSourceGateway;
import com.jupiter.shortlink.agent.riskprofile.source.ShortLinkActiveCandidate;
import com.jupiter.shortlink.agent.riskprofile.source.ShortLinkStatsWindow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class ShortLinkRiskProfileService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final RiskStatsSourceGateway sourceGateway;

    private final JdbcShortLinkRiskProfileRepository profileRepository;

    private final ShortLinkRiskDetector riskDetector;

    @Autowired
    public ShortLinkRiskProfileService(
            RiskStatsSourceGateway sourceGateway,
            JdbcShortLinkRiskProfileRepository profileRepository) {
        this(sourceGateway, profileRepository, new ShortLinkRiskDetector());
    }

    public ShortLinkRiskProfileService(
            RiskStatsSourceGateway sourceGateway,
            JdbcShortLinkRiskProfileRepository profileRepository,
            ShortLinkRiskDetector riskDetector) {
        this.sourceGateway = sourceGateway;
        this.profileRepository = profileRepository;
        this.riskDetector = riskDetector;
    }

    public ShortLinkRiskProfile generateProfile(
            ShortLinkActiveCandidate candidate,
            Instant batchNow,
            String batchId,
            String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must not be blank");
        }
        Map<String, ShortLinkStatsWindow> windows =
                sourceGateway.loadStatsWindows(candidate, batchNow);
        ShortLinkStatsWindow stats2h = windows.get("2h");
        ShortLinkStatsWindow stats24h = windows.get("24h");
        ShortLinkStatsWindow stats7d = windows.get("7d");
        if (stats2h == null || stats24h == null || stats7d == null)
            throw new IllegalStateException("Statistics windows are incomplete");
        StatsEvidence.requireSameSnapshot(stats2h.meta(), stats24h.meta());
        StatsEvidence.requireSameSnapshot(stats2h.meta(), stats7d.meta());
        Instant effectiveEnd =
                Instant.ofEpochMilli(StatsEvidence.number(stats2h.meta().get("effectiveEnd")));
        if (effectiveEnd.isAfter(batchNow))
            throw new IllegalStateException("Statistics effectiveEnd exceeds requested cutoff");
        requireWindow(stats2h, effectiveEnd, Duration.ofHours(2));
        requireWindow(stats24h, effectiveEnd, Duration.ofHours(24));
        requireWindow(stats7d, effectiveEnd, Duration.ofDays(7));
        Instant window2hStart = stats2h.startTime();

        ShortLinkRiskSourceStats sourceStats =
                ShortLinkRiskSourceStats.builder()
                        .gid(firstText(stats2h.gid(), candidate.gid()))
                        .domain(firstText(stats2h.domain(), candidate.domain()))
                        .shortUri(firstText(stats2h.shortUri(), candidate.shortUri()))
                        .fullShortUrl(firstText(stats2h.fullShortUrl(), candidate.fullShortUrl()))
                        .pv2h(intValue(stats2h.pv()))
                        .uv2h(intValue(stats2h.uv()))
                        .pv24h(intValue(stats24h.pv()))
                        .uv24h(intValue(stats24h.uv()))
                        .pv7d(intValue(stats7d.pv()))
                        .uv7d(intValue(stats7d.uv()))
                        .topIpShare(stats2h.topIpShare())
                        .topVisitorShare(stats2h.topVisitorShare())
                        .topRegionShare(stats2h.topRegionShare())
                        .topDeviceShare(stats2h.topDeviceShare())
                        .topBrowserShare(stats2h.topBrowserShare())
                        .peakHourShare(stats2h.peakHourShare())
                        .repeatVisitRatio(stats2h.repeatVisitRatio())
                        .profileWindowStart(toBusinessTime(window2hStart))
                        .profileWindowEnd(toBusinessTime(effectiveEnd))
                        .build();
        ShortLinkRiskProfile profile =
                riskDetector
                        .detect(sourceStats)
                        .withBatchId(batchId)
                        .withEvidence(
                                new StatsEvidence(
                                        stats2h.tenantId(),
                                        StatsEvidence.number(stats2h.linkId()),
                                        stats2h.meta(),
                                        StatsEvidence.CURRENT_RULE_VERSION));
        if (!profileRepository.saveIfLeaseOwned(
                profile, ownerToken, LocalDateTime.now(BUSINESS_ZONE))) {
            throw new RiskProfileBatchLeaseLostException(batchId);
        }
        return profile;
    }

    public List<ShortLinkRiskProfile> findByBatchIdAndGid(String batchId, String gid) {
        return profileRepository.findByBatchIdAndGid(batchId, gid);
    }

    private LocalDateTime toBusinessTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, BUSINESS_ZONE);
    }

    private long intValue(Long value) {
        return StatsEvidence.number(value);
    }

    private static void requireWindow(ShortLinkStatsWindow window, Instant end, Duration duration) {
        if (!end.equals(window.endTime()) || !end.minus(duration).equals(window.startTime())) {
            throw new IllegalStateException(
                    "Statistics window does not share the authoritative common cutoff");
        }
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }
}
