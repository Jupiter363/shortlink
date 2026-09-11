package com.jupiter.shortlink.admin.common.biz.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;

import org.springframework.stereotype.Component;

@Component
public class TrustedManagementIdentity {
    private final UserMapper users;

    public TrustedManagementIdentity(UserMapper users) {
        this.users = users;
    }

    public UserInfoDTO verify(String tenant, String username, String version) {
        if (tenant == null
                || username == null
                || version == null
                || username.isBlank()
                || username.length() > 64) throw new IllegalArgumentException("Missing principal");
        long id = Long.parseLong(tenant), v = Long.parseLong(version);
        if (id <= 0 || v < 1) throw new IllegalArgumentException("Invalid principal");
        UserDO user =
                users.selectOne(
                        new LambdaQueryWrapper<UserDO>()
                                .eq(UserDO::getUsername, username)
                                .eq(UserDO::getId, id));
        if (user == null
                || !Long.valueOf(v).equals(user.getAuthVersion())
                || !Boolean.FALSE.equals(user.getDisabled())
                || !Integer.valueOf(0).equals(user.getDelFlag()))
            throw new IllegalArgumentException("Principal revoked");
        return new UserInfoDTO(Long.toString(id), username, null, v);
    }
}
