package com.nageoffer.shortlink.agent.riskprofile.service;

import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class RiskProfileCandidateSelector {

    private static final Comparator<ShortLinkRiskProfile> CANDIDATE_ORDER = Comparator
            .comparingInt(ShortLinkRiskProfile::riskScore)
            .reversed()
            .thenComparing(
                    profile -> profile.metrics() == null ? 0 : profile.metrics().pv2h(),
                    Comparator.reverseOrder()
            )
            .thenComparing(ShortLinkRiskProfile::domain)
            .thenComparing(ShortLinkRiskProfile::shortUri);

    private RiskProfileCandidateSelector() {
    }

    public static List<ShortLinkRiskProfile> top(List<ShortLinkRiskProfile> profiles, int limit) {
        if (profiles == null || profiles.isEmpty() || limit <= 0) {
            return List.of();
        }
        return profiles.stream()
                .filter(Objects::nonNull)
                .sorted(CANDIDATE_ORDER)
                .limit(limit)
                .toList();
    }
}
