package com.jupiter.shortlink.agent.riskprofile.source;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface RiskStatsSourceGateway {

    List<ShortLinkActiveCandidate> listActiveShortLinks(Instant since);

    ShortLinkStatsWindow loadStatsWindow(
            ShortLinkActiveCandidate candidate, Instant start, Instant end);

    default Map<String, ShortLinkStatsWindow> loadStatsWindows(
            ShortLinkActiveCandidate candidate, Instant end) {
        return Map.of(
                "2h",
                loadStatsWindow(candidate, end.minus(Duration.ofHours(2)), end),
                "24h",
                loadStatsWindow(candidate, end.minus(Duration.ofHours(24)), end),
                "7d",
                loadStatsWindow(candidate, end.minus(Duration.ofDays(7)), end));
    }
}
