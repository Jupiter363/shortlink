package com.nageoffer.shortlink.agent.riskprofile.service;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.source.RiskStatsSourceGateway;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkActiveCandidate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiskProfileBatchService {

    private final RiskStatsSourceGateway sourceGateway;

    private final ShortLinkRiskProfileService profileService;

    private final JdbcGroupRiskProfileRepository groupProfileRepository;

    private final GroupRiskProfileAggregator groupRiskProfileAggregator;

    private final AgentProperties agentProperties;

    public RiskProfileBatchService(
            RiskStatsSourceGateway sourceGateway,
            ShortLinkRiskProfileService profileService,
            JdbcGroupRiskProfileRepository groupProfileRepository,
            GroupRiskProfileAggregator groupRiskProfileAggregator,
            AgentProperties agentProperties
    ) {
        this.sourceGateway = sourceGateway;
        this.profileService = profileService;
        this.groupProfileRepository = groupProfileRepository;
        this.groupRiskProfileAggregator = groupRiskProfileAggregator;
        this.agentProperties = agentProperties;
    }

    public RiskProfileBatchResult runOnce(Instant batchNow) {
        Instant effectiveBatchNow = batchNow == null ? Instant.now() : batchNow;
        List<ShortLinkActiveCandidate> candidates = sourceGateway.listActiveShortLinks(
                effectiveBatchNow.minus(Duration.ofDays(activeScanDays()))
        );
        List<ShortLinkRiskProfile> generatedProfiles = new ArrayList<>();
        for (ShortLinkActiveCandidate candidate : safeList(candidates)) {
            generatedProfiles.add(profileService.generateProfile(candidate, effectiveBatchNow));
        }
        saveGroupProfiles(generatedProfiles);
        return new RiskProfileBatchResult(
                effectiveBatchNow,
                safeList(candidates).size(),
                generatedProfiles.size(),
                abnormalCandidatesByGid(generatedProfiles)
        );
    }

    private void saveGroupProfiles(List<ShortLinkRiskProfile> generatedProfiles) {
        Map<String, List<ShortLinkRiskProfile>> profilesByGid = new LinkedHashMap<>();
        generatedProfiles.forEach(profile -> profilesByGid
                .computeIfAbsent(profile.gid(), key -> new ArrayList<>())
                .add(profile));
        profilesByGid.forEach((gid, profiles) -> {
            LocalDate trendEndDate = profiles.stream()
                    .map(ShortLinkRiskProfile::profileWindowEnd)
                    .max(java.time.LocalDateTime::compareTo)
                    .map(java.time.LocalDateTime::toLocalDate)
                    .orElse(LocalDate.now());
            groupProfileRepository.save(groupRiskProfileAggregator.aggregate(
                    gid,
                    profiles,
                    groupProfileRepository.findTrend7d(gid, trendEndDate)
            ));
        });
    }

    private Map<String, List<ShortLinkRiskProfile>> abnormalCandidatesByGid(List<ShortLinkRiskProfile> profiles) {
        Map<String, List<ShortLinkRiskProfile>> grouped = new LinkedHashMap<>();
        for (ShortLinkRiskProfile profile : profiles) {
            if (profile.riskLevel() == RiskLevel.LOW) {
                continue;
            }
            grouped.computeIfAbsent(profile.gid(), key -> new ArrayList<>()).add(profile);
        }
        int topCandidateSize = topCandidateSize();
        Map<String, List<ShortLinkRiskProfile>> topByGid = new LinkedHashMap<>();
        grouped.forEach((gid, candidates) -> topByGid.put(
                gid,
                candidates.stream()
                        .sorted(candidateComparator())
                        .limit(topCandidateSize)
                        .toList()
        ));
        return topByGid;
    }

    private Comparator<ShortLinkRiskProfile> candidateComparator() {
        return Comparator
                .comparingInt(ShortLinkRiskProfile::riskScore)
                .reversed()
                .thenComparing(
                        profile -> profile.metrics() == null ? 0 : profile.metrics().pv2h(),
                        Comparator.reverseOrder()
                )
                .thenComparing(ShortLinkRiskProfile::shortUri);
    }

    private int activeScanDays() {
        return Math.max(1, agentProperties.getRisk().getProfile().getActiveScanDays());
    }

    private int topCandidateSize() {
        return Math.max(1, agentProperties.getRisk().getProfile().getTopCandidateSize());
    }

    private List<ShortLinkActiveCandidate> safeList(List<ShortLinkActiveCandidate> candidates) {
        return candidates == null ? List.of() : candidates;
    }
}
