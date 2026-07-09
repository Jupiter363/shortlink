package com.nageoffer.shortlink.admin.remote.dto.req;

import lombok.Data;

@Data
public class RiskPolicyDisableReqDTO {

    private String gid;

    private String reviewer;

    private String reason;

    private String traceId;
}
