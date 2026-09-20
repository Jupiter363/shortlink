package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignReportReadProjectionTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final String REPORT_ID = "report-projection";
    private static final int REVISION = 2;
    private static final String RUN_ID = "run-projection";
    private static final String PLAN_ID = "plan-projection";
    private static final String OWNER = "owner-projection";
    private static final String CAPABILITY = "report/read";
    // Persisted report payloads use record components only; derived isDeliverable() is not stored.
    private static final ObjectMapper JSON = new ObjectMapper()
            .addMixIn(ReportBlock.class, ReportBlockJsonMixin.class);

    @JsonIgnoreProperties("deliverable")
    private abstract static class ReportBlockJsonMixin { }

    @Test
    void projectsTypedSnapshotAndPassesHistoryModeWithoutExposingStoragePrincipal() {
        Fixture fixture = fixture(payload(fixtureDraft(), List.of(fixtureGoal())), true);

        CampaignReportReadProjection.Snapshot snapshot = fixture.projection.read(request(
                ReportLifecycleStore.Mode.HISTORY_VIEW)).orElseThrow();

        assertThat(snapshot.schemaVersion()).isEqualTo(CampaignReportReadProjection.SCHEMA);
        assertThat(snapshot.mode()).isEqualTo(ReportLifecycleStore.Mode.HISTORY_VIEW);
        assertThat(snapshot.reportRef()).isEqualTo(new CampaignReportPublisher.ReportRef(REPORT_ID, REVISION));
        assertThat(snapshot.runId()).isEqualTo(RUN_ID);
        assertThat(snapshot.planRevision()).isEqualTo(REVISION);
        assertThat(snapshot.draft().reportId()).isEqualTo(REPORT_ID);
        assertThat(snapshot.goalAssessments()).extracting(GoalAssessment::goalId)
                .containsExactly("goal-projection");
        assertThat(fixture.store.modes).containsExactly(ReportLifecycleStore.Mode.HISTORY_VIEW);
        assertThat(List.of(SnapshotComponentNames())).doesNotContain("owner", "capability");
    }

    @Test
    void exportModeIsPreservedAndProjectedListsAreImmutable() {
        Fixture fixture = fixture(payload(fixtureDraft(), List.of(fixtureGoal())), true);

        CampaignReportReadProjection.Snapshot snapshot = fixture.projection.read(request(
                ReportLifecycleStore.Mode.EXPORT)).orElseThrow();

        assertThat(snapshot.mode()).isEqualTo(ReportLifecycleStore.Mode.EXPORT);
        assertThat(fixture.store.modes).containsExactly(ReportLifecycleStore.Mode.EXPORT);
        assertThatThrownBy(() -> snapshot.goalAssessments().add(fixtureGoal()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyPayloadShapeWithoutRootSchemaRemainsAcceptedButUnknownRootFieldsFailClosed() {
        ReportDraft draft = fixtureDraft();
        String legacyShape = json(Map.of("draft", draft, "goalAssessments", List.of(fixtureGoal())));
        Fixture accepted = fixture(legacyShape, true);
        assertThat(accepted.projection.read(request(ReportLifecycleStore.Mode.HISTORY_VIEW))).isPresent();

        String unknownField = json(Map.of("draft", draft, "goalAssessments", List.of(fixtureGoal()),
                "unexpected", true));
        Fixture rejected = fixture(unknownField, true);
        assertInvalidPayload(rejected);
    }

    @Test
    void mismatchedDraftIdentityAndUnparseableGoalsFailClosed() {
        ReportDraft mismatched = new ReportDraft("other-report", REVISION, RUN_ID, PLAN_ID, REVISION,
                fixtureDraft().sections(), List.of());
        Fixture identity = fixture(payload(mismatched, List.of(fixtureGoal())), true);
        assertInvalidPayload(identity);

        Map<String, Object> invalidGoal = new LinkedHashMap<>();
        invalidGoal.put("goalId", "goal-projection");
        invalidGoal.put("status", "NOT_A_STATUS");
        invalidGoal.put("reasonCode", null);
        invalidGoal.put("evidenceArtifactIds", List.of());
        invalidGoal.put("limitations", List.of());
        invalidGoal.put("requirements", List.of());
        String invalidGoalPayload = json(Map.of("schemaVersion", CampaignReportReadProjection.PAYLOAD_SCHEMA,
                "draft", fixtureDraft(), "goalAssessments", List.of(invalidGoal)));
        Fixture goals = fixture(invalidGoalPayload, true);
        assertInvalidPayload(goals);
    }

    @Test
    void emptyDurableReadDoesNotFallbackToPreviousReport() {
        Fixture fixture = fixture(payload(fixtureDraft(), List.of(fixtureGoal())), false);

        assertThat(fixture.projection.read(request(ReportLifecycleStore.Mode.HISTORY_VIEW))).isEmpty();
        assertThat(fixture.store.modes).containsExactly(ReportLifecycleStore.Mode.HISTORY_VIEW);
    }

    private static void assertInvalidPayload(Fixture fixture) {
        assertThatThrownBy(() -> fixture.projection.read(request(ReportLifecycleStore.Mode.HISTORY_VIEW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REPORT_PAYLOAD_INVALID");
    }

    private static CampaignReportApplicationService.ReadRequest request(ReportLifecycleStore.Mode mode) {
        return new CampaignReportApplicationService.ReadRequest(REPORT_ID, REVISION, OWNER, CAPABILITY, mode);
    }

    private static Fixture fixture(String payload, boolean present) {
        RecordingStore store = new RecordingStore(published(payload), present);
        CampaignReportPublisher publisher = new CampaignReportPublisher(new GoalAssessor(), id -> Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC), CAPABILITY, store);
        CampaignReportApplicationService application = new CampaignReportApplicationService(publisher,
                (owner, capability, operation) -> OWNER.equals(owner) && CAPABILITY.equals(capability));
        return new Fixture(store, new CampaignReportReadProjection(application));
    }

    private static ReportLifecycleStore.Published published(String payload) {
        return new ReportLifecycleStore.Published(
                new ReportLifecycleStore.Key(REPORT_ID, REVISION), RUN_ID, REVISION, OWNER, CAPABILITY,
                "{}", "a".repeat(64), NOW.plusSeconds(3600), NOW.plusSeconds(1800), NOW.plusSeconds(2400),
                payload, 1, 0);
    }

    private static ReportDraft fixtureDraft() {
        ReportBlock block = new ReportBlock("metric-projection", ReportBlock.Kind.METRIC, "Visits", null,
                Map.of("value", 12), List.of("artifact-projection"), true);
        return new ReportDraft(REPORT_ID, REVISION, RUN_ID, PLAN_ID, REVISION,
                List.of(new ReportSection("summary", 0, "Summary", List.of("goal-projection"), List.of(block))),
                List.of());
    }

    private static GoalAssessment fixtureGoal() {
        return new GoalAssessment("goal-projection", GoalAssessment.Status.ANSWERED, null,
                List.of("artifact-projection"), List.of(), List.of());
    }

    private static String payload(ReportDraft draft, List<GoalAssessment> goals) {
        return json(Map.of("schemaVersion", CampaignReportReadProjection.PAYLOAD_SCHEMA,
                "draft", draft, "goalAssessments", goals));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String[] SnapshotComponentNames() {
        return java.util.Arrays.stream(CampaignReportReadProjection.Snapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new);
    }

    private record Fixture(RecordingStore store, CampaignReportReadProjection projection) { }

    private static final class RecordingStore implements ReportLifecycleStore {
        private final Published published;
        private final boolean present;
        private final List<Mode> modes = new ArrayList<>();

        private RecordingStore(Published published, boolean present) {
            this.published = published;
            this.present = present;
        }

        @Override
        public Published publish(Draft draft) {
            return published;
        }

        @Override
        public Optional<Published> read(Key key, String owner, String capability, Mode mode) {
            modes.add(mode);
            return present ? Optional.of(published) : Optional.empty();
        }

        @Override public long retain(Key key, String referenceId, String owner, String capability) { return 1; }
        @Override public long release(Key key, String referenceId) { return 1; }
        @Override public boolean cleanup(Key key, long expectedVersion) { return false; }
    }
}
