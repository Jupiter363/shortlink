package com.nageoffer.shortlink.agent.riskprofile.service;

import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RiskProfileBatchResult(
        String batchId,
        Instant batchTime,
        int scannedShortLinks,
        int generatedProfiles,
        List<RiskProfileBatchFailure> failures,
        Map<String, List<ShortLinkRiskProfile>> abnormalCandidatesByGid
) {

    public RiskProfileBatchResult {
        batchId = batchId == null ? "" : batchId;
        failures = failures == null ? List.of() : List.copyOf(failures);
        Map<String, List<ShortLinkRiskProfile>> copied = new LinkedHashMap<>();
        if (abnormalCandidatesByGid != null) {
            abnormalCandidatesByGid.forEach((gid, profiles) -> copied.put(
                    gid,
                    List.copyOf(profiles == null ? List.of() : profiles)
            ));
        }
        abnormalCandidatesByGid = Collections.unmodifiableMap(copied);
    }

    public RiskProfileBatchResult(
            Instant batchTime,
            int scannedShortLinks,
            int generatedProfiles,
            Map<String, List<ShortLinkRiskProfile>> abnormalCandidatesByGid
    ) {
        this(
                batchTime == null ? "" : "risk-profile:" + batchTime.getEpochSecond(),
                batchTime,
                scannedShortLinks,
                generatedProfiles,
                List.of(),
                abnormalCandidatesByGid
        );
    }

    public List<ShortLinkRiskProfile> abnormalCandidates() {
        List<ShortLinkRiskProfile> candidates = new ArrayList<>();
        abnormalCandidatesByGid.values().forEach(candidates::addAll);
        return List.copyOf(candidates);
    }
}
