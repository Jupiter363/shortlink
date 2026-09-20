package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol.Status;
import java.util.Optional;

/** One cancel operation for an existing physical binding; never grants a fresh statistics submission. */
public interface CampaignStatisticsCancellationStore {
    enum State { PREPARED, DISPATCHING, UNKNOWN, TERMINAL }
    enum Purpose { CANCEL, RECONCILE }

    record Operation(String bindingId, long bindingVersion, State state, String observedState,
                     String reasonCode, String attemptId, long attemptVersion, Purpose purpose,
                     boolean callbackActive) {}
    record Permit(RunToken token, String bindingId, long bindingVersion, String attemptId,
                  long attemptVersion, Purpose purpose) {}
    record ReadAccess(Binding binding, ChildRecord sourceChild) {}

    /** Atomic with the original cancellation intent; no operation is created while consumers remain. */
    Optional<Operation> request(RunToken current, String bindingId);
    Optional<Operation> operation(RunToken current, String bindingId);
    /** Available before request, so the coordinator can check current scope rights before writing intent. */
    ReadAccess readAccess(RunToken current, String bindingId);
    /** Commits a unique POST or subsequent GET attempt before returning. An ambient transaction is rejected. */
    Permit begin(RunToken current, String bindingId);
    boolean mayDispatch(Permit permit);
    /** Saves an exact dispatched response, including a late terminal fact after current authorization is lost. */
    Operation recordStatus(Permit permit, Status status);
    void markUnknown(Permit permit);
    /** Only actual callback finally may call this; cancellation of a Future is not evidence of exit. */
    void callbackExited(Permit permit);
}
