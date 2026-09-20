package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.util.Objects;

/** Short-lived, server-issued report access proof; owner/capability are never transport fields. */
public final class CampaignReportAccessGrant {
    static final String SERVER_CAPABILITY = "campaign/report/v1";

    private final Caller caller;
    private final CampaignRunHandle handle;
    private final ReportLifecycleStore.Mode mode;
    private final String owner;

    private CampaignReportAccessGrant(Caller caller, CampaignRunHandle handle,
                                      ReportLifecycleStore.Mode mode, String owner) {
        this.caller = caller;
        this.handle = handle;
        this.mode = mode;
        this.owner = Objects.requireNonNull(owner, "REPORT_ACCESS_OWNER_REQUIRED");
    }

    static CampaignReportAccessGrant issue(Caller caller, CampaignRunHandle handle,
                                           ReportLifecycleStore.Mode mode, String owner) {
        caller = Objects.requireNonNull(caller, "REPORT_ACCESS_CALLER_REQUIRED");
        handle = Objects.requireNonNull(handle, "REPORT_ACCESS_HANDLE_REQUIRED");
        if (!caller.equals(handle.caller()))
            throw new SecurityException("REPORT_ACCESS_CALLER_MISMATCH");
        return new CampaignReportAccessGrant(caller, handle, Objects.requireNonNull(mode), owner);
    }

    public ReportLifecycleStore.Mode mode() { return mode; }

    boolean bindsTo(Caller expectedCaller, CampaignRunHandle expectedHandle) {
        return caller.equals(expectedCaller)
                && handle.caller().equals(expectedHandle.caller())
                && handle.sessionId().equals(expectedHandle.sessionId())
                && handle.runId().equals(expectedHandle.runId())
                && handle.planId().equals(expectedHandle.planId())
                && handle.revision() == expectedHandle.revision();
    }

    CampaignJdbcDurableRunResponseBridgeFactory.ReportAccess reportAccess() {
        return new CampaignJdbcDurableRunResponseBridgeFactory.ReportAccess(owner, SERVER_CAPABILITY, mode);
    }
}
