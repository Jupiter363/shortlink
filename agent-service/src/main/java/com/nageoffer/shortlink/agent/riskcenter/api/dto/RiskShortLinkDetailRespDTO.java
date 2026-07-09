package com.nageoffer.shortlink.agent.riskcenter.api.dto;

import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;

import java.util.List;
import java.util.Map;

public record RiskShortLinkDetailRespDTO(
        RiskShortLinkCardRespDTO card,
        ShortLinkRiskMetrics metrics,
        Map<String, Object> latestSnapshot,
        List<RiskEventRespDTO> recentEvents
) {
}
