package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec.ExecutorRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol.Status;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver.Target;

/** Consumer authority for one actual existing statistics producer, never a second job ledger. */
public interface CampaignStatisticsConsumerStore {
    enum CancelIntent { NONE, REQUESTED, CONFIRMED }

    record Binding(String bindingId, Caller owner, String producerRunId, int producerRevision,
                   String producerChildId, String producerDefinitionHash, String actionId,
                   ExecutorRef executor, String outputContractRef, String jobId, String requestId,
                   String requestHash, Target target, long expiresAtMillis, long version,
                   CancelIntent cancelIntent, boolean localOnly) {}

    /** Exact server-resolved expectation; jobId alone is never an adoption contract. */
    record Expectation(String stepId, ExecutorRef executor, String outputContractRef,
                       String requestHash, Target target) {}
    record Consumer(String consumerId, String bindingId, String runId, int revision,
                    Expectation expectation, boolean active) {}
    record Consumption(Binding binding, Consumer consumer, RunToken sourceToken) {}
    record CancelDecision(String bindingId, long bindingVersion, CancelIntent state, boolean dispatchRequired) {}

    @FunctionalInterface interface Authorizer {
        /** Pure current grant; this is called while local database locks are held. */
        boolean mayConsume(RunToken current, Binding binding, Expectation expectation);
    }

    static String consumerId(RunDefinition definition, String stepId, String bindingId) {
        return "consumer-" + CampaignRunStore.sha256(definition.runId() + ":" + definition.revision()
                + ":" + stepId.length() + ":" + stepId + ":" + bindingId);
    }

    static String bindingId(Caller owner, String jobId) {
        return "statistics-binding-" + CampaignRunStore.sha256(owner.tenantId().length() + ":" + owner.tenantId()
                + owner.subject().length() + ":" + owner.subject() + jobId.length() + ":" + jobId);
    }

    /** Called only after a real authorized RECONCILE status response with a positive remote expiry. */
    Binding pin(DispatchPermit permit, Status status, Target target);
    Consumer adopt(RunToken current, String consumerId, String bindingId, Expectation expected, Authorizer authorizer);
    Consumption resolve(RunToken current, String consumerId, Authorizer authorizer);
    void retire(RunToken current, String consumerId);
    CancelDecision requestCancel(RunToken current, String bindingId);
    /** Trusted receiver of a real cancelled status; an unknown/lost acknowledgement never reopens NONE. */
    void confirmCancelled(RunToken current, String bindingId, long bindingVersion, Status status);
}
