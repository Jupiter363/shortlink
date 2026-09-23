package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignSessionRecoveryServiceTest {
    private static final AgentPrincipal OWNER = new AgentPrincipal("1001", "analyst", 7, false);
    private static final AgentPrincipal OTHER = new AgentPrincipal("1002", "analyst", 7, false);
    private static final String QUESTION = "分析范围：指定分组（gid=1ff4df996bf244bdb86865533a92b12c）\n比较最近两个期间的投放📊";

    @Test void discoversUnreportedNeedsInputRequestWithExactQuestionAndIdentityWithoutExecutionOrWrites() {
        var f = new Fixture();
        f.add(OWNER, "older-session", "older", "先前问题", null);
        var prepared = f.add(OWNER, "current-session", "prepared", QUESTION, null);
        var clarification = f.add(OWNER, "current-session", "clarify", QUESTION + "，保留全部目标", prepared.reference().runId());
        String attempt = f.requests.begin(clarification, "a".repeat(64));
        f.requests.complete(clarification, attempt, "{}"); f.requests.callbackExited(clarification, attempt);
        f.requests.needsInput(clarification, "REQUIREMENTS_NEED_INPUT");
        // A newer foreign request must not affect default session discovery.
        f.add(OTHER, "foreign-session", "foreign", "private-question", null);
        var before = f.snapshot();

        var page = f.service.recover(OWNER, null, null, 20);
        assertEquals(CampaignSessionRecoveryService.SCHEMA, page.schemaVersion());
        assertEquals("current-session", page.sessionId());
        assertEquals(List.of("clarify", "prepared"), page.entries().stream().map(CampaignSessionRecoveryService.Entry::requestKey).toList());
        var restored = page.entries().get(0);
        assertEquals(QUESTION + "，保留全部目标", restored.originalQuestion());
        assertEquals(clarification.reference(), restored.continuation().workRef());
        assertEquals(prepared.reference().runId(), restored.previousRunId());
        assertEquals(clarification.createdAt(), restored.createdAt());
        assertEquals(clarification.expiresAt(), restored.expiresAt());
        assertEquals("NEEDS_INPUT", restored.requestState());
        assertEquals("PREPARED", page.entries().get(1).requestState());
        assertNull(page.nextCursor());
        assertEquals(before, f.snapshot(), "Recovery is read-only, including callback state and frozen request data");
        // Fixture deliberately has no Run, model, job, report or due-work tables. Only account
        // verification is callable from this read service, so recovery cannot submit execution.
        verify(f.authority, atLeast(2)).verifyCurrentPrincipal(OWNER);
        assertEquals(page, f.service.recover(OWNER, "current-session", null, 20));
        assertEquals(before, f.snapshot());
    }

    @Test void linkedNeedsInputAndAcceptedRequestsRestoreCurrentQuestionAcrossPagesWithoutExecuting() {
        var f = new Fixture();
        var first = f.add(OWNER, "linked-session", "first", QUESTION, null);
        f.finish(first, false);
        String clarificationQuestion = "这两个期间都保留，先解释为何访问下降📉";
        var clarification = f.add(OWNER, "linked-session", "clarify", clarificationQuestion, first.reference().runId());
        f.finish(clarification, true);
        String finalQuestion = "分析范围：分组「默认分组」；gid=g1;\n补充为 9 月 1 日到 7 日，以及 8 日到 14 日。";
        var completed = f.add(OWNER, "linked-session", "completed", finalQuestion, clarification.reference().runId());
        f.finish(completed, false);
        var before = f.snapshot();

        var latest = f.service.recover(OWNER, "linked-session", null, 1);
        assertEquals(finalQuestion, latest.entries().get(0).originalQuestion());
        assertEquals(completed.reference(), latest.entries().get(0).continuation().workRef());
        assertEquals("ACCEPTED", latest.entries().get(0).requestState());
        var middle = f.service.recover(OWNER, "linked-session", latest.nextCursor(), 1);
        assertEquals(clarificationQuestion, middle.entries().get(0).originalQuestion());
        assertEquals("NEEDS_INPUT", middle.entries().get(0).requestState());
        assertEquals(first.reference().runId(), middle.entries().get(0).previousRunId());
        var oldest = f.service.recover(OWNER, "linked-session", middle.nextCursor(), 1);
        assertEquals(QUESTION, oldest.entries().get(0).originalQuestion());
        assertNull(oldest.nextCursor());
        assertEquals(before, f.snapshot(), "Recovery verifies frozen context without changing requests, invoking models or loading reports");
    }

    @Test void linkedRecoveryRejectsUnrelatedCrossOwnerCrossSessionAndTamperedAncestorContext() {
        var f = new Fixture();
        var first = f.add(OWNER, "linked-session", "first", QUESTION, null);
        var linked = f.add(OWNER, "linked-session", "linked", "再分析设备维度", first.reference().runId());
        var unrelated = f.add(OWNER, "linked-session", "unrelated", "另一项无关分析", null);
        var foreign = f.add(OTHER, "foreign-session", "foreign", QUESTION, null);
        var otherSession = f.add(OWNER, "other-session", "other", QUESTION, null);
        for (String forged : List.of(unrelated.reference().runId(), foreign.reference().runId(),
                otherSession.reference().runId(), linked.reference().runId())) {
            f.jdbc.update("UPDATE campaign_conversation_turn SET previous_run_id=? WHERE request_id=?", forged, linked.reference().workId());
            assertThrows(SecurityException.class, () -> f.service.recover(OWNER, "linked-session", null, 20));
        }
        f.jdbc.update("UPDATE campaign_conversation_turn SET previous_run_id=? WHERE request_id=?",
                first.reference().runId(), linked.reference().workId());
        f.jdbc.update("UPDATE campaign_conversation_turn SET original_question=? WHERE request_id=?", "篡改本轮问题", linked.reference().workId());
        assertThrows(SecurityException.class, () -> f.service.recover(OWNER, "linked-session", null, 20));
        f.jdbc.update("UPDATE campaign_conversation_turn SET original_question=? WHERE request_id=?", "再分析设备维度", linked.reference().workId());
        f.jdbc.update("UPDATE campaign_public_request SET question_text=?,question_hash=? WHERE request_id=?",
                "篡改前驱问题", CampaignRunStore.sha256("篡改前驱问题"), first.reference().workId());
        var before = f.snapshot();
        assertThrows(SecurityException.class, () -> f.service.recover(OWNER, "linked-session", null, 20));
        assertEquals(before, f.snapshot(), "Rejected chains also remain read-only");
    }

    @Test void currentAccountSessionOwnershipAndAuthVersionAreRequiredBeforeAnyContentIsReturned() {
        var f = new Fixture();
        f.add(OWNER, "private-session", "private", QUESTION, null);
        assertTrue(f.service.recover(OTHER, null, null, 20).entries().isEmpty());
        assertThrows(SecurityException.class, () -> f.service.recover(OTHER, "private-session", null, 20));
        assertThrows(SecurityException.class, () -> f.service.recover(new AgentPrincipal("1001", "another-user", 7, false),
                "private-session", null, 20));
        var before = f.snapshot();
        f.allowed.set(false);
        assertThrows(SecurityException.class, () -> f.service.recover(OWNER, null, null, 20));
        assertThrows(SecurityException.class, () -> f.service.recover(OWNER, "private-session", null, 20));
        assertThrows(SecurityException.class, () -> f.service.sessions(OWNER, null, 20));
        f.allowed.set(true);
        var upgraded = new AgentPrincipal("1001", "analyst", 8, false);
        f.sessions.bindVerified(upgraded, "private-session");
        assertThrows(SecurityException.class, () -> f.service.recover(OWNER, "private-session", null, 20));
        assertTrue(f.service.recover(upgraded, null, null, 20).entries().isEmpty(), "Old auth epochs are not disclosed to a new epoch");
        assertTrue(f.service.sessions(upgraded, null, 20).entries().isEmpty());
        assertEquals(before.get("requests"), f.snapshot().get("requests"));
        assertEquals(before.get("turns"), f.snapshot().get("turns"));
    }

    @Test void listsOlderSessionsWithoutContentAndPagesStayBoundToCurrentIdentity() {
        var f = new Fixture();
        f.add(OWNER, "first-session", "first", QUESTION, null);
        f.add(OWNER, "second-session", "second", "第二个会话的问题", null);
        var firstInLatest = f.add(OWNER, "latest-session", "latest-first", "第三个会话的首个问题", null);
        var lastInLatest = f.add(OWNER, "latest-session", "latest-last", "第三个会话的第二个问题", firstInLatest.reference().runId());
        f.add(OTHER, "newer-foreign-session", "foreign", "不应泄露的内容", null);
        f.sessions.bindVerified(OWNER, "empty-session");
        var before = f.snapshot();

        var first = f.service.sessions(OWNER, null, 1);
        assertEquals(CampaignSessionRecoveryService.SESSIONS_SCHEMA, first.schemaVersion());
        assertEquals(1, first.entries().size());
        var latest = first.entries().get(0);
        assertEquals("latest-session", latest.sessionId());
        assertEquals(2, latest.requestCount());
        assertEquals(firstInLatest.createdAt(), latest.firstRequestAt());
        assertEquals(lastInLatest.createdAt(), latest.lastRequestAt());
        var second = f.service.sessions(OWNER, first.nextCursor(), 1);
        var last = f.service.sessions(OWNER, second.nextCursor(), 1);
        assertEquals("second-session", second.entries().get(0).sessionId());
        assertEquals("first-session", last.entries().get(0).sessionId());
        assertNull(last.nextCursor());
        assertEquals(List.of("sessionId", "firstRequestAt", "lastRequestAt", "requestCount"),
                java.util.Arrays.stream(CampaignSessionRecoveryService.SessionEntry.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals("newer-foreign-session", f.service.sessions(OTHER, null, 20).entries().get(0).sessionId());
        assertThrows(IllegalArgumentException.class, () -> f.service.sessions(OTHER, first.nextCursor(), 1));
        assertThrows(IllegalArgumentException.class, () -> f.service.sessions(OWNER, "invalid", 1));
        assertThrows(IllegalArgumentException.class, () -> f.service.sessions(OWNER, null, 51));
        String requestCursor = f.service.recover(OWNER, "latest-session", null, 1).nextCursor();
        assertThrows(IllegalArgumentException.class, () -> f.service.sessions(OWNER, requestCursor, 1));
        assertEquals(before, f.snapshot(), "Session enumeration neither creates nor advances requests");
    }

    @Test void keysetPagesStayInTheSameAuthorizedSessionAndRejectChangedStoredReferences() {
        var f = new Fixture();
        f.add(OWNER, "another-session", "elsewhere", "other-own-question", null);
        f.add(OWNER, "paged-session", "first", QUESTION, null);
        f.add(OWNER, "paged-session", "second", QUESTION + "补充二", null);
        var third = f.add(OWNER, "paged-session", "third", QUESTION + "补充三", null);
        var first = f.service.recover(OWNER, "paged-session", null, 1);
        var second = f.service.recover(OWNER, first.sessionId(), first.nextCursor(), 1);
        var last = f.service.recover(OWNER, first.sessionId(), second.nextCursor(), 1);
        assertEquals(List.of("third", "second", "first"), List.of(first.entries().get(0).requestKey(),
                second.entries().get(0).requestKey(), last.entries().get(0).requestKey()));
        assertNull(last.nextCursor());
        assertThrows(IllegalArgumentException.class, () -> f.service.recover(OWNER, null, first.nextCursor(), 1));
        assertThrows(IllegalArgumentException.class, () -> f.service.recover(OWNER, "another-session", first.nextCursor(), 1));
        assertThrows(IllegalArgumentException.class, () -> f.service.recover(OWNER, "paged-session", "malformed-cursor", 1));
        assertThrows(IllegalArgumentException.class, () -> f.service.recover(OWNER, "paged-session", null, 51));
        f.jdbc.update("UPDATE campaign_public_request SET question_text=? WHERE request_id=?", "changed-question", third.reference().workId());
        assertThrows(SecurityException.class, () -> f.service.recover(OWNER, "paged-session", null, 20));
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final JdbcTemplate jdbc;
        final CampaignPublicRequestStore requests;
        final JdbcCampaignConversationSessionOwner sessions;
        final JdbcCampaignConversationTurnStore turns;
        final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final CampaignSessionRecoveryService service;
        Fixture() {
            var data = new DriverManagerDataSource("jdbc:h2:mem:session-recovery-" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260923__campaign_conversation_session_owner.sql"),
                    new ClassPathResource("sql/migration/V20260923_3__campaign_conversation_turn.sql"),
                    new ClassPathResource("sql/migration/V20260924_3__campaign_public_request.sql"),
                    new ClassPathResource("sql/migration/V20260924_6__campaign_public_request_cancellation.sql")).execute(data);
            jdbc = new JdbcTemplate(data);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
            requests = new CampaignPublicRequestStore(jdbc, tx, clock);
            sessions = new JdbcCampaignConversationSessionOwner(jdbc, tx, clock);
            turns = new JdbcCampaignConversationTurnStore(jdbc, tx, sessions, clock);
            when(authority.verifyCurrentPrincipal(any())).thenAnswer(invocation -> {
                if (!allowed.get()) throw new SecurityException("CURRENT_AUTH_REVOKED");
                return invocation.getArgument(0);
            });
            service = new CampaignSessionRecoveryService(jdbc, authority, new CampaignCurrentPrincipalResolver(authority, sessions));
        }
        CampaignPublicRequestStore.Request add(AgentPrincipal principal, String session, String key, String question, String previous) {
            clock.advance();
            sessions.bindVerified(principal, session);
            var caller = new Caller(principal.tenantId(), principal.username(), principal.authVersion());
            String frozen = question;
            if (previous != null) {
                var predecessor = turns.requireRun(principal, session, previous);
                frozen = "上一轮问题（用于续接上下文）：\n" + requests.read(predecessor.workRef()).question()
                        + "\n本轮补充或新的分析要求：\n" + question;
            }
            var request = requests.register(caller, session, key, frozen, clock.instant().plusSeconds(3600));
            turns.recordVerified(principal, session, key, question, "{}", "campaign-response/v2", request.reference(), previous, request.expiresAt());
            return request;
        }
        void finish(CampaignPublicRequestStore.Request request, boolean needsInput) {
            String attempt = requests.begin(request, "a".repeat(64));
            requests.complete(request, attempt, "{}"); requests.callbackExited(request, attempt);
            if (needsInput) requests.needsInput(request, "REQUIREMENTS_NEED_INPUT");
            else requests.bind(request, new WorkRef(request.reference().runId(),
                    "planning-" + request.reference().workId().substring("request-".length())));
        }
        Map<String, List<Map<String, Object>>> snapshot() {
            return Map.of("requests", jdbc.queryForList("SELECT request_id,question_text,request_state,callback_active,attempt_id,response_json,reason_code,cancelled_at FROM campaign_public_request ORDER BY request_id"),
                    "turns", jdbc.queryForList("SELECT * FROM campaign_conversation_turn ORDER BY request_id"),
                    "sessions", jdbc.queryForList("SELECT * FROM campaign_conversation_session_owner ORDER BY session_id"));
        }
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T00:00:00Z");
        void advance() { now = now.plusSeconds(1); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
