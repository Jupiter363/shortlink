package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignRunHandleResolverTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-1", "subject-1", 2);

    @Test
    void resolvesExactRevisionWithoutFallingBackToLatest() {
        Fixture fixture = fixture();
        var first = fixture.runs.createRun(definition(1));
        var second = fixture.runs.revise(first, 2, "{}");
        JdbcCampaignRunHandleResolver resolver = new JdbcCampaignRunHandleResolver(fixture.runs);

        CampaignRunHandle firstHandle = resolver.resolve(request(1, "plan-1")).orElseThrow();
        CampaignRunHandle secondHandle = resolver.resolve(request(2, "plan-1")).orElseThrow();

        assertThat(firstHandle.revision()).isEqualTo(1);
        assertThat(firstHandle.status()).isEqualTo(RunStatus.SUPERSEDED);
        assertThat(secondHandle.revision()).isEqualTo(second.definition().revision());
        assertThat(secondHandle.status()).isEqualTo(RunStatus.ACTIVE);
    }

    @Test
    void missingRevisionReturnsEmptyInsteadOfReadingAnotherRevision() {
        Fixture fixture = fixture();
        fixture.runs.createRun(definition(1));
        JdbcCampaignRunHandleResolver resolver = new JdbcCampaignRunHandleResolver(fixture.runs);

        assertThat(resolver.resolve(request(2, "plan-1"))).isEmpty();
    }

    @Test
    void sessionAndPlanMustMatchTheExactLedgerDefinition() {
        Fixture fixture = fixture();
        fixture.runs.createRun(definition(1));
        JdbcCampaignRunHandleResolver resolver = new JdbcCampaignRunHandleResolver(fixture.runs);

        assertThatThrownBy(() -> resolver.resolve(new CampaignRunHandleResolver.Request(
                OWNER, "other-session", "run-1", 1, "plan-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_BINDING_MISMATCH");
        assertThatThrownBy(() -> resolver.resolve(request(1, "other-plan")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_PLAN_MISMATCH");
    }

    @Test
    void callerMismatchIsRejectedBeforeProducingAHandle() {
        Fixture fixture = fixture();
        fixture.runs.createRun(definition(1));
        JdbcCampaignRunHandleResolver resolver = new JdbcCampaignRunHandleResolver(fixture.runs);

        assertThatThrownBy(() -> resolver.resolve(new CampaignRunHandleResolver.Request(
                new Caller("tenant-2", "subject-1", 2), "session-1", "run-1", 1, "plan-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("LEDGER_SUBJECT_MISMATCH");
    }

    @Test
    void malformedCallerIsRejectedWhenTheReferenceIsConstructed() {
        assertThatThrownBy(() -> new CampaignRunHandleResolver.Request(
                new Caller("", "subject-1", 2), "session-1", "run-1", 1, "plan-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_CALLER_INVALID");
        assertThatThrownBy(() -> new CampaignRunHandleResolver.Request(
                new Caller("tenant-1", "subject-1", 0), "session-1", "run-1", 1, "plan-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_CALLER_INVALID");
    }

    @Test
    void terminalRunFactsRemainVisibleAsFactsWithoutGrantingWriteCredentials() {
        Fixture fixture = fixture();
        var cancelled = fixture.runs.createRun(definition(1));
        fixture.runs.cancel(cancelled);
        JdbcCampaignRunHandleResolver resolver = new JdbcCampaignRunHandleResolver(fixture.runs);

        CampaignRunHandle handle = resolver.resolve(request(1, "plan-1")).orElseThrow();

        assertThat(handle.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(handle).hasFieldOrPropertyWithValue("runId", "run-1");
    }

    private static CampaignRunHandleResolver.Request request(int revision, String planId) {
        return new CampaignRunHandleResolver.Request(OWNER, "session-1", "run-1", revision, planId);
    }

    private static RunDefinition definition(int revision) {
        return new RunDefinition(OWNER, "session-1", "run-1", "plan-1", revision, "{}");
    }

    private static Fixture fixture() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:run_handle_" + UUID.randomUUID().toString().replace('-', '_')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"))
                .execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        return new Fixture(new JdbcCampaignRunStore(jdbc, transactions, CLOCK));
    }

    private record Fixture(JdbcCampaignRunStore runs) {}
}
