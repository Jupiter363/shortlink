package com.nageoffer.shortlink.admin.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRiskActiveShortLinkRespDTO {

    private String gid;

    private String domain;

    private String shortUri;

    private String fullShortUrl;

    private Integer pv;

    private Integer uv;

    private Integer uip;
}
