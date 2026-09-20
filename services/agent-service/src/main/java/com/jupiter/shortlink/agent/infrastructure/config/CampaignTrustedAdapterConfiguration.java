package com.jupiter.shortlink.agent.infrastructure.config;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanRuntimeFactory;
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
    public ReportLifecycleStore campaignReportLifecycleStore(JdbcTemplate jdbc,
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
    public CampaignReplanRuntimeFactory campaignReplanRuntimeFactory(
            JdbcTemplate jdbc,
            @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions, Clock clock,
            PlanValidator planValidator, ObjectProvider<ProcessExecutionScope> processScope,
            CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer,
            CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer) {
        return new CampaignReplanRuntimeFactory(jdbc, transactions, clock, planValidator,
                processScope.getIfAvailable(), capabilityAuthorizer, consumerAuthorizer);
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
