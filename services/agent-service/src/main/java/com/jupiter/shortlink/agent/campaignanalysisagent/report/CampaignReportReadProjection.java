package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Converts one authorized durable report read into a stable, typed view.
 *
 * <p>The lifecycle application service remains the only owner/capability gate.  This class
 * deliberately accepts its already typed read request, calls that service, and removes storage
 * metadata which must never become part of a client response.  The persisted payload predates
 * this projection and therefore has no root schema field; that legacy shape is accepted only
 * when its root is exactly {@code draft + goalAssessments} and the nested draft schema is valid.
 * A future writer may add {@value #PAYLOAD_SCHEMA} at the root.</p>
 */
public final class CampaignReportReadProjection {
    /** Schema exposed to a report consumer, independent of the persisted payload shape. */
    public static final String SCHEMA = "campaign-report-snapshot/v1";

    /** Optional root schema for a future durable payload writer. */
    public static final String PAYLOAD_SCHEMA = "campaign-report-payload/v1";

    private static final Set<String> PAYLOAD_FIELDS = Set.of("schemaVersion", "draft", "goalAssessments");
    private static final ObjectMapper JSON = strictJson();

    private final CampaignReportApplicationService reports;

    public CampaignReportReadProjection(CampaignReportApplicationService reports) {
        this.reports = Objects.requireNonNull(reports, "REPORT_APPLICATION_SERVICE_REQUIRED");
    }

    /**
     * Reads one report through the durable application boundary and projects it without any
     * fallback to a previous report or to a fresh query.  Lifecycle authorization, expiry and
     * HISTORY_VIEW/EXPORT semantics are intentionally delegated unchanged.
     */
    public Optional<Snapshot> read(CampaignReportApplicationService.ReadRequest request) {
        Objects.requireNonNull(request, "REPORT_READ_REQUEST_REQUIRED");
        return reports.read(request).map(stored -> projectAuthorized(stored, request.mode()));
    }

    /**
     * Projects an already authorized lifecycle row without opening another transaction.  Trusted
     * coordinators call this only after locking the row and applying owner/capability/mode/expiry
     * checks in the lifecycle store; this method performs payload and report identity validation
     * and returns a sanitized snapshot.
     */
    public Snapshot projectAuthorized(ReportLifecycleStore.Published stored, ReportLifecycleStore.Mode mode) {
        try {
            if (stored == null || stored.key() == null || mode == null
                    || stored.payloadJson() == null || stored.payloadJson().isBlank()) {
                throw invalid(null);
            }

            JsonNode root = parseObject(stored.payloadJson());
            validatePayloadRoot(root);
            ReportDraft draft = decodeObject(root.get("draft"), ReportDraft.class);
            validateDraftIdentity(stored, draft);
            List<GoalAssessment> goals = decodeGoals(root.get("goalAssessments"));

            return new Snapshot(
                    SCHEMA,
                    mode,
                    new CampaignReportPublisher.ReportRef(stored.key().reportId(), stored.key().revision()),
                    stored.runId(),
                    stored.planRevision(),
                    draft,
                    goals,
                    stored.manifestChecksum(),
                    stored.evidenceRetainedUntil(),
                    stored.retainedUntil(),
                    stored.reuseExpiresAt());
        } catch (ProjectionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    private static JsonNode parseObject(String encoded) {
        try {
            JsonNode root = JSON.readTree(encoded);
            if (root == null || !root.isObject()) throw invalid(null);
            return root;
        } catch (ProjectionFailure failure) {
            throw failure;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    private static void validatePayloadRoot(JsonNode root) {
        Set<String> fields = new HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        if (!PAYLOAD_FIELDS.containsAll(fields)
                || !root.has("draft") || !root.has("goalAssessments")
                || !root.get("draft").isObject() || !root.get("goalAssessments").isArray()) {
            throw invalid(null);
        }
        JsonNode schema = root.get("schemaVersion");
        if (schema != null && !schema.isTextual()) throw invalid(null);
        if (schema != null && !PAYLOAD_SCHEMA.equals(schema.textValue())) throw invalid(null);
    }

    private static void validateDraftIdentity(ReportLifecycleStore.Published stored, ReportDraft draft) {
        if (draft == null || !ReportDraft.SCHEMA.equals(draft.schemaVersion())
                || !stored.key().reportId().equals(draft.reportId())
                || stored.key().revision() != draft.revision()
                || !Objects.equals(stored.runId(), draft.runId())
                || stored.planRevision() != draft.planRevision()) {
            throw invalid(null);
        }
    }

    private static List<GoalAssessment> decodeGoals(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid(null);
        List<GoalAssessment> goals = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode value : node) {
            GoalAssessment goal = decodeObject(value, GoalAssessment.class);
            if (!ids.add(goal.goalId())) throw invalid(null);
            goals.add(goal);
        }
        return List.copyOf(goals);
    }

    private static <T> T decodeObject(JsonNode value, Class<T> type) {
        if (value == null || !value.isObject()) throw invalid(null);
        try {
            return JSON.treeToValue(value, type);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    private static ProjectionFailure invalid(Throwable cause) {
        return cause == null
                ? new ProjectionFailure("REPORT_PAYLOAD_INVALID")
                : new ProjectionFailure("REPORT_PAYLOAD_INVALID", cause);
    }

    private static ObjectMapper strictJson() {
        return JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .build();
    }

    /**
     * Typed report data safe to hand to a response adapter.  Owner and capability are omitted on
     * purpose; they were used only to authorize the read.
     */
    public record Snapshot(String schemaVersion,
                           ReportLifecycleStore.Mode mode,
                           CampaignReportPublisher.ReportRef reportRef,
                           String runId,
                           int planRevision,
                           ReportDraft draft,
                           List<GoalAssessment> goalAssessments,
                           String manifestChecksum,
                           Instant evidenceRetainedUntil,
                           Instant retainedUntil,
                           Instant reuseExpiresAt) {
        public Snapshot {
            if (!SCHEMA.equals(schemaVersion) || mode == null || reportRef == null
                    || runId == null || runId.isBlank() || planRevision < 1 || draft == null
                    || manifestChecksum == null || manifestChecksum.isBlank()
                    || evidenceRetainedUntil == null || retainedUntil == null || reuseExpiresAt == null) {
                throw new IllegalArgumentException("REPORT_SNAPSHOT_INVALID");
            }
            goalAssessments = goalAssessments == null ? List.of() : List.copyOf(goalAssessments);
        }
    }

    private static final class ProjectionFailure extends IllegalStateException {
        private ProjectionFailure(String message) { super(message); }
        private ProjectionFailure(String message, Throwable cause) { super(message, cause); }
    }
}
