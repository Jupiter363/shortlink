package com.jupiter.shortlink.agent.infrastructure.config;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in timer for short durable references. All real work still passes the single shared intake. */
public final class CampaignPublicWorkScheduler implements AutoCloseable {
    private final CampaignPublicRequestStore requests;
    private final CampaignDueWorkStore due;
    private final CampaignDueWorkDispatcher dispatcher;
    private final ScheduledExecutorService timer;
    public CampaignPublicWorkScheduler(JdbcTemplate jdbc,CampaignPublicRequestStore requests,
            CampaignDueWorkStore due,CampaignRunIntake intake,Clock clock) {
        this.requests=requests;this.due=due;
        dispatcher=new CampaignDueWorkDispatcher(due,intake::submitBackground,schedulingState(jdbc,requests,clock),
                CampaignDueWorkDispatcher.Settings.defaults());
        timer=Executors.newSingleThreadScheduledExecutor(task->{
            Thread thread=new Thread(task,"campaign-due-work");thread.setDaemon(false);return thread;
        });
    }

    /** Durable observations only; this never releases callbacks or grants a dispatch/recovery permit. */
    static CampaignDueWorkDispatcher.WorkState schedulingState(JdbcTemplate jdbc,CampaignPublicRequestStore requests,Clock clock) {
        var workState=new CampaignDueWorkState(jdbc);
        return reference->{
            Instant now=clock.instant();
            String planningId=null;
            if (reference.workId().startsWith("request-")) {
                var request=requests.read(reference);
                if (request.cancelled()) return CampaignDueWorkDispatcher.Decision.DONE;
                var preparation=preparationDecision(request.state(),request.callbackActive(),request.expiresAt(),now);
                if (preparation!=null) return preparation;
                if (request.targetWorkId()!=null && request.targetWorkId().startsWith("planning-"))
                    planningId=request.targetWorkId();
            } else if (!jdbc.query("SELECT request_id FROM campaign_public_request WHERE run_id=? LIMIT 1",
                    (rs,row)->rs.getString(1),reference.runId()).isEmpty()) {
                // Retire old aliases before the next submission; an existing callback is allowed to exit.
                return CampaignDueWorkDispatcher.Decision.DONE;
            } else if (reference.workId().startsWith("planning-")) {
                planningId=reference.workId();
            }
            if (planningId!=null) {
                var planning=jdbc.query("SELECT request_state,callback_active,expires_at FROM campaign_planning_request WHERE request_id=? AND run_id=?",
                        (rs,row)->new Preparation(rs.getString(1),rs.getBoolean(2),Instant.ofEpochMilli(rs.getLong(3))),planningId,reference.runId());
                if (planning.size()!=1) return CampaignDueWorkDispatcher.Decision.BLOCKED;
                var preparation=preparationDecision(planning.get(0).state(),planning.get(0).active(),planning.get(0).expiresAt(),now);
                if (preparation!=null) return preparation;
            }
            // Run callbacks still go through the existing proof-based recovery coordinator.
            return workState.inspect(reference);
        };
    }

    private record Preparation(String state,boolean active,Instant expiresAt) {}
    private static CampaignDueWorkDispatcher.Decision preparationDecision(String state,boolean active,Instant expiresAt,Instant now) {
        if (java.util.Set.of("UNKNOWN","NEEDS_INPUT","REJECTED").contains(state))
            return CampaignDueWorkDispatcher.Decision.BLOCKED;
        // Expiry retires polling, not the callback: it neither proves death nor permits another model call.
        if (active) return expiresAt.isAfter(now) && java.util.Set.of("DISPATCHING","READY").contains(state)
                ? CampaignDueWorkDispatcher.Decision.WAIT : CampaignDueWorkDispatcher.Decision.BLOCKED;
        return "DISPATCHING".equals(state) ? CampaignDueWorkDispatcher.Decision.BLOCKED : null;
    }
    public void start() { timer.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS); }
    public void tick() {
        try {
            requests.undiscovered(16).forEach(due::schedule);
            due.discoverRegistered(16,true);
            dispatcher.tick();
        } catch (RuntimeException failure) {
            // Do not log prompts, remote response bodies or credentials from provider exceptions.
            LoggerFactory.getLogger(getClass()).warn("Campaign due-work pass unavailable ({})",failure.getClass().getSimpleName());
        }
    }
    @Override public void close() { timer.shutdown(); }
}
