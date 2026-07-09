package com.nageoffer.shortlink.agent.riskprofile.repository;

import com.nageoffer.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcGroupRiskProfileRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskJsonCodec jsonCodec;

    public JdbcGroupRiskProfileRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskJsonCodec());
    }

    public JdbcGroupRiskProfileRepository(JdbcTemplate jdbcTemplate, RiskJsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public void save(GroupRiskProfile profile) {
        jdbcTemplate.update("""
                        insert into t_agent_group_risk_profile (
                            gid,
                            profile_window_start,
                            profile_window_end,
                            total_short_links_scanned,
                            low_risk_count,
                            medium_risk_count,
                            high_risk_count,
                            watching_count,
                            disabled_count,
                            avg_risk_score,
                            max_risk_score,
                            group_risk_score,
                            group_risk_level,
                            group_reason_codes_json,
                            top_risk_short_links_json,
                            risk_trend_7d_json,
                            agent_summary
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                profile.gid(),
                Timestamp.valueOf(profile.profileWindowStart()),
                Timestamp.valueOf(profile.profileWindowEnd()),
                profile.totalShortLinksScanned(),
                profile.lowRiskCount(),
                profile.mediumRiskCount(),
                profile.highRiskCount(),
                profile.watchingCount(),
                profile.disabledCount(),
                profile.avgRiskScore(),
                profile.maxRiskScore(),
                profile.groupRiskScore(),
                profile.groupRiskLevel().name(),
                jsonCodec.toJson(reasonCodeNames(profile.groupReasonCodes())),
                jsonCodec.toJson(profile.topRiskShortLinks().stream()
                        .map(this::shortLinkSnapshot)
                        .toList()),
                jsonCodec.toJson(profile.riskTrend7d().stream()
                        .map(this::trendSnapshot)
                        .toList()),
                profile.agentSummary()
        );
    }

    public Optional<GroupRiskProfile> findLatestByGid(String gid) {
        List<GroupRiskProfile> profiles = jdbcTemplate.query("""
                        select *
                        from t_agent_group_risk_profile
                        where gid = ?
                        order by profile_window_end desc, id desc
                        limit 1
                        """,
                (rs, rowNum) -> mapProfile(rs),
                gid
        );
        return profiles.stream().findFirst();
    }

    public List<RiskTrendPoint> findTrend7d(String gid, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(6);
        List<RiskTrendPoint> points = jdbcTemplate.query("""
                        select profile_window_end,
                               group_risk_score,
                               group_risk_level
                        from t_agent_group_risk_profile
                        where gid = ?
                          and profile_window_end >= ?
                          and profile_window_end < ?
                        order by profile_window_end asc, id asc
                        """,
                (rs, rowNum) -> new RiskTrendPoint(
                        localDateTime(rs.getTimestamp("profile_window_end")).toLocalDate(),
                        rs.getInt("group_risk_score"),
                        RiskLevel.valueOf(rs.getString("group_risk_level"))
                ),
                gid,
                Timestamp.valueOf(startDate.atStartOfDay()),
                Timestamp.valueOf(endDate.plusDays(1).atStartOfDay())
        );
        Map<LocalDate, RiskTrendPoint> latestByDate = new LinkedHashMap<>();
        for (RiskTrendPoint point : points) {
            latestByDate.put(point.date(), point);
        }
        return List.copyOf(latestByDate.values());
    }

    private GroupRiskProfile mapProfile(ResultSet rs) throws SQLException {
        return new GroupRiskProfile(
                rs.getString("gid"),
                localDateTime(rs.getTimestamp("profile_window_start")),
                localDateTime(rs.getTimestamp("profile_window_end")),
                rs.getInt("total_short_links_scanned"),
                rs.getInt("low_risk_count"),
                rs.getInt("medium_risk_count"),
                rs.getInt("high_risk_count"),
                rs.getInt("watching_count"),
                rs.getInt("disabled_count"),
                doubleValue(rs, "avg_risk_score"),
                rs.getInt("max_risk_score"),
                rs.getInt("group_risk_score"),
                RiskLevel.valueOf(rs.getString("group_risk_level")),
                reasonCodes(rs.getString("group_reason_codes_json")),
                List.of(),
                trendPoints(rs.getString("risk_trend_7d_json")),
                rs.getString("agent_summary")
        );
    }

    private List<String> reasonCodeNames(List<RiskReasonCode> reasonCodes) {
        return reasonCodes.stream()
                .map(RiskReasonCode::name)
                .toList();
    }

    private List<RiskReasonCode> reasonCodes(String reasonCodesJson) {
        String[] values = jsonCodec.fromJson(reasonCodesJson, String[].class);
        return List.of(values).stream()
                .map(RiskReasonCode::valueOf)
                .toList();
    }

    private List<RiskTrendPoint> trendPoints(String trendJson) {
        Map[] rows = jsonCodec.fromJson(trendJson, Map[].class);
        return List.of(rows).stream()
                .map(row -> new RiskTrendPoint(
                        LocalDate.parse(String.valueOf(row.get("date"))),
                        intValue(row.get("riskScore")),
                        RiskLevel.valueOf(String.valueOf(row.get("riskLevel")))
                ))
                .toList();
    }

    private Map<String, Object> shortLinkSnapshot(com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gid", profile.gid());
        snapshot.put("domain", profile.domain());
        snapshot.put("shortUri", profile.shortUri());
        snapshot.put("fullShortUrl", profile.fullShortUrl());
        snapshot.put("profileWindowStart", profile.profileWindowStart().toString());
        snapshot.put("profileWindowEnd", profile.profileWindowEnd().toString());
        snapshot.put("riskScore", profile.riskScore());
        snapshot.put("riskLevel", profile.riskLevel().name());
        snapshot.put("reasonCodes", profile.reasonCodes().stream().map(RiskReasonCode::name).sorted().toList());
        return snapshot;
    }

    private Map<String, Object> trendSnapshot(RiskTrendPoint point) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("date", point.date().toString());
        snapshot.put("riskScore", point.riskScore());
        snapshot.put("riskLevel", point.riskLevel().name());
        return snapshot;
    }

    private double doubleValue(ResultSet rs, String columnName) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnName);
        return value == null ? 0D : value.doubleValue();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
