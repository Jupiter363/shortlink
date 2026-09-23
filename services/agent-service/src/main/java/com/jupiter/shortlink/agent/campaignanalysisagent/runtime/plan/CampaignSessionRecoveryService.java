package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunRequest.Continuation;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only discovery of existing public requests. It has no model, intake, scheduler or result executor. */
public final class CampaignSessionRecoveryService {
    public static final String SCHEMA = "campaign-session-recovery/v1";
    public static final String SESSIONS_SCHEMA = "campaign-session-list/v1";
    public record Entry(String sessionId, String requestKey, String originalQuestion, Continuation continuation,
                        String previousRunId, Instant createdAt, Instant expiresAt, String requestState) {}
    public record Page(String schemaVersion, String sessionId, List<Entry> entries, String nextCursor) {
        public Page { entries = List.copyOf(entries); }
    }
    public record SessionEntry(String sessionId, Instant firstRequestAt, Instant lastRequestAt, long requestCount) {}
    public record SessionsPage(String schemaVersion, List<SessionEntry> entries, String nextCursor) {
        public SessionsPage { entries = List.copyOf(entries); }
    }
    private record Cursor(String sessionId, String ownerHash, long beforeCreatedAt, String beforeRequestId) {}
    private record SessionsCursor(String ownerHash, long beforeLastRequestAt, String beforeSessionId) {}
    private record FrozenEntry(Entry entry, String question) {}
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).build();
    private static final String JOIN = " FROM campaign_public_request p JOIN campaign_conversation_turn t"
            + " ON t.request_id=p.request_id AND t.run_id=p.run_id AND t.session_id=p.session_id"
            + " AND t.request_key=p.request_key AND t.tenant_id=p.tenant_id AND t.subject_name=p.subject_name AND t.auth_version=p.auth_version"
            + " JOIN campaign_conversation_session_owner s ON s.session_id=p.session_id"
            + " AND s.tenant_id=p.tenant_id AND s.subject_name=p.subject_name AND s.auth_version=p.auth_version"
            + " WHERE p.tenant_id=? AND p.subject_name=? AND p.auth_version=?";
    private static final String COLUMNS = "p.request_id,p.run_id,p.request_key,p.question_text,p.question_hash,p.created_at,p.expires_at,"
            + "p.request_state,t.original_question,t.previous_run_id";
    private final JdbcTemplate jdbc;
    private final AgentAuthorityClient authority;
    private final CampaignCurrentPrincipalResolver principals;

    public CampaignSessionRecoveryService(JdbcTemplate jdbc, AgentAuthorityClient authority, CampaignCurrentPrincipalResolver principals) {
        this.jdbc = Objects.requireNonNull(jdbc); this.authority = Objects.requireNonNull(authority);
        this.principals = Objects.requireNonNull(principals);
    }

    /** Session summaries contain no question or model response, and only currently owned public sessions. */
    public SessionsPage sessions(AgentPrincipal expected, String encodedCursor, int size) {
        if (expected == null || expected.system()) throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        if (size < 1 || size > 50) throw new IllegalArgumentException("CAMPAIGN_RECOVERY_PAGE_SIZE_INVALID");
        if (!expected.equals(authority.verifyCurrentPrincipal(expected))) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        Caller caller = new Caller(expected.tenantId(), expected.username(), expected.authVersion());
        SessionsCursor cursor = decodeSessionsCursor(encodedCursor, caller);
        var arguments = new ArrayList<Object>(List.of(caller.tenantId(), caller.subject(), caller.authVersion()));
        String after = "";
        if (cursor != null) {
            after = " HAVING (MAX(p.created_at)<? OR (MAX(p.created_at)=? AND p.session_id<?))";
            arguments.add(cursor.beforeLastRequestAt()); arguments.add(cursor.beforeLastRequestAt()); arguments.add(cursor.beforeSessionId());
        }
        arguments.add(size + 1);
        var rows = jdbc.query("SELECT p.session_id,MIN(p.created_at) first_at,MAX(p.created_at) last_at,COUNT(*) request_count"
                + JOIN + " GROUP BY p.session_id" + after + " ORDER BY last_at DESC,p.session_id DESC LIMIT ?",
                (rs, row) -> new SessionEntry(rs.getString("session_id"), Instant.ofEpochMilli(rs.getLong("first_at")),
                        Instant.ofEpochMilli(rs.getLong("last_at")), rs.getLong("request_count")), arguments.toArray());
        List<SessionEntry> page = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        // Recheck all returned ownership rows in one bounded query, then the current account.
        // No per-session remote calls or new session bindings are needed for a metadata list.
        if (!page.isEmpty()) {
            var owners = new ArrayList<Object>(List.of(caller.tenantId(), caller.subject(), caller.authVersion()));
            page.forEach(entry -> owners.add(entry.sessionId()));
            Long valid = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_conversation_session_owner"
                            + " WHERE tenant_id=? AND subject_name=? AND auth_version=? AND session_id IN ("
                            + String.join(",", Collections.nCopies(page.size(), "?")) + ")", Long.class, owners.toArray());
            if (valid == null || valid != page.size()) throw new SecurityException("CAMPAIGN_SESSION_OWNER_CHANGED");
        }
        if (!expected.equals(authority.verifyCurrentPrincipal(expected))) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        String next = null;
        if (rows.size() > size) {
            SessionEntry last = page.get(page.size() - 1);
            String json = FrozenCampaignRun.encode(new SessionsCursor(ownerHash(caller), last.lastRequestAt().toEpochMilli(), last.sessionId()));
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        return new SessionsPage(SESSIONS_SCHEMA, page, next);
    }

    public Page recover(AgentPrincipal expected, String sessionId, String encodedCursor, int size) {
        if (expected == null || expected.system()) throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        if (size < 1 || size > 50) throw new IllegalArgumentException("CAMPAIGN_RECOVERY_PAGE_SIZE_INVALID");
        if (!expected.equals(authority.verifyCurrentPrincipal(expected))) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        Caller caller = new Caller(expected.tenantId(), expected.username(), expected.authVersion());
        if (encodedCursor != null && sessionId == null) throw new IllegalArgumentException("CAMPAIGN_RECOVERY_CURSOR_INVALID");
        if (sessionId == null) {
            var recent = jdbc.query("SELECT p.session_id" + JOIN + " ORDER BY p.created_at DESC,p.request_id DESC LIMIT 1",
                    (rs, row) -> rs.getString(1), caller.tenantId(), caller.subject(), caller.authVersion());
            if (recent.isEmpty()) {
                if (!expected.equals(authority.verifyCurrentPrincipal(expected))) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
                return new Page(SCHEMA, null, List.of(), null);
            }
            sessionId = recent.get(0);
        }
        requireCurrent(expected, caller, sessionId);
        String selectedSession = sessionId;
        Cursor cursor = decode(encodedCursor, caller, sessionId);
        var arguments = new ArrayList<Object>(List.of(caller.tenantId(), caller.subject(), caller.authVersion(), sessionId));
        String after = "";
        if (cursor != null) {
            after = " AND (p.created_at<? OR (p.created_at=? AND p.request_id<?))";
            arguments.add(cursor.beforeCreatedAt()); arguments.add(cursor.beforeCreatedAt()); arguments.add(cursor.beforeRequestId());
        }
        arguments.add(size + 1);
        var rows = jdbc.query("SELECT " + COLUMNS + JOIN + " AND p.session_id=?" + after
                        + " ORDER BY p.created_at DESC,p.request_id DESC LIMIT ?",
                (rs, row) -> frozenEntry(rs, caller, selectedSession), arguments.toArray());
        Map<String, FrozenEntry> known = new HashMap<>();
        rows.forEach(row -> known.put(row.entry().continuation().runId(), row));
        Set<String> verified = new HashSet<>();
        rows.forEach(row -> verifyQuestionChain(caller, selectedSession, row, known, verified));
        requireCurrent(expected, caller, sessionId);
        boolean more = rows.size() > size;
        List<Entry> page = rows.subList(0, Math.min(size, rows.size())).stream().map(FrozenEntry::entry).toList();
        return new Page(SCHEMA, sessionId, page, more ? encode(caller, page.get(page.size() - 1)) : null);
    }

    private static FrozenEntry frozenEntry(ResultSet rs, Caller caller, String session) throws SQLException {
        String key = rs.getString("request_key"), question = rs.getString("question_text");
        String original = rs.getString("original_question");
        var identity = JdbcCampaignRunIntakeStore.identity(caller, session, key);
        String requestId = "request-" + identity.requestId().substring("intake-".length());
        if (!identity.runId().equals(rs.getString("run_id")) || !requestId.equals(rs.getString("request_id"))
                || question == null || question.isBlank() || question.length() > 16000
                || original == null || original.isBlank() || original.length() > 16000
                || !CampaignRunStore.sha256(question).equals(rs.getString("question_hash")))
            throw new SecurityException("CAMPAIGN_RECOVERY_REFERENCE_CHANGED");
        return new FrozenEntry(new Entry(session, key, original, new Continuation(identity.runId(), requestId),
                rs.getString("previous_run_id"), Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("expires_at")), rs.getString("request_state")), question);
    }

    private void verifyQuestionChain(Caller caller, String session, FrozenEntry current,
            Map<String, FrozenEntry> known, Set<String> verified) {
        Set<String> visiting = new HashSet<>();
        while (!verified.contains(current.entry().continuation().runId())) {
            if (!visiting.add(current.entry().continuation().runId()))
                throw new SecurityException("CAMPAIGN_RECOVERY_REFERENCE_CHANGED");
            String previous = current.entry().previousRunId();
            if (previous == null) {
                if (!current.question().equals(current.entry().originalQuestion()))
                    throw new SecurityException("CAMPAIGN_RECOVERY_REFERENCE_CHANGED");
                break;
            }
            FrozenEntry predecessor = known.get(previous);
            if (predecessor == null) {
                // The same ownership/session join applies to ancestors outside this result page.
                var matches = jdbc.query("SELECT " + COLUMNS + JOIN + " AND p.session_id=? AND p.run_id=?",
                        (rs, row) -> frozenEntry(rs, caller, session), caller.tenantId(), caller.subject(), caller.authVersion(), session, previous);
                if (matches.size() != 1) throw new SecurityException("CAMPAIGN_RECOVERY_REFERENCE_CHANGED");
                predecessor = matches.get(0); known.put(previous, predecessor);
            }
            String expected = "上一轮问题（用于续接上下文）：\n" + predecessor.question()
                    + "\n本轮补充或新的分析要求：\n" + current.entry().originalQuestion();
            if (!expected.equals(current.question())) throw new SecurityException("CAMPAIGN_RECOVERY_REFERENCE_CHANGED");
            // Exact concatenation strictly shortens each ancestor. The persisted 16,000-character
            // request bound limits traversal; per-page caching avoids revisiting a shared chain.
            current = predecessor;
        }
        verified.addAll(visiting);
    }

    private void requireCurrent(AgentPrincipal expected, Caller caller, String session) {
        if (!expected.equals(principals.resolve(caller, session))) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
    }
    private static String ownerHash(Caller caller) {
        return CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(caller.tenantId(), caller.subject(), caller.authVersion())));
    }
    private static String encode(Caller caller, Entry last) {
        String json = FrozenCampaignRun.encode(new Cursor(last.sessionId(), ownerHash(caller),
                last.createdAt().toEpochMilli(), last.continuation().requestId()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
    private static Cursor decode(String encoded, Caller caller, String session) {
        if (encoded == null) return null;
        try {
            if (encoded.isBlank() || encoded.length() > 1024) throw new IllegalArgumentException();
            Cursor cursor = JSON.readValue(Base64.getUrlDecoder().decode(encoded), Cursor.class);
            if (!session.equals(cursor.sessionId()) || !ownerHash(caller).equals(cursor.ownerHash())
                    || cursor.beforeCreatedAt() < 0 || !cursor.beforeRequestId().matches("request-[a-f0-9]{64}"))
                throw new IllegalArgumentException();
            return cursor;
        } catch (Exception invalid) { throw new IllegalArgumentException("CAMPAIGN_RECOVERY_CURSOR_INVALID"); }
    }

    private static SessionsCursor decodeSessionsCursor(String encoded, Caller caller) {
        if (encoded == null) return null;
        try {
            if (encoded.isBlank() || encoded.length() > 1024) throw new IllegalArgumentException();
            SessionsCursor cursor = JSON.readValue(Base64.getUrlDecoder().decode(encoded), SessionsCursor.class);
            if (!ownerHash(caller).equals(cursor.ownerHash()) || cursor.beforeLastRequestAt() < 0
                    || !cursor.beforeSessionId().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")) throw new IllegalArgumentException();
            return cursor;
        } catch (Exception invalid) { throw new IllegalArgumentException("CAMPAIGN_RECOVERY_CURSOR_INVALID"); }
    }
}
