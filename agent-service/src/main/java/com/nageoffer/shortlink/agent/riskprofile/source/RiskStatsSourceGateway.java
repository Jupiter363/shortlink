package com.nageoffer.shortlink.agent.riskprofile.source;

import java.time.Instant;
import java.util.List;

public interface RiskStatsSourceGateway {

    List<ShortLinkActiveCandidate> listActiveShortLinks(Instant since);

    ShortLinkStatsWindow loadStatsWindow(ShortLinkActiveCandidate candidate, Instant start, Instant end);
}
