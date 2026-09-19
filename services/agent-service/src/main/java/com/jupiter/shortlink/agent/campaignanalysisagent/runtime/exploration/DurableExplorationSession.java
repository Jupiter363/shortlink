package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Message;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import java.util.List;

/** Durable business facts for the native loop; native checkpoints are never execution authority. */
public interface DurableExplorationSession extends ExplorationLedger, ModelCallBoundary {
    /** Complete current authorized request, including server-frozen system/user messages. */
    List<Message> canonicalMessages();

    /** Exact real callback owner for an admitted child operation. */
    CallPermit callPermit(long attempt);

    /** Publication acknowledgement only; losing this acknowledgement never re-executes a tool. */
    void acknowledgeCanonical();
}
