package com.jupiter.shortlink.admin.config;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

class ProductionDataSourceIntegrationTest {
    static HikariDataSource physical;
    static DataSource sharded;
    static JdbcTemplate jdbc, raw;
    static TransactionTemplate transaction;

    @BeforeAll
    static void open() throws Exception {
        String url = System.getenv("SHORTLINK_ADMIN_SHARD_TEST_URL");
        if (url == null
                || !url.matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:(3306|13306)/shortlink_admin_sharding_it\\?.*")
                || !"true".equals(System.getenv("SHORTLINK_ADMIN_SHARD_TEST_ALLOW_RESET")))
            throw new IllegalStateException(
                    "Dedicated admin sharding catalog and reset opt-in required");
        var config = new ProductionDataSourceConfiguration();
        physical =
                config.accountPhysicalDataSource(
                        url,
                        System.getenv("SHORTLINK_ADMIN_SHARD_TEST_USER"),
                        System.getenv("SHORTLINK_ADMIN_SHARD_TEST_PASSWORD"));
        sharded = config.dataSource(physical, "integration-only-pii-key-at-least-32-bytes");
        jdbc = new JdbcTemplate(sharded);
        raw = new JdbcTemplate(physical);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(sharded));
    }

    @AfterAll
    static void close() throws Exception {
        if (sharded instanceof AutoCloseable c) c.close();
        if (physical != null) physical.close();
    }

    @BeforeEach
    void clear() {
        for (int i = 0; i < 16; i++) raw.update("DELETE FROM t_user_" + i);
        for (String t : List.of("t_account_identity", "t_account_initialization"))
            raw.update("DELETE FROM " + t);
    }

    @Test
    void actualProductionEncryptionAndShardingRoundTripFullLengthEmail() {
        String mail = "a".repeat(240) + "@example.test";
        jdbc.update(
                "INSERT INTO t_user(id,username,password,phone,mail) VALUES"
                    + " (1,'alice','fixture-hash','13800138000',?)",
                mail);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT mail FROM t_user WHERE username='alice'", String.class))
                .isEqualTo(mail);
        String physicalMail = null;
        for (int i = 0; i < 16; i++) {
            List<String> values =
                    raw.queryForList("SELECT mail FROM t_user_" + i + " WHERE id=1", String.class);
            if (!values.isEmpty()) physicalMail = values.get(0);
        }
        assertThat(physicalMail).isNotNull().doesNotContain("@example.test").isNotEqualTo(mail);
    }

    @Test
    void generatedGlobalIdentityAccountAndIntentRollbackTogetherWithProductionRouting() {
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            var key = new GeneratedKeyHolder();
                                            jdbc.update(
                                                    connection -> {
                                                        var p =
                                                                connection.prepareStatement(
                                                                        "INSERT INTO"
                                                                            + " t_account_identity(username,created_at)"
                                                                            + " VALUES ('alice',0)",
                                                                        Statement
                                                                                .RETURN_GENERATED_KEYS);
                                                        return p;
                                                    },
                                                    key);
                                            long tenant = key.getKey().longValue();
                                            jdbc.update(
                                                    "INSERT INTO t_user(id,username,password)"
                                                        + " VALUES (?,'alice','fixture-hash')",
                                                    tenant);
                                            jdbc.update(
                                                    "INSERT INTO"
                                                        + " t_account_initialization(tenant_id,username,command_id,state,next_attempt_at,created_at,updated_at)"
                                                        + " VALUES (?,'alice',?,'PENDING',0,0,0)",
                                                    tenant,
                                                    "account-default-group:" + tenant);
                                            throw new IllegalStateException(
                                                    "Injected failure after intent");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Injected failure after intent");
        assertThat(raw.queryForObject("SELECT COUNT(*) FROM t_account_identity", Long.class))
                .isZero();
        assertThat(raw.queryForObject("SELECT COUNT(*) FROM t_account_initialization", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM t_user WHERE username='alice'", Long.class))
                .isZero();
    }
}
