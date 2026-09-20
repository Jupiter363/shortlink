package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignRunReportBindingVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final RunDefinition RUN = new RunDefinition(new Caller("tenant", "user", 1), "session", "run", "plan", 2, "{}");

    @Test
    void bindsOnlyReadyReportWithMatchingRunAndRevision() {
        RecordingStore store = new RecordingStore(true, "run", 2);
        CampaignRunReportBindingVerifier verifier = new CampaignRunReportBindingVerifier(store, "owner", "cap");
        assertThat(verifier.mayBind(RUN, new ReportRef("report", 1))).isTrue();
        assertThat(store.mode).isEqualTo(ReportLifecycleStore.Mode.HISTORY_VIEW);
        assertThat(verifier.retain(new ReportRef("report", 1), "binding-1")).isEqualTo(7);
        assertThat(verifier.release(new ReportRef("report", 1), "binding-1")).isEqualTo(8);
    }

    @Test
    void rejectsMissingOrMismatchedReportsAndInvalidConstruction() {
        assertThatThrownBy(() -> new CampaignRunReportBindingVerifier(new RecordingStore(false, "run", 2), "", "cap"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("REPORT_OWNER_INVALID");
        assertThat(new CampaignRunReportBindingVerifier(new RecordingStore(false, "run", 2), "owner", "cap")
                .mayBind(RUN, new ReportRef("report", 1))).isFalse();
        assertThat(new CampaignRunReportBindingVerifier(new RecordingStore(true, "other", 2), "owner", "cap")
                .mayBind(RUN, new ReportRef("report", 1))).isFalse();
        assertThat(new CampaignRunReportBindingVerifier(new RecordingStore(true, "run", 3), "owner", "cap")
                .mayBind(RUN, new ReportRef("report", 1))).isFalse();
    }

    private static final class RecordingStore implements ReportLifecycleStore {
        private final boolean present; private final String runId; private final int planRevision; private Mode mode;
        private RecordingStore(boolean present, String runId, int planRevision) { this.present = present; this.runId = runId; this.planRevision = planRevision; }
        @Override public Published publish(Draft draft) { throw new UnsupportedOperationException(); }
        @Override public Optional<Published> read(Key key, String owner, String capability, Mode mode) {
            this.mode = mode;
            return present ? Optional.of(new Published(key, runId, planRevision, owner, capability, "{}", "a".repeat(64),
                    NOW.plusSeconds(100), NOW.plusSeconds(100), NOW.plusSeconds(100), "{}", 1, 0)) : Optional.empty();
        }
        @Override public long retain(Key key, String referenceId, String owner, String capability) { return 7; }
        @Override public long release(Key key, String referenceId) { return 8; }
        @Override public boolean cleanup(Key key, long expectedVersion) { return false; }
    }
}
