package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.Approval;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import java.util.Map;

/** Execution gates shared by a fixed capability and one admitted local CALL; no planning API. */
public interface CapabilityExecution extends AutoCloseable {
    void requireCurrent();
    ChildRecord child(ChildSpec spec, CampaignStepExecution.ChildCall call) throws Exception;
    Map<String, ArtifactRef> local(ChildSpec spec, Approval approval, ArtifactAuthorizer authorizer,
                                  CampaignStepExecution.LocalCall calculation) throws Exception;
    @Override void close();
}
