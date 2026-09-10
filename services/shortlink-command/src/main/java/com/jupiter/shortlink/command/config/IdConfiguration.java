package com.jupiter.shortlink.command.config;

import com.jupiter.shortlink.id.*;
import com.zaxxer.hikari.*;

import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;

import java.time.Duration;
import java.util.HexFormat;

import javax.sql.DataSource;

@Configuration
public class IdConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource idDataSource(
            @Value("${shortlink.database.url}") String url,
            @Value("${shortlink.database.username}") String user,
            @Value("${shortlink.database.password}") String password) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(user);
        c.setPassword(password);
        c.setAutoCommit(true);
        c.setMaximumPoolSize(2);
        c.setMinimumIdle(0);
        c.setConnectionTimeout(1000);
        c.setPoolName("command-id-reservations");
        return new HikariDataSource(c);
    }

    @Bean(destroyMethod = "close")
    public SegmentIdGenerator idGenerator(
            @Qualifier("idDataSource") DataSource ds,
            @Value("${shortlink.database.catalog}") String catalog,
            @Value("${shortlink.id.dynamic-step-enabled:false}") boolean dynamic,
            @Value("${shortlink.id.initial-step:10000}") int initial,
            @Value("${shortlink.id.min-step:10000}") int min,
            @Value("${shortlink.id.max-step:1000000}") int max) {
        StepPolicy step =
                new StepPolicy(
                        dynamic,
                        initial,
                        min,
                        max,
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30),
                        2,
                        Duration.ofMinutes(5));
        GeneratorOptions options =
                new GeneratorOptions(
                        step,
                        20,
                        50000,
                        128,
                        Duration.ofSeconds(3),
                        Duration.ofMillis(100),
                        Duration.ofSeconds(3),
                        3,
                        Duration.ofMillis(50));
        return new SegmentIdGenerator(
                new JdbcSegmentStore(
                        ds,
                        new JdbcOptions(catalog, Duration.ofSeconds(2), Duration.ofSeconds(3), 2)),
                options);
    }

    @Bean
    public ShortCodeCodec shortCodeCodec(
            @Value("${shortlink.id.fixed-key-hex}") String key,
            @Qualifier("physicalDataSource") DataSource source) {
        byte[] bytes = HexFormat.of().parseHex(key);
        ShortCodeCodec codec = new ShortCodeCodec(bytes);
        String fingerprint;
        try {
            fingerprint =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.setQueryTimeout(3);
        String version = "leaf-feistel52-base62-v1";
        jdbc.update(
                "INSERT INTO t_id_namespace(biz_tag,codec_version,key_fingerprint) VALUES"
                    + " ('shortlink_global',?,?) ON DUPLICATE KEY UPDATE biz_tag=VALUES(biz_tag)",
                version,
                fingerprint);
        var stored =
                jdbc.queryForMap(
                        "SELECT codec_version,key_fingerprint FROM t_id_namespace WHERE"
                                + " biz_tag='shortlink_global'");
        if (!version.equals(stored.get("codec_version"))
                || !fingerprint.equals(stored.get("key_fingerprint")))
            throw new IllegalStateException(
                    "Short-code namespace configuration differs from its immutable registry");
        return codec;
    }
}
