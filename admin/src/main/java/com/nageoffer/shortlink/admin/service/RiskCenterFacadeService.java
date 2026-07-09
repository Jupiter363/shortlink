package com.nageoffer.shortlink.admin.service;

import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskPolicyDisableReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskReviewReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskReviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkDetailRespDTO;

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
