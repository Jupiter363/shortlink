package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.infrastructure.config.CampaignTrustedAdapterConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignReplanTrustedAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);
    private static final Caller OTHER_OWNER = new Caller("tenant-2", "analyst-1", 7);

    @Test
    void missingResolverTokenFailsBeforeAnyDurableWrite() {
        Fixture fixture = new Fixture(true);
        CampaignReplanTrustedAdapter adapter = fixture.adapter((owner, session, run) -> Optional.empty());

        assertThatThrownBy(() -> adapter.execute(fixture.request(OWNER, "session-1", "run-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REPLAN_RUN_TOKEN_NOT_FOUND");
        fixture.assertUnchanged();
    }

    @Test
    void resolverBindingMustMatchTransportOwnerAndSession() {
        Fixture fixture = new Fixture(true);
        CampaignReplanTrustedAdapter adapter = fixture.adapter((owner, session, run) -> Optional.of(fixture.base));

        assertThatThrownBy(() -> adapter.execute(fixture.request(OTHER_OWNER, "session-1", "run-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_RUN_TOKEN_BINDING_MISMATCH");
        assertThatThrownBy(() -> adapter.execute(fixture.request(OWNER, "other-session", "run-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_RUN_TOKEN_BINDING_MISMATCH");
        fixture.assertUnchanged();
    }

    @Test
    void staleAndInactiveResolverTokensAreRejectedByFactoryWithoutWrites() {
        Fixture stale = new Fixture(true);
        CampaignReplanTrustedAdapter staleAdapter = stale.adapter((owner, session, run) -> Optional.of(stale.base));
        stale.runs.advance(stale.base);

        assertThatThrownBy(() -> staleAdapter.execute(stale.request(OWNER, "session-1", "run-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_BASE_TOKEN_STALE");
        stale.assertUnchanged(1, 0);

        Fixture inactive = new Fixture(true);
        CampaignReplanTrustedAdapter inactiveAdapter = inactive.adapter(
                (owner, session, run) -> Optional.of(inactive.base));
        inactive.runs.cancel(inactive.base);

        assertThatThrownBy(() -> inactiveAdapter.execute(inactive.request(OWNER, "session-1", "run-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REPLAN_BASE_RUN_NOT_ACTIVE");
        inactive.assertUnchanged();
    }

    @Test
    void capabilityDenialDoesNotRecordReceiptOrRevision() {
        Fixture fixture = new Fixture(false);
        RunToken current = fixture.runs.advance(fixture.base);
        AtomicReference<Caller> resolvedOwner = new AtomicReference<>();
        AtomicReference<String> resolvedSession = new AtomicReference<>();
        AtomicReference<String> resolvedRun = new AtomicReference<>();
        CampaignReplanTrustedAdapter adapter = fixture.adapter((owner, session, run) -> {
            resolvedOwner.set(owner);
            resolvedSession.set(session);
            resolvedRun.set(run);
            return Optional.of(current);
        });

        assertThatThrownBy(() -> adapter.execute(fixture.request(OWNER, "session-1", "run-1")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_CAPABILITY_DENIED");
        assertThat(resolvedOwner).hasValue(OWNER);
        assertThat(resolvedSession).hasValue("session-1");
        assertThat(resolvedRun).hasValue("run-1");
        fixture.assertUnchanged(1, 0);
    }

    @Test
    void constructorRequiresFactoryAndResolver() {
        assertThatThrownBy(() -> new CampaignReplanTrustedAdapter(null,
                (owner, session, run) -> Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_RUNTIME_FACTORY_REQUIRED");
    }

    @Test
    void profileCreatesAdapterOnlyWithExplicitResolverProvider() {
        contextRunner(false).withUserConfiguration(TrustedProviders.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CampaignReplanTrustedAdapter.class);
                });
        contextRunner(true).withUserConfiguration(TrustedProviders.class, ResolverProvider.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CampaignReplanTrustedAdapter.class);
                });
    }

    private static ApplicationContextRunner contextRunner(boolean ignored) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=campaign-trusted-adapter",
                        "spring.datasource.url=jdbc:h2:mem:trusted_adapter_" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=")
                .withUserConfiguration(CampaignTrustedAdapterConfiguration.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TrustedProviders {
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_19__campaign_statistics_consumers.sql"),
                    new ClassPathResource("sql/migration/V20260920_21__campaign_replan_receipt.sql"),
                    new ClassPathResource("sql/migration/V20260920_22__campaign_report_lifecycle.sql"))
                    .execute(dataSource);
            return new JdbcTemplate(dataSource);
        }

        @Bean Clock clock() { return CLOCK; }

        @Bean PlanValidator planValidator() { return validator(); }

        @Bean CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer() {
            return (owner, token, capability) -> true;
        }

        @Bean CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer() {
            return (run, binding, expected) -> true;
        }

        @Bean CampaignReportPublisher.EvidenceReader evidenceReader() { return id -> Optional.empty(); }

        @Bean CampaignReportApplicationService.AccessAuthorizer reportAccessAuthorizer() {
            return (owner, capability, operation) -> true;
        }

        @Bean(name = "campaignReportCapability") String campaignReportCapability() { return "report/v1"; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ResolverProvider {
        @Bean CampaignReplanTrustedAdapter.RunTokenResolver runTokenResolver() {
            return (owner, sessionId, runId) -> Optional.empty();
        }
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
        final PlanSpec plan;
        final PlanningAssessment assessment;
        final RunToken base;
        final boolean authorize;

        Fixture(boolean authorize) {
            this.authorize = authorize;
            DriverManagerDataSource source = new DriverManagerDataSource(
                    "jdbc:h2:mem:replan_trusted_" + UUID.randomUUID()
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
            plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                    List.of(), List.of());
            FrozenInputSet inputs = new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of());
            assessment = new PlanningAssessment("plan-1", 1, "catalog-v1", List.of(), List.of(), List.of());
            base = runs.createRun(FrozenCampaignRun.freeze(plan, inputs, assessment)
                    .definition(OWNER, "session-1"));
            factory = new CampaignReplanRuntimeFactory(jdbc, transactions, CLOCK, validator(), null,
                    (owner, token, capability) -> authorize, (run, binding, expected) -> true);
        }

        CampaignReplanTrustedAdapter adapter(CampaignReplanTrustedAdapter.RunTokenResolver resolver) {
            return new CampaignReplanTrustedAdapter(factory, resolver);
        }

        CampaignReplanTrustedAdapter.Request request(Caller owner, String sessionId, String runId) {
            ReplanRequest replan = ReplanRequest.create(plan, assessment, Set.of(),
                    List.of(new ReplanRequest.Evidence("evidence-1", "artifact-1", "evidence-v1", "a".repeat(64))),
                    List.of(), "trusted adapter test");
            return new CampaignReplanTrustedAdapter.Request(owner, sessionId, runId,
                    CampaignReplanApplicationService.Capability.CAMPAIGN_REPLAN, replan, plan, assessment);
        }

        void assertUnchanged() { assertUnchanged(1, 0); }

        void assertUnchanged(int runsCount, int receiptsCount) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_ledger", Integer.class))
                    .isEqualTo(runsCount);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_replan_receipt", Integer.class))
                    .isEqualTo(receiptsCount);
        }
    }
}
