package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignConversationTurnStoreTest {
    private static final AgentPrincipal ALICE = new AgentPrincipal("1001", "alice", 7, false);
    private static final AgentPrincipal BOB = new AgentPrincipal("1002", "bob", 7, false);
    private static final String SESSION = "campaign-session";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant EXPIRY = CLOCK.instant().plusSeconds(3600);

    @Test
    void duplicateRegistrationAndRestartKeepFirstWorkReferenceQuestionAndDeadline() {
        var fixture = fixture();
        WorkRef reference = reference("first");
        var first = fixture.store().recordVerified(ALICE, SESSION, "first", "比较两个期间",
                "{\"groupId\":\"g1\"}", "PLANNED", reference, null, EXPIRY);
        var retry = fixture.store().recordVerified(ALICE, SESSION, "first", "比较两个期间",
                "{\"groupId\":\"g1\"}", "PLANNED", reference, null, EXPIRY.plusSeconds(900));
        assertThat(retry).isEqualTo(first);
        var restarted = new JdbcCampaignConversationTurnStore(fixture.jdbc(), fixture.transactions(),
                fixture.sessions(), CLOCK);
        assertThat(restarted.findByKey(ALICE, SESSION, "first")).contains(first);
        assertThat(restarted.require(ALICE, SESSION, reference)).isEqualTo(first);
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_conversation_turn", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> restarted.recordVerified(ALICE, SESSION, "first", "改为另一个期间",
                "{\"groupId\":\"g1\"}", "PLANNED", reference, null, EXPIRY))
                .hasMessage("CAMPAIGN_TURN_REQUEST_CONFLICT");
        assertThat(restarted.require(ALICE, SESSION, reference)).isEqualTo(first);
    }

    @Test
    void newDemandLinksNewIdentityWithoutMutatingPreviousRequestAndRejectsForeignPredecessor() {
        var fixture = fixture();
        var first = fixture.store().recordVerified(ALICE, SESSION, "first", "比较期间", "{}",
                "PLANNED", reference("first"), null, EXPIRY);
        var second = fixture.store().recordVerified(ALICE, SESSION, "second", "增加设备下钻", "{}",
                "PLANNED", reference("second"), first.workRef().runId(), EXPIRY);
        assertThat(second.workRef()).isNotEqualTo(first.workRef());
        assertThat(second.previousRunId()).isEqualTo(first.workRef().runId());
        assertThat(fixture.store().require(ALICE, SESSION, first.workRef())).isEqualTo(first);

        fixture.sessions().bindVerified(BOB, "bob-session");
        var bobId = JdbcCampaignRunIntakeStore.identity(new Caller("1002", "bob", 7), "bob-session", "third");
        assertThatThrownBy(() -> fixture.store().recordVerified(BOB, "bob-session", "third", "follow", "{}",
                "PLANNED", new WorkRef(bobId.runId(), bobId.requestId()), first.workRef().runId(), EXPIRY))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_TURN_OWNER_CHANGED");
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_conversation_turn", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void possessionOfReferenceDoesNotAuthorizeAnotherSessionAccountOrAuthenticationEpoch() {
        var fixture = fixture();
        WorkRef reference = reference("first");
        fixture.store().recordVerified(ALICE, SESSION, "first", "compare", "{}", "FIXED", reference, null, EXPIRY);
        fixture.sessions().bindVerified(ALICE, "other-session");
        fixture.sessions().bindVerified(BOB, "bob-session");
        assertThatThrownBy(() -> fixture.store().require(ALICE, "other-session", reference))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_TURN_OWNER_CHANGED");
        assertThatThrownBy(() -> fixture.store().require(BOB, "bob-session", reference))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_TURN_OWNER_CHANGED");
        assertThatThrownBy(() -> fixture.store().require(ALICE, SESSION,
                new WorkRef(reference.runId(), reference("unrelated").workId())))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_TURN_UNAVAILABLE");
        fixture.sessions().bindVerified(new AgentPrincipal("1001", "alice", 8, false), SESSION);
        assertThatThrownBy(() -> fixture.store().require(ALICE, SESSION, reference))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_SESSION_OWNER_CHANGED");
        assertThatThrownBy(() -> fixture.store().require(new AgentPrincipal("1001", "alice", 8, false), SESSION, reference))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_TURN_OWNER_CHANGED");
    }

    private static WorkRef reference(String key) {
        var identity = JdbcCampaignRunIntakeStore.identity(new Caller("1001", "alice", 7), SESSION, key);
        return new WorkRef(identity.runId(), identity.requestId());
    }

    private static Fixture fixture() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:turn_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260923__campaign_conversation_session_owner.sql"),
                new ClassPathResource("sql/migration/V20260923_3__campaign_conversation_turn.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var sessions = new JdbcCampaignConversationSessionOwner(jdbc, transactions, CLOCK);
        sessions.bindVerified(ALICE, SESSION);
        return new Fixture(jdbc, transactions, sessions,
                new JdbcCampaignConversationTurnStore(jdbc, transactions, sessions, CLOCK));
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions,
                           JdbcCampaignConversationSessionOwner sessions, JdbcCampaignConversationTurnStore store) {}
}
