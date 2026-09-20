package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import java.util.Objects;

/** Trusted report reference verifier used by the durable run-result binding store. */
public final class CampaignRunReportBindingVerifier implements CampaignRunResultStore.ReportBindingVerifier {
    private final ReportLifecycleStore reports;
    private final String owner;
    private final String capability;

    public CampaignRunReportBindingVerifier(ReportLifecycleStore reports, String owner, String capability) {
        this.reports = Objects.requireNonNull(reports, "REPORT_LIFECYCLE_STORE_REQUIRED");
        ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
        ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
        this.owner = owner;
        this.capability = capability;
    }

    @Override
    public boolean mayBind(RunDefinition run, ReportRef reportRef) {
        Objects.requireNonNull(run, "REPORT_RUN_REQUIRED");
        Objects.requireNonNull(reportRef, "REPORT_REF_REQUIRED");
        return reports.read(new ReportLifecycleStore.Key(reportRef.reportId(), reportRef.revision()),
                        owner, capability, ReportLifecycleStore.Mode.HISTORY_VIEW)
                .filter(report -> run.runId().equals(report.runId())
                        && run.revision() == report.planRevision())
                .isPresent();
    }

    public long retain(ReportRef reportRef, String referenceId) {
        Objects.requireNonNull(reportRef, "REPORT_REF_REQUIRED");
        return reports.retain(new ReportLifecycleStore.Key(reportRef.reportId(), reportRef.revision()),
                referenceId, owner, capability);
    }

    public long release(ReportRef reportRef, String referenceId) {
        Objects.requireNonNull(reportRef, "REPORT_REF_REQUIRED");
        ReportLifecycleStore.Key key = new ReportLifecycleStore.Key(reportRef.reportId(), reportRef.revision());
        reports.read(key, owner, capability, ReportLifecycleStore.Mode.HISTORY_VIEW)
                .orElseThrow(() -> new IllegalStateException("REPORT_NOT_FOUND"));
        return reports.release(key, referenceId);
    }
}
