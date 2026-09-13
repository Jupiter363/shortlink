package com.jupiter.shortlink.admin.remote.analytics;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.dao.entity.GroupDO;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.service.GroupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/** Enumerates bounded, explicit tenant/group scopes for the configured scheduler only. */
@Service
public class ScheduledRiskProfileScopeService {
    private final UserMapper users;
    private final GroupService groups;
    private final String schedulerUsername;

    public ScheduledRiskProfileScopeService(UserMapper users, GroupService groups,
            @Value("${shortlink.agent.system-username:}") String schedulerUsername) {
        this.users = users;
        this.groups = groups;
        this.schedulerUsername = schedulerUsername;
    }

    public ScopePage discover(String mode, String cursor, int pageSize) {
        requireScheduler(mode);
        if (pageSize < 1 || pageSize > 100)
            throw new ClientException("Scheduled scope pageSize must be between 1 and 100");
        Cursor cut = Cursor.parse(cursor);
        return discover(cut, pageSize);
    }

    private void requireScheduler(String mode) {
        if (!"SYSTEM".equals(mode) || schedulerUsername.isBlank()
                || !schedulerUsername.equals(UserContext.getUsername())
                || UserContext.getUserId() == null || UserContext.getAuthVersion() == null)
            throw new ClientException("Only the authenticated configured scheduler may discover tenant scopes");
    }

    /** A queued job may only rebind to the same tenant that owns its persisted profile. */
    public TenantScope resolve(String mode, String tenantId, String gid) {
        requireScheduler(mode);
        long id;
        try {
            if (tenantId == null || !tenantId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            id = Long.parseLong(tenantId);
            if (id < 1 || gid == null || !gid.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ClientException("An exact scheduled tenant and group are required");
        }
        List<UserDO> accounts = users.selectList(Wrappers.lambdaQuery(UserDO.class)
                .select(UserDO::getId, UserDO::getUsername, UserDO::getAuthVersion, UserDO::getDisabled, UserDO::getDelFlag)
                .eq(UserDO::getId, id).eq(UserDO::getDelFlag, 0).eq(UserDO::getDisabled, false)
                .ge(UserDO::getAuthVersion, 1L).last("LIMIT 2"));
        if (accounts.size() != 1) throw new ClientException("Scheduled account is unavailable");
        UserDO account = accounts.get(0);
        if (!Long.valueOf(id).equals(account.getId()) || !Integer.valueOf(0).equals(account.getDelFlag())
                || !Boolean.FALSE.equals(account.getDisabled()) || account.getAuthVersion() == null
                || account.getAuthVersion() < 1 || account.getUsername() == null
                || !account.getUsername().matches("[A-Za-z0-9_-]{3,64}"))
            throw new ClientException("Scheduled account is unavailable");
        if (groups.count(Wrappers.lambdaQuery(GroupDO.class)
                .eq(GroupDO::getTenantId, id).eq(GroupDO::getUsername, account.getUsername())
                .eq(GroupDO::getGid, gid).eq(GroupDO::getDelFlag, 0)) != 1)
            throw new ClientException("Scheduled group no longer belongs to the persisted tenant");
        return new TenantScope(tenantId, account.getUsername(), account.getAuthVersion(), List.of(gid));
    }

    private ScopePage discover(Cursor cut, int pageSize) {
        var query = Wrappers.lambdaQuery(UserDO.class)
                .select(UserDO::getId, UserDO::getUsername, UserDO::getAuthVersion, UserDO::getDisabled, UserDO::getDelFlag)
                .eq(UserDO::getDelFlag, 0).eq(UserDO::getDisabled, false)
                .ge(UserDO::getAuthVersion, 1L);
        if (cut.groupId() > 0) query.eq(UserDO::getId, cut.userId());
        else query.gt(UserDO::getId, cut.userId());
        List<UserDO> found = users.selectList(query.orderByAsc(UserDO::getId).last("LIMIT 1"));
        if (found.isEmpty()) {
            if (cut.groupId() > 0) throw new ClientException("Scheduled account scope changed; restart discovery");
            return new ScopePage(List.of(), null);
        }
        UserDO account = found.get(0);
        // Defense in depth: never delegate a stale/disabled account even if a mapper is misconfigured.
        if (!Integer.valueOf(0).equals(account.getDelFlag()) || !Boolean.FALSE.equals(account.getDisabled())
                || account.getId() == null || account.getAuthVersion() == null || account.getAuthVersion() < 1
                || account.getUsername() == null || !account.getUsername().matches("[A-Za-z0-9_-]{3,64}")
                || (cut.groupId() > 0 && (account.getId() != cut.userId() || account.getAuthVersion() != cut.authVersion())))
            throw new ClientException("Scheduled account scope changed; restart discovery");
        List<GroupDO> owned = groups.list(Wrappers.lambdaQuery(GroupDO.class)
                .select(GroupDO::getId, GroupDO::getGid, GroupDO::getTenantId, GroupDO::getUsername, GroupDO::getDelFlag)
                .eq(GroupDO::getUsername, account.getUsername()).eq(GroupDO::getTenantId, account.getId())
                .eq(GroupDO::getDelFlag, 0).gt(GroupDO::getId, cut.groupId())
                .orderByAsc(GroupDO::getId).last("LIMIT " + (pageSize + 1)));
        for (GroupDO group : owned) {
            if (!account.getId().equals(group.getTenantId()) || !account.getUsername().equals(group.getUsername())
                    || !Integer.valueOf(0).equals(group.getDelFlag()) || group.getId() == null || group.getId() <= cut.groupId()
                    || group.getGid() == null || group.getGid().isBlank())
                throw new ClientException("Scheduled group ownership is invalid");
        }
        boolean moreGroups = owned.size() > pageSize;
        List<GroupDO> page = owned.subList(0, Math.min(owned.size(), pageSize));
        String next = moreGroups
                ? account.getId() + ":" + page.get(page.size() - 1).getId() + ":" + account.getAuthVersion()
                : account.getId() + ":0:0";
        return new ScopePage(List.of(new TenantScope(account.getId().toString(), account.getUsername(),
                account.getAuthVersion(), page.stream().map(GroupDO::getGid).toList())), next);
    }

    public record TenantScope(String tenantId, String username, long authVersion, List<String> gids) {}
    public record ScopePage(List<TenantScope> items, String nextCursor) {}

    private record Cursor(long userId, long groupId, long authVersion) {
        static Cursor parse(String value) {
            if (value == null || value.isBlank()) return new Cursor(0, 0, 0);
            try {
                if (!value.matches("[1-9][0-9]{0,18}:[0-9]{1,19}:[0-9]{1,19}")) throw new IllegalArgumentException();
                String[] parts = value.split(":");
                Cursor cursor = new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
                if (cursor.userId <= 0 || cursor.groupId < 0 || cursor.authVersion < 0
                        || (cursor.groupId == 0) != (cursor.authVersion == 0)) throw new IllegalArgumentException();
                return cursor;
            } catch (IllegalArgumentException invalid) {
                throw new ClientException("Invalid scheduled scope cursor");
            }
        }
    }
}
