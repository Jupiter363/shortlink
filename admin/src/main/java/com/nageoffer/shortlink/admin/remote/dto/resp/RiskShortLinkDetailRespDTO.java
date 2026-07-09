package com.nageoffer.shortlink.admin.remote.dto.resp;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RiskShortLinkDetailRespDTO {

    private RiskShortLinkCardRespDTO card;

    private Map<String, Object> metrics;

    private Map<String, Object> latestSnapshot;

    private List<RiskEventRespDTO> recentEvents;
}
