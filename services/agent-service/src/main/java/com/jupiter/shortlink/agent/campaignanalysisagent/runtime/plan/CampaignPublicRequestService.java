package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.NativeCampaignPlanner;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningProposal;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.AdmittedCampaignAdvance;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseCapabilityGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.*;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunRequest;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatModel;

/** Public orchestration around the existing durable intake. Reads never submit work or synthesize reports. */
public final class CampaignPublicRequestService implements CampaignRunIntake.PreparationHandler {
    @FunctionalInterface public interface PlannerInput {
        PlanningProposal.Request prepare(Caller caller, String session, String requestKey,
                CampaignInterpretedRequest candidate, java.time.Instant expiresAt);
    }
    public interface Delivery {
        Optional<AgentRunResult> read(AgentPrincipal principal, String sessionId, WorkRef reference);
    }
    @FunctionalInterface public interface Cancellation {
        void cancel(AgentPrincipal principal,String sessionId,WorkRef reference);
    }
    private final CampaignPublicRequestStore requests;
    private final JdbcCampaignConversationTurnStore turns;
    private final CampaignCurrentPrincipalResolver principals;
    private final CampaignRunIntake intake;
    private final ChatModel model;
    private final PlannerInput planner;
    private final String profileRef, profileVersion;
    private final Consumer<WorkRef> schedule;
    private Consumer<WorkRef> wake;
    private Cancellation cancellation=(principal,session,reference)->{ throw new IllegalStateException("CAMPAIGN_CANCEL_UNAVAILABLE"); };
    private final Delivery delivery;
    private final Clock clock;
    private final Duration retention;

    public CampaignPublicRequestService(CampaignPublicRequestStore requests, JdbcCampaignConversationTurnStore turns,
            CampaignCurrentPrincipalResolver principals, CampaignRunIntake intake, ChatModel model,
            PlannerInput planner, String profileRef, String profileVersion, Consumer<WorkRef> schedule,
            Delivery delivery, Clock clock, Duration retention) {
        this.requests=Objects.requireNonNull(requests); this.turns=Objects.requireNonNull(turns);
        this.principals=Objects.requireNonNull(principals); this.intake=Objects.requireNonNull(intake);
        this.model=Objects.requireNonNull(model); this.planner=Objects.requireNonNull(planner);
        this.profileRef=Objects.requireNonNull(profileRef); this.profileVersion=Objects.requireNonNull(profileVersion);
        this.schedule=Objects.requireNonNull(schedule); this.delivery=Objects.requireNonNull(delivery);
        this.wake=this.schedule;
        this.clock=Objects.requireNonNull(clock); this.retention=Objects.requireNonNull(retention);
        if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("CAMPAIGN_RETENTION_INVALID");
    }

    /** Explicit continuation may reopen a completed scheduling lease, never a model attempt. */
    public void installWake(Consumer<WorkRef> wake) { this.wake=Objects.requireNonNull(wake); }
    public void installCancellation(Cancellation cancellation) { this.cancellation=Objects.requireNonNull(cancellation); }

    /** Route identity only: no scheduling, report read, model or statistics execution. */
    public boolean recordedRequest(AgentRunRequest request) {
        if (request.requestKey() == null) return false;
        AgentPrincipal current = principals.bindCurrent(request.principal(), request.sessionId());
        if (!current.equals(request.principal())) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        return turns.findByKey(current, request.sessionId(), request.requestKey()).isPresent()
                || requests.exists(caller(current), request.sessionId(), request.requestKey());
    }

    public AgentRunResult run(AgentRunRequest request) {
        if (!request.clientCapabilities().contains(CampaignResponseCapabilityGate.CAPABILITY_V2))
            throw new IllegalArgumentException("CAMPAIGN_CLIENT_UPGRADE_REQUIRED");
        AgentPrincipal current = request.operation()==AgentRunRequest.Operation.NEW
                ? principals.bindCurrent(request.principal(), request.sessionId())
                : principals.resolve(caller(request.principal()), request.sessionId());
        if (!current.equals(request.principal())) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        WorkRef reference;
        if (request.operation()==AgentRunRequest.Operation.NEW) {
            if (request.requestKey()==null) throw new IllegalArgumentException("CAMPAIGN_REQUEST_KEY_REQUIRED");
            var previous = request.previousRunId()==null ? null
                    : turns.requireRun(current, request.sessionId(), request.previousRunId());
            String question=request.message();
            if (previous!=null) {
                AgentRunRequest.Operation command=continuationCommand(request.message(),previous.originalQuestion());
                if (command!=AgentRunRequest.Operation.NEW) {
                    authorize(current,previous.workRef());
                    if (command==AgentRunRequest.Operation.CONTINUE) wake.accept(previous.workRef());
                    return read(current,request.sessionId(),previous.workRef());
                }
                // Keep the persisted chain rather than replacing its context with the last short follow-up.
                authorize(current,previous.workRef());
                question = "上一轮问题（用于续接上下文）：\n"+requests.read(previous.workRef()).question()
                        +"\n本轮补充或新的分析要求：\n"+request.message();
            }
            var registered=requests.register(caller(current),request.sessionId(),request.requestKey(),question,
                    java.time.Instant.ofEpochMilli(clock.instant().plus(retention).toEpochMilli()));
            reference=registered.reference();
            turns.recordVerified(current,request.sessionId(),request.requestKey(),request.message(),"{}",
                    CampaignResponseCapabilityGate.PROTOCOL_V2,reference,request.previousRunId(),registered.expiresAt());
            schedule.accept(reference);
        } else {
            reference=request.continuation().workRef();
            turns.require(current,request.sessionId(),reference);
            authorize(current,reference);
            if (request.operation()==AgentRunRequest.Operation.CONTINUE) wake.accept(reference);
            if (request.operation()==AgentRunRequest.Operation.CANCEL) cancellation.cancel(current,request.sessionId(),reference);
        }
        // Scheduling is persistent. The HTTP request never waits for a model or remote statistics job.
        return read(current,request.sessionId(),reference);
    }

    public AgentRunResult progress(AgentPrincipal principal,String sessionId,WorkRef reference) {
        turns.require(principal,sessionId,reference); authorize(principal,reference);
        return read(principal,sessionId,reference);
    }

    private AgentRunResult read(AgentPrincipal principal,String session,WorkRef reference) {
        authorize(principal,reference);
        var request=requests.read(reference);
        boolean expired=!request.expiresAt().isAfter(clock.instant());
        if (!expired && "ACCEPTED".equals(request.state())) {
            var result=delivery.read(principal,session,reference);
            if (result.isPresent()) return result.get();
        }
        boolean needs="NEEDS_INPUT".equals(request.state());
        boolean unknown="UNKNOWN".equals(request.state()) || "DISPATCHING".equals(request.state());
        List<String> missing=List.of();
        if (needs && request.response()!=null) {
            try { missing=CampaignInterpretedRequest.parse(request.response(),request.question()).clarification(); }
            catch (IllegalArgumentException ignored) { /* Only expose a controlled repair instruction. */ }
        }
        if (needs && missing.isEmpty()) missing=List.of("请明确需要分析的分组、期间或具体目标。");
        NextAction next=expired ? new NextAction(NextActionKind.NEEDS_INPUT,"CAMPAIGN_REQUEST_EXPIRED",List.of("原请求已到期，请重新发起分析。"))
                : needs ? new NextAction(NextActionKind.NEEDS_INPUT,"REQUIREMENTS_NEED_INPUT",missing)
                : unknown ? new NextAction(NextActionKind.WAIT,"MODEL_OUTCOME_UNKNOWN",List.of())
                : new NextAction(NextActionKind.WAIT,"ANALYSIS_IN_PROGRESS",List.of());
        String answer=expired ? "原请求已到期；继续操作不会重新提数，请重新发起分析。" : needs ? String.join("\n",missing) : unknown
                ? "本次模型调用结果尚未确认，已保留原请求，未重复调用。" : "分析请求已保存，正在规划或等待原任务完成。";
        return new AgentRunResult(session,UUID.randomUUID().toString(),answer,List.of(),List.of(),List.of(),List.of(),
                List.of(),needs?missing:List.of(),null,new AgentRunRequest.Continuation(reference.runId(),reference.workId()),
                new AgentRunResult.Progress(reference.runId(),null,0,expired?ExecutionStatus.FAILED:unknown?ExecutionStatus.UNKNOWN:ExecutionStatus.WAITING,next));
    }

    @Override public boolean recognizes(WorkRef reference) { return reference.workId().startsWith("request-"); }
    @Override public void authorize(AgentPrincipal principal,WorkRef reference) {
        var request=requests.read(reference);
        if (!request.caller().equals(caller(principal))
                || !principal.equals(principals.resolve(request.caller(),request.sessionId())))
            throw new SecurityException("CAMPAIGN_REQUEST_ACCESS_DENIED");
    }

    @Override public AdmittedCampaignAdvance.Operation load(WorkRef reference,ProcessExecutionScope scope) {
        var request=requests.read(reference);
        AgentPrincipal current=principals.resolve(request.caller(),request.sessionId());
        authorize(current,reference);
        return () -> {
            var state=requests.read(reference);
            if ("ACCEPTED".equals(state.state())) {
                intake.advancePrepared(new WorkRef(reference.runId(),state.targetWorkId()),scope);
                return;
            }
            if ("NEEDS_INPUT".equals(state.state())) return;
            if (state.callbackActive() || "UNKNOWN".equals(state.state()) || "DISPATCHING".equals(state.state()))
                throw new IllegalStateException("CAMPAIGN_INTERPRETATION_UNRESOLVED");
            if (!state.expiresAt().isAfter(clock.instant())) throw new IllegalStateException("CAMPAIGN_REQUEST_EXPIRED");
            if ("PREPARED".equals(state.state())) interpret(state,current,scope);
            state=requests.read(reference);
            if (state.callbackActive() || !"READY".equals(state.state()))
                throw new IllegalStateException("CAMPAIGN_INTERPRETATION_UNRESOLVED");
            CampaignInterpretedRequest candidate;
            try { candidate=CampaignInterpretedRequest.parse(state.response(),state.question()); }
            catch (IllegalArgumentException invalid) { requests.needsInput(state,"REQUIREMENTS_INVALID"); return; }
            if (!candidate.clarification().isEmpty()) { requests.needsInput(state,"REQUIREMENTS_NEED_INPUT"); return; }
            authorize(current,reference);
            PlanningProposal.Request prepared;
            try { prepared=planner.prepare(state.caller(),state.sessionId(),state.requestKey(),candidate,state.expiresAt()); }
            catch (IllegalArgumentException invalid) { requests.needsInput(state,"REQUIREMENTS_UNSUPPORTED"); return; }
            WorkRef target=intake.registerPlanning(current,state.sessionId(),state.requestKey(),profileRef,profileVersion,
                    prepared,state.expiresAt());
            requests.bind(state,target);
            intake.advancePrepared(target,scope);
        };
    }

    private void interpret(CampaignPublicRequestStore.Request state,AgentPrincipal current,ProcessExecutionScope scope) throws Exception {
        String schema=CampaignInterpretedRequest.schemaJson();
        String prompt=FrozenCampaignRun.encode(Map.of("question",state.question(),"frozenToday",
                state.createdAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()))+"\n"+schema;
        String[] attempt={null};
        try {
            new NativeCampaignPlanner(model,(actual,live)-> {
                authorize(current,state.reference());
                if (!actual.tools().isEmpty() || actual.messages().size()!=2
                        || !"system".equals(actual.messages().get(0).role())
                        || !CampaignInterpretedRequest.instructions().equals(actual.messages().get(0).text())
                        || !"user".equals(actual.messages().get(1).role())
                        || !prompt.equals(actual.messages().get(1).text()))
                    throw new IllegalArgumentException("CAMPAIGN_INTERPRETATION_PROMPT_CHANGED");
                attempt[0]=requests.begin(state,CampaignRunStore.sha256(ModelInvocationRegistry.encodeRequest(actual)));
                try {
                    authorize(current,state.reference());
                    var response=live.get();
                    authorize(current,state.reference());
                    requests.complete(state,attempt[0],response.text());
                    return response;
                } catch (RuntimeException | Error failure) {
                    requests.unknown(state,attempt[0]);
                    throw failure;
                } finally { requests.callbackExited(state,attempt[0]); }
            },scope,ModelInvocationRegistry.Limits.defaults())
                    .generateStructured(CampaignInterpretedRequest.instructions(),prompt,schema);
        } catch (Exception | Error failure) {
            if (attempt[0]!=null) requests.unknown(state,attempt[0]);
            throw failure;
        }
    }

    private static Caller caller(AgentPrincipal principal) {
        if (principal==null || principal.system()) throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        return new Caller(principal.tenantId(),principal.username(),principal.authVersion());
    }

    /** Only exact read/continue commands bypass planning. A changed scope or requirement remains a new request. */
    static AgentRunRequest.Operation continuationCommand(String message,String previousQuestion) {
        if (message==null) return AgentRunRequest.Operation.NEW;
        var selection=java.util.regex.Pattern.compile("(?su)^\\s*分析范围：分组「[^」]+」；gid=([^;\\s]+);\\s*");
        var selected=selection.matcher(message);
        String body=message;
        if (selected.find()) {
            var before=selection.matcher(Objects.toString(previousQuestion,""));
            if (!before.find() || !selected.group(1).equals(before.group(1))) return AgentRunRequest.Operation.NEW;
            body=message.substring(selected.end());
        }
        body=body.strip().replaceAll("[。？！?!]+$", "").toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("进度","查询进度","查看进度","查看结果","查看分析结果","结果出来了吗","check progress","check result").contains(body))
            return AgentRunRequest.Operation.PROGRESS;
        if (java.util.Set.of("继续","继续分析","继续执行","继续查询","continue","resume").contains(body))
            return AgentRunRequest.Operation.CONTINUE;
        return AgentRunRequest.Operation.NEW;
    }
}
