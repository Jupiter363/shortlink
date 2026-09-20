package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignReplanRunTokenResolverTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-1", "subject-1", 2);

    @Test
    void resolvesOnlyTheCurrentActiveTokenAndReReadsAfterFencing() {
        Fixture fixture = fixture();
        RunToken original = fixture.runs.createRun(definition(1));
        JdbcCampaignReplanRunTokenResolver resolver = new JdbcCampaignReplanRunTokenResolver(fixture.runs);

        assertThat(resolver.resolve(OWNER, "session-1", "run-1")).contains(original);

        RunToken advanced = fixture.runs.advance(original);
        assertThat(resolver.resolve(OWNER, "session-1", "run-1")).contains(advanced);
        assertThat(resolver.resolve(OWNER, "session-1", "run-1").orElseThrow())
                .isNotEqualTo(original);

        // A second call must observe cancellation rather than a cached write token.
        fixture.runs.cancel(advanced);
        assertThat(resolver.resolve(OWNER, "session-1", "run-1")).isEmpty();
    }

    @Test
    void sessionMismatchFailsClosedWithoutDisclosingTheRun() {
        Fixture fixture = fixture();
        fixture.runs.createRun(definition(1));
        JdbcCampaignReplanRunTokenResolver resolver = new JdbcCampaignReplanRunTokenResolver(fixture.runs);

        assertThat(resolver.resolve(OWNER, "other-session", "run-1")).isEmpty();
    }

    @Test
    void cancelledAndMissingRunsDoNotProduceAWriteToken() {
        Fixture fixture = fixture();
        RunToken token = fixture.runs.createRun(definition(1));
        fixture.runs.cancel(token);
        JdbcCampaignReplanRunTokenResolver resolver = new JdbcCampaignReplanRunTokenResolver(fixture.runs);

        assertThat(resolver.resolve(OWNER, "session-1", "run-1")).isEmpty();
        assertThat(resolver.resolve(OWNER, "session-1", "missing-run")).isEmpty();
    }

    @Test
    void aSupersededRevisionIsNeverReturnedInsteadLatestRevisionIsResolved() {
        Fixture fixture = fixture();
        RunToken superseded = fixture.runs.createRun(definition(1));
        RunToken current = fixture.runs.revise(superseded, 2, "{}");
        JdbcCampaignReplanRunTokenResolver resolver = new JdbcCampaignReplanRunTokenResolver(fixture.runs);

        Optional<RunToken> resolved = resolver.resolve(OWNER, "session-1", "run-1");
        assertThat(resolved).contains(current);
        assertThat(resolved.orElseThrow()).isNotEqualTo(superseded);
        assertThat(fixture.runs.loadRun(OWNER, "run-1").orElseThrow().status())
                .isEqualTo(RunStatus.ACTIVE);
    }

    @Test
    void ownerMismatchIsRejectedByTheOwnerBoundJdbcRead() {
        Fixture fixture = fixture();
        fixture.runs.createRun(definition(1));
        JdbcCampaignReplanRunTokenResolver resolver = new JdbcCampaignReplanRunTokenResolver(fixture.runs);

        assertThatThrownBy(() -> resolver.resolve(new Caller("tenant-2", "subject-1", 2),
                "session-1", "run-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("LEDGER_SUBJECT_MISMATCH");
    }

    @Test
    void malformedResolverInputsAndMissingStoreFailClosed() {
        assertThatThrownBy(() -> new JdbcCampaignReplanRunTokenResolver(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_RUN_STORE_REQUIRED");

        Fixture fixture = fixture();
        JdbcCampaignReplanRunTokenResolver resolver = new JdbcCampaignReplanRunTokenResolver(fixture.runs);
        assertThatThrownBy(() -> resolver.resolve(null, "session-1", "run-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_OWNER_REQUIRED");
        assertThatThrownBy(() -> resolver.resolve(OWNER, "", "run-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_SESSION_REQUIRED");
        assertThatThrownBy(() -> resolver.resolve(OWNER, "session-1", "bad/run"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_RUN_ID_REQUIRED");
    }

    private static RunDefinition definition(int revision) {
        return new RunDefinition(OWNER, "session-1", "run-1", "plan-1", revision, "{}");
    }

    private static Fixture fixture() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:replan_token_" + UUID.randomUUID().toString().replace('-', '_')
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
