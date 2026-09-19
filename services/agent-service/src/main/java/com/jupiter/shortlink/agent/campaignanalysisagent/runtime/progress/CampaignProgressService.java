package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressView.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Unregistered, read-only projection for the future versioned client. Does not dispatch, refresh,
 * assess goals or load artifact payloads. Snapshot ownership and every emitted result ref are
 * checked against current rights; clients must reauthorize again when following an artifact ref.
 */
public final class CampaignProgressService {
    private final CampaignStepStore steps;
    private final CampaignRunStore runs;
    private final ArtifactAuthorizer authorizer;

    public CampaignProgressService(CampaignStepStore steps, CampaignRunStore runs, ArtifactAuthorizer authorizer) {
        this.steps = Objects.requireNonNull(steps);
        this.runs = Objects.requireNonNull(runs);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    public CampaignProgressView read(Caller caller, String runId) {
        var snapshot = steps.snapshot(caller, runId);
        var run = snapshot.run();
        var frozen = FrozenCampaignRun.read(run.definition());
        PlanSpec plan = frozen.plan();
        Map<String, StepRecord> records = indexRecords(plan, snapshot.steps());
        Map<String, List<PlanningAssessment.Gap>> gaps = goalGaps(plan, frozen.assessment());
        Map<String, StepProgress> projected = new LinkedHashMap<>();
        for (PlanSpec.Step step : plan.steps()) {
            StepRecord record = records.get(step.stepId());
            StepStatus status = record == null ? StepStatus.PENDING : record.status();
            List<String> blockedBy = step.dependsOn().stream().filter(dependency ->
                    projected.get(dependency).recordedStatus() != StepStatus.SUCCEEDED).toList();
            WorkState state = stepState(status, blockedBy.stream().map(projected::get).toList());
            List<AvailableOutput> available = new ArrayList<>();
            List<UnavailableOutput> unavailable = new ArrayList<>();
            if (status == StepStatus.SUCCEEDED) {
                for (var output : new TreeMap<>(record.outputs()).entrySet()) {
                    try {
                        var metadata = runs.inspectArtifact(caller, output.getValue(), authorizer);
                        available.add(new AvailableOutput(step.stepId(), output.getKey(), metadata.ref()));
                    } catch (SecurityException denied) {
                        String reason = "ARTIFACT_EXPIRED".equals(denied.getMessage())
                                ? "OUTPUT_EXPIRED" : "OUTPUT_ACCESS_DENIED";
                        unavailable.add(new UnavailableOutput(step.stepId(), output.getKey(), reason));
                    } catch (IllegalStateException missing) {
                        if (!"ARTIFACT_NOT_FOUND".equals(missing.getMessage())) throw missing;
                        unavailable.add(new UnavailableOutput(step.stepId(), output.getKey(), "OUTPUT_UNAVAILABLE"));
                    }
                }
            }
            projected.put(step.stepId(), new StepProgress(step.stepId(), step.goalIds(), status,
                    terminal(run.status(), state), reason(record, state, blockedBy), blockedBy, available, unavailable));
        }
        List<GoalProgress> goals = new ArrayList<>();
        for (PlanSpec.Goal goal : plan.goals()) {
            List<StepProgress> related = projected.values().stream().filter(step -> step.goalIds().contains(goal.goalId())).toList();
            List<PlanningAssessment.Gap> goalGaps = gaps.getOrDefault(goal.goalId(), List.of());
            WorkState state = aggregate(related.stream().map(StepProgress::workState).toList(), !goalGaps.isEmpty());
            goals.add(new GoalProgress(goal.goalId(), goal.question(), goal.required(), terminal(run.status(), state),
                    related.stream().map(StepProgress::stepId).toList(), goalGaps,
                    related.stream().flatMap(step -> step.availableOutputs().stream()).toList(),
                    related.stream().flatMap(step -> step.unavailableOutputs().stream()).toList()));
        }
        return new CampaignProgressView(CampaignProgressView.SCHEMA, plan.runId(), plan.planId(), plan.revision(), run.status(),
                terminal(run.status(), aggregate(goals.stream().map(GoalProgress::workState).toList(), false)),
                DeliveryState.NOT_ASSESSED, goals, List.copyOf(projected.values()));
    }

    private static WorkState stepState(StepStatus status, List<StepProgress> blockers) {
        return switch (status) {
            case RUNNING -> WorkState.RUNNING;
            case WAITING -> WorkState.WAITING;
            case BLOCKED -> WorkState.BLOCKED;
            case FAILED -> WorkState.FAILED;
            case SUCCEEDED -> WorkState.EXECUTED;
            case PENDING, READY -> {
                Set<WorkState> upstream = new HashSet<>(blockers.stream().map(StepProgress::workState).toList());
                if (upstream.contains(WorkState.FAILED) || upstream.contains(WorkState.BLOCKED)) yield WorkState.BLOCKED;
                if (upstream.contains(WorkState.WAITING) || upstream.contains(WorkState.RUNNING)) yield WorkState.WAITING;
                yield WorkState.PENDING;
            }
        };
    }

    private static WorkState aggregate(List<WorkState> states, boolean hasGaps) {
        for (WorkState active : List.of(WorkState.RUNNING, WorkState.WAITING, WorkState.PENDING))
            if (states.contains(active)) return active;
        if (hasGaps || states.contains(WorkState.BLOCKED)) return WorkState.BLOCKED;
        if (states.contains(WorkState.FAILED)) return WorkState.FAILED;
        return states.isEmpty() ? WorkState.PENDING : WorkState.EXECUTED;
    }

    private static String reason(StepRecord record, WorkState state, List<String> blockedBy) {
        if (record != null && record.reason() != null && record.reason().matches("[A-Z][A-Z0-9_]{0,95}"))
            return record.reason();
        if (!blockedBy.isEmpty()) return "DEPENDENCY_" + state.name();
        return switch (state) {
            case WAITING -> "RESULT_PENDING";
            case BLOCKED -> "STEP_BLOCKED";
            case FAILED -> "STEP_FAILED";
            default -> null;
        };
    }

    private static WorkState terminal(RunStatus status, WorkState otherwise) {
        return switch (status) {
            case ACTIVE -> otherwise;
            case CANCELLED -> WorkState.CANCELLED;
            case SUPERSEDED -> WorkState.SUPERSEDED;
        };
    }

    private static Map<String, StepRecord> indexRecords(PlanSpec plan, List<StepRecord> records) {
        Map<String, StepRecord> result = new HashMap<>();
        for (StepRecord record : records) if (result.put(record.spec().stepId(), record) != null) invalid();
        Set<String> goalIds = new HashSet<>();
        for (PlanSpec.Goal goal : plan.goals()) if (!goalIds.add(goal.goalId())) invalid();
        Set<String> visited = new HashSet<>();
        for (PlanSpec.Step step : plan.steps()) {
            if (visited.contains(step.stepId()) || !visited.containsAll(step.dependsOn())
                    || !goalIds.containsAll(step.goalIds())) invalid();
            visited.add(step.stepId());
            StepRecord record = result.get(step.stepId());
            if (!result.isEmpty() && (record == null || !record.spec().dependsOn().equals(step.dependsOn())
                    || !record.spec().allowedOutputs().containsAll(record.outputs().keySet()))) invalid();
        }
        if (!result.isEmpty() && !visited.equals(result.keySet())) invalid();
        return result;
    }

    private static Map<String, List<PlanningAssessment.Gap>> goalGaps(PlanSpec plan, PlanningAssessment assessment) {
        if (!plan.planId().equals(assessment.planId()) || plan.revision() != assessment.revision()) invalid();
        Set<String> goalIds = new HashSet<>(plan.goals().stream().map(PlanSpec.Goal::goalId).toList());
        Map<String, String> requirementGoals = new HashMap<>();
        for (var requirement : assessment.requirements()) {
            if (!goalIds.contains(requirement.goalId())
                    || requirementGoals.put(requirement.requirementId(), requirement.goalId()) != null) invalid();
        }
        Map<String, List<PlanningAssessment.Gap>> result = new HashMap<>();
        for (var gap : assessment.gaps()) {
            String goalId = requirementGoals.get(gap.requirementId());
            if (goalId == null) invalid();
            result.computeIfAbsent(goalId, ignored -> new ArrayList<>()).add(gap);
        }
        return result;
    }

    private static void invalid() { throw new IllegalStateException("PROGRESS_DEFINITION_MISMATCH"); }
}
