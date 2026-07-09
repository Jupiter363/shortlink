package com.nageoffer.shortlink.agent.riskprofile.service;

import com.nageoffer.shortlink.agent.riskprofile.detector.ShortLinkRiskDetector;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskSourceStats;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.source.RiskStatsSourceGateway;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkActiveCandidate;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkStatsWindow;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class ShortLinkRiskProfileService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final RiskStatsSourceGateway sourceGateway;

    private final JdbcShortLinkRiskProfileRepository profileRepository;

    private final ShortLinkRiskDetector riskDetector;

    public ShortLinkRiskProfileService(
            RiskStatsSourceGateway sourceGateway,
            JdbcShortLinkRiskProfileRepository profileRepository
    ) {
        this(sourceGateway, profileRepository, new ShortLinkRiskDetector());
    }

    public ShortLinkRiskProfileService(
            RiskStatsSourceGateway sourceGateway,
            JdbcShortLinkRiskProfileRepository profileRepository,
            ShortLinkRiskDetector riskDetector
    ) {
        this.sourceGateway = sourceGateway;
        this.profileRepository = profileRepository;
        this.riskDetector = riskDetector;
    }

    public ShortLinkRiskProfile generateProfile(ShortLinkActiveCandidate candidate, Instant batchNow) {
        Instant window2hStart = batchNow.minus(Duration.ofHours(2));
        ShortLinkStatsWindow stats2h = sourceGateway.loadStatsWindow(candidate, window2hStart, batchNow);
        ShortLinkStatsWindow stats24h = sourceGateway.loadStatsWindow(
                candidate,
                batchNow.minus(Duration.ofHours(24)),
                batchNow
        );
        ShortLinkStatsWindow stats7d = sourceGateway.loadStatsWindow(
                candidate,
                batchNow.minus(Duration.ofDays(7)),
                batchNow
        );

        ShortLinkRiskSourceStats sourceStats = ShortLinkRiskSourceStats.builder()
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
                .profileWindowEnd(toBusinessTime(batchNow))
                .build();
        ShortLinkRiskProfile profile = riskDetector.detect(sourceStats);
        profileRepository.save(profile);
        return profile;
    }

    private LocalDateTime toBusinessTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, BUSINESS_ZONE);
    }

    private int intValue(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }
}
