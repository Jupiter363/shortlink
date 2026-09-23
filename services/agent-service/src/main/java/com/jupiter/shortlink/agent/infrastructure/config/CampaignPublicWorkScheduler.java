package com.jupiter.shortlink.agent.infrastructure.config;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
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
            CampaignDueWorkStore due,CampaignRunIntake intake) {
        this.requests=requests;this.due=due;
        var workState=new CampaignDueWorkState(jdbc);
        dispatcher=new CampaignDueWorkDispatcher(due,intake::submitBackground,reference->{
            if (reference.workId().startsWith("request-")) {
                var request=requests.read(reference);
                if (request.cancelled()) return CampaignDueWorkDispatcher.Decision.DONE;
                if (request.callbackActive() || java.util.Set.of("UNKNOWN","DISPATCHING","NEEDS_INPUT").contains(request.state()))
                    return CampaignDueWorkDispatcher.Decision.BLOCKED;
            } else if (!jdbc.query("SELECT request_id FROM campaign_public_request WHERE run_id=? LIMIT 1",
                    (rs,row)->rs.getString(1),reference.runId()).isEmpty()) {
                // Retire old aliases before the next submission; an existing callback is allowed to exit.
                return CampaignDueWorkDispatcher.Decision.DONE;
            }
            return workState.inspect(reference);
        },CampaignDueWorkDispatcher.Settings.defaults());
        timer=Executors.newSingleThreadScheduledExecutor(task->{
            Thread thread=new Thread(task,"campaign-due-work");thread.setDaemon(false);return thread;
        });
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
