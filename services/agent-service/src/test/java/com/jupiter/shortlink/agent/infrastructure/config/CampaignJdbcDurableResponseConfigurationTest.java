package com.jupiter.shortlink.agent.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignResultProgressReader;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignJdbcDurableRunResponseBridgeFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseRouteAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableResponseTransportAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignJdbcDurableResponseConfigurationTest {
    private ApplicationContextRunner runner() {
        String databaseName = "jdbc_durable_response_" + UUID.randomUUID().toString().replace('-', '_');
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa", "spring.datasource.password=")
            .withUserConfiguration(CampaignTrustedAdapterConfiguration.class,
                    CampaignJdbcDurableResponseConfiguration.class);
    }

    @Test
    void profileOffDoesNotCreateJdbcFactory() {
        runner().run(context -> {
            assertThat(context).doesNotHaveBean(CampaignJdbcDurableRunResponseBridgeFactory.class);
            assertThat(context).doesNotHaveBean(CampaignResponseRouteAdapter.class);
            assertThat(context).doesNotHaveBean(CampaignDurableResponseTransportAdapter.class);
        });
    }

    @Test
    void combinedProfileFailsWhenNamedProjectorsAreMissing() {
        runner().withPropertyValues("spring.profiles.active=campaign-trusted-adapter,campaign-jdbc-durable-response")
                .withUserConfiguration(CampaignTrustedAdapterConfigurationTest.ExplicitProviders.class,
                        CampaignTrustedAdapterConfigurationTest.DurableResponseProviders.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void combinedProfileCreatesFactoryOnlyFromExplicitProviders() {
        runner().withPropertyValues("spring.profiles.active=campaign-trusted-adapter,campaign-jdbc-durable-response")
                .withUserConfiguration(CampaignTrustedAdapterConfigurationTest.ExplicitProviders.class,
                        CampaignTrustedAdapterConfigurationTest.DurableResponseProviders.class,
                        JdbcDurableProviders.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CampaignJdbcDurableRunResponseBridgeFactory.class);
                    assertThat(context).hasSingleBean(CampaignResponseRouteAdapter.class);
                    assertThat(context).hasSingleBean(CampaignDurableResponseTransportAdapter.class);
                    assertThat(context).hasBean("campaignDurableResponseRunHandleResolver");
                    assertThat(context).hasSingleBean(JdbcCampaignResultProgressReader.class);
                    assertThat(context).hasBean("campaignJdbcDurableRunResultProjection");
                    assertThat(context).getBean("campaignJdbcDurableRunResultProjection")
                            .isInstanceOf(CampaignRunReportReadProjection.class);
                    assertThat(context).hasSingleBean(CampaignReportReadProjection.class);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JdbcDurableProviders {
        @Bean(name = "campaignJdbcDurableRunProgressReader")
        JdbcCampaignResultProgressReader progressReader(
                JdbcTemplate jdbc, @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions,
                Clock clock) {
            return new JdbcCampaignResultProgressReader(jdbc, transactions, clock);
        }

        @Bean(name = "campaignJdbcDurableRunProgress")
        CampaignProgressService progress(
                JdbcTemplate jdbc, @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions,
                Clock clock,
                @Qualifier("campaignJdbcDurableRunProgressReader") JdbcCampaignResultProgressReader reader) {
            CampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, clock);
            CampaignStepStore steps = new JdbcCampaignStepStore(jdbc, transactions, clock);
            CampaignRunStore.ArtifactAuthorizer artifacts = (caller, artifact) -> true;
            CampaignResultProgressReader.ScopeAuthorizer scopes = (caller, run, scope, periods) -> true;
            return new CampaignProgressService(steps, runs, artifacts, reader, scopes, clock);
        }

        @Bean(name = "campaignJdbcDurableRunReportProjection")
        CampaignReportReadProjection reportProjection(
                com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService reports) {
            return new CampaignReportReadProjection(reports);
        }

        @Bean(name = "campaignJdbcDurableRunResultProjection")
        CampaignRunReportReadProjection resultProjection(
                @Qualifier("campaignJdbcDurableRunProgress") CampaignProgressService progress,
                @Qualifier("campaignJdbcDurableRunReportProjection") CampaignReportReadProjection reports) {
            return new CampaignRunReportReadProjection(progress, reports);
        }
    }
}
