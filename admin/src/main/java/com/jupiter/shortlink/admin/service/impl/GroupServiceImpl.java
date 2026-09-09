package com.jupiter.shortlink.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.dao.entity.GroupDO;
import com.jupiter.shortlink.admin.dao.mapper.GroupMapper;
import com.jupiter.shortlink.admin.dto.req.*;
import com.jupiter.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.jupiter.shortlink.admin.remote.GroupCommandRemoteService;
import com.jupiter.shortlink.admin.service.GroupService;

import org.springframework.stereotype.Service;

import java.util.*;

/** All group lifecycle decisions are committed by Command in the link business database. */
@Service
public class GroupServiceImpl extends ServiceImpl<GroupMapper, GroupDO> implements GroupService {
    private final GroupCommandRemoteService command;

    public GroupServiceImpl(GroupCommandRemoteService command) {
        this.command = command;
    }

    private void requirePrincipal() {
        if (UserContext.getUserId() == null || UserContext.getAuthVersion() == null)
            throw new ClientException("Authenticated account required");
    }

    @Override
    public void saveGroup(String name) {
        requirePrincipal();
        command.create(new GroupCommandRemoteService.Input(null, name));
    }

    @Override
    public void saveGroup(String username, String name) {
        requirePrincipal();
        if (!Objects.equals(username, UserContext.getUsername()))
            throw new ClientException("Cannot create another account's group");
        saveGroup(name);
    }

    @Override
    public List<ShortLinkGroupRespDTO> listGroup() {
        requirePrincipal();
        var groups = command.list();
        if (groups == null) throw new IllegalStateException("Command returned no group result");
        return groups.stream()
                .map(
                        g -> {
                            var dto = new ShortLinkGroupRespDTO();
                            dto.setGid(g.gid());
                            dto.setName(g.name());
                            dto.setSortOrder(g.sortOrder());
                            dto.setShortLinkCount(g.linkCount());
                            return dto;
                        })
                .toList();
    }

    @Override
    public void updateGroup(ShortLinkGroupUpdateReqDTO q) {
        requirePrincipal();
        command.rename(new GroupCommandRemoteService.Input(q.getGid(), q.getName()));
    }

    @Override
    public void deleteGroup(String gid) {
        requirePrincipal();
        command.delete(gid);
    }

    @Override
    public void sortGroup(List<ShortLinkGroupSortReqDTO> input) {
        requirePrincipal();
        if (input == null || input.size() > 20)
            throw new ClientException("Group ordering exceeds budget");
        Map<String, Integer> order = new LinkedHashMap<>();
        for (var q : input)
            if (order.put(q.getGid(), q.getSortOrder()) != null)
                throw new ClientException("Duplicate group");
        command.order(order);
    }
}
