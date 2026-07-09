package com.nageoffer.shortlink.agent.riskcenter.api;

import com.nageoffer.shortlink.agent.common.result.Result;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskEventQueryReqDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskEventRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskPolicyDisableReqDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskReviewReqDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskReviewRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskShortLinkDetailRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RiskCenterInternalController {

    private final RiskCenterService riskCenterService;

    public RiskCenterInternalController(RiskCenterService riskCenterService) {
        this.riskCenterService = riskCenterService;
    }

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/overview")
    public Result<RiskGroupOverviewRespDTO> groupOverview(@PathVariable("gid") String gid) {
        return Result.success(riskCenterService.getGroupOverview(gid));
    }

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/short-links")
    public Result<List<RiskShortLinkCardRespDTO>> groupShortLinks(@PathVariable("gid") String gid) {
        return Result.success(riskCenterService.listGroupShortLinkCards(gid));
    }

    @GetMapping("/internal/short-link-agent/v1/risk/short-links/{domain}/{shortUri}")
    public Result<RiskShortLinkDetailRespDTO> shortLinkDetail(
            @PathVariable("domain") String domain,
            @PathVariable("shortUri") String shortUri
    ) {
        return Result.success(riskCenterService.getShortLinkRisk(domain, shortUri));
    }

    @GetMapping("/internal/short-link-agent/v1/risk/events")
    public Result<PageResult<RiskEventRespDTO>> events(
            @RequestParam(value = "gid", required = false) String gid,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "shortUri", required = false) String shortUri,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize
    ) {
        return Result.success(riskCenterService.listEvents(new RiskEventQueryReqDTO(
                gid,
                targetType,
                domain,
                shortUri,
                pageNo,
                pageSize
        )));
    }

    @PostMapping("/internal/short-link-agent/v1/risk/reviews")
    public Result<RiskReviewRespDTO> review(@RequestBody RiskReviewReqDTO request) {
        return Result.success(riskCenterService.submitReview(request));
    }

    @PostMapping("/internal/short-link-agent/v1/risk/policies/{policyId}/disable")
    public Result<Map<String, Object>> disablePolicy(
            @PathVariable("policyId") String policyId,
            @RequestBody RiskPolicyDisableReqDTO request
    ) {
        riskCenterService.disablePolicy(policyId, request.reviewer(), request.reason(), request.traceId());
        return Result.success(Map.of("policyId", policyId, "disabled", true));
    }
}
