package com.nageoffer.shortlink.agent.riskprofile.service;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.source.RiskStatsSourceGateway;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkActiveCandidate;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RiskProfileBatchService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final RiskStatsSourceGateway sourceGateway;

    private final ShortLinkRiskProfileService profileService;

    private final JdbcGroupRiskProfileRepository groupProfileRepository;

    private final GroupRiskProfileAggregator groupRiskProfileAggregator;

    private final AgentProperties agentProperties;

    private final SecurityRiskSanitizer sanitizer = new SecurityRiskSanitizer();

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

    public RiskProfileBatchResult runOnce(Instant batchNow, String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must not be blank");
        }
        Instant effectiveBatchNow = batchNow == null ? Instant.now() : batchNow;
        String batchId = "risk-profile:" + effectiveBatchNow.getEpochSecond();
        List<ShortLinkActiveCandidate> candidates = sourceGateway.listActiveShortLinks(
                effectiveBatchNow.minus(Duration.ofDays(activeScanDays()))
        );
        List<ShortLinkActiveCandidate> safeCandidates = safeList(candidates);
        List<ShortLinkRiskProfile> generatedProfiles = new ArrayList<>();
        List<RiskProfileBatchFailure> failures = new ArrayList<>();
        for (ShortLinkActiveCandidate candidate : safeCandidates) {
            try {
                ShortLinkRiskProfile profile = profileService.generateProfile(
                        candidate,
                        effectiveBatchNow,
                        batchId,
                        ownerToken
                );
                generatedProfiles.add(profile);
            } catch (RiskProfileBatchLeaseLostException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                failures.add(new RiskProfileBatchFailure(
                        targetKey(candidate),
                        "PROFILE_GENERATION_FAILED",
                        failureMessage(ex)
                ));
            }
        }
        List<ShortLinkRiskProfile> batchProfiles = loadBatchProfiles(
                batchId,
                safeCandidates,
                generatedProfiles
        );
        List<RiskProfileBatchFailure> unresolvedFailures = unresolvedFailures(failures, batchProfiles);
        saveGroupProfiles(batchProfiles, ownerToken);
        return new RiskProfileBatchResult(
                batchId,
                effectiveBatchNow,
                safeCandidates.size(),
                batchProfiles.size(),
                unresolvedFailures,
                abnormalCandidatesByGid(batchProfiles)
        );
    }

    private String targetKey(ShortLinkActiveCandidate candidate) {
        return candidate.gid() + "/" + candidate.domain() + "/" + candidate.shortUri();
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        String sanitized = sanitizer.sanitizeText(message);
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }

    private List<ShortLinkRiskProfile> loadBatchProfiles(
            String batchId,
            List<ShortLinkActiveCandidate> candidates,
            List<ShortLinkRiskProfile> generatedProfiles
    ) {
        Map<String, ShortLinkRiskProfile> profilesByTarget = new LinkedHashMap<>();
        generatedProfiles.forEach(profile -> profilesByTarget.put(targetKey(profile), profile));
        Set<String> gids = new LinkedHashSet<>();
        candidates.stream()
                .map(ShortLinkActiveCandidate::gid)
                .filter(gid -> gid != null && !gid.isBlank())
                .forEach(gids::add);
        for (String gid : gids) {
            List<ShortLinkRiskProfile> persistedProfiles = profileService.findByBatchIdAndGid(batchId, gid);
            if (persistedProfiles == null) {
                continue;
            }
            persistedProfiles.forEach(profile -> profilesByTarget.put(targetKey(profile), profile));
        }
        return List.copyOf(profilesByTarget.values());
    }

    private List<RiskProfileBatchFailure> unresolvedFailures(
            List<RiskProfileBatchFailure> failures,
            List<ShortLinkRiskProfile> batchProfiles
    ) {
        Set<String> successfulTargets = batchProfiles.stream()
                .map(this::targetKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return failures.stream()
                .filter(failure -> !successfulTargets.contains(failure.targetKey()))
                .toList();
    }

    private String targetKey(ShortLinkRiskProfile profile) {
        return profile.gid() + "/" + profile.domain() + "/" + profile.shortUri();
    }

    private void saveGroupProfiles(List<ShortLinkRiskProfile> generatedProfiles, String ownerToken) {
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
            com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile groupProfile =
                    groupRiskProfileAggregator.aggregate(
                    gid,
                    profiles,
                    groupProfileRepository.findTrend7d(gid, trendEndDate)
            );
            if (!groupProfileRepository.saveIfLeaseOwned(
                    groupProfile,
                    ownerToken,
                    LocalDateTime.now(BUSINESS_ZONE)
            )) {
                throw new RiskProfileBatchLeaseLostException(groupProfile.batchId());
            }
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
                RiskProfileCandidateSelector.top(candidates, topCandidateSize)
        ));
        return topByGid;
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
