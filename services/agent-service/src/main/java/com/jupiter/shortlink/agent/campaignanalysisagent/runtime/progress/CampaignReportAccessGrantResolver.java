package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.util.Objects;
import java.util.Optional;

/** Issues a request-bound report grant only after principal and operation authorization. */
public final class CampaignReportAccessGrantResolver {
    private final AccessAuthorizer authorizer;

    public CampaignReportAccessGrantResolver(AccessAuthorizer authorizer) {
        this.authorizer = Objects.requireNonNull(authorizer, "REPORT_ACCESS_AUTHORIZER_REQUIRED");
    }

    public Optional<CampaignReportAccessGrant> resolve(Request request) {
        Objects.requireNonNull(request, "REPORT_ACCESS_GRANT_REQUEST_REQUIRED");
        Caller caller = request.caller();
        CampaignRunHandle handle = request.handle();
        if (!caller.equals(handle.caller()))
            throw new SecurityException("REPORT_ACCESS_CALLER_MISMATCH");
        Authorization authorization = authorizer.authorize(caller, handle, request.mode());
        if (authorization == null)
            throw new SecurityException("REPORT_ACCESS_DENIED");
        return Optional.of(CampaignReportAccessGrant.issue(caller, handle, request.mode(),
                authorization.owner()));
    }

    @FunctionalInterface
    public interface AccessAuthorizer {
        Authorization authorize(Caller caller, CampaignRunHandle handle, ReportLifecycleStore.Mode mode);
    }

    /** Trusted server decision; owner is resolved by authorization infrastructure, never by transport. */
    public record Authorization(String owner) {
        public Authorization {
            if (owner == null || owner.isBlank())
                throw new IllegalArgumentException("REPORT_ACCESS_OWNER_REQUIRED");
        }
    }

    public record Request(Caller caller, CampaignRunHandle handle, ReportLifecycleStore.Mode mode) {
        public Request {
            Objects.requireNonNull(caller, "REPORT_ACCESS_CALLER_REQUIRED");
            Objects.requireNonNull(handle, "REPORT_ACCESS_HANDLE_REQUIRED");
            Objects.requireNonNull(mode, "REPORT_ACCESS_MODE_REQUIRED");
            if (handle.status() == com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus.SUPERSEDED)
                throw new SecurityException("REPORT_ACCESS_RUN_SUPERSEDED");
        }
    }
}
