package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignPlanningStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignDueWorkStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignReportDeliveryService;
import com.jupiter.shortlink.agent.harness.runtime.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.*;

/** Exact current Run projection. A finished Step alone is never a successful analysis. */
public final class CampaignPublicDeliveryAdapter implements CampaignPublicRequestService.Delivery {
    private final CampaignStatisticsFixedRuntime runtime;
    private final CampaignReportDeliveryService reports;
    private final CampaignPublicRequestStore requests;
    public CampaignPublicDeliveryAdapter(CampaignStatisticsFixedRuntime runtime,CampaignReportDeliveryService reports,
            CampaignPublicRequestStore requests) {
        this.runtime=Objects.requireNonNull(runtime); this.reports=Objects.requireNonNull(reports);
        this.requests=Objects.requireNonNull(requests);
    }
    @Override public Optional<AgentRunResult> read(AgentPrincipal principal,String session,WorkRef reference) {
        return read(principal,session,reference,null);
    }
    @Override public Optional<AgentRunResult> read(AgentPrincipal principal,String session,WorkRef reference,
            CampaignDueWorkStore.Entry scheduling) {
        Caller owner=new Caller(principal.tenantId(),principal.username(),principal.authVersion());
        if (!principal.equals(runtime.principals().resolve(owner,session))) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        var request=requests.read(reference);
        if (!owner.equals(request.caller()) || !session.equals(request.sessionId()) || !reference.equals(request.reference())
                || !"ACCEPTED".equals(request.state()) || request.targetWorkId()==null || !request.targetWorkId().startsWith("planning-"))
            throw new SecurityException("CAMPAIGN_REQUEST_ACCESS_DENIED");
        if (scheduling!=null && !reference.equals(scheduling.reference()))
            throw new SecurityException("CAMPAIGN_SCHEDULING_REFERENCE_CHANGED");
        var existing=runtime.runs().loadRun(owner,reference.runId());
        if (existing.isEmpty()) return Optional.of(planning(principal,session,reference,request,scheduling));
        var run=existing.get(); var definition=run.definition();
        var frozen=FrozenCampaignRun.read(definition);
        if (!session.equals(definition.sessionId()) || !runtime.extension().profile().runAuthorizer()
                .mayExecute(owner,frozen.inputs())) throw new SecurityException("CAMPAIGN_RUN_ACCESS_DENIED");
        var report=run.status()==RunStatus.ACTIVE?reports.latest(owner,run.token()).orElse(null):null;
        var steps=runtime.steps().snapshot(owner,reference.runId()).steps();
        Set<String> expectedSteps=new HashSet<>(frozen.plan().steps().stream().map(step->step.stepId()).toList());
        Set<String> recordedSteps=new HashSet<>(steps.stream().map(step->step.spec().stepId()).toList());
        boolean finished=expectedSteps.equals(recordedSteps) && steps.stream().allMatch(step->step.status()==StepStatus.SUCCEEDED);
        boolean failed=steps.stream().anyMatch(step->step.status()==StepStatus.FAILED);
        boolean blocked=steps.stream().anyMatch(step->step.status()==StepStatus.BLOCKED);
        boolean complete=report!=null && report.availability()==com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.COMPLETE;
        ExecutionStatus status=complete?ExecutionStatus.SUCCEEDED:failed?ExecutionStatus.FAILED:ExecutionStatus.WAITING;
        NextAction action=complete?NextAction.none():new NextAction(NextActionKind.WAIT,"ANALYSIS_IN_PROGRESS",List.of());
        if (run.status()!=RunStatus.ACTIVE) {
            status=run.status()==RunStatus.SUPERSEDED?ExecutionStatus.SUPERSEDED:ExecutionStatus.CANCELLED; action=NextAction.none();
        } else if (!complete && failed) action=new NextAction(NextActionKind.REPLAN,"ANALYSIS_STEP_FAILED",List.of());
        else if (!complete && blocked) {
            status=ExecutionStatus.UNKNOWN;
            action=new NextAction(NextActionKind.REPLAN,"ANALYSIS_REQUIRES_ATTENTION",List.of());
        } else if (!complete && finished) {
            var gaps=frozen.assessment().gaps();
            List<String> missing=gaps.stream().filter(gap->gap.reason()==PlanningAssessment.GapReason.NEEDS_INPUT)
                    .map(PlanningAssessment.Gap::explanation).filter(value->value!=null&&!value.isBlank()).distinct().toList();
            status=missing.isEmpty()?ExecutionStatus.UNKNOWN:ExecutionStatus.WAITING;
            action=missing.isEmpty()?new NextAction(NextActionKind.REPLAN,"GOAL_EVIDENCE_INCOMPLETE",List.of())
                    :new NextAction(NextActionKind.NEEDS_INPUT,"GOAL_EVIDENCE_INCOMPLETE",missing);
        }
        String answer=report==null ? finished ? "计划已结束，但尚未形成可交付结果；请按缺失条件补充信息或调整分析目标。"
                : blocked ? "有分析步骤的执行结果尚未确认或所需条件未满足，不能直接重复执行；原任务状态已保留，请补充条件或重新规划。"
                : failed ? "有分析步骤执行失败，原请求和已返回的结果已保留，请调整目标后重新规划。"
                : "计划已登记，正在获取证据；完成的部分会在此处展示。" : report.answer();
        if (scheduling!=null && scheduling.state()==CampaignDueWorkStore.State.BLOCKED
                && run.status()==RunStatus.ACTIVE && !complete && !failed && action.kind()!=NextActionKind.NEEDS_INPUT) {
            // Only an untouched execution can offer a manual restart. A scheduler failure is not proof
            // that a submitted job/model has stopped; those receipts remain authoritative in the intake.
            boolean untouched=!request.cancelled() && !request.callbackActive() && !expectedSteps.isEmpty()
                    && (steps.isEmpty() || expectedSteps.equals(recordedSteps))
                    && steps.stream().allMatch(step->(step.status()==StepStatus.PENDING || step.status()==StepStatus.READY)
                            && !step.callbackActive() && step.attemptId()==null && step.outputs().isEmpty())
                    && runtime.runs().children(run.token()).isEmpty();
            if (untouched) {
                var receipt=receipt(principal,session,reference,request);
                untouched=receipt.state()==JdbcCampaignPlanningStore.State.ACCEPTED && !receipt.callbackActive();
            }
            status=untouched?ExecutionStatus.FAILED:ExecutionStatus.UNKNOWN;
            action=new NextAction(untouched?NextActionKind.CONTINUE:NextActionKind.WAIT,"ADVANCE_REQUIRES_ATTENTION",List.of());
            String paused=untouched?"执行准备已暂停，尚未派发分析步骤；可继续原请求。"
                    :"本次分析推进已暂停，已保留原请求和已有结果；未决调用不会重复派发，请等待原执行结果核验。";
            answer=report==null?paused:paused+"\n\n"+report.answer();
        }
        if (run.status()!=RunStatus.ACTIVE) answer=run.status()==RunStatus.SUPERSEDED
                ? "该计划已被新版本替代，请查看新的分析任务。" : "本次分析已取消。";
        var latest=runtime.runs().loadRun(owner,reference.runId()).orElseThrow(()->new SecurityException("CAMPAIGN_RUN_CHANGED"));
        if (!latest.definition().equals(definition) || !latest.token().equals(run.token())
                || !principal.equals(runtime.principals().resolve(owner,session)))
            throw new SecurityException("CAMPAIGN_RUN_CHANGED");
        return Optional.of(new AgentRunResult(session,UUID.randomUUID().toString(),answer,List.of(),List.of(),List.of(),
                List.of(),List.of(),report==null?List.of():report.limitations(),report,
                new AgentRunRequest.Continuation(reference.runId(),reference.workId()),
                new AgentRunResult.Progress(reference.runId(),definition.planId(),definition.revision(),status,action,
                        goals(frozen,run.status(),report))));
    }

    private static List<AgentRunResult.GoalProgress> goals(FrozenCampaignRun frozen,RunStatus runStatus,
            AgentRunResult.Report report) {
        Map<String,GoalAssessment> published=new HashMap<>();
        if (report!=null) report.goalAssessments().forEach(goal->published.put(goal.goalId(),goal));
        return frozen.plan().goals().stream().map(goal->{
            var assessed=published.get(goal.goalId());
            if (assessed!=null) return new AgentRunResult.GoalProgress(goal.goalId(),goal.question(),
                    assessed.status(),assessed.limitations());
            Set<String> requirements=new HashSet<>();
            Set<String> required=new HashSet<>();
            frozen.assessment().requirements().stream().filter(item->goal.goalId().equals(item.goalId())).forEach(item->{
                requirements.add(item.requirementId());
                if (item.required()) required.add(item.requirementId());
            });
            var gaps=frozen.assessment().gaps().stream().filter(gap->requirements.contains(gap.requirementId())).toList();
            var status=GoalAssessment.Status.PENDING;
            if (runStatus!=RunStatus.ACTIVE) status=GoalAssessment.Status.CANCELLED;
            else if (gaps.stream().anyMatch(gap->required.contains(gap.requirementId())
                    && gap.reason()==PlanningAssessment.GapReason.NEEDS_INPUT)) status=GoalAssessment.Status.NEEDS_INPUT;
            else if (gaps.stream().anyMatch(gap->required.contains(gap.requirementId())
                    && gap.reason()==PlanningAssessment.GapReason.UNSUPPORTED)) status=GoalAssessment.Status.UNSUPPORTED;
            return new AgentRunResult.GoalProgress(goal.goalId(),goal.question(),status,gaps.stream()
                    .map(PlanningAssessment.Gap::explanation).filter(value->value!=null&&!value.isBlank()).distinct().toList());
        }).toList();
    }

    private JdbcCampaignPlanningStore.Header receipt(AgentPrincipal principal,String session,WorkRef reference,
            CampaignPublicRequestStore.Request request) {
        var receipt=runtime.intake().planningReceipt(principal,new WorkRef(reference.runId(),request.targetWorkId()));
        if (!receipt.caller().equals(request.caller()) || !session.equals(receipt.sessionId())
                || !reference.runId().equals(receipt.runId()) || !request.targetWorkId().equals(receipt.requestId())
                || !request.requestKey().equals(receipt.requestKey())) throw new SecurityException("CAMPAIGN_PLANNING_REFERENCE_CHANGED");
        return receipt;
    }

    private AgentRunResult planning(AgentPrincipal principal,String session,WorkRef reference,
            CampaignPublicRequestStore.Request request,CampaignDueWorkStore.Entry scheduling) {
        var receipt=receipt(principal,session,reference,request);
        ExecutionStatus status=ExecutionStatus.WAITING;
        NextAction next=new NextAction(NextActionKind.WAIT,"ANALYSIS_IN_PROGRESS",List.of());
        String answer="请求已接收，正在完成原计划的校验与执行登记。";
        if (receipt.state()==JdbcCampaignPlanningStore.State.UNKNOWN) {
            status=ExecutionStatus.UNKNOWN;
            next=new NextAction(NextActionKind.WAIT,"MODEL_OUTCOME_UNKNOWN",List.of());
            answer="本次规划调用的结果无法确认，尚未创建分析任务；已保留原调用，不会重复发送。";
        } else if (receipt.state()==JdbcCampaignPlanningStore.State.REJECTED) {
            status=ExecutionStatus.FAILED;
            next=new NextAction(NextActionKind.WAIT,"PLANNING_UNRESOLVED",List.of());
            answer="本次生成的分析计划未通过服务端校验，分析已停止。原问题已保留，尚未执行数据查询；需要修复规划后重新发起分析。";
        } else if (receipt.state()==JdbcCampaignPlanningStore.State.DISPATCHING) {
            status=receipt.callbackActive()?ExecutionStatus.RUNNING:ExecutionStatus.UNKNOWN;
            next=new NextAction(NextActionKind.WAIT,"MODEL_OUTCOME_UNKNOWN",List.of());
            answer="原规划调用尚未返回可确认结果；未启动数据查询，也不会重复发起调用。";
        } else if (scheduling!=null && scheduling.state()==CampaignDueWorkStore.State.BLOCKED) {
            status=ExecutionStatus.UNKNOWN;
            next=new NextAction(NextActionKind.WAIT,"ADVANCE_REQUIRES_ATTENTION",List.of());
            answer="原计划的准备与执行登记已暂停；已保留原请求，请等待原规划结果核验。";
        }
        if (!principal.equals(runtime.principals().resolve(request.caller(),session)))
            throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        return new AgentRunResult(session,UUID.randomUUID().toString(),answer,List.of(),List.of(),List.of(),List.of(),List.of(),
                next.requiredInputs(),null,new AgentRunRequest.Continuation(reference.runId(),reference.workId()),
                new AgentRunResult.Progress(reference.runId(),null,0,status,next));
    }
}
