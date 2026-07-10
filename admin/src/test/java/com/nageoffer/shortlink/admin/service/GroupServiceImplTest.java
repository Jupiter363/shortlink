package com.nageoffer.shortlink.admin.service;

import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.common.convention.exception.RemoteException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.dao.mapper.GroupMapper;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkGroupCountQueryRespDTO;
import com.nageoffer.shortlink.admin.service.impl.GroupServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupServiceImplTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void listGroupFailsWhenProjectCountQueryReturnsBusinessFailure() {
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        GroupMapper groupMapper = mock(GroupMapper.class);
        GroupServiceImpl service = new GroupServiceImpl(remoteService, mock(RedissonClient.class));
        ReflectionTestUtils.setField(service, "baseMapper", groupMapper);
        UserContext.setUser(UserInfoDTO.builder().username("zhangsan").build());
        when(groupMapper.selectList(any())).thenReturn(List.of(GroupDO.builder()
                .gid("g1")
                .name("group-1")
                .username("zhangsan")
                .build()));
        when(remoteService.listGroupShortLinkCount(List.of("g1")))
                .thenReturn(new Result<List<ShortLinkGroupCountQueryRespDTO>>()
                        .setCode("B000001")
                        .setMessage("project count query failed"));

        assertThatThrownBy(service::listGroup)
                .isInstanceOf(RemoteException.class)
                .hasMessage("Group short link count request failed");
    }
}
