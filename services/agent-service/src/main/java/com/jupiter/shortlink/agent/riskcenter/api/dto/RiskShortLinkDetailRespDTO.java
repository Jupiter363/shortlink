package com.jupiter.shortlink.agent.riskcenter.api.dto;

import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;

import java.util.List;
import java.util.Map;

public record RiskShortLinkDetailRespDTO(
        RiskShortLinkCardRespDTO card,
        ShortLinkRiskMetrics metrics,
        Map<String, Object> latestSnapshot,
        List<RiskEventRespDTO> recentEvents
) {
}
