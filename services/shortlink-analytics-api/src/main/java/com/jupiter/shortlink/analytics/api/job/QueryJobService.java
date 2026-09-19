package com.jupiter.shortlink.analytics.api.job;

import static com.jupiter.shortlink.analytics.api.ClickHouseReader.quote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

/** Durable admission, frozen query selection, leased execution and atomic result visibility. */
@Service
public class QueryJobService {
    static final long RETENTION = 86_400_000L, LEASE = 360_000L, MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_ROWS = 200_000, PAGE_ROWS = 500;
    private static final String STATE_COLUMNS =
            "job_id,tenant_id,subject_id,request_id,request_hash,request_json,recovery_epoch,ownership_version,manifest_hash,state,lease_owner,lease_token,lease_until,attempts,next_attempt_at,row_count,byte_count,page_count,error_code,created_at,updated_at,expires_at";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuthorizationClient auth;
    private final ApiSettings settings;
    private final JobClickHouseStream clickhouse;
    private final TransactionTemplate tx;

    public record Submit(String requestId, QueryRequest query) {}

    public record Identity(
            String tenantId, String subjectId, long authVersion, Integer pageIndex, Integer size) {}

    public record Status(
            String jobId,
            String state,
            long rowCount,
            long byteCount,
            int pageCount,
            String errorCode,
            long expiresAt) {}

    public record Lease(
            String jobId,
            String owner,
            long token,
            QueryRequest query,
            String epoch,
            String ownershipVersion,
            ManifestPlan plan,
            long snapshotTime) {
        public Lease(String jobId, String owner, long token, QueryRequest query, String epoch,
                String ownershipVersion, ManifestPlan plan) {
            this(jobId, owner, token, query, epoch, ownershipVersion, plan, query.endExclusive());
        }
    }

    public QueryJobService(
            JdbcTemplate db,
            ObjectMapper json,
            AuthorizationClient auth,
            ApiSettings settings,
            JobClickHouseStream clickhouse,
            PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.auth = auth;
        this.settings = settings;
        this.clickhouse = clickhouse;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(15);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Status submit(Submit submit) {
        QueryRequest q = validatedSubmission(submit);
        var scope = auth.authorize(q);
        String epoch = auth.activeEpoch();
        String digest = digestRequest(q);
        return tx.execute(
                s -> {
                    gate();
                    long now = now();
                    var old =
                            db.queryForList(
                                    "SELECT "
                                            + STATE_COLUMNS
                                            + " FROM analytics_query_job WHERE tenant_id=? AND"
                                            + " subject_id=? AND request_id=?",
                                    q.tenantId(),
                                    q.subjectId(),
                                    submit.requestId());
                    if (!old.isEmpty()) {
                        var row = old.get(0);
                        if (!digest.equals(row.get("request_hash")))
                            throw new QueryFailure(
                                    "CONFLICT", "requestId is bound to another query");
                        assertScope(row, scope, epoch, now);
                        return status(row);
                    }
                    var retained =
                            db.queryForList(
                                    "SELECT tenant_id,state,byte_count,OCTET_LENGTH(manifest_json)"
                                            + " AS manifest_bytes FROM analytics_query_job");
                    long tenantJobs = 0, active = 0, tenantActive = 0, reservedBytes = 0;
                    for (var job : retained) {
                        boolean same = q.tenantId().equals(job.get("tenant_id")),
                                running = Set.of("QUEUED", "RUNNING").contains(job.get("state"));
                        if (same) tenantJobs++;
                        if (running) {
                            active++;
                            if (same) tenantActive++;
                        }
                        reservedBytes +=
                                number(job.get("manifest_bytes")).longValue()
                                        + (running
                                                ? MAX_BYTES
                                                : number(job.get("byte_count")).longValue());
                    }
                    if (retained.size() >= 128
                            || tenantJobs >= 8
                            || active >= 8
                            || tenantActive >= 2)
                        throw new QueryFailure(
                                "TOO_LARGE",
                                "Persistent query capacity exhausted; retry after completion or"
                                        + " result expiry");
                    assertEpoch(epoch);
                    ManifestPlan plan =
                            ManifestPlan.capture(
                                    db,
                                    json,
                                    settings,
                                    epoch,
                                    q.startInclusive(),
                                    q.endExclusive());
                    String frozen = write(plan);
                    if (frozen.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
                        throw new QueryFailure("TOO_LARGE", "Manifest selection exceeds 16 MiB");
                    if (reservedBytes + MAX_BYTES + frozen.getBytes(StandardCharsets.UTF_8).length
                            > 1024L * 1024 * 1024)
                        throw new QueryFailure(
                                "TOO_LARGE", "Persistent query storage reservation exceeds 1 GiB");
                    QueryRequest fixed = with(q, q.authVersion(), scope.linkIds());
                    String request = write(fixed);
                    if (request.length() > 65536)
                        throw new QueryFailure("TOO_LARGE", "Query description too large");
                    String id = UUID.randomUUID().toString();
                    db.update(
                            "INSERT INTO"
                                + " analytics_query_job(job_id,tenant_id,subject_id,request_id,request_hash,request_json,recovery_epoch,ownership_version,manifest_json,manifest_hash,state,created_at,updated_at,expires_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?)",
                            id,
                            q.tenantId(),
                            q.subjectId(),
                            submit.requestId(),
                            digest,
                            request,
                            epoch,
                            scope.ownershipVersion(),
                            frozen,
                            hash(frozen),
                            now,
                            now,
                            now + RETENTION);
                    return new Status(id, "QUEUED", 0, 0, 0, null, now + RETENTION);
                });
    }

    /**
     * Reconcile an uncertain submission without ever admitting another job. The gate is shared
     * with submit/cleanup, and the current locking read happens after acquiring it. In particular,
     * a mapping removed while this request waits for the gate cannot fall through to creation.
     */
    public Status recoverExisting(Submit submit) {
        QueryRequest q = validatedSubmission(submit);
        if (q.tenantId() == null || q.subjectId() == null || q.authVersion() < 0)
            throw new QueryFailure("FORBIDDEN", "Current identity required");
        String digest = digestRequest(q);
        return tx.execute(
                s -> {
                    gate();
                    var rows = db.queryForList(
                            "SELECT " + STATE_COLUMNS + " FROM analytics_query_job"
                                    + " WHERE tenant_id=? AND subject_id=? AND request_id=? FOR UPDATE",
                            q.tenantId(), q.subjectId(), submit.requestId());
                    if (rows.size() != 1)
                        throw new QueryFailure("REPLAY_UNAVAILABLE", "Original query identity is unavailable");
                    var row = rows.get(0);
                    assertRecoveryRetention(row, now());
                    if (!digest.equals(row.get("request_hash")))
                        throw new QueryFailure("CONFLICT", "requestId is bound to another query");

                    // The submitted hash keeps its original semantics (including an originally
                    // omitted linkIds field); current authorization uses the stored frozen members.
                    QueryRequest frozen = with(read(row.get("request_json").toString(), QueryRequest.class),
                            q.authVersion(), null);
                    var scope = auth.authorize(frozen);
                    String epoch = auth.activeEpoch();
                    long checkedAt = now();
                    assertRecoveryRetention(row, checkedAt);
                    assertScope(row, scope, epoch, checkedAt);
                    if (!Objects.equals(frozen.linkIds(), scope.linkIds()))
                        throw new QueryFailure("QUERY_SCOPE_CHANGED", "Authorized resource scope changed");
                    assertEpoch(epoch);
                    assertRecoveryRetention(row, now());
                    // Status only: no retained-quota admission, manifest capture, lease or INSERT.
                    return status(row);
                });
    }

    private QueryRequest validatedSubmission(Submit submit) {
        if (submit == null || submit.requestId() == null || submit.requestId().isBlank()
                || submit.requestId().length() > 96)
            throw new QueryFailure("INVALID_QUERY", "requestId must be 1..96 characters");
        validate(submit.query());
        return submit.query();
    }

    private void assertRecoveryRetention(Map<String, Object> row, long now) {
        if (number(row.get("expires_at")).longValue() <= now)
            throw new QueryFailure("REPLAY_UNAVAILABLE", "Original query identity is unavailable");
    }

    public Status status(String id, Identity identity) {
        return status(authorized(id, identity));
    }

    public Map<String, Object> page(String id, Identity identity) {
        int index = identity.pageIndex() == null ? 0 : identity.pageIndex();
        int size = identity.size() == null ? PAGE_ROWS : identity.size();
        if (index < 0 || size != PAGE_ROWS)
            throw new QueryFailure(
                    "INVALID_QUERY", "pageIndex must be nonnegative and page size is 500");
        var job = authorized(id, identity);
        if (!"SUCCEEDED".equals(job.get("state")))
            throw new QueryFailure("NOT_READY", "Query job has no published results");
        int count = number(job.get("page_count")).intValue();
        if (index >= count && !(index == 0 && count == 0))
            throw new QueryFailure("INVALID_QUERY", "Result page does not exist");
        List<Map<String, Object>> items = List.of();
        if (count > 0) {
            var rows =
                    db.queryForList(
                            "SELECT payload_json FROM analytics_query_page WHERE job_id=? AND"
                                    + " lease_token=? AND page_index=?",
                            id,
                            number(job.get("lease_token")).longValue(),
                            index);
            if (rows.size() != 1)
                throw new QueryFailure("UNAVAILABLE", "Published result page missing");
            items = readList(rows.get(0).get("payload_json").toString());
        }
        // Revalidate after reading the page, so a concurrent ownership/epoch change cannot
        // authorize stale results.
        authorized(id, identity);
        QueryRequest q = read(job.get("request_json").toString(), QueryRequest.class);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryKind", q.kind());
        meta.put("gid", q.gid());
        meta.put("linkIds", q.linkIds());
        meta.put("snapshotId", id);
        meta.put("recoveryEpoch", job.get("recovery_epoch"));
        meta.put("manifestSelectionHash", job.get("manifest_hash"));
        meta.put("manifestVersion", Map.of("selectionHash", job.get("manifest_hash")));
        meta.put("sourceCut", Map.of("manifestSelectionHash", job.get("manifest_hash")));
        meta.put("metricVersion", "click-v1");
        ManifestPlan frozenPlan = read(db.queryForObject("SELECT manifest_json FROM analytics_query_job WHERE job_id=?", String.class, id), ManifestPlan.class);
        var datasetVersions = frozenPlan.windows().stream().map(ManifestPlan.Window::datasetVersion).distinct().toList();
        meta.put("detailDatasetVersion", datasetVersions.size() == 1 ? datasetVersions.get(0) : "MIXED");
        meta.put("detailDatasetVersions", datasetVersions);
        meta.put("ruleVersion", null);
        meta.put("requestedStart", q.startInclusive());
        meta.put("requestedEnd", q.endExclusive());
        meta.put("effectiveEnd", q.endExclusive());
        meta.put("businessTimezone", "Asia/Shanghai");
        meta.put("snapshotCreatedAt", number(job.get("created_at")).longValue());
        meta.put("snapshotExpiresAt", number(job.get("expires_at")).longValue());
        meta.put("generatedAt", number(job.get("created_at")).longValue());
        meta.put("availability", "AVAILABLE");
        meta.put("freshness", "FRESH");
        meta.put("completeness", "COMPLETE");
        meta.put("provisional", false);
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        Map<String, Object> metrics = Map.of();
        Map<String, Object> dimensionQuality;
        var summaryRows = db.queryForList("SELECT payload_json FROM analytics_query_page WHERE job_id=?"
                + " AND lease_token=? AND page_index=-1", id, number(job.get("lease_token")).longValue());
        if (summaryRows.size() == 1) {
            var summary = readList(summaryRows.get(0).get("payload_json").toString()).get(0);
            dimensionQuality = (Map<String, Object>) summary.getOrDefault("dimensionQuality", Map.of());
            if (Set.of("METRICS", LinkMetrics.KIND, DimensionBreakdown.KIND).contains(q.kind())) metrics = Map.of("requested", summary);
        } else {
            if (Set.of(LinkMetrics.KIND, DimensionBreakdown.KIND).contains(q.kind()))
                throw new QueryFailure("UNAVAILABLE", "Whole-window summary is unavailable");
            dimensionQuality = (Map<String, Object>) MetricDimensions.recordDimensions(items).get("dimensionQuality");
            meta.put("dimensionQualityScope", "RETURNED_PAGE_LEGACY_JOB");
        }
        meta.put("dimensionQuality", dimensionQuality);
        var missing = new ArrayList<String>(DimensionBreakdown.KIND.equals(q.kind()) ? DimensionBreakdown.missingMetrics(dimensionQuality)
                : LinkMetrics.KIND.equals(q.kind()) ? List.of() : MetricDimensions.missingMetrics(dimensionQuality));
        missing.add("producerCollectionCompleteness");
        meta.put("missingMetrics", missing);
        meta.put("approximation", DimensionBreakdown.KIND.equals(q.kind()) ? DimensionBreakdown.approximation()
                : LinkMetrics.KIND.equals(q.kind()) ? LinkMetrics.approximation() : MetricDimensions.approximation());
        if (LinkMetrics.KIND.equals(q.kind())) meta.put("aggregationLevel", "LINK_WINDOW");
        meta.put("nextPageIndex", index + 1 < count ? index + 1 : null);
        meta.put("pageIndex", index);
        meta.put("totalRows", number(job.get("row_count")).longValue());
        if (DimensionBreakdown.KIND.equals(q.kind()))
            DimensionBreakdown.metadata(meta, DimensionBreakdown.options(q), number(job.get("row_count")).longValue());
        authorized(id, identity);
        return Map.of("items", items, "metrics", metrics, "meta", meta);
    }

    public Status cancel(String id, Identity identity) {
        authorized(id, identity);
        return tx.execute(
                s -> {
                    var row = locked(id);
                    if (Set.of("QUEUED", "RUNNING").contains(row.get("state"))) {
                        db.update(
                                "UPDATE analytics_query_job SET"
                                    + " state='CANCELLED',lease_token=lease_token+1,lease_until=0,error_code='CANCELLED',updated_at=?"
                                    + " WHERE job_id=?",
                                now(),
                                id);
                        db.update("DELETE FROM analytics_query_page WHERE job_id=?", id);
                    }
                    return status(locked(id));
                });
    }

    private Map<String, Object> authorized(String id, Identity identity) {
        if (identity == null || identity.tenantId() == null || identity.subjectId() == null)
            throw new QueryFailure("FORBIDDEN", "Current identity required");
        var rows =
                db.queryForList(
                        "SELECT "
                                + STATE_COLUMNS
                                + " FROM analytics_query_job WHERE job_id=? AND tenant_id=? AND"
                                + " subject_id=?",
                        id,
                        identity.tenantId(),
                        identity.subjectId());
        if (rows.size() != 1)
            throw new QueryFailure("FORBIDDEN", "Query result unavailable to this subject");
        var row = rows.get(0);
        QueryRequest q =
                with(
                        read(row.get("request_json").toString(), QueryRequest.class),
                        identity.authVersion(),
                        null);
        assertScope(row, auth.authorize(q), auth.activeEpoch(), now());
        return row;
    }

    private void assertScope(
            Map<String, Object> row, AuthorizationClient.Scope scope, String epoch, long now) {
        if (number(row.get("expires_at")).longValue() <= now)
            throw new QueryFailure("SNAPSHOT_EXPIRED", "Query job expired");
        if (!epoch.equals(row.get("recovery_epoch")))
            throw new QueryFailure("SNAPSHOT_EXPIRED", "Recovery epoch changed");
        if (!scope.ownershipVersion().equals(row.get("ownership_version")))
            throw new QueryFailure("QUERY_SCOPE_CHANGED", "Authorized resource scope changed");
    }

    public Lease claim(String owner) {
        return tx.execute(
                s -> {
                    long now = now();
                    var candidates =
                            db.queryForList(
                                    "SELECT job_id FROM analytics_query_job WHERE expires_at>? AND"
                                            + " ((state='QUEUED' AND next_attempt_at<=?) OR"
                                            + " (state='RUNNING' AND lease_until<=?)) ORDER BY"
                                            + " created_at LIMIT 8",
                                    now,
                                    now,
                                    now);
                    for (var candidate : candidates) {
                        String id = candidate.get("job_id").toString();
                        var row = locked(id);
                        String state = row.get("state").toString();
                        if (!((state.equals("QUEUED")
                                        && number(row.get("next_attempt_at")).longValue() <= now)
                                || (state.equals("RUNNING")
                                        && number(row.get("lease_until")).longValue() <= now)))
                            continue;
                        if (number(row.get("attempts")).intValue() >= 3) {
                            db.update(
                                    "UPDATE analytics_query_job SET"
                                        + " state='FAILED',error_code='ATTEMPTS_EXHAUSTED',lease_token=lease_token+1,updated_at=?"
                                        + " WHERE job_id=?",
                                    now,
                                    id);
                            db.update("DELETE FROM analytics_query_page WHERE job_id=?", id);
                            continue;
                        }
                        long token = number(row.get("lease_token")).longValue() + 1;
                        db.update(
                                "UPDATE analytics_query_job SET"
                                    + " state='RUNNING',lease_owner=?,lease_token=?,lease_until=?,attempts=attempts+1,row_count=0,byte_count=0,page_count=0,error_code=NULL,updated_at=?"
                                    + " WHERE job_id=?",
                                owner,
                                token,
                                now + LEASE,
                                now,
                                id);
                        db.update("DELETE FROM analytics_query_page WHERE job_id=?", id);
                        String manifest =
                                db.queryForObject(
                                        "SELECT manifest_json FROM analytics_query_job WHERE"
                                                + " job_id=?",
                                        String.class,
                                        id);
                        return new Lease(
                                id,
                                owner,
                                token,
                                read(row.get("request_json").toString(), QueryRequest.class),
                                row.get("recovery_epoch").toString(),
                                row.get("ownership_version").toString(),
                                read(manifest, ManifestPlan.class),
                                number(row.get("created_at")).longValue());
                    }
                    return null;
                });
    }

    public void execute(Lease lease) {
        try {
            checkExecution(lease);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
            String replica = clickhouse.verify(lease.plan(), deadline);
            var visitorHistory = history(lease);
            if (visitorHistory.available() && !Set.of(LinkMetrics.KIND, DimensionBreakdown.KIND).contains(lease.query().kind())) {
                var candidate = visitorHistory;
                long proofDeadline = Math.min(deadline, System.nanoTime()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(VisitorHistory.PROOF_TIMEOUT_MILLIS));
                visitorHistory = VisitorHistory.optionalProof(candidate, () -> {
                    List<Map<String, Object>> visible = new ArrayList<>();
                    clickhouse.query(replica, VisitorHistory.visibilitySql(candidate), 4096, proofDeadline, visible::add);
                    return visible;
                }, visible -> VisitorHistory.verifyVisibility(candidate, visible));
            }
            PageBuffer buffer = new PageBuffer(lease, visitorHistory);
            clickhouse.query(replica, sql(lease, visitorHistory), MAX_ROWS, deadline, buffer::add);
            buffer.finishAggregates();
            buffer.flush();
            buffer.publishSummary();
            checkExecution(lease);
            tx.executeWithoutResult(
                    s -> {
                        var row = fenced(lease);
                        assertEpoch(lease.epoch());
                        db.update(
                                "UPDATE analytics_query_job SET"
                                        + " state='SUCCEEDED',lease_until=0,updated_at=? WHERE"
                                        + " job_id=?",
                                now(),
                                lease.jobId());
                    });
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) throw failure;
            fail(lease, failure);
        }
    }

    private void checkExecution(Lease lease) {
        var scope = auth.authorize(lease.query());
        if (!lease.epoch().equals(auth.activeEpoch()))
            throw new QueryFailure("SNAPSHOT_EXPIRED", "Epoch changed");
        if (!lease.ownershipVersion().equals(scope.ownershipVersion()))
            throw new QueryFailure("QUERY_SCOPE_CHANGED", "Ownership changed");
    }

    private final class PageBuffer {
        final Lease lease;
        final List<Map<String, Object>> page = new ArrayList<>();
        int pageBytes = 2;
        Map<String, Object> summary;
        final Map<String, Map<String, Object>> summaryDaily = new TreeMap<>();
        final RecordDimensionSummary recordSummary = new RecordDimensionSummary(MAX_ROWS);
        final VisitorHistory.Plan visitorHistory;
        final LinkMetrics.Accumulator linkMetrics;
        final DimensionBreakdown.Accumulator dimensionBreakdown;

        PageBuffer(Lease lease, VisitorHistory.Plan visitorHistory) {
            this.lease = lease;
            this.visitorHistory = visitorHistory;
            this.linkMetrics = LinkMetrics.KIND.equals(lease.query().kind())
                    ? new LinkMetrics.Accumulator(lease.query().linkIds(),
                            lease.query().startInclusive(), lease.query().endExclusive()) : null;
            this.dimensionBreakdown = DimensionBreakdown.KIND.equals(lease.query().kind())
                    ? new DimensionBreakdown.Accumulator(DimensionBreakdown.options(lease.query()),
                            lease.query().startInclusive(), lease.query().endExclusive()) : null;
        }

        void add(Map<String, Object> row) {
            if (dimensionBreakdown != null) {
                dimensionBreakdown.add(row);
                return;
            }
            if (linkMetrics != null) {
                linkMetrics.add(row);
                return;
            }
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            for (String key : List.of("linkId", "occurredAt", "pv", "uv", "uip", "denied"))
                if (normalized.get(key) != null)
                    try {
                        normalized.put(
                                key,
                                new java.math.BigDecimal(normalized.get(key).toString())
                                        .longValueExact());
                    } catch (ArithmeticException | NumberFormatException invalid) {
                        throw new QueryFailure(
                                "TOO_LARGE",
                                "Analytics integer exceeds the signed 64-bit contract");
                    }
            if ("METRICS".equals(lease.query().kind()) && row.containsKey("group_row")) {
                boolean group = number(row.get("group_row")).longValue() == 1;
                boolean total = number(row.get("whole_window")).longValue() == 1;
                long rowStart = lease.query().startInclusive(), rowEnd = lease.query().endExclusive();
                if (!total) {
                    rowStart = java.time.LocalDate.parse(row.get("day").toString()).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                            .toInstant().toEpochMilli();
                    rowEnd = rowStart + 86_400_000L;
                }
                var rawMetrics = new LinkedHashMap<>(row);
                if (!total) for (String scope : List.of("scope", "link")) for (String type : List.of("new", "old", "unknown")) {
                    String key = scope + "_" + type + "_uv";
                    rawMetrics.put(key, row.get("daily_" + key));
                }
                var report = metricReport(rawMetrics, rowStart, rowEnd, visitorHistory, group);
                VisitorHistory.labelScope(report, group ? lease.query().linkIds().size() : 1);
                if (total) { summary = report; return; }
                report.put("daily", List.of(Map.of("date", row.get("day"), "pv", report.get("pv"),
                        "uv", report.get("uv"), "uip", report.get("uip"))));
                if (group) {
                    summaryDaily.put(row.get("day").toString(), Map.of("date", row.get("day"), "pv", report.get("pv"),
                            "uv", report.get("uv"), "uip", report.get("uip")));
                    return;
                }
                report.put("day", row.get("day"));
                report.put("linkId", normalized.get("linkId"));
                normalized = report;
            } else if ("ACCESS_RECORDS".equals(lease.query().kind())) recordSummary.add(normalized);
            append(normalized);
        }

        void finishAggregates() {
            if (linkMetrics != null) {
                var result = linkMetrics.finish();
                summary = result.summary();
                for (var row : result.items()) append(row);
            } else if (dimensionBreakdown != null) {
                var result = dimensionBreakdown.finish();
                summary = result.summary();
                for (var row : result.items()) append(row);
            }
        }

        private void append(Map<String, Object> normalized) {
            int size = write(normalized).getBytes(StandardCharsets.UTF_8).length + 1;
            if (size > 1_048_576 - 2)
                throw new QueryFailure("TOO_LARGE", "Result page byte budget exceeded");
            if (pageBytes + size > 1_048_576)
                throw new QueryFailure("TOO_LARGE", "Result page exceeds 1 MiB");
            page.add(normalized);
            pageBytes += size;
            if (page.size() == PAGE_ROWS) flush();
        }

        @SuppressWarnings("unchecked")
        void publishSummary() {
            if ("ACCESS_RECORDS".equals(lease.query().kind())) summary = recordSummary.materialize(visitorHistory);
            else if (summary != null && linkMetrics == null && dimensionBreakdown == null) {
                List<Map<String, Object>> days = new ArrayList<>();
                for (var day : (List<Map<String, Object>>) summary.get("daily"))
                    days.add(summaryDaily.getOrDefault(day.get("date").toString(), day));
                summary.put("daily", days);
            }
            if (summary == null) return; // Already-published legacy jobs remain readable.
            if (linkMetrics == null && dimensionBreakdown == null) VisitorHistory.labelScope(summary, lease.query().linkIds().size());
            String payload = write(List.of(summary));
            int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1_048_576) throw new QueryFailure("TOO_LARGE", "Summary exceeds page byte budget");
            tx.executeWithoutResult(s -> {
                var row = fenced(lease);
                if (number(row.get("byte_count")).longValue() + bytes > MAX_BYTES)
                    throw new QueryFailure("TOO_LARGE", "Persistent result budget exceeded");
                db.update("INSERT INTO analytics_query_page(job_id,lease_token,page_index,payload_json) VALUES(?,?,-1,?)",
                        lease.jobId(), lease.token(), payload);
                db.update("UPDATE analytics_query_job SET byte_count=byte_count+? WHERE job_id=?", bytes, lease.jobId());
            });
        }

        void flush() {
            if (page.isEmpty()) return;
            String payload = write(page);
            int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
            int count = page.size();
            tx.executeWithoutResult(
                    s -> {
                        var row = fenced(lease);
                        long total = number(row.get("row_count")).longValue() + count,
                                totalBytes = number(row.get("byte_count")).longValue() + bytes;
                        int index = number(row.get("page_count")).intValue();
                        if (total > MAX_ROWS || totalBytes > MAX_BYTES)
                            throw new QueryFailure(
                                    "TOO_LARGE", "Persistent result budget exceeded");
                        db.update(
                                "INSERT INTO"
                                    + " analytics_query_page(job_id,lease_token,page_index,payload_json)"
                                    + " VALUES(?,?,?,?)",
                                lease.jobId(),
                                lease.token(),
                                index,
                                payload);
                        db.update(
                                "UPDATE analytics_query_job SET"
                                    + " row_count=?,byte_count=?,page_count=page_count+1,updated_at=?"
                                    + " WHERE job_id=?",
                                total,
                                totalBytes,
                                now(),
                                lease.jobId());
                    });
            page.clear();
            pageBytes = 2;
        }
    }

    private Map<String, Object> fenced(Lease lease) {
        var row = locked(lease.jobId());
        long now = now();
        if (!"RUNNING".equals(row.get("state"))
                || !lease.owner().equals(row.get("lease_owner"))
                || lease.token() != number(row.get("lease_token")).longValue()
                || number(row.get("lease_until")).longValue() <= now
                || number(row.get("expires_at")).longValue() <= now)
            throw new QueryFailure("LEASE_LOST", "Query lease no longer owns this attempt");
        return row;
    }

    private void fail(Lease lease, RuntimeException failure) {
        try {
            tx.executeWithoutResult(
                    s -> {
                        var row = fenced(lease);
                        String code = failure instanceof QueryFailure q ? q.code : "UNAVAILABLE";
                        boolean retry =
                                Set.of("UNAVAILABLE", "NOT_READY").contains(code)
                                        && number(row.get("attempts")).intValue() < 3;
                        db.update(
                                "UPDATE analytics_query_job SET"
                                    + " state=?,lease_until=0,error_code=?,next_attempt_at=?,updated_at=?"
                                    + " WHERE job_id=?",
                                retry ? "QUEUED" : "FAILED",
                                code,
                                now() + 5000,
                                now(),
                                lease.jobId());
                        db.update("DELETE FROM analytics_query_page WHERE job_id=?", lease.jobId());
                    });
        } catch (QueryFailure stale) {
            if (!stale.code.equals("LEASE_LOST")) throw stale;
        }
    }

    static String sql(Lease lease) {
        var candidate = history(lease);
        return sql(lease, VisitorHistory.verifyVisibility(candidate, List.of()));
    }

    static String sql(Lease lease, VisitorHistory.Plan visitorHistory) {
        QueryRequest q = lease.query();
        String facts = AnalyticsFacts.sql("rebuild_input", lease.plan().predicate(), q.tenantId(), q.linkIds());
        if (DimensionBreakdown.KIND.equals(q.kind()))
            return DimensionBreakdown.sql(facts, q.startInclusive(), q.endExclusive(), DimensionBreakdown.options(q));
        if (LinkMetrics.KIND.equals(q.kind()))
            return LinkMetrics.sql(facts, q.startInclusive(), q.endExclusive());
        facts = VisitorHistory.enrich(facts, visitorHistory, q.tenantId(), q.linkIds());
        String filter =
                " FROM ("
                        + facts
                        + ") WHERE occurred_at>="
                        + q.startInclusive()
                        + " AND occurred_at<"
                        + q.endExclusive();
        if ("ACCESS_RECORDS".equals(q.kind()))
            return "SELECT event_id eventId,link_id linkId,occurred_at occurredAt,visitor_hash"
                    + " visitorHash,ip_hash ipHash,kind,status,browser,os,device,country,province,city,network,"
                    + "geo_status geoStatus,geo_version geoVersion,(geo_version_count>1) geoVersionConflict,"
                    + "referer_domain refererDomain,history_earliest historyEarliestObservedAt,"
                    + "if(scope_history_known,if(scope_first_seen>=" + q.startInclusive()
                    + ",'newUser','oldUser'),'UNKNOWN') uvType"
                    + filter
                    + " AND kind='CLICK' ORDER BY occurred_at DESC,event_id LIMIT "
                    + (MAX_ROWS + 1);
        return "SELECT toString(toDate(fromUnixTimestamp64Milli(occurred_at),'Asia/Shanghai'))"
                + " day,link_id linkId,grouping(link_id) group_row,grouping(day) whole_window,countIf(kind='CLICK')"
                + " pv,uniqCombined64If(visitor_hash,kind='CLICK' AND visitor_hash!='')"
                + " uv,uniqCombined64If(ip_hash,kind='CLICK' AND ip_hash!='')"
                + " uip,countIf(kind='REQUEST' AND request_source='REDIRECT' AND"
                + " decision_stage='BUSINESS' AND status IN (403,429)) denied"
                + MetricDimensions.sql(q.startInclusive(), q.endExclusive(), false)
                + VisitorHistory.metricsSql(Long.toString(q.startInclusive()))
                + VisitorHistory.metricsSql("toUnixTimestamp64Milli(toDateTime64(toStartOfDay(fromUnixTimestamp64Milli(occurred_at,'Asia/Shanghai')),3,'Asia/Shanghai'))", "daily_")
                + filter
                + " GROUP BY GROUPING SETS ((day,link_id),(day),()) ORDER BY whole_window DESC,group_row DESC,day,link_id LIMIT "
                + (MAX_ROWS + 1);
    }

    private static VisitorHistory.Plan history(Lease lease) {
        return VisitorHistory.plan(lease.plan().windows().stream().map(ManifestPlan.Window::sourceCut).distinct().toList(),
                new ObjectMapper(), lease.snapshotTime(), lease.query().endExclusive());
    }

    private static Map<String, Object> metricReport(Map<String, Object> raw, long start, long end,
            VisitorHistory.Plan history, boolean group) {
        Map<String, Object> report = new LinkedHashMap<>();
        for (String key : List.of("pv", "uv", "uip", "denied"))
            report.put(key, raw.get(key) == null ? 0L : number(raw.get(key)).longValue());
        report.putAll(MetricDimensions.materialize(raw, start, end));
        VisitorHistory.materialize(report, raw, history, group);
        return report;
    }

    public void cleanup() {
        tx.executeWithoutResult(
                s -> {
                    gate();
                    long now = now();
                    var expired =
                            db.queryForList(
                                    "SELECT job_id FROM analytics_query_job WHERE expires_at<=?"
                                            + " ORDER BY expires_at LIMIT 2",
                                    now);
                    for (var r : expired) {
                        String id = r.get("job_id").toString();
                        locked(id);
                        db.update("DELETE FROM analytics_query_page WHERE job_id=?", id);
                        db.update("DELETE FROM analytics_query_job WHERE job_id=?", id);
                    }
                });
    }

    private void gate() {
        if (db.queryForList(
                                "SELECT singleton FROM analytics_query_gate WHERE singleton=1 FOR"
                                        + " UPDATE")
                        .size()
                != 1)
            throw new QueryFailure("NOT_READY", "Query admission schema is not initialized");
    }

    private void assertEpoch(String epoch) {
        var row =
                db.queryForMap(
                        "SELECT recovery_epoch,mode,command_ack FROM analytics_epoch WHERE"
                                + " singleton=1 FOR UPDATE");
        if (!epoch.equals(row.get("recovery_epoch"))
                || !"ACTIVE".equals(row.get("mode"))
                || !(Boolean.TRUE.equals(row.get("command_ack"))
                        || "1".equals(row.get("command_ack").toString())))
            throw new QueryFailure("SNAPSHOT_EXPIRED", "Recovery gate changed");
    }

    private Map<String, Object> locked(String id) {
        var rows =
                db.queryForList(
                        "SELECT "
                                + STATE_COLUMNS
                                + " FROM analytics_query_job WHERE job_id=? FOR UPDATE",
                        id);
        if (rows.size() != 1) throw new QueryFailure("LEASE_LOST", "Query job no longer exists");
        return rows.get(0);
    }

    private long now() {
        return db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).getTime();
    }

    private static Number number(Object v) {
        return v instanceof Number n ? n : Long.valueOf(v.toString());
    }

    private static Status status(Map<String, Object> r) {
        return new Status(
                r.get("job_id").toString(),
                r.get("state").toString(),
                number(r.get("row_count")).longValue(),
                number(r.get("byte_count")).longValue(),
                number(r.get("page_count")).intValue(),
                Objects.toString(r.get("error_code"), null),
                number(r.get("expires_at")).longValue());
    }

    private void validate(QueryRequest q) {
        if (q == null
                || q.startInclusive() == null
                || q.endExclusive() == null
                || q.startInclusive() < 0
                || q.endExclusive() <= q.startInclusive()
                || q.endExclusive() - q.startInclusive() > ManifestPlan.MAX_RANGE
                || q.endExclusive() > System.currentTimeMillis())
            throw new QueryFailure(
                    "TOO_LARGE",
                    "Historical query interval must be within 180 days and end no later than now");
        DimensionBreakdown.validateRequest(q);
        if (!Set.of("METRICS", "ACCESS_RECORDS", LinkMetrics.KIND, DimensionBreakdown.KIND).contains(q.kind())
                || q.snapshotId() != null
                || q.cursor() != null
                || q.windows() != null
                || q.endPolicy() != null && !"REQUESTED".equals(q.endPolicy()))
            throw new QueryFailure(
                    "INVALID_QUERY",
                    "Jobs require one fixed historical interval without a previous snapshot");
        if (q.linkIds() != null && q.linkIds().size() > 500)
            throw new QueryFailure("TOO_LARGE", "Scope exceeds 500 links");
    }

    private QueryRequest with(QueryRequest q, long version, List<Long> ids) {
        var node = json.valueToTree(q);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("authVersion", version);
        if (ids != null)
            ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                    .set("linkIds", json.valueToTree(ids));
        return json.convertValue(node, QueryRequest.class);
    }

    private String digestRequest(QueryRequest q) {
        var node = json.valueToTree(q);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("authVersion");
        if (DimensionBreakdown.KIND.equals(q.kind())) {
            var options = DimensionBreakdown.options(q);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("dimensions", json.valueToTree(options.dimensions()));
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("filters", json.valueToTree(options.filterMaps()));
        }
        return hash(write(node));
    }

    private static String hash(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String v, Class<T> type) {
        try {
            return json.readValue(v, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt query job", e);
        }
    }

    private List<Map<String, Object>> readList(String v) {
        try {
            return json.readValue(v, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt query result page", e);
        }
    }
}
