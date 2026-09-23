package com.jupiter.shortlink.agent.harness.runtime;

import com.jupiter.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.graph.CampaignAnalysisGraphRequest;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignCurrentPrincipalResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignPublicRequestService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignPublicRequestStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignRunIntake;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.securityriskagent.graph.SecurityRiskGraphExecutor;
import com.jupiter.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAgentRunHarnessTest {

    @Test
    void runDelegatesToCampaignAnalysisGraphByDefaultWithTraceId() {
        CapturingCampaignAnalysisGraphExecutor campaignGraphExecutor = new CapturingCampaignAnalysisGraphExecutor();
        CapturingSecurityRiskGraphExecutor securityRiskGraphExecutor = new CapturingSecurityRiskGraphExecutor();
        DefaultAgentRunHarness harness = new DefaultAgentRunHarness(campaignGraphExecutor, securityRiskGraphExecutor);

        AgentRunResult result = harness.run(new AgentRunRequest(
                "session-1",
                null,
                "zhangsan",
                "analyze recent campaign data",
                null,
                "turn-123"
        ));

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.traceId()).isEqualTo(campaignGraphExecutor.request.traceId());
        assertThat(result.answer()).isEqualTo("campaign analysis result");
        assertThat(campaignGraphExecutor.request.traceId()).isNotBlank();
        assertThat(campaignGraphExecutor.request.sessionId()).isEqualTo("session-1");
        assertThat(campaignGraphExecutor.request.username()).isEqualTo("zhangsan");
        assertThat(campaignGraphExecutor.request.message()).isEqualTo("analyze recent campaign data");
        assertThat(campaignGraphExecutor.request.requestKey()).isEqualTo("turn-123");
        assertThat(securityRiskGraphExecutor.request).isNull();
    }

    @Test
    void runDelegatesToSecurityRiskGraphWhenAgentTypeIsSecurityRisk() {
        CapturingCampaignAnalysisGraphExecutor campaignGraphExecutor = new CapturingCampaignAnalysisGraphExecutor();
        CapturingSecurityRiskGraphExecutor securityRiskGraphExecutor = new CapturingSecurityRiskGraphExecutor();
        DefaultAgentRunHarness harness = new DefaultAgentRunHarness(campaignGraphExecutor, securityRiskGraphExecutor);

        AgentRunResult result = harness.run(new AgentRunRequest(
                "session-2",
                "security-risk",
                "zhangsan",
                "analyze gid=g1 security risk"
        ));

        assertThat(result.sessionId()).isEqualTo("session-2");
        assertThat(result.traceId()).isEqualTo(securityRiskGraphExecutor.request.traceId());
        assertThat(result.answer()).isEqualTo("security risk result");
        assertThat(campaignGraphExecutor.request).isNull();
        assertThat(securityRiskGraphExecutor.request.traceId()).isNotBlank();
        assertThat(securityRiskGraphExecutor.request.sessionId()).isEqualTo("session-2");
        assertThat(securityRiskGraphExecutor.request.username()).isEqualTo("zhangsan");
        assertThat(securityRiskGraphExecutor.request.message()).isEqualTo("analyze gid=g1 security risk");
    }

    @Test
    void runRejectsUnsupportedAgentTypeWithoutFallingBackToCampaignGraph() {
        CapturingCampaignAnalysisGraphExecutor campaignGraphExecutor = new CapturingCampaignAnalysisGraphExecutor();
        CapturingSecurityRiskGraphExecutor securityRiskGraphExecutor = new CapturingSecurityRiskGraphExecutor();
        DefaultAgentRunHarness harness = new DefaultAgentRunHarness(campaignGraphExecutor, securityRiskGraphExecutor);

        AgentRunResult result = harness.run(new AgentRunRequest(
                "session-3",
                "securityrisk",
                "zhangsan",
                "analyze gid=g1 security risk"
        ));

        assertThat(result.sessionId()).isEqualTo("session-3");
        assertThat(result.answer()).contains("Unsupported agent type");
        assertThat(result.warnings()).contains("Unsupported agent type: securityrisk");
        assertThat(result.dataSources()).isEmpty();
        assertThat(campaignGraphExecutor.request).isNull();
        assertThat(securityRiskGraphExecutor.request).isNull();
    }

    @Test
    void registeredV2RetryCannotDowngradeIntoLegacyExecution() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:harness_route_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260923__campaign_conversation_session_owner.sql"),
                new ClassPathResource("sql/migration/V20260923_3__campaign_conversation_turn.sql"),
                new ClassPathResource("sql/migration/V20260924_3__campaign_public_request.sql"),
                new ClassPathResource("sql/migration/V20260924_6__campaign_public_request_cancellation.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
        var sessions = new JdbcCampaignConversationSessionOwner(jdbc, transactions, clock);
        var turns = new JdbcCampaignConversationTurnStore(jdbc, transactions, sessions, clock);
        var requests = new CampaignPublicRequestStore(jdbc, transactions, clock);
        var principal = new AgentPrincipal("1001", "analyst", 7, false);
        var authorized = new AtomicBoolean(true);
        var authorityChecks = new AtomicInteger();
        var authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(any())).thenAnswer(call -> {
            authorityChecks.incrementAndGet();
            assertThat((AgentPrincipal) call.getArgument(0)).isEqualTo(principal);
            if (!authorized.get()) throw new SecurityException("ACCOUNT_REVOKED");
            return principal;
        });
        var principals = new CampaignCurrentPrincipalResolver(authority, sessions);
        var runs = new JdbcCampaignRunStore(jdbc, transactions, clock);
        var intakeStore = new JdbcCampaignRunIntakeStore(jdbc, transactions, clock, runs, 1024 * 1024);
        var gateway = mock(ShortLinkBusinessGateway.class);
        var recovery = mock(CampaignRecoveryStore.class);
        var intake = new CampaignRunIntake(intakeStore, runs, recovery,
                new StatisticsSubmissionReconciler(runs, gateway), null, null, List.of(), principals,
                new ProcessCapacityExecutor.Limits(1, 1, 1, 1), work -> {
                    throw new AssertionError("Routing cannot start background execution");
                });
        var model = mock(ChatModel.class);
        var planner = mock(CampaignPublicRequestService.PlannerInput.class);
        var delivery = mock(CampaignPublicRequestService.Delivery.class);
        var scheduled = new AtomicInteger();
        var service = new CampaignPublicRequestService(requests, turns,
                principals, intake, model, planner, "business", "1",
                ignored -> scheduled.incrementAndGet(), delivery, clock, Duration.ofHours(1));
        var campaign = new CapturingCampaignAnalysisGraphExecutor();
        var risk = new CapturingSecurityRiskGraphExecutor();
        var harness = new DefaultAgentRunHarness(campaign, risk);
        var identity = new CampaignRuntimeIdentityGuard(jdbc, authority);
        harness.setCampaignIdentity(identity);
        harness.setCampaignRequests(service);
        String question = "分析 gid=g1 的最近七天访问";
        harness.run(new AgentRunRequest("session-1", "campaign-analysis", principal.username(), question,
                principal, "registered", Set.of("campaign-response/v2"), AgentRunRequest.Operation.NEW, null, null));
        var downgraded = new AgentRunRequest("session-1", "campaign-analysis", principal.username(), question,
                principal, "registered");
        int checksBeforeRetry = authorityChecks.get();

        assertThatThrownBy(() -> harness.run(downgraded)).hasMessage("CAMPAIGN_CLIENT_UPGRADE_REQUIRED");
        assertThat(authorityChecks.get()).isGreaterThan(checksBeforeRetry);
        assertThat(campaign.calls).isZero();
        assertThat(scheduled.get()).isEqualTo(1);

        // A crash between raw registration and the turn write must also keep this request durable.
        requests.register(new Caller(principal.tenantId(), principal.username(), principal.authVersion()), "session-1",
                "raw-only", question, clock.instant().plusSeconds(3600));
        assertThatThrownBy(() -> harness.run(new AgentRunRequest("session-1", "campaign-analysis", principal.username(),
                question, principal, "raw-only"))).hasMessage("CAMPAIGN_CLIENT_UPGRADE_REQUIRED");
        assertThat(campaign.calls).isZero();
        authorized.set(false);
        assertThatThrownBy(() -> harness.run(downgraded)).isInstanceOf(SecurityException.class);
        assertThat(campaign.calls).isZero();

        authorized.set(true);
        var profileOff = new DefaultAgentRunHarness(campaign, risk);
        profileOff.setCampaignIdentity(identity);
        assertThatThrownBy(() -> profileOff.run(downgraded)).hasMessage("CAMPAIGN_DURABLE_RUNTIME_UNAVAILABLE");
        assertThatThrownBy(() -> profileOff.run(new AgentRunRequest("session-1", "campaign-analysis", "body_username",
                "username and question text do not override the trusted owner", principal, "registered")))
                .hasMessage("CAMPAIGN_DURABLE_RUNTIME_UNAVAILABLE");
        assertThat(campaign.calls).isZero();
        AgentRunResult compatible = profileOff.run(new AgentRunRequest("session-1", "campaign-analysis", principal.username(),
                "未登记的新旧版请求", principal, "profile-off-new"));
        assertThat(compatible.answer()).isEqualTo("campaign analysis result");
        AgentRunResult legacy = harness.run(new AgentRunRequest("session-1", "campaign-analysis", principal.username(),
                "新的旧版请求", principal, "new-legacy"));
        assertThat(legacy.answer()).isEqualTo("campaign analysis result");
        assertThat(campaign.calls).isEqualTo(2);
        assertThat(risk.request).isNull();
        assertThat(scheduled.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_conversation_turn", Integer.class)).isEqualTo(1);
        verifyNoInteractions(gateway, recovery, model, planner, delivery);
        assertThat(intake.snapshot().activeAdvances()).isZero();
        assertThat(intake.snapshot().queued()).isZero();
    }

    @Test
    void missingRuntimeSchemaIsNotCachedAndDatabaseFailureNeverAuthorizesDowngrade() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:harness_identity_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        var clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
        var principal = new AgentPrincipal("1001", "analyst", 7, false);
        var authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(principal)).thenReturn(principal);
        var campaign = new CapturingCampaignAnalysisGraphExecutor();
        var risk = new CapturingSecurityRiskGraphExecutor();
        var harness = new DefaultAgentRunHarness(campaign, risk);
        harness.setCampaignIdentity(new CampaignRuntimeIdentityGuard(jdbc, authority));
        var request = new AgentRunRequest("session-1", "campaign-analysis", principal.username(), "分析访问", principal, "same-key");
        harness.run(request);
        assertThat(campaign.calls).isEqualTo(1);
        verifyNoInteractions(authority);

        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260924_3__campaign_public_request.sql"),
                new ClassPathResource("sql/migration/V20260924_6__campaign_public_request_cancellation.sql")).execute(source);
        var store = new CampaignPublicRequestStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), clock);
        store.register(new Caller(principal.tenantId(), principal.username(), principal.authVersion()), "session-1",
                "same-key", request.message(), clock.instant().plusSeconds(3600));
        assertThatThrownBy(() -> harness.run(request)).hasMessage("CAMPAIGN_DURABLE_RUNTIME_UNAVAILABLE");
        assertThat(campaign.calls).isEqualTo(1);

        // An installed but broken schema is an error, never evidence of an unregistered identity.
        jdbc.execute("ALTER TABLE campaign_public_request DROP COLUMN request_key");
        assertThatThrownBy(() -> harness.run(request)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(campaign.calls).isEqualTo(1);
        assertThat(risk.request).isNull();
    }

    private static class CapturingCampaignAnalysisGraphExecutor implements CampaignAnalysisGraphExecutor {

        private CampaignAnalysisGraphRequest request;
        private int calls;

        @Override
        public AgentRunResult execute(CampaignAnalysisGraphRequest request) {
            calls++;
            this.request = request;
            return new AgentRunResult(
                    request.sessionId(),
                    request.traceId(),
                    "campaign analysis result",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.of("type", "graph", "name", "campaign-analysis-graph")),
                    List.of(),
                    List.of()
            );
        }
    }

    private static class CapturingSecurityRiskGraphExecutor implements SecurityRiskGraphExecutor {

        private SecurityRiskGraphRequest request;

        @Override
        public AgentRunResult execute(SecurityRiskGraphRequest request) {
            this.request = request;
            return new AgentRunResult(
                    request.sessionId(),
                    request.traceId(),
                    "security risk result",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.of("type", "graph", "name", "security-risk-graph")),
                    List.of(),
                    List.of()
            );
        }
    }
}
