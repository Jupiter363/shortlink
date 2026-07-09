package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyRepositoryTest {

    @Test
    void upsertsActivePolicyAndWritesAudit() {
        DataSource dataSource = h2DataSource("risk_policy_repository");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcRiskPolicyRepository policyRepository = new JdbcRiskPolicyRepository(jdbcTemplate);
        JdbcRiskActionAuditRepository auditRepository = new JdbcRiskActionAuditRepository(jdbcTemplate);

        RiskPolicy policy = RiskPolicy.shortLinkPolicy(
                "policy-001",
                "risk:policy:short-link:disable:nurl.ink:abc123",
                RiskPolicyAction.DISABLE_SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"DISABLE_SHORT_LINK\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "trace-001",
                "event-001"
        );

        policyRepository.saveActive(policy);
        auditRepository.saveActivationAudit(policy, "manual-user", "confirmed high risk");

        assertThat(policyRepository.findByPolicyId("policy-001")).isPresent();
        assertThat(policyRepository.findActiveByPolicyKey(policy.policyKey())).isPresent();
        assertThat(policyRepository.findByPolicyId("policy-001").get().status()).isEqualTo(RiskPolicyStatus.ACTIVE);
        assertThat(auditRepository.countByPolicyId("policy-001")).isEqualTo(1);

        policyRepository.disable("policy-001", "trace-002");
        assertThat(policyRepository.findByPolicyId("policy-001").get().status()).isEqualTo(RiskPolicyStatus.DISABLED);
    }

    @Test
    void expiresPolicyAndRemovesItFromActiveLookup() {
        DataSource dataSource = h2DataSource("risk_policy_expire");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcRiskPolicyRepository policyRepository = new JdbcRiskPolicyRepository(jdbcTemplate);

        RiskPolicy policy = RiskPolicy.shortLinkPolicy(
                "policy-expired-001",
                "risk:policy:short-link:rate-limit:nurl.ink:abc123",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "trace-001",
                "event-001",
                LocalDateTime.of(2026, 7, 10, 10, 59)
        );

        policyRepository.saveActive(policy);
        policyRepository.expire("policy-expired-001", "trace-002");

        assertThat(policyRepository.findByPolicyId("policy-expired-001").get().status()).isEqualTo(RiskPolicyStatus.EXPIRED);
        assertThat(policyRepository.findActiveByPolicyKey(policy.policyKey())).isEmpty();
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
