package com.jupiter.shortlink.command.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in read-only probe against a disposable/local MySQL instance using the real pool builder. */
@EnabledIfEnvironmentVariable(named = "SHORTLINK_UAT_DB_URL", matches = ".+")
class UtcJdbcSessionIT {
    @Test
    void physicalConnectionUsesUtcForDatabaseGeneratedTimes() throws Exception {
        var config =
                CommandDataSourceConfiguration.physicalConfiguration(
                        System.getenv("SHORTLINK_UAT_DB_URL"),
                        System.getenv("SHORTLINK_UAT_DB_USERNAME"),
                        System.getenv("SHORTLINK_UAT_DB_PASSWORD"));
        config.setMaximumPoolSize(1);
        try (var pool = new HikariDataSource(config);
                var connection = pool.getConnection();
                var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            try (var result = statement.executeQuery(
                    "SELECT @@session.time_zone, TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW())")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("+00:00");
                assertThat(result.getLong(2)).isZero();
            }
        }
    }
}
