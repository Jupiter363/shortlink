package com.nageoffer.shortlink.admin.authority.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.session.outbox.AgentAuthorityOutboxEvent;
import com.nageoffer.shortlink.admin.authority.session.outbox.JdbcAgentAuthorityOutboxRepository;
import com.nageoffer.shortlink.admin.authority.session.persistence.JdbcAgentSessionGrantRepository;
import com.nageoffer.shortlink.admin.authority.session.persistence.JdbcAgentTokenRevocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSessionGrantStoreJdbcTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    private static final String SESSION_ID = "as-s-integration";

    private static final String TENANT_ID = "shortlink-default";

    private static final String USERNAME = "test-user";

    private static final String FIRST_TOKEN_ID = "adt-first-token";

    private static final String SECOND_TOKEN_ID = "adt-second-token";

    private static final Set<String> SCOPES = Set.of(
            "agent:run",
            "capability:group:read",
            "capability:stats:read"
    );

    private DriverManagerDataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;

    private JdbcAgentAuthorityOutboxRepository outboxRepository;

    private AgentSessionGrantStore store;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent_authority_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        applyMigration();
        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper();
        JdbcAgentSessionGrantRepository grantRepository =
                new JdbcAgentSessionGrantRepository(jdbcTemplate, objectMapper);
        JdbcAgentTokenRevocationRepository revocationRepository =
                new JdbcAgentTokenRevocationRepository(jdbcTemplate);
        outboxRepository = new JdbcAgentAuthorityOutboxRepository(jdbcTemplate);
        store = new AgentSessionGrantStore(
                grantRepository,
                revocationRepository,
                outboxRepository,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new DataSourceTransactionManager(dataSource)
        );
    }

    @Test
    void refreshAndRevokePersistAuthoritativeStateAndMinimalEvent() throws Exception {
        bootstrap();

        AgentSessionGrantStore.RefreshResult refreshed = store.refresh(
                new AgentSessionGrantStore.RefreshCommand(
                        SESSION_ID,
                        TENANT_ID,
                        "",
                        USERNAME,
                        1,
                        FIRST_TOKEN_ID,
                        2,
                        SECOND_TOKEN_ID,
                        NOW.plusSeconds(240)
                )
        );

        assertThat(refreshed.grant().grantVersion()).isEqualTo(2);
        assertThat(refreshed.revokedTokenId()).isEqualTo(FIRST_TOKEN_ID);
        assertThat(store.isActive(SESSION_ID, 1, FIRST_TOKEN_ID)).isFalse();
        assertThat(store.isActive(SESSION_ID, 2, SECOND_TOKEN_ID)).isTrue();
        assertThat(count("t_agent_token_revocation")).isEqualTo(1);

        AgentSessionGrantStore.RevokeResult revoked = store.revoke(
                new AgentSessionGrantStore.RevokeCommand(
                        SESSION_ID,
                        TENANT_ID,
                        "",
                        USERNAME,
                        "USER_CLOSED"
                )
        );

        assertThat(revoked.changed()).isTrue();
        assertThat(revoked.grant().status()).isEqualTo(AgentSessionGrantStatus.REVOKED);
        assertThat(revoked.grant().grantVersion()).isEqualTo(3);
        assertThat(store.isActive(SESSION_ID, 2, SECOND_TOKEN_ID)).isFalse();
        assertThat(count("t_agent_token_revocation")).isEqualTo(2);
        assertThat(count("t_agent_authority_outbox")).isEqualTo(1);

        String payloadJson = jdbcTemplate.queryForObject(
                "select payload_json from t_agent_authority_outbox",
                String.class
        );
        JsonNode payload = objectMapper.readTree(payloadJson);
        Set<String> fieldNames = new HashSet<>();
        payload.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "eventId",
                "eventType",
                "occurredAt",
                "tenantId",
                "sessionId",
                "grantVersion",
                "status",
                "reasonCode",
                "revokedAt"
        );
        assertThat(payload.path("eventType").asText())
                .isEqualTo(AgentSessionGrantStore.SESSION_REVOKED_EVENT);
        assertThat(payload.path("tenantId").asText()).isEqualTo(TENANT_ID);
        assertThat(payload.path("sessionId").asText()).isEqualTo(SESSION_ID);
        assertThat(payload.path("grantVersion").asLong()).isEqualTo(3);
        assertThat(payload.path("status").asText()).isEqualTo("REVOKED");
        assertThat(payload.path("reasonCode").asText()).isEqualTo("USER_CLOSED");
        assertThat(payloadJson).doesNotContain(SECOND_TOKEN_ID, USERNAME, "scopes");
    }

    @Test
    void duplicateOutboxIdentityRollsBackGrantAndTokenRevocation() {
        bootstrap();
        assertThat(outboxRepository.createIfAbsent(new AgentAuthorityOutboxEvent(
                "ase-existing-event",
                AgentSessionGrantStore.SESSION_REVOKED_EVENT,
                "agent-session",
                SESSION_ID,
                2,
                TENANT_ID,
                "{}",
                NOW.minusSeconds(1)
        ))).isTrue();

        assertThatThrownBy(() -> store.revoke(new AgentSessionGrantStore.RevokeCommand(
                SESSION_ID,
                TENANT_ID,
                "",
                USERNAME,
                "USER_CLOSED"
        )))
                .isInstanceOf(AgentSessionGrantException.class)
                .extracting(exception -> ((AgentSessionGrantException) exception).reason())
                .isEqualTo(AgentSessionGrantException.Reason.CONFLICT);

        assertThat(jdbcTemplate.queryForObject(
                "select status from t_agent_session_grant where session_id = ?",
                String.class,
                SESSION_ID
        )).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select grant_version from t_agent_session_grant where session_id = ?",
                Long.class,
                SESSION_ID
        )).isEqualTo(1L);
        assertThat(count("t_agent_token_revocation")).isZero();
    }

    @Test
    void staleRefreshFailsClosedWithoutRevokingCurrentToken() {
        bootstrap();

        assertThatThrownBy(() -> store.refresh(new AgentSessionGrantStore.RefreshCommand(
                SESSION_ID,
                TENANT_ID,
                "",
                USERNAME,
                2,
                FIRST_TOKEN_ID,
                3,
                SECOND_TOKEN_ID,
                NOW.plusSeconds(240)
        )))
                .isInstanceOf(AgentSessionGrantException.class)
                .extracting(exception -> ((AgentSessionGrantException) exception).reason())
                .isEqualTo(AgentSessionGrantException.Reason.CONFLICT);

        assertThat(store.isActive(SESSION_ID, 1, FIRST_TOKEN_ID)).isTrue();
        assertThat(count("t_agent_token_revocation")).isZero();
    }

    @Test
    void authorityScopeSubsetCanReuseParentDelegationTokenCheck() {
        bootstrap();

        AgentSessionGrant active = store.requireActiveToken(
                new AgentSessionGrantStore.ActiveTokenCommand(
                        SESSION_ID,
                        TENANT_ID,
                        "username:" + USERNAME,
                        USERNAME,
                        1,
                        Set.of("capability:group:read"),
                        FIRST_TOKEN_ID
                )
        );

        assertThat(active.sessionId()).isEqualTo(SESSION_ID);
        assertThatThrownBy(() -> store.requireActiveToken(
                new AgentSessionGrantStore.ActiveTokenCommand(
                        SESSION_ID,
                        TENANT_ID,
                        "username:" + USERNAME,
                        USERNAME,
                        1,
                        Set.of("capability:delete"),
                        FIRST_TOKEN_ID
                )
        )).isInstanceOf(AgentSessionGrantException.class);
    }

    @Test
    void migrationIsRerunnableAndRecordsOneHistoryEntry() {
        applyMigration();

        assertThat(count("t_agent_authority_schema_migration_history")).isEqualTo(1);
        assertThat(count("t_agent_session_grant")).isZero();
        assertThat(count("t_agent_token_revocation")).isZero();
        assertThat(count("t_agent_authority_outbox")).isZero();
    }

    private void bootstrap() {
        AgentSessionGrant grant = store.bootstrap(new AgentSessionGrantStore.BootstrapCommand(
                SESSION_ID,
                TENANT_ID,
                "",
                USERNAME,
                AgentSessionGrantStore.CAMPAIGN_ANALYSIS_AGENT,
                SCOPES,
                1,
                FIRST_TOKEN_ID,
                NOW.plusSeconds(180),
                NOW.plusSeconds(3600)
        ));
        assertThat(grant.sessionId()).startsWith("as-s-");
        assertThat(store.isActive(SESSION_ID, 1, FIRST_TOKEN_ID)).isTrue();
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("select count(1) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private void applyMigration() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(
                        "sql/migration/V20260717_01__agent_session_grant_authority.sql"
                )
        );
        populator.execute(dataSource);
    }
}
