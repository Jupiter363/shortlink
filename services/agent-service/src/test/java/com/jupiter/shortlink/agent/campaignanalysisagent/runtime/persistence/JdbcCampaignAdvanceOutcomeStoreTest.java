package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.Header;
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

class JdbcCampaignAdvanceOutcomeStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "analyst", 7);

    @Test
    void failedAttemptIsReadableAndRetrySuccessCannotBeOverwrittenByLateFailure() {
        Fixture fixture = new Fixture();
        Header header = fixture.register("request-a");

        var first = fixture.outcomes.begin(header);
        assertEquals(new JdbcCampaignAdvanceOutcomeStore.Outcome(1,
                JdbcCampaignAdvanceOutcomeStore.RUNNING, null), fixture.outcomes.latest(header).orElseThrow());
        fixture.outcomes.failed(first, JdbcCampaignAdvanceOutcomeStore.ACCESS_DENIED);
        assertEquals(new JdbcCampaignAdvanceOutcomeStore.Outcome(1,
                JdbcCampaignAdvanceOutcomeStore.FAILED, JdbcCampaignAdvanceOutcomeStore.ACCESS_DENIED),
                fixture.outcomes.latest(header).orElseThrow());

        var retry = fixture.outcomes.begin(header);
        assertEquals(2, retry.version());
        fixture.outcomes.succeeded(retry);
        fixture.outcomes.failed(first, JdbcCampaignAdvanceOutcomeStore.RUNTIME_UNAVAILABLE);
        assertEquals(new JdbcCampaignAdvanceOutcomeStore.Outcome(2,
                JdbcCampaignAdvanceOutcomeStore.SUCCEEDED, null), fixture.outcomes.latest(header).orElseThrow());
        assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_advance_outcome", Integer.class));
    }

    @Test
    void immutableRequestBindingRejectsChangedProfileAndUnknownReason() {
        Fixture fixture = new Fixture();
        Header header = fixture.register("request-b");
        var attempt = fixture.outcomes.begin(header);
        Header forged = new Header(header.requestId(), header.caller(), header.sessionId(), header.requestKey(),
                header.profileRef(), "2", header.runId(), header.planId(), header.revision(),
                header.definitionHash(), header.requestHash(), header.state());

        assertThrows(SecurityException.class, () -> fixture.outcomes.begin(forged));
        assertThrows(SecurityException.class, () -> fixture.outcomes.latest(forged));
        assertThrows(SecurityException.class, () -> fixture.outcomes.succeeded(
                new JdbcCampaignAdvanceOutcomeStore.Attempt(forged, attempt.version())));
        assertThrows(IllegalArgumentException.class, () -> fixture.outcomes.failed(attempt, "SECRET_EXCEPTION"));
        assertTrue(fixture.outcomes.latest(header).isPresent());
        assertEquals(JdbcCampaignAdvanceOutcomeStore.RUNNING, fixture.outcomes.latest(header).orElseThrow().status());
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final JdbcCampaignRunIntakeStore intake;
        final JdbcCampaignAdvanceOutcomeStore outcomes;

        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:advance_outcome_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_17__campaign_run_intake.sql"),
                    new ClassPathResource("sql/migration/V20260923_2__campaign_advance_outcome.sql"))
                    .execute(source);
            jdbc = new JdbcTemplate(source);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            var runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
            intake = new JdbcCampaignRunIntakeStore(jdbc, transactions, CLOCK, runs, 1024);
            outcomes = new JdbcCampaignAdvanceOutcomeStore(jdbc, transactions, CLOCK);
        }

        Header register(String requestKey) {
            var identity = JdbcCampaignRunIntakeStore.identity(OWNER, "session-1", requestKey);
            var definition = new RunDefinition(OWNER, "session-1", identity.runId(), identity.planId(), 1, "{}");
            return intake.register(OWNER, "session-1", requestKey, "fixed-profile", "1", definition);
        }
    }
}
