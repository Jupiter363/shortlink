package com.jupiter.shortlink.agent.riskcenter.api;

import com.jupiter.shortlink.agent.common.result.Result;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskEventQueryReqDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskEventRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskGroupOverviewRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskPolicyDisableReqDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskReviewReqDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskReviewRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskShortLinkCardRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskShortLinkDetailRespDTO;
import com.jupiter.shortlink.agent.riskcenter.service.RiskCenterService;
import com.jupiter.shortlink.agent.riskcenter.service.RiskCenterService.PageResult;

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
    private com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal(
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            return new com.jupiter.shortlink.agent.harness.security.AgentPrincipal(
                    request.getHeader("X-Agent-UserId"),
                    request.getHeader("X-Agent-Username"),
                    Long.parseLong(request.getHeader("X-Agent-Auth-Version")),
                    false);
        } catch (IllegalArgumentException failure) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Trusted identity is required");
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SecurityException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.FORBIDDEN)
    public Map<String, Object> forbidden() {
        return Map.of("success", false, "code", "RESOURCE_UNAVAILABLE");
    }

    private final RiskCenterService riskCenterService;

    public RiskCenterInternalController(RiskCenterService riskCenterService) {
        this.riskCenterService = riskCenterService;
    }

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/overview")
    public Result<RiskGroupOverviewRespDTO> groupOverview(
            @PathVariable("gid") String gid, jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(riskCenterService.getGroupOverview(principal(request), gid));
    }

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/short-links")
    public Result<List<RiskShortLinkCardRespDTO>> groupShortLinks(
            @PathVariable("gid") String gid, jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(riskCenterService.listGroupShortLinkCards(principal(request), gid));
    }

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/short-links/{domain}/{shortUri}")
    public Result<RiskShortLinkDetailRespDTO> shortLinkDetail(
            @PathVariable("gid") String gid,
            @PathVariable("domain") String domain,
            @PathVariable("shortUri") String shortUri,
            jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(
                riskCenterService.getShortLinkRisk(principal(request), gid, domain, shortUri));
    }

    @GetMapping("/internal/short-link-agent/v1/risk/events")
    public Result<PageResult<RiskEventRespDTO>> events(
            @RequestParam(value = "gid", required = false) String gid,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "shortUri", required = false) String shortUri,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(
                riskCenterService.listEvents(
                        principal(request),
                        new RiskEventQueryReqDTO(
                                gid, targetType, domain, shortUri, pageNo, pageSize)));
    }

    @PostMapping("/internal/short-link-agent/v1/risk/reviews")
    public Result<RiskReviewRespDTO> review(
            @RequestBody RiskReviewReqDTO request,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        return Result.success(riskCenterService.submitReview(principal(servletRequest), request));
    }

    @PostMapping("/internal/short-link-agent/v1/risk/policies/{policyId}/disable")
    public Result<Map<String, Object>> disablePolicy(
            @PathVariable("policyId") String policyId,
            @RequestBody RiskPolicyDisableReqDTO request) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE,
                "Use the authorized Admin Command revocation endpoint");
    }
}
