package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import java.util.Objects;

/**
 * Plain-Java composition factory for the durable run response read path.
 *
 * <p>The factory accepts only already-authorized typed readers.  It keeps no caller, owner,
 * capability, or request state and is intentionally not a Spring, Graph, HTTP, or chat bean.
 * Every {@link #create()} call gets a fresh E71 read service and E76/E77 response adapters, so a
 * future transport can safely reuse the factory across requests without sharing identity state.</p>
 */
public final class CampaignDurableRunResponseRuntimeFactory {
    private final CampaignRunResultStore bindings;
    private final CampaignRunReportReadProjection reports;

    public CampaignDurableRunResponseRuntimeFactory(CampaignRunResultStore bindings,
                                                    CampaignRunReportReadProjection reports) {
        this.bindings = Objects.requireNonNull(bindings, "RUN_RESULT_STORE_REQUIRED");
        this.reports = Objects.requireNonNull(reports, "RUN_REPORT_PROJECTION_REQUIRED");
    }

    /** Creates a request-independent durable read-to-response service. */
    public CampaignDurableRunResponseService create() {
        CampaignDurableRunResultReadService reads =
                new CampaignDurableRunResultReadService(bindings, reports);
        return new CampaignDurableRunResponseService(
                reads, new CampaignDurableRunResponseAdapter());
    }
}
