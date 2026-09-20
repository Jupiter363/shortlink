package com.jupiter.shortlink.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignTrustedRunResultAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunResponseDecorator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunResponseRuntimeFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunResponseService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseCapabilityGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanRuntimeFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanTrustedAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignRunReportPublicationCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignTrustedAdapterConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:trusted_adapter;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa", "spring.datasource.password=")
            .withUserConfiguration(CampaignTrustedAdapterConfiguration.class);

    @Test
    void defaultProfileDoesNotCreateTrustedBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CampaignReplanRuntimeFactory.class);
            assertThat(context).doesNotHaveBean(CampaignReportApplicationService.class);
            assertThat(context).doesNotHaveBean(ReportLifecycleStore.class);
            assertThat(context).doesNotHaveBean(CampaignDurableRunResponseRuntimeFactory.class);
            assertThat(context).doesNotHaveBean(CampaignResponseCapabilityGate.class);
        });
    }

    @Test
    void activeProfileFailsWithoutSensitiveProviders() {
        runner.withPropertyValues("spring.profiles.active=campaign-trusted-adapter")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void activeProfileFailsWhenLegacyProvidersExistButDurableReadersAreMissing() {
        runner.withPropertyValues("spring.profiles.active=campaign-trusted-adapter")
                .withUserConfiguration(ExplicitProviders.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void activeProfileCreatesOnlyWithExplicitProvidersAndSharedTransaction() {
        runner.withPropertyValues("spring.profiles.active=campaign-trusted-adapter")
                .withUserConfiguration(ExplicitProviders.class, DurableResponseProviders.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CampaignReplanRuntimeFactory.class);
                    assertThat(context).hasSingleBean(CampaignReplanTrustedAdapter.class);
                    assertThat(context).hasSingleBean(CampaignReportApplicationService.class);
                    assertThat(context).hasSingleBean(CampaignTrustedRunResultAdapter.class);
                    assertThat(context).hasSingleBean(CampaignRunReportPublicationCoordinator.class);
                    assertThat(context).hasSingleBean(CampaignDurableRunResponseRuntimeFactory.class);
                    assertThat(context).hasSingleBean(CampaignResponseCapabilityGate.class);
                    assertThat(context).doesNotHaveBean(CampaignDurableRunResponseService.class);
                    CampaignDurableRunResponseDecorator first =
                            context.getBean(CampaignDurableRunResponseDecorator.class);
                    CampaignDurableRunResponseDecorator second =
                            context.getBean(CampaignDurableRunResponseDecorator.class);
                    assertThat(first).isNotSameAs(second);
                    CampaignDurableRunResponseDecorator.Outcome outcome = first.decorate(
                            new CampaignDurableRunResponseDecorator.Request(
                                    new AgentRunResult("session", "trace", "legacy", List.of(), List.of(),
                                            List.of(), List.of(), List.of(), List.of()),
                                    new com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunResultReadService.Request(
                                            new com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller(
                                                    "tenant", "user", 1), "run", 1),
                                    "plan"));
                    assertThat(outcome.status()).isEqualTo(
                            CampaignDurableRunResponseDecorator.Outcome.Status.NO_BINDING);
                    assertThat(context).hasSingleBean(JdbcTemplate.class);
                    assertThat(context).hasSingleBean(TransactionTemplate.class);
                    ReportLifecycleStore store = context.getBean(ReportLifecycleStore.class);
                    assertThat(store).isInstanceOf(com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore.class);
                    DataSourceTransactionManager manager = (DataSourceTransactionManager)
                            context.getBean(TransactionTemplate.class).getTransactionManager();
                    assertThat(manager.getDataSource()).isSameAs(context.getBean(DataSource.class));
                    TransactionTemplate transactions = context.getBean(TransactionTemplate.class);
                    assertThat(transactions.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                    assertThat(transactions.isReadOnly()).isFalse();
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExplicitProviders {
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_19__campaign_statistics_consumers.sql"),
                    new ClassPathResource("sql/migration/V20260920_21__campaign_replan_receipt.sql"),
                    new ClassPathResource("sql/migration/V20260920_22__campaign_report_lifecycle.sql"),
                    new ClassPathResource("sql/migration/V20260921__campaign_run_result_binding.sql"))
                    .execute(dataSource);
            return new JdbcTemplate(dataSource);
        }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-20T08:00:00Z"), ZoneOffset.UTC); }
        @Bean PlanValidator planValidator() {
            CapabilityCatalog catalog = new CapabilityCatalog() {
                public String version() { return "test"; }
                public Optional<Capability> capability(com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec.ExecutorRef e) { return Optional.empty(); }
                public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
                public Optional<Criterion> criterion(String ref, String version) { return Optional.empty(); }
            };
            return new PlanValidator(catalog);
        }
        @Bean CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer() { return (o, t, c) -> true; }
        @Bean CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer() { return (r, b, e) -> true; }
        @Bean CampaignReplanTrustedAdapter.RunTokenResolver runTokenResolver() {
            return (owner, sessionId, runId) -> Optional.empty();
        }
        @Bean CampaignReportPublisher.EvidenceReader evidenceReader() { return id -> Optional.empty(); }
        @Bean CampaignReportApplicationService.AccessAuthorizer reportAccessAuthorizer() { return (o, c, op) -> true; }
        @Bean(name = "campaignReportCapability") String campaignReportCapability() { return "report/v1"; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DurableResponseProviders {
        @Bean(name = "campaignDurableRunResultStore")
        CampaignRunResultStore campaignDurableRunResultStore() {
            return new CampaignRunResultStore() {
                @Override
                public Binding bind(RunToken token, BindingDraft draft) {
                    throw new UnsupportedOperationException("READ_ONLY_TEST_STORE");
                }

                @Override
                public Optional<Binding> read(CampaignRunStore.Caller caller, String runId, int revision) {
                    return Optional.empty();
                }
            };
        }

        @Bean(name = "campaignDurableRunReportReadProjection")
        CampaignRunReportReadProjection campaignDurableRunReportReadProjection() {
            return new CampaignRunReportReadProjection((caller, runId) -> {
                throw new AssertionError("progress must not be read without a binding");
            }, request -> {
                throw new AssertionError("report must not be read without a binding");
            });
        }
    }
}
