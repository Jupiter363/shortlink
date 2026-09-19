package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded single-page persistence; it never combines page payloads into a result-sized object. */
public final class JdbcCampaignStatisticsResultStore implements CampaignStatisticsResultStore {
    private static final int PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Limits limits;
    private final int pageBytes;
    private final JdbcCampaignRunStore runs;

    public JdbcCampaignStatisticsResultStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this(jdbc, transactions, clock, Limits.defaults(), DEFAULT_PAGE_BYTES);
    }

    public JdbcCampaignStatisticsResultStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
                                            Limits limits, int pageBytes) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        if (pageBytes < 1) throw new IllegalArgumentException("A positive page byte limit is required");
        this.pageBytes = pageBytes;
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Statistics results require one writable REQUIRED DataSource transaction");
        this.runs = new JdbcCampaignRunStore(jdbc, transactions, clock, limits);
    }

    @Override
    public Optional<Receipt> receipt(RunToken token, String childId) {
        return transaction(() -> {
            ChildRecord child = runs.child(token, childId).orElseThrow(() -> new IllegalStateException("CHILD_NOT_FOUND"));
            Optional<StoredReceipt> stored = find(token.definition().runId(), token.definition().revision(), childId);
            stored.ifPresent(value -> binding(child, value.receipt().spec()));
            return stored.map(StoredReceipt::receipt);
        });
    }

    @Override
    public Receipt initialize(DispatchPermit permit, ReceiptSpec spec) {
        validateSpec(spec);
        return transaction(() -> {
            ChildRecord child = mutation(permit);
            binding(child, spec);
            unexpired(spec);
            Optional<StoredReceipt> existing = find(permit);
            if (existing.isPresent()) {
                if (!existing.get().receipt().spec().equals(spec)) fail("STATISTICS_RECEIPT_CHANGED");
                if (existing.get().receipt().published()) fail("STATISTICS_RECEIPT_PUBLISHED");
                return existing.get().receipt();
            }
            String specJson = encode(spec, limits.definitionBytes());
            String specHash = CampaignRunStore.sha256(specJson);
            var definition = permit.token().definition();
            jdbc.update("INSERT INTO campaign_statistics_receipt (run_id,revision,child_id,artifact_id,spec_json,spec_hash,"
                            + "next_page_index,stored_pages,stored_rows,stored_bytes,chain_hash,published,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,0,0,0,0,?,FALSE,?,?)",
                    definition.runId(), definition.revision(), permit.childId(), spec.artifactId(), specJson, specHash,
                    initialChain(specHash), clock.millis(), clock.millis());
            return find(permit).orElseThrow().receipt();
        });
    }

    @Override
    public Receipt append(DispatchPermit permit, Page page) {
        Objects.requireNonNull(page);
        return transaction(() -> {
            ChildRecord child = mutation(permit);
            StoredReceipt stored = find(permit).orElseThrow(() -> new IllegalStateException("STATISTICS_RECEIPT_NOT_FOUND"));
            Receipt receipt = stored.receipt();
            binding(child, receipt.spec());
            unexpired(receipt.spec());
            if (receipt.published()) fail("STATISTICS_RECEIPT_PUBLISHED");
            int bytes = validatePage(receipt.spec(), page);
            JsonNode snapshot = object(page.snapshotJson(), limits.artifactBytes());
            JsonNode metrics = object(page.metricsJson(), limits.artifactBytes());
            if (receipt.storedPages() > 0 && (!snapshot.equals(object(receipt.snapshotJson(), limits.artifactBytes()))
                    || !metrics.equals(object(receipt.metricsJson(), limits.artifactBytes())))) fail("STATISTICS_PAGE_CONTEXT_CHANGED");
            String checksum = CampaignRunStore.sha256(page.payloadJson());
            var definition = permit.token().definition();
            Optional<PageHeader> previous = jdbc.query("SELECT page_index,next_page_index,row_count,byte_count,checksum "
                            + "FROM campaign_statistics_page WHERE run_id=? AND revision=? AND child_id=? AND page_index=?",
                    (rs, row) -> header(rs), definition.runId(), definition.revision(), permit.childId(), page.pageIndex())
                    .stream().findFirst();
            if (previous.isPresent()) {
                PageHeader expected = new PageHeader(page.pageIndex(), page.nextPageIndex(), page.rowCount(), bytes, checksum);
                if (!previous.get().equals(expected)) fail("STATISTICS_PAGE_CONFLICT");
                return receipt;
            }
            if (page.pageIndex() != receipt.nextPageIndex() || page.pageIndex() != receipt.storedPages())
                fail("STATISTICS_PAGE_NOT_CONTIGUOUS");
            String chain = nextChain(receipt.chainHash(), page.pageIndex(), checksum, page.rowCount());
            jdbc.update("INSERT INTO campaign_statistics_page (run_id,revision,child_id,page_index,next_page_index,"
                            + "payload_json,checksum,row_count,byte_count,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    definition.runId(), definition.revision(), permit.childId(), page.pageIndex(), page.nextPageIndex(),
                    page.payloadJson(), checksum, page.rowCount(), bytes, clock.millis());
            jdbc.update("UPDATE campaign_statistics_receipt SET snapshot_json=?,metrics_json=?,next_page_index=?,stored_pages=?,"
                            + "stored_rows=?,stored_bytes=?,chain_hash=?,updated_at=? WHERE run_id=? AND revision=? AND child_id=?",
                    receipt.storedPages() == 0 ? page.snapshotJson() : receipt.snapshotJson(),
                    receipt.storedPages() == 0 ? page.metricsJson() : receipt.metricsJson(),
                    Math.addExact(receipt.storedPages(), 1), Math.addExact(receipt.storedPages(), 1),
                    Math.addExact(receipt.storedRows(), page.rowCount()), Math.addExact(stored.bytes(), bytes), chain, clock.millis(),
                    definition.runId(), definition.revision(), permit.childId());
            return find(permit).orElseThrow().receipt();
        });
    }

    @Override
    public ArtifactRef publish(DispatchPermit permit) {
        return transaction(() -> {
            ChildRecord child = mutation(permit);
            StoredReceipt stored = find(permit).orElseThrow(() -> new IllegalStateException("STATISTICS_RECEIPT_NOT_FOUND"));
            Receipt receipt = stored.receipt();
            binding(child, receipt.spec());
            unexpired(receipt.spec());
            if (receipt.published()) fail("STATISTICS_RECEIPT_PUBLISHED");
            if (!receipt.complete() || receipt.nextPageIndex() != receipt.requiredPages()
                    || receipt.snapshotJson() == null || receipt.metricsJson() == null) fail("STATISTICS_RESULT_INCOMPLETE");
            verifyPages(permit, stored);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("jobId", receipt.spec().jobId());
            manifest.put("artifactId", receipt.spec().artifactId());
            manifest.put("pageCount", receipt.spec().pageCount());
            manifest.put("receivedPageCount", receipt.storedPages());
            manifest.put("totalRows", receipt.storedRows());
            manifest.put("chainHash", receipt.chainHash());
            manifest.put("resultComplete", true);
            JsonNode snapshot = object(receipt.snapshotJson(), limits.artifactBytes());
            manifest.put("meta", snapshot);
            manifest.put("metrics", object(receipt.metricsJson(), limits.artifactBytes()));
            String payload = encode(manifest, limits.artifactBytes());
            Map<String, Object> provenanceFields = new LinkedHashMap<>();
            provenanceFields.put("jobId", receipt.spec().jobId());
            provenanceFields.put("requestHash", receipt.spec().requestHash());
            provenanceFields.put("scopeMode", "CURRENT_QUERY");
            if (StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path())) {
                Map<String, Object> proof = new StatisticsJobResultProtocol(child).frozenScopeProof(
                        receipt.spec().scopeRef(), JSON.convertValue(snapshot, new TypeReference<Map<String, Object>>() {}));
                provenanceFields.put("scopeMode", "FROZEN_SET");
                provenanceFields.put("scopeProof", proof);
            }
            String provenance = encode(provenanceFields, limits.artifactBytes());
            ArtifactRef reference = runs.publishReady(permit, new ArtifactDraft(receipt.spec().artifactId(), ARTIFACT_TYPE,
                    SCHEMA_VERSION, receipt.spec().scopeRef(), receipt.spec().periodsRef(), receipt.snapshotJson(), provenance,
                    Instant.ofEpochMilli(receipt.spec().expiresAtMillis()), payload));
            var definition = permit.token().definition();
            int changed = jdbc.update("UPDATE campaign_statistics_receipt SET published=TRUE,updated_at=? "
                            + "WHERE run_id=? AND revision=? AND child_id=? AND published=FALSE",
                    clock.millis(), definition.runId(), definition.revision(), permit.childId());
            if (changed != 1) fail("STATISTICS_PUBLICATION_CONFLICT");
            return reference;
        });
    }

    @Override
    public String readPage(Caller current, String artifactId, int pageIndex, ArtifactAuthorizer authorizer) {
        if (pageIndex < 0) throw new IllegalArgumentException("Invalid page index");
        ArtifactMetadata artifact = runs.inspectArtifact(current, artifactId, authorizer);
        if (!ARTIFACT_TYPE.equals(artifact.ref().type()) || !SCHEMA_VERSION.equals(artifact.ref().schemaVersion()))
            fail("STATISTICS_ARTIFACT_CONTRACT_MISMATCH");
        StoredReceipt stored = find(artifact.runId(), artifact.revision(), artifact.childId())
                .orElseThrow(() -> new IllegalStateException("STATISTICS_RECEIPT_NOT_FOUND"));
        Receipt receipt = stored.receipt();
        if (!receipt.published() || !receipt.complete() || !artifactId.equals(receipt.spec().artifactId())
                || !artifact.ref().scopeRef().equals(receipt.spec().scopeRef())
                || !artifact.ref().periodsRef().equals(receipt.spec().periodsRef())
                || artifact.ref().expiresAt().toEpochMilli() != receipt.spec().expiresAtMillis())
            fail("STATISTICS_ARTIFACT_RECEIPT_MISMATCH");
        unexpired(receipt.spec());
        if (pageIndex >= receipt.requiredPages()) throw new IllegalArgumentException("Invalid page index");
        StoredPage page = jdbc.query("SELECT page_index,next_page_index,row_count,byte_count,checksum,payload_json "
                        + "FROM campaign_statistics_page WHERE run_id=? AND revision=? AND child_id=? AND page_index=?",
                (rs, row) -> new StoredPage(header(rs), rs.getString("payload_json")),
                artifact.runId(), artifact.revision(), artifact.childId(), pageIndex).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("STATISTICS_PAGE_NOT_FOUND"));
        int bytes = byteLength(page.payload(), pageBytes);
        if (bytes != page.header().bytes() || !CampaignRunStore.sha256(page.payload()).equals(page.header().checksum()))
            fail("STATISTICS_PAGE_CORRUPTED");
        // Recheck current authorization and expiry after the bounded payload read as well.
        if (!artifact.equals(runs.inspectArtifact(current, artifactId, authorizer))) fail("STATISTICS_ARTIFACT_CHANGED");
        return page.payload();
    }

    private void verifyPages(DispatchPermit permit, StoredReceipt stored) {
        Receipt receipt = stored.receipt();
        var definition = permit.token().definition();
        var counts = jdbc.queryForMap("SELECT COUNT(*) AS pages,COALESCE(SUM(row_count),0) AS rows_total,"
                        + "COALESCE(SUM(byte_count),0) AS bytes_total FROM campaign_statistics_page WHERE run_id=? AND revision=? AND child_id=?",
                definition.runId(), definition.revision(), permit.childId());
        if (((Number) counts.get("pages")).longValue() != receipt.storedPages()
                || ((Number) counts.get("rows_total")).longValue() != receipt.storedRows()
                || ((Number) counts.get("bytes_total")).longValue() != stored.bytes()) fail("STATISTICS_PAGE_TOTALS_MISMATCH");
        String chain = jdbc.query("SELECT page_index,next_page_index,row_count,byte_count,checksum FROM campaign_statistics_page "
                        + "WHERE run_id=? AND revision=? AND child_id=? ORDER BY page_index",
                ps -> { ps.setString(1, definition.runId()); ps.setInt(2, definition.revision()); ps.setString(3, permit.childId()); },
                rs -> {
                    int index = 0;
                    String hash = initialChain(stored.specHash());
                    while (rs.next()) {
                        PageHeader page = header(rs);
                        if (page.index() != index || page.rows() != expectedRows(receipt.spec(), index)
                                || !Objects.equals(page.next(), expectedNext(receipt.spec(), index)))
                            fail("STATISTICS_PAGE_CHAIN_MISMATCH");
                        hash = nextChain(hash, index, page.checksum(), page.rows());
                        index++;
                    }
                    if (index != receipt.requiredPages()) fail("STATISTICS_PAGE_CHAIN_MISMATCH");
                    return hash;
                });
        if (!Objects.equals(chain, receipt.chainHash())) fail("STATISTICS_PAGE_CHAIN_MISMATCH");
    }

    private ChildRecord mutation(DispatchPermit permit) {
        Objects.requireNonNull(permit);
        if (permit.purpose() != DispatchPurpose.RECONCILE || !runs.mayDispatch(permit)) fail("STATISTICS_ATTEMPT_NOT_ACTIVE");
        ChildRecord child = runs.child(permit.token(), permit.childId()).orElseThrow(() -> new IllegalStateException("CHILD_NOT_FOUND"));
        if (child.spec().mode() != ChildMode.ASYNC || child.jobId() == null) fail("STATISTICS_KNOWN_ASYNC_JOB_REQUIRED");
        return child;
    }

    private static void binding(ChildRecord child, ReceiptSpec spec) {
        if (child.spec().mode() != ChildMode.ASYNC || !Objects.equals(child.jobId(), spec.jobId())
                || !child.spec().wire().hash().equals(spec.requestHash())) fail("STATISTICS_RECEIPT_BINDING_MISMATCH");
    }

    private Optional<StoredReceipt> find(DispatchPermit permit) {
        return find(permit.token().definition().runId(), permit.token().definition().revision(), permit.childId());
    }

    private Optional<StoredReceipt> find(String runId, int revision, String childId) {
        return jdbc.query("SELECT * FROM campaign_statistics_receipt WHERE run_id=? AND revision=? AND child_id=?",
                (rs, row) -> storedReceipt(rs), runId, revision, childId).stream().findFirst();
    }

    private StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        String specJson = rs.getString("spec_json");
        byteLength(specJson, limits.definitionBytes());
        String specHash = rs.getString("spec_hash");
        if (!CampaignRunStore.sha256(specJson).equals(specHash)) fail("STATISTICS_RECEIPT_CORRUPTED");
        ReceiptSpec spec;
        try { spec = JSON.readValue(specJson, ReceiptSpec.class); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("STATISTICS_RECEIPT_CORRUPTED"); }
        validateSpec(spec);
        if (!spec.artifactId().equals(rs.getString("artifact_id"))) fail("STATISTICS_RECEIPT_CORRUPTED");
        String snapshot = rs.getString("snapshot_json");
        String metrics = rs.getString("metrics_json");
        if (snapshot != null) object(snapshot, limits.artifactBytes());
        if (metrics != null) object(metrics, limits.artifactBytes());
        Receipt receipt = new Receipt(spec, rs.getInt("next_page_index"), rs.getInt("stored_pages"), rs.getLong("stored_rows"),
                snapshot, metrics, rs.getString("chain_hash"), rs.getBoolean("published"));
        if (receipt.storedPages() < 0 || receipt.storedPages() > receipt.requiredPages()
                || receipt.nextPageIndex() != receipt.storedPages() || receipt.storedRows() < 0
                || receipt.storedRows() > spec.totalRows()) fail("STATISTICS_RECEIPT_CORRUPTED");
        return new StoredReceipt(receipt, rs.getLong("stored_bytes"), specHash);
    }

    private static PageHeader header(ResultSet rs) throws SQLException {
        int next = rs.getInt("next_page_index");
        Integer nextIndex = rs.wasNull() ? null : next;
        return new PageHeader(rs.getInt("page_index"), nextIndex, rs.getInt("row_count"), rs.getLong("byte_count"), rs.getString("checksum"));
    }

    private int validatePage(ReceiptSpec spec, Page page) {
        if (page.pageIndex() < 0 || page.pageIndex() >= Math.max(1, spec.pageCount())
                || page.rowCount() != expectedRows(spec, page.pageIndex())
                || !Objects.equals(page.nextPageIndex(), expectedNext(spec, page.pageIndex())))
            throw new IllegalArgumentException("Invalid statistics page boundary");
        int bytes = byteLength(page.payloadJson(), pageBytes);
        int rows = 0;
        boolean items = false;
        try (JsonParser parser = JSON.getFactory().createParser(page.payloadJson())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("Page must be an object");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IllegalArgumentException("Invalid page object");
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("items".equals(field)) {
                    if (value != JsonToken.START_ARRAY) throw new IllegalArgumentException("Page items must be an array");
                    items = true;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() == null || ++rows > PAGE_SIZE) throw new IllegalArgumentException("Invalid page item count");
                        parser.skipChildren();
                    }
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null || !items || rows != page.rowCount())
                throw new IllegalArgumentException("Page item count does not match its receipt");
        } catch (java.io.IOException malformed) { throw new IllegalArgumentException("Invalid statistics page JSON"); }
        return bytes;
    }

    private static void validateSpec(ReceiptSpec spec) {
        Objects.requireNonNull(spec);
        id(spec.jobId(), 128); id(spec.artifactId(), 96);
        text(spec.scopeRef(), 256); text(spec.periodsRef(), 256);
        if (spec.requestHash() == null || !spec.requestHash().matches("[a-f0-9]{64}")
                || spec.totalRows() < 0 || spec.pageCount() < 0 || spec.expiresAtMillis() <= 0)
            throw new IllegalArgumentException("Invalid statistics receipt specification");
        long pages = spec.totalRows() / PAGE_SIZE + (spec.totalRows() % PAGE_SIZE == 0 ? 0 : 1);
        if (pages != spec.pageCount()) throw new IllegalArgumentException("Statistics page count does not match total rows");
    }

    private static int expectedRows(ReceiptSpec spec, int index) {
        return (int) Math.min(PAGE_SIZE, Math.max(0L, spec.totalRows() - (long) index * PAGE_SIZE));
    }

    private static Integer expectedNext(ReceiptSpec spec, int index) {
        return index + 1 < Math.max(1, spec.pageCount()) ? index + 1 : null;
    }

    private void unexpired(ReceiptSpec spec) {
        if (clock.millis() >= spec.expiresAtMillis()) throw new SecurityException("STATISTICS_RESULT_EXPIRED");
    }

    private static String initialChain(String specHash) { return CampaignRunStore.sha256(SCHEMA_VERSION + ":" + specHash); }
    private static String nextChain(String prior, int index, String checksum, int rows) {
        return CampaignRunStore.sha256(prior + ":" + index + ":" + checksum + ":" + rows);
    }

    private static JsonNode object(String value, int limit) {
        byteLength(value, limit);
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Statistics metadata must be an object");
            // readTree alone accepts trailing values unless explicitly checked.
            try (JsonParser parser = JSON.getFactory().createParser(value)) {
                parser.nextToken(); parser.skipChildren();
                if (parser.nextToken() != null) throw new IllegalArgumentException("Invalid statistics metadata JSON");
            }
            return parsed;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid statistics metadata JSON"); }
    }

    private static String encode(Object value, int limit) {
        try {
            String result = JSON.writeValueAsString(value);
            byteLength(result, limit);
            return result;
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid statistics result JSON"); }
    }

    private static int byteLength(String value, int limit) {
        if (value == null) throw new IllegalArgumentException("Statistics JSON is required");
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4; i++;
            } else bytes += Character.isSurrogate(c) ? 1 : 3;
            if (bytes > limit) throw new IllegalArgumentException("Statistics JSON byte limit exceeded");
        }
        return (int) bytes;
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid statistics reference");
    }
    private static void id(String value, int maximum) {
        text(value, maximum);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) throw new IllegalArgumentException("Invalid statistics identity");
    }
    private <T> T transaction(Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (DataIntegrityViolationException conflict) { throw new IllegalStateException("STATISTICS_RESULT_IDENTITY_CONFLICT"); }
    }
    private static void fail(String reason) { throw new IllegalStateException(reason); }
    private record StoredReceipt(Receipt receipt, long bytes, String specHash) {}
    private record PageHeader(int index, Integer next, int rows, long bytes, String checksum) {}
    private record StoredPage(PageHeader header, String payload) {}
}
