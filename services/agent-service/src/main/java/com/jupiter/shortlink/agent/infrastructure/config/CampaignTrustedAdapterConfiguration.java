package com.jupiter.shortlink.agent.infrastructure.config;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignTrustedRunResultAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanRuntimeFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanTrustedAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunResponseDecorator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunResponseRuntimeFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseCapabilityGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignRunReportPublicationCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit composition seam for trusted campaign adapters. It is opt-in and has no transport,
 * chat, tool, or scheduler entry point.
 */
@Configuration(proxyBeanMethods = false)
@Profile("campaign-trusted-adapter")
public class CampaignTrustedAdapterConfiguration {
    @Bean
    public JdbcReportLifecycleStore campaignReportLifecycleStore(JdbcTemplate jdbc,
                                                                  @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions,
                                                                  Clock clock) {
        return new JdbcReportLifecycleStore(jdbc, transactions, clock);
    }

    @Bean
    public GoalAssessor campaignGoalAssessor() {
        return new GoalAssessor();
    }

    @Bean
    public CampaignReportPublisher campaignReportPublisher(GoalAssessor assessor,
                                                           CampaignReportPublisher.EvidenceReader evidenceReader,
                                                           Clock clock,
                                                           @Qualifier("campaignReportCapability") String capability,
                                                           ReportLifecycleStore lifecycleStore) {
        return new CampaignReportPublisher(assessor, evidenceReader, clock, capability, lifecycleStore);
    }

    @Bean
    public CampaignReportApplicationService campaignReportApplicationService(
            CampaignReportPublisher publisher,
            CampaignReportApplicationService.AccessAuthorizer authorizer) {
        return new CampaignReportApplicationService(publisher, authorizer);
    }

    @Bean
    public CampaignTrustedRunResultAdapter campaignTrustedRunResultAdapter(
            JdbcTemplate jdbc, JdbcReportLifecycleStore lifecycle,
            @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions, Clock clock) {
        return new CampaignTrustedRunResultAdapter(jdbc, lifecycle, transactions, clock);
    }

    @Bean
    public CampaignRunReportPublicationCoordinator campaignRunReportPublicationCoordinator(
            CampaignReportApplicationService reports, CampaignTrustedRunResultAdapter results,
            JdbcReportLifecycleStore lifecycle, JdbcTemplate jdbc,
            @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions) {
        return new CampaignRunReportPublicationCoordinator(reports, results, lifecycle, jdbc, transactions);
    }

    /**
     * Explicit read-only composition for the durable campaign response path. Providers are
     * qualified because caller/report authorization is request-owned; this configuration never
     * creates an allow-all store or projection.
     */
    @Bean
    public CampaignDurableRunResponseRuntimeFactory campaignDurableRunResponseRuntimeFactory(
            @Qualifier("campaignDurableRunResultStore") CampaignRunResultStore bindings,
            @Qualifier("campaignDurableRunReportReadProjection") CampaignRunReportReadProjection reports) {
        return new CampaignDurableRunResponseRuntimeFactory(bindings, reports);
    }

    /** Prototype boundary: each transport request gets a fresh E71/E76/E77 composition. */
    @Bean
    @org.springframework.context.annotation.Scope(
            org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public CampaignDurableRunResponseDecorator campaignDurableRunResponseDecorator(
            CampaignDurableRunResponseRuntimeFactory factory) {
        return new CampaignDurableRunResponseDecorator(factory.create());
    }

    /** Protocol selection only; authorization remains owned by the transport/read providers. */
    @Bean
    public CampaignResponseCapabilityGate campaignResponseCapabilityGate() {
        return new CampaignResponseCapabilityGate();
    }

    @Bean
    public CampaignReplanRuntimeFactory campaignReplanRuntimeFactory(
            JdbcTemplate jdbc,
            @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions, Clock clock,
            PlanValidator planValidator, ObjectProvider<ProcessExecutionScope> processScope,
            CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer,
            CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer) {
        return new CampaignReplanRuntimeFactory(jdbc, transactions, clock, planValidator,
                processScope.getIfAvailable(), capabilityAuthorizer, consumerAuthorizer);
    }

    /** The profile requires a transport-owned resolver; no default can authorize a durable write. */
    @Bean
    public CampaignReplanTrustedAdapter campaignReplanTrustedAdapter(
            CampaignReplanRuntimeFactory runtimeFactory,
            CampaignReplanTrustedAdapter.RunTokenResolver tokenResolver) {
        return new CampaignReplanTrustedAdapter(runtimeFactory, tokenResolver);
    }

    /** Explicitly binds the transaction manager used by both trusted seams to the primary data source. */
    @Bean
    public TransactionTemplate campaignTrustedTransactionTemplate(@Qualifier("dataSource") DataSource dataSource) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(Objects.requireNonNull(dataSource));
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setReadOnly(false);
        return template;
    }
}
