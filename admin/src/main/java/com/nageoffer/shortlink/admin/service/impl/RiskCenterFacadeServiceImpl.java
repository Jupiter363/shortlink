package com.nageoffer.shortlink.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.remote.AgentRiskRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskPolicyDisableReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskReviewReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskReviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkDetailRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import com.nageoffer.shortlink.admin.service.RiskCenterFacadeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RiskCenterFacadeServiceImpl implements RiskCenterFacadeService {

    private final AgentRiskRemoteService agentRiskRemoteService;

    private final AgentAdminConfiguration agentAdminConfiguration;

    private final GroupService groupService;

    @Override
    public Result<RiskGroupOverviewRespDTO> groupOverview(String gid) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        return agentRiskRemoteService.groupOverview(
                internalToken(),
                user.username(),
                user.userId(),
                user.realName(),
                gid
        );
    }

    @Override
    public Result<List<RiskShortLinkCardRespDTO>> groupShortLinks(String gid) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        return agentRiskRemoteService.groupShortLinks(
                internalToken(),
                user.username(),
                user.userId(),
                user.realName(),
                gid
        );
    }

    @Override
    public Result<RiskShortLinkDetailRespDTO> shortLinkDetail(String gid, String domain, String shortUri) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        Result<RiskShortLinkDetailRespDTO> result = agentRiskRemoteService.shortLinkDetail(
                internalToken(),
                user.username(),
                user.userId(),
                user.realName(),
                gid,
                domain,
                shortUri
        );
        if (result != null && result.isSuccess()) {
            if (result.getData() == null || result.getData().getCard() == null) {
                throw new ClientException("Risk center request gid is not owned by current user");
            }
            requireReturnedGid(gid, result.getData().getCard().getGid());
        }
        return result;
    }

    @Override
    public Result<RiskPageRespDTO<?>> events(
            String gid,
            String targetType,
            String domain,
            String shortUri,
            Integer pageNo,
            Integer pageSize) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        return agentRiskRemoteService.events(
                internalToken(),
                user.username(),
                user.userId(),
                user.realName(),
                gid,
                targetType,
                domain,
                shortUri,
                pageNo,
                pageSize
        );
    }

    @Override
    public Result<RiskReviewRespDTO> review(RiskReviewReqDTO requestParam) {
        TrustedUser user = trustedUser();
        RiskReviewReqDTO request = Optional.ofNullable(requestParam).orElseGet(RiskReviewReqDTO::new);
        requireOwnedGid(request.getGid());
        request.setReviewer(user.username());
        return agentRiskRemoteService.review(
                internalToken(),
                user.username(),
                user.userId(),
                user.realName(),
                request
        );
    }

    @Override
    public Result<Map<String, Object>> disablePolicy(String policyId, RiskPolicyDisableReqDTO requestParam) {
        TrustedUser user = trustedUser();
        RiskPolicyDisableReqDTO request = Optional.ofNullable(requestParam).orElseGet(RiskPolicyDisableReqDTO::new);
        requireOwnedGid(request.getGid());
        request.setReviewer(user.username());
        return agentRiskRemoteService.disablePolicy(
                internalToken(),
                user.username(),
                user.userId(),
                user.realName(),
                policyId,
                request
        );
    }

    private TrustedUser trustedUser() {
        String username = UserContext.getUsername();
        if (!StringUtils.hasText(username)) {
            throw new ClientException("Risk center request requires authenticated user context");
        }
        return new TrustedUser(username, UserContext.getUserId(), UserContext.getRealName());
    }

    private void requireOwnedGid(String gid) {
        if (!StringUtils.hasText(gid)) {
            throw new ClientException("Risk center request gid is not owned by current user");
        }
        Long groupCount = groupService.count(Wrappers.lambdaQuery(GroupDO.class)
                .eq(GroupDO::getUsername, UserContext.getUsername())
                .eq(GroupDO::getGid, gid)
                .eq(GroupDO::getDelFlag, 0));
        if (groupCount == null || groupCount < 1) {
            throw new ClientException("Risk center request gid is not owned by current user");
        }
    }

    private void requireReturnedGid(String expectedGid, String returnedGid) {
        if (!expectedGid.equals(returnedGid)) {
            throw new ClientException("Risk center request gid is not owned by current user");
        }
    }

    private String internalToken() {
        return Optional.ofNullable(agentAdminConfiguration.getInternalToken()).orElse("");
    }

    private record TrustedUser(String username, String userId, String realName) {
    }
}
