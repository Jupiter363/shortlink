package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Scheduling hints from durable status only; the intake always resolves the current principal again. */
public final class CampaignDueWorkState implements CampaignDueWorkDispatcher.WorkState {
    private final JdbcTemplate jdbc;
    public CampaignDueWorkState(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public CampaignDueWorkDispatcher.Decision inspect(WorkRef reference) {
        if (reference.workId().startsWith("planning-")) {
            var planning = jdbc.query("SELECT request_state FROM campaign_planning_request WHERE request_id=? AND run_id=?",
                    (rs, row) -> rs.getString(1), reference.workId(), reference.runId());
            if (planning.size() != 1) return CampaignDueWorkDispatcher.Decision.BLOCKED;
            if (planning.get(0).equals("UNKNOWN") || planning.get(0).equals("DISPATCHING") || planning.get(0).equals("REJECTED"))
                return CampaignDueWorkDispatcher.Decision.BLOCKED;
            if (!planning.get(0).equals("ACCEPTED")) return CampaignDueWorkDispatcher.Decision.CONTINUE;
        }
        var active = jdbc.query("SELECT revision FROM campaign_run_ledger WHERE run_id=? AND run_status='ACTIVE' ORDER BY revision DESC LIMIT 1",
                (rs, row) -> rs.getInt(1), reference.runId());
        if (active.isEmpty()) {
            Integer known = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_ledger WHERE run_id=?", Integer.class, reference.runId());
            return known != null && known > 0 ? CampaignDueWorkDispatcher.Decision.DONE : CampaignDueWorkDispatcher.Decision.CONTINUE;
        }
        int revision = active.get(0);
        Integer unknownModels = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND revision=? "
                + "AND child_mode='MODEL' AND child_state IN ('DISPATCHING','UNRESOLVED')", Integer.class, reference.runId(), revision);
        if (unknownModels != null && unknownModels > 0) return CampaignDueWorkDispatcher.Decision.BLOCKED;
        var states = jdbc.query("SELECT step_status FROM campaign_step_ledger WHERE run_id=? AND revision=?",
                (rs, row) -> rs.getString(1), reference.runId(), revision);
        if (!states.isEmpty() && states.stream().allMatch("SUCCEEDED"::equals)) return CampaignDueWorkDispatcher.Decision.DONE;
        if (states.stream().anyMatch(value -> value.equals("FAILED") || value.equals("BLOCKED"))) {
            // Typed capacity/child reconciliation blockers are handled by the existing coordinator.
            Integer actionable = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND revision=? "
                    + "AND child_mode<>'MODEL' AND child_state IN ('PREPARED','DISPATCHING','WAITING','UNRESOLVED')",
                    Integer.class, reference.runId(), revision);
            if (actionable == null || actionable == 0) {
                Integer adoptedWaiting = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE run_id=? AND revision=? "
                        + "AND step_status='BLOCKED' AND reason='ADOPTED_STATISTICS_WAITING'", Integer.class, reference.runId(), revision);
                if (adoptedWaiting != null && adoptedWaiting > 0) {
                    Integer originalJobs = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_consumer c "
                            + "JOIN campaign_statistics_job_binding b ON b.binding_id=c.binding_id "
                            + "JOIN campaign_step_ledger s ON s.run_id=c.run_id AND s.revision=c.revision AND s.step_id=c.step_id "
                            + "WHERE c.run_id=? AND c.revision=? AND c.active=TRUE AND b.producer_revision<c.revision "
                            + "AND b.cancel_intent='NONE' AND s.reason='ADOPTED_STATISTICS_WAITING'", Integer.class, reference.runId(), revision);
                    if (originalJobs != null && originalJobs > 0) return CampaignDueWorkDispatcher.Decision.CONTINUE;
                }
                return CampaignDueWorkDispatcher.Decision.BLOCKED;
            }
        }
        return CampaignDueWorkDispatcher.Decision.CONTINUE;
    }
}
