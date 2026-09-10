package com.jupiter.shortlink.command.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.security.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

class CommittedCreateResultControllerTest {
    @Test
    void onlyTenantScopedCommittedReceiptReadAndCurrentAccountRechecked() {
        var auth = mock(CommandAuthorization.class);
        var jdbc = mock(JdbcTemplate.class);
        var request = new MockHttpServletRequest();
        var p = new CommandPrincipal(1001, "alice", 7);
        when(auth.principal(request)).thenReturn(p);
        when(jdbc.queryForList(
                        "SELECT result_json FROM t_command_result WHERE tenant_id=? AND"
                                + " command_id=?",
                        1001L,
                        "create:one"))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "result_json",
                                        "[{\"linkId\":4000000001,\"fullShortUrl\":\"https://s/a\",\"originUrl\":\"https://x/a\",\"gid\":\"g\",\"shortUri\":\"a\",\"routeVersion\":1,\"targetRevision\":1}]")));
        var result =
                new CommittedCreateResultController(auth, jdbc, new ObjectMapper())
                        .result("one", request);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).linkId()).isEqualTo(4000000001L);
        verify(auth).check(p, false);
        verify(jdbc)
                .queryForList(
                        "SELECT result_json FROM t_command_result WHERE tenant_id=? AND"
                                + " command_id=?",
                        1001L,
                        "create:one");
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void MissingReceiptIs404AndNeverRecreates() {
        var auth = mock(CommandAuthorization.class);
        var jdbc = mock(JdbcTemplate.class);
        var request = new MockHttpServletRequest();
        when(auth.principal(request)).thenReturn(new CommandPrincipal(2002, "bob", 1));
        when(jdbc.queryForList(anyString(), eq(2002L), eq("create:other"))).thenReturn(List.of());
        assertThatThrownBy(
                        () ->
                                new CommittedCreateResultController(auth, jdbc, new ObjectMapper())
                                        .result("other", request))
                .hasMessageContaining("404");
        verify(jdbc).queryForList(anyString(), eq(2002L), eq("create:other"));
        verifyNoMoreInteractions(jdbc);
    }
}
