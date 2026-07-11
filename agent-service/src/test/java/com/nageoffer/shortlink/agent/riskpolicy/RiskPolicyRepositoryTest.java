package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RiskPolicyRepositoryTest {

    private static final LocalDateTime EFFECTIVE_TIME =
            LocalDateTime.of(2026, 7, 10, 10, 0);

    @Test
    void savesPolicyHistoryByIdempotencyKeyAndVersion() {
        Fixture fixture = fixture("risk_policy_history_version");
        RiskPolicy first = policy(
                "policy-1",
                "manual:action-1",
                "key-1",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        RiskPolicy second = policy(
                "policy-2",
                "manual:action-2",
                "key-1",
                2L,
                RiskPolicyStatus.ACTIVE
        );

        fixture.repository().insert(first);
        fixture.repository().markSuperseded("policy-1", "trace-2");
        fixture.repository().insert(second);

        assertThat(fixture.repository().findByIdempotencyKey("manual:action-1"))
                .isPresent()
                .get()
                .extracting(RiskPolicy::status)
                .isEqualTo(RiskPolicyStatus.SUPERSEDED);
        assertThat(fixture.repository().findByPolicyKeyOrderByVersion("key-1"))
                .extracting(RiskPolicy::policyVersion)
                .containsExactly(2L, 1L);
        assertThat(fixture.repository().findActiveByPolicyKey("key-1"))
                .contains(second);
    }

    @Test
    void duplicateIdempotencyKeyFailsWithoutOverwritingHistory() {
        Fixture fixture = fixture("risk_policy_history_idempotency");
        RiskPolicy first = policy(
                "policy-1",
                "manual:action-1",
                "key-1",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        RiskPolicy conflicting = policy(
                "policy-2",
                "manual:action-1",
                "key-2",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        fixture.repository().insert(first);

        assertThatThrownBy(() -> fixture.repository().insert(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(fixture.repository().findByPolicyId("policy-1")).contains(first);
        assertThat(fixture.repository().findByPolicyId("policy-2")).isEmpty();
    }

    @Test
    void duplicatePolicyKeyVersionFailsWithoutCreatingAmbiguousHistory() {
        Fixture fixture = fixture("risk_policy_history_key_version");
        RiskPolicy first = policy(
                "policy-1",
                "manual:action-1",
                "key-1",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        RiskPolicy conflicting = policy(
                "policy-2",
                "manual:action-2",
                "key-1",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        fixture.repository().insert(first);

        assertThatThrownBy(() -> fixture.repository().insert(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(fixture.repository().findByPolicyKeyOrderByVersion("key-1"))
                .containsExactly(first);
    }

    @Test
    void duplicatePolicyIdCannotMutateExistingHistoryPayload() {
        Fixture fixture = fixture("risk_policy_history_immutable");
        RiskPolicy first = policy(
                "policy-1",
                "manual:action-1",
                "key-1",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        RiskPolicy conflicting = new RiskPolicy(
                first.policyId(),
                first.policyKey(),
                "manual:action-2",
                2L,
                first.action(),
                first.targetType(),
                first.gid(),
                first.domain(),
                first.shortUri(),
                first.ipHash(),
                "{\"action\":\"LIMIT_RATE\",\"limit\":1}",
                first.status(),
                first.effectiveTime().plusMinutes(1),
                first.expireTime(),
                first.source(),
                "trace-conflict",
                first.eventId()
        );
        fixture.repository().insert(first);

        assertThatThrownBy(() -> fixture.repository().insert(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(fixture.repository().findByPolicyId("policy-1"))
                .contains(first);
    }

    @Test
    void marksHistoryDisabledExpiredAndWritesAudit() {
        Fixture fixture = fixture("risk_policy_history_status");
        RiskPolicy disabled = policy(
                "policy-disabled",
                "manual:disabled",
                "key-disabled",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        RiskPolicy expired = policy(
                "policy-expired",
                "auto:expired",
                "key-expired",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        fixture.repository().insert(disabled);
        fixture.repository().insert(expired);
        fixture.auditRepository().saveActivationAudit(
                disabled,
                "manual-user",
                "confirmed high risk"
        );

        fixture.repository().markDisabled("policy-disabled", "trace-disabled");
        fixture.repository().markExpired("policy-expired", "trace-expired");
        fixture.repository().markExpired("policy-disabled", "trace-must-not-overwrite");
        fixture.repository().markDisabled("policy-expired", "trace-must-not-overwrite");

        assertThat(fixture.repository().findByPolicyId("policy-disabled"))
                .get()
                .satisfies(policy -> {
                    assertThat(policy.status()).isEqualTo(RiskPolicyStatus.DISABLED);
                    assertThat(policy.traceId()).isEqualTo("trace-disabled");
                });
        assertThat(fixture.repository().findByPolicyId("policy-expired"))
                .get()
                .satisfies(policy -> {
                    assertThat(policy.status()).isEqualTo(RiskPolicyStatus.EXPIRED);
                    assertThat(policy.traceId()).isEqualTo("trace-expired");
                });
        assertThat(fixture.repository().findActiveByPolicyKey("key-disabled")).isEmpty();
        assertThat(fixture.repository().findActiveByPolicyKey("key-expired")).isEmpty();
        assertThat(fixture.auditRepository().countByPolicyId("policy-disabled")).isEqualTo(1);
    }

    @Test
    void readsNullableLegacyHistoryButRejectsLegacyShapedNewWrites() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(h2DataSource("risk_policy_legacy_read"));
        createLegacyPolicyTable(jdbcTemplate);
        jdbcTemplate.update("""
                        insert into t_agent_risk_policy (
                            policy_id, policy_key, idempotency_key, policy_version,
                            action, target_type, gid, domain, short_uri, ip_hash,
                            policy_payload_json, status, effective_time, expire_time,
                            source, trace_id, event_id
                        ) values (?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                        """,
                "legacy-policy-1",
                "legacy-key-1",
                RiskPolicyAction.DISABLE_SHORT_LINK.name(),
                RiskTargetType.SHORT_LINK.name(),
                "gid-legacy",
                "nurl.ink",
                "legacy001",
                "",
                "{\"action\":\"DISABLE_SHORT_LINK\"}",
                RiskPolicyStatus.ACTIVE.name(),
                EFFECTIVE_TIME,
                RiskPolicySource.MANUAL_REVIEW.name(),
                "trace-legacy",
                "event-legacy"
        );
        JdbcRiskPolicyRepository repository = new JdbcRiskPolicyRepository(jdbcTemplate);

        assertThat(repository.findByPolicyId("legacy-policy-1"))
                .isPresent()
                .get()
                .satisfies(policy -> {
                    assertThat(policy.idempotencyKey()).isEqualTo("legacy:legacy-policy-1");
                    assertThat(policy.policyVersion()).isZero();
                });

        RiskPolicy invalidNewPolicy = policy(
                "policy-invalid",
                "",
                "key-invalid",
                0L,
                RiskPolicyStatus.ACTIVE
        );
        assertThatThrownBy(() -> repository.insert(invalidNewPolicy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk policy history identity is invalid");
    }

    @Test
    void rejectsBlankIdempotencyAndNonpositiveVersionBeforeJdbc() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcRiskPolicyRepository repository = new JdbcRiskPolicyRepository(jdbcTemplate);
        RiskPolicy blankIdempotency = policy(
                "policy-blank-idempotency",
                " ",
                "key-blank-idempotency",
                1L,
                RiskPolicyStatus.ACTIVE
        );
        RiskPolicy nonpositiveVersion = policy(
                "policy-nonpositive-version",
                "manual:nonpositive-version",
                "key-nonpositive-version",
                0L,
                RiskPolicyStatus.ACTIVE
        );

        assertThatThrownBy(() -> repository.insert(blankIdempotency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk policy history identity is invalid");
        assertThatThrownBy(() -> repository.insert(nonpositiveVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk policy history identity is invalid");
        verifyNoInteractions(jdbcTemplate);
    }

    private Fixture fixture(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/agent_service_schema.sql")
        ).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new Fixture(
                new JdbcRiskPolicyRepository(jdbcTemplate),
                new JdbcRiskActionAuditRepository(jdbcTemplate)
        );
    }

    private RiskPolicy policy(
            String policyId,
            String idempotencyKey,
            String policyKey,
            long policyVersion,
            RiskPolicyStatus status
    ) {
        return new RiskPolicy(
                policyId,
                policyKey,
                idempotencyKey,
                policyVersion,
                RiskPolicyAction.DISABLE_SHORT_LINK,
                RiskTargetType.SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                "",
                "{\"action\":\"DISABLE_SHORT_LINK\"}",
                status,
                EFFECTIVE_TIME.plusMinutes(policyVersion),
                null,
                RiskPolicySource.MANUAL_REVIEW,
                "trace-" + policyVersion,
                "event-" + policyVersion
        );
    }

    private void createLegacyPolicyTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                create table t_agent_risk_policy (
                    id bigint not null auto_increment primary key,
                    policy_id varchar(128) not null unique,
                    policy_key varchar(512) not null,
                    idempotency_key varchar(512) null,
                    policy_version bigint null,
                    action varchar(64) not null,
                    target_type varchar(32) not null,
                    gid varchar(64) not null default '',
                    domain varchar(256) not null default '',
                    short_uri varchar(128) not null default '',
                    ip_hash varchar(128) not null default '',
                    policy_payload_json longtext not null,
                    status varchar(32) not null,
                    effective_time timestamp not null,
                    expire_time timestamp null,
                    source varchar(64) not null,
                    trace_id varchar(128) not null default '',
                    event_id varchar(128) not null default '',
                    create_time timestamp not null default current_timestamp,
                    update_time timestamp not null default current_timestamp
                )
                """);
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:" + name
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record Fixture(
            JdbcRiskPolicyRepository repository,
            JdbcRiskActionAuditRepository auditRepository
    ) {
    }
}
