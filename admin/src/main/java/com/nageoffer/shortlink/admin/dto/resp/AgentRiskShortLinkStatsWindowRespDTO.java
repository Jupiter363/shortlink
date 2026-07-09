package com.nageoffer.shortlink.admin.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRiskShortLinkStatsWindowRespDTO {

    private String gid;

    private String domain;

    private String shortUri;

    private String fullShortUrl;

    private String startTime;

    private String endTime;

    private Integer pv;

    private Integer uv;

    private Integer uip;

    private Double topIpShare;

    private Double topVisitorShare;

    private Double topRegionShare;

    private Double topDeviceShare;

    private Double topBrowserShare;

    private Double peakHourShare;

    private Double repeatVisitRatio;
}
