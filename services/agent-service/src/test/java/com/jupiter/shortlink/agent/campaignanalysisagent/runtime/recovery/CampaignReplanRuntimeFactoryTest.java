package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignReplanRuntimeFactoryTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);

    @Test
    void opensRuntimeOnlyForCurrentOwnerAndFrozenToken() {
        Fixture fixture = new Fixture();

        CampaignReplanRuntime runtime = fixture.factory.open(OWNER, fixture.base);

        assertThat(runtime.owner()).isEqualTo(OWNER);
        assertThat(runtime.baseRun()).isEqualTo(fixture.base);
        assertThat(runtime.frozen()).isEqualTo(FrozenCampaignRun.read(fixture.base.definition()));
        assertThat(runtime.frozen().plan().runId()).isEqualTo(fixture.base.definition().runId());
    }

    @Test
    void rejectsOwnerMismatchAndStaleBaseTokenBeforeComposition() {
        Fixture fixture = new Fixture();
        Caller otherOwner = new Caller("tenant-2", "analyst-1", 7);

        assertThatThrownBy(() -> fixture.factory.open(otherOwner, fixture.base))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_OWNER_TOKEN_MISMATCH");

        fixture.runs.advance(fixture.base);
        assertThatThrownBy(() -> fixture.factory.open(OWNER, fixture.base))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_BASE_TOKEN_STALE");
    }

    @Test
    void requiresExplicitTrustedDependencies() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> new CampaignReplanRuntimeFactory(fixture.jdbc, fixture.transactions,
                CLOCK, null, null, fixture.capabilityAuthorizer, fixture.consumerAuthorizer))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_PLAN_VALIDATOR_REQUIRED");
        assertThatThrownBy(() -> new CampaignReplanRuntimeFactory(fixture.jdbc, fixture.transactions,
                CLOCK, validator(), null, null, fixture.consumerAuthorizer))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_CAPABILITY_AUTHORIZER_REQUIRED");
        assertThatThrownBy(() -> new CampaignReplanRuntimeFactory(fixture.jdbc, fixture.transactions,
                CLOCK, validator(), null, fixture.capabilityAuthorizer, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_CONSUMER_AUTHORIZER_REQUIRED");
    }

    private static PlanValidator validator() {
        CapabilityCatalog catalog = new CapabilityCatalog() {
            @Override public String version() { return "catalog-v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef executor) { return Optional.empty(); }
            @Override public Optional<Policy> policy(String policyRef, String policyVersion) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String criterionRef, String criterionVersion) { return Optional.empty(); }
        };
        return new PlanValidator(catalog);
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final JdbcCampaignRunStore runs;
        final CampaignReplanRuntimeFactory factory;
        final RunToken base;
        final CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer = (owner, token, capability) -> true;
        final com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer =
                (run, binding, expected) -> true;

        Fixture() {
            DriverManagerDataSource source = new DriverManagerDataSource(
                    "jdbc:h2:mem:replan_runtime_" + UUID.randomUUID()
                            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_19__campaign_statistics_consumers.sql"),
                    new ClassPathResource("sql/migration/V20260920_21__campaign_replan_receipt.sql"))
                    .execute(source);
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
            PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                    List.of(), List.of());
            FrozenInputSet inputs = new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of());
            PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog-v1",
                    List.of(), List.of(), List.of());
            base = runs.createRun(FrozenCampaignRun.freeze(plan, inputs, assessment)
                    .definition(OWNER, "session-1"));
            factory = new CampaignReplanRuntimeFactory(jdbc, transactions, CLOCK, validator(), null,
                    capabilityAuthorizer, consumerAuthorizer);
        }
    }
}
