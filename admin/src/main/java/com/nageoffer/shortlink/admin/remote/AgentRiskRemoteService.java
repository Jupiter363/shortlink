package com.nageoffer.shortlink.admin.remote;

import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskPolicyDisableReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskReviewReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskReviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkDetailRespDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(value = "short-link-agent", url = "${short-link.agent.admin.remote-url:}")
public interface AgentRiskRemoteService {

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/overview")
    Result<RiskGroupOverviewRespDTO> groupOverview(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @PathVariable("gid") String gid);

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/short-links")
    Result<List<RiskShortLinkCardRespDTO>> groupShortLinks(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @PathVariable("gid") String gid);

    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/short-links/{domain}/{shortUri}")
    Result<RiskShortLinkDetailRespDTO> shortLinkDetail(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @PathVariable("gid") String gid,
            @PathVariable("domain") String domain,
            @PathVariable("shortUri") String shortUri);

    @GetMapping("/internal/short-link-agent/v1/risk/events")
    Result<RiskPageRespDTO<?>> events(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @RequestParam(value = "gid", required = false) String gid,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "shortUri", required = false) String shortUri,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize);

    @PostMapping("/internal/short-link-agent/v1/risk/reviews")
    Result<RiskReviewRespDTO> review(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @RequestBody RiskReviewReqDTO requestParam);

    @PostMapping("/internal/short-link-agent/v1/risk/policies/{policyId}/disable")
    Result<Map<String, Object>> disablePolicy(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @PathVariable("policyId") String policyId,
            @RequestBody RiskPolicyDisableReqDTO requestParam);
}
