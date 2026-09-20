package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignReportAccessGrantTest {
    private static final Caller CALLER = new Caller("tenant", "subject", 3);
    private static final CampaignRunHandle HANDLE = new CampaignRunHandle(
            CALLER, "session", "run", "plan", 2, RunStatus.ACTIVE);

    @Test
    void bindsCallerExactHandleAndModeWithoutExposingRawCredentials() {
        CampaignReportAccessGrantResolver resolver = new CampaignReportAccessGrantResolver((caller, handle, mode) ->
                caller.equals(CALLER) && handle.equals(HANDLE)
                        && mode == ReportLifecycleStore.Mode.HISTORY_VIEW
                        ? new CampaignReportAccessGrantResolver.Authorization("report-owner") : null);
        CampaignReportAccessGrant grant = resolver.resolve(new CampaignReportAccessGrantResolver.Request(
                CALLER, HANDLE, ReportLifecycleStore.Mode.HISTORY_VIEW)).orElseThrow();

        assertThat(grant.mode()).isEqualTo(ReportLifecycleStore.Mode.HISTORY_VIEW);
        assertThat(CampaignReportAccessGrant.SERVER_CAPABILITY).isEqualTo("campaign/report/v1");
        assertThat(grant.getClass().getMethods()).extracting(Method::getName)
                .doesNotContain("owner", "capability", "reportAccess");
        assertThat(grant.reportAccess().owner()).isEqualTo("report-owner");
        assertThat(grant.reportAccess().capability()).isEqualTo(CampaignReportAccessGrant.SERVER_CAPABILITY);
    }

    @Test
    void rejectsCallerMismatchSupersededRunAndUnauthorizedExport() {
        CampaignReportAccessGrantResolver resolver = new CampaignReportAccessGrantResolver((caller, handle, mode) ->
                mode == ReportLifecycleStore.Mode.HISTORY_VIEW
                        ? new CampaignReportAccessGrantResolver.Authorization("report-owner") : null);
        assertThatThrownBy(() -> resolver.resolve(new CampaignReportAccessGrantResolver.Request(
                new Caller("other", "subject", 3), HANDLE, ReportLifecycleStore.Mode.HISTORY_VIEW)))
                .hasMessage("REPORT_ACCESS_CALLER_MISMATCH");
        CampaignRunHandle superseded = new CampaignRunHandle(CALLER, "session", "run", "plan", 2,
                RunStatus.SUPERSEDED);
        assertThatThrownBy(() -> new CampaignReportAccessGrantResolver.Request(
                CALLER, superseded, ReportLifecycleStore.Mode.HISTORY_VIEW))
                .hasMessage("REPORT_ACCESS_RUN_SUPERSEDED");
        assertThatThrownBy(() -> resolver.resolve(new CampaignReportAccessGrantResolver.Request(
                CALLER, HANDLE, ReportLifecycleStore.Mode.EXPORT)))
                .hasMessage("REPORT_ACCESS_DENIED");
    }

    @Test
    void requiresAnExplicitAuthorizerAndMode() {
        assertThatThrownBy(() -> new CampaignReportAccessGrantResolver(null))
                .hasMessage("REPORT_ACCESS_AUTHORIZER_REQUIRED");
        assertThatThrownBy(() -> new CampaignReportAccessGrantResolver.Request(CALLER, HANDLE, null))
                .hasMessage("REPORT_ACCESS_MODE_REQUIRED");
    }
}
