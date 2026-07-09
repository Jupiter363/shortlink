package com.nageoffer.shortlink.agent.riskprofile.repository;

import com.nageoffer.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class JdbcShortLinkRiskProfileRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskJsonCodec jsonCodec;

    public JdbcShortLinkRiskProfileRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskJsonCodec());
    }

    public JdbcShortLinkRiskProfileRepository(JdbcTemplate jdbcTemplate, RiskJsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public void save(ShortLinkRiskProfile profile) {
        ShortLinkRiskMetrics metrics = profile.metrics();
        jdbcTemplate.update("""
                        insert into t_agent_short_link_risk_profile (
                            gid,
                            domain,
                            short_uri,
                            full_short_url,
                            profile_window_start,
                            profile_window_end,
                            pv_2h,
                            uv_2h,
                            pv_24h,
                            uv_24h,
                            pv_7d,
                            uv_7d,
                            pv_growth_2h_vs_24h_avg,
                            top_ip_share,
                            top_visitor_share,
                            top_region_share,
                            top_device_share,
                            top_browser_share,
                            pv_per_uv,
                            peak_hour_share,
                            repeat_visit_ratio,
                            anomaly_score,
                            risk_score,
                            risk_level,
                            reason_codes_json,
                            profile_json
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                profile.gid(),
                profile.domain(),
                profile.shortUri(),
                profile.fullShortUrl(),
                Timestamp.valueOf(profile.profileWindowStart()),
                Timestamp.valueOf(profile.profileWindowEnd()),
                metrics.pv2h(),
                metrics.uv2h(),
                metrics.pv24h(),
                metrics.uv24h(),
                metrics.pv7d(),
                metrics.uv7d(),
                metrics.pvGrowth2hVs24hAvg(),
                metrics.topIpShare(),
                metrics.topVisitorShare(),
                metrics.topRegionShare(),
                metrics.topDeviceShare(),
                metrics.topBrowserShare(),
                metrics.pvPerUv(),
                metrics.peakHourShare(),
                metrics.repeatVisitRatio(),
                profile.anomalyScore(),
                profile.riskScore(),
                profile.riskLevel().name(),
                jsonCodec.toJson(reasonCodeNames(profile.reasonCodes())),
                jsonCodec.toJson(profileSnapshot(profile))
        );
    }

    public Optional<ShortLinkRiskProfile> findLatest(String domain, String shortUri) {
        List<ShortLinkRiskProfile> profiles = jdbcTemplate.query("""
                        select *
                        from t_agent_short_link_risk_profile
                        where domain = ?
                          and short_uri = ?
                        order by profile_window_end desc, id desc
                        limit 1
                        """,
                (rs, rowNum) -> mapProfile(rs),
                domain,
                shortUri
        );
        return profiles.stream().findFirst();
    }

    public List<ShortLinkRiskProfile> findLatestByGid(String gid) {
        List<ShortLinkRiskProfile> orderedProfiles = jdbcTemplate.query("""
                        select *
                        from t_agent_short_link_risk_profile
                        where gid = ?
                        order by profile_window_end desc, domain asc, short_uri asc, id desc
                        """,
                (rs, rowNum) -> mapProfile(rs),
                gid
        );
        Map<String, ShortLinkRiskProfile> latestByTarget = new LinkedHashMap<>();
        for (ShortLinkRiskProfile profile : orderedProfiles) {
            latestByTarget.putIfAbsent(profile.domain() + "\n" + profile.shortUri(), profile);
        }
        return new ArrayList<>(latestByTarget.values());
    }

    public List<ShortLinkRiskProfile> findTopRiskByGid(String gid, int limit) {
        return findLatestByGid(gid).stream()
                .sorted((first, second) -> {
                    int scoreCompare = Integer.compare(second.riskScore(), first.riskScore());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    int windowCompare = second.profileWindowEnd().compareTo(first.profileWindowEnd());
                    if (windowCompare != 0) {
                        return windowCompare;
                    }
                    return first.shortUri().compareTo(second.shortUri());
                })
                .limit(Math.max(0, limit))
                .toList();
    }

    private ShortLinkRiskProfile mapProfile(ResultSet rs) throws SQLException {
        Map<?, ?> snapshot = profileSnapshot(rs.getString("profile_json"));
        ShortLinkRiskMetrics metrics = new ShortLinkRiskMetrics(
                rs.getInt("pv_2h"),
                rs.getInt("uv_2h"),
                rs.getInt("pv_24h"),
                rs.getInt("uv_24h"),
                rs.getInt("pv_7d"),
                rs.getInt("uv_7d"),
                doubleValue(rs, "pv_growth_2h_vs_24h_avg"),
                doubleValue(rs, "top_ip_share"),
                doubleValue(rs, "top_visitor_share"),
                doubleValue(rs, "top_region_share"),
                doubleValue(rs, "top_device_share"),
                doubleValue(rs, "top_browser_share"),
                doubleValue(rs, "pv_per_uv"),
                doubleValue(rs, "peak_hour_share"),
                doubleValue(rs, "repeat_visit_ratio")
        );
        return new ShortLinkRiskProfile(
                rs.getString("gid"),
                rs.getString("domain"),
                rs.getString("short_uri"),
                rs.getString("full_short_url"),
                localDateTime(rs.getTimestamp("profile_window_start")),
                localDateTime(rs.getTimestamp("profile_window_end")),
                metrics,
                rs.getInt("anomaly_score"),
                rs.getInt("risk_score"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                reasonCodes(rs.getString("reason_codes_json")),
                watchStatus(snapshot),
                stringList(snapshot.get("latestPolicyActions")),
                stringValue(snapshot.get("latestAgentSummary"))
        );
    }

    private List<String> reasonCodeNames(Set<RiskReasonCode> reasonCodes) {
        return reasonCodes.stream()
                .map(RiskReasonCode::name)
                .sorted()
                .toList();
    }

    private Set<RiskReasonCode> reasonCodes(String reasonCodesJson) {
        String[] values = jsonCodec.fromJson(reasonCodesJson, String[].class);
        return List.of(values).stream()
                .map(RiskReasonCode::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, Object> profileSnapshot(ShortLinkRiskProfile profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gid", profile.gid());
        snapshot.put("domain", profile.domain());
        snapshot.put("shortUri", profile.shortUri());
        snapshot.put("fullShortUrl", profile.fullShortUrl());
        snapshot.put("profileWindowStart", profile.profileWindowStart().toString());
        snapshot.put("profileWindowEnd", profile.profileWindowEnd().toString());
        snapshot.put("metrics", profile.metrics());
        snapshot.put("anomalyScore", profile.anomalyScore());
        snapshot.put("riskScore", profile.riskScore());
        snapshot.put("riskLevel", profile.riskLevel().name());
        snapshot.put("reasonCodes", reasonCodeNames(profile.reasonCodes()));
        snapshot.put("watchStatus", profile.watchStatus().name());
        snapshot.put("latestPolicyActions", profile.latestPolicyActions());
        snapshot.put("latestAgentSummary", profile.latestAgentSummary());
        return snapshot;
    }

    private Map<?, ?> profileSnapshot(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) {
            return Map.of();
        }
        try {
            return jsonCodec.fromJson(profileJson, Map.class);
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private RiskWatchStatus watchStatus(Map<?, ?> snapshot) {
        String value = stringValue(snapshot.get("watchStatus"));
        if (value.isBlank()) {
            return RiskWatchStatus.NONE;
        }
        try {
            return RiskWatchStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return RiskWatchStatus.NONE;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleValue(ResultSet rs, String columnName) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnName);
        return value == null ? null : value.doubleValue();
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
