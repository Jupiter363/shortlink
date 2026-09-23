package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignCurrentPrincipalResolver;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignConversationSessionOwnerTest {
    private static final String SESSION = "console-session-1";
    private static final AgentPrincipal ALICE = new AgentPrincipal("1001", "alice", 7, false);

    @Test
    void trustedBindingIsIdempotentAndRejectsAnotherAccountBeforeAnyRunExists() {
        var store = fixture();
        store.bindVerified(ALICE, SESSION);
        store.bindVerified(ALICE, SESSION);
        store.requireOwner(new Caller("1001", "alice", 7), SESSION);
        assertThatThrownBy(() -> store.bindVerified(new AgentPrincipal("1002", "bob", 7, false), SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_SESSION_OWNER_CHANGED");
        assertThatThrownBy(() -> store.requireOwner(new Caller("1002", "bob", 7), SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_SESSION_OWNER_CHANGED");
        store.requireOwner(new Caller("1001", "alice", 7), SESSION);
    }

    @Test
    void newerVerifiedAccountVersionFencesOldRunAndCannotBeDowngraded() {
        var store = fixture();
        store.bindVerified(ALICE, SESSION);
        var newer = new AgentPrincipal("1001", "alice", 8, false);
        store.bindVerified(newer, SESSION);
        store.requireOwner(new Caller("1001", "alice", 8), SESSION);
        assertThatThrownBy(() -> store.requireOwner(new Caller("1001", "alice", 7), SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_SESSION_OWNER_CHANGED");
        assertThatThrownBy(() -> store.bindVerified(ALICE, SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_SESSION_OWNER_CHANGED");
    }

    @Test
    void recoveryCannotCreateAClaimAndMalformedSessionsAreRejected() {
        var store = fixture();
        assertThatThrownBy(() -> store.requireOwner(new Caller("1001", "alice", 7), SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_SESSION_OWNER_MISSING");
        assertThatThrownBy(() -> store.bindVerified(ALICE, "bad/session"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("CAMPAIGN_SESSION_INVALID");
        assertThatThrownBy(() -> store.bindVerified(AgentPrincipal.system("worker"), SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
    }

    @Test
    void resolverRequiresBothPersistedOwnerAndFreshAdminAccount() {
        var store = fixture();
        var authority = mock(AgentAuthorityClient.class);
        var resolver = new CampaignCurrentPrincipalResolver(authority, store);
        when(authority.verifyCurrentPrincipal(ALICE)).thenReturn(ALICE);
        assertThat(resolver.bindCurrent(ALICE, SESSION)).isEqualTo(ALICE);
        assertThat(resolver.resolve(new Caller("1001", "alice", 7), SESSION)).isEqualTo(ALICE);

        reset(authority);
        assertThatThrownBy(() -> resolver.resolve(new Caller("1002", "bob", 7), SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_SESSION_OWNER_CHANGED");
        verifyNoInteractions(authority);
        when(authority.verifyCurrentPrincipal(ALICE)).thenThrow(new SecurityException("account revoked"));
        assertThatThrownBy(() -> resolver.resolve(new Caller("1001", "alice", 7), SESSION))
                .isInstanceOf(SecurityException.class).hasMessage("account revoked");
    }

    private static JdbcCampaignConversationSessionOwner fixture() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:conversation_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "sql/migration/V20260923__campaign_conversation_session_owner.sql")).execute(source);
        return new JdbcCampaignConversationSessionOwner(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)), Clock.systemUTC());
    }
}
