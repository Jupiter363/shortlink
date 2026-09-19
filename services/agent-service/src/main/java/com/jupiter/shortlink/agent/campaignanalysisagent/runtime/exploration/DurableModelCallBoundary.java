package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Approval;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.ModelActionSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Request;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Response;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One server-frozen model turn. No turn allocation, retry loop, native checkpoint ownership or
 * production registration. The live supplier is synchronous: its actual exit owns callbackExited.
 */
public final class DurableModelCallBoundary implements ModelCallBoundary {
    private final CampaignRunStore runs;
    private final StepPermit step;
    private final ModelActionSpec action;
    private final ChildSpec child;
    private final Approval approval;
    private final ArtifactAuthorizer authorizer;

    public DurableModelCallBoundary(CampaignRunStore runs, StepPermit step, ModelActionSpec action,
                                   ChildSpec child, Approval approval, ArtifactAuthorizer authorizer) {
        this.runs = Objects.requireNonNull(runs);
        this.step = Objects.requireNonNull(step);
        this.action = Objects.requireNonNull(action);
        this.child = Objects.requireNonNull(child);
        this.approval = Objects.requireNonNull(approval);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    @Override
    public Response call(Request actualRequest, Supplier<Response> liveCall) {
        // The request and active callback must commit before a provider can receive any I/O.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw failure("MODEL_CALL_REQUIRES_COMMITTED_PREPARATION");
        if (actualRequest == null || liveCall == null) throw failure("MODEL_REQUEST_MISMATCH");
        String actual;
        try { actual = ModelInvocationRegistry.encodeRequest(actualRequest); }
        catch (Throwable rejected) { throw failure("MODEL_REQUEST_MISMATCH"); }
        if (!actual.equals(approval.invocation().requestJson())) throw failure("MODEL_REQUEST_MISMATCH");

        ChildRecord prepared = prepare();
        if (prepared.state() == ChildState.READY) {
            try { return runs.readModelResponse(step.runToken(), child.childId(), approval, authorizer); }
            catch (SecurityException denied) { throw fenced(); }
            catch (Throwable unavailable) { throw failure("MODEL_RESPONSE_UNAVAILABLE"); }
        }
        if (prepared.state() != ChildState.PREPARED) throw failure("MODEL_RESULT_UNKNOWN");

        DispatchPermit permit;
        try { permit = runs.beginModelDispatch(step, child.childId(), approval, authorizer); }
        catch (SecurityException denied) { throw fenced(); }
        catch (Throwable unavailable) { throw failure("MODEL_DISPATCH_UNAVAILABLE"); }

        boolean published = false;
        try {
            // Recheck the real running step, frozen expiry and every actual input ACL/hash immediately
            // before the supplier. Preparing the same identity cannot create or dispatch another turn.
            ChildRecord live = prepare();
            if (live.state() != ChildState.DISPATCHING || !live.callbackActive()
                    || !permit.attemptId().equals(live.attemptId()) || permit.attemptVersion() != live.attemptVersion()
                    || permit.purpose() != live.purpose() || !runs.mayDispatch(permit)) throw fenced();
            Response response = Objects.requireNonNull(liveCall.get());
            if (!runs.mayDispatch(permit)) throw fenced();
            runs.publishModelResponse(step, permit, approval, response, authorizer);
            published = true;
            return response;
        } catch (SecurityException denied) {
            throw fenced();
        } catch (Throwable unknown) {
            // Provider messages, stack traces and suppressed exceptions never become native messages.
            throw failure("MODEL_RESULT_UNKNOWN");
        } finally {
            if (!published) {
                try { runs.markUnresolved(permit); }
                catch (Throwable notRecorded) {
                    // A cancelled/revised token cannot mutate its old result. A storage failure also
                    // leaves a non-retryable attempt; neither case permits returning an unsaved response.
                }
            }
            try { runs.callbackExited(permit); }
            catch (Throwable uncertainExit) { throw failure("MODEL_CALLBACK_EXIT_UNCONFIRMED"); }
        }
    }

    private ChildRecord prepare() {
        try { return runs.prepareModelChild(step, action, child, approval, authorizer); }
        catch (SecurityException denied) { throw fenced(); }
        catch (Throwable rejected) { throw failure("MODEL_INVOCATION_REJECTED"); }
    }

    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private static SecurityException fenced() { return new SecurityException("MODEL_EXECUTION_FENCED"); }
}
