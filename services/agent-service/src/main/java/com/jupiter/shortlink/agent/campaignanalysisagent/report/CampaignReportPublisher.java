package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
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
    private final Map<Key, PublishedReport> reports = new ConcurrentHashMap<>();

    public CampaignReportPublisher(GoalAssessor assessor, EvidenceReader evidenceReader,
                                   Clock clock, String requiredClientCapability) {
        this.assessor = Objects.requireNonNull(assessor);
        this.evidenceReader = Objects.requireNonNull(evidenceReader);
        this.clock = Objects.requireNonNull(clock);
        if (requiredClientCapability == null || requiredClientCapability.isBlank())
            throw new IllegalArgumentException("REPORT_CLIENT_CAPABILITY_INVALID");
        this.requiredClientCapability = requiredClientCapability;
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

    private record Key(String reportId, int revision) {}
}
