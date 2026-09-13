package com.jupiter.shortlink.admin.remote.analytics;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.dao.entity.GroupDO;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.service.GroupService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.List;

class ScheduledRiskProfileScopeServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final GroupService groups = mock(GroupService.class);
    private final ScheduledRiskProfileScopeService service = new ScheduledRiskProfileScopeService(users, groups, "localdev");

    @BeforeEach void setUp() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "scheduled-scope-test");
        TableInfoHelper.initTableInfo(assistant, UserDO.class);
        TableInfoHelper.initTableInfo(assistant, GroupDO.class);
        UserContext.setUser(new UserInfoDTO("1", "localdev", "Scheduler", 1L));
    }
    @AfterEach void cleanUp() { UserContext.removeUser(); }

    @Test void discoversDifferentTenantsWithoutMergingOwnership() {
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user(2, "Jupiter")), List.of(user(3, "alice")), List.of());
        when(groups.list(any(Wrapper.class))).thenReturn(List.of(group(11, 2, "Jupiter", "g-jupiter")), List.of(group(21, 3, "alice", "g-alice")));
        var first = service.discover("SYSTEM", null, 100);
        var second = service.discover("SYSTEM", first.nextCursor(), 100);
        var end = service.discover("SYSTEM", second.nextCursor(), 100);
        assertThat(first.items().get(0).tenantId()).isEqualTo("2");
        assertThat(first.items().get(0).gids()).containsExactly("g-jupiter");
        assertThat(second.items().get(0).tenantId()).isEqualTo("3");
        assertThat(second.items().get(0).gids()).containsExactly("g-alice");
        assertThat(end.items()).isEmpty();
        assertThat(end.nextCursor()).isNull();
        ArgumentCaptor<LambdaQueryWrapper<UserDO>> accountQueries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(users, times(3)).selectList(accountQueries.capture());
        for (var query : accountQueries.getAllValues())
            assertThat(query.getSqlSegment()).contains("disabled =", "auth_version >=", "id >", "ORDER BY id ASC", "LIMIT 1");
        ArgumentCaptor<LambdaQueryWrapper<GroupDO>> groupQueries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(groups, times(2)).list(groupQueries.capture());
        for (var query : groupQueries.getAllValues())
            assertThat(query.getSqlSegment()).contains("username =", "tenant_id =", "del_flag =", "id >", "LIMIT 101");
    }

    @Test void pagesGroupsByStableIdsAndThenMovesToNextUser() {
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user(2, "Jupiter")));
        when(groups.list(any(Wrapper.class))).thenReturn(
                List.of(group(11, 2, "Jupiter", "g1"), group(12, 2, "Jupiter", "g2")),
                List.of(group(12, 2, "Jupiter", "g2")));
        var first = service.discover("SYSTEM", null, 1);
        assertThat(first.items().get(0).gids()).containsExactly("g1");
        assertThat(first.nextCursor()).isEqualTo("2:11:1");
        var second = service.discover("SYSTEM", first.nextCursor(), 1);
        assertThat(second.items().get(0).gids()).containsExactly("g2");
        assertThat(second.nextCursor()).isEqualTo("2:0:0");
    }

    @Test void rejectsOrdinaryPrincipalsAndInvalidBudgetsBeforeDatabase() {
        assertThatThrownBy(() -> service.discover(null, null, 100)).hasMessageContaining("configured scheduler");
        UserContext.setUser(new UserInfoDTO("2", "Jupiter", "", 1L));
        assertThatThrownBy(() -> service.discover("SYSTEM", null, 100)).hasMessageContaining("configured scheduler");
        UserContext.setUser(new UserInfoDTO("1", "localdev", "", 1L));
        assertThatThrownBy(() -> service.discover("SYSTEM", null, 101)).hasMessageContaining("pageSize");
        assertThatThrownBy(() -> service.discover("SYSTEM", "2:11:0", 100)).hasMessageContaining("cursor");
        verifyNoInteractions(users, groups);
    }

    @Test void disabledOrChangedAccountIsRejectedAndRetryRestartsFromAuthority() {
        UserDO disabled = user(2, "Jupiter"); disabled.setDisabled(true);
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(disabled), List.of(user(2, "Jupiter")), List.of(user(2, "Jupiter")));
        assertThatThrownBy(() -> service.discover("SYSTEM", null, 100)).hasMessageContaining("account scope changed");
        assertThatThrownBy(() -> service.discover("SYSTEM", "2:11:2", 100)).hasMessageContaining("account scope changed");
        when(groups.list(any(Wrapper.class))).thenReturn(List.of(group(11, 2, "Jupiter", "g1")));
        assertThat(service.discover("SYSTEM", null, 100).items().get(0).gids()).containsExactly("g1");
    }

    @Test void crossTenantGroupIsRejected() {
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user(2, "Jupiter")));
        when(groups.list(any(Wrapper.class))).thenReturn(List.of(group(11, 3, "alice", "g1")));
        assertThatThrownBy(() -> service.discover("SYSTEM", null, 100)).hasMessageContaining("group ownership");
    }

    @Test void exactJobScopeReturnsCurrentOwnerAndRejectsTransferredOrDisabledOwnership() {
        UserDO disabled = user(2, "Jupiter"); disabled.setDisabled(true);
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(user(2, "Jupiter")), List.of(user(2, "Jupiter")), List.of(disabled));
        when(groups.count(any(Wrapper.class))).thenReturn(1L, 0L);
        var owner = service.resolve("SYSTEM", "2", "g1");
        assertThat(owner.username()).isEqualTo("Jupiter");
        assertThat(owner.gids()).containsExactly("g1");
        assertThatThrownBy(() -> service.resolve("SYSTEM", "2", "g1")).hasMessageContaining("persisted tenant");
        assertThatThrownBy(() -> service.resolve("SYSTEM", "2", "g1")).hasMessageContaining("account is unavailable");
        assertThatThrownBy(() -> service.resolve("USER", "2", "g1")).hasMessageContaining("configured scheduler");
    }

    private static UserDO user(long id, String username) {
        UserDO user = new UserDO(); user.setId(id); user.setUsername(username); user.setAuthVersion(1L);
        user.setDisabled(false); user.setDelFlag(0); return user;
    }
    private static GroupDO group(long id, long tenantId, String username, String gid) {
        GroupDO group = GroupDO.builder().id(id).tenantId(tenantId).username(username).gid(gid).build();
        group.setDelFlag(0); return group;
    }
}
