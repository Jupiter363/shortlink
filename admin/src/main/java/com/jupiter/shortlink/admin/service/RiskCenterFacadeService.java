package com.jupiter.shortlink.admin.service;

import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.remote.dto.req.RiskPolicyDisableReqDTO;
import com.jupiter.shortlink.admin.remote.dto.req.RiskReviewReqDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.RiskPageRespDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.RiskReviewRespDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.RiskShortLinkCardRespDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.RiskShortLinkDetailRespDTO;

import java.util.List;
import java.util.Map;

public interface RiskCenterFacadeService {

    Result<RiskGroupOverviewRespDTO> groupOverview(String gid);

    Result<List<RiskShortLinkCardRespDTO>> groupShortLinks(String gid);

    Result<RiskShortLinkDetailRespDTO> shortLinkDetail(String gid, String domain, String shortUri);

    Result<RiskPageRespDTO<?>> events(
            String gid,
            String targetType,
            String domain,
            String shortUri,
            Integer pageNo,
            Integer pageSize);

    Result<RiskReviewRespDTO> review(RiskReviewReqDTO requestParam);

    Result<Map<String, Object>> disablePolicy(String policyId, RiskPolicyDisableReqDTO requestParam);
}
