package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment.Gap;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import java.util.List;

/** Execution progress only. EXECUTED is never GoalAssessment.ANSWERED or a completed report. */
public record CampaignProgressView(String schemaVersion, String runId, String planId, int revision,
                                   RunStatus runStatus, WorkState workState, DeliveryState deliveryState,
                                   List<GoalProgress> goals, List<StepProgress> steps) {
    public static final String SCHEMA = "campaign-progress/v1";
    public enum WorkState { PENDING, RUNNING, WAITING, BLOCKED, FAILED, EXECUTED, CANCELLED, SUPERSEDED }
    public enum DeliveryState { NOT_ASSESSED }

    public CampaignProgressView { goals = List.copyOf(goals); steps = List.copyOf(steps); }

    public record AvailableOutput(String stepId, String outputName, ArtifactRef artifact) {}
    /** An inaccessible output's artifact ID, scope and metadata are deliberately absent. */
    public record UnavailableOutput(String stepId, String outputName, String reasonCode) {}

    public record StepProgress(String stepId, List<String> goalIds, StepStatus recordedStatus,
                               WorkState workState, String reasonCode, List<String> blockedBy,
                               List<AvailableOutput> availableOutputs, List<UnavailableOutput> unavailableOutputs) {
        public StepProgress {
            goalIds = List.copyOf(goalIds); blockedBy = List.copyOf(blockedBy);
            availableOutputs = List.copyOf(availableOutputs); unavailableOutputs = List.copyOf(unavailableOutputs);
        }
    }

    public record GoalProgress(String goalId, String question, boolean required, WorkState workState,
                               List<String> stepIds, List<Gap> planningGaps,
                               List<AvailableOutput> availableOutputs, List<UnavailableOutput> unavailableOutputs) {
        public GoalProgress {
            stepIds = List.copyOf(stepIds); planningGaps = List.copyOf(planningGaps);
            availableOutputs = List.copyOf(availableOutputs); unavailableOutputs = List.copyOf(unavailableOutputs);
        }
    }
}
