package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;
import java.util.Collection;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory P5 publication boundary. It models the transaction shape: validate all fixed
 * evidence first, assess the real draft, then publish one immutable report revision.
 */
public final class CampaignReportPublisher {
    private final GoalAssessor assessor;
    private final EvidenceReader evidenceReader;
    private final Clock clock;
    private final String requiredClientCapability;
    private ReportLifecycleStore lifecycleStore;
    private final Map<Key, PublishedReport> reports = new ConcurrentHashMap<>();

    public CampaignReportPublisher(GoalAssessor assessor, EvidenceReader evidenceReader,
                                   Clock clock, String requiredClientCapability) {
        this.assessor = Objects.requireNonNull(assessor);
        this.evidenceReader = Objects.requireNonNull(evidenceReader);
        this.clock = Objects.requireNonNull(clock);
        if (requiredClientCapability == null || requiredClientCapability.isBlank())
            throw new IllegalArgumentException("REPORT_CLIENT_CAPABILITY_INVALID");
        this.requiredClientCapability = requiredClientCapability;
        this.lifecycleStore = null;
    }

    /** Durable publication constructor. A publisher with this constructor has no in-memory fallback. */
    public CampaignReportPublisher(GoalAssessor assessor, EvidenceReader evidenceReader,
                                   Clock clock, String requiredClientCapability,
                                   ReportLifecycleStore lifecycleStore) {
        this(assessor, evidenceReader, clock, requiredClientCapability);
        this.lifecycleStore = Objects.requireNonNull(lifecycleStore);
    }

    /**
     * Internal configuration check used by the typed application boundary.  It is package
     * private deliberately: callers should use {@link CampaignReportApplicationService} rather
     * than branching on the publisher's storage mode themselves.
     */
    boolean hasDurableStore() {
        return lifecycleStore != null;
    }

    /** Composition guard for a trusted coordinator; callers still use the application service. */
    public boolean usesLifecycleStore(ReportLifecycleStore store) {
        return lifecycleStore == store;
    }

    public PublishedReport publish(PublishRequest request) {
        Objects.requireNonNull(request);
        if (!requiredClientCapability.equals(request.clientCapability()))
            throw new IllegalArgumentException("CLIENT_CAPABILITY_REQUIRED");
        ReportDraft draft = request.draft();
        if (draft == null || !ReportDraft.SCHEMA.equals(draft.schemaVersion())) invalid();
        if (!request.plan().runId().equals(draft.runId()) || !request.plan().planId().equals(draft.planId())
                || request.plan().revision() != draft.planRevision()) invalid();
        LinkedHashSet<String> refs = new LinkedHashSet<>(draft.evidenceArtifactIds());
        request.observations().values().forEach(observation -> refs.addAll(observation.evidenceArtifactIds()));
        List<Evidence> evidence = new ArrayList<>();
        for (String artifactId : refs) evidence.add(validateEvidence(artifactId));
        GoalAssessor.Result assessment = assessor.assess(new GoalAssessor.Input(request.plan(), request.planningAssessment(),
                request.observations(), draft));
        EvidenceManifest manifest = manifest(draft, evidence);
        PublishedReport candidate = new PublishedReport(new ReportRef(draft.reportId(), draft.revision()), draft,
                assessment.goals(), manifest);
        Key key = new Key(draft.reportId(), draft.revision());
        PublishedReport previous = reports.putIfAbsent(key, candidate);
        if (previous == null) return candidate;
        if (previous.equals(candidate)) return previous;
        throw new IllegalStateException("REPORT_REVISION_CONFLICT");
    }

    public Optional<PublishedReport> read(String reportId, int revision) {
        return Optional.ofNullable(reports.get(new Key(reportId, revision)));
    }

    /** Validates and durably publishes one revision. Metadata is explicit so expiry and access are store-owned. */
    public ReportLifecycleStore.Published publishDurable(PublishRequest request, Publication publication) {
        if (lifecycleStore == null) throw new IllegalStateException("REPORT_LIFECYCLE_STORE_REQUIRED");
        PublishedReport report = assessAndManifest(request);
        Instant evidenceRetainedUntil = report.evidenceManifest().entries().stream()
                .map(EvidenceEntry::retainedUntil).min(Comparator.naturalOrder()).orElseThrow(
                        () -> invalid("REPORT_EVIDENCE_NOT_FOUND"));
        String manifestJson = canonicalJson(report.evidenceManifest());
        String payloadJson = canonicalJson(new Payload(report.draft(), report.goalAssessments()));
        ReportLifecycleStore.Draft draft = new ReportLifecycleStore.Draft(
                new ReportLifecycleStore.Key(report.ref().reportId(), report.ref().revision()),
                report.draft().runId(), report.draft().planRevision(), publication.owner(),
                publication.capability(), manifestJson, digest(manifestJson), evidenceRetainedUntil,
                publication.retainedUntil(), publication.reuseExpiresAt(), payloadJson);
        return lifecycleStore.publish(draft);
    }

    /** Fixed-key read path; all authorization, expiry and mode rules remain in the lifecycle store. */
    public Optional<ReportLifecycleStore.Published> read(String reportId, int revision, String owner,
                                                         String capability, ReportLifecycleStore.Mode mode) {
        if (lifecycleStore == null) throw new IllegalStateException("REPORT_LIFECYCLE_STORE_REQUIRED");
        return lifecycleStore.read(new ReportLifecycleStore.Key(reportId, revision), owner, capability, mode);
    }

    private PublishedReport assessAndManifest(PublishRequest request) {
        Objects.requireNonNull(request);
        if (!requiredClientCapability.equals(request.clientCapability()))
            throw new IllegalArgumentException("CLIENT_CAPABILITY_REQUIRED");
        ReportDraft draft = request.draft();
        if (draft == null || !ReportDraft.SCHEMA.equals(draft.schemaVersion())) invalid();
        if (!request.plan().runId().equals(draft.runId()) || !request.plan().planId().equals(draft.planId())
                || request.plan().revision() != draft.planRevision()) invalid();
        LinkedHashSet<String> refs = new LinkedHashSet<>(draft.evidenceArtifactIds());
        request.observations().values().forEach(observation -> refs.addAll(observation.evidenceArtifactIds()));
        List<Evidence> evidence = new ArrayList<>();
        for (String artifactId : refs) evidence.add(validateEvidence(artifactId));
        GoalAssessor.Result assessment = assessor.assess(new GoalAssessor.Input(request.plan(), request.planningAssessment(),
                request.observations(), draft));
        EvidenceManifest manifest = manifest(draft, evidence);
        return new PublishedReport(new ReportRef(draft.reportId(), draft.revision()), draft,
                assessment.goals(), manifest);
    }

    private Evidence validateEvidence(String artifactId) {
        Evidence evidence = evidenceReader.inspect(artifactId).orElseThrow(() -> invalid("REPORT_EVIDENCE_NOT_FOUND"));
        if (!artifactId.equals(evidence.artifactId()) || !evidence.ready() || evidence.checksum().isBlank()
                || evidence.retainedUntil() == null || !clock.instant().isBefore(evidence.retainedUntil()))
            throw invalid("REPORT_EVIDENCE_NOT_READY");
        return evidence;
    }

    private static EvidenceManifest manifest(ReportDraft draft, List<Evidence> evidence) {
        List<EvidenceEntry> entries = evidence.stream().map(item -> new EvidenceEntry(item.artifactId(), item.type(),
                item.schemaVersion(), item.checksum(), item.retainedUntil(), item.readContract())).toList();
        StringBuilder canonical = new StringBuilder(draft.reportId()).append(':').append(draft.revision());
        for (EvidenceEntry entry : entries) canonical.append('|').append(entry.artifactId()).append(':').append(entry.checksum());
        return new EvidenceManifest("campaign-evidence-manifest/v1", draft.reportId(), draft.revision(),
                digest(canonical.toString()), entries);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
    private static void invalid() { throw invalid("REPORT_DEFINITION_MISMATCH"); }

    @FunctionalInterface
    public interface EvidenceReader {
        Optional<Evidence> inspect(String artifactId);
    }

    public record Evidence(String artifactId, String type, String schemaVersion, String checksum,
                           Instant retainedUntil, String readContract, boolean ready) {
        public Evidence {
            if (artifactId == null || artifactId.isBlank() || type == null || type.isBlank()
                    || schemaVersion == null || schemaVersion.isBlank() || checksum == null || checksum.isBlank()
                    || readContract == null || readContract.isBlank())
                throw new IllegalArgumentException("REPORT_EVIDENCE_INVALID");
        }
    }

    public record PublishRequest(PlanSpec plan, PlanningAssessment planningAssessment,
                                 Map<String, GoalAssessor.RequirementObservation> observations,
                                 ReportDraft draft, String clientCapability) {
        public PublishRequest {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(planningAssessment);
            observations = observations == null ? Map.of() : Map.copyOf(observations);
            Objects.requireNonNull(draft);
            if (clientCapability == null || clientCapability.isBlank())
                throw new IllegalArgumentException("REPORT_CLIENT_CAPABILITY_INVALID");
        }
    }

    public record ReportRef(String reportId, int revision) {
        public ReportRef {
            if (reportId == null || reportId.isBlank() || revision < 1)
                throw new IllegalArgumentException("REPORT_REF_INVALID");
        }
    }

    public record EvidenceManifest(String schemaVersion, String reportId, int revision,
                                   String checksum, List<EvidenceEntry> entries) {
        public EvidenceManifest { entries = entries == null ? List.of() : List.copyOf(entries); }
    }

    public record EvidenceEntry(String artifactId, String type, String schemaVersion, String checksum,
                                Instant retainedUntil, String readContract) {}

    public record PublishedReport(ReportRef ref, ReportDraft draft, List<GoalAssessment> goalAssessments,
                                  EvidenceManifest evidenceManifest) {
        public PublishedReport {
            Objects.requireNonNull(ref); Objects.requireNonNull(draft); Objects.requireNonNull(evidenceManifest);
            goalAssessments = goalAssessments == null ? List.of() : List.copyOf(goalAssessments);
        }
    }

    public record Publication(String owner, String capability, Instant retainedUntil, Instant reuseExpiresAt) {
        public Publication {
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(retainedUntil);
            Objects.requireNonNull(reuseExpiresAt);
        }
    }

    private record Payload(ReportDraft draft, List<GoalAssessment> goalAssessments) { }

    /** Canonical JSON keeps persisted bytes stable across retries and map insertion order. */
    private static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        writeJson(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(Object value, StringBuilder out) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String || value instanceof Character || value instanceof Enum<?> || value instanceof Instant) {
            quote(out, value instanceof Enum<?> e ? e.name() : value.toString()); return;
        }
        if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Map<?, ?> map) {
            out.append('{'); boolean first = true;
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (sorted.containsKey(key))
                    throw new IllegalArgumentException("REPORT_SERIALIZATION_DUPLICATE_KEY");
                sorted.put(key, entry.getValue());
            }
            for (var entry : sorted.entrySet()) {
                if (!first) out.append(','); first = false; quote(out, entry.getKey()); out.append(':'); writeJson(entry.getValue(), out);
            }
            out.append('}'); return;
        }
        if (value instanceof Collection<?> values) {
            out.append('['); boolean first = true; for (Object item : values) { if (!first) out.append(','); first = false; writeJson(item, out); } out.append(']'); return;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> fields = new TreeMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) try {
                fields.put(component.getName(), component.getAccessor().invoke(value));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("REPORT_SERIALIZATION_FAILED", e); }
            writeJson(fields, out); return;
        }
        quote(out, value.toString());
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"'); for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); switch (c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t"); default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
        }} out.append('"');
    }

    private record Key(String reportId, int revision) {}
}
