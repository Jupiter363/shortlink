package com.nageoffer.shortlink.admin.controller;

import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskPolicyDisableReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskReviewReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskReviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkDetailRespDTO;
import com.nageoffer.shortlink.admin.service.RiskCenterFacadeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class RiskCenterController {

    private final RiskCenterFacadeService riskCenterFacadeService;

    @GetMapping("/api/short-link/admin/v1/risk/groups/{gid}/overview")
    public Result<RiskGroupOverviewRespDTO> groupOverview(@PathVariable("gid") String gid) {
        return riskCenterFacadeService.groupOverview(gid);
    }

    @GetMapping("/api/short-link/admin/v1/risk/groups/{gid}/short-links")
    public Result<List<RiskShortLinkCardRespDTO>> groupShortLinks(@PathVariable("gid") String gid) {
        return riskCenterFacadeService.groupShortLinks(gid);
    }

    @GetMapping("/api/short-link/admin/v1/risk/short-links")
    public Result<RiskShortLinkDetailRespDTO> shortLinkDetail(
            @RequestParam("gid") String gid,
            @RequestParam("domain") String domain,
            @RequestParam("shortUri") String shortUri) {
        return riskCenterFacadeService.shortLinkDetail(gid, domain, shortUri);
    }

    @GetMapping("/api/short-link/admin/v1/risk/events")
    public Result<RiskPageRespDTO<?>> events(
            @RequestParam("gid") String gid,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "shortUri", required = false) String shortUri,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        return riskCenterFacadeService.events(gid, targetType, domain, shortUri, pageNo, pageSize);
    }

    @PostMapping("/api/short-link/admin/v1/risk/reviews")
    public Result<RiskReviewRespDTO> review(@RequestBody RiskReviewReqDTO requestParam) {
        return riskCenterFacadeService.review(requestParam);
    }

    @PostMapping("/api/short-link/admin/v1/risk/policies/{policyId}/disable")
    public Result<Map<String, Object>> disablePolicy(
            @PathVariable("policyId") String policyId,
            @RequestBody RiskPolicyDisableReqDTO requestParam) {
        return riskCenterFacadeService.disablePolicy(policyId, requestParam);
    }
}
