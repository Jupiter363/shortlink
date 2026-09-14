package com.jupiter.shortlink.command.config;

import com.zaxxer.hikari.HikariConfig;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** The business DATETIME columns contain UTC wall-clock values, including SQL defaults. */
final class UtcJdbcConfiguration {
    private static final String UTC_QUERY =
            "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true";
    private static final Pattern HOST_OVERRIDE =
            Pattern.compile(
                    "(?i)(?:connectionTimeZone|serverTimezone|forceConnectionTimeZoneToSession|preserveInstants|time_zone)\\s*[`\"]?\\s*=");
    private static final Pattern SESSION_TIME_ZONE =
            Pattern.compile("(?i)\\btime_zone\\s*[`\"]?\\s*=");

    private UtcJdbcConfiguration() {}

    static void apply(HikariConfig config, String url) {
        config.setJdbcUrl(normalize(url));
        config.addDataSourceProperty("connectionTimeZone", "UTC");
        config.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        config.addDataSourceProperty("preserveInstants", "true");
        // Also cover CURRENT_TIMESTAMP defaults when Hikari opens a new physical connection.
        config.setConnectionInitSql("SET SESSION time_zone = '+00:00'");
    }

    static String normalize(String url) {
        if (url == null || !url.startsWith("jdbc:mysql:"))
            throw new IllegalArgumentException("Command requires an explicit MySQL JDBC URL");
        int queryIndex = url.indexOf('?');
        String address = queryIndex < 0 ? url : url.substring(0, queryIndex);
        if (HOST_OVERRIDE.matcher(decode(address)).find())
            throw new IllegalArgumentException("Per-host JDBC time-zone overrides are not supported");
        var retained = new ArrayList<String>();
        if (queryIndex >= 0) {
            for (String parameter : url.substring(queryIndex + 1).split("&")) {
                if (parameter.isEmpty()) continue;
                String[] pair = parameter.split("=", 2);
                String key = decode(pair[0]).trim().toLowerCase(Locale.ROOT);
                switch (key) {
                    case "connectiontimezone", "servertimezone" -> {
                        String value = pair.length == 2 ? decode(pair[1]) : "";
                        if (!isUtc(value))
                            throw new IllegalArgumentException(
                                    "Command JDBC connectionTimeZone/serverTimezone must be UTC");
                    }
                    case "forceconnectiontimezonetosession", "preserveinstants" -> {
                        String value = pair.length == 2 ? decode(pair[1]) : "";
                        if (!"true".equalsIgnoreCase(value))
                            throw new IllegalArgumentException(
                                    "Command JDBC forceConnectionTimeZoneToSession and preserveInstants must be true");
                    }
                    case "sessionvariables" -> {
                        if (pair.length == 2 && SESSION_TIME_ZONE.matcher(decode(pair[1])).find())
                            throw new IllegalArgumentException(
                                    "Configure the UTC session through JDBC time-zone properties, not sessionVariables");
                        retained.add(parameter);
                    }
                    default -> retained.add(parameter);
                }
            }
        }
        // A JDBC URL can override Hikari's driver properties. Remove aliases/duplicates and
        // write one canonical copy to both sources, rather than depend on driver precedence.
        retained.add(UTC_QUERY);
        return address + "?" + String.join("&", retained);
    }

    private static boolean isUtc(String value) {
        try {
            var rules = ZoneId.of(value).getRules();
            return rules.isFixedOffset() && rules.getOffset(Instant.EPOCH).equals(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            return false;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // The URL can contain credentials: never include it or the driver's raw error.
            throw new IllegalArgumentException("Invalid JDBC URL property encoding");
        }
    }
}
