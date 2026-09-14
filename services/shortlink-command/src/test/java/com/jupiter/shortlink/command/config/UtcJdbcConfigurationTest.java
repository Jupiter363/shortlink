package com.jupiter.shortlink.command.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UtcJdbcConfigurationTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:13306/shortlink_business_it";

    @Test
    void missingZoneGetsTheUtcDriverAndSqlSessionContractWithoutOpeningAConnection() {
        HikariConfig config =
                CommandDataSourceConfiguration.physicalConfiguration(URL, "test-user", "test-only");
        assertThat(config.getJdbcUrl())
                .isEqualTo(
                        URL
                                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true");
        assertThat(config.getDataSourceProperties())
                .containsEntry("connectionTimeZone", "UTC")
                .containsEntry("forceConnectionTimeZoneToSession", "true")
                .containsEntry("preserveInstants", "true");
        assertThat(config.getConnectionInitSql()).isEqualTo("SET SESSION time_zone = '+00:00'");
    }

    @Test
    void currentLocalServerTimezoneAliasIsAcceptedAndCanonicalized() {
        String result =
                UtcJdbcConfiguration.normalize(
                        URL + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertThat(result)
                .isEqualTo(
                        URL
                                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true")
                .doesNotContain("serverTimezone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Etc%2FUTC", "GMT", "%2B00%3A00", "Z"})
    void fixedZeroOffsetAliasesHaveTheSameCanonicalUtcContract(String zone) {
        assertThat(UtcJdbcConfiguration.normalize(URL + "?connectionTimeZone=" + zone))
                .contains("connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "serverTimezone=Asia%2FShanghai",
                "connectionTimeZone=America%2FLos_Angeles",
                "connectionTimeZone=LOCAL",
                "connectionTimeZone=SERVER",
                "connectionTimeZone=",
                "serverTimezone=UTC&connectionTimeZone=Asia%2FShanghai",
                "serverTimezone=UTC&serverTimezone=Asia%2FShanghai",
                "serverTimezone%20=Asia%2FShanghai",
                "forceConnectionTimeZoneToSession=false",
                "preserveInstants=false",
                "preserveInstants=true&preserveInstants=false"
            })
    void conflictingUrlPropertiesFailBeforeThePoolCanConnect(String query) {
        assertThatThrownBy(
                        () ->
                                CommandDataSourceConfiguration.physicalConfiguration(
                                        URL + "?" + query, "test-user", "test-only"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateCompatiblePropertiesBecomeOneCanonicalSet() {
        assertThat(
                        UtcJdbcConfiguration.normalize(
                                URL
                                        + "?serverTimezone=UTC&connectionTimeZone=UTC&preserveInstants=TRUE&preserveInstants=true&forceConnectionTimeZoneToSession=true"))
                .isEqualTo(UtcJdbcConfiguration.normalize(URL));
    }

    @Test
    void timezoneCannotBeOverriddenThroughSessionVariablesOrPerHostParameters() {
        assertThatThrownBy(
                        () ->
                                UtcJdbcConfiguration.normalize(
                                        URL + "?sessionVariables=time_zone%3D%27%2B08%3A00%27"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionVariables");
        assertThatThrownBy(
                        () ->
                                UtcJdbcConfiguration.normalize(
                                        URL + "?sessionVariables=%60time_zone%60%3D%27%2B08%3A00%27"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionVariables");
        assertThatThrownBy(
                        () ->
                                UtcJdbcConfiguration.normalize(
                                        "jdbc:mysql://address=(host=127.0.0.1)(connectionTimeZone=Asia%2FShanghai)/business"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Per-host");
    }

    @Test
    void unrelatedEncodedParametersRemainUnchangedAndNormalizationIsIdempotent() {
        String query = "?sessionVariables=sql_mode%3D%27STRICT_TRANS_TABLES%27&applicationName=a%26b";
        String normalized = UtcJdbcConfiguration.normalize(URL + query);
        assertThat(normalized).startsWith(URL + query + "&");
        assertThat(UtcJdbcConfiguration.normalize(normalized)).isEqualTo(normalized);
    }

    @Test
    void invalidConfigurationErrorsDoNotEchoUrlOrCredentialValues() {
        assertThatThrownBy(
                        () ->
                                UtcJdbcConfiguration.normalize(
                                        URL + "?password=local-test-sentinel&connectionTimeZone=bad-zone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("local-test-sentinel")
                .hasMessageNotContaining(URL)
                .hasNoCause();
        assertThatThrownBy(() -> UtcJdbcConfiguration.normalize(URL + "?serverTimezone=%ZZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid JDBC URL property encoding")
                .hasNoCause();
    }
}
