package com.jupiter.shortlink.agent.infrastructure.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStatisticsFixedRuntime;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in FIXED statistics runtime. No chat or HTTP route is registered by this configuration. */
@Configuration(proxyBeanMethods = false)
@Profile("campaign-statistics-fixed")
public class CampaignStatisticsFixedConfiguration {
    @Bean("campaignStatisticsClock")
    public Clock campaignStatisticsClock() {
        return Clock.systemUTC();
    }

    @Bean("campaignStatisticsTransactionTemplate")
    public TransactionTemplate campaignStatisticsTransactionTemplate(@Qualifier("dataSource") DataSource dataSource) {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(dataSource)));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setReadOnly(false);
        return transactions;
    }

    @Bean(destroyMethod = "close")
    public CampaignStatisticsFixedRuntime campaignStatisticsFixedRuntime(JdbcTemplate jdbc,
            @Qualifier("campaignStatisticsTransactionTemplate") TransactionTemplate transactions,
            @Qualifier("campaignStatisticsClock") Clock clock,
            AgentAuthorityClient authority, ShortLinkBusinessGateway gateway,
            @Qualifier("mysqlGraphSaver") BaseCheckpointSaver saver,
            @Value("${short-link.agent.campaign-statistics.process-domain:}") String trustedProcessDomain,
            @Value("${short-link.agent.campaign-statistics.active-advances:4}") int activeAdvances,
            @Value("${short-link.agent.campaign-statistics.models:4}") int models,
            @Value("${short-link.agent.campaign-statistics.large-payloads:4}") int largePayloads,
            @Value("${short-link.agent.campaign-statistics.max-queued:128}") int maxQueued) {
        return new CampaignStatisticsFixedRuntime(jdbc, transactions, clock, authority, gateway, saver,
                trustedProcessDomain,
                new ProcessCapacityExecutor.Limits(activeAdvances, models, largePayloads, maxQueued));
    }
}
