package com.nageoffer.shortlink.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkGroupStatsAccessRecordReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkGroupStatsReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkPageReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkStatsReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsAccessRecordRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AgentToolInternalController {

    private final GroupService groupService;

    private final ShortLinkActualRemoteService shortLinkActualRemoteService;

    @GetMapping("/internal/short-link-admin/v1/agent-tools/groups")
    public Result<List<ShortLinkGroupRespDTO>> listGroups() {
        return Results.success(groupService.listGroup());
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/short-links/page")
    public Result<Page<ShortLinkPageRespDTO>> pageShortLinks(ShortLinkPageReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.pageShortLink(
                requestParam.getGid(),
                requestParam.getOrderTag(),
                requestParam.getCurrent(),
                requestParam.getSize()
        );
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/short-link/stats")
    public Result<ShortLinkStatsRespDTO> shortLinkStats(ShortLinkStatsReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.oneShortLinkStats(
                requestParam.getFullShortUrl(),
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate()
        );
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/group/stats")
    public Result<ShortLinkStatsRespDTO> groupStats(ShortLinkGroupStatsReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.groupShortLinkStats(
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate()
        );
    }

    @GetMapping("/internal/short-link-admin/v1/agent-tools/group/access-records")
    public Result<Page<ShortLinkStatsAccessRecordRespDTO>> groupAccessRecords(
            ShortLinkGroupStatsAccessRecordReqDTO requestParam) {
        requireOwnedGid(requestParam.getGid());
        return shortLinkActualRemoteService.groupShortLinkStatsAccessRecord(
                requestParam.getGid(),
                requestParam.getStartDate(),
                requestParam.getEndDate(),
                requestParam.getCurrent(),
                requestParam.getSize()
        );
    }

    private void requireOwnedGid(String gid) {
        Long groupCount = groupService.count(Wrappers.lambdaQuery(GroupDO.class)
                .eq(GroupDO::getUsername, UserContext.getUsername())
                .eq(GroupDO::getGid, gid)
                .eq(GroupDO::getDelFlag, 0));
        if (groupCount == null || groupCount < 1) {
            throw new ClientException("Agent tool request gid is not owned by current user");
        }
    }
}
