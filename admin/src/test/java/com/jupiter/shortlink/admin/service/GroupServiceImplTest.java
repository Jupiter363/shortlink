package com.jupiter.shortlink.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.admin.common.biz.user.*;
import com.jupiter.shortlink.admin.remote.GroupCommandRemoteService;
import com.jupiter.shortlink.admin.service.impl.GroupServiceImpl;

import org.junit.jupiter.api.*;

import java.util.List;

class GroupServiceImplTest {
    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    @Test
    void commandFailureIsNotConvertedIntoAnEmptyGroupList() {
        var command = mock(GroupCommandRemoteService.class);
        var service = new GroupServiceImpl(command);
        UserContext.setUser(new UserInfoDTO("101", "alice", null, 1L));
        when(command.list()).thenThrow(new IllegalStateException("Command unavailable"));
        assertThatThrownBy(service::listGroup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Command unavailable");
    }

    @Test
    void anotherUsernameCannotChooseTheWritePrincipal() {
        var command = mock(GroupCommandRemoteService.class);
        var service = new GroupServiceImpl(command);
        UserContext.setUser(new UserInfoDTO("101", "alice", null, 1L));
        assertThatThrownBy(() -> service.saveGroup("bob", "foreign"))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(command);
    }

    @Test
    void countsArePreservedBeyondIntegerRange() {
        var command = mock(GroupCommandRemoteService.class);
        var service = new GroupServiceImpl(command);
        UserContext.setUser(new UserInfoDTO("101", "alice", null, 1L));
        when(command.list())
                .thenReturn(
                        List.of(
                                new GroupCommandRemoteService.Group(
                                        "g", "group", 0, 3000000000L, 0, 1)));
        assertThat(service.listGroup().get(0).getShortLinkCount()).isEqualTo(3000000000L);
    }
}
