package com.jupiter.shortlink.agent.infrastructure.config;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignResultProgressReader;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunHandleResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignJdbcDurableRunResponseBridgeFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseCapabilityGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseProtocolMetadataResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignReportAccessGrantResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseRouteAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableResponseTransportAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseEnvelopeAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in, transport-neutral assembly for the E83 JDBC response bridge.
 *
 * <p>The extra profile is intentionally conjunctive with {@code campaign-trusted-adapter}. Every
 * projector and JDBC progress reader is a named provider; this configuration never invents an
 * authorizer, report credential, caller, or run identity. The existing E80 legacy factory stays
 * untouched until a later transport migration is separately approved.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("campaign-trusted-adapter & campaign-jdbc-durable-response")
public class CampaignJdbcDurableResponseConfiguration {
    @Bean
    public CampaignJdbcDurableRunResponseBridgeFactory campaignJdbcDurableRunResponseBridgeFactory(
            JdbcTemplate jdbc,
            @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions,
            Clock clock,
            @Qualifier("campaignJdbcDurableRunProgressReader") JdbcCampaignResultProgressReader progressReader,
            @Qualifier("campaignReportLifecycleStore") JdbcReportLifecycleStore reports,
            @Qualifier("campaignJdbcDurableRunProgress") CampaignProgressService progress,
            @Qualifier("campaignJdbcDurableRunReportProjection") CampaignReportReadProjection report,
            @Qualifier("campaignJdbcDurableRunResultProjection") CampaignRunReportReadProjection result) {
        return new CampaignJdbcDurableRunResponseBridgeFactory(
                jdbc, transactions, clock, progressReader, reports, progress, report, result);
    }

    /**
     * Opt-in route selector. It does not register an HTTP or Graph entry point; a later transport
     * can supply the server-owned durable request after resolving and authorizing its run handle.
     */
    @Bean
    public CampaignResponseRouteAdapter campaignResponseRouteAdapter(
            CampaignResponseCapabilityGate gate,
            CampaignJdbcDurableRunResponseBridgeFactory factory) {
        return new CampaignResponseRouteAdapter(gate, factory);
    }

    /**
     * Requires a server-owned exact handle resolver; no identity is inferred from session or graph
     * state. The adapter remains transport-neutral until a later HTTP/chat contract supplies it.
     */
    @Bean
    public CampaignDurableResponseTransportAdapter campaignDurableResponseTransportAdapter(
            CampaignResponseRouteAdapter route,
            @Qualifier("campaignDurableResponseRunHandleResolver")
            CampaignRunHandleResolver handles,
            @Qualifier("campaignDurableResponseProtocolMetadataResolver")
            CampaignResponseProtocolMetadataResolver protocols,
            @Qualifier("campaignDurableResponseReportAccessGrantResolver")
            CampaignReportAccessGrantResolver reportGrants) {
        return new CampaignDurableResponseTransportAdapter(route, handles, protocols, reportGrants);
    }

    /** Stable typed envelope over the one configured authority transport. */
    @Bean
    public CampaignResponseEnvelopeAdapter campaignResponseEnvelopeAdapter(
            CampaignDurableResponseTransportAdapter transport) {
        return new CampaignResponseEnvelopeAdapter(transport);
    }

    /** Exact revision lookup; unlike the generic run reader it never selects latest implicitly. */
    @Bean(name = "campaignDurableResponseRunHandleResolver")
    public CampaignRunHandleResolver campaignDurableResponseRunHandleResolver(
            JdbcTemplate jdbc,
            @Qualifier("campaignTrustedTransactionTemplate") TransactionTemplate transactions,
            Clock clock) {
        return new JdbcCampaignRunHandleResolver(new JdbcCampaignRunStore(jdbc, transactions, clock));
    }
}
