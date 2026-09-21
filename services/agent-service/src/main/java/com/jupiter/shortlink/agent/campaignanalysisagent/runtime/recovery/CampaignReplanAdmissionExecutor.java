package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes only a candidate that crossed the E104 admission boundary.
 *
 * <p>This is deliberately a transport-neutral seam. The public request contains the E102
 * handoff and E104 admission, never a raw candidate. Token resolution happens first, the binder
 * repeats the exact identity/hash checks, and only then does the runtime factory open a persisted
 * runtime. It does not register an HTTP, chat, tool, Graph, or Spring entry.</p>
 */
public final class CampaignReplanAdmissionExecutor {
    private final CampaignReplanTrustedAdapter.RunTokenResolver tokenResolver;
    private final RuntimeExecutor runtimeExecutor;

    public CampaignReplanAdmissionExecutor(CampaignReplanRuntimeFactory runtimeFactory,
                                            CampaignReplanTrustedAdapter.RunTokenResolver tokenResolver) {
        this(tokenResolver, runtimeExecutor(runtimeFactory));
    }

    private static RuntimeExecutor runtimeExecutor(CampaignReplanRuntimeFactory runtimeFactory) {
        Objects.requireNonNull(runtimeFactory, "REPLAN_RUNTIME_FACTORY_REQUIRED");
        return (owner, token, request) -> runtimeFactory.open(owner, token).execute(request);
    }

    /** Package-safe test seam; production composition should use the factory constructor. */
    CampaignReplanAdmissionExecutor(CampaignReplanTrustedAdapter.RunTokenResolver tokenResolver,
                                     RuntimeExecutor runtimeExecutor) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver, "REPLAN_TOKEN_RESOLVER_REQUIRED");
        this.runtimeExecutor = Objects.requireNonNull(runtimeExecutor, "REPLAN_RUNTIME_EXECUTOR_REQUIRED");
    }

    /** Resolves, binds, and executes one admitted candidate. */
    public CampaignReplanApplicationService.Response executeAdmitted(Request request) throws Exception {
        Objects.requireNonNull(request, "REPLAN_ADMISSION_EXECUTION_REQUEST_REQUIRED");
        verifyTransportIdentity(request);

        Optional<RunToken> resolved = tokenResolver.resolve(request.owner(), request.sessionId(), request.runId());
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalStateException("REPLAN_RUN_TOKEN_NOT_FOUND");
        }
        RunToken token = resolved.get();
        CampaignReplanApplicationService.Request typed = CampaignReplanAdmissionBinder.bind(
                request.handoff(), request.admission(), token, request.capability());
        return runtimeExecutor.execute(request.owner(), token, typed);
    }

    private static void verifyTransportIdentity(Request request) {
        if (request.capability() != CampaignReplanApplicationService.REQUIRED_CAPABILITY) {
            throw new SecurityException("REPLAN_ADMISSION_EXECUTION_CAPABILITY_INVALID");
        }
        if (!request.handoff().hasBaselineIdentity()) {
            throw new SecurityException("REPLAN_ADMISSION_EXECUTION_HANDOFF_IDENTITY_MISSING");
        }
        if (!request.owner().equals(request.handoff().baselineOwner())
                || !request.sessionId().equals(request.handoff().baselineSessionId())
                || !request.runId().equals(request.handoff().request().baseline().plan().runId())) {
            throw new SecurityException("REPLAN_ADMISSION_EXECUTION_IDENTITY_MISMATCH");
        }
    }

    @FunctionalInterface
    interface RuntimeExecutor {
        CampaignReplanApplicationService.Response execute(
                Caller owner, RunToken token, CampaignReplanApplicationService.Request request)
                throws Exception;
    }

    /** Trusted transport request; candidate data is available only through the admission. */
    public record Request(Caller owner, String sessionId, String runId,
                          CampaignReplanApplicationService.Capability capability,
                          CampaignReplanPlanningHandoff.Result handoff,
                          CampaignReplanCandidateAdmission.CandidateAdmission admission) {
        public Request {
            validateOwner(owner);
            requireId(sessionId, "REPLAN_SESSION_REQUIRED");
            requireId(runId, "REPLAN_RUN_ID_REQUIRED");
            Objects.requireNonNull(capability, "REPLAN_CAPABILITY_REQUIRED");
            Objects.requireNonNull(handoff, "REPLAN_HANDOFF_REQUIRED");
            Objects.requireNonNull(admission, "REPLAN_ADMISSION_REQUIRED");
        }

        private static void validateOwner(Caller owner) {
            Objects.requireNonNull(owner, "REPLAN_OWNER_REQUIRED");
            requireId(owner.tenantId(), "REPLAN_OWNER_INVALID");
            if (owner.subject() == null || owner.subject().isBlank() || owner.subject().length() > 128
                    || owner.authVersion() < 1) {
                throw new IllegalArgumentException("REPLAN_OWNER_INVALID");
            }
        }

        private static void requireId(String value, String code) {
            if (value == null || value.isBlank() || value.length() > 96
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) {
                throw new IllegalArgumentException(code);
            }
        }
    }
}
